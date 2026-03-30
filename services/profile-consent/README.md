# profile-consent

Сервис централизованного чтения коммуникационного профиля получателя

## Ответственность

- отдает контакты получателя по каналм из контрактов прото
- отдает предпочтительный канал связи 
- отдает согласия на отправку и признаки блокировки канала
- скрывает внутреннюю схему хранения профиелй за единым gRPC API

## Контракты

- gRPC service: `ProfileConsentService`
- proto: `libs/proto-profile/src/main/proto/notification/facade/v1/profile.proto`

Поддерживаемые методы:
- `GetRecipientProfile`
- `BatchGetRecipientProfiles`
- `CheckRecipientChannel`

## Хранилище

Redis используется как оперативная read-model по ключу , предполагаем, что настроена репликация в это хранилище из мастер-системы, котоаря отвечает за хранение пользовательских данных

`profile-consent:recipient:{recipientId}`.

Пример hash-полей:
- `active`
- `preferred_channel`
- `email.destination`
- `email.enabled`
- `email.blacklisted`
- `sms.destination`
- `sms.enabled`
- `sms.blacklisted`
- `push.destination`
- `push.enabled`
- `push.blacklisted`
- `updated_at`

## Конфигурация

- `PROFILE_CONSENT_GRPC_PORT` gRPC порт сервиса, по умолчанию `9096`
- `PROFILE_CONSENT_REDIS_HOST` host Redis, по умолчанию `localhost`
- `PROFILE_CONSENT_REDIS_PORT` port Redis, по умолчанию `6380`
- `PROFILE_CONSENT_REDIS_USERNAME` ACL user Redis, по умолчанию `redisuser`
- `PROFILE_CONSENT_REDIS_PASSWORD` пароль Redis user
- `PROFILE_CONSENT_REDIS_KEY_PREFIX` префикс ключей профилей

## Проверка

Сервис компилируется как Spring Boot приложение и поднимает только gRPC endpoint и Redis-клиент. HTTP API и локальная БД ему не нужны