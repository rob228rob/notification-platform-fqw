set search_path = nf;

drop trigger if exists tr_dispatch_outbox on dispatch;

drop function if exists trg_dispatch_outbox();
