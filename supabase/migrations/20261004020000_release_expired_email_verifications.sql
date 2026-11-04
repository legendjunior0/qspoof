-- Let an email verification that was never saved expire cleanly. The profile
-- flow accepts the pending proof for 15 minutes; without this cleanup, the
-- unique pending-email index could reserve an abandoned address indefinitely.
create or replace function public.verify_app_email_otp(
    p_android_id text, p_email text, p_code_hash text
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    v_hash text;
    v_email text := lower(btrim(coalesce(p_email, '')));
    v_challenge private.app_email_otp_challenges%rowtype;
begin
    if p_android_id is null or char_length(p_android_id) not between 8 and 128
       or p_code_hash !~ '^[0-9a-f]{64}$' then
        raise exception 'Invalid verification code';
    end if;

    v_hash := public.hash_premium_device_id(p_android_id);
    select * into v_challenge
      from private.app_email_otp_challenges
      where device_hash = v_hash and email = v_email
      for update;

    if not found or v_challenge.expires_at < now() then
        return pg_catalog.jsonb_build_object('verified', false, 'error', 'Code expired. Request a new code.');
    end if;
    if v_challenge.attempts >= 5 then
        return pg_catalog.jsonb_build_object('verified', false, 'error', 'Too many attempts. Request a new code.');
    end if;
    if v_challenge.code_hash <> p_code_hash then
        update private.app_email_otp_challenges
          set attempts = attempts + 1
          where device_hash = v_hash and email = v_email;
        return pg_catalog.jsonb_build_object('verified', false, 'error', 'Incorrect verification code.');
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
        verified_pending_email_at = now(),
        updated_at = now();
    delete from private.app_email_otp_challenges
      where device_hash = v_hash and email = v_email;
    return pg_catalog.jsonb_build_object('verified', true, 'email', v_email);
exception when unique_violation then
    return pg_catalog.jsonb_build_object('verified', false, 'error', 'This email is already linked to another device.');
end;
$$;
