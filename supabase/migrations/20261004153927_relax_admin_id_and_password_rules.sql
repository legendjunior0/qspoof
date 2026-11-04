-- Admin sign-in uses an administrator ID stored in the legacy `email` columns.
-- IDs no longer need to be email addresses; passwords need only be non-empty.
create or replace function public.update_premium_admin_credentials(
 p_email text, p_password text, p_new_email text, p_new_password text
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
 v_email text := lower(btrim(coalesce(p_email,'')));
 v_new_email text := lower(btrim(coalesce(p_new_email,'')));
 v_hash text;
begin
 if not public.verify_premium_admin_password(v_email,p_password) then
   raise exception 'Current password is incorrect';
 end if;
 if length(v_new_email) > 254 then raise exception 'Admin ID is too long'; end if;
 if length(coalesce(p_new_password,'')) > 256 then
   raise exception 'Password must be 256 characters or fewer';
 end if;
 if v_new_email <> '' then
   update private.premium_admins set email=v_new_email where email=v_email;
   if not found then raise exception 'Admin account not found'; end if;
   update private.premium_admin_credentials set email=v_new_email where email=v_email;
 end if;
 if length(coalesce(p_new_password,'')) > 0 then
   v_hash := extensions.crypt(p_new_password, extensions.gen_salt('bf'));
   update private.premium_admin_credentials
   set password_hash=v_hash
   where email=case when v_new_email<>'' then v_new_email else v_email end;
 end if;
 return public.get_premium_admin_profile(case when v_new_email<>'' then v_new_email else v_email end);
end $$;

create or replace function public.manage_premium_admin(
 p_actor text, p_action text, p_email text default null,
 p_permissions text[] default array[]::text[], p_password text default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
 v_actor text := lower(btrim(coalesce(p_actor,'')));
 v_email text := lower(btrim(coalesce(p_email,'')));
 v_permissions text[] := coalesce(p_permissions,array[]::text[]);
 v_allowed text[] := array['review_payments','manage_premium','manage_settings','view_analytics'];
begin
 if not exists(
   select 1 from private.premium_admin_access
   where email=v_actor and role='owner' and enabled
 ) then raise exception 'Owner permission required'; end if;

 if p_action='list' then
   return coalesce((select jsonb_agg(jsonb_build_object(
     'email',email,'role',role,'permissions',permissions,'enabled',enabled
   ) order by email) from private.premium_admin_access),'[]'::jsonb);
 elsif p_action='create' then
   if v_email='' or length(v_email)>254
      or coalesce(length(p_password),0) not between 1 and 256 then
     raise exception 'Admin ID and a non-empty password (up to 256 characters) are required';
   end if;
   if exists(select 1 from private.premium_admins where email=v_email) then
     raise exception 'That admin ID already exists';
   end if;
   if not (v_permissions <@ v_allowed) then raise exception 'Invalid permission'; end if;
   insert into private.premium_admins(email) values(v_email);
   insert into private.premium_admin_credentials(email,password_hash)
   values(v_email,extensions.crypt(p_password,extensions.gen_salt('bf')));
   insert into private.premium_admin_access(email,role,permissions,created_by)
   values(v_email,'admin',v_permissions,v_actor);
   return public.get_premium_admin_profile(v_email);
 elsif p_action='permissions' then
   if not (v_permissions <@ v_allowed) then raise exception 'Invalid permission'; end if;
   update private.premium_admin_access set permissions=v_permissions,updated_at=now()
   where email=v_email and role='admin';
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

revoke all on function public.update_premium_admin_credentials(text,text,text,text)
  from public,anon,authenticated;
revoke all on function public.manage_premium_admin(text,text,text,text[],text)
  from public,anon,authenticated;
grant execute on function public.update_premium_admin_credentials(text,text,text,text),
  public.manage_premium_admin(text,text,text,text[],text) to service_role;
