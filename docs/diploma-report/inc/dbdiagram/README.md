# DBML Schemas For VKR

В этой директории лежат DBML-версии схем данных проекта для визуализации в `dbdiagram.io`.

## Что здесь находится

- `facade-postgres.dbml` — логическая схема PostgreSQL `notification-facade`.
- `delivery-dispatcher-postgres.dbml` — логическая схема PostgreSQL `delivery-dispatcher`.
- `profile-consent-postgres.dbml` — логическая схема PostgreSQL `profile-consent`.
- `history-clickhouse.dbml` — аналитическая схема ClickHouse `history-writer`.

## Как использовать

1. Открыть `https://dbdiagram.io`.
2. Создать новый diagram.
3. Скопировать содержимое нужного `.dbml` файла.
4. Вставить его в редактор DBML.

## Что важно

- DBML здесь отражает логическую схему для ВКР и визуального ERD-представления.
- DBML не заменяет production DDL и не обязан содержать все технические колонки из миграций.
- PlantUML-версии схем остаются в `docs/plantuml`.
- Демонстрационные примеры данных остаются в `docs/plantuml/examples`.

## Почему нет DBML для Redis и MongoDB

`dbdiagram.io` лучше всего подходит для реляционных схем. Поэтому:

- Redis key-space для `cancellation-service`, `notification-mail-sender` и `notification-sms-sender`
  описан в PlantUML и JSON-примерах, а не в виде фейковой реляционной ERD.
- MongoDB `template-registry` тоже оставлен в виде PlantUML/JSON-примеров без искусственного
  преобразования документов в SQL-таблицы.

## Ограничения

- Между БД разных микросервисов не добавляются фейковые внешние ключи.
- Для ClickHouse показывается аналитическая append-only модель без физических FK.
- Для `delivery-dispatcher` dispatch, dispatch target, outbox и scheduled task показаны как
  доменная модель контура диспетчеризации. В runtime-миграциях часть этих сущностей может
  быть реализована компактнее, но на ERD они вынесены для читаемого разделения ответственности.
