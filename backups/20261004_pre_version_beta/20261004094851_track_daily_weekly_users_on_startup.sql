-- Count a device once per India-local day and once per Sunday-starting week,
-- even when the app correctly omits unchanged device telemetry on later launches.
-- This records unique active devices, not app-open events.
create or replace function public.check_app_startup(
    p_android_id text,
    p_model text default null,
    p_ram_mb integer default null,
    p_storage_mb integer default null,
    p_cpu text default null,
    p_ip_address inet default null,
    p_version_code integer default 0
) returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
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
    v_beta_version integer;
    v_beta_limit integer;
    v_beta_allowed boolean := true;
    v_registered boolean;
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
    select ads_enabled, premium_only_access, app_minimum_version_code, app_latest_version_code,
        app_update_url, app_update_message, telegram_channel_url, telegram_group_url,
        beta_enabled, beta_version_code, beta_device_limit
    into v_ads, v_premium_only, v_min_version, v_latest, v_update_url, v_update_message,
        v_channel, v_group, v_beta_enabled, v_beta_version, v_beta_limit
    from private.premium_config where singleton = true;
    if v_ads is null or v_premium_only is null then
        raise exception 'App settings are not configured';
    end if;

    v_premium := exists (
        select 1 from public.premium_entitlements where device_hash = v_hash
    );

    if v_beta_enabled and p_version_code = v_beta_version
       and p_version_code >= v_min_version and not v_premium then
        select exists (
            select 1 from private.app_beta_registrations
            where version_code = v_beta_version and device_hash = v_hash
        ) into v_registered;
        if not v_registered then
            perform 1 from private.premium_config where singleton = true for update;
            select beta_enabled, beta_version_code, beta_device_limit
            into v_beta_enabled, v_beta_version, v_beta_limit
            from private.premium_config where singleton = true;
            if v_beta_enabled and p_version_code = v_beta_version then
                select exists (
                    select 1 from private.app_beta_registrations
                    where version_code = v_beta_version and device_hash = v_hash
                ) into v_registered;
                if not v_registered then
                    select count(*) into v_count from private.app_beta_registrations
                    where version_code = v_beta_version;
                    if v_count >= v_beta_limit then
                        v_beta_allowed := false;
                    else
                        insert into private.app_beta_registrations(version_code, device_hash)
                        values (v_beta_version, v_hash) on conflict do nothing;
                    end if;
                end if;
            end if;
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
                -- Unchanged specs are sent only once per day by the client.
                -- Reuse the stored specs to update daily/weekly unique counts.
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
                        -- Counts are already current; skip further aggregate writes.
                        v_telemetry := true;
                    end if;
                end if;
            end if;
        exception when others then
            -- Usage analytics must never make a valid access check fail.
            v_telemetry := false;
        end;
    end if;

    if p_ip_address is not null then
        begin
            update public.app_devices set ip_address = p_ip_address where device_hash = v_hash;
        exception when others then null;
        end;
    end if;

    select count(*) into v_count from private.app_beta_registrations
    where version_code = v_beta_version;

    return pg_catalog.jsonb_build_object(
        'premium', v_premium,
        'premiumOnlyAccess', v_premium_only,
        'accessAllowed', (
            (not v_premium_only or v_premium or
                (v_beta_enabled and p_version_code = v_beta_version
                    and p_version_code >= v_min_version and v_beta_allowed))
            and v_beta_allowed
        ),
        'adsEnabled', v_ads,
        'telemetryRecorded', v_telemetry,
        'minimumVersionCode', v_min_version,
        'latestVersionCode', v_latest,
        'updateUrl', v_update_url,
        'updateMessage', v_update_message,
        'channelUrl', v_channel,
        'groupUrl', v_group,
        'betaFull', not v_beta_allowed,
        'betaEnabled', v_beta_enabled,
        'betaVersionCode', v_beta_version,
        'betaDeviceLimit', v_beta_limit,
        'betaRegisteredCount', v_count
    );
end;
$$;

revoke all on function public.check_app_startup(text, text, integer, integer, text, inet, integer)
    from public, anon, authenticated;
grant execute on function public.check_app_startup(text, text, integer, integer, text, inet, integer)
    to service_role;
