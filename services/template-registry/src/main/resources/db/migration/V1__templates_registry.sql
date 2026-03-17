set search_path = nf;

create table if not exists template (
    template_id      uuid primary key,
    client_id        uuid         not null,
    name             varchar(256) not null,
    description      text         null,
    status           varchar(32)  not null,
    active_version   int          null,
    idempotency_key  varchar(128) not null,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    constraint uq_template_idem unique (idempotency_key)
);

create table if not exists template_version (
    template_id uuid         not null,
    version     int          not null,
    engine      varchar(32)  not null,
    contents    jsonb        not null,
    created_at  timestamptz  not null default now(),
    primary key (template_id, version),
    constraint fk_template_version_template foreign key (template_id) references template(template_id) on delete cascade
);

create index if not exists ix_template_client_status on template (client_id, status, updated_at desc);
