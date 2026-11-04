alter table private.premium_config
    add column if not exists premium_only_access boolean not null default false;

create or replace function public.get_premium_public_settings()
returns jsonb
language sql
security definer
set search_path = ''
as $$
    select pg_catalog.jsonb_build_object(
        'adsEnabled', ads_enabled,
        'premiumOnlyAccess', premium_only_access
    )
    from private.premium_config
    where singleton = true;
$$;

create or replace function public.set_premium_only_access(enabled boolean)
returns boolean
language sql
security definer
set search_path = ''
as $$
    update private.premium_config
    set premium_only_access = enabled
    where singleton = true
    returning premium_only_access;
$$;

revoke all on function public.set_premium_only_access(boolean)
    from public, anon, authenticated;
grant execute on function public.set_premium_only_access(boolean)
    to service_role;