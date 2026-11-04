alter table private.premium_config
    add column if not exists app_access_check_interval_hours integer not null default 12;

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'premium_config_access_check_interval_hours_check'
          and conrelid = 'private.premium_config'::regclass
    ) then
        alter table private.premium_config
            add constraint premium_config_access_check_interval_hours_check
            check (app_access_check_interval_hours between 1 and 168);
    end if;
end;
$$;

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
        'updateUrl', config.app_update_url,
        'updateMessage', config.app_update_message,
        'channelUrl', config.telegram_channel_url,
        'groupUrl', config.telegram_group_url
    )
    from private.premium_config as config
    where config.singleton = true;
$$;

create or replace function public.set_app_access_policy(
    p_premium_only_access boolean,
    p_access_check_interval_hours integer
) returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
begin
    if p_premium_only_access is null
       or p_access_check_interval_hours is null
       or p_access_check_interval_hours not between 1 and 168 then
        raise exception 'Choose a valid access mode and an interval from 1 to 168 hours';
    end if;

    update private.premium_config
       set premium_only_access = p_premium_only_access,
           app_access_check_interval_hours = p_access_check_interval_hours
     where singleton = true;
    return public.get_premium_public_settings();
end;
$$;

revoke all on function public.set_app_access_policy(boolean, integer)
    from public, anon, authenticated;
grant execute on function public.set_app_access_policy(boolean, integer)
    to service_role;
