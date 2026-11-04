-- Keep one inventory row per anonymized device for the admin's hardware list,
-- and use bounded sharded counters for new daily usage. The old per-device
-- history is retained as an archive and is no longer written after this change.
create table if not exists public.app_usage_daily (
    usage_date date not null,
    shard smallint not null default 0 check (shard between 0 and 63),
    total_users integer not null default 0 check (total_users >= 0),
    premium_users integer not null default 0 check (premium_users >= 0),
    non_premium_users integer not null default 0 check (non_premium_users >= 0),
    last_pruned_on date,
    check (total_users = premium_users + non_premium_users),
    primary key (usage_date, shard)
);

alter table public.app_devices
    add column if not exists last_seen_date date,
    add column if not exists last_seen_premium boolean not null default false;

alter table public.app_usage_daily enable row level security;
alter table public.app_usage_daily force row level security;
revoke all on public.app_usage_daily from public, anon, authenticated;
grant select, insert, update, delete on public.app_usage_daily to service_role;

-- Preserve the counts already collected in their corresponding counter shards.
insert into public.app_usage_daily (
    usage_date, shard, total_users, premium_users, non_premium_users
)
select
    usage_date,
    (get_byte(decode(substr(device_hash, 1, 2), 'hex'), 0) % 64)::smallint,
    count(*)::integer,
    count(*) filter (where was_premium)::integer,
    count(*) filter (where not was_premium)::integer
from public.app_device_daily_usage
group by usage_date, (get_byte(decode(substr(device_hash, 1, 2), 'hex'), 0) % 64)
on conflict (usage_date, shard) do update
set total_users = excluded.total_users,
    premium_users = excluded.premium_users,
    non_premium_users = excluded.non_premium_users;

-- Seed the per-device daily guard from the most recent retained observation.
update public.app_devices as device
set last_seen_date = latest.usage_date,
    last_seen_premium = latest.was_premium
from (
    select distinct on (device_hash) device_hash, usage_date, was_premium
    from public.app_device_daily_usage
    order by device_hash, usage_date desc
) as latest
where latest.device_hash = device.device_hash;

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
    v_last_seen_date date;
    v_last_seen_premium boolean;
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

    v_shard := get_byte(decode(substr(p_device_hash, 1, 2), 'hex'), 0) % 64;

    v_is_premium := exists (
        select 1 from public.premium_entitlements
        where device_hash = p_device_hash
    );

    insert into public.app_devices (
        device_hash, model, ram_mb, storage_mb, cpu
    ) values (
        p_device_hash, v_model, p_ram_mb, p_storage_mb, v_cpu
    )
    on conflict (device_hash) do nothing;

    select last_seen_date, last_seen_premium
      into v_last_seen_date, v_last_seen_premium
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
            where usage_date < v_today - 29;

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

    update public.app_devices
    set model = v_model,
        ram_mb = p_ram_mb,
        storage_mb = p_storage_mb,
        cpu = v_cpu,
        last_premium = v_is_premium,
        last_seen_date = v_today,
        last_seen_premium = v_is_premium,
        last_seen_at = now()
    where device_hash = p_device_hash;

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
        left join daily_counts as usage
          on usage.usage_date = dates.usage_date
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
grant execute on function public.record_app_device_usage(text, text, integer, integer, text)
    to service_role;
revoke all on function public.get_app_usage_analytics(integer)
    from public, anon, authenticated;
grant execute on function public.get_app_usage_analytics(integer)
    to service_role;
