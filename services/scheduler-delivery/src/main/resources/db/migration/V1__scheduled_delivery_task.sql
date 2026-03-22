set search_path = nf_sched;

create table if not exists scheduled_delivery_task
(
    task_id            bigserial primary key,
    message_id         uuid         not null,
    aggregate_type     varchar(64)  not null,
    aggregate_id       varchar(256) not null,
    event_type         varchar(128) not null,
    payload            jsonb        not null default '{}'::jsonb,
    headers            jsonb        not null default '{}'::jsonb,
    planned_send_at    timestamptz  not null,
    source_created_at  timestamptz  not null,
    status             varchar(32)  not null,
    attempt_count      int          not null default 0,
    next_retry_at      timestamptz  null,
    last_error         varchar(512) null,
    created_at         timestamptz  not null default now(),
    published_at       timestamptz  null
);

create unique index if not exists uq_scheduled_delivery_task_message_id
    on scheduled_delivery_task (message_id);

create index if not exists ix_scheduled_delivery_task_due
    on scheduled_delivery_task (status, planned_send_at, next_retry_at);
