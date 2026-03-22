set search_path = nf_mail;

alter table if exists mail_delivery
    add column if not exists client_id varchar(128) null;

create index if not exists ix_mail_delivery_client_created
    on mail_delivery (client_id, created_at desc);
