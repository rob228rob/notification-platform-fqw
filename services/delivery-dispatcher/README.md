# delivery-dispatcher

Сервис принимает запросы на диспетчеризацию доставки из Kafka-топика `delivery.dispatcher`, хранит отложенные задачи в PostgreSQL и публикует канальные команды в sender-топики.

## Ответственность

- consume `delivery.dispatcher`;
- отложенный запуск по `planned_send_at`;
- маршрутизация в `notification.mail.dispatches` и `notification.sms.dispatches`;
- consume `delivery.fallback` и запуск fallback-маршрута;
- минимальная оркестрация жизненного цикла доставки без переноса валидации, аудитории и шаблонов из facade.

## Хранилище и топики

- `PostgreSQL`: таблица `nf_sched.scheduled_delivery_task`
- `Kafka input`: `${dispatcher.delivery.dispatch-topic}` и `${dispatcher.delivery.fallback-topic}`
- `Kafka output`: `${dispatcher.delivery.mail-topic}` и `${dispatcher.delivery.sms-topic}`

## Smoke

- предпосылки:
  - запущены `kafka`, `postgres-db` и `delivery-dispatcher`
- запуск:
  - `powershell -File deploy/smoke/delivery-dispatcher-smoke.ps1`
