alter table private.premium_config
    add column if not exists app_remote_config jsonb not null default '{}'::jsonb,
    add column if not exists app_display_version text not null default '1.0',
    add column if not exists app_release_notes text not null default '',
    add column if not exists app_apk_sha256 text not null default '',
    add column if not exists app_apk_storage_path text not null default '';

alter table private.premium_config
    add constraint premium_config_display_version_check
    check (char_length(app_display_version) between 1 and 32)
    not valid;

create or replace function public.get_premium_public_settings()
returns jsonb
language sql
security definer
set search_path = ''
as $$
    select pg_catalog.jsonb_build_object(
        'adsEnabled', config.ads_enabled,
        'premiumAdsEnabled', config.premium_ads_enabled,
        'bannerSlotLimit', config.banner_slot_limit,
        'interstitialsPerDay', config.launch_interstitials_per_day,
        'premiumOnlyAccess', config.premium_only_access,
        'accessCheckIntervalHours', config.app_access_check_interval_hours,
        'latestVersionCode', config.app_latest_version_code,
        'minimumVersionCode', config.app_minimum_version_code,
        'displayVersion', config.app_display_version,
        'releaseNotes', config.app_release_notes,
        'apkSha256', config.app_apk_sha256,
        'apkStoragePath', config.app_apk_storage_path,
        'updateUrl', config.app_update_url,
        'updateMessage', config.app_update_message,
        'channelUrl', config.telegram_channel_url,
        'groupUrl', config.telegram_group_url,
        'remoteConfig', config.app_remote_config
    )
    from private.premium_config as config
    where config.singleton = true;
$$;

create or replace function public.set_app_remote_config(
    p_config jsonb,
    p_display_version text,
    p_release_notes text,
    p_apk_sha256 text,
    p_apk_storage_path text
) returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
begin
    if jsonb_typeof(p_config) <> 'object'
       or p_display_version is null
       or char_length(p_display_version) not between 1 and 32
       or p_release_notes is null
       or p_apk_sha256 is null
       or p_apk_storage_path is null then
        raise exception 'Invalid remote app configuration';
    end if;
    update private.premium_config
       set app_remote_config = p_config,
           app_display_version = p_display_version,
           app_release_notes = p_release_notes,
           app_apk_sha256 = p_apk_sha256,
           app_apk_storage_path = p_apk_storage_path
     where singleton = true;
    return public.get_premium_public_settings();
end;
$$;

revoke all on function public.set_app_remote_config(jsonb, text, text, text, text)
    from public, anon, authenticated;
grant execute on function public.set_app_remote_config(jsonb, text, text, text, text)
    to service_role;
