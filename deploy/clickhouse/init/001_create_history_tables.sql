CREATE DATABASE IF NOT EXISTS notification_history;

CREATE TABLE IF NOT EXISTS notification_history.delivery_status_history
(
    event_date Date,
    occurred_at Nullable(DateTime64(3, 'UTC')),
    ingested_at DateTime64(3, 'UTC'),
    created_at DateTime64(3, 'UTC'),
    client_id String,
    event_id String,
    dispatch_id String,
    recipient_id String,
    aggregate_id String,
    channel LowCardinality(String),
    provider LowCardinality(String),
    delivery_id String,
    status LowCardinality(String),
    previous_status LowCardinality(String),
    reason_code LowCardinality(String),
    reason_message String,
    attempt_no UInt16,
    max_attempts UInt16,
    is_final UInt8,
    next_attempt_at Nullable(DateTime64(3, 'UTC')),
    scheduled_at Nullable(DateTime64(3, 'UTC')),
    sent_at Nullable(DateTime64(3, 'UTC')),
    failed_at Nullable(DateTime64(3, 'UTC')),
    latency_ms Nullable(UInt64),
    provider_message_id String,
    template_id String,
    template_version UInt32,
    priority UInt8,
    correlation_id String,
    idempotency_key String,
    kafka_topic String,
    kafka_partition Int32,
    kafka_offset Int64,
    aggregate_type String,
    event_type String,
    outbox_id Int64,
    destination String,
    payload_hash String,
    payload_json String,
    headers_json String,
    metadata_json String
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(event_date)
ORDER BY (client_id, event_id, recipient_id, channel, occurred_at, delivery_id, outbox_id);

-- ORDER BY выбран под основные чтения history-writer:
-- client_id используется как основной tenant-фильтр,
-- event_id / recipient_id / channel нужны для истории конкретного события и получателя,
-- occurred_at под временные интервалы,
-- delivery_id и outbox_id сохраняют детализацию append-only журнала.
