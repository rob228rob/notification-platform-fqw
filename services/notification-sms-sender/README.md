# notification-sms-sender

## Назначение
Сервис отвечает за обработку SMS-доставок.
Он читает события и SMS-dispatch сообщения из Kafka, планирует записи в `nf_sms.sms_delivery`, выполняет отправку через SMS provider и публикует изменения статусов через outbox.

Пользовательские данные и согласия сервис не хранит как источник истины.
На этапе planning и непосредственно перед отправкой сведения о допустимости SMS-доставки и номере получателя запрашиваются только через gRPC-сервис `profile-consent`.
На этапе planning сервис дополнительно может запросить в `history-writer` краткую сводку по `recipient_id` и применить SMS-specific лимит.

## Входящие интеграции

### Kafka consumers
Сервис читает:
- `notification.facade.events`
- `notification.sms.dispatches`

Обрабатываемые сообщения:
- `aggregateType=notification_event`, `eventType=EventCreated`
- `aggregateType=sms_dispatch`, `eventType=SmsDispatchRequested`

### gRPC client
Сервис вызывает `ProfileConsentService`:
- `BatchGetRecipientProfiles`
- `CheckRecipientChannel`

Сервис вызывает `NotificationHistoryService`:
- `BatchGetRecipientDeliverySummaries`

Вызовы используются так:
- на этапе planning выполняется batch-запрос по получателям dispatch;
- на этапе provider-level отправки выполняется дополнительная точечная проверка каждого сообщения перед обращением к SMS provider.
History check выполняется только на этапе planning и работает в режиме fail-open.

## Исходящие интеграции

### Kafka producer
Через outbox сервис публикует:
- `notification.sms.delivery-statuses`

Событие:
- `aggregateType=sms_delivery`
- `eventType=SmsDeliveryStatusChanged`

## Хранилище
Сервис использует схему `nf_sms`.

Основные таблицы:
- `consumer_inbox_message`
- `sms_delivery`
- `sms_delivery_attempt`
- `outbox_message`

Локальные таблицы профилей пользователей, согласий и предпочтений сервис не использует.

## Основной поток обработки
1. Получить событие или `sms_dispatch` из Kafka.
2. Записать входящее сообщение в inbox.
3. На этапе planning запросить профили получателей через `profile-consent`.
4. На этапе planning запросить краткую историю доставок через `history-writer` и применить лимит по каналу.
5. Создать `sms_delivery` в статусе `PENDING` либо сохранить `SKIPPED` с `rule_code`.
6. На этапе отправки повторно проверить допустимость доставки через `CheckRecipientChannel`.
7. Передать разрешённые сообщения в SMS provider.
8. Сохранить попытку и итоговый статус доставки.
9. Опубликовать `SmsDeliveryStatusChanged` через outbox relay.
