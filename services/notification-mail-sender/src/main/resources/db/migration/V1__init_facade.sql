-- V1__init_facade.sql

create schema if not exists nf;


-- notification_event
create table if not exists notification_event
(
    event_id          uuid primary key,
    client_id         uuid         not null,
    idempotency_key   varchar(128) not null,

    template_id       varchar(128) not null,
    template_version  int          not null default 0,

    priority          varchar(64)  not null, -- DELIVERY_PRIORITY_*
    preferred_channel varchar(64)  not null, -- CHANNEL_*

    strategy_kind     varchar(64)  not null, -- STRATEGY_KIND_*
    scheduled_at      timestamptz  null,

    status            varchar(64)  not null, -- EVENT_STATUS_*

    payload           jsonb        not null default '{}'::jsonb,

    cancelled_at      timestamptz  null,
    cancel_reason     varchar(512) null,

    created_at        timestamptz  not null,
    updated_at        timestamptz  not null,

    constraint uq_event_client_idem unique (client_id, idempotency_key)
);

-- event_audience
create table if not exists event_audience
(
    event_id             uuid primary key
        references notification_event (event_id) on delete cascade,

    kind                 varchar(64)  not null, -- AUDIENCE_KIND_*
    snapshot_on_dispatch boolean      not null default true,
    segment_id           varchar(128) null
);

-- event_recipient (explicit audience)
create table if not exists event_recipient
(
    event_id     uuid         not null references notification_event (event_id) on delete cascade,
    recipient_id varchar(128) not null,
    primary key (event_id, recipient_id)
);

-- dispatch
create table if not exists dispatch
(
    dispatch_id     uuid primary key,
    event_id        uuid         not null references notification_event (event_id) on delete cascade,
    idempotency_key varchar(128) not null,

    status          varchar(64)  not null, -- DISPATCH_STATUS_*
    planned_send_at timestamptz  null,
    started_at      timestamptz  null,
    finished_at     timestamptz  null,

    total_targets   bigint       not null default 0,
    enqueued        bigint       not null default 0,

    created_at      timestamptz  not null,

    constraint uq_dispatch_event_idem unique (event_id, idempotency_key)
);

-- dispatch_target (snapshot targets)
create table if not exists dispatch_target
(
    dispatch_id  uuid         not null references dispatch (dispatch_id) on delete cascade,
    recipient_id varchar(128) not null,
    primary key (dispatch_id, recipient_id)
);
