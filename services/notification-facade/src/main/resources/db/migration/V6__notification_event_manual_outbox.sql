set search_path = nf_fac;

create or replace function trg_notification_event_outbox()
    returns trigger
    language plpgsql
as
$$
declare
    v_payload jsonb;
begin
    if (tg_op = 'INSERT') then
        return new;
    end if;

    if (tg_op = 'UPDATE') then

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
