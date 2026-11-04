alter table private.premium_config
    add column if not exists app_popups jsonb not null default '[]'::jsonb;

insert into storage.buckets (id, name, public)
values ('app-updates', 'app-updates', false)
on conflict (id) do update set public = excluded.public;

create or replace function public.set_app_popups(p_popups jsonb)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
begin
    if jsonb_typeof(p_popups) <> 'array' or jsonb_array_length(p_popups) > 20 then
        raise exception 'Popup configuration must be an array with at most 20 entries';
    end if;
    update private.premium_config
       set app_popups = p_popups
     where singleton = true;
    return p_popups;
end;
$$;

revoke all on function public.set_app_popups(jsonb) from public, anon, authenticated;
grant execute on function public.set_app_popups(jsonb) to service_role;

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
        'remoteConfig', config.app_remote_config,
        'popups', config.app_popups
    )
    from private.premium_config as config
    where config.singleton = true;
$$;
