create table if not exists private.premium_admin_access (
    email text primary key references private.premium_admins(email) on update cascade on delete cascade,
    role text not null default 'admin' check (role in ('owner', 'admin')),
    permissions text[] not null default array[]::text[],
    enabled boolean not null default true,
    created_at timestamptz not null default now(),
    created_by text,
    updated_at timestamptz not null default now()
);
alter table private.premium_admin_access enable row level security;
revoke all on private.premium_admin_access from public, anon, authenticated;
grant select, insert, update, delete on private.premium_admin_access to service_role;

-- Existing allowlisted accounts retain full control as owners.
insert into private.premium_admin_access(email, role, permissions)
select email, 'owner', array['review_payments','manage_premium','manage_settings','view_analytics','manage_admins']
from private.premium_admins
on conflict (email) do nothing;

create or replace function public.premium_admin_permission(p_email text, p_permission text)
returns boolean language sql security definer set search_path = '' as $$
  select coalesce((select enabled and (role='owner' or p_permission = any(permissions))
    from private.premium_admin_access where email=lower(btrim(p_email))), false)
$$;

create or replace function public.get_premium_admin_profile(p_email text)
returns jsonb language sql security definer set search_path = '' as $$
  select jsonb_build_object('email', email, 'role', role, 'permissions', permissions)
  from private.premium_admin_access where email=lower(btrim(p_email)) and enabled
$$;

create or replace function public.update_premium_admin_credentials(
 p_email text, p_password text, p_new_email text, p_new_password text
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_email text := lower(btrim(coalesce(p_email,''))); v_new_email text := lower(btrim(coalesce(p_new_email,''))); v_hash text;
begin
 if not public.verify_premium_admin_password(v_email,p_password) then raise exception 'Current password is incorrect'; end if;
 if v_new_email <> '' and v_new_email !~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$' then raise exception 'Enter a valid email'; end if;
 if length(coalesce(p_new_password,'')) > 0 and (length(p_new_password) < 12 or length(p_new_password) > 256) then raise exception 'New password must contain 12 to 256 characters'; end if;
 if length(v_new_email) > 254 then raise exception 'Email is too long'; end if;
 if v_new_email <> '' then
   update private.premium_admins set email=v_new_email where email=v_email;
   if not found then raise exception 'Admin account not found'; end if;
   update private.premium_admin_credentials set email=v_new_email where email=v_email;
 end if;
 if length(coalesce(p_new_password,'')) > 0 then
   v_hash := extensions.crypt(p_new_password, extensions.gen_salt('bf'));
   update private.premium_admin_credentials set password_hash=v_hash where email=case when v_new_email<>'' then v_new_email else v_email end;
 end if;
 return public.get_premium_admin_profile(case when v_new_email<>'' then v_new_email else v_email end);
end $$;

create or replace function public.manage_premium_admin(
 p_actor text, p_action text, p_email text default null, p_permissions text[] default array[]::text[], p_password text default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_actor text := lower(btrim(coalesce(p_actor,''))); v_email text := lower(btrim(coalesce(p_email,''))); v_permissions text[] := coalesce(p_permissions,array[]::text[]); v_allowed text[] := array['review_payments','manage_premium','manage_settings','view_analytics'];
begin
 if not exists(select 1 from private.premium_admin_access where email=v_actor and role='owner' and enabled) then raise exception 'Owner permission required'; end if;
 if p_action='list' then
   return coalesce((select jsonb_agg(jsonb_build_object('email',email,'role',role,'permissions',permissions,'enabled',enabled) order by email) from private.premium_admin_access),'[]'::jsonb);
 elsif p_action='create' then
   if v_email !~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$' or length(v_email)>254 or length(coalesce(p_password,''))<12 or length(p_password)>256 then raise exception 'Valid email and password of at least 12 characters are required'; end if;
   if exists(select 1 from private.premium_admins where email=v_email) then raise exception 'That admin email already exists'; end if;
   if not (v_permissions <@ v_allowed) then raise exception 'Invalid permission'; end if;
   insert into private.premium_admins(email) values(v_email);
   insert into private.premium_admin_credentials(email,password_hash) values(v_email,extensions.crypt(p_password,extensions.gen_salt('bf')));
   insert into private.premium_admin_access(email,role,permissions,created_by) values(v_email,'admin',v_permissions,v_actor);
   return public.get_premium_admin_profile(v_email);
 elsif p_action='permissions' then
   if not (v_permissions <@ v_allowed) then raise exception 'Invalid permission'; end if;
   update private.premium_admin_access set permissions=v_permissions,updated_at=now() where email=v_email and role='admin';
   if not found then raise exception 'Delegated admin not found'; end if;
   return public.get_premium_admin_profile(v_email);
 elsif p_action='remove' then
   delete from private.premium_admins where email=v_email and email<>v_actor;
   if not found then raise exception 'Cannot remove this admin account'; end if;
   delete from private.premium_admin_credentials where email=v_email;
   return jsonb_build_object('removed',true);
 end if;
 raise exception 'Unknown admin management action';
end $$;

revoke all on function public.premium_admin_permission(text,text) from public,anon,authenticated;
revoke all on function public.get_premium_admin_profile(text) from public,anon,authenticated;
revoke all on function public.update_premium_admin_credentials(text,text,text,text) from public,anon,authenticated;
revoke all on function public.manage_premium_admin(text,text,text,text[],text) from public,anon,authenticated;
grant execute on function public.premium_admin_permission(text,text), public.get_premium_admin_profile(text), public.update_premium_admin_credentials(text,text,text,text), public.manage_premium_admin(text,text,text,text[],text) to service_role;
