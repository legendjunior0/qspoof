-- Keep beta enrollment cumulative across capacity/version changes, gate
-- non-premium startup requests while a beta is open, and record each device's
-- most recently used app version on its existing compact device row.

alter table public.app_devices
    add column if not exists app_version_code integer not null default 0
        check (app_version_code >= 0);

create index if not exists app_devices_version_code_idx
    on public.app_devices (app_version_code, last_seen_at desc);

-- Raising capacity for the current version must never evict enrolled devices.
-- Registrations for older versions are retained so a tester already using an
-- earlier beta build is not kicked out when a newer beta is configured.
create or replace function public.set_app_beta_config(
    p_enabled boolean, p_version_code integer, p_device_limit integer
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_count integer;
begin
    if p_enabled is null or p_version_code is null or p_version_code < 0
       or p_device_limit is null or p_device_limit not between 1 and 10000
       or (p_enabled and p_version_code < 1) then
        raise exception 'Choose a positive beta version and a device limit from 1 to 10000';
    end if;
    update private.premium_config
       set beta_enabled = p_enabled,
           beta_closed = false,
           beta_version_code = p_version_code,
           beta_device_limit = p_device_limit
     where singleton = true;
    select count(*) into v_count from private.app_beta_registrations
     where version_code = p_version_code;
    return pg_catalog.jsonb_build_object(
        'betaEnabled', p_enabled, 'betaClosed', false,
        'betaVersionCode', p_version_code,
        'betaDeviceLimit', p_device_limit, 'betaRegisteredCount', v_count
    );
end;
$$;

-- Add the exact version to the compact registered-device profile. Existing
-- version telemetry is refreshed on every startup without adding event rows.
create or replace function public.check_app_startup(
    p_android_id text,
    p_model text default null,
    p_ram_mb integer default null,
    p_storage_mb integer default null,
    p_cpu text default null,
    p_ip_address inet default null,
    p_version_code integer default 0
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_hash text;
    v_ads boolean;
    v_premium_only boolean;
    v_premium boolean;
    v_telemetry boolean := false;
    v_min_version integer;
    v_latest integer;
    v_update_url text;
    v_update_message text;
    v_channel text;
    v_group text;
    v_beta_enabled boolean;
    v_beta_closed boolean;
    v_beta_version integer;
    v_beta_limit integer;
    v_beta_allowed boolean := true;
    v_registered boolean := false;
    v_count integer;
    v_model text;
    v_ram_mb integer;
    v_storage_mb integer;
    v_cpu text;
    v_last_seen_date date;
    v_last_seen_premium boolean;
    v_last_seen_week_start date;
    v_last_seen_week_premium boolean;
    v_today date := (now() at time zone 'Asia/Kolkata')::date;
    v_week_start date := (now() at time zone 'Asia/Kolkata')::date
        - extract(dow from (now() at time zone 'Asia/Kolkata')::date)::integer;
begin
    if p_android_id is null or char_length(p_android_id) not between 8 and 128 then
        raise exception 'Invalid device identifier';
    end if;
    v_hash := public.hash_premium_device_id(p_android_id);
    select ads_enabled, premium_only_access, app_minimum_version_code,
        app_latest_version_code, app_update_url, app_update_message,
        telegram_channel_url, telegram_group_url, beta_enabled, beta_closed,
        beta_version_code, beta_device_limit
      into v_ads, v_premium_only, v_min_version, v_latest, v_update_url,
        v_update_message, v_channel, v_group, v_beta_enabled, v_beta_closed,
        v_beta_version, v_beta_limit
      from private.premium_config where singleton = true;
    if v_ads is null or v_premium_only is null then
        raise exception 'App settings are not configured';
    end if;
    v_premium := exists (
        select 1 from public.premium_entitlements where device_hash = v_hash
    );

    if not v_premium then
        select exists (
            select 1 from private.app_beta_registrations
             where version_code = p_version_code and device_hash = v_hash
        ) into v_registered;

        if v_beta_enabled then
            -- While beta enrollment is active, an unregistered non-premium
            -- device can only enroll on the exact configured version. Existing
            -- testers stay valid for the version they originally enrolled on.
            v_beta_allowed := v_registered;
            if not v_registered and p_version_code = v_beta_version
               and p_version_code >= v_min_version then
                perform 1 from private.premium_config
                 where singleton = true for update;
                select beta_enabled, beta_version_code, beta_device_limit
                  into v_beta_enabled, v_beta_version, v_beta_limit
                  from private.premium_config where singleton = true;
                if v_beta_enabled and p_version_code = v_beta_version then
                    select exists (
                        select 1 from private.app_beta_registrations
                         where version_code = p_version_code and device_hash = v_hash
                    ) into v_registered;
                    if not v_registered then
                        select count(*) into v_count from private.app_beta_registrations
                         where version_code = v_beta_version;
                        if v_count >= v_beta_limit then
                            v_beta_allowed := false;
                        else
                            insert into private.app_beta_registrations(version_code, device_hash)
                            values (v_beta_version, v_hash) on conflict do nothing;
                            v_registered := true;
                            v_beta_allowed := true;
                        end if;
                    else
                        v_beta_allowed := true;
                    end if;
                else
                    v_beta_allowed := false;
                end if;
            end if;
        elsif v_beta_closed and v_registered then
            -- Closing beta revokes already-enrolled non-premium devices too.
            v_beta_allowed := false;
        end if;
    end if;

    if v_beta_allowed and coalesce(p_version_code, 0) >= v_min_version then
        begin
            if p_model is not null and p_ram_mb is not null
               and p_storage_mb is not null and p_cpu is not null then
                v_telemetry := public.record_app_device_usage(
                    v_hash, p_model, p_ram_mb, p_storage_mb, p_cpu
                );
            else
                select model, ram_mb, storage_mb, cpu, last_seen_date,
                    last_seen_premium, last_seen_week_start, last_seen_week_premium
                  into v_model, v_ram_mb, v_storage_mb, v_cpu, v_last_seen_date,
                    v_last_seen_premium, v_last_seen_week_start, v_last_seen_week_premium
                  from public.app_devices where device_hash = v_hash;
                if found then
                    if v_last_seen_date is distinct from v_today
                       or v_last_seen_premium is distinct from v_premium
                       or v_last_seen_week_start is distinct from v_week_start
                       or v_last_seen_week_premium is distinct from v_premium then
                        v_telemetry := public.record_app_device_usage(
                            v_hash, v_model, v_ram_mb, v_storage_mb, v_cpu
                        );
                    else
                        v_telemetry := true;
                    end if;
                end if;
            end if;
        exception when others then
            v_telemetry := false;
        end;
    end if;

    begin
        update public.app_devices
           set app_version_code = greatest(coalesce(p_version_code, 0), 0),
               ip_address = coalesce(p_ip_address, ip_address),
               last_seen_at = now()
         where device_hash = v_hash;
    exception when others then null;
    end;

    select count(*) into v_count from private.app_beta_registrations
     where version_code = v_beta_version;
    return pg_catalog.jsonb_build_object(
        'premium', v_premium,
        'premiumOnlyAccess', v_premium_only,
        'accessAllowed', v_premium or (
            coalesce(p_version_code, 0) >= v_min_version and v_beta_allowed
            and case when v_beta_enabled then true else not v_premium_only end
        ),
        'adsEnabled', v_ads,
        'telemetryRecorded', v_telemetry,
        'minimumVersionCode', v_min_version,
        'latestVersionCode', v_latest,
        'updateUrl', v_update_url,
        'updateMessage', v_update_message,
        'channelUrl', v_channel,
        'groupUrl', v_group,
        'betaFull', v_beta_enabled and not v_beta_allowed
            and p_version_code = v_beta_version,
        'betaClosed', v_beta_closed and v_registered,
        'betaEnabled', v_beta_enabled,
        'betaVersionCode', v_beta_version,
        'betaDeviceLimit', v_beta_limit,
        'betaRegisteredCount', v_count
    );
end;
$$;

create or replace function public.get_admin_device_inventory()
returns jsonb language sql stable security definer set search_path = '' as $$
    select coalesce(jsonb_agg(pg_catalog.jsonb_build_object(
        'model', device.model, 'ramMb', device.ram_mb, 'storageMb', device.storage_mb,
        'cpu', device.cpu, 'appVersionCode', device.app_version_code,
        'ipAddress', device.ip_address::text,
        'name', profile.display_name, 'email', profile.email,
        'emailVerified', coalesce(profile.email_verified, false),
        'premium', exists(select 1 from public.premium_entitlements e where e.device_hash = device.device_hash),
        'referenceId', coalesce(active.transaction_id, latest_request.transaction_id),
        'firstSeenAt', device.first_seen_at
    ) order by device.last_seen_at desc), '[]'::jsonb)
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

revoke all on function public.set_app_beta_config(boolean, integer, integer),
    public.check_app_startup(text, text, integer, integer, text, inet, integer),
    public.get_admin_device_inventory()
    from public, anon, authenticated;
grant execute on function public.set_app_beta_config(boolean, integer, integer),
    public.check_app_startup(text, text, integer, integer, text, inet, integer),
    public.get_admin_device_inventory() to service_role;
