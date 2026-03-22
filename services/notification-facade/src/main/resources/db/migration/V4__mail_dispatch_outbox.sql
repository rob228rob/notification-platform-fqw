set search_path = nf_fac;

drop trigger if exists tr_dispatch_outbox on dispatch;

drop function if exists trg_dispatch_outbox();
