set search_path = nf_mail;

create table if not exists outbox_message
(
    outbox_id      bigserial primary key,
    aggregate_type varchar(64)  not null,
    aggregate_id   varchar(128) not null,
    event_type     varchar(128) not null,
    payload        jsonb        not null,
    headers        jsonb        not null default '{}'::jsonb,
    created_at     timestamptz  not null default now(),
    published_at   timestamptz  null,
    claimed_at     timestamptz  null,
    claim_token    uuid         null
);

create index if not exists ix_mail_sender_outbox_unpublished
    on outbox_message (published_at, claimed_at, outbox_id);
