-- Disable beta access permanently while preserving existing hashed registration
-- data in the device inventory. Historical beta log rows continue to expire
-- under the existing three-day retention job.

alter table public.app_devices
    add column if not exists app_version_code integer not null default 0
        check (app_version_code >= 0);

update private.premium_config
   set beta_enabled = false,
       beta_closed = false,
       beta_premium_only = false
 where singleton = true;

create or replace function public.get_premium_public_settings()
returns jsonb language sql security definer set search_path = '' as $$
    select pg_catalog.jsonb_build_object(
        'adsEnabled', config.ads_enabled,
        'premiumOnlyAccess', config.premium_only_access,
        'latestVersionCode', config.app_latest_version_code,
        'minimumVersionCode', config.app_minimum_version_code,
        'updateUrl', config.app_update_url,
        'updateMessage', config.app_update_message,
        'channelUrl', config.telegram_channel_url,
        'groupUrl', config.telegram_group_url
    )
    from private.premium_config config
    where config.singleton = true;
$$;

create or replace function public.get_admin_device_inventory()
returns jsonb language sql stable security definer set search_path = '' as $$
    with beta_only as (
        select distinct on (registration.device_hash)
            registration.device_hash,
            registration.version_code,
            log.model,
            log.ram_mb,
            log.storage_mb,
            log.cpu,
            log.ip_address,
            log.last_seen_at
        from private.app_beta_registrations registration
        left join private.app_beta_access_logs log
          on log.device_hash = registration.device_hash
         and log.version_code = registration.version_code
        order by registration.device_hash, registration.version_code desc,
                 log.last_seen_at desc nulls last
    ), inventory as (
        select device.device_hash, device.model, device.ram_mb, device.storage_mb,
            device.cpu, device.app_version_code, device.ip_address,
            device.last_seen_at as sort_at,
            profile.display_name as name, profile.email,
            coalesce(profile.email_verified, false) as email_verified,
            exists(select 1 from public.premium_entitlements entitlement
                   where entitlement.device_hash = device.device_hash) as premium,
            coalesce(active.transaction_id, latest_request.transaction_id) as reference_id
        from public.app_devices device
        left join private.app_device_profiles profile using (device_hash)
        left join lateral (
            select entitlement.transaction_id
            from public.premium_entitlements entitlement
            where entitlement.device_hash = device.device_hash
            order by entitlement.granted_at desc limit 1
        ) active on true
        left join lateral (
            select request.transaction_id
            from public.premium_requests request
            where request.device_hash = device.device_hash
            order by request.submitted_at desc limit 1
        ) latest_request on true

        union all

        select beta.device_hash, beta.model, beta.ram_mb, beta.storage_mb,
            beta.cpu, beta.version_code, beta.ip_address, beta.last_seen_at,
            profile.display_name, profile.email,
            coalesce(profile.email_verified, false),
            exists(select 1 from public.premium_entitlements entitlement
                   where entitlement.device_hash = beta.device_hash),
            coalesce(active.transaction_id, latest_request.transaction_id)
        from beta_only beta
        left join public.app_devices device using (device_hash)
        left join private.app_device_profiles profile using (device_hash)
        left join lateral (
            select entitlement.transaction_id
            from public.premium_entitlements entitlement
            where entitlement.device_hash = beta.device_hash
            order by entitlement.granted_at desc limit 1
        ) active on true
        left join lateral (
            select request.transaction_id
            from public.premium_requests request
            where request.device_hash = beta.device_hash
            order by request.submitted_at desc limit 1
        ) latest_request on true
        where device.device_hash is null
    )
    select coalesce(jsonb_agg(pg_catalog.jsonb_build_object(
        'deviceId', left(device_hash, 12),
        'model', model,
        'ramMb', ram_mb,
        'storageMb', storage_mb,
        'cpu', cpu,
        'appVersionCode', app_version_code,
        'ipAddress', ip_address::text,
        'name', name,
        'email', email,
        'emailVerified', email_verified,
        'premium', premium,
        'referenceId', reference_id
    ) order by sort_at desc nulls last), '[]'::jsonb)
    from inventory;
$$;

-- Beta RPCs are no longer part of the client/admin API. Keep the tables so
-- enrolled devices remain visible above; do not expose beta management RPCs.
revoke all on function public.set_app_beta_config(boolean,integer,integer),
    public.set_app_beta_config(boolean,integer,integer,boolean),
    public.close_app_beta(),
    public.record_app_beta_access(integer,text,text,integer,integer,text,inet,boolean,boolean),
    public.get_app_beta_access_logs()
    from public, anon, authenticated, service_role;

revoke all on function public.get_admin_device_inventory()
    from public, anon, authenticated;
grant execute on function public.get_admin_device_inventory() to service_role;

revoke all on function public.get_premium_public_settings()
    from public, anon, authenticated;
grant execute on function public.get_premium_public_settings() to service_role;
