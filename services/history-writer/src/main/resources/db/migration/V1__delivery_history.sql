set search_path = nf_hist;

create table if not exists delivery_history
(
    outbox_id         bigint primary key,
    aggregate_type    varchar(64)  not null,
    aggregate_id      varchar(256) not null,
    event_type        varchar(128) not null,
    created_at        timestamptz  not null,

    dispatch_id       varchar(64)  not null,
    event_id          varchar(64)  not null,
    client_id         varchar(128) not null,
    recipient_id      varchar(128) not null,
    email             varchar(320) not null,
    channel           varchar(64)  not null,
    delivery_status   varchar(64)  not null,
    template_id       varchar(128) not null,
    template_version  int          not null default 0,
    idempotency_key   varchar(256) not null,
    attempt_no        int          not null default 0,
    error_message     varchar(512) null,
    next_attempt_at   timestamptz  null,
    occurred_at       timestamptz  null,
    payload_json      jsonb        not null default '{}'::jsonb,
    headers_json      jsonb        not null default '{}'::jsonb
);

create index if not exists ix_delivery_history_client_occurred
    on delivery_history (client_id, occurred_at desc, outbox_id desc);
