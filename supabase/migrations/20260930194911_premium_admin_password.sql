create table if not exists private.premium_admin_credentials (
    email text primary key check (email = lower(btrim(email))),
    password_hash text not null
);

revoke all on private.premium_admin_credentials from public, anon, authenticated;
grant select on private.premium_admin_credentials to service_role;

create or replace function public.verify_premium_admin_password(
    candidate_email text,
    candidate_password text
)
returns boolean
language sql
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from private.premium_admin_credentials as credentials
        join private.premium_admins as admins
          on admins.email = credentials.email
        where credentials.email = lower(btrim(candidate_email))
          and credentials.password_hash = extensions.crypt(
              candidate_password,
              credentials.password_hash
          )
    );
$$;

revoke all on function public.verify_premium_admin_password(text, text)
    from public, anon, authenticated;
grant execute on function public.verify_premium_admin_password(text, text)
    to service_role;
