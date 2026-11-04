alter table public.premium_requests
    add column if not exists is_pinned boolean not null default false;

update public.premium_requests
set display_name = '##UNKNOWN',
    display_name_partial = false,
    is_pinned = true
where upper(transaction_id) = 'XX6967'
  and status = 'approved';
