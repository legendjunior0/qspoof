-- Compact unique-device aggregates by app version. No per-launch or
-- per-device-version history rows are retained.
create table if not exists public.app_usage_version_daily (
    usage_date date not null,
    version_code integer not null check (version_code > 0),
    shard smallint not null check (shard between 0 and 63),
    total_users integer not null default 0 check (total_users >= 0),
    premium_users integer not null default 0 check (premium_users >= 0),
    non_premium_users integer not null default 0 check (non_premium_users >= 0),
    last_pruned_on date,
    check (total_users = premium_users + non_premium_users),
    primary key (usage_date, version_code, shard)
);
create table if not exists public.app_usage_version_weekly (
    week_start date not null,
    version_code integer not null check (version_code > 0),
    shard smallint not null check (shard between 0 and 63),
    total_users integer not null default 0 check (total_users >= 0),
    premium_users integer not null default 0 check (premium_users >= 0),
    non_premium_users integer not null default 0 check (non_premium_users >= 0),
    last_pruned_on date,
    check (total_users = premium_users + non_premium_users),
    primary key (week_start, version_code, shard)
);
alter table public.app_usage_version_daily enable row level security;
alter table public.app_usage_version_daily force row level security;
alter table public.app_usage_version_weekly enable row level security;
alter table public.app_usage_version_weekly force row level security;
revoke all on public.app_usage_version_daily, public.app_usage_version_weekly
    from public, anon, authenticated;
grant select, insert, update, delete on public.app_usage_version_daily,
    public.app_usage_version_weekly to service_role;

alter table public.app_devices
    add column if not exists last_seen_version_code integer not null default 0
        check (last_seen_version_code >= 0),
    add column if not exists last_seen_week_version_code integer not null default 0
        check (last_seen_week_version_code >= 0);

create or replace function private.bump_app_version_daily(
    p_date date, p_version integer, p_shard smallint,
    p_total integer, p_premium integer, p_non_premium integer
) returns void language plpgsql security definer set search_path = '' as $$
declare v_today date := (now() at time zone 'Asia/Kolkata')::date; v_pruned date;
begin
    if p_date is null or p_version < 1 or p_shard not between 0 and 63
       or p_total = 0 then return; end if;
    if p_total > 0 then
        insert into public.app_usage_version_daily(
            usage_date, version_code, shard, total_users, premium_users, non_premium_users
        ) values (p_date, p_version, p_shard, p_total, p_premium, p_non_premium)
        on conflict (usage_date, version_code, shard) do update set
            total_users = public.app_usage_version_daily.total_users + excluded.total_users,
            premium_users = public.app_usage_version_daily.premium_users + excluded.premium_users,
            non_premium_users = public.app_usage_version_daily.non_premium_users + excluded.non_premium_users;
        if p_date = v_today then
            select last_pruned_on into v_pruned from public.app_usage_version_daily
             where usage_date = p_date and version_code = p_version and shard = p_shard for update;
            if v_pruned is distinct from v_today then
                delete from public.app_usage_version_daily where usage_date < v_today - 6;
                update public.app_usage_version_daily set last_pruned_on = v_today
                 where usage_date = p_date and version_code = p_version and shard = p_shard;
            end if;
        end if;
    else
        update public.app_usage_version_daily
           set total_users = total_users + p_total,
               premium_users = premium_users + p_premium,
               non_premium_users = non_premium_users + p_non_premium
         where usage_date = p_date and version_code = p_version and shard = p_shard;
    end if;
end;
$$;

create or replace function private.bump_app_version_weekly(
    p_week date, p_version integer, p_shard smallint,
    p_total integer, p_premium integer, p_non_premium integer
) returns void language plpgsql security definer set search_path = '' as $$
declare
    v_today date := (now() at time zone 'Asia/Kolkata')::date;
    v_week date := v_today - extract(dow from v_today)::integer;
    v_pruned date;
begin
    if p_week is null or p_version < 1 or p_shard not between 0 and 63
       or p_total = 0 then return; end if;
    if p_total > 0 then
        insert into public.app_usage_version_weekly(
            week_start, version_code, shard, total_users, premium_users, non_premium_users
        ) values (p_week, p_version, p_shard, p_total, p_premium, p_non_premium)
        on conflict (week_start, version_code, shard) do update set
            total_users = public.app_usage_version_weekly.total_users + excluded.total_users,
            premium_users = public.app_usage_version_weekly.premium_users + excluded.premium_users,
            non_premium_users = public.app_usage_version_weekly.non_premium_users + excluded.non_premium_users;
        if p_week = v_week then
            select last_pruned_on into v_pruned from public.app_usage_version_weekly
             where week_start = p_week and version_code = p_version and shard = p_shard for update;
            if v_pruned is distinct from v_today then
                delete from public.app_usage_version_weekly where week_start < v_week - 42;
                update public.app_usage_version_weekly set last_pruned_on = v_today
                 where week_start = p_week and version_code = p_version and shard = p_shard;
            end if;
        end if;
    else
        update public.app_usage_version_weekly
           set total_users = total_users + p_total,
               premium_users = premium_users + p_premium,
               non_premium_users = non_premium_users + p_non_premium
         where week_start = p_week and version_code = p_version and shard = p_shard;
    end if;
end;
$$;

create or replace function private.track_app_device_version_usage()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
    v_today date := (now() at time zone 'Asia/Kolkata')::date;
    v_week date := v_today - extract(dow from v_today)::integer;
    v_shard smallint;
    v_daily_changed boolean := true;
    v_week_changed boolean := true;
begin
    v_shard := (get_byte(decode(substr(new.device_hash, 1, 2), 'hex'), 0) % 64)::smallint;
    if new.last_seen_date = v_today then
        new.last_seen_version_code := new.app_version_code;
    end if;
    if new.last_seen_week_start = v_week then
        new.last_seen_week_version_code := new.app_version_code;
    end if;
    if tg_op = 'UPDATE' then
        v_daily_changed := old.last_seen_date is distinct from new.last_seen_date
            or old.app_version_code is distinct from new.app_version_code
            or old.last_seen_premium is distinct from new.last_seen_premium
            or old.last_seen_version_code is distinct from new.last_seen_version_code;
        v_week_changed := old.last_seen_week_start is distinct from new.last_seen_week_start
            or old.last_seen_week_version_code is distinct from new.last_seen_week_version_code
            or old.last_seen_week_premium is distinct from new.last_seen_week_premium;
    end if;
    if tg_op = 'UPDATE' then
        if old.last_seen_date = v_today and old.last_seen_version_code > 0 and v_daily_changed then
            perform private.bump_app_version_daily(v_today, old.last_seen_version_code, v_shard,
                -1, case when old.last_seen_premium then -1 else 0 end,
                case when old.last_seen_premium then 0 else -1 end);
        end if;
        if old.last_seen_week_start = v_week and old.last_seen_week_version_code > 0 and v_week_changed then
            perform private.bump_app_version_weekly(v_week, old.last_seen_week_version_code, v_shard,
                -1, case when old.last_seen_week_premium then -1 else 0 end,
                case when old.last_seen_week_premium then 0 else -1 end);
        end if;
    end if;
    if new.last_seen_date = v_today and new.last_seen_version_code > 0
       and v_daily_changed then
        perform private.bump_app_version_daily(v_today, new.last_seen_version_code, v_shard,
            1, case when new.last_seen_premium then 1 else 0 end,
            case when new.last_seen_premium then 0 else 1 end);
    end if;

    if new.last_seen_week_start = v_week and new.last_seen_week_version_code > 0
       and v_week_changed then
        perform private.bump_app_version_weekly(v_week, new.last_seen_week_version_code, v_shard,
            1, case when new.last_seen_week_premium then 1 else 0 end,
            case when new.last_seen_week_premium then 0 else 1 end);
    end if;
    return new;
end;
$$;
drop trigger if exists track_app_device_version_usage on public.app_devices;
create trigger track_app_device_version_usage_insert
before insert on public.app_devices
for each row execute function private.track_app_device_version_usage();
create trigger track_app_device_version_usage_update
before update of app_version_code, last_seen_date, last_seen_premium,
    last_seen_week_start, last_seen_week_premium on public.app_devices
for each row when (
    old.app_version_code is distinct from new.app_version_code
    or old.last_seen_date is distinct from new.last_seen_date
    or old.last_seen_premium is distinct from new.last_seen_premium
    or old.last_seen_week_start is distinct from new.last_seen_week_start
    or old.last_seen_week_premium is distinct from new.last_seen_week_premium
)
execute function private.track_app_device_version_usage();

create or replace function public.get_app_version_analytics()
returns jsonb language sql stable security definer set search_path = '' as $$
    with inputs as (
        select (now() at time zone 'Asia/Kolkata')::date as today,
            ((now() at time zone 'Asia/Kolkata')::date
                - extract(dow from (now() at time zone 'Asia/Kolkata')::date)::integer) as this_week
    ), dates as (
        select generate_series((today - 6)::timestamp, today::timestamp, interval '1 day')::date as d from inputs
    ), weeks as (
        select generate_series((this_week - 42)::timestamp, this_week::timestamp, interval '7 days')::date as d from inputs
    ), daily_counts as (
        select usage_date, version_code, sum(total_users)::integer as all_users,
            sum(premium_users)::integer as premium_users, sum(non_premium_users)::integer as non_premium_users
          from public.app_usage_version_daily group by usage_date, version_code
    ), weekly_counts as (
        select week_start, version_code, sum(total_users)::integer as all_users,
            sum(premium_users)::integer as premium_users, sum(non_premium_users)::integer as non_premium_users
          from public.app_usage_version_weekly group by week_start, version_code
    ), daily as (
        select coalesce(jsonb_agg(jsonb_build_object('date', dc.usage_date,
            'versionCode', dc.version_code, 'allUsers', dc.all_users,
            'premiumUsers', dc.premium_users, 'nonPremiumUsers', dc.non_premium_users)
            order by dc.usage_date, dc.version_code), '[]'::jsonb) as values from daily_counts dc
    ), weekly as (
        select coalesce(jsonb_agg(jsonb_build_object('weekStart', wc.week_start,
            'weekEnd', wc.week_start + 6, 'versionCode', wc.version_code,
            'allUsers', wc.all_users, 'premiumUsers', wc.premium_users,
            'nonPremiumUsers', wc.non_premium_users)
            order by wc.week_start, wc.version_code), '[]'::jsonb) as values from weekly_counts wc
    ), versions as (
        select coalesce(jsonb_agg(version_code order by version_code), '[]'::jsonb) as values
          from (select version_code from daily_counts union select version_code from weekly_counts) v
    )
    select jsonb_build_object('timezone', 'Asia/Kolkata',
        'daily', (select values from daily), 'weekly', (select values from weekly),
        'versionCodes', (select values from versions));
$$;

revoke all on function private.bump_app_version_daily(date,integer,smallint,integer,integer,integer),
    private.bump_app_version_weekly(date,integer,smallint,integer,integer,integer),
    private.track_app_device_version_usage(), public.get_app_version_analytics()
    from public, anon, authenticated;
grant execute on function private.bump_app_version_daily(date,integer,smallint,integer,integer,integer),
    private.bump_app_version_weekly(date,integer,smallint,integer,integer,integer),
    private.track_app_device_version_usage(), public.get_app_version_analytics() to service_role;
