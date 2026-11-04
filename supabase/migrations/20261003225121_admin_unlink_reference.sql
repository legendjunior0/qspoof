-- Let an authenticated administrator release a manual premium reference from
-- its current device so it can be claimed again. Keep the reference and donor
-- display fields intact.
create or replace function public.admin_unlink_premium_reference(
    p_transaction_id text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_transaction_id text := upper(btrim(coalesce(p_transaction_id, '')));
    v_device_hash text;
    v_display_name text;
    v_display_name_partial boolean := false;
    v_reference_found boolean := false;
    v_updated integer;
begin
    if v_transaction_id !~ '^[A-Za-z0-9-]{6,80}$' then
        return false;
    end if;

    select claimed_device_hash, display_name, display_name_partial
      into v_device_hash, v_display_name, v_display_name_partial
    from public.premium_reference_grants
    where transaction_id = v_transaction_id
    for update;

    v_reference_found := found;

    if not v_reference_found or v_device_hash is null then
        select device_hash, display_name, display_name_partial
          into v_device_hash, v_display_name, v_display_name_partial
        from public.premium_requests
        where transaction_id = v_transaction_id
          and status = 'approved'
        for update;

        if not found or v_device_hash is null then
            return false;
        end if;
    end if;

    delete from public.premium_entitlements
    where transaction_id = v_transaction_id
      and device_hash = v_device_hash;

    get diagnostics v_updated = row_count;
    if v_updated <> 1 then
        return false;
    end if;

    if v_reference_found then
        update public.premium_reference_grants
        set claimed_device_hash = null,
            claimed_at = null
        where transaction_id = v_transaction_id
          and claimed_device_hash = v_device_hash;
    else
        -- Approved user-submitted references do not normally live in the
        -- reusable-reference table. Create that mapping on admin release so
        -- the same payment reference can be claimed again, retaining its name.
        insert into public.premium_reference_grants (
            transaction_id, display_name, display_name_partial
        ) values (
            v_transaction_id, v_display_name, v_display_name_partial
        ) on conflict (transaction_id) do nothing;
    end if;

    return true;
end;
$$;

revoke all on function public.admin_unlink_premium_reference(text)
    from public, anon, authenticated;
grant execute on function public.admin_unlink_premium_reference(text)
    to service_role;
