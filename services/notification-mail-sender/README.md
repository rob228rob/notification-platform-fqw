# notification-mail-sender

## Назначение
Сервис отвечает за обработку email-доставок.
Он читает события и запросы на доставку из Kafka, планирует записи в `nf_mail.mail_delivery`, выполняет отправку писем и публикует изменения статусов доставки через outbox.

Данные о получателе сервис не хранит локально как источник истины.
Во время планирования и перед фактической отправкой сервис получает сведения о допустимости email-доставки и email-адресе только через gRPC-вызов `profile-consent`.
Дополнительно на этапе planning сервис может запросить краткую историю доставок по `recipient_id` через `history-writer` и применить channel-specific лимит.

## Входящие интеграции

### Kafka consumers
Сервис читает:
- `notification.facade.events`
- `notification.mail.dispatches`

Обрабатываемые сообщения:
- `aggregateType=notification_event`, `eventType=EventCreated`
- `aggregateType=mail_dispatch`, `eventType=MailDispatchRequested`

### gRPC client
Сервис вызывает `ProfileConsentService`:
- `CheckRecipientChannel`

Сервис вызывает `NotificationHistoryService`:
- `GetRecipientDeliverySummary`

Вызов используется в двух местах:
- на этапе planning, чтобы получить решение по получателю и destination;
- на этапе provider-level отправки, чтобы повторно проверить допустимость доставки непосредственно перед вызовом mail provider.
History check используется только на этапе planning и работает в режиме fail-open.

## Исходящие интеграции

### Kafka producer
Через outbox сервис публикует:
- `notification.mail.delivery-statuses`

Событие:
- `aggregateType=mail_delivery`
- `eventType=MailDeliveryStatusChanged`

## Хранилище
Сервис использует схему `nf_mail`.

Основные таблицы:
- `consumer_inbox_message`
- `mail_delivery`
- `mail_delivery_attempt`
- `outbox_message`

Таблицы локального хранения пользовательских профилей и согласий сервис не использует.

## Основной поток обработки
1. Получить событие или dispatch из Kafka.
2. Записать входящее сообщение в inbox.
3. На этапе planning вызвать `profile-consent` для проверки канала `CHANNEL_EMAIL`.
4. На этапе planning запросить в `history-writer` краткую историю доставок по `recipient_id` и применить лимит по каналу.
5. Создать `mail_delivery` в статусе `PENDING` либо сохранить `SKIPPED` с `rule_code`.
6. На этапе отправки повторно вызвать `profile-consent`.
7. Передать разрешённые сообщения в mail provider.
8. Сохранить попытку и итоговый статус доставки.
9. Опубликовать `MailDeliveryStatusChanged` через outbox relay.
