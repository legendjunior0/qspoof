alter table private.premium_config
    add column if not exists app_normal_access_check_interval_hours integer not null default 6,
    add column if not exists app_premium_access_check_interval_hours integer not null default 6;

update private.premium_config
   set app_normal_access_check_interval_hours = 6,
       app_premium_access_check_interval_hours = 6
 where singleton = true;

do $$
begin
    if not exists (
        select 1
          from pg_constraint
         where conname = 'premium_config_normal_access_interval_check'
           and conrelid = 'private.premium_config'::regclass
    ) then
        alter table private.premium_config
            add constraint premium_config_normal_access_interval_check
            check (app_normal_access_check_interval_hours between 1 and 168);
    end if;

    if not exists (
        select 1
          from pg_constraint
         where conname = 'premium_config_premium_access_interval_check'
           and conrelid = 'private.premium_config'::regclass
    ) then
        alter table private.premium_config
            add constraint premium_config_premium_access_interval_check
            check (app_premium_access_check_interval_hours between 1 and 168);
    end if;
end;
$$;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'app-promotions',
    'app-promotions',
    true,
    8388608,
    array['image/jpeg', 'image/png', 'image/webp']::text[]
)
on conflict (id) do update
   set public = excluded.public,
       file_size_limit = excluded.file_size_limit,
       allowed_mime_types = excluded.allowed_mime_types;

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
        'normalAccessCheckIntervalHours', config.app_normal_access_check_interval_hours,
        'premiumAccessCheckIntervalHours', config.app_premium_access_check_interval_hours,
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

create or replace function public.set_app_access_policy(
    p_premium_only_access boolean,
    p_normal_access_check_interval_hours integer,
    p_premium_access_check_interval_hours integer
) returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
begin
    if p_premium_only_access is null
       or p_normal_access_check_interval_hours is null
       or p_premium_access_check_interval_hours is null
       or p_normal_access_check_interval_hours not between 1 and 168
       or p_premium_access_check_interval_hours not between 1 and 168 then
        raise exception 'Choose valid access intervals from 1 to 168 hours';
    end if;

    update private.premium_config
       set premium_only_access = p_premium_only_access,
           app_normal_access_check_interval_hours = p_normal_access_check_interval_hours,
           app_premium_access_check_interval_hours = p_premium_access_check_interval_hours
     where singleton = true;

    return public.get_premium_public_settings();
end;
$$;

revoke all on function public.set_app_access_policy(boolean, integer, integer)
    from public, anon, authenticated;
grant execute on function public.set_app_access_policy(boolean, integer, integer)
    to service_role;

create or replace function public.set_premium_ads_policy(p_policy jsonb)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_global_enabled boolean;
    v_free_enabled boolean;
    v_premium_enabled boolean;
    v_banner_limit integer;
    v_interstitial_limit integer;
begin
    if pg_catalog.jsonb_typeof(p_policy) is distinct from 'object'
       or pg_catalog.jsonb_typeof(p_policy->'globalEnabled') is distinct from 'boolean'
       or pg_catalog.jsonb_typeof(p_policy#>'{free,enabled}') is distinct from 'boolean'
       or pg_catalog.jsonb_typeof(p_policy#>'{premium,enabled}') is distinct from 'boolean'
       or pg_catalog.jsonb_typeof(p_policy#>'{free,formats,banner}') is distinct from 'boolean'
       or pg_catalog.jsonb_typeof(p_policy#>'{free,formats,fullPage}') is distinct from 'boolean'
       or pg_catalog.jsonb_typeof(p_policy#>'{free,formats,native}') is distinct from 'boolean'
       or pg_catalog.jsonb_typeof(p_policy#>'{premium,formats,banner}') is distinct from 'boolean'
       or pg_catalog.jsonb_typeof(p_policy#>'{premium,formats,fullPage}') is distinct from 'boolean'
       or pg_catalog.jsonb_typeof(p_policy#>'{premium,formats,native}') is distinct from 'boolean' then
        raise exception 'Advertising policy is incomplete';
    end if;

    if coalesce(p_policy#>>'{free,limits,bannerSlotsPerScreen}', '') !~ '^(0|[1-6])$'
       or coalesce(p_policy#>>'{free,limits,fullPagePerSession}', '') !~ '^(0|[1-2])$'
       or coalesce(p_policy#>>'{free,limits,fullPageCooldownHours}', '') !~ '^(0|[1-9][0-9]?|1[0-6][0-9]|168)$'
       or coalesce(p_policy#>>'{free,limits,nativeSlotsPerScreen}', '') !~ '^(0|[1-2])$'
       or coalesce(p_policy#>>'{premium,limits,bannerSlotsPerScreen}', '') !~ '^(0|[1-6])$'
       or coalesce(p_policy#>>'{premium,limits,fullPagePerSession}', '') !~ '^(0|[1-2])$'
       or coalesce(p_policy#>>'{premium,limits,fullPageCooldownHours}', '') !~ '^(0|[1-9][0-9]?|1[0-6][0-9]|168)$'
       or coalesce(p_policy#>>'{premium,limits,nativeSlotsPerScreen}', '') !~ '^(0|[1-2])$' then
        raise exception 'Advertising limits are outside the allowed range';
    end if;

    v_global_enabled := (p_policy->>'globalEnabled')::boolean;
    v_free_enabled := (p_policy#>>'{free,enabled}')::boolean;
    v_premium_enabled := (p_policy#>>'{premium,enabled}')::boolean;
    v_banner_limit := (p_policy#>>'{free,limits,bannerSlotsPerScreen}')::integer;
    v_interstitial_limit := (p_policy#>>'{free,limits,fullPagePerSession}')::integer;

    update private.premium_config
       set ads_enabled = v_global_enabled and v_free_enabled,
           premium_ads_enabled = v_global_enabled and v_premium_enabled,
           banner_slot_limit = v_banner_limit,
           launch_interstitials_per_day = v_interstitial_limit,
           app_remote_config = pg_catalog.jsonb_set(
               coalesce(app_remote_config, '{}'::jsonb),
               '{adsPolicy}',
               p_policy,
               true
           )
     where singleton = true;

    return public.get_premium_public_settings();
end;
$$;

revoke all on function public.set_premium_ads_policy(jsonb)
    from public, anon, authenticated;
grant execute on function public.set_premium_ads_policy(jsonb)
    to service_role;