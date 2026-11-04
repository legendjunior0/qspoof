-- Allow the app OTP endpoints to use Supabase Auth OTP delivery (configured
-- with Gmail SMTP) while retaining the existing Brevo custom-code path.
create or replace function public.complete_app_email_otp(
    p_android_id text,
    p_email text
) returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
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
          and expires_at >= now() and sent_at >= now() - interval '10 minutes'
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

revoke all on function public.complete_app_email_otp(text, text) from public, anon, authenticated;
grant execute on function public.complete_app_email_otp(text, text) to service_role;
