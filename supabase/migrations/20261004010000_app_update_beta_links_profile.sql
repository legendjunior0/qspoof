-- Version policy, bounded beta enrollment, configurable community links, and
-- per-device profile fields.  Public clients only reach these via the premium
-- Edge Function; raw tables and mutating RPCs remain service-role only.

alter table private.premium_config
    add column if not exists app_latest_version_code integer not null default 0
        check (app_latest_version_code >= 0),
    add column if not exists app_minimum_version_code integer not null default 0
        check (app_minimum_version_code >= 0),
    add column if not exists app_update_url text not null default '',
    add column if not exists app_update_message text not null default 'An update is required to continue.',
    add column if not exists beta_enabled boolean not null default false,
    add column if not exists beta_version_code integer not null default 0
        check (beta_version_code >= 0),
    add column if not exists beta_device_limit integer not null default 50
        check (beta_device_limit between 1 and 10000),
    add column if not exists telegram_channel_url text not null default 'https://t.me/iqoo_neo10_channel',
    add column if not exists telegram_group_url text not null default 'https://t.me/iqoo_neo10';

create table if not exists private.app_beta_registrations (
    version_code integer not null check (version_code > 0),
    device_hash text not null check (device_hash ~ '^[0-9a-f]{64}$'),
    registered_at timestamptz not null default now(),
    primary key (version_code, device_hash)
);
alter table private.app_beta_registrations enable row level security;
revoke all on private.app_beta_registrations from public, anon, authenticated;
grant select, insert, update, delete on private.app_beta_registrations to service_role;

create table if not exists private.app_device_profiles (
    device_hash text primary key check (device_hash ~ '^[0-9a-f]{64}$'),
    display_name text not null default '' check (char_length(display_name) <= 40),
    email text not null default '' check (char_length(email) <= 254),
    changed_at timestamptz,
    change_violations integer not null default 0 check (change_violations >= 0),
    locked_until timestamptz,
    updated_at timestamptz not null default now()
);
alter table private.app_device_profiles
    add column if not exists email_verified boolean not null default false,
    add column if not exists verified_pending_email text not null default '',
    add column if not exists verified_pending_email_at timestamptz;
alter table private.app_device_profiles enable row level security;
revoke all on private.app_device_profiles from public, anon, authenticated;
grant select, insert, update, delete on private.app_device_profiles to service_role;

create unique index if not exists app_device_profiles_unique_verified_email
    on private.app_device_profiles (lower(email))
    where email_verified and email <> '';
create unique index if not exists app_device_profiles_unique_pending_email
    on private.app_device_profiles (lower(verified_pending_email))
    where verified_pending_email <> '';

create table if not exists private.app_email_otp_challenges (
    device_hash text not null check (device_hash ~ '^[0-9a-f]{64}$'),
    email text not null check (char_length(email) between 6 and 254),
    code_hash text not null check (code_hash ~ '^[0-9a-f]{64}$'),
    sent_at timestamptz not null default now(),
    sent_on date not null default ((now() at time zone 'UTC')::date),
    sent_count integer not null default 1 check (sent_count between 1 and 5),
    expires_at timestamptz not null default (now() + interval '10 minutes'),
    attempts integer not null default 0 check (attempts between 0 and 5),
    primary key (device_hash, email)
);
alter table private.app_email_otp_challenges enable row level security;
revoke all on private.app_email_otp_challenges from public, anon, authenticated;
grant select, insert, update, delete on private.app_email_otp_challenges to service_role;

create table if not exists private.app_email_otp_rate_limits (
    device_hash text primary key check (device_hash ~ '^[0-9a-f]{64}$'),
    sent_on date not null default ((now() at time zone 'UTC')::date),
    sent_count integer not null default 1 check (sent_count between 1 and 5),
    last_sent_at timestamptz not null default now()
);
alter table private.app_email_otp_rate_limits enable row level security;
revoke all on private.app_email_otp_rate_limits from public, anon, authenticated;
grant select, insert, update, delete on private.app_email_otp_rate_limits to service_role;

create table if not exists private.app_email_premium_grants (
    email text primary key check (char_length(email) between 6 and 254),
    device_hash text not null unique check (device_hash ~ '^[0-9a-f]{64}$'),
    transaction_id text not null unique check (transaction_id like 'EMAIL-%'),
    granted_at timestamptz not null default now()
);
alter table private.app_email_premium_grants enable row level security;
revoke all on private.app_email_premium_grants from public, anon, authenticated;
grant select, insert, update, delete on private.app_email_premium_grants to service_role;

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
        'betaVersionCode', beta_version_code,
        'betaDeviceLimit', beta_device_limit,
        'betaRegisteredCount', (select count(*) from private.app_beta_registrations where version_code = config.beta_version_code),
        'channelUrl', telegram_channel_url,
        'groupUrl', telegram_group_url
    )
    from private.premium_config as config where singleton = true;
$$;

create or replace function public.set_app_update_config(
    p_latest_version_code integer, p_minimum_version_code integer,
    p_update_url text, p_update_message text
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_url text := btrim(coalesce(p_update_url, '')); v_message text := btrim(coalesce(p_update_message, ''));
begin
    if p_latest_version_code is null or p_minimum_version_code is null
       or p_latest_version_code < 0 or p_minimum_version_code < 0
       or (p_minimum_version_code > 0 and p_latest_version_code < p_minimum_version_code) then
        raise exception 'Version codes must be non-negative and latest must be at least the minimum';
    end if;
    if char_length(v_url) > 1000 or (v_url <> '' and v_url !~ '^https://[^[:space:]]+$') then
        raise exception 'Update link must be a valid HTTPS URL';
    end if;
    if p_minimum_version_code > 0 and v_url = '' then
        raise exception 'Set an update link before requiring an update';
    end if;
    if char_length(v_message) > 240 then raise exception 'Update message is too long'; end if;
    update private.premium_config set
        app_latest_version_code = p_latest_version_code,
        app_minimum_version_code = p_minimum_version_code,
        app_update_url = v_url,
        app_update_message = coalesce(nullif(v_message, ''), 'An update is required to continue.')
    where singleton = true;
    return public.get_premium_public_settings();
end;
$$;

create or replace function public.set_app_beta_config(
    p_enabled boolean, p_version_code integer, p_device_limit integer
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_old_version integer; v_count integer;
begin
    if p_enabled is null or p_version_code is null or p_version_code < 0
       or p_device_limit is null or p_device_limit not between 1 and 10000
       or (p_enabled and p_version_code < 1) then
        raise exception 'Choose a positive beta version and a device limit from 1 to 10000';
    end if;
    select beta_version_code into v_old_version
      from private.premium_config where singleton = true for update;
    if not p_enabled or v_old_version is distinct from p_version_code then
        delete from private.app_beta_registrations;
    end if;
    update private.premium_config set beta_enabled = p_enabled,
        beta_version_code = p_version_code, beta_device_limit = p_device_limit
    where singleton = true;
    select count(*) into v_count from private.app_beta_registrations
      where version_code = p_version_code;
    return pg_catalog.jsonb_build_object(
        'betaEnabled', p_enabled, 'betaVersionCode', p_version_code,
        'betaDeviceLimit', p_device_limit, 'betaRegisteredCount', v_count
    );
end;
$$;

create or replace function public.set_app_telegram_links(p_channel_url text, p_group_url text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_channel text := btrim(coalesce(p_channel_url, '')); v_group text := btrim(coalesce(p_group_url, ''));
begin
    if char_length(v_channel) > 1000 or char_length(v_group) > 1000
       or v_channel !~ '^https://[^[:space:]]+$' or v_group !~ '^https://[^[:space:]]+$' then
        raise exception 'Channel and group links must be valid HTTPS URLs';
    end if;
    update private.premium_config set telegram_channel_url = v_channel, telegram_group_url = v_group
    where singleton = true;
    return public.get_premium_public_settings();
end;
$$;

create or replace function public.get_app_device_profile(p_android_id text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_hash text; v_name text := ''; v_email text := ''; v_changed timestamptz; v_locked timestamptz;
        v_reference text;
begin
    if p_android_id is null or char_length(p_android_id) not between 8 and 128 then raise exception 'Invalid device identifier'; end if;
    v_hash := public.hash_premium_device_id(p_android_id);
    select display_name, email, changed_at, locked_until into v_name, v_email, v_changed, v_locked
    from private.app_device_profiles where device_hash = v_hash;
    select transaction_id into v_reference from public.premium_entitlements
    where device_hash = v_hash limit 1;
    if v_reference is null then
        select transaction_id into v_reference from public.premium_requests
        where device_hash = v_hash order by submitted_at desc limit 1;
    end if;
    return pg_catalog.jsonb_build_object('name', coalesce(v_name,''), 'email', coalesce(v_email,''),
        'referenceId', v_reference, 'changedAt', v_changed, 'lockedUntil', v_locked,
        'emailVerified', coalesce((select email_verified from private.app_device_profiles where device_hash = v_hash), false),
        'pendingEmail', coalesce((select verified_pending_email from private.app_device_profiles where device_hash = v_hash), ''));
end;
$$;

create or replace function public.set_app_device_profile(
    p_android_id text, p_display_name text, p_email text
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_hash text; v_name text := btrim(coalesce(p_display_name,'')); v_email text := lower(btrim(coalesce(p_email,'')));
        v_old private.app_device_profiles%rowtype; v_violations integer; v_lock interval;
        v_email_verified boolean := false;
begin
    if p_android_id is null or char_length(p_android_id) not between 8 and 128 then raise exception 'Invalid device identifier'; end if;
    if char_length(v_name) > 40 or v_name ~ '[[:cntrl:]]' then raise exception 'Name must be at most 40 characters'; end if;
    if char_length(v_email) > 254 or (v_email <> '' and v_email !~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$') then raise exception 'Enter a valid email address'; end if;
    v_hash := public.hash_premium_device_id(p_android_id);
    insert into private.app_device_profiles(device_hash) values(v_hash) on conflict do nothing;
    select * into v_old from private.app_device_profiles where device_hash = v_hash for update;
    if v_old.locked_until is not null and v_old.locked_until > now() then
        return pg_catalog.jsonb_build_object('saved', false, 'error', 'Profile changes are temporarily locked', 'lockedUntil', v_old.locked_until);
    end if;
    if v_name = v_old.display_name and v_email = v_old.email
       and not (v_email <> '' and v_email = lower(v_old.verified_pending_email)
           and v_old.verified_pending_email_at >= now() - interval '15 minutes') then
        return pg_catalog.jsonb_build_object('saved', true, 'name', v_old.display_name, 'email', v_old.email,
            'emailVerified', v_old.email_verified, 'changedAt', v_old.changed_at);
    end if;
    if v_email = v_old.email then
        v_email_verified := v_old.email_verified;
        if not v_email_verified and v_email <> ''
           and v_email = lower(v_old.verified_pending_email)
           and v_old.verified_pending_email_at is not null
           and v_old.verified_pending_email_at >= now() - interval '15 minutes' then
            v_email_verified := true;
        end if;
    elsif v_email <> '' then
        if v_email <> lower(v_old.verified_pending_email)
           or v_old.verified_pending_email_at is null
           or v_old.verified_pending_email_at < now() - interval '15 minutes' then
            raise exception 'Verify this email address before saving it';
        end if;
        v_email_verified := true;
    end if;
    if v_old.changed_at is not null and v_old.changed_at > now() - interval '24 hours' then
        v_violations := v_old.change_violations + 1;
        v_lock := make_interval(days => case when v_violations = 1 then 1 when v_violations = 2 then 3 when v_violations = 3 then 7 else least(30, 7 + (v_violations - 3) * 7) end);
        update private.app_device_profiles set change_violations = v_violations,
            locked_until = now() + v_lock, updated_at = now() where device_hash = v_hash;
        return pg_catalog.jsonb_build_object('saved', false, 'error', 'Name and email can be changed once every 24 hours', 'lockedUntil', now() + v_lock);
    end if;
    update private.app_device_profiles set display_name = v_name, email = v_email,
        email_verified = v_email_verified, verified_pending_email = '', verified_pending_email_at = null,
        changed_at = now(), locked_until = null, updated_at = now() where device_hash = v_hash;
    return pg_catalog.jsonb_build_object('saved', true, 'name', v_name, 'email', v_email,
        'emailVerified', v_email_verified, 'changedAt', now());
end;
$$;

-- Enforce one first-come beta slot per non-premium device and configured app
-- version. Existing premium entitlements always bypass beta enrollment.
drop function if exists public.check_app_startup(text, text, integer, integer, text);
drop function if exists public.check_app_startup(text, text, integer, integer, text, inet);
create function public.check_app_startup(
    p_android_id text, p_model text default null, p_ram_mb integer default null,
    p_storage_mb integer default null, p_cpu text default null,
    p_ip_address inet default null, p_version_code integer default 0
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_hash text; v_ads boolean; v_premium_only boolean; v_premium boolean;
        v_telemetry boolean := false; v_min_version integer; v_latest integer;
        v_update_url text; v_update_message text; v_channel text; v_group text;
        v_beta_enabled boolean; v_beta_version integer; v_beta_limit integer;
        v_beta_allowed boolean := true; v_registered boolean; v_count integer;
begin
    if p_android_id is null or char_length(p_android_id) not between 8 and 128 then raise exception 'Invalid device identifier'; end if;
    v_hash := public.hash_premium_device_id(p_android_id);
    select ads_enabled, premium_only_access, app_minimum_version_code, app_latest_version_code,
        app_update_url, app_update_message, telegram_channel_url, telegram_group_url,
        beta_enabled, beta_version_code, beta_device_limit
      into v_ads, v_premium_only, v_min_version, v_latest, v_update_url, v_update_message,
        v_channel, v_group, v_beta_enabled, v_beta_version, v_beta_limit
    from private.premium_config where singleton = true;
    if v_ads is null or v_premium_only is null then raise exception 'App settings are not configured'; end if;
    v_premium := exists(select 1 from public.premium_entitlements where device_hash = v_hash);
    if v_beta_enabled and p_version_code = v_beta_version and p_version_code >= v_min_version and not v_premium then
        select exists(select 1 from private.app_beta_registrations where version_code = v_beta_version and device_hash = v_hash) into v_registered;
        if not v_registered then
            perform 1 from private.premium_config where singleton = true for update;
            select beta_enabled, beta_version_code, beta_device_limit into v_beta_enabled, v_beta_version, v_beta_limit
              from private.premium_config where singleton = true;
            if v_beta_enabled and p_version_code = v_beta_version then
                select exists(select 1 from private.app_beta_registrations where version_code = v_beta_version and device_hash = v_hash)
                  into v_registered;
                if not v_registered then
                    select count(*) into v_count from private.app_beta_registrations where version_code = v_beta_version;
                    if v_count >= v_beta_limit then v_beta_allowed := false;
                    else
                        insert into private.app_beta_registrations(version_code, device_hash)
                        values(v_beta_version, v_hash) on conflict do nothing;
                    end if;
                end if;
            end if;
        end if;
    end if;
    begin
        if v_beta_allowed and coalesce(p_version_code, 0) >= v_min_version
           and p_model is not null and p_ram_mb is not null and p_storage_mb is not null and p_cpu is not null then
            v_telemetry := public.record_app_device_usage(v_hash, p_model, p_ram_mb, p_storage_mb, p_cpu);
        end if;
    exception when others then v_telemetry := false;
    end;
    if p_ip_address is not null then
        begin update public.app_devices set ip_address = p_ip_address where device_hash = v_hash;
        exception when others then null; end;
    end if;
    select count(*) into v_count from private.app_beta_registrations where version_code = v_beta_version;
    return pg_catalog.jsonb_build_object(
        'premium', v_premium, 'premiumOnlyAccess', v_premium_only,
        'accessAllowed', (
            (not v_premium_only or v_premium or
                (v_beta_enabled and p_version_code = v_beta_version
                    and p_version_code >= v_min_version and v_beta_allowed))
            and v_beta_allowed
        ),
        'adsEnabled', v_ads, 'telemetryRecorded', v_telemetry,
        'minimumVersionCode', v_min_version, 'latestVersionCode', v_latest,
        'updateUrl', v_update_url, 'updateMessage', v_update_message,
        'channelUrl', v_channel, 'groupUrl', v_group,
        'betaFull', not v_beta_allowed, 'betaEnabled', v_beta_enabled,
        'betaVersionCode', v_beta_version, 'betaDeviceLimit', v_beta_limit,
        'betaRegisteredCount', v_count
    );
end; $$;

-- Extend the admin-only analytics payload with account fields, without exposing
-- the anonymized device key.
create or replace function public.get_app_usage_analytics(p_days integer default 7)
returns jsonb language sql stable security definer set search_path = '' as $$
    with inputs as (
        select least(greatest(coalesce(p_days, 7), 1), 7) as days,
            (now() at time zone 'Asia/Kolkata')::date as today,
            ((now() at time zone 'Asia/Kolkata')::date - extract(dow from (now() at time zone 'Asia/Kolkata')::date)::integer) as this_week
    ), dates as (
        select generate_series((today - days + 1)::timestamp, today::timestamp, interval '1 day')::date as usage_date from inputs
    ), weeks as (
        select generate_series((this_week - 42)::timestamp, this_week::timestamp, interval '7 days')::date as week_start from inputs
    ), daily_counts as (
        select usage_date, sum(total_users)::integer total_users, sum(premium_users)::integer premium_users, sum(non_premium_users)::integer non_premium_users
        from public.app_usage_daily group by usage_date
    ), weekly_counts as (
        select week_start, sum(total_users)::integer total_users, sum(premium_users)::integer premium_users, sum(non_premium_users)::integer non_premium_users
        from public.app_usage_weekly group by week_start
    ), daily as (
        select coalesce(jsonb_agg(jsonb_build_object('date', dates.usage_date, 'allUsers', coalesce(counts.total_users,0),
            'premiumUsers', coalesce(counts.premium_users,0), 'nonPremiumUsers', coalesce(counts.non_premium_users,0)) order by dates.usage_date),'[]'::jsonb) values
        from dates left join daily_counts counts on counts.usage_date = dates.usage_date
    ), weekly as (
        select coalesce(jsonb_agg(jsonb_build_object('weekStart', weeks.week_start, 'weekEnd', weeks.week_start + 6,
            'allUsers', coalesce(counts.total_users,0), 'premiumUsers', coalesce(counts.premium_users,0),
            'nonPremiumUsers', coalesce(counts.non_premium_users,0)) order by weeks.week_start),'[]'::jsonb) values
        from weeks left join weekly_counts counts on counts.week_start = weeks.week_start
    ), devices as (
        select coalesce(jsonb_agg(jsonb_build_object('model', device.model, 'ramMb', device.ram_mb,
            'storageMb', device.storage_mb, 'cpu', device.cpu, 'ipAddress', device.ip_address::text,
            'name', profile.display_name, 'email', profile.email,
            'emailVerified', coalesce(profile.email_verified, false),
            'premium', exists(select 1 from public.premium_entitlements e where e.device_hash=device.device_hash),
            'firstSeenAt', device.first_seen_at) order by device.first_seen_at desc),'[]'::jsonb) values
        from public.app_devices device left join private.app_device_profiles profile using(device_hash)
    ), filters as (
        select jsonb_build_object(
            'ramMb', coalesce((select jsonb_agg(value order by value) from (select distinct ram_mb value from public.app_devices) o),'[]'::jsonb),
            'storageMb', coalesce((select jsonb_agg(value order by value) from (select distinct storage_mb value from public.app_devices) o),'[]'::jsonb),
            'cpus', coalesce((select jsonb_agg(value order by value) from (select distinct cpu value from public.app_devices) o),'[]'::jsonb),
            'models', coalesce((select jsonb_agg(value order by value) from (select distinct model value from public.app_devices) o),'[]'::jsonb)
        ) values
    )
    select jsonb_build_object('timezone','Asia/Kolkata','daily',(select values from daily),
        'weekly',(select values from weekly),'devices',(select values from devices),'filters',(select values from filters));
$$;

revoke all on function public.set_app_update_config(integer,integer,text,text),
    public.set_app_beta_config(boolean,integer,integer), public.set_app_telegram_links(text,text),
    public.get_app_device_profile(text), public.set_app_device_profile(text,text,text),
    public.check_app_startup(text,text,integer,integer,text,inet,integer)
    from public, anon, authenticated;
grant execute on function public.set_app_update_config(integer,integer,text,text),
    public.set_app_beta_config(boolean,integer,integer), public.set_app_telegram_links(text,text),
    public.get_app_device_profile(text), public.set_app_device_profile(text,text,text),
    public.check_app_startup(text,text,integer,integer,text,inet,integer)
    to service_role;
revoke all on function public.get_app_usage_analytics(integer) from public, anon, authenticated;
grant execute on function public.get_app_usage_analytics(integer) to service_role;

create or replace function public.begin_app_email_otp(
    p_android_id text, p_email text, p_code_hash text
) returns boolean language plpgsql security definer set search_path = '' as $$
declare v_hash text; v_email text := lower(btrim(coalesce(p_email,''))); v_allowed boolean := false;
        v_today date := (now() at time zone 'UTC')::date;
begin
    if p_android_id is null or char_length(p_android_id) not between 8 and 128
       or v_email !~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'
       or p_code_hash !~ '^[0-9a-f]{64}$' then raise exception 'Invalid email verification request'; end if;
    v_hash := public.hash_premium_device_id(p_android_id);
    delete from private.app_email_otp_challenges where sent_at < now() - interval '30 days';
    insert into private.app_email_otp_rate_limits as limiter(device_hash, sent_on, sent_count, last_sent_at)
      values (v_hash, v_today, 1, now())
    on conflict (device_hash) do update set
      sent_count = case when limiter.sent_on = v_today then limiter.sent_count + 1 else 1 end,
      sent_on = v_today,
      last_sent_at = now()
    where limiter.last_sent_at <= now() - interval '60 seconds'
      and (limiter.sent_on <> v_today or limiter.sent_count < 5)
    returning true into v_allowed;
    if not coalesce(v_allowed, false) then return false; end if;
    insert into private.app_email_otp_challenges as challenge(
        device_hash, email, code_hash, sent_at, sent_on, sent_count, expires_at, attempts
    ) values (v_hash, v_email, p_code_hash, now(), v_today, 1, now() + interval '10 minutes', 0)
    on conflict (device_hash, email) do update set
        code_hash = excluded.code_hash,
        sent_at = now(),
        sent_on = v_today,
        sent_count = case when challenge.sent_on = v_today then challenge.sent_count + 1 else 1 end,
        expires_at = now() + interval '10 minutes',
        attempts = 0
    returning true into v_allowed;
    return coalesce(v_allowed, false);
end;
$$;

create or replace function public.verify_app_email_otp(
    p_android_id text, p_email text, p_code_hash text
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_hash text; v_email text := lower(btrim(coalesce(p_email,'')));
        v_challenge private.app_email_otp_challenges%rowtype;
begin
    if p_android_id is null or char_length(p_android_id) not between 8 and 128
       or p_code_hash !~ '^[0-9a-f]{64}$' then raise exception 'Invalid verification code'; end if;
    v_hash := public.hash_premium_device_id(p_android_id);
    select * into v_challenge from private.app_email_otp_challenges
      where device_hash = v_hash and email = v_email for update;
    if not found or v_challenge.expires_at < now() then
        return pg_catalog.jsonb_build_object('verified', false, 'error', 'Code expired. Request a new code.');
    end if;
    if v_challenge.attempts >= 5 then
        return pg_catalog.jsonb_build_object('verified', false, 'error', 'Too many attempts. Request a new code.');
    end if;
    if v_challenge.code_hash <> p_code_hash then
        update private.app_email_otp_challenges set attempts = attempts + 1
          where device_hash = v_hash and email = v_email;
        return pg_catalog.jsonb_build_object('verified', false, 'error', 'Incorrect verification code.');
    end if;
    if exists (
        select 1 from private.app_device_profiles
        where lower(email) = v_email and email_verified and device_hash <> v_hash
    ) or exists (
        select 1 from private.app_device_profiles
        where lower(verified_pending_email) = v_email and device_hash <> v_hash
    ) then
        return pg_catalog.jsonb_build_object('verified', false, 'error', 'This email is already linked to another device.');
    end if;
    insert into private.app_device_profiles(device_hash, verified_pending_email, verified_pending_email_at)
      values (v_hash, v_email, now())
    on conflict (device_hash) do update set
      verified_pending_email = excluded.verified_pending_email,
      verified_pending_email_at = now(), updated_at = now();
    delete from private.app_email_otp_challenges where device_hash = v_hash and email = v_email;
    return pg_catalog.jsonb_build_object('verified', true, 'email', v_email);
exception when unique_violation then
    return pg_catalog.jsonb_build_object('verified', false, 'error', 'This email is already linked to another device.');
end;
$$;

create or replace function public.grant_app_premium_by_verified_email(p_email text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_email text := lower(btrim(coalesce(p_email,''))); v_hash text; v_name text;
        v_reference text; v_granted_at timestamptz;
begin
    if v_email !~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$' then
        raise exception 'Enter a valid email address';
    end if;
    select device_hash, display_name into v_hash, v_name
      from private.app_device_profiles where lower(email) = v_email and email_verified;
    if v_hash is null then raise exception 'No device has verified this email yet'; end if;
    select transaction_id, granted_at into v_reference, v_granted_at
      from private.app_email_premium_grants where email = v_email;
    if v_reference is not null then
        if exists(select 1 from private.app_email_premium_grants where email = v_email and device_hash = v_hash) then
            return pg_catalog.jsonb_build_object('granted', true, 'alreadyGranted', true,
                'email', v_email, 'displayName', v_name, 'transactionId', v_reference, 'grantedAt', v_granted_at);
        end if;
        raise exception 'This email is already registered to another device';
    end if;
    if exists(select 1 from public.premium_entitlements where device_hash = v_hash) then
        raise exception 'This device already has premium access';
    end if;
    v_reference := 'EMAIL-' || upper(substr(encode(extensions.digest(v_email, 'sha256'), 'hex'), 1, 32));
    insert into public.premium_entitlements(device_hash, transaction_id) values (v_hash, v_reference);
    insert into private.app_email_premium_grants(email, device_hash, transaction_id)
      values(v_email, v_hash, v_reference) returning granted_at into v_granted_at;
    update public.app_devices set last_premium = true where device_hash = v_hash;
    return pg_catalog.jsonb_build_object('granted', true, 'alreadyGranted', false,
        'email', v_email, 'displayName', v_name, 'transactionId', v_reference, 'grantedAt', v_granted_at);
exception when unique_violation then
    return pg_catalog.jsonb_build_object('granted', false, 'error', 'Email or device already has a premium grant');
end;
$$;

create or replace function public.list_app_email_premium_grants()
returns jsonb language sql stable security definer set search_path = '' as $$
    select coalesce(jsonb_agg(jsonb_build_object(
        'email', grants.email, 'deviceHash', grants.device_hash,
        'transactionId', grants.transaction_id, 'grantedAt', grants.granted_at,
        'displayName', profile.display_name
    ) order by grants.granted_at desc), '[]'::jsonb)
    from private.app_email_premium_grants grants
    left join private.app_device_profiles profile on profile.device_hash = grants.device_hash;
$$;

create or replace function public.revoke_app_email_premium_grant(p_transaction_id text)
returns boolean language plpgsql security definer set search_path = '' as $$
declare v_reference text := upper(btrim(coalesce(p_transaction_id,''))); v_device_hash text;
begin
    delete from private.app_email_premium_grants where transaction_id = v_reference
      returning device_hash into v_device_hash;
    if v_device_hash is null then return false; end if;
    delete from public.premium_entitlements where transaction_id = v_reference;
    update public.app_devices set last_premium = false where device_hash = v_device_hash
      and not exists(select 1 from public.premium_entitlements where device_hash = v_device_hash);
    return true;
end;
$$;

create or replace function public.delink_app_email_premium_grant(p_device_hash text, p_transaction_id text)
returns boolean language plpgsql security definer set search_path = '' as $$
declare v_reference text := upper(btrim(coalesce(p_transaction_id,''))); v_deleted text;
begin
    delete from private.app_email_premium_grants where device_hash = p_device_hash and transaction_id = v_reference
      returning transaction_id into v_deleted;
    if v_deleted is null then return false; end if;
    delete from public.premium_entitlements where device_hash = p_device_hash and transaction_id = v_reference;
    update public.app_devices set last_premium = false where device_hash = p_device_hash
      and not exists(select 1 from public.premium_entitlements where device_hash = p_device_hash);
    return true;
end;
$$;

create or replace function public.get_app_device_profile(p_android_id text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_hash text; v_profile private.app_device_profiles%rowtype; v_reference text;
begin
    if p_android_id is null or char_length(p_android_id) not between 8 and 128 then raise exception 'Invalid device identifier'; end if;
    v_hash := public.hash_premium_device_id(p_android_id);
    select * into v_profile from private.app_device_profiles where device_hash = v_hash;
    select transaction_id into v_reference from public.premium_entitlements where device_hash = v_hash;
    if v_reference is null then
        select transaction_id into v_reference from public.premium_requests where device_hash = v_hash order by submitted_at desc limit 1;
    end if;
    return pg_catalog.jsonb_build_object(
        'name', coalesce(v_profile.display_name,''), 'email', coalesce(v_profile.email,''),
        'emailVerified', coalesce(v_profile.email_verified,false),
        'pendingEmail', coalesce(v_profile.verified_pending_email,''),
        'referenceId', v_reference, 'changedAt', v_profile.changed_at, 'lockedUntil', v_profile.locked_until
    );
end;
$$;

create or replace function public.set_app_device_profile(
    p_android_id text, p_display_name text, p_email text
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_hash text; v_name text := btrim(coalesce(p_display_name,''));
        v_email text := lower(btrim(coalesce(p_email,'')));
        v_old private.app_device_profiles%rowtype; v_violations integer; v_lock interval;
        v_email_verified boolean := false;
begin
    if p_android_id is null or char_length(p_android_id) not between 8 and 128 then raise exception 'Invalid device identifier'; end if;
    if char_length(v_name) > 40 or v_name ~ '[[:cntrl:]]' then raise exception 'Name must be at most 40 characters'; end if;
    if char_length(v_email) > 254 or (v_email <> '' and v_email !~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$') then raise exception 'Enter a valid email address'; end if;
    v_hash := public.hash_premium_device_id(p_android_id);
    insert into private.app_device_profiles(device_hash) values(v_hash) on conflict do nothing;
    select * into v_old from private.app_device_profiles where device_hash = v_hash for update;
    if v_old.locked_until is not null and v_old.locked_until > now() then
        return pg_catalog.jsonb_build_object('saved', false, 'error', 'Profile changes are temporarily locked', 'lockedUntil', v_old.locked_until);
    end if;
    if v_name = v_old.display_name and v_email = v_old.email
       and not (v_email <> '' and v_email = lower(v_old.verified_pending_email)
           and v_old.verified_pending_email_at >= now() - interval '15 minutes') then
        return pg_catalog.jsonb_build_object('saved', true, 'name', v_old.display_name, 'email', v_old.email,
            'emailVerified', v_old.email_verified, 'changedAt', v_old.changed_at);
    end if;
    if v_email = v_old.email then
        v_email_verified := v_old.email_verified;
        if not v_email_verified and v_email <> ''
           and v_email = lower(v_old.verified_pending_email)
           and v_old.verified_pending_email_at is not null
           and v_old.verified_pending_email_at >= now() - interval '15 minutes' then
            v_email_verified := true;
        end if;
    elsif v_email <> '' then
        if v_email <> lower(v_old.verified_pending_email)
           or v_old.verified_pending_email_at is null
           or v_old.verified_pending_email_at < now() - interval '15 minutes' then
            raise exception 'Verify this email address before saving it';
        end if;
        v_email_verified := true;
    end if;
    if v_old.changed_at is not null and v_old.changed_at > now() - interval '24 hours' then
        v_violations := v_old.change_violations + 1;
        v_lock := make_interval(days => case when v_violations = 1 then 1 when v_violations = 2 then 3 when v_violations = 3 then 7 else least(30, 7 + (v_violations - 3) * 7) end);
        update private.app_device_profiles set change_violations = v_violations,
            locked_until = now() + v_lock, updated_at = now() where device_hash = v_hash;
        return pg_catalog.jsonb_build_object('saved', false, 'error', 'Name and email can be changed once every 24 hours', 'lockedUntil', now() + v_lock);
    end if;
    update private.app_device_profiles set display_name = v_name, email = v_email,
        email_verified = v_email_verified, verified_pending_email = '', verified_pending_email_at = null,
        changed_at = now(), locked_until = null, updated_at = now() where device_hash = v_hash;
    return pg_catalog.jsonb_build_object('saved', true, 'name', v_name, 'email', v_email,
        'emailVerified', v_email_verified, 'changedAt', now());
end;
$$;

revoke all on function public.begin_app_email_otp(text,text,text),
    public.verify_app_email_otp(text,text,text), public.grant_app_premium_by_verified_email(text),
    public.list_app_email_premium_grants(), public.revoke_app_email_premium_grant(text),
    public.delink_app_email_premium_grant(text,text)
    from public, anon, authenticated;
grant execute on function public.begin_app_email_otp(text,text,text),
    public.verify_app_email_otp(text,text,text), public.grant_app_premium_by_verified_email(text),
    public.list_app_email_premium_grants(), public.revoke_app_email_premium_grant(text),
    public.delink_app_email_premium_grant(text,text)
    to service_role;
revoke all on function public.get_app_device_profile(text), public.set_app_device_profile(text,text,text)
    from public, anon, authenticated;
grant execute on function public.get_app_device_profile(text), public.set_app_device_profile(text,text,text)
    to service_role;
