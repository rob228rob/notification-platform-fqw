set search_path = nf_prof;

create table if not exists recipient_profile
(
    recipient_id       varchar(128) primary key,
    active             boolean        not null default true,
    preferred_channel  varchar(64)    not null,
    created_at         timestamptz    not null default now(),
    updated_at         timestamptz    not null default now()
);

create table if not exists recipient_channel_consent
(
    recipient_id   varchar(128)   not null references recipient_profile (recipient_id) on delete cascade,
    channel        varchar(64)    not null,
    tenant_key     varchar(128)   not null default '',
    enabled        boolean        not null default false,
    blacklisted    boolean        not null default false,
    destination    varchar(512)   not null default '',
    created_at     timestamptz    not null default now(),
    updated_at     timestamptz    not null default now(),
    primary key (recipient_id, channel, tenant_key)
);

create index if not exists idx_recipient_profile_updated_at
    on recipient_profile (updated_at);

create index if not exists idx_recipient_channel_consent_lookup
    on recipient_channel_consent (recipient_id, channel, tenant_key);

create index if not exists idx_recipient_channel_consent_channel_tenant
    on recipient_channel_consent (channel, tenant_key);
