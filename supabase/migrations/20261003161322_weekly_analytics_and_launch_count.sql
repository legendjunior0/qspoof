-- Correct per-device launch counts and add exact Sunday-Saturday weekly uniques.
-- Daily counters retain seven India-local calendar dates; weekly counters
-- retain the current week and six previous Sunday-starting weeks.
alter table public.app_devices
    add column if not exists last_seen_week_start date,
    add column if not exists last_seen_week_premium boolean not null default false;

-- The previous counter did not increment on successful telemetry. Discard its
-- incomplete values so the admin ranking begins with accurate new counts.
update public.app_devices set launch_count = 0 where launch_count <> 0;

create table if not exists public.app_usage_weekly (
    week_start date not null,
    shard smallint not null default 0 check (shard between 0 and 63),
    total_users integer not null default 0 check (total_users >= 0),
    premium_users integer not null default 0 check (premium_users >= 0),
    non_premium_users integer not null default 0 check (non_premium_users >= 0),
    last_pruned_on date,
    check (total_users = premium_users + non_premium_users),
    primary key (week_start, shard)
);

alter table public.app_usage_weekly enable row level security;
alter table public.app_usage_weekly force row level security;
revoke all on public.app_usage_weekly from public, anon, authenticated;
grant select, insert, update, delete on public.app_usage_weekly to service_role;

create or replace function public.record_app_device_usage(
    p_device_hash text,
    p_model text,
    p_ram_mb integer,
    p_storage_mb integer,
    p_cpu text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_model text := regexp_replace(btrim(coalesce(p_model, '')), '\s+', ' ', 'g');
    v_cpu text := regexp_replace(btrim(coalesce(p_cpu, '')), '\s+', ' ', 'g');
    v_is_premium boolean;
    v_today date := (now() at time zone 'Asia/Kolkata')::date;
    v_week_start date;
    v_last_seen_date date;
    v_last_seen_premium boolean;
    v_last_seen_week_start date;
    v_last_seen_week_premium boolean;
    v_last_pruned_on date;
    v_shard smallint;
begin
    if p_device_hash is null
       or p_device_hash !~ '^[0-9a-f]{64}$'
       or char_length(v_model) not between 1 and 120
       or char_length(v_cpu) not between 1 and 120
       or p_ram_mb is null
       or p_ram_mb not between 256 and 1048576
       or p_storage_mb is null
       or p_storage_mb not between 1024 and 16777216 then
        return false;
    end if;

    v_week_start := v_today - extract(dow from v_today)::integer;
    v_shard := (get_byte(decode(substr(p_device_hash, 1, 2), 'hex'), 0) % 64)::smallint;
    v_is_premium := exists (
        select 1 from public.premium_entitlements
        where device_hash = p_device_hash
    );

    insert into public.app_devices (device_hash, model, ram_mb, storage_mb, cpu)
    values (p_device_hash, v_model, p_ram_mb, p_storage_mb, v_cpu)
    on conflict (device_hash) do nothing;

    select last_seen_date, last_seen_premium,
           last_seen_week_start, last_seen_week_premium
      into v_last_seen_date, v_last_seen_premium,
           v_last_seen_week_start, v_last_seen_week_premium
    from public.app_devices
    where device_hash = p_device_hash
    for update;

    if v_last_seen_date is distinct from v_today then
        insert into public.app_usage_daily (usage_date, shard)
        values (v_today, v_shard)
        on conflict (usage_date, shard) do nothing;

        select last_pruned_on into v_last_pruned_on
        from public.app_usage_daily
        where usage_date = v_today and shard = v_shard
        for update;

        if v_last_pruned_on is distinct from v_today then
            delete from public.app_usage_daily
            where usage_date < v_today - 6;

            update public.app_usage_daily
            set last_pruned_on = v_today
            where usage_date = v_today and shard = v_shard;
        end if;

        update public.app_usage_daily
        set total_users = total_users + 1,
            premium_users = premium_users + case when v_is_premium then 1 else 0 end,
            non_premium_users = non_premium_users + case when v_is_premium then 0 else 1 end
        where usage_date = v_today and shard = v_shard;
    elsif v_last_seen_premium is distinct from v_is_premium then
        update public.app_usage_daily
        set premium_users = premium_users + case when v_is_premium then 1 else -1 end,
            non_premium_users = non_premium_users + case when v_is_premium then -1 else 1 end
        where usage_date = v_today and shard = v_shard;
    end if;

    insert into public.app_usage_weekly (week_start, shard)
    values (v_week_start, v_shard)
    on conflict (week_start, shard) do nothing;

    select last_pruned_on into v_last_pruned_on
    from public.app_usage_weekly
    where week_start = v_week_start and shard = v_shard
    for update;

    if v_last_pruned_on is distinct from v_today then
        delete from public.app_usage_weekly
        where week_start < v_week_start - 42;

        update public.app_usage_weekly
        set last_pruned_on = v_today
        where week_start = v_week_start and shard = v_shard;
    end if;

    if v_last_seen_week_start is distinct from v_week_start then
        update public.app_usage_weekly
        set total_users = total_users + 1,
            premium_users = premium_users + case when v_is_premium then 1 else 0 end,
            non_premium_users = non_premium_users + case when v_is_premium then 0 else 1 end
        where week_start = v_week_start and shard = v_shard;
    elsif v_last_seen_week_premium is distinct from v_is_premium then
        update public.app_usage_weekly
        set premium_users = premium_users + case when v_is_premium then 1 else -1 end,
            non_premium_users = non_premium_users + case when v_is_premium then -1 else 1 end
        where week_start = v_week_start and shard = v_shard;
    end if;

    update public.app_devices
    set model = v_model,
        ram_mb = p_ram_mb,
        storage_mb = p_storage_mb,
        cpu = v_cpu,
        last_premium = v_is_premium,
        last_seen_date = v_today,
        last_seen_premium = v_is_premium,
        last_seen_week_start = v_week_start,
        last_seen_week_premium = v_is_premium,
        last_seen_at = now()
    where device_hash = p_device_hash;

    return true;
end;
$$;

revoke all on function public.record_app_device_usage(text, text, integer, integer, text)
    from public, anon, authenticated;
grant execute on function public.record_app_device_usage(text, text, integer, integer, text)
    to service_role;

-- Count one launch at the startup access-check boundary, independently of
-- whether optional hardware telemetry succeeds. The telemetry RPC itself does
-- not increment this counter, so a startup can never be counted twice.
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

    begin
        update public.app_devices
        set launch_count = launch_count + 1,
            last_seen_at = now(),
            last_premium = v_premium
        where device_hash = v_device_hash;
    exception when others then
        null;
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

create or replace function public.check_app_startup(
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

-- Seed the in-progress week's unique devices from each device's latest sighting.
update public.app_devices
set last_seen_week_start = last_seen_date - extract(dow from last_seen_date)::integer,
    last_seen_week_premium = last_seen_premium
where last_seen_date >= ((now() at time zone 'Asia/Kolkata')::date
                         - extract(dow from (now() at time zone 'Asia/Kolkata')::date)::integer)
  and last_seen_week_start is null;

insert into public.app_usage_weekly (
    week_start, shard, total_users, premium_users, non_premium_users
)
select
    device.last_seen_week_start,
    (get_byte(decode(substr(device.device_hash, 1, 2), 'hex'), 0) % 64)::smallint,
    count(*)::integer,
    count(*) filter (where device.last_seen_week_premium)::integer,
    count(*) filter (where not device.last_seen_week_premium)::integer
from public.app_devices as device
where device.last_seen_week_start = ((now() at time zone 'Asia/Kolkata')::date
                                     - extract(dow from (now() at time zone 'Asia/Kolkata')::date)::integer)
group by device.last_seen_week_start,
         (get_byte(decode(substr(device.device_hash, 1, 2), 'hex'), 0) % 64)
on conflict (week_start, shard) do update
set total_users = excluded.total_users,
    premium_users = excluded.premium_users,
    non_premium_users = excluded.non_premium_users;

create or replace function public.get_app_usage_analytics(p_days integer default 7)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
    with inputs as (
        select
            least(greatest(coalesce(p_days, 7), 1), 7) as days,
            (now() at time zone 'Asia/Kolkata')::date as today,
            ((now() at time zone 'Asia/Kolkata')::date
                - extract(dow from (now() at time zone 'Asia/Kolkata')::date)::integer) as this_week
    ), dates as (
        select generate_series(
            (inputs.today - inputs.days + 1)::timestamp,
            inputs.today::timestamp,
            interval '1 day'
        )::date as usage_date
        from inputs
    ), weeks as (
        select generate_series(
            (inputs.this_week - 42)::timestamp,
            inputs.this_week::timestamp,
            interval '7 days'
        )::date as week_start
        from inputs
    ), daily_counts as (
        select usage_date,
               sum(total_users)::integer as total_users,
               sum(premium_users)::integer as premium_users,
               sum(non_premium_users)::integer as non_premium_users
        from public.app_usage_daily
        group by usage_date
    ), weekly_counts as (
        select week_start,
               sum(total_users)::integer as total_users,
               sum(premium_users)::integer as premium_users,
               sum(non_premium_users)::integer as non_premium_users
        from public.app_usage_weekly
        group by week_start
    ), daily as (
        select coalesce(jsonb_agg(jsonb_build_object(
            'date', dates.usage_date,
            'allUsers', coalesce(counts.total_users, 0),
            'premiumUsers', coalesce(counts.premium_users, 0),
            'nonPremiumUsers', coalesce(counts.non_premium_users, 0)
        ) order by dates.usage_date), '[]'::jsonb) as values
        from dates
        left join daily_counts as counts on counts.usage_date = dates.usage_date
    ), weekly as (
        select coalesce(jsonb_agg(jsonb_build_object(
            'weekStart', weeks.week_start,
            'weekEnd', weeks.week_start + 6,
            'allUsers', coalesce(counts.total_users, 0),
            'premiumUsers', coalesce(counts.premium_users, 0),
            'nonPremiumUsers', coalesce(counts.non_premium_users, 0)
        ) order by weeks.week_start), '[]'::jsonb) as values
        from weeks
        left join weekly_counts as counts on counts.week_start = weeks.week_start
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
            'launchCount', device.launch_count
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
    select jsonb_build_object(
        'timezone', 'Asia/Kolkata',
        'daily', (select values from daily),
        'weekly', (select values from weekly),
        'devices', (select values from devices),
        'filters', (select values from filters)
    );
$$;

revoke all on function public.get_app_usage_analytics(integer)
    from public, anon, authenticated;
grant execute on function public.get_app_usage_analytics(integer)
    to service_role;

delete from public.app_usage_daily
where usage_date < (now() at time zone 'Asia/Kolkata')::date - 6;
