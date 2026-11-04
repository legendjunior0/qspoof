-- Keep compact beta logs for three days, apply per-device profile/email limits,
-- and allow the owner's test inbox to bypass only this app's OTP limiter.

alter table private.app_device_profiles
    add column if not exists profile_changes_on date,
    add column if not exists profile_changes_count integer not null default 0
        check (profile_changes_count between 0 and 5);

update private.app_device_profiles
   set profile_changes_on = (changed_at at time zone 'UTC')::date,
       profile_changes_count = 1
 where changed_at >= now() - interval '24 hours'
   and profile_changes_on is null;

create extension if not exists pg_cron;

delete from private.app_beta_access_logs
 where last_seen_at < now() - interval '3 days';

create or replace function public.get_app_beta_access_logs()
returns jsonb language sql stable security definer set search_path = '' as $$
    select coalesce(jsonb_agg(pg_catalog.jsonb_build_object(
        'deviceId', left(log.device_hash, 12), 'versionCode', log.version_code,
        'model', log.model, 'ramMb', log.ram_mb, 'storageMb', log.storage_mb,
        'cpu', log.cpu, 'ipAddress', log.ip_address::text,
        'name', profile.display_name, 'email', profile.email,
        'premium', log.premium, 'accessAllowed', log.access_allowed,
        'firstSeenAt', log.first_seen_at, 'lastSeenAt', log.last_seen_at
    ) order by log.last_seen_at desc), '[]'::jsonb)
    from private.app_beta_access_logs log
    left join private.app_device_profiles profile using (device_hash)
    where log.last_seen_at >= now() - interval '3 days';
$$;

revoke all on function public.get_app_beta_access_logs() from public, anon, authenticated;
grant execute on function public.get_app_beta_access_logs() to service_role;

do $$
declare v_job_id bigint;
begin
    for v_job_id in select jobid from cron.job where jobname = 'qspoof-prune-beta-logs'
    loop
        perform cron.unschedule(v_job_id);
    end loop;
    perform cron.schedule(
        'qspoof-prune-beta-logs',
        '*/10 * * * *',
        $job$delete from private.app_beta_access_logs where last_seen_at < now() - interval '3 days'$job$
    );
end;
$$;

create or replace function public.begin_app_email_otp(
    p_android_id text, p_email text, p_code_hash text
) returns boolean language plpgsql security definer set search_path = '' as $$
declare
    v_hash text;
    v_email text := lower(btrim(coalesce(p_email,'')));
    v_allowed boolean := false;
    v_email_limit integer := 5;
    v_email_already_linked boolean := false;
    v_today date := (now() at time zone 'UTC')::date;
    v_test_inbox boolean;
begin
    if p_android_id is null or char_length(p_android_id) not between 8 and 128
       or v_email !~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'
       or p_code_hash !~ '^[0-9a-f]{64}$' then
        raise exception 'Invalid email verification request';
    end if;
    v_hash := public.hash_premium_device_id(p_android_id);
    v_test_inbox := v_email = 'adityakumar.as12345@gmail.com';

    select exists (
        select 1 from private.app_device_profiles
         where device_hash = v_hash and email_verified and lower(email) = v_email
    ) into v_email_already_linked;
    if v_email_already_linked then v_email_limit := 3; end if;

    delete from private.app_email_otp_challenges where sent_at < now() - interval '30 days';
    insert into private.app_email_otp_rate_limits as limiter(device_hash, sent_on, sent_count, last_sent_at)
      values (v_hash, v_today, 1, now())
    on conflict (device_hash) do update set
      sent_count = case when limiter.sent_on = v_today then least(limiter.sent_count + 1, 5) else 1 end,
      sent_on = v_today,
      last_sent_at = now()
    where v_test_inbox
       or (limiter.last_sent_at <= now() - interval '60 seconds'
           and (limiter.sent_on <> v_today or limiter.sent_count < v_email_limit))
    returning true into v_allowed;
    if not coalesce(v_allowed, false) then return false; end if;

    insert into private.app_email_otp_challenges as challenge(
        device_hash, email, code_hash, sent_at, sent_on, sent_count, expires_at, attempts
    ) values (v_hash, v_email, p_code_hash, now(), v_today, 1, now() + interval '10 minutes', 0)
    on conflict (device_hash, email) do update set
        code_hash = excluded.code_hash,
        sent_at = now(),
        sent_on = v_today,
        sent_count = case when challenge.sent_on = v_today then least(challenge.sent_count + 1, 5) else 1 end,
        expires_at = now() + interval '10 minutes',
        attempts = 0;
    return true;
end;
$$;

create or replace function public.set_app_device_profile(
    p_android_id text, p_display_name text, p_email text
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    v_hash text;
    v_name text := btrim(coalesce(p_display_name,''));
    v_email text := lower(btrim(coalesce(p_email,'')));
    v_old private.app_device_profiles%rowtype;
    v_violations integer;
    v_lock interval;
    v_email_verified boolean := false;
    v_daily_limit integer;
    v_today date := (now() at time zone 'UTC')::date;
    v_count integer;
begin
    if p_android_id is null or char_length(p_android_id) not between 8 and 128 then
        raise exception 'Invalid device identifier';
    end if;
    if char_length(v_name) > 40 or v_name ~ '[[:cntrl:]]' then
        raise exception 'Name must be at most 40 characters';
    end if;
    if char_length(v_email) > 254 or (v_email <> '' and v_email !~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$') then
        raise exception 'Enter a valid email address';
    end if;
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

    v_daily_limit := case when v_old.email_verified and v_old.email <> '' then 3 else 5 end;
    v_count := case when v_old.profile_changes_on = v_today then v_old.profile_changes_count else 0 end;
    if v_count >= v_daily_limit then
        v_violations := v_old.change_violations + 1;
        v_lock := make_interval(days => case when v_violations = 1 then 1 when v_violations = 2 then 3 when v_violations = 3 then 7 else least(30, 7 + (v_violations - 3) * 7) end);
        update private.app_device_profiles set change_violations = v_violations,
            locked_until = now() + v_lock, updated_at = now() where device_hash = v_hash;
        return pg_catalog.jsonb_build_object('saved', false, 'error', 'Daily profile change limit reached', 'lockedUntil', now() + v_lock);
    end if;

    update private.app_device_profiles set display_name = v_name, email = v_email,
        email_verified = v_email_verified, verified_pending_email = '', verified_pending_email_at = null,
        changed_at = now(), profile_changes_on = v_today, profile_changes_count = v_count + 1,
        locked_until = null, updated_at = now() where device_hash = v_hash;
    return pg_catalog.jsonb_build_object('saved', true, 'name', v_name, 'email', v_email,
        'emailVerified', v_email_verified, 'changedAt', now(), 'changesRemaining', v_daily_limit - v_count - 1);
end;
$$;

revoke all on function public.begin_app_email_otp(text,text,text),
    public.set_app_device_profile(text,text,text),
    public.get_app_beta_access_logs()
    from public, anon, authenticated;
grant execute on function public.begin_app_email_otp(text,text,text),
    public.set_app_device_profile(text,text,text),
    public.get_app_beta_access_logs()
    to service_role;
