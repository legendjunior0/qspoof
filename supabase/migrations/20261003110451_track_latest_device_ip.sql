-- Keep only the latest observed client IP on each already-anonymized device row.
-- inet is compact for IPv4/IPv6, and no IP history table or index is needed.
alter table public.app_devices
    add column if not exists ip_address inet;

-- Six-argument entry point used by the updated Edge Function.
-- Existing app versions remain compatible through the five-argument wrapper below.
drop function public.check_app_startup(text, text, integer, integer, text);

create function public.check_app_startup(
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
        -- Device analytics and IP capture must not block the access decision.
        v_telemetry_recorded := false;
    end;

    if not v_telemetry_recorded then
        begin
            update public.app_devices
            set launch_count = launch_count + 1,
                last_seen_at = now(),
                last_premium = v_premium
            where device_hash = v_device_hash;
        exception when others then
            null;
        end;
    end if;

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

-- Older deployed Edge Function versions still call the original five-argument RPC.
create function public.check_app_startup(
    p_android_id text,
    p_model text default null,
    p_ram_mb integer default null,
    p_storage_mb integer default null,
    p_cpu text default null
)
returns jsonb
language sql
security definer
set search_path = ''
as $$
    select public.check_app_startup(
        p_android_id, p_model, p_ram_mb, p_storage_mb, p_cpu, null::inet
    );
$$;

revoke all on function public.check_app_startup(text, text, integer, integer, text, inet)
    from public, anon, authenticated;
grant execute on function public.check_app_startup(text, text, integer, integer, text, inet)
    to service_role;
revoke all on function public.check_app_startup(text, text, integer, integer, text)
    from public, anon, authenticated;
grant execute on function public.check_app_startup(text, text, integer, integer, text)
    to service_role;

-- Include the current IP in the existing admin-only analytics result.
create or replace function public.get_app_usage_analytics(p_days integer default 30)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
    with inputs as (
        select
            least(greatest(coalesce(p_days, 30), 1), 30) as days,
            (now() at time zone 'Asia/Kolkata')::date as today
    ), dates as (
        select generate_series(
            (inputs.today - inputs.days + 1)::timestamp,
            inputs.today::timestamp,
            interval '1 day'
        )::date as usage_date
        from inputs
    ), daily_counts as (
        select
            usage_date,
            sum(total_users)::integer as total_users,
            sum(premium_users)::integer as premium_users,
            sum(non_premium_users)::integer as non_premium_users
        from public.app_usage_daily
        group by usage_date
    ), daily as (
        select coalesce(jsonb_agg(jsonb_build_object(
            'date', dates.usage_date,
            'allUsers', coalesce(usage.total_users, 0),
            'premiumUsers', coalesce(usage.premium_users, 0),
            'nonPremiumUsers', coalesce(usage.non_premium_users, 0)
        ) order by dates.usage_date), '[]'::jsonb) as values
        from dates
        left join daily_counts as usage on usage.usage_date = dates.usage_date
    ), devices as (
        select coalesce(jsonb_agg(jsonb_build_object(
            'model', device.model,
            'ramMb', device.ram_mb,
            'storageMb', device.storage_mb,
            'cpu', device.cpu,
            'ipAddress', device.ip_address::text,
            'premium', exists (
                select 1 from public.premium_entitlements as entitlement
                where entitlement.device_hash = device.device_hash
            ),
            'launchCount', device.launch_count,
            'registeredAt', device.first_seen_at
        ) order by device.first_seen_at desc), '[]'::jsonb) as values
        from public.app_devices as device
    ), filters as (
        select jsonb_build_object(
            'ramMb', coalesce((
                select jsonb_agg(value order by value)
                from (select distinct ram_mb as value from public.app_devices) as options
            ), '[]'::jsonb),
            'storageMb', coalesce((
                select jsonb_agg(value order by value)
                from (select distinct storage_mb as value from public.app_devices) as options
            ), '[]'::jsonb),
            'cpus', coalesce((
                select jsonb_agg(value order by value)
                from (select distinct cpu as value from public.app_devices) as options
            ), '[]'::jsonb),
            'models', coalesce((
                select jsonb_agg(value order by value)
                from (select distinct model as value from public.app_devices) as options
            ), '[]'::jsonb)
        ) as values
    )
    select pg_catalog.jsonb_build_object(
        'timezone', 'Asia/Kolkata',
        'daily', (select values from daily),
        'devices', (select values from devices),
        'filters', (select values from filters)
    );
$$;

revoke all on function public.get_app_usage_analytics(integer)
    from public, anon, authenticated;
grant execute on function public.get_app_usage_analytics(integer)
    to service_role;
