# notification-facade

## Назначение
Точка входа для управления notification events и dispatch. Хранит событие, аудиторию, dispatch, публикует доменные события через outbox в Kafka.

## Входящие интерфейсы

### gRPC API
Proto: `libs/proto-facade/src/main/proto/notification/facade/v1/facade.proto`

Сервис: `notification.facade.v1.NotificationFacade`

Ключевые методы:
- `CreateNotificationEvent` создать событие рассылки.
- `UpdateNotificationEvent` обновить mutable-поля события.
- `CancelNotificationEvent` отменить событие.
- `GetNotificationEvent`, `ListNotificationEvents`, чтение событий.
- `SetAudience`, `AddRecipients`, `RemoveRecipients`, `GetAudience`, управление аудиторией.
- `TriggerDispatch`, создать dispatch и поставить mail-dispatch в outbox.
- `GetDispatch`, `ListDispatches`, чтение dispatch.

## Kafka 

### Входящие
- Нет.

### Исходящие (outbox relay)
Конфиг: `services/notification-facade/src/main/resources/application.yaml` (`outbox.relay.topics.*`)

Топики:
- `notification.facade.events`
- `notification.facade.dispatches`
- `notification.mail.dispatches`

json, публикуемый relay:
- `outboxId: number`
- `aggregateType: string`
- `aggregateId: string`
- `eventType: string`
- `payload: object`
- `headers: object`
- `createdAt: string (ISO-8601)`

Практически важный для downstream:
- `aggregateType=mail_dispatch`, `eventType=MailDispatchRequested`, topic `notification.mail.dispatches`.

## Важная связка с template-registry
- При create/update может рендерить шаблон через `template-registry` и инжектить `subject/body` в payload.
- Совместимость сохранена: если в payload уже есть inline `subject` + `body|text|message|content`, внешний рендер не вызывается.
