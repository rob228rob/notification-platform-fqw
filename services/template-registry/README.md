# template-registry

## Назначение
Сервис хранит шаблоны уведомлений, их версии и выполняет preview-рендер по payload.

## API
Proto-контракт: `libs/proto-templates/src/main/proto/notification/templates/v1/templates.proto`.

Реализованы методы:
- `CreateTemplate`
- `UpdateTemplate`
- `GetTemplate`
- `ListTemplates`
- `RenderPreview`

## Модель хранения (MongoDB)
- Коллекция: `templates`.
- Документ содержит:
  - метаданные шаблона (`templateId`, `clientId`, `name`, `status`, `activeVersion`);
  - `createIdempotencyKey` для идемпотентного создания;
  - массив версий `versions` с каналами и контентом.
- Индексы:
  - уникальный `(clientId, templateId)`;
  - уникальный `(clientId, createIdempotencyKey)` (sparse);
  - `(clientId, updatedAt)` для пагинации и сортировки.

## Рендеринг
`RenderPreview` выбирает нужную версию (явную или `activeVersion`), канал и рендерит `subject/body` через выбранный `TemplateEngine`.

## Kafka
Сервис не публикует и не потребляет доменные события в текущей реализации.
