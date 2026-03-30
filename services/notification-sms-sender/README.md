# notification-sms-sender

Сервис обработки SMS-доставок. Логика сервиса повторяет общий конвейер mail-sender: Kafka inbox -> планирование доставки -> попытки отправки -> outbox статусов.

## Ответственность

- принимает `notification_event` и `sms_dispatch` сообщения из Kafka;
- складывает входящие сообщения в локальный inbox;
- для каждого получателя запрашивает согласия и предпочтения через `profile-consent`;
- формирует записи доставок в схеме `nf_sms`;
- выполняет попытки отправки и пишет статусы в outbox.

## Входы и выходы

- consumer topic `notification.facade.events`
- consumer topic `notification.sms.dispatches`
- producer topic `notification.sms.delivery-statuses`
- schema `nf_sms`

## Основные таблицы

- `nf_sms.consumer_inbox_message`
- `nf_sms.sms_delivery`
- `nf_sms.sms_delivery_attempt`
- `nf_sms.outbox_message`

## Внешние зависимости

- PostgreSQL
- Kafka
- gRPC service `profile-consent`
