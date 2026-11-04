alter table public.premium_requests
    add column if not exists display_name text,
    add column if not exists display_name_partial boolean not null default false,
    add constraint premium_requests_display_name_length
        check (display_name is null or char_length(display_name) between 1 and 40);
