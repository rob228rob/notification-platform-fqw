set search_path = nf;

alter table if exists outbox_message
    add column if not exists claimed_at timestamptz null;

alter table if exists outbox_message
    add column if not exists claim_token uuid null;

create index if not exists ix_outbox_unpublished_claim
    on outbox_message (published_at, claimed_at, outbox_id);
