# notification-mail-sender

## Назначение
Mail delivery processor: читает события из Kafka, планирует и выполняет email-доставку, пишет статусы доставки в Kafka через собственный outbox relay

## Входящие интерфейсы

### Kafka consumers

Топики:
- `notification.facade.events` (`outbox.relay.topics.events`)
- `notification.mail.dispatches` (`outbox.relay.topics.mail-dispatches`)

Ожидаемый контракт:
- `outboxId`, `aggregateType`, `aggregateId`, `eventType`, `payload`, `headers`, `createdAt`

Обрабатываемые события:
- `aggregateType=notification_event`, `eventType=EventCreated`
- `aggregateType=mail_dispatch`, `eventType=MailDispatchRequested`

## Исходящие интерфейсы

### Kafka producer (через outbox)
Топик:
- `notification.mail.delivery-statuses` (`outbox.relay.topics.mail-delivery-statuses`)

Событие:
- `aggregateType=mail_delivery`
- `eventType=MailDeliveryStatusChanged`

Payload contract:
- `dispatch_id: string(UUID)`
- `event_id: string(UUID)`
- `client_id: string`
- `recipient_id: string`
- `email: string`
- `channel: string` (например `CHANNEL_EMAIL`)
- `status: string` (`MAIL_DELIVERY_STATUS_SENT|RETRY|FAILED|...`)
- `template_id: string`
- `template_version: number`
- `idempotency_key: string`
- `attempt_no: number`
- `error_message: string|null`
- `next_attempt_at: string|null` (ISO-8601)
- `occurred_at: string` (ISO-8601)

События в историю публикуются:
- после `SENT`
- после `RETRY`
- после финального `FAILED`

## Внутренняя обработка
- Inbox: `nf.consumer_inbox_message`
- План доставки: `nf.mail_delivery` (`client_id` хранится для истории)
- Попытки: `nf.mail_delivery_attempt`
- Outbox: `nf.outbox_message`
