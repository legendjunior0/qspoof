create table if not exists private.premium_admins (
    email text primary key check (email = lower(btrim(email)))
);

revoke all on private.premium_admins from public, anon, authenticated;
grant select on private.premium_admins to service_role;

create or replace function public.is_premium_admin(candidate_email text)
returns boolean
language sql
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from private.premium_admins as admins
        where admins.email = lower(btrim(candidate_email))
    );
$$;

revoke all on function public.is_premium_admin(text) from public, anon, authenticated;
grant execute on function public.is_premium_admin(text) to service_role;
