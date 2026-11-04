-- Add an explicit premium-only beta mode while preserving the separate,
-- capped enrollment path for non-premium testers. Premium activity is logged
-- independently and never consumes beta registration capacity.
-- Released builds (versionCode at or below latest APK) are public to everyone
-- and never beta-gated: if v6 is latest, v6 and below stay available to all.
alter table private.premium_config
    add column if not exists beta_premium_only boolean not null default false;

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
        'betaPremiumOnly', beta_premium_only,
        'betaVersionCode', beta_version_code,
        'betaDeviceLimit', beta_device_limit,
        'betaRegisteredCount', (select count(*) from private.app_beta_registrations where version_code = config.beta_version_code),
        'channelUrl', telegram_channel_url,
        'groupUrl', telegram_group_url
    )
    from private.premium_config as config where singleton = true;
$$;

create or replace function public.set_app_beta_config(
    p_enabled boolean, p_version_code integer, p_device_limit integer, p_premium_only boolean
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_count integer;
begin
    if p_enabled is null or p_premium_only is null or p_version_code is null or p_version_code < 0
       or p_device_limit is null or p_device_limit not between 1 and 10000
       or (p_enabled and p_version_code < 1) then
        raise exception 'Choose a positive beta version and a device limit from 1 to 10000';
    end if;
    update private.premium_config
       set beta_enabled = p_enabled,
           beta_closed = false,
           beta_version_code = p_version_code,
           beta_device_limit = p_device_limit,
           beta_premium_only = p_premium_only
     where singleton = true;
    select count(*) into v_count from private.app_beta_registrations
     where version_code = p_version_code;
    return pg_catalog.jsonb_build_object(
        'betaEnabled', p_enabled, 'betaClosed', false, 'betaPremiumOnly', p_premium_only,
        'betaVersionCode', p_version_code,
        'betaDeviceLimit', p_device_limit, 'betaRegisteredCount', v_count
    );
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
    if not (v_enabled or v_closed) then return false; end if;
    -- Premium devices are logged on their actual installed version without
    -- consuming a non-premium enrollment slot. Non-premium logs are scoped to
    -- the configured beta version only.
    if not coalesce(p_premium, false) and v_beta_version <> p_version_code then return false; end if;
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
    v_beta_premium_only boolean;
    v_beta_version integer;
    v_beta_limit integer;
    v_beta_allowed boolean := true;
    v_registered boolean := false;
    v_is_released boolean := false;
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
        telegram_channel_url, telegram_group_url, beta_enabled, beta_closed, beta_premium_only,
        beta_version_code, beta_device_limit
      into v_ads, v_premium_only, v_min_version, v_latest, v_update_url,
        v_update_message, v_channel, v_group, v_beta_enabled, v_beta_closed, v_beta_premium_only,
        v_beta_version, v_beta_limit
      from private.premium_config where singleton = true;
    if v_ads is null or v_premium_only is null then
        raise exception 'App settings are not configured';
    end if;
    v_premium := exists (
        select 1 from public.premium_entitlements where device_hash = v_hash
    );

    -- Released builds (at or below the latest APK) are public to everyone and
    -- never count as beta: if v6 is the latest APK, v6 and below skip beta
    -- enrollment/denial completely, even while a beta runs above latest.
    v_is_released := coalesce(p_version_code, 0) > 0
        and coalesce(v_latest, 0) > 0
        and coalesce(p_version_code, 0) <= v_latest;

    if not v_premium and not v_is_released then
        select exists (
            select 1 from private.app_beta_registrations
             where version_code = p_version_code and device_hash = v_hash
        ) into v_registered;

        if v_beta_enabled then
            if v_beta_premium_only then
                -- Premium-only beta does not consume or admit free beta slots.
                v_beta_allowed := false;
            else
                -- While capped beta is open, an unregistered non-premium device
                -- can enroll only on the exact configured version.
                v_beta_allowed := v_registered;
                if not v_registered and p_version_code = v_beta_version
                   and p_version_code >= v_min_version then
                    perform 1 from private.premium_config
                     where singleton = true for update;
                    select beta_enabled, beta_version_code, beta_device_limit,
                           beta_premium_only
                      into v_beta_enabled, v_beta_version, v_beta_limit,
                           v_beta_premium_only
                      from private.premium_config where singleton = true;
                    if v_beta_enabled and not v_beta_premium_only
                       and p_version_code = v_beta_version then
                        select exists (
                            select 1 from private.app_beta_registrations
                             where version_code = p_version_code and device_hash = v_hash
                        ) into v_registered;
                        if not v_registered then
                            select count(*) into v_count
                              from private.app_beta_registrations
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
            end if;
        elsif v_beta_closed and v_registered and not v_is_released then
            v_beta_allowed := false;
        end if;
    end if;

    if v_is_released then
        -- Released versions bypass beta enrollment/denial completely.
        v_beta_allowed := true;
        v_registered := false;
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
        'accessAllowed', v_premium or v_is_released or (
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
            and p_version_code = v_beta_version and not v_is_released,
        'betaClosed', v_beta_closed and v_registered and not v_is_released,
        'betaEnabled', v_beta_enabled,
        'betaPremiumOnly', v_beta_premium_only,
        'betaVersionCode', v_beta_version,
        'betaDeviceLimit', v_beta_limit,
        'betaRegisteredCount', v_count
    );
end;
$$;

-- Keep the existing RPC signature working during rollout. Old clients retain
-- the current mode; the updated admin page sends the explicit fourth argument.
create or replace function public.set_app_beta_config(
    p_enabled boolean, p_version_code integer, p_device_limit integer
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_premium_only boolean;
begin
    select beta_premium_only into v_premium_only
      from private.premium_config where singleton = true;
    return public.set_app_beta_config(
        p_enabled, p_version_code, p_device_limit, coalesce(v_premium_only, false)
    );
end;
$$;

revoke all on function public.set_app_beta_config(boolean, integer, integer, boolean)
    from public, anon, authenticated;
grant execute on function public.set_app_beta_config(boolean, integer, integer, boolean)
    to service_role;
revoke all on function public.set_app_beta_config(boolean, integer, integer)
    from public, anon, authenticated;
grant execute on function public.set_app_beta_config(boolean, integer, integer)
    to service_role;
