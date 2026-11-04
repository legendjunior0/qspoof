alter table private.premium_config
    add column if not exists ads_enabled boolean not null default true;

drop index if exists public.premium_requests_one_pending_per_device;

create table if not exists public.premium_reference_grants (
    transaction_id text primary key
        check (transaction_id ~ '^[A-Za-z0-9-]{6,80}$'),
    display_name text
        check (display_name is null or char_length(display_name) between 1 and 40),
    display_name_partial boolean not null default false,
    created_at timestamptz not null default now(),
    claimed_device_hash text unique,
    claimed_at timestamptz
);

alter table public.premium_reference_grants enable row level security;
alter table public.premium_reference_grants force row level security;
revoke all on public.premium_reference_grants from public, anon, authenticated;
grant select, insert, update, delete on public.premium_reference_grants to service_role;


create or replace function public.get_premium_public_settings()
returns jsonb
language sql
security definer
set search_path = ''
as $$
    select pg_catalog.jsonb_build_object('adsEnabled', ads_enabled)
    from private.premium_config
    where singleton = true;
$$;

create or replace function public.set_premium_ads_enabled(enabled boolean)
returns boolean
language sql
security definer
set search_path = ''
as $$
    update private.premium_config
    set ads_enabled = enabled
    where singleton = true
    returning ads_enabled;
$$;

create or replace function public.add_premium_reference(
    reference text,
    donor_name text,
    donor_name_partial boolean
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
    if reference !~ '^[A-Za-z0-9-]{6,80}$'
       or (
           donor_name is not null
           and char_length(donor_name) not between 1 and 40
       ) then
        return false;
    end if;

    if exists (
        select 1 from public.premium_requests
        where transaction_id = upper(btrim(reference))
    ) or exists (
        select 1 from public.premium_entitlements
        where transaction_id = upper(btrim(reference))
    ) then
        return false;
    end if;

    insert into public.premium_reference_grants (
        transaction_id,
        display_name,
        display_name_partial
    )
    values (
        upper(btrim(reference)),
        donor_name,
        donor_name is not null and donor_name_partial
    )
    on conflict (transaction_id) do nothing;

    return found;
end;
$$;

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
        claimed_at = now()
    where transaction_id = upper(btrim(p_reference))
      and claimed_device_hash is null
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
    where device_hash = claimed_device_hash
      and status = 'pending'
      and transaction_id <> claimed_reference.transaction_id;

    return true;
exception
    when unique_violation then
        return false;
end;
$$;

create or replace function public.approve_premium_request(request_id uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    approved_request public.premium_requests%rowtype;
begin
    select *
    into approved_request
    from public.premium_requests
    where id = request_id
      and status = 'pending'
    for update;

    if not found then
        return false;
    end if;

    insert into public.premium_entitlements (device_hash, transaction_id)
    values (approved_request.device_hash, approved_request.transaction_id);

    update public.premium_requests
    set status = 'approved',
        reviewed_at = now()
    where id = approved_request.id;

    update public.premium_requests
    set status = 'rejected',
        reviewed_at = now()
    where device_hash = approved_request.device_hash
      and status = 'pending'
      and id <> approved_request.id;

    return true;
exception
    when unique_violation then
        update public.premium_requests
        set status = 'rejected',
            reviewed_at = now()
        where id = request_id
          and status = 'pending';
        return false;
end;
$$;

create or replace function public.clear_premium_user_data()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    deleted_entitlements integer;
    deleted_requests integer;
    deleted_references integer;
begin
    delete from public.premium_entitlements;
    get diagnostics deleted_entitlements = row_count;

    delete from public.premium_requests;
    get diagnostics deleted_requests = row_count;

    delete from public.premium_reference_grants;
    get diagnostics deleted_references = row_count;

    return pg_catalog.jsonb_build_object(
        'entitlements', deleted_entitlements,
        'requests', deleted_requests,
        'references', deleted_references
    );
end;
$$;

revoke all on function public.get_premium_public_settings()
    from public, anon, authenticated;
revoke all on function public.add_premium_reference(text, text, boolean)
    from public, anon, authenticated;
revoke all on function public.set_premium_ads_enabled(boolean)
    from public, anon, authenticated;
revoke all on function public.claim_premium_reference(text, text)
    from public, anon, authenticated;
revoke all on function public.approve_premium_request(uuid)
    from public, anon, authenticated;
revoke all on function public.clear_premium_user_data()
    from public, anon, authenticated;
grant execute on function public.get_premium_public_settings()
    to service_role;
grant execute on function public.add_premium_reference(text, text, boolean)
    to service_role;
grant execute on function public.set_premium_ads_enabled(boolean)
    to service_role;
grant execute on function public.claim_premium_reference(text, text)
    to service_role;
grant execute on function public.approve_premium_request(uuid)
    to service_role;
grant execute on function public.clear_premium_user_data()
    to service_role;
