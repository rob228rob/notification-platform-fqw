workspace "Платформа уведомлений" "Актуальная архитектура платформы уведомлений" {

    !identifiers hierarchical

    model {
        clientSystem = softwareSystem "Клиентская система" "Источник команд" {
            tags "External"
        }

        emailProvider = softwareSystem "Почтовый провайдер" "Внешний канал" {
            tags "External"
        }

        smsProvider = softwareSystem "SMS-провайдер" "Внешний канал" {
            tags "External"
        }

        platform = softwareSystem "Платформа уведомлений" "Контур обработки уведомлений" {
            facade = container "notification-facade" "Командный фасад" "Java/gRPC"
            templateRegistry = container "template-registry" "Шаблоны" "Java/gRPC"
            profileConsent = container "profile-consent" "Профиль и согласия" "Java/gRPC"
            cancellationService = container "cancellation-service" "Отмена доставки" "Java/gRPC"
            deliveryDispatcher = container "delivery-dispatcher" "Маршрутизация" "Java/Kafka"
            mailSender = container "notification-mail-sender" "Почтовая отправка" "Java/Kafka"
            smsSender = container "notification-sms-sender" "SMS-отправка" "Java/Kafka"
            historyWriter = container "history-writer" "История статусов" "Java/gRPC/Kafka"

            facadeDb = container "PostgreSQL фасада" "События и публикации" "PostgreSQL" {
                tags "Database"
            }

            dispatcherDb = container "PostgreSQL диспетчера" "Задачи и маршруты" "PostgreSQL" {
                tags "Database"
            }

            historyDb = container "ClickHouse истории" "История доставки" "ClickHouse" {
                tags "Database"
                tags "ClickHouseStore"
            }

            templateMongo = container "MongoDB шаблонов" "Версии шаблонов" "MongoDB" {
                tags "Database"
                tags "MongoStore"
            }

            profileDb = container "PostgreSQL профилей" "Профили и каналы" "PostgreSQL" {
                tags "Database"
            }

            cancellationRedis = container "Redis отмены" "Ключи с TTL" "Redis" {
                tags "Database"
                tags "RedisStore"
            }

            mailDedupRedis = container "Redis почты" "Дедупликация" "Redis" {
                tags "Database"
                tags "RedisStore"
            }

            smsDedupRedis = container "Redis SMS" "Дедупликация" "Redis" {
                tags "Database"
                tags "RedisStore"
            }

            kafka = container "Kafka" "Событийная шина" "Apache Kafka" {
                tags "MessageBus"
            }

            prometheus = container "Prometheus" "Метрики" "Prometheus"
            grafana = container "Grafana" "Дашборды" "Grafana"
        }

        clientSystem -> platform.facade "команды" "gRPC"

        platform.facade -> platform.templateRegistry "шаблон" "gRPC"
        platform.facade -> platform.cancellationService "отмена" "gRPC"
        platform.facade -> platform.facadeDb "события" "JDBC"
        platform.facade -> platform.kafka "запуск" "Kafka"

        platform.templateRegistry -> platform.templateMongo "шаблоны" "MongoDB"
        platform.profileConsent -> platform.profileDb "профили" "JDBC"
        platform.cancellationService -> platform.cancellationRedis "отмена" "Redis"

        platform.kafka -> platform.deliveryDispatcher "запросы" "Kafka"
        platform.deliveryDispatcher -> platform.dispatcherDb "задачи" "JDBC"
        platform.deliveryDispatcher -> platform.profileConsent "профиль" "gRPC"
        platform.deliveryDispatcher -> platform.kafka "команды" "Kafka"

        platform.kafka -> platform.mailSender "почта" "Kafka"
        platform.kafka -> platform.smsSender "SMS" "Kafka"
        platform.mailSender -> platform.cancellationService "проверка" "gRPC"
        platform.smsSender -> platform.cancellationService "проверка" "gRPC"
        platform.mailSender -> platform.mailDedupRedis "дедупликация" "Redis"
        platform.smsSender -> platform.smsDedupRedis "дедупликация" "Redis"
        platform.mailSender -> emailProvider "почта" "SMTP/API"
        platform.smsSender -> smsProvider "SMS" "API"
        platform.mailSender -> platform.kafka "статус" "Kafka"
        platform.smsSender -> platform.kafka "статус" "Kafka"

        platform.kafka -> platform.historyWriter "статусы" "Kafka"
        platform.historyWriter -> platform.historyDb "история" "JDBC"

        platform.prometheus -> platform.facade "метрики" "HTTP"
        platform.prometheus -> platform.templateRegistry "метрики" "HTTP"
        platform.prometheus -> platform.profileConsent "метрики" "HTTP"
        platform.prometheus -> platform.cancellationService "метрики" "HTTP"
        platform.prometheus -> platform.deliveryDispatcher "метрики" "HTTP"
        platform.prometheus -> platform.mailSender "метрики" "HTTP"
        platform.prometheus -> platform.smsSender "метрики" "HTTP"
        platform.prometheus -> platform.historyWriter "метрики" "HTTP"
        platform.grafana -> platform.prometheus "запросы" "PromQL"
    }

    views {
        systemContext platform "SystemContextCurrent" "Контекст платформы" {
            include *
            exclude platform.prometheus
            exclude platform.grafana
            autolayout lr
        }

        container platform "ContainersCurrent" "Обзор сервисов" {
            include clientSystem
            include platform.facade
            include platform.kafka
            include platform.deliveryDispatcher
            include platform.mailSender
            include platform.smsSender
            include platform.historyWriter
            autolayout lr
        }

        container platform "ContainersDataOwnership" "Владение данными" {
            include clientSystem
            include platform.facade
            include platform.facadeDb
            include platform.templateRegistry
            include platform.templateMongo
            include platform.kafka
            include platform.deliveryDispatcher
            include platform.dispatcherDb
            include platform.profileConsent
            include platform.profileDb
            include platform.cancellationService
            include platform.cancellationRedis
            include platform.mailSender
            include platform.mailDedupRedis
            include platform.smsSender
            include platform.smsDedupRedis
            include platform.historyWriter
            include platform.historyDb
            include emailProvider
            include smsProvider
            autolayout lr
        }

        container platform "ServicesPresentation" "Состав сервисов" {
            include platform.facade
            include platform.templateRegistry
            include platform.profileConsent
            include platform.cancellationService
            include platform.deliveryDispatcher
            include platform.mailSender
            include platform.smsSender
            include platform.historyWriter
            include platform.kafka
            autolayout lr
        }

        container platform "InboundCommandFlow" "Входной поток" {
            include clientSystem
            include platform.facade
            include platform.templateRegistry
            include platform.templateMongo
            include platform.facadeDb
            include platform.kafka
            include platform.cancellationService
            include platform.cancellationRedis
            autolayout lr
        }

        container platform "KafkaDeliveryFlow" "Kafka-поток" {
            include platform.facade
            include platform.kafka
            include platform.deliveryDispatcher
            include platform.mailSender
            include platform.smsSender
            include platform.historyWriter
            autolayout lr
        }

        container platform "EmailNotificationProcessingFlow" "Почтовая обработка" {
            include platform.kafka
            include platform.mailSender
            include platform.mailDedupRedis
            include platform.cancellationService
            include platform.cancellationRedis
            include emailProvider
            autolayout lr
        }

        container platform "DispatchRoutingFlow" "Маршрутизация" {
            include platform.kafka
            include platform.deliveryDispatcher
            include platform.dispatcherDb
            include platform.profileConsent
            include platform.profileDb
            autolayout lr
        }

        dynamic platform "CreateEventFlow" "Создание события" {
            clientSystem -> platform.facade "команда"
            platform.facade -> platform.templateRegistry "шаблон"
            platform.facade -> platform.facadeDb "запись"
            platform.facade -> platform.kafka "событие"
            autolayout lr
        }

        dynamic platform "DelayedMailDispatchFlow" "Отложенная доставка" {
            clientSystem -> platform.facade "запуск"
            platform.facade -> platform.templateRegistry "шаблон"
            platform.facade -> platform.facadeDb "запись"
            platform.facade -> platform.kafka "запрос"
            platform.kafka -> platform.deliveryDispatcher "получение"
            platform.deliveryDispatcher -> platform.dispatcherDb "задача"
            platform.deliveryDispatcher -> platform.kafka "команда"
            platform.kafka -> platform.mailSender "почта"
            autolayout lr
        }

        dynamic platform "DeliveryAndHistoryFlow" "Доставка и история" {
            platform.kafka -> platform.deliveryDispatcher "запрос"
            platform.deliveryDispatcher -> platform.profileConsent "профиль"
            platform.deliveryDispatcher -> platform.kafka "команда"
            platform.kafka -> platform.mailSender "почта"
            platform.mailSender -> platform.cancellationService "отмена"
            platform.mailSender -> emailProvider "отправка"
            platform.mailSender -> platform.kafka "статус"
            platform.kafka -> platform.deliveryDispatcher "резерв"
            platform.deliveryDispatcher -> platform.kafka "SMS"
            platform.kafka -> platform.historyWriter "статус"
            platform.historyWriter -> platform.historyDb "запись"
            autolayout lr
        }

        dynamic platform "KafkaMessageProcessingFlow" "Обработка сообщения" {
            platform.kafka -> platform.deliveryDispatcher "запрос"
            platform.deliveryDispatcher -> platform.profileConsent "профиль"
            platform.profileConsent -> platform.profileDb "чтение"
            platform.deliveryDispatcher -> platform.kafka "команда"
            platform.kafka -> platform.mailSender "почта"
            platform.mailSender -> platform.mailDedupRedis "дедупликация"
            platform.mailSender -> platform.cancellationService "отмена"
            platform.cancellationService -> platform.cancellationRedis "чтение"
            platform.mailSender -> emailProvider "отправка"
            platform.mailSender -> platform.kafka "статус"
            platform.kafka -> platform.historyWriter "статус"
            platform.historyWriter -> platform.historyDb "запись"
            autolayout lr
        }

        dynamic platform "ProcessingFlowPresentation" "Основной поток" {
            clientSystem -> platform.facade "команда"
            platform.facade -> platform.kafka "запрос"
            platform.kafka -> platform.deliveryDispatcher "получение"
            platform.deliveryDispatcher -> platform.kafka "команда"
            platform.kafka -> platform.mailSender "почта"
            platform.mailSender -> platform.kafka "статус"
            platform.kafka -> platform.historyWriter "история"
            autolayout lr
        }

        dynamic platform "SchedulingFlowPresentation" "Планирование" {
            clientSystem -> platform.facade "запуск"
            platform.facade -> platform.kafka "запрос"
            platform.kafka -> platform.deliveryDispatcher "получение"
            platform.deliveryDispatcher -> platform.dispatcherDb "задача"
            platform.deliveryDispatcher -> platform.kafka "команда"
            platform.kafka -> platform.mailSender "почта"
            platform.mailSender -> platform.kafka "статус"
            platform.kafka -> platform.historyWriter "история"
            autolayout lr
        }

        styles {
            element "Person" {
                shape person
                background "#ffffff"
                color "#000000"
                stroke "#000000"
            }

            element "Software System" {
                shape roundedbox
                background "#ffffff"
                color "#000000"
                stroke "#000000"
            }

            element "Container" {
                shape roundedbox
                background "#ffffff"
                color "#000000"
                stroke "#000000"
            }

            element "Database" {
                shape cylinder
                background "#ffffff"
                color "#000000"
                stroke "#000000"
            }

            element "RedisStore" {
                background "#ffffff"
                color "#000000"
                stroke "#000000"
            }

            element "ClickHouseStore" {
                background "#ffffff"
                color "#000000"
                stroke "#000000"
            }

            element "MongoStore" {
                background "#ffffff"
                color "#000000"
                stroke "#000000"
            }

            element "MessageBus" {
                shape pipe
                background "#ffffff"
                color "#000000"
                stroke "#000000"
            }

            element "External" {
                background "#ffffff"
                color "#000000"
                stroke "#000000"
            }

            relationship "Relationship" {
                color "#000000"
                thickness 2
            }
        }
    }
}
