insert into public.premium_reference_grants (
    transaction_id,
    display_name,
    display_name_partial
)
values (
    'XX6967',
    '##UNKNOWN',
    false
)
on conflict (transaction_id) do update
set display_name = excluded.display_name,
    display_name_partial = false;