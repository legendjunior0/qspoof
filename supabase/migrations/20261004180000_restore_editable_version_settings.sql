-- Keep the server-side v9 floor in the Edge Function while leaving the
-- admin update controls editable for later release plans.
create or replace function public.set_app_update_config(
    p_latest_version_code integer,
    p_minimum_version_code integer,
    p_update_url text,
    p_update_message text
) returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_url text := btrim(coalesce(p_update_url, ''));
    v_message text := btrim(coalesce(p_update_message, ''));
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
    if char_length(v_message) > 240 then
        raise exception 'Update message is too long';
    end if;

    update private.premium_config
       set app_latest_version_code = p_latest_version_code,
           app_minimum_version_code = p_minimum_version_code,
           app_update_url = v_url,
           app_update_message = coalesce(nullif(v_message, ''), 'An update is required to continue.')
     where singleton = true;
    return public.get_premium_public_settings();
end;
$$;

revoke all on function public.set_app_update_config(integer, integer, text, text)
    from public, anon, authenticated;
grant execute on function public.set_app_update_config(integer, integer, text, text)
    to service_role;
