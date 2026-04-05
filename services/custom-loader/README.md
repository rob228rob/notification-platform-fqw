# custom-loader

Простой Java CLI без Spring для двух задач:

1. генерация нескольких тысяч профилей пользователей и запись их в Redis в формате, совместимом с `profile-consent`;
2. многопоточная gRPC-нагрузка на `notification-facade`.

## Что делает

- создаёт `recipient_id` вида `load-user-00001`;
- для каждого пользователя пишет в Redis:
  - `active`
  - `preferred_channel`
  - `email.enabled`, `email.blacklisted`, `email.destination`
  - `sms.enabled`, `sms.blacklisted`, `sms.destination`
  - `push.enabled`, `push.blacklisted`, `push.destination`
- шлёт `CreateNotificationEvent` в facade с:
  - разными `idempotencyKey`
  - разными `subject`, `body`, `category`, `tenant`, `correlationId`
  - разными `priority`
  - смесью `IMMEDIATE` и `SCHEDULED`
  - смесью `CHANNEL_EMAIL` и `CHANNEL_SMS`

## Запуск

Прямой запуск `main` из IntelliJ тоже работает без аргументов.
Дефолтный конфиг читается из:

```text
services/custom-loader/src/main/resources/loader.properties
```

Его можно править под текущий стенд.

Дефолтный профиль без аргументов:

- `users=10000`
- `duration=600s`
- `qpsStart=10`
- `qpsEnd=250`
- `threads=24`

Только seed:

```powershell
.\gradlew :services:custom-loader:run --args="--mode seed --users 5000"
```

Только нагрузка:

```powershell
.\gradlew :services:custom-loader:run --args="--mode load --users 10000 --threads 32 --qps-start 10 --qps-end 250 --duration-seconds 600 --facade-host localhost --facade-port 9090"
```

Seed + нагрузка:

```powershell
.\gradlew :services:custom-loader:run --args="--mode seed-and-load --users 10000 --threads 32 --qps-start 10 --qps-end 250 --duration-seconds 600"
```

## Основные параметры

- `--mode` : `seed`, `load`, `seed-and-load`
- `--users` : число пользователей
- `--threads` : число потоков нагрузки
- `--qps-start` : стартовый суммарный QPS
- `--qps-end` : конечный суммарный QPS
- `--duration-seconds` : длительность нагрузки
- `--facade-host`
- `--facade-port`
- `--redis-host`
- `--redis-port`
- `--redis-user`
- `--redis-password`
- `--redis-key-prefix`
- `--recipient-prefix`
- `--email-share`
- `--sms-share`
- `--template-id`
- `--template-version`

## Значения по умолчанию

- `facade`: `localhost:9090`
- `redis`: `localhost:6379`
- `redis user`: `redisuser`
- `redis password`: `redisuserpassword`
- `redis key prefix`: `profile-consent:recipient:`
- `users`: `10000`
- `threads`: `24`
- `qpsStart`: `10`
- `qpsEnd`: `250`
- `duration`: `600s`
- `templateId`: `tmpl-order-reminder`
- `templateVersion`: `1`

## Ожидаемый поток

1. профили пишутся в Redis;
2. `profile-consent` читает их как обычные production-like данные;
3. `notification-facade` принимает gRPC-запросы;
4. дальше цепочка идёт через Kafka в sender;
5. sender запрашивает `profile-consent` и использует seeded-получателей.
