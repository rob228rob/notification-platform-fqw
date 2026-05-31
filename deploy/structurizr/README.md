# Structurizr Lite

Compose-файл: `deploy/structurizr/docker-compose.structurizr.yml`.

Сервис запускает Structurizr Lite в режиме `local` и монтирует корень репозитория в контейнер:

```text
../../structurizr:/usr/local/structurizr
```

Поэтому контейнер видит файлы:

- `structurizr/1/workspace.dsl`;
- `structurizr/2/workspace.dsl`;
- `structurizr/3/workspace.dsl`;
- другие workspace-файлы, если они лежат под `structurizr`.

Список рабочих пространств задаётся в `structurizr/structurizr.properties`:

```properties
structurizr.workspaces=*
structurizr.editable=true
structurizr.autoRefreshInterval=2000
```

`autoRefreshInterval=2000` означает, что при уже запущенном контейнере изменения DSL-файлов должны подхватываться без ручного пересоздания контейнера. После `git pull` на сервере достаточно, чтобы контейнер продолжал видеть тот же bind mount.

## Локальный запуск

Из директории `deploy`:

```powershell
docker compose -f structurizr/docker-compose.structurizr.yml -f networks/docker-compose.networks.yml up -d
```

URL:

```text
http://localhost:8088
```

## Перезапуск

Из директории `deploy`:

```powershell
docker compose -f structurizr/docker-compose.structurizr.yml -f networks/docker-compose.networks.yml down
docker compose -f structurizr/docker-compose.structurizr.yml -f networks/docker-compose.networks.yml up -d
```

## CI/CD

В `.github/workflows/deploy-remote.yml` после основного `docker compose up -d --remove-orphans` отдельно выполняется:

```bash
docker system prune -af || true
cd ~/own_apps/notification-platform-fqw
git pull --ff-only
cd deploy
docker rm -f structurizr-lite || true
docker compose -f structurizr/docker-compose.structurizr.yml -f networks/docker-compose.networks.yml up -d --remove-orphans
```

Это нужно потому, что `deploy/docker-compose.yaml` сейчас не включает Structurizr Lite в общий compose-файл. Без отдельной команды контейнер не стартует автоматически, если он был остановлен или отсутствовал на сервере.

`docker system prune -af` освобождает место перед `git pull`, если на VM накопились старые Docker-слои. Команда не удаляет volumes.

`docker rm -f structurizr-lite || true` нужен для случая, когда контейнер с таким именем уже существует, но был создан другим compose-проектом или вручную. Без удаления Docker возвращает ошибку `container name "/structurizr-lite" is already in use`.
