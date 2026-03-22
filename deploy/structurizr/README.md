# Structurizr Lite

Файл Compose: `deploy/structurizr/docker-compose.structurizr.yml`

Запуск:

```powershell
cd deploy
docker compose -f structurizr/docker-compose.structurizr.yml -f networks/docker-compose.networks.yml up -d
```

URL:
- http://localhost:8088

Workspace:
- файл `structurizr-108584-workspace.dsl` лежит в корне репозитория и доступен контейнеру как `/usr/local/structurizr/structurizr-108584-workspace.dsl`.

Перезапуск после правок compose:

```powershell
cd deploy
docker compose -f structurizr/docker-compose.structurizr.yml -f networks/docker-compose.networks.yml down
docker compose -f structurizr/docker-compose.structurizr.yml -f networks/docker-compose.networks.yml up -d
```
