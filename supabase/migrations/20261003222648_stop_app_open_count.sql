-- Stop collecting per-device app-open counts while preserving the column for
-- compatibility with already deployed analytics functions and app versions.
create or replace function public.check_app_startup(
    p_android_id text,
    p_model text,
    p_ram_mb integer,
    p_storage_mb integer,
    p_cpu text,
    p_ip_address inet
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_device_hash text;
    v_ads_enabled boolean;
    v_premium_only boolean;
    v_premium boolean;
    v_telemetry_recorded boolean := false;
begin
    if p_android_id is null or char_length(p_android_id) not between 8 and 128 then
        raise exception 'Invalid device identifier';
    end if;

    v_device_hash := public.hash_premium_device_id(p_android_id);
    if v_device_hash is null then
        raise exception 'Could not securely identify this device';
    end if;

    select ads_enabled, premium_only_access
      into v_ads_enabled, v_premium_only
    from private.premium_config
    where singleton = true;

    if v_ads_enabled is null or v_premium_only is null then
        raise exception 'App settings are not configured';
    end if;

    v_premium := exists (
        select 1 from public.premium_entitlements
        where device_hash = v_device_hash
    );

    begin
        if p_model is not null and p_ram_mb is not null
           and p_storage_mb is not null and p_cpu is not null then
            v_telemetry_recorded := public.record_app_device_usage(
                v_device_hash, p_model, p_ram_mb, p_storage_mb, p_cpu
            );
        end if;
    exception when others then
        v_telemetry_recorded := false;
    end;

    if p_ip_address is not null then
        begin
            update public.app_devices
            set ip_address = p_ip_address
            where device_hash = v_device_hash;
        exception when others then
            null;
        end;
    end if;

    return pg_catalog.jsonb_build_object(
        'premium', v_premium,
        'premiumOnlyAccess', v_premium_only,
        'accessAllowed', (not v_premium_only or v_premium),
        'adsEnabled', v_ads_enabled,
        'telemetryRecorded', v_telemetry_recorded
    );
end;
$$;

revoke all on function public.check_app_startup(text, text, integer, integer, text, inet)
    from public, anon, authenticated;
grant execute on function public.check_app_startup(text, text, integer, integer, text, inet)
    to service_role;
