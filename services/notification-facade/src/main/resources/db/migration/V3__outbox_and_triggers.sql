-- V3__outbox_and_triggers.sql

set search_path = nf_fac;

create table if not exists outbox_message
(
    outbox_id      bigserial primary key,

    aggregate_type varchar(64)  not null, -- e.g. "notification_event", "dispatch"
    aggregate_id   varchar(64)  not null, -- UUID string (event_id/dispatch_id)
    event_type     varchar(128) not null, -- e.g. "EventCreated", "EventUpdated"

    payload        jsonb        not null,
    headers        jsonb        not null default '{}'::jsonb,

    created_at     timestamptz  not null default now(),
    published_at   timestamptz  null
);

create index if not exists ix_outbox_unpublished
    on outbox_message (created_at)
    where published_at is null;

-- Helper function: enqueue record to outbox
create or replace function outbox_enqueue(
    p_aggregate_type varchar,
    p_aggregate_id varchar,
    p_event_type varchar,
    p_payload jsonb,
    p_headers jsonb default '{}'::jsonb
) returns void
    language plpgsql
as
$$
begin
    insert into outbox_message(aggregate_type, aggregate_id, event_type, payload, headers)
    values (p_aggregate_type, p_aggregate_id, p_event_type, p_payload, coalesce(p_headers, '{}'::jsonb));
end;
$$;

-- Trigger on notification_event:
--  - AFTER INSERT => EventCreated
--  - AFTER UPDATE:
--      status -> CANCELLED => EventCancelled
--      relevant field changes => EventUpdated
create or replace function trg_notification_event_outbox()
    returns trigger
    language plpgsql
as
$$
declare
    v_payload jsonb;
begin
    if (tg_op = 'INSERT') then
        v_payload := jsonb_build_object(
                'event_id', new.event_id::text,
                'client_id', new.client_id::text,
                'template_id', new.template_id,
                'template_version', new.template_version,
                'priority', new.priority,
                'preferred_channel', new.preferred_channel,
                'strategy_kind', new.strategy_kind,
                'scheduled_at', new.scheduled_at,
                'status', new.status,
                'created_at', new.created_at,
                'updated_at', new.updated_at
                     );

        perform outbox_enqueue(
                'notification_event',
                new.event_id::text,
                'EventCreated',
                v_payload,
                jsonb_build_object('client_id', new.client_id::text)
                );

        return new;
    end if;

    if (tg_op = 'UPDATE') then

        -- отмена
        if (old.status is distinct from new.status and new.status = 'EVENT_STATUS_CANCELLED') then
            v_payload := jsonb_build_object(
                    'event_id', new.event_id::text,
                    'client_id', new.client_id::text,
                    'status', new.status,
                    'cancel_reason', new.cancel_reason,
                    'cancelled_at', new.cancelled_at,
                    'updated_at', new.updated_at
                         );

            perform outbox_enqueue(
                    'notification_event',
                    new.event_id::text,
                    'EventCancelled',
                    v_payload,
                    jsonb_build_object('client_id', new.client_id::text)
                    );

            return new;
        end if;

        -- "значимые изменения" (чтобы не спамить outbox на любое updated_at)
        if (
            old.template_id is distinct from new.template_id or
            old.template_version is distinct from new.template_version or
            old.priority is distinct from new.priority or
            old.preferred_channel is distinct from new.preferred_channel or
            old.strategy_kind is distinct from new.strategy_kind or
            old.scheduled_at is distinct from new.scheduled_at or
            old.status is distinct from new.status or
            old.payload is distinct from new.payload
            ) then
            v_payload := jsonb_build_object(
                    'event_id', new.event_id::text,
                    'client_id', new.client_id::text,
                    'template_id', new.template_id,
                    'template_version', new.template_version,
                    'priority', new.priority,
                    'preferred_channel', new.preferred_channel,
                    'strategy_kind', new.strategy_kind,
                    'scheduled_at', new.scheduled_at,
                    'status', new.status,
                    'updated_at', new.updated_at
                         );

            perform outbox_enqueue(
                    'notification_event',
                    new.event_id::text,
                    'EventUpdated',
                    v_payload,
                    jsonb_build_object('client_id', new.client_id::text)
                    );
        end if;

        return new;
    end if;

    return new;
end;
$$;

drop trigger if exists tr_notification_event_outbox on notification_event;

create trigger tr_notification_event_outbox
    after insert or update
    on notification_event
    for each row
execute function trg_notification_event_outbox();

-- Trigger on dispatch:
--  - AFTER INSERT => DispatchCreated
--  - AFTER UPDATE status => DispatchStatusChanged
create or replace function trg_dispatch_outbox()
    returns trigger
    language plpgsql
as
$$
declare
    v_payload jsonb;
begin
    if (tg_op = 'INSERT') then
        v_payload := jsonb_build_object(
                'dispatch_id', new.dispatch_id::text,
                'event_id', new.event_id::text,
                'status', new.status,
                'planned_send_at', new.planned_send_at,
                'created_at', new.created_at,
                'total_targets', new.total_targets,
                'enqueued', new.enqueued
                     );

        perform outbox_enqueue(
                'dispatch',
                new.dispatch_id::text,
                'DispatchCreated',
                v_payload,
                '{}'::jsonb
                );

        return new;
    end if;

    if (tg_op = 'UPDATE') then
        if (old.status is distinct from new.status) then
            v_payload := jsonb_build_object(
                    'dispatch_id', new.dispatch_id::text,
                    'event_id', new.event_id::text,
                    'old_status', old.status,
                    'new_status', new.status,
                    'started_at', new.started_at,
                    'finished_at', new.finished_at,
                    'total_targets', new.total_targets,
                    'enqueued', new.enqueued
                         );

            perform outbox_enqueue(
                    'dispatch',
                    new.dispatch_id::text,
                    'DispatchStatusChanged',
                    v_payload,
                    '{}'::jsonb
                    );
        end if;

        return new;
    end if;

    return new;
end;
$$;

drop trigger if exists tr_dispatch_outbox on dispatch;

create trigger tr_dispatch_outbox
    after insert or update
    on dispatch
    for each row
execute function trg_dispatch_outbox();