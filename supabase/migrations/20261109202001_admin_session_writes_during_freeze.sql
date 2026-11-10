CREATE OR REPLACE FUNCTION private.qspoof_block_non_analytics_writes()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  freeze_enabled boolean;
BEGIN
  SELECT enabled INTO freeze_enabled
  FROM private.qspoof_write_freeze
  WHERE singleton = true;

  IF NOT coalesce(freeze_enabled, false) THEN
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
  END IF;

  IF (TG_TABLE_SCHEMA = 'public' AND TG_TABLE_NAME IN (
        'app_devices',
        'app_device_daily_usage',
        'app_usage_daily',
        'app_usage_version_daily',
        'app_usage_version_weekly',
        'app_usage_weekly'
      ))
     OR (TG_TABLE_SCHEMA = 'private' AND TG_TABLE_NAME IN (
        'app_device_daily_presence',
        'app_device_weekly_presence'
      ))
     OR (TG_TABLE_SCHEMA = 'private' AND TG_TABLE_NAME IN (
        'premium_admin_login_attempts',
        'premium_admin_sessions',
        'premium_admin_trusted_browsers',
        'premium_admin_audit'
      )) THEN
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
  END IF;

  RAISE EXCEPTION 'QSpoof temporary write freeze: non-analytics writes are paused'
    USING ERRCODE = 'read_only_sql_transaction';
END;
$function$;