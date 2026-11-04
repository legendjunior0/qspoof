-- Admin-managed supported hardware catalog plus bounded per-device period membership
-- for the Analytics bar selection. Device period rows retain only 7 daily and 7 weekly buckets.
create table if not exists private.supported_device_catalog (
    singleton boolean primary key default true check (singleton),
    categories jsonb not null default '[{"name":"iQOO Neo Series","items":[{"name":"All models except Neo 9 Pro","status":"Supported"}]},{"name":"Vivo Some Series","items":[{"name":"Y60","status":"Not tested"}]}]'::jsonb,
    updated_at timestamptz not null default now(),
    check (jsonb_typeof(categories) = 'array')
);
insert into private.supported_device_catalog(singleton) values (true) on conflict do nothing;

alter table private.supported_device_catalog enable row level security;
alter table private.supported_device_catalog force row level security;
revoke all on private.supported_device_catalog from public, anon, authenticated;
grant select, insert, update on private.supported_device_catalog to service_role;

create table if not exists private.app_device_daily_presence (
    usage_date date not null,
    device_hash text not null check (device_hash ~ '^[0-9a-f]{64}$'),
    was_premium boolean not null,
    version_code integer not null default 0 check (version_code >= 0),
    primary key (usage_date, device_hash)
);
create table if not exists private.app_device_weekly_presence (
    week_start date not null,
    device_hash text not null check (device_hash ~ '^[0-9a-f]{64}$'),
    was_premium boolean not null,
    version_code integer not null default 0 check (version_code >= 0),
    primary key (week_start, device_hash)
);
alter table private.app_device_daily_presence enable row level security;
alter table private.app_device_daily_presence force row level security;
alter table private.app_device_weekly_presence enable row level security;
alter table private.app_device_weekly_presence force row level security;
revoke all on private.app_device_daily_presence, private.app_device_weekly_presence from public, anon, authenticated;
grant select, insert, update, delete on private.app_device_daily_presence, private.app_device_weekly_presence to service_role;

create table if not exists private.app_presence_maintenance (
    singleton boolean primary key default true check (singleton),
    last_pruned_on date not null
);
insert into private.app_presence_maintenance(singleton, last_pruned_on)
values (true, date '0001-01-01') on conflict (singleton) do nothing;
alter table private.app_presence_maintenance enable row level security;
alter table private.app_presence_maintenance force row level security;
revoke all on private.app_presence_maintenance from public, anon, authenticated;
grant select, insert, update on private.app_presence_maintenance to service_role;

insert into private.app_device_daily_presence(usage_date, device_hash, was_premium, version_code)
select u.usage_date, u.device_hash, u.was_premium, coalesce(d.app_version_code, 0)
from public.app_device_daily_usage u
left join public.app_devices d using (device_hash)
where u.usage_date >= (now() at time zone 'Asia/Kolkata')::date - 6
on conflict (usage_date, device_hash) do nothing;
insert into private.app_device_weekly_presence(week_start, device_hash, was_premium, version_code)
select u.usage_date - extract(dow from u.usage_date)::integer, u.device_hash,
       bool_or(u.was_premium), coalesce(max(d.app_version_code), 0)
from public.app_device_daily_usage u
left join public.app_devices d using (device_hash)
where u.usage_date >= (now() at time zone 'Asia/Kolkata')::date - 48
group by u.usage_date - extract(dow from u.usage_date)::integer, u.device_hash
on conflict (week_start, device_hash) do nothing;

create or replace function private.capture_app_device_presence()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
    v_day date := coalesce(new.last_seen_date, (now() at time zone 'Asia/Kolkata')::date);
    v_week date := v_day - extract(dow from v_day)::integer;
begin
    if new.last_seen_date is null then return new; end if;
    insert into private.app_device_daily_presence(usage_date, device_hash, was_premium, version_code)
    values (v_day, new.device_hash, coalesce(new.last_seen_premium, false), greatest(coalesce(new.app_version_code, 0),0))
    on conflict (usage_date, device_hash) do update set
      was_premium = excluded.was_premium, version_code = greatest(excluded.version_code, private.app_device_daily_presence.version_code);
    insert into private.app_device_weekly_presence(week_start, device_hash, was_premium, version_code)
    values (v_week, new.device_hash, coalesce(new.last_seen_premium, false), greatest(coalesce(new.app_version_code, 0),0))
    on conflict (week_start, device_hash) do update set
      was_premium = excluded.was_premium, version_code = greatest(excluded.version_code, private.app_device_weekly_presence.version_code);
    if pg_catalog.pg_try_advisory_xact_lock(81008, 1) then
      if not exists (select 1 from private.app_presence_maintenance where singleton and last_pruned_on >= v_day) then
        delete from private.app_device_daily_presence where usage_date < v_day - 6;
        delete from private.app_device_weekly_presence where week_start < v_week - 42;
        update private.app_presence_maintenance set last_pruned_on = v_day where singleton;
      end if;
    end if;
    return new;
end;
$$;
drop trigger if exists app_devices_capture_period_presence on public.app_devices;
create trigger app_devices_capture_period_presence
after insert or update of last_seen_date, last_seen_premium, app_version_code on public.app_devices
for each row execute function private.capture_app_device_presence();

create or replace function public.get_app_supported_device_catalog()
returns jsonb language sql stable security definer set search_path = '' as $$
  select categories from private.supported_device_catalog where singleton = true;
$$;
create or replace function public.set_app_supported_device_catalog(p_categories jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if p_categories is null or jsonb_typeof(p_categories) <> 'array'
     or jsonb_array_length(p_categories) > 40 then
    raise exception 'Catalog must be an array with no more than 40 categories';
  end if;
  if exists (
    select 1 from jsonb_array_elements(p_categories) c
    where jsonb_typeof(c) <> 'object'
       or jsonb_typeof(c->'name') <> 'string'
       or char_length(btrim(c->>'name')) not between 1 and 80
       or jsonb_typeof(c->'items') <> 'array'
       or jsonb_array_length(c->'items') > 200
  ) then raise exception 'Each category needs a name and up to 200 items'; end if;
  if exists (
    select 1 from jsonb_array_elements(p_categories) c,
      jsonb_array_elements(c->'items') i
    where jsonb_typeof(i) <> 'object'
       or jsonb_typeof(i->'name') <> 'string'
       or char_length(btrim(i->>'name')) not between 1 and 100
       or coalesce(i->>'status','Supported') not in ('Supported','Not tested','Unsupported')
  ) then raise exception 'Each item needs a name and a supported status'; end if;
  update private.supported_device_catalog set categories = p_categories, updated_at = now() where singleton = true;
  return p_categories;
end;
$$;

create or replace function public.get_admin_usage_period_devices(p_period text, p_start date)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_result jsonb;
begin
  if p_period = 'daily' then
    if p_start < (now() at time zone 'Asia/Kolkata')::date - 6
       or p_start > (now() at time zone 'Asia/Kolkata')::date then
      raise exception 'Daily history is limited to the latest 7 days';
    end if;
    select coalesce(jsonb_agg(pg_catalog.jsonb_build_object(
      'model', d.model, 'ramMb', d.ram_mb, 'storageMb', d.storage_mb, 'cpu', d.cpu,
      'appVersionCode', u.version_code, 'ipAddress', d.ip_address::text,
      'name', p.display_name, 'email', p.email, 'premium', u.was_premium,
      'referenceId', coalesce(e.transaction_id, r.transaction_id), 'firstSeenAt', d.first_seen_at
    ) order by d.last_seen_at desc), '[]'::jsonb) into v_result
    from private.app_device_daily_presence u
    join public.app_devices d using (device_hash)
    left join private.app_device_profiles p using (device_hash)
    left join lateral (select transaction_id from public.premium_entitlements x where x.device_hash=d.device_hash order by granted_at desc limit 1) e on true
    left join lateral (select transaction_id from public.premium_requests x where x.device_hash=d.device_hash order by submitted_at desc limit 1) r on true
    where u.usage_date = p_start;
  elsif p_period = 'weekly' then
    if p_start <> p_start - extract(dow from p_start)::integer
       or p_start < ((now() at time zone 'Asia/Kolkata')::date - extract(dow from (now() at time zone 'Asia/Kolkata')::date)::integer) - 42
       or p_start > (now() at time zone 'Asia/Kolkata')::date then
      raise exception 'Weekly history is limited to the latest 7 calendar weeks';
    end if;
    select coalesce(jsonb_agg(pg_catalog.jsonb_build_object(
      'model', d.model, 'ramMb', d.ram_mb, 'storageMb', d.storage_mb, 'cpu', d.cpu,
      'appVersionCode', u.version_code, 'ipAddress', d.ip_address::text,
      'name', p.display_name, 'email', p.email, 'premium', u.was_premium,
      'referenceId', coalesce(e.transaction_id, r.transaction_id), 'firstSeenAt', d.first_seen_at
    ) order by d.last_seen_at desc), '[]'::jsonb) into v_result
    from private.app_device_weekly_presence u
    join public.app_devices d using (device_hash)
    left join private.app_device_profiles p using (device_hash)
    left join lateral (select transaction_id from public.premium_entitlements x where x.device_hash=d.device_hash order by granted_at desc limit 1) e on true
    left join lateral (select transaction_id from public.premium_requests x where x.device_hash=d.device_hash order by submitted_at desc limit 1) r on true
    where u.week_start = p_start;
  else raise exception 'Period must be daily or weekly'; end if;
  return coalesce(v_result, '[]'::jsonb);
end;
$$;

revoke all on function public.get_app_supported_device_catalog(), public.set_app_supported_device_catalog(jsonb), public.get_admin_usage_period_devices(text,date) from public, anon, authenticated;
grant execute on function public.get_app_supported_device_catalog() to service_role;
grant execute on function public.set_app_supported_device_catalog(jsonb), public.get_admin_usage_period_devices(text,date) to service_role;
