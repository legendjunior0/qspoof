create table if not exists private.premium_admin_login_attempts (
    email text primary key,
    failed_attempts integer not null default 0,
    locked_until timestamptz
);

revoke all on private.premium_admin_login_attempts from public, anon, authenticated;
grant select, insert, update on private.premium_admin_login_attempts to service_role;

create or replace function public.verify_premium_admin_password(
    candidate_email text,
    candidate_password text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    normalized_email text := lower(btrim(candidate_email));
    stored_hash text;
    attempt_row private.premium_admin_login_attempts%rowtype;
    password_matches boolean;
begin
    if normalized_email = '' or candidate_password is null then
        return false;
    end if;

    insert into private.premium_admin_login_attempts (email)
    values (normalized_email)
    on conflict (email) do nothing;

    select *
    into attempt_row
    from private.premium_admin_login_attempts
    where email = normalized_email
    for update;

    if attempt_row.locked_until is not null and attempt_row.locked_until > now() then
        return false;
    end if;

    select credentials.password_hash
    into stored_hash
    from private.premium_admin_credentials as credentials
    join private.premium_admins as admins
      on admins.email = credentials.email
    where credentials.email = normalized_email;

    password_matches :=
        stored_hash is not null
        and stored_hash = extensions.crypt(candidate_password, stored_hash);

    if password_matches then
        update private.premium_admin_login_attempts
        set failed_attempts = 0,
            locked_until = null
        where email = normalized_email;
        return true;
    end if;

    update private.premium_admin_login_attempts
    set failed_attempts = failed_attempts + 1,
        locked_until = case
            when failed_attempts + 1 >= 5 then now() + interval '15 minutes'
            else locked_until
        end
    where email = normalized_email;
    return false;
end;
$$;

revoke all on function public.verify_premium_admin_password(text, text)
    from public, anon, authenticated;
grant execute on function public.verify_premium_admin_password(text, text)
    to service_role;
