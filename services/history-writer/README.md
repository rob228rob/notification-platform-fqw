# history-writer

## Назначение
Сервис истории доставок: потребляет delivery status events из Kafka, сохраняет их в БД и отдает историю по `client_id` через gRPC.

## Входящие интерфейсы

### Kafka consumer
Класс: `DeliveryHistoryConsumer`

Топик:
- `notification.mail.delivery-statuses` (`outbox.relay.topics.mail-delivery-statuses`)

Ожидаемый envelope (должен совпадать с mail-sender outbox relay):
- `outboxId: number`
- `aggregateType: string` (`mail_delivery`)
- `aggregateId: string`
- `eventType: string` (`MailDeliveryStatusChanged`)
- `payload: object`
- `headers: object`
- `createdAt: string (ISO-8601)`

Payload сохраняется как типизированная запись истории (включая `client_id`, `status`, `attempt_no`, `error_message`, `occurred_at`, `next_attempt_at`).

### gRPC API
Proto: `libs/proto-history/src/main/proto/notification/facade/v1/history.proto`

Сервис: `notification.facade.v1.NotificationHistoryService`

Метод:
- `ListClientHistory(ListClientHistoryRequest)` — отдать историю доставок по `client_id`

## Хранилище
Таблица:
- `nf.delivery_history`

Ключ:
- `outbox_id` (идемпотентная запись одного Kafka message).

Индекс чтения:
- `(client_id, occurred_at desc, outbox_id desc)`.

## proto контракт
Основные типы:
- `DeliveryStatusKafkaEvent`
- `DeliveryHistoryPayload`
- `DeliveryStatus` enum
- `ListClientHistoryRequest/Response`
