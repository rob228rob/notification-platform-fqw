create table if not exists nf_sms.consumer_inbox_message (
    message_id uuid primary key,
    aggregate_type varchar(64) not null,
    aggregate_id varchar(128) not null,
    event_type varchar(128) not null,
    event_id uuid not null,
    payload jsonb not null,
    headers jsonb not null default '{}'::jsonb,
    processing_status varchar(32) not null,
    error_message varchar(500),
    received_at timestamptz not null,
    processed_at timestamptz
);
create index if not exists idx_nf_sms_consumer_inbox_status on nf_sms.consumer_inbox_message (processing_status, aggregate_type, event_type, received_at);
create table if not exists nf_sms.sms_delivery (
    dispatch_id uuid not null,
    event_id uuid not null,
    client_id varchar(128) not null,
    recipient_id varchar(128) not null,
    channel varchar(64) not null,
    status varchar(64) not null,
    phone varchar(64) not null,
    template_id varchar(128) not null,
    template_version integer not null,
    payload jsonb not null,
    rule_code varchar(128),
    idempotency_key varchar(255) not null,
    attempt_count integer not null default 0,
    next_attempt_at timestamptz,
    last_error varchar(500),
    created_at timestamptz not null,
    sent_at timestamptz,
    primary key (dispatch_id, recipient_id, channel)
);
create unique index if not exists uq_nf_sms_sms_delivery_idempotency_key on nf_sms.sms_delivery (idempotency_key);
create index if not exists idx_nf_sms_sms_delivery_status_next_attempt on nf_sms.sms_delivery (status, next_attempt_at, created_at);
create table if not exists nf_sms.sms_delivery_attempt (
    dispatch_id uuid not null,
    recipient_id varchar(128) not null,
    channel varchar(64) not null,
    attempt_no integer not null,
    status varchar(32) not null,
    error_message varchar(500),
    created_at timestamptz not null,
    finished_at timestamptz,
    primary key (dispatch_id, recipient_id, channel, attempt_no)
);
create table if not exists nf_sms.outbox_message (
    outbox_id bigserial primary key,
    aggregate_type varchar(64) not null,
    aggregate_id varchar(255) not null,
    event_type varchar(128) not null,
    payload jsonb not null,
    headers jsonb not null default '{}'::jsonb,
    created_at timestamptz not null,
    published_at timestamptz,
    claim_token uuid,
    claimed_at timestamptz
);
create index if not exists idx_nf_sms_outbox_publish_claim on nf_sms.outbox_message (published_at, claim_token, outbox_id);
