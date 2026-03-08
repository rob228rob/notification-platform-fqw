set search_path = nf;

create table if not exists consumer_inbox_message
(
    message_id          uuid primary key,
    aggregate_type      varchar(64)  not null,
    aggregate_id        varchar(128) not null,
    event_type          varchar(128) not null,
    event_id            uuid         not null,
    payload             jsonb        not null,
    headers             jsonb        not null default '{}'::jsonb,
    processing_status   varchar(32)  not null,
    error_message       varchar(512) null,
    received_at         timestamptz  not null default now(),
    processed_at        timestamptz  null,

    constraint uq_consumer_inbox_message unique (aggregate_type, aggregate_id, event_type)
);

create index if not exists ix_consumer_inbox_status_id
    on consumer_inbox_message (processing_status, received_at, message_id);

create table if not exists recipient_mail_settings
(
    recipient_id            varchar(128) primary key,
    email                   varchar(320) not null,
    email_consent           boolean      not null default false,
    active                  boolean      not null default true,
    max_deliveries_per_day  int          not null default 1,
    created_at              timestamptz  not null default now(),
    updated_at              timestamptz  not null default now()
);

create table if not exists mail_delivery
(
    dispatch_id      uuid         not null,
    event_id         uuid         not null,
    recipient_id     varchar(128) not null,
    channel          varchar(64)  not null,
    status           varchar(64)  not null,
    email            varchar(320) not null,
    template_id      varchar(128) not null,
    template_version int          not null default 0,
    payload          jsonb        not null default '{}'::jsonb,
    rule_code        varchar(128) null,
    idempotency_key  varchar(256) not null,
    attempt_count    int          not null default 0,
    next_attempt_at  timestamptz  null,
    sent_at          timestamptz  null,
    last_error       varchar(512) null,
    created_at       timestamptz  not null default now(),

    primary key (dispatch_id, recipient_id, channel),
    constraint uq_mail_delivery_idempotency unique (idempotency_key)
);

create index if not exists ix_mail_delivery_status_next_attempt
    on mail_delivery (status, next_attempt_at, created_at);

create table if not exists mail_delivery_attempt
(
    dispatch_id     uuid         not null,
    recipient_id    varchar(128) not null,
    channel         varchar(64)  not null,
    attempt_no      int          not null,
    status          varchar(32)  not null,
    error_message   varchar(512) null,
    created_at      timestamptz  not null default now(),
    finished_at     timestamptz  null,

    primary key (dispatch_id, recipient_id, channel, attempt_no)
);
