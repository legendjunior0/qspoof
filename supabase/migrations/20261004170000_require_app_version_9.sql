-- Version 9 is the minimum supported Android client. The Edge Function also
-- enforces this floor for every app action and rejects missing version fields.
update private.premium_config
   set app_minimum_version_code = greatest(app_minimum_version_code, 9),
       app_latest_version_code = greatest(app_latest_version_code, app_minimum_version_code, 9)
 where singleton = true;

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
       or p_latest_version_code < 9 or p_minimum_version_code < 9
       or p_latest_version_code < p_minimum_version_code then
        raise exception 'Latest and minimum app versions must be at least version code 9';
    end if;
    if char_length(v_url) > 1000 or (v_url <> '' and v_url !~ '^https://[^[:space:]]+$') then
        raise exception 'Update link must be a valid HTTPS URL';
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
