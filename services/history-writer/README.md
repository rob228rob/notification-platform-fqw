# history-writer

## Назначение
Сервис собирает историю доставок из Kafka и хранит её как read model в ClickHouse.
Поверх этой истории сервис предоставляет gRPC API:
- для чтения истории по `client_id`;
- для получения краткой сводки по `recipient_id/recipient_ids` за ограниченное окно времени.

## Входящие интеграции

### Kafka consumer
Сервис читает:
- `notification.mail.delivery-statuses`
- `notification.sms.delivery-statuses`

События доставки сохраняются в аналитическую таблицу с полями:
- `dispatch_id`
- `event_id`
- `client_id`
- `recipient_id`
- `channel`
- `delivery_status`
- `attempt_no`
- `error_message`
- `occurred_at`
- `kafka_topic / kafka_partition / kafka_offset`
- `payload_json / headers_json`

## gRPC API
Proto:
- [history.proto](C:\Users\Asus\IdeaProjects\notifcation-platform-gradle-groovy\libs\proto-history\src\main\proto\notification\facade\v1\history.proto)

Методы:
- `ListClientHistory`
- `GetRecipientDeliverySummary`
- `BatchGetRecipientDeliverySummaries`

### Recipient summary
Сводка по получателю возвращает:
- общее число успешных доставок;
- общее число неуспешных доставок;
- total по окну;
- разбивку по каналам;
- границы окна `window_from/window_to`.

Успешными считаются записи со статусом `SENT`.
Неуспешными считаются записи со статусами `FAILED` и `SKIPPED`.

## Хранилище
Основное аналитическое хранилище:
- `notification_history.delivery_status_history` в ClickHouse

Порядок сортировки MergeTree:
- `(client_id, event_id, recipient_id, channel, occurred_at, delivery_id, outbox_id)`

PostgreSQL-адаптер сохранён в кодовой базе как fallback-реализация storage interface, но не является целевым аналитическим хранилищем текущей версии.

## Использование в planning
Sender-сервисы могут запрашивать по `recipient_id` или батчу получателей краткую историю доставок за последние сутки и применять собственные лимиты без хранения локальной user-policy модели.
