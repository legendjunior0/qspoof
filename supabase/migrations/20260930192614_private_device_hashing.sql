create schema if not exists private;
revoke all on schema private from public, anon, authenticated;
grant usage on schema private to service_role;

create table if not exists private.premium_config (
    singleton boolean primary key default true check (singleton),
    device_hash_secret text not null
);

revoke all on private.premium_config from public, anon, authenticated;
grant select on private.premium_config to service_role;

insert into private.premium_config (singleton, device_hash_secret)
values (true, encode(extensions.gen_random_bytes(32), 'hex'))
on conflict (singleton) do nothing;

create or replace function public.hash_premium_device_id(android_id text)
returns text
language sql
security definer
set search_path = ''
as $$
    select encode(
        extensions.hmac(
            convert_to(android_id, 'UTF8'),
            convert_to(config.device_hash_secret, 'UTF8'),
            'sha256'
        ),
        'hex'
    )
    from private.premium_config as config
    where config.singleton = true;
$$;

revoke all on function public.hash_premium_device_id(text) from public, anon, authenticated;
grant execute on function public.hash_premium_device_id(text) to service_role;
