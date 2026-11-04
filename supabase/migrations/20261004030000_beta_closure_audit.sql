-- Persist a closure marker and compact per-device beta access logs. Device
-- identifiers in this table are the existing server-side HMAC, never raw IDs.
alter table private.premium_config
    add column if not exists beta_closed boolean not null default false;

create table if not exists private.app_beta_access_logs (
    version_code integer not null check (version_code > 0),
    device_hash text not null check (device_hash ~ '^[0-9a-f]{64}$'),
    model text,
    ram_mb integer,
    storage_mb integer,
    cpu text,
    ip_address inet,
    premium boolean not null default false,
    access_allowed boolean not null default false,
    first_seen_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    primary key (version_code, device_hash)
);
create index if not exists app_beta_access_logs_last_seen_idx
    on private.app_beta_access_logs (version_code, last_seen_at desc);
alter table private.app_beta_access_logs enable row level security;
revoke all on private.app_beta_access_logs from public, anon, authenticated;
grant select, insert, update, delete on private.app_beta_access_logs to service_role;

create or replace function public.get_premium_public_settings()
returns jsonb language sql security definer set search_path = '' as $$
    select pg_catalog.jsonb_build_object(
        'adsEnabled', ads_enabled,
        'premiumOnlyAccess', premium_only_access,
        'latestVersionCode', app_latest_version_code,
        'minimumVersionCode', app_minimum_version_code,
        'updateUrl', app_update_url,
        'updateMessage', app_update_message,
        'betaEnabled', beta_enabled,
        'betaClosed', beta_closed,
        'betaVersionCode', beta_version_code,
        'betaDeviceLimit', beta_device_limit,
        'betaRegisteredCount', (select count(*) from private.app_beta_registrations where version_code = config.beta_version_code),
        'channelUrl', telegram_channel_url,
        'groupUrl', telegram_group_url
    )
    from private.premium_config as config where singleton = true;
$$;

create or replace function public.set_app_beta_config(
    p_enabled boolean, p_version_code integer, p_device_limit integer
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_old_version integer; v_count integer;
begin
    if p_enabled is null or p_version_code is null or p_version_code < 0
       or p_device_limit is null or p_device_limit not between 1 and 10000
       or (p_enabled and p_version_code < 1) then
        raise exception 'Choose a positive beta version and a device limit from 1 to 10000';
    end if;
    select beta_version_code into v_old_version
      from private.premium_config where singleton = true for update;
    if not p_enabled or v_old_version is distinct from p_version_code then
        delete from private.app_beta_registrations;
    end if;
    update private.premium_config set beta_enabled = p_enabled, beta_closed = false,
        beta_version_code = p_version_code, beta_device_limit = p_device_limit
    where singleton = true;
    select count(*) into v_count from private.app_beta_registrations where version_code = p_version_code;
    return pg_catalog.jsonb_build_object('betaEnabled', p_enabled, 'betaClosed', false,
        'betaVersionCode', p_version_code, 'betaDeviceLimit', p_device_limit, 'betaRegisteredCount', v_count);
end;
$$;

create or replace function public.close_app_beta()
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
    update private.premium_config set beta_enabled = false, beta_closed = true where singleton = true;
    return public.get_premium_public_settings();
end;
$$;

create or replace function public.record_app_beta_access(
    p_version_code integer, p_device_hash text, p_model text, p_ram_mb integer,
    p_storage_mb integer, p_cpu text, p_ip_address inet,
    p_premium boolean, p_access_allowed boolean
) returns boolean language plpgsql security definer set search_path = '' as $$
declare v_beta_version integer; v_enabled boolean; v_closed boolean;
begin
    if p_version_code is null or p_version_code < 1
       or p_device_hash !~ '^[0-9a-f]{64}$' then return false; end if;
    select beta_version_code, beta_enabled, beta_closed
      into v_beta_version, v_enabled, v_closed from private.premium_config where singleton = true;
    if v_beta_version <> p_version_code or not (v_enabled or v_closed) then return false; end if;
    insert into private.app_beta_access_logs(version_code, device_hash, model, ram_mb, storage_mb,
        cpu, ip_address, premium, access_allowed)
    values (p_version_code, p_device_hash, left(p_model,120), p_ram_mb, p_storage_mb,
        left(p_cpu,120), p_ip_address, coalesce(p_premium,false), coalesce(p_access_allowed,false))
    on conflict (version_code,device_hash) do update set
        model = coalesce(excluded.model, app_beta_access_logs.model),
        ram_mb = coalesce(excluded.ram_mb, app_beta_access_logs.ram_mb),
        storage_mb = coalesce(excluded.storage_mb, app_beta_access_logs.storage_mb),
        cpu = coalesce(excluded.cpu, app_beta_access_logs.cpu),
        ip_address = coalesce(excluded.ip_address, app_beta_access_logs.ip_address),
        premium = excluded.premium, access_allowed = excluded.access_allowed,
        last_seen_at = now();
    return true;
end;
$$;

create or replace function public.get_app_beta_access_logs()
returns jsonb language sql stable security definer set search_path = '' as $$
    select coalesce(jsonb_agg(pg_catalog.jsonb_build_object(
        'deviceId', left(log.device_hash, 12), 'versionCode', log.version_code,
        'model', log.model, 'ramMb', log.ram_mb, 'storageMb', log.storage_mb,
        'cpu', log.cpu, 'ipAddress', log.ip_address::text,
        'name', profile.display_name, 'email', profile.email,
        'premium', log.premium, 'accessAllowed', log.access_allowed,
        'firstSeenAt', log.first_seen_at, 'lastSeenAt', log.last_seen_at
    ) order by log.last_seen_at desc), '[]'::jsonb)
    from private.app_beta_access_logs log
    left join private.app_device_profiles profile using (device_hash);
$$;

create or replace function public.get_admin_device_profiles(p_device_hashes text[])
returns jsonb language sql stable security definer set search_path = '' as $$
    select coalesce(jsonb_object_agg(profile.device_hash,
        pg_catalog.jsonb_build_object('name', profile.display_name, 'email', profile.email)), '{}'::jsonb)
    from private.app_device_profiles profile
    where profile.device_hash = any(coalesce(p_device_hashes, array[]::text[]));
$$;

create or replace function public.get_admin_device_inventory()
returns jsonb language sql stable security definer set search_path = '' as $$
    select coalesce(jsonb_agg(pg_catalog.jsonb_build_object(
        'model', device.model, 'ramMb', device.ram_mb, 'storageMb', device.storage_mb,
        'cpu', device.cpu, 'ipAddress', device.ip_address::text,
        'name', profile.display_name, 'email', profile.email,
        'emailVerified', coalesce(profile.email_verified, false),
        'premium', exists(select 1 from public.premium_entitlements e where e.device_hash = device.device_hash),
        'referenceId', coalesce(active.transaction_id, latest_request.transaction_id),
        'firstSeenAt', device.first_seen_at
    ) order by device.first_seen_at desc), '[]'::jsonb)
    from public.app_devices device
    left join private.app_device_profiles profile using (device_hash)
    left join lateral (
        select entitlement.transaction_id from public.premium_entitlements entitlement
        where entitlement.device_hash = device.device_hash
        order by entitlement.granted_at desc limit 1
    ) active on true
    left join lateral (
        select request.transaction_id from public.premium_requests request
        where request.device_hash = device.device_hash
        order by request.submitted_at desc limit 1
    ) latest_request on true;
$$;

revoke all on function public.close_app_beta(),
    public.record_app_beta_access(integer,text,text,integer,integer,text,inet,boolean,boolean),
    public.get_app_beta_access_logs(), public.get_admin_device_profiles(text[]),
    public.get_admin_device_inventory()
    from public, anon, authenticated;
grant execute on function public.close_app_beta(),
    public.record_app_beta_access(integer,text,text,integer,integer,text,inet,boolean,boolean),
    public.get_app_beta_access_logs(), public.get_admin_device_profiles(text[]),
    public.get_admin_device_inventory() to service_role;
