-- Keep OTP challenge and verification windows aligned at 30 minutes.
-- Resolve donor names from the server-side reference record and return them
-- with the device's verified email and reference ID.

alter table private.app_email_otp_challenges
    alter column expires_at set default (now() + interval '30 minutes');

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
    ) values (v_hash, v_email, p_code_hash, now(), v_today, 1, now() + interval '30 minutes', 0)
    on conflict (device_hash, email) do update set
        code_hash = excluded.code_hash,
        sent_at = now(),
        sent_on = v_today,
        sent_count = case when challenge.sent_on = v_today then least(challenge.sent_count + 1, 5) else 1 end,
        expires_at = now() + interval '30 minutes',
        attempts = 0;
    return true;
end;
$$;

create or replace function public.complete_app_email_otp(
    p_android_id text, p_email text
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    v_hash text;
    v_email text := lower(btrim(coalesce(p_email, '')));
begin
    if p_android_id is null or char_length(p_android_id) not between 8 and 128
       or v_email !~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$' then
        raise exception 'Invalid email verification request';
    end if;
    v_hash := public.hash_premium_device_id(p_android_id);
    if not exists (
        select 1 from private.app_email_otp_challenges
        where device_hash = v_hash and email = v_email
          and expires_at >= now() and sent_at >= now() - interval '30 minutes'
    ) then
        return pg_catalog.jsonb_build_object('verified', false, 'error', 'Code expired. Request a new code.');
    end if;
    update private.app_device_profiles
      set verified_pending_email = '', verified_pending_email_at = null, updated_at = now()
      where lower(verified_pending_email) = v_email
        and verified_pending_email_at < now() - interval '15 minutes';
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
        verified_pending_email_at = excluded.verified_pending_email_at,
        updated_at = now();
    delete from private.app_email_otp_challenges
      where device_hash = v_hash and email = v_email;
    return pg_catalog.jsonb_build_object('verified', true, 'email', v_email);
exception when unique_violation then
    return pg_catalog.jsonb_build_object('verified', false, 'error', 'This email is already linked to another device.');
end;
$$;

create or replace function public.get_app_device_profile(p_android_id text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    v_hash text;
    v_profile private.app_device_profiles%rowtype;
    v_reference text;
    v_server_name text;
    v_request_name text;
begin
    if p_android_id is null or char_length(p_android_id) not between 8 and 128 then
        raise exception 'Invalid device identifier';
    end if;
    v_hash := public.hash_premium_device_id(p_android_id);
    select * into v_profile from private.app_device_profiles where device_hash = v_hash;

    select entitlement.transaction_id, nullif(grant_row.display_name, ''), nullif(request_row.display_name, '')
      into v_reference, v_server_name, v_request_name
      from public.premium_entitlements entitlement
      left join public.premium_reference_grants grant_row
        on grant_row.transaction_id = entitlement.transaction_id
      left join public.premium_requests request_row
        on request_row.transaction_id = entitlement.transaction_id
     where entitlement.device_hash = v_hash
     limit 1;
    if v_reference is null then
        select request_row.transaction_id, nullif(request_row.display_name, '')
          into v_reference, v_request_name
          from public.premium_requests request_row
         where request_row.device_hash = v_hash
         order by request_row.submitted_at desc
         limit 1;
    end if;

    return pg_catalog.jsonb_build_object(
        'name', coalesce(v_server_name, v_request_name, nullif(v_profile.display_name, ''), ''),
        'email', coalesce(v_profile.email, ''),
        'emailVerified', coalesce(v_profile.email_verified, false),
        'pendingEmail', coalesce(v_profile.verified_pending_email, ''),
        'referenceId', v_reference,
        'changedAt', v_profile.changed_at,
        'lockedUntil', v_profile.locked_until
    );
end;
$$;

revoke all on function public.begin_app_email_otp(text,text,text),
    public.complete_app_email_otp(text,text),
    public.get_app_device_profile(text)
    from public, anon, authenticated;
grant execute on function public.begin_app_email_otp(text,text,text),
    public.complete_app_email_otp(text,text),
    public.get_app_device_profile(text)
    to service_role;
