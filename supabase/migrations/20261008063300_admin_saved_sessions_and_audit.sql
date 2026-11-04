-- Move admin sign-in to short-lived, per-browser sessions, use email OTP once
-- to trust a browser, and retain a bounded seven-day audit trail.

alter table private.premium_admin_access
    add column if not exists display_name text not null default '';

alter table private.premium_admin_credentials
    add column if not exists password_initialized boolean not null default true;

create table if not exists private.premium_admin_trusted_browsers (
    email text not null references private.premium_admins(email) on update cascade on delete cascade,
    browser_hash text not null check (browser_hash ~ '^[0-9a-f]{64}$'),
    trusted_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    last_ip inet,
    primary key (email, browser_hash)
);

create table if not exists private.premium_admin_sessions (
    session_hash text primary key check (session_hash ~ '^[0-9a-f]{64}$'),
    email text not null references private.premium_admins(email) on update cascade on delete cascade,
    browser_hash text not null check (browser_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    last_seen_at timestamptz not null default now(),
    last_ip inet
);

create index if not exists premium_admin_sessions_expiry_idx
    on private.premium_admin_sessions (expires_at);
create index if not exists premium_admin_sessions_email_idx
    on private.premium_admin_sessions (email, expires_at desc);

create table if not exists private.premium_admin_audit (
    id bigint generated always as identity primary key,
    actor_email text not null,
    action text not null,
    target_email text,
    created_at timestamptz not null default now(),
    ip_address inet
);

create index if not exists premium_admin_audit_created_idx
    on private.premium_admin_audit (created_at desc, id desc);

alter table private.premium_admin_trusted_browsers enable row level security;
alter table private.premium_admin_sessions enable row level security;
alter table private.premium_admin_audit enable row level security;
revoke all on private.premium_admin_trusted_browsers,
    private.premium_admin_sessions, private.premium_admin_audit
    from public, anon, authenticated;
grant select, insert, update, delete on private.premium_admin_trusted_browsers,
    private.premium_admin_sessions to service_role;
grant select, insert, delete on private.premium_admin_audit to service_role;
grant usage, select on sequence private.premium_admin_audit_id_seq to service_role;

-- Only this fixed account is the owner. Preserve any former owner as a regular
-- admin, but strip the ability to create or manage other administrator accounts.
update private.premium_admin_access
   set role = 'admin',
       permissions = array_remove(permissions, 'manage_admins'),
       updated_at = now()
 where email <> 'adityakumar.as12345@gmail.com' and role = 'owner';

insert into private.premium_admins(email)
values ('adityakumar.as12345@gmail.com')
on conflict (email) do nothing;

insert into private.premium_admin_credentials(email, password_hash, password_initialized)
values (
    'adityakumar.as12345@gmail.com',
    extensions.crypt(pg_catalog.gen_random_uuid()::text, extensions.gen_salt('bf')),
    false
)
on conflict (email) do nothing;

insert into private.premium_admin_access(email, role, permissions, display_name, created_by)
values (
    'adityakumar.as12345@gmail.com', 'owner',
    array['review_payments','manage_premium','manage_settings','view_analytics','manage_admins'],
    'Aditya Kumar', 'system'
)
on conflict (email) do update set
    role = 'owner',
    permissions = array['review_payments','manage_premium','manage_settings','view_analytics','manage_admins'],
    display_name = coalesce(nullif(private.premium_admin_access.display_name, ''), excluded.display_name),
    enabled = true,
    updated_at = now();

update private.premium_admin_access
   set display_name = split_part(email, '@', 1)
 where display_name = '';

create or replace function public.get_premium_admin_profile(p_email text)
returns jsonb
language sql
security definer
set search_path = ''
as $$
  select pg_catalog.jsonb_build_object(
      'email', access.email,
      'name', access.display_name,
      'role', access.role,
      'permissions', access.permissions
  )
  from private.premium_admin_access access
  where access.email = lower(btrim(p_email)) and access.enabled;
$$;

create or replace function public.get_premium_admin_password_state(p_email text)
returns jsonb
language sql
security definer
set search_path = ''
as $$
  select pg_catalog.jsonb_build_object(
      'email', access.email,
      'name', access.display_name,
      'role', access.role,
      'passwordInitialized', credentials.password_initialized
  )
  from private.premium_admin_access access
  join private.premium_admin_credentials credentials using (email)
  where access.email = lower(btrim(p_email)) and access.enabled;
$$;

create or replace function public.is_premium_admin_browser_trusted(p_email text, p_browser_hash text)
returns boolean
language sql
security definer
set search_path = ''
as $$
  select exists (
    select 1 from private.premium_admin_trusted_browsers trusted
    join private.premium_admin_access access using (email)
    where trusted.email = lower(btrim(p_email))
      and trusted.browser_hash = p_browser_hash
      and access.enabled
  );
$$;

create or replace function public.create_premium_admin_session(
    p_email text, p_session_hash text, p_browser_hash text,
    p_ip text default null, p_trust_browser boolean default false
) returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_email text := lower(btrim(coalesce(p_email, '')));
    v_ip inet;
    v_profile jsonb;
begin
    if p_session_hash is null or p_browser_hash is null
       or p_session_hash !~ '^[0-9a-f]{64}$' or p_browser_hash !~ '^[0-9a-f]{64}$' then
        raise exception 'Invalid admin session';
    end if;
    if p_ip is not null then v_ip := p_ip::inet; end if;
    v_profile := public.get_premium_admin_profile(v_email);
    if v_profile is null then raise exception 'Admin account is disabled'; end if;
    if (v_profile->>'email') = 'adityakumar.as12345@gmail.com'
       and coalesce((public.get_premium_admin_password_state(v_email)->>'passwordInitialized')::boolean, true) = false then
        raise exception 'Master password setup is incomplete';
    end if;
    if p_trust_browser then
        insert into private.premium_admin_trusted_browsers(email, browser_hash, last_ip)
        values (v_email, p_browser_hash, v_ip)
        on conflict (email, browser_hash) do update
          set last_seen_at = now(), last_ip = excluded.last_ip;
        delete from private.premium_admin_trusted_browsers trusted
         where trusted.email = v_email and trusted.browser_hash in (
           select browser_hash from private.premium_admin_trusted_browsers
            where email = v_email order by last_seen_at desc offset 10
         );
    else
        update private.premium_admin_trusted_browsers
           set last_seen_at = now(), last_ip = v_ip
         where email = v_email and browser_hash = p_browser_hash;
    end if;
    delete from private.premium_admin_sessions where email = v_email and expires_at <= now();
    insert into private.premium_admin_sessions(session_hash, email, browser_hash, expires_at, last_ip)
    values (p_session_hash, v_email, p_browser_hash, now() + interval '24 hours', v_ip);
    return v_profile || pg_catalog.jsonb_build_object('expiresAt', now() + interval '24 hours');
end;
$$;

create or replace function public.get_premium_admin_session(
    p_session_hash text, p_browser_hash text, p_ip text default null
) returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_email text;
    v_ip inet;
    v_profile jsonb;
    v_expires_at timestamptz;
begin
    if p_session_hash !~ '^[0-9a-f]{64}$' or p_browser_hash !~ '^[0-9a-f]{64}$' then return null; end if;
    if p_ip is not null then v_ip := p_ip::inet; end if;
    update private.premium_admin_sessions session
       set last_seen_at = now(), last_ip = v_ip
      from private.premium_admin_access access
     where session.session_hash = p_session_hash
       and session.browser_hash = p_browser_hash
       and session.expires_at > now()
       and access.email = session.email and access.enabled
     returning session.email, session.expires_at into v_email, v_expires_at;
    if v_email is null then return null; end if;
    v_profile := public.get_premium_admin_profile(v_email);
    return v_profile || pg_catalog.jsonb_build_object('expiresAt', v_expires_at);
end;
$$;

create or replace function public.revoke_premium_admin_session(p_session_hash text)
returns boolean
language sql
security definer
set search_path = ''
as $$
  with deleted as (
    delete from private.premium_admin_sessions where session_hash = p_session_hash
    returning 1
  ) select exists(select 1 from deleted);
$$;

create or replace function public.set_qspoof_master_password(p_password text, p_name text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_name text := btrim(regexp_replace(coalesce(p_name, ''), '\s+', ' ', 'g'));
begin
    if length(coalesce(p_password, '')) < 12 or length(p_password) > 256 then
        raise exception 'Choose a password at least 12 characters long';
    end if;
    if length(v_name) not between 1 and 80 then raise exception 'Enter your name'; end if;
    update private.premium_admin_credentials
       set password_hash = extensions.crypt(p_password, extensions.gen_salt('bf')),
           password_initialized = true
     where email = 'adityakumar.as12345@gmail.com' and password_initialized = false;
    if not found then raise exception 'Master password is already configured'; end if;
    update private.premium_admin_access set display_name = v_name, updated_at = now()
     where email = 'adityakumar.as12345@gmail.com' and role = 'owner' and enabled;
    return public.get_premium_admin_profile('adityakumar.as12345@gmail.com');
end;
$$;

create or replace function public.update_premium_admin_credentials(
    p_email text, p_password text, p_new_email text, p_new_password text, p_name text
) returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_email text := lower(btrim(coalesce(p_email, '')));
    v_new_email text := lower(btrim(coalesce(p_new_email, '')));
    v_name text := btrim(regexp_replace(coalesce(p_name, ''), '\s+', ' ', 'g'));
    v_next_email text;
begin
    if not public.verify_premium_admin_password(v_email, p_password) then
        raise exception 'Current password is incorrect';
    end if;
    if length(v_new_email) > 254 or (v_new_email <> '' and v_new_email !~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$') then
        raise exception 'Enter a valid email';
    end if;
    if v_email = 'adityakumar.as12345@gmail.com' and v_new_email <> '' and v_new_email <> v_email then
        raise exception 'The master account email is fixed';
    end if;
    if length(coalesce(p_new_password, '')) > 0 and (length(p_new_password) < 12 or length(p_new_password) > 256) then
        raise exception 'New password must be at least 12 characters';
    end if;
    if length(v_name) not between 1 and 80 then raise exception 'Enter a name between 1 and 80 characters'; end if;
    v_next_email := case when v_new_email <> '' then v_new_email else v_email end;
    if v_new_email <> '' and v_new_email <> v_email then
        update private.premium_admins set email = v_new_email where email = v_email;
        if not found then raise exception 'Admin account not found'; end if;
        update private.premium_admin_credentials set email = v_new_email where email = v_email;
        delete from private.premium_admin_sessions where email = v_new_email;
        delete from private.premium_admin_trusted_browsers where email = v_new_email;
    end if;
    update private.premium_admin_access
       set display_name = v_name, updated_at = now()
     where email = v_next_email;
    if length(coalesce(p_new_password, '')) > 0 then
        update private.premium_admin_credentials
           set password_hash = extensions.crypt(p_new_password, extensions.gen_salt('bf')),
               password_initialized = true
         where email = v_next_email;
        delete from private.premium_admin_sessions where email = v_next_email;
        delete from private.premium_admin_trusted_browsers where email = v_next_email;
    end if;
    return public.get_premium_admin_profile(v_next_email);
end;
$$;

create or replace function public.manage_premium_admin(
    p_actor text, p_action text, p_email text default null,
    p_permissions text[] default array[]::text[], p_password text default null,
    p_name text default null
) returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_actor text := lower(btrim(coalesce(p_actor, '')));
    v_email text := lower(btrim(coalesce(p_email, '')));
    v_name text := btrim(regexp_replace(coalesce(p_name, ''), '\s+', ' ', 'g'));
    v_permissions text[] := coalesce(p_permissions, array[]::text[]);
    v_allowed text[] := array['review_payments','manage_premium','manage_settings','view_analytics'];
begin
    if v_actor <> 'adityakumar.as12345@gmail.com'
       or not exists (select 1 from private.premium_admin_access
                      where email = v_actor and role = 'owner' and enabled) then
        raise exception 'Master administrator permission required';
    end if;
    if p_action = 'list' then
        return coalesce((select pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
            'email', email, 'name', display_name, 'role', role,
            'permissions', permissions, 'enabled', enabled
        ) order by role, display_name, email) from private.premium_admin_access), '[]'::jsonb);
    elsif p_action = 'create' then
        if v_email !~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$' or length(v_email) > 254
           or length(coalesce(p_password, '')) < 12 or length(p_password) > 256
           or length(v_name) not between 1 and 80 then
            raise exception 'Enter an admin name, valid email, and password of at least 12 characters';
        end if;
        if exists(select 1 from private.premium_admins where email = v_email) then
            raise exception 'That admin email already exists';
        end if;
        if not (v_permissions <@ v_allowed) then raise exception 'Invalid permission'; end if;
        insert into private.premium_admins(email) values (v_email);
        insert into private.premium_admin_credentials(email, password_hash, password_initialized)
        values (v_email, extensions.crypt(p_password, extensions.gen_salt('bf')), true);
        insert into private.premium_admin_access(email, role, permissions, display_name, created_by)
        values (v_email, 'admin', v_permissions, v_name, v_actor);
        return public.get_premium_admin_profile(v_email);
    elsif p_action = 'permissions' then
        if not (v_permissions <@ v_allowed) then raise exception 'Invalid permission'; end if;
        update private.premium_admin_access set permissions = v_permissions, updated_at = now()
         where email = v_email and role = 'admin';
        if not found then raise exception 'Delegated admin not found'; end if;
        return public.get_premium_admin_profile(v_email);
    elsif p_action = 'reset-password' then
        if length(coalesce(p_password, '')) < 12 or length(p_password) > 256 then
            raise exception 'New password must be at least 12 characters';
        end if;
        update private.premium_admin_credentials
           set password_hash = extensions.crypt(p_password, extensions.gen_salt('bf')),
               password_initialized = true
         where email = v_email and exists (
             select 1 from private.premium_admin_access where email = v_email and role = 'admin' and enabled
         );
        if not found then raise exception 'Delegated admin not found'; end if;
        delete from private.premium_admin_sessions where email = v_email;
        delete from private.premium_admin_trusted_browsers where email = v_email;
        return pg_catalog.jsonb_build_object('reset', true);
    elsif p_action = 'remove' then
        if v_email = 'adityakumar.as12345@gmail.com' then raise exception 'The master account cannot be removed'; end if;
        delete from private.premium_admins where email = v_email;
        if not found then raise exception 'Cannot remove this admin account'; end if;
        delete from private.premium_admin_credentials where email = v_email;
        return pg_catalog.jsonb_build_object('removed', true);
    end if;
    raise exception 'Unknown admin management action';
end;
$$;

create or replace function public.record_premium_admin_audit(
    p_actor text, p_action text, p_target text default null, p_ip text default null
) returns void
language plpgsql
security definer
set search_path = ''
as $$
declare v_ip inet;
begin
    if p_ip is not null then v_ip := p_ip::inet; end if;
    insert into private.premium_admin_audit(actor_email, action, target_email, ip_address)
    values (lower(btrim(p_actor)), left(p_action, 80), nullif(lower(btrim(p_target)), ''), v_ip);
    delete from private.premium_admin_audit where created_at < now() - interval '7 days';
    delete from private.premium_admin_audit
     where id not in (select id from private.premium_admin_audit order by id desc limit 10000);
end;
$$;

create or replace function public.get_premium_admin_audit(p_limit integer default 300)
returns jsonb
language sql
security definer
set search_path = ''
as $$
  select coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
      'actorEmail', actor_email, 'action', action, 'targetEmail', target_email,
      'createdAt', created_at, 'ipAddress', ip_address::text
  ) order by created_at desc, id desc), '[]'::jsonb)
  from (
      select actor_email, action, target_email, created_at, ip_address, id
      from private.premium_admin_audit
      where created_at >= now() - interval '7 days'
      order by created_at desc, id desc
      limit greatest(1, least(coalesce(p_limit, 300), 500))
  ) recent;
$$;

revoke all on function public.get_premium_admin_profile(text),
    public.get_premium_admin_password_state(text),
    public.is_premium_admin_browser_trusted(text, text),
    public.create_premium_admin_session(text, text, text, text, boolean),
    public.get_premium_admin_session(text, text, text),
    public.revoke_premium_admin_session(text),
    public.set_qspoof_master_password(text, text),
    public.update_premium_admin_credentials(text, text, text, text, text),
    public.manage_premium_admin(text, text, text, text[], text, text),
    public.record_premium_admin_audit(text, text, text, text),
    public.get_premium_admin_audit(integer)
    from public, anon, authenticated;

grant execute on function public.get_premium_admin_profile(text),
    public.get_premium_admin_password_state(text),
    public.is_premium_admin_browser_trusted(text, text),
    public.create_premium_admin_session(text, text, text, text, boolean),
    public.get_premium_admin_session(text, text, text),
    public.revoke_premium_admin_session(text),
    public.set_qspoof_master_password(text, text),
    public.update_premium_admin_credentials(text, text, text, text, text),
    public.manage_premium_admin(text, text, text, text[], text, text),
    public.record_premium_admin_audit(text, text, text, text),
    public.get_premium_admin_audit(integer)
    to service_role;

do $$
declare v_job_id bigint;
begin
    if to_regclass('cron.job') is not null then
        for v_job_id in execute 'select jobid from cron.job where jobname = ''qspoof-prune-admin-audit''' loop
            perform cron.unschedule(v_job_id);
        end loop;
        perform cron.schedule(
            'qspoof-prune-admin-audit', '17 * * * *',
            'delete from private.premium_admin_audit where created_at < now() - interval ''7 days'''
        );
        perform cron.schedule(
            'qspoof-prune-admin-sessions', '23 * * * *',
            'delete from private.premium_admin_sessions where expires_at <= now()'
        );
        perform cron.schedule(
            'qspoof-prune-admin-trusted-browsers', '29 * * * *',
            'delete from private.premium_admin_trusted_browsers where last_seen_at < now() - interval ''90 days'''
        );
    end if;
end $$;
