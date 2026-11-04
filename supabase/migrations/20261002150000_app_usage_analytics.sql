-- Device hashes are produced by the existing private HMAC function. Raw Android IDs
-- never enter these tables. Daily rows are deliberately retained for only 35 days.
create table if not exists public.app_devices (
    device_hash text primary key check (device_hash ~ '^[0-9a-f]{64}$'),
    model text not null check (char_length(model) between 1 and 120),
    ram_mb integer not null check (ram_mb between 256 and 1048576),
    storage_mb integer not null check (storage_mb between 1024 and 16777216),
    cpu text not null check (char_length(cpu) between 1 and 120),
    last_premium boolean not null default false,
    first_seen_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now()
);

create table if not exists public.app_device_daily_usage (
    usage_date date not null,
    device_hash text not null check (device_hash ~ '^[0-9a-f]{64}$'),
    was_premium boolean not null,
    primary key (usage_date, device_hash)
);

-- A singleton row lets the write function prune old aggregate rows once per day,
-- rather than doing a retention delete for every app launch.
create table if not exists private.app_analytics_maintenance (
    singleton boolean primary key default true check (singleton),
    last_pruned_on date not null
);

alter table public.app_devices enable row level security;
alter table public.app_devices force row level security;
alter table public.app_device_daily_usage enable row level security;
alter table public.app_device_daily_usage force row level security;

revoke all on public.app_devices from public, anon, authenticated;
revoke all on public.app_device_daily_usage from public, anon, authenticated;
grant select, insert, update, delete on public.app_devices to service_role;
grant select, insert, update, delete on public.app_device_daily_usage to service_role;

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
    v_model text := regexp_replace(btrim(coalesce(p_model, '')), '\\s+', ' ', 'g');
    v_cpu text := regexp_replace(btrim(coalesce(p_cpu, '')), '\\s+', ' ', 'g');
    v_is_premium boolean;
    v_today date := (now() at time zone 'Asia/Kolkata')::date;
    v_last_pruned_on date;
begin
    if p_device_hash !~ '^[0-9a-f]{64}$'
       or char_length(v_model) not between 1 and 120
       or char_length(v_cpu) not between 1 and 120
       or p_ram_mb not between 256 and 1048576
       or p_storage_mb not between 1024 and 16777216 then
        return false;
    end if;

    v_is_premium := exists (
        select 1
        from public.premium_entitlements
        where device_hash = p_device_hash
    );

    insert into public.app_devices (
        device_hash, model, ram_mb, storage_mb, cpu, last_premium
    ) values (
        p_device_hash, v_model, p_ram_mb, p_storage_mb, v_cpu, v_is_premium
    )
    on conflict (device_hash) do update
    set model = excluded.model,
        ram_mb = excluded.ram_mb,
        storage_mb = excluded.storage_mb,
        cpu = excluded.cpu,
        last_premium = excluded.last_premium,
        last_seen_at = now();

    insert into public.app_device_daily_usage (
        usage_date, device_hash, was_premium
    ) values (
        v_today, p_device_hash, v_is_premium
    )
    on conflict (usage_date, device_hash) do update
    set was_premium = excluded.was_premium;

    insert into private.app_analytics_maintenance (singleton, last_pruned_on)
    values (true, date '0001-01-01')
    on conflict (singleton) do nothing;

    select last_pruned_on
    into v_last_pruned_on
    from private.app_analytics_maintenance
    where singleton
    for update;

    if v_last_pruned_on < v_today then
        delete from public.app_device_daily_usage
        where usage_date < v_today - 34;

        update private.app_analytics_maintenance
        set last_pruned_on = v_today
        where singleton;
    end if;

    return true;
end;
$$;

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
            dates.usage_date,
            count(usage.device_hash) as all_users,
            count(usage.device_hash) filter (where usage.was_premium) as premium_users,
            count(usage.device_hash) filter (where not usage.was_premium) as non_premium_users
        from dates
        left join public.app_device_daily_usage as usage
          on usage.usage_date = dates.usage_date
        group by dates.usage_date
    ), daily as (
        select coalesce(jsonb_agg(jsonb_build_object(
            'date', daily_counts.usage_date,
            'allUsers', daily_counts.all_users,
            'premiumUsers', daily_counts.premium_users,
            'nonPremiumUsers', daily_counts.non_premium_users
        ) order by daily_counts.usage_date), '[]'::jsonb) as values
        from daily_counts
    ), devices as (
        select coalesce(jsonb_agg(jsonb_build_object(
            'model', device.model,
            'ramMb', device.ram_mb,
            'storageMb', device.storage_mb,
            'cpu', device.cpu,
            'premium', device.last_premium,
            'firstSeenAt', device.first_seen_at,
            'lastSeenAt', device.last_seen_at
        ) order by device.last_seen_at desc), '[]'::jsonb) as values
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
            ), '[]'::jsonb)
        ) as values
    )
    select jsonb_build_object(
        'timezone', 'Asia/Kolkata',
        'daily', (select values from daily),
        'devices', (select values from devices),
        'filters', (select values from filters)
    );
$$;

revoke all on function public.record_app_device_usage(text, text, integer, integer, text)
    from public, anon, authenticated;
revoke all on function public.get_app_usage_analytics(integer)
    from public, anon, authenticated;
grant execute on function public.record_app_device_usage(text, text, integer, integer, text)
    to service_role;
grant execute on function public.get_app_usage_analytics(integer)
    to service_role;
