create table private.app_update_bandwidth_usage (
    month_start date primary key,
    reserved_bytes bigint not null default 0
        check (reserved_bytes >= 0),
    updated_at timestamptz not null default pg_catalog.now()
);

alter table private.app_update_bandwidth_usage enable row level security;
revoke all on table private.app_update_bandwidth_usage
    from public, anon, authenticated;
grant usage on schema private to service_role;
grant select, insert, update on table private.app_update_bandwidth_usage
    to service_role;

create or replace function public.reserve_app_update_bandwidth(
    p_requested_bytes bigint,
    p_monthly_limit_bytes bigint
) returns boolean
language plpgsql
security invoker
set search_path = ''
as $$
declare
    v_month_start date := pg_catalog.date_trunc(
        'month',
        pg_catalog.now() at time zone 'UTC'
    )::date;
    v_reserved_bytes bigint;
begin
    if p_requested_bytes is null or p_monthly_limit_bytes is null
       or p_requested_bytes <= 0 or p_monthly_limit_bytes <= 0
       or p_requested_bytes > p_monthly_limit_bytes then
        return false;
    end if;

    insert into private.app_update_bandwidth_usage as current_usage (
        month_start,
        reserved_bytes
    )
    values (v_month_start, p_requested_bytes)
    on conflict (month_start) do update
       set reserved_bytes = current_usage.reserved_bytes + excluded.reserved_bytes,
           updated_at = pg_catalog.now()
     where current_usage.reserved_bytes <=
           p_monthly_limit_bytes - excluded.reserved_bytes
    returning reserved_bytes into v_reserved_bytes;

    return found;
end;
$$;

revoke all on function public.reserve_app_update_bandwidth(bigint, bigint)
    from public, anon, authenticated;
grant execute on function public.reserve_app_update_bandwidth(bigint, bigint)
    to service_role;

create or replace function public.get_app_update_bandwidth_usage()
returns bigint
language sql
security invoker
set search_path = ''
as $$
    select coalesce((
        select meter.reserved_bytes
        from private.app_update_bandwidth_usage as meter
        where meter.month_start = pg_catalog.date_trunc(
            'month',
            pg_catalog.now() at time zone 'UTC'
        )::date
    ), 0);
$$;

revoke all on function public.get_app_update_bandwidth_usage()
    from public, anon, authenticated;
grant execute on function public.get_app_update_bandwidth_usage()
    to service_role;