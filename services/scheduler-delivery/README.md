# scheduler-delivery

Сервис принимает отложенные dispatch-сообщения из Kafka, сохраняет их в БД и по `planned_send_at` публикует в рабочий топик отправки.

## Входящие/исходящие каналы

- `Kafka consumer`: `${scheduler.delivery.consumer-topic}` (default `notification.mail.dispatches.scheduled`)
- `Kafka producer`: `${scheduler.delivery.producer-topic}` (default `notification.mail.dispatches`)
- `DB table`: `nf.scheduled_delivery_task`

## Kafka контракт (in/out envelope)

- Формат сообщения: `outbox`-envelope
  - `outboxId: number`
  - `aggregateType: string`
  - `aggregateId: string`
  - `eventType: string`
  - `payload: object`
  - `headers: object`
  - `createdAt: ISO-8601 timestamp`
- Поле времени отложенной доставки читается из `payload`:
  - приоритет: `planned_send_at`, `plannedSendAt`, `send_at`, `sendAt`
  - если не задано, сообщение публикуется сразу

## Retry/статусы задач

- `NEW` -> `PUBLISHING` -> `PUBLISHED`
- при ошибке publish: `RETRY` + `next_retry_at = now() + retry-backoff`
- idempotency на входе по `message_id` (из `headers.message_id`, fallback от `aggregateId`)

## Минимальный smoke e2e

- Предусловия:
  - подняты `kafka` и `postgres-db`
  - запущен `scheduler-delivery`
- Запуск:
  - `powershell -File deploy/smoke/scheduler-delivery-smoke.ps1`
- Что проверяет:
  - отправка тестового сообщения в `notification.mail.dispatches.scheduled`
  - появление и переход записи в `nf.scheduled_delivery_task` в статус `PUBLISHED`
