create or replace function private.revoke_admin_sessions_after_email_change()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    if new.email is distinct from old.email then
        delete from private.premium_admin_sessions where email = new.email;
        delete from private.premium_admin_trusted_browsers where email = new.email;
    end if;
    return new;
end;
$$;

revoke all on function private.revoke_admin_sessions_after_email_change()
    from public, anon, authenticated;

drop trigger if exists revoke_admin_sessions_after_email_change
    on private.premium_admins;
create trigger revoke_admin_sessions_after_email_change
after update of email on private.premium_admins
for each row execute function private.revoke_admin_sessions_after_email_change();
