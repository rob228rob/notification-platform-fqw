workspace "Платформа уведомлений" "Русская чёрно-белая версия архитектурных диаграмм для презентации" {

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
            facade = container "notification-facade" "Командный фасад" "Java, Spring Boot, gRPC"
            templateRegistry = container "template-registry" "Шаблоны" "Java, Spring Boot, gRPC"
            profileConsent = container "profile-consent" "Профиль и согласия" "Java, Spring Boot, gRPC"
            cancellationService = container "cancellation-service" "Отмена доставки" "Java, Spring Boot, gRPC"
            deliveryDispatcher = container "delivery-dispatcher" "Маршрутизация" "Java, Spring Boot, Kafka"
            mailSender = container "notification-mail-sender" "Почтовая отправка" "Java, Spring Boot, Kafka"
            smsSender = container "notification-sms-sender" "SMS-отправка" "Java, Spring Boot, Kafka"
            historyWriter = container "history-writer" "История статусов" "Java, Spring Boot, gRPC, Kafka"

            facadeDb = container "PostgreSQL фасада" "События и публикации" "PostgreSQL" {
                tags "Database"
            }

            dispatcherDb = container "PostgreSQL диспетчера" "Планирование" "PostgreSQL" {
                tags "Database"
            }

            historyDb = container "ClickHouse истории" "Статусы доставки" "ClickHouse" {
                tags "Database"
            }

            templateMongo = container "MongoDB шаблонов" "Версии шаблонов" "MongoDB" {
                tags "Database"
            }

            profileDb = container "PostgreSQL профилей" "Профили и каналы" "PostgreSQL" {
                tags "Database"
            }

            cancellationRedis = container "Redis отмены" "Ключи TTL" "Redis" {
                tags "Database"
            }

            mailDedupRedis = container "Redis email" "Дедупликация" "Redis" {
                tags "Database"
            }

            smsDedupRedis = container "Redis SMS" "Дедупликация" "Redis" {
                tags "Database"
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
        systemContext platform "RU_SystemContextBW" "Контекст платформы" {
            include *
            exclude platform.prometheus
            exclude platform.grafana
            autolayout lr
        }

        container platform "RU_ServicesBW" "Состав сервисов" {
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

        container platform "RU_InboundBW" "Входной контур" {
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

        container platform "RU_KafkaBW" "Kafka-контур" {
            include platform.facade
            include platform.kafka
            include platform.deliveryDispatcher
            include platform.mailSender
            include platform.smsSender
            include platform.historyWriter
            autolayout lr
        }

        container platform "RU_EmailBW" "Почтовая обработка" {
            include platform.kafka
            include platform.mailSender
            include platform.mailDedupRedis
            include platform.cancellationService
            include platform.cancellationRedis
            include emailProvider
            autolayout lr
        }

        container platform "RU_RoutingBW" "Маршрутизация" {
            include platform.kafka
            include platform.deliveryDispatcher
            include platform.dispatcherDb
            include platform.profileConsent
            include platform.profileDb
            autolayout lr
        }

        dynamic platform "RU_ProcessBW" "Основной поток" {
            clientSystem -> platform.facade "команда"
            platform.facade -> platform.kafka "запуск"
            platform.kafka -> platform.deliveryDispatcher "запрос"
            platform.deliveryDispatcher -> platform.profileConsent "профиль"
            platform.deliveryDispatcher -> platform.kafka "команда"
            platform.kafka -> platform.mailSender "почта"
            platform.mailSender -> platform.cancellationService "отмена"
            platform.mailSender -> emailProvider "отправка"
            platform.mailSender -> platform.kafka "статус"
            platform.kafka -> platform.historyWriter "статус"
            platform.historyWriter -> platform.historyDb "запись"
            autolayout lr
        }

        dynamic platform "RU_DelayedBW" "Отложенная доставка" {
            clientSystem -> platform.facade "запуск"
            platform.facade -> platform.kafka "запрос"
            platform.kafka -> platform.deliveryDispatcher "получение"
            platform.deliveryDispatcher -> platform.dispatcherDb "задача"
            platform.deliveryDispatcher -> platform.kafka "команда"
            platform.kafka -> platform.mailSender "почта"
            platform.mailSender -> platform.kafka "статус"
            platform.kafka -> platform.historyWriter "статус"
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
