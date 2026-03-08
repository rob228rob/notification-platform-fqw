# Notification Platform

## Назначение

Проект реализует два ключевых сервиса:

- `notification-facade` — API и orchestration-слой для создания notification events, управления аудиторией, запуска dispatch и публикации событий в Kafka через outbox.
- `notification-mail-sender` — асинхронный mail delivery processor: принимает события из Kafka, пишет их в inbox, применяет бизнес-правила, планирует доставки в БД и выполняет отправку через mail gateway.

## Сервисы

### `notification-facade`

Основные сценарии:

- Создание notification event через gRPC `CreateNotificationEvent`
- Добавление получателей через `AddRecipients`
- Запуск dispatch через `TriggerDispatch`
- Публикация событий из локальной БД в Kafka через outbox relay

Публикуемые Kafka-события:

- `notification_event / EventCreated` в topic `notification.facade.events`
- `mail_dispatch / MailDispatchRequested` в topic `notification.mail.dispatches`

### `notification-mail-sender`

Основные сценарии:

- Чтение `notification_event / EventCreated` из Kafka
- Чтение `mail_dispatch / MailDispatchRequested` из Kafka
- Идемпотентная запись входящих событий в `nf.consumer_inbox_message`
- Отложенная обработка inbox через scheduler-воркеры
- Применение business rules перед постановкой mail delivery
- Выполнение отправки через `MailGateway`

## Потоки данных

### 1. Event creation flow

1. Клиент вызывает `CreateNotificationEvent` в `notification-facade`
2. `notification-facade` сохраняет event в своей БД
3. Outbox relay публикует `notification_event / EventCreated` в Kafka topic `notification.facade.events`
4. `notification-mail-sender` читает событие через `NotificationEventConsumer`
5. Consumer пишет событие в `nf.consumer_inbox_message`
6. `NotificationEventWorkflow` раз в `10000ms` подбирает `NEW` inbox rows
7. Workflow валидирует payload, применяет business rules и создаёт запись в `nf.mail_delivery`
8. `MailDispatchWorkflow` подбирает `PENDING` доставки и вызывает `MailGateway.send(...)`
9. `LoggingMailGateway` пишет `MAIL send ...` в лог

### 2. Dispatch flow

1. Клиент вызывает `TriggerDispatch` в `notification-facade`
2. `notification-facade` создаёт dispatch и пишет `mail_dispatch / MailDispatchRequested` в outbox
3. Outbox relay публикует сообщение в Kafka topic `notification.mail.dispatches`
4. `notification-mail-sender` читает событие через `MailNotificationConsumer`
5. Consumer пишет событие в `nf.consumer_inbox_message`
6. `MailDispatchWorkflow` подбирает `NEW` inbox rows типа `mail_dispatch / MailDispatchRequested`
7. Для каждого recipient применяются business rules
8. Разрешённые доставки пишутся в `nf.mail_delivery` со статусом `PENDING`
9. Запрещённые доставки пишутся в `nf.mail_delivery` со статусом `SKIPPED`
10. Delivery worker берёт `PENDING/RETRY` и отправляет через `MailGateway`

## Гарантии и идемпотентность

### Kafka consumer inbox

- Все входящие события сначала пишутся в `nf.consumer_inbox_message`
- `message_id` — UUID из контракта события
- Дубликаты режутся на уровне БД
- Kafka consumer не выполняет бизнес-логику и не отправляет mail напрямую

### Delivery deduplication

- Все попытки доставки планируются в `nf.mail_delivery`
- Основной ключ доставки: `(dispatch_id, recipient_id, channel)`
- Дополнительно используется `idempotency_key`
- Это исключает повторную постановку одной и той же доставки

### Retry model

- Повторные попытки выполняются из БД
- Статусы доставки хранятся в `nf.mail_delivery`
- История попыток хранится в `nf.mail_delivery_attempt`

## Business rules

Проверки выполняются в `MailDeliveryPlanService`.

Текущие правила:

- проверка `active`
- проверка `email_consent`
- ограничение по количеству доставок за окно `delivery.mail.counting-window`
- fallback на дефолтные recipient settings, если запись в `nf.recipient_mail_settings` не найдена

Поведение при отсутствии recipient settings:

- используется кодовый default:
  - `emailConsent = true`
  - `active = true`
  - `maxDeliveriesPerDay = delivery.mail.default-max-deliveries`

## Схема БД

### `notification-facade`

Facade хранит собственные сущности:

- `notification_event`
- `event_audience`
- `event_recipient`
- `dispatch`
- `dispatch_target`
- `nf.outbox_message`

Facade не зависит от таблиц `notification-mail-sender`.

### `notification-mail-sender`

Mail sender хранит только свои локальные таблицы:

- `nf.consumer_inbox_message`
- `nf.recipient_mail_settings`
- `nf.mail_delivery`
- `nf.mail_delivery_attempt`

## Kafka topics

- `notification.facade.events` — envelope для `notification_event / EventCreated`
- `notification.mail.dispatches` — envelope для `mail_dispatch / MailDispatchRequested`
- `notification.facade.dispatches` — общий dispatch topic, в mail-sender сейчас не используется

## Конфигурация

### `notification-facade`

Основной конфиг:

- `services/notification-facade/src/main/resources/application.yaml`

Критичные параметры:

- `outbox.relay.enabled`
- `outbox.relay.topics.events`
- `outbox.relay.topics.mail-dispatches`
- `spring.kafka.bootstrap-servers`

### `notification-mail-sender`

Основной конфиг:

- `services/notification-mail-sender/src/main/resources/application.yaml`

Критичные параметры:

- `delivery.mail.inbox-fixed-delay`
- `delivery.mail.delivery-fixed-delay`
- `delivery.notification-event.fixed-delay`
- `delivery.mail.default-max-deliveries`
- `delivery.mail.max-attempts`
- `spring.kafka.bootstrap-servers`

## Сборка

Сборка всего проекта:

```powershell
.\gradlew build
```

Сборка только facade:

```powershell
.\gradlew :services:notification-facade:compileJava
```

Сборка только mail-sender:

```powershell
.\gradlew :services:notification-mail-sender:compileJava
```

## Подъём инфраструктуры

Docker compose находится в `deploy/docker-compose.yaml`.

Поднять инфраструктуру:

```powershell
cd deploy
docker compose up -d
```

Поднимаются:

- Postgres для `notification-facade`
- Postgres для `notification-mail-sender`
- Kafka
- Zookeeper
- общая docker network

## Локальный запуск сервисов

Запуск `notification-facade`:

```powershell
.\gradlew :services:notification-facade:bootRun
```

Запуск `notification-mail-sender`:

```powershell
.\gradlew :services:notification-mail-sender:bootRun
```
