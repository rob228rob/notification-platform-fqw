# template-registry

## Назначение
Хранилище и рендер шаблонов уведомлений (версионирование + preview рендер по payload)

## Входящие интерфейсы

### gRPC API
Proto: `libs/proto-templates/src/main/proto/notification/templates/v1/templates.proto`

Сервис: `notification.templates.v1.TemplateRegistry`

Методы:
- `CreateTemplate` создать шаблон (v1).
- `UpdateTemplate` создать новую версию шаблона.
- `GetTemplate` получить шаблон и версию (explicit или active).
- `ListTemplates` пагинированный список шаблонов.
- `RenderPreview` рендер subject/body для `template_id + version + channel + payload`.

## Kafka
- Входящих обработчиков нет.
- Исходящей доменной публикации нет.

## Контракт рендера ( для интеграции)
`RenderPreviewRequest`:
- `template_id`
- `version` (если `0`, используется active version)
- `channel`
- `payload: map<string,string>`

`RenderPreviewResponse`:
- `subject`
- `body`
