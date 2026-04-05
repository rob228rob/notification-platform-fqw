# history-writer

## Назначение
Сервис собирает историю доставок из Kafka и хранит её как read model в `nf_hist.delivery_history`.
Поверх этой истории сервис предоставляет gRPC API:
- для чтения истории по `client_id`;
- для получения краткой сводки по `recipient_id/recipient_ids` за ограниченное окно времени.

## Входящие интеграции

### Kafka consumer
Сервис читает:
- `notification.mail.delivery-statuses`
- `notification.sms.delivery-statuses`

События доставки сохраняются в общую историческую таблицу с полями:
- `dispatch_id`
- `event_id`
- `client_id`
- `recipient_id`
- `channel`
- `delivery_status`
- `attempt_no`
- `error_message`
- `occurred_at`

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
Схема:
- `nf_hist`

Таблица:
- `delivery_history`

Индекс для клиентской истории:
- `(client_id, occurred_at desc, outbox_id desc)`

## Использование в planning
Sender-сервисы могут запрашивать по `recipient_id` или батчу получателей краткую историю доставок за последние сутки и применять собственные лимиты без хранения локальной user-policy модели.
