alter table private.premium_config
    add column if not exists premium_ads_enabled boolean not null default false,
    add column if not exists banner_slot_limit integer not null default 6,
    add column if not exists launch_interstitials_per_day integer not null default 2;

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'premium_config_banner_slot_limit_check'
          and conrelid = 'private.premium_config'::regclass
    ) then
        alter table private.premium_config
            add constraint premium_config_banner_slot_limit_check
            check (banner_slot_limit between 0 and 6);
    end if;
    if not exists (
        select 1 from pg_constraint
        where conname = 'premium_config_launch_interstitials_per_day_check'
          and conrelid = 'private.premium_config'::regclass
    ) then
        alter table private.premium_config
            add constraint premium_config_launch_interstitials_per_day_check
            check (launch_interstitials_per_day between 0 and 2);
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

create or replace function public.set_premium_ads_config(p_config jsonb)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_ads_enabled boolean;
    v_premium_ads_enabled boolean;
    v_banner_limit integer;
    v_interstitial_limit integer;
begin
    if pg_catalog.jsonb_typeof(p_config->'adsEnabled') is distinct from 'boolean'
       or pg_catalog.jsonb_typeof(p_config->'premiumAdsEnabled') is distinct from 'boolean'
       or (p_config->>'bannerSlotLimit') !~ '^[0-6]$'
       or (p_config->>'interstitialsPerDay') !~ '^[0-2]$' then
        raise exception 'Ad settings are incomplete';
    end if;
    v_ads_enabled := (p_config->>'adsEnabled')::boolean;
    v_premium_ads_enabled := (p_config->>'premiumAdsEnabled')::boolean;
    v_banner_limit := (p_config->>'bannerSlotLimit')::integer;
    v_interstitial_limit := (p_config->>'interstitialsPerDay')::integer;
    if v_banner_limit not between 0 and 6
       or v_interstitial_limit not between 0 and 2 then
        raise exception 'Ad limits are outside the allowed range';
    end if;
    update private.premium_config
       set ads_enabled = v_ads_enabled,
           premium_ads_enabled = v_premium_ads_enabled,
           banner_slot_limit = v_banner_limit,
           launch_interstitials_per_day = v_interstitial_limit
     where singleton = true;
    return public.get_premium_public_settings();
end;
$$;

revoke all on function public.set_premium_ads_config(jsonb)
    from public, anon, authenticated;
grant execute on function public.set_premium_ads_config(jsonb) to service_role;
