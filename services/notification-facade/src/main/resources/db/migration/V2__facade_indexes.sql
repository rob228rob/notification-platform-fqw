-- V2__indexes_facade.sql

set search_path = nf_fac;

-- events
create index if not exists ix_event_client_created
    on notification_event (client_id, created_at desc);

create index if not exists ix_event_client_status_created
    on notification_event (client_id, status, created_at desc);

create index if not exists ix_event_status_scheduled
    on notification_event (status, scheduled_at);

-- recipients
create index if not exists ix_event_recipient_event
    on event_recipient (event_id);

create index if not exists ix_event_recipient_recipient
    on event_recipient (recipient_id);

-- dispatch
create index if not exists ix_dispatch_event_created
    on dispatch (event_id, created_at desc);

create index if not exists ix_dispatch_status_planned
    on dispatch (status, planned_send_at);

-- dispatch_target
create index if not exists ix_dispatch_target_dispatch
    on dispatch_target (dispatch_id);

create index if not exists ix_dispatch_target_recipient
    on dispatch_target (recipient_id);