create table if not exists public.premium_requests (
    id uuid primary key default gen_random_uuid(),
    transaction_id text not null unique,
    device_hash text not null,
    status text not null default 'pending'
        check (status in ('pending', 'approved', 'rejected')),
    submitted_at timestamptz not null default now(),
    reviewed_at timestamptz
);

create table if not exists public.premium_entitlements (
    device_hash text primary key,
    transaction_id text not null unique,
    granted_at timestamptz not null default now()
);

create unique index if not exists premium_requests_one_pending_per_device
    on public.premium_requests (device_hash)
    where status = 'pending';

alter table public.premium_requests enable row level security;
alter table public.premium_requests force row level security;
alter table public.premium_entitlements enable row level security;
alter table public.premium_entitlements force row level security;

revoke all on public.premium_requests from anon, authenticated;
revoke all on public.premium_entitlements from anon, authenticated;

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

    return true;
exception
    when unique_violation then
        update public.premium_requests
        set status = 'rejected',
            reviewed_at = now()
        where id = approved_request.id;
        return false;
end;
$$;

revoke all on function public.approve_premium_request(uuid) from public, anon, authenticated;
grant execute on function public.approve_premium_request(uuid) to service_role;
