create table if not exists private.app_feedback_votes (
    device_hash text primary key,
    works boolean not null,
    updated_at timestamptz not null default now()
);

create or replace function public.get_app_feedback_vote(p_device_hash text)
returns jsonb
language sql
security definer
set search_path = ''
as $$
    select coalesce(
        (select pg_catalog.jsonb_build_object('works', works, 'updatedAt', updated_at)
         from private.app_feedback_votes
         where device_hash = p_device_hash),
        '{}'::jsonb
    );
$$;

create or replace function public.set_app_feedback_vote(p_device_hash text, p_works boolean)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into private.app_feedback_votes(device_hash, works, updated_at)
    values (p_device_hash, p_works, now())
    on conflict (device_hash) do update
      set works = excluded.works, updated_at = excluded.updated_at;
    return public.get_app_feedback_vote(p_device_hash);
end;
$$;

revoke all on function public.get_app_feedback_vote(text) from public, anon, authenticated;
revoke all on function public.set_app_feedback_vote(text, boolean) from public, anon, authenticated;
grant execute on function public.get_app_feedback_vote(text) to service_role;
grant execute on function public.set_app_feedback_vote(text, boolean) to service_role;
