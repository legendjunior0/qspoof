create or replace function public.claim_premium_reference(
    p_reference text,
    p_claimed_device_hash text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    claimed_reference public.premium_reference_grants%rowtype;
begin
    if exists (
        select 1
        from public.premium_entitlements
        where device_hash = p_claimed_device_hash
    ) then
        return false;
    end if;

    update public.premium_reference_grants
    set claimed_device_hash = p_claimed_device_hash,
        claimed_at = coalesce(claimed_at, now())
    where transaction_id = upper(btrim(p_reference))
      and (claimed_device_hash is null or claimed_device_hash = p_claimed_device_hash)
    returning * into claimed_reference;

    if not found then
        return false;
    end if;

    insert into public.premium_entitlements (device_hash, transaction_id)
    values (p_claimed_device_hash, claimed_reference.transaction_id);

    update public.premium_requests
    set status = 'approved',
        display_name = claimed_reference.display_name,
        display_name_partial = claimed_reference.display_name_partial,
        reviewed_at = now()
    where transaction_id = claimed_reference.transaction_id
      and status = 'pending';

    update public.premium_requests
    set status = 'rejected',
        reviewed_at = now()
    where device_hash = p_claimed_device_hash
      and status = 'pending'
      and transaction_id <> claimed_reference.transaction_id;

    return true;
exception
    when unique_violation then
        return false;
end;
$$;

revoke all on function public.claim_premium_reference(text, text)
    from public, anon, authenticated;
grant execute on function public.claim_premium_reference(text, text)
    to service_role;
