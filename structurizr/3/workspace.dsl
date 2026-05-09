workspace "Notification Platform - Current Implementation" "Actual architecture of the implemented notification platform" {

    !identifiers hierarchical

    model {
        clientSystem = softwareSystem "Client Information System" "External system that creates notification events, triggers dispatches, requests cancellations and reads status/history." {
            tags "External"
        }

        emailProvider = softwareSystem "Email Provider" "External provider or SMTP server used by the mail sender." {
            tags "External"
        }

        smsProvider = softwareSystem "SMS Provider" "External SMS gateway or provider API used by the SMS sender." {
            tags "External"
        }

        platform = softwareSystem "Notification Platform" "Distributed event-driven platform for multi-channel notification delivery." {
            facade = container "Notification Facade" "Accepts gRPC commands, validates input, manages events, audience and dispatches, renders templates and publishes dispatch requests." "Java, Spring Boot, gRPC"
            templateRegistry = container "Template Registry" "Stores template versions and renders subject/body for a chosen channel." "Java, Spring Boot, gRPC"
            profileConsent = container "Profile Consent" "Provides recipient channel permissions and destinations." "Java, Spring Boot, gRPC"
            cancellationService = container "Cancellation Service" "Stores dispatch cancellation marks in Redis and provides fast cancellation checks for senders." "Java, Spring Boot, gRPC"
            deliveryDispatcher = container "Delivery Dispatcher" "Consumes delivery dispatch requests, manages scheduling, routes delivery commands to sender topics and orchestrates fallback." "Java, Spring Boot, Kafka"
            mailSender = container "Notification Mail Sender" "Consumes delivery commands, performs Redis dedup and cancellation checks, sends e-mail and publishes statuses or fallback events." "Java, Spring Boot, Kafka"
            smsSender = container "Notification SMS Sender" "Consumes delivery commands, performs Redis dedup and cancellation checks, sends SMS and publishes statuses." "Java, Spring Boot, Kafka"
            historyWriter = container "History Writer" "Consumes delivery statuses and provides gRPC access to delivery history and summaries." "Java, Spring Boot, gRPC, Kafka"

            facadeDb = container "Facade PostgreSQL" "Stores notification events, audience, dispatch requests and outbox records owned by the facade." "PostgreSQL" {
                tags "Database"
            }

            dispatcherDb = container "Dispatcher PostgreSQL" "Stores delayed dispatch tasks, routing decisions and dispatcher scheduling state." "PostgreSQL" {
                tags "Database"
            }

            historyDb = container "ClickHouse History Store" "Stores delivery history and analytical read models." "ClickHouse" {
                tags "Database"
                tags "ClickHouseStore"
            }

            templateMongo = container "MongoDB Templates" "Stores templates, versions, channel contents and activeVersion." "MongoDB" {
                tags "Database"
                tags "MongoStore"
            }

            profileRedis = container "Redis Recipient Profiles" "Recipient profile storage used only by the Profile Consent service." "Redis" {
                tags "Database"
                tags "RedisStore"
            }

            cancellationRedis = container "Redis Cancellation Marks" "TTL storage for dispatch cancellation keys, default TTL 30 minutes." "Redis" {
                tags "Database"
                tags "RedisStore"
            }

            mailDedupRedis = container "Redis Mail Dedup" "Local TTL dedup storage for mail sender commands." "Redis" {
                tags "Database"
                tags "RedisStore"
            }

            smsDedupRedis = container "Redis SMS Dedup" "Local TTL dedup storage for SMS sender commands." "Redis" {
                tags "Database"
                tags "RedisStore"
            }

            kafka = container "Kafka" "Asynchronous transport for dispatch requests, sender commands, fallback events and delivery statuses. Topics: delivery.dispatcher, delivery.mail.commands, delivery.sms.commands, delivery.fallback, delivery.statuses." "Apache Kafka" {
                tags "MessageBus"
            }

            prometheus = container "Prometheus" "Collects service metrics from Actuator and Micrometer endpoints." "Prometheus"
            grafana = container "Grafana" "Visualizes system quality and observability metrics." "Grafana"
        }

        clientSystem -> platform.facade "Creates notification events, triggers dispatch, cancels dispatch and requests status/history" "gRPC"

        platform.facade -> platform.templateRegistry "Renders templates" "gRPC"
        platform.facade -> platform.cancellationService "Cancels dispatch" "gRPC"
        platform.facade -> platform.facadeDb "Writes events, audience, dispatch requests and outbox records" "JDBC"
        platform.facade -> platform.kafka "Publishes dispatch requests to delivery.dispatcher" "Kafka"

        platform.templateRegistry -> platform.templateMongo "Stores templates" "MongoDB"
        platform.profileConsent -> platform.profileRedis "Reads recipient profiles" "Redis"
        platform.cancellationService -> platform.cancellationRedis "Reads/writes cancellation marks with TTL" "Redis"

        platform.kafka -> platform.deliveryDispatcher "Consumes dispatch requests and fallback events" "Kafka"
        platform.deliveryDispatcher -> platform.dispatcherDb "Stores delayed dispatch tasks, routing decisions and dispatcher scheduling state" "JDBC"
        platform.deliveryDispatcher -> platform.profileConsent "Resolves recipient channel availability and routing constraints" "gRPC"
        platform.deliveryDispatcher -> platform.kafka "Publishes sender commands to mail/sms topics" "Kafka"

        platform.kafka -> platform.mailSender "Consumes mail delivery commands" "Kafka"
        platform.kafka -> platform.smsSender "Consumes SMS delivery commands" "Kafka"
        platform.mailSender -> platform.cancellationService "Checks dispatch cancellation before send" "gRPC"
        platform.smsSender -> platform.cancellationService "Checks dispatch cancellation before send" "gRPC"
        platform.mailSender -> platform.mailDedupRedis "Deduplicates mail commands" "Redis"
        platform.smsSender -> platform.smsDedupRedis "Deduplicates SMS commands" "Redis"
        platform.mailSender -> emailProvider "Sends email" "SMTP / provider API"
        platform.smsSender -> smsProvider "Sends SMS" "Provider API"
        platform.mailSender -> platform.kafka "Publishes statuses and fallback events to delivery.fallback when final mail attempt fails" "Kafka"
        platform.smsSender -> platform.kafka "Publishes delivery statuses" "Kafka"

        platform.kafka -> platform.historyWriter "Consumes delivery status events" "Kafka"
        platform.historyWriter -> platform.historyDb "Writes delivery history" "JDBC"

        platform.prometheus -> platform.facade "Scrapes metrics" "HTTP / Actuator"
        platform.prometheus -> platform.templateRegistry "Scrapes metrics" "HTTP / Actuator"
        platform.prometheus -> platform.profileConsent "Scrapes metrics" "HTTP / Actuator"
        platform.prometheus -> platform.cancellationService "Scrapes metrics" "HTTP / Actuator"
        platform.prometheus -> platform.deliveryDispatcher "Scrapes metrics" "HTTP / Actuator"
        platform.prometheus -> platform.mailSender "Scrapes metrics" "HTTP / Actuator"
        platform.prometheus -> platform.smsSender "Scrapes metrics" "HTTP / Actuator"
        platform.prometheus -> platform.historyWriter "Scrapes metrics" "HTTP / Actuator"
        platform.grafana -> platform.prometheus "Reads metrics" "PromQL"
    }

    views {
        systemContext platform "SystemContextCurrent" "System context of the current notification platform implementation." {
            include *
            exclude platform.prometheus
            exclude platform.grafana
            autolayout lr
        }

        container platform "ContainersCurrent" "Simplified container overview of the current notification platform implementation." {
            include clientSystem
            include platform.facade
            include platform.kafka
            include platform.deliveryDispatcher
            include platform.mailSender
            include platform.smsSender
            include platform.historyWriter
            autolayout lr
        }

        container platform "ContainersDataOwnership" "Container architecture with explicit data ownership per service." {
            include clientSystem
            include platform.facade
            include platform.facadeDb
            include platform.templateRegistry
            include platform.templateMongo
            include platform.kafka
            include platform.deliveryDispatcher
            include platform.dispatcherDb
            include platform.profileConsent
            include platform.profileRedis
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

        container platform "ServicesPresentation" "High-level service architecture for presentation: processing services and Kafka only." {
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

        container platform "InboundCommandFlow" "Inbound Command Flow: client command processing, template rendering, state persistence and publication to Kafka." {
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

        container platform "KafkaDeliveryFlow" "Kafka Delivery Flow: producers and consumers around Kafka without storage and provider details." {
            include platform.facade
            include platform.kafka
            include platform.deliveryDispatcher
            include platform.mailSender
            include platform.smsSender
            include platform.historyWriter
            autolayout lr
        }

        container platform "EmailNotificationProcessingFlow" "Email Notification Processing Flow: Kafka command consumption, Redis dedup, cancellation check, provider call and status/fallback publication." {
            include platform.kafka
            include platform.mailSender
            include platform.mailDedupRedis
            include platform.cancellationService
            include platform.cancellationRedis
            include emailProvider
            autolayout lr
        }

        container platform "DispatchRoutingFlow" "Dispatch Routing Flow: dispatcher consumes Kafka, resolves profile constraints, stores scheduling state and publishes sender commands." {
            include platform.kafka
            include platform.deliveryDispatcher
            include platform.dispatcherDb
            include platform.profileConsent
            include platform.profileRedis
            autolayout lr
        }

        dynamic platform "CreateEventFlow" "CreateNotificationEvent and EventCreated flow." {
            clientSystem -> platform.facade "create event"
            platform.facade -> platform.templateRegistry "render preview"
            platform.facade -> platform.facadeDb "save event"
            platform.facade -> platform.kafka "publish event"
            autolayout lr
        }

        dynamic platform "DelayedMailDispatchFlow" "TriggerDispatch for delayed delivery routed through delivery-dispatcher." {
            clientSystem -> platform.facade "trigger dispatch"
            platform.facade -> platform.templateRegistry "read template"
            platform.facade -> platform.facadeDb "save dispatch"
            platform.facade -> platform.kafka "publish delivery.dispatcher"
            platform.kafka -> platform.deliveryDispatcher "consume dispatch request"
            platform.deliveryDispatcher -> platform.dispatcherDb "save delayed task"
            platform.deliveryDispatcher -> platform.kafka "publish mail command when due"
            platform.kafka -> platform.mailSender "consume command"
            autolayout lr
        }

        dynamic platform "DeliveryAndHistoryFlow" "Mail delivery, cancellation checks, fallback and history update." {
            platform.kafka -> platform.deliveryDispatcher "consume dispatch request"
            platform.deliveryDispatcher -> platform.profileConsent "resolve email destination"
            platform.deliveryDispatcher -> platform.kafka "publish mail command"
            platform.kafka -> platform.mailSender "consume command"
            platform.mailSender -> platform.cancellationService "check delivery allowed"
            platform.mailSender -> emailProvider "send email"
            platform.mailSender -> platform.kafka "publish status or delivery.fallback"
            platform.kafka -> platform.deliveryDispatcher "consume delivery.fallback"
            platform.deliveryDispatcher -> platform.kafka "publish sms command when fallback allowed"
            platform.kafka -> platform.historyWriter "consume status"
            platform.historyWriter -> platform.historyDb "write history"
            autolayout lr
        }

        dynamic platform "KafkaMessageProcessingFlow" "Kafka-driven message processing from dispatcher topic to sender execution and history update." {
            platform.kafka -> platform.deliveryDispatcher "consume delivery.dispatcher"
            platform.deliveryDispatcher -> platform.profileConsent "resolve recipient profile and channel consent"
            platform.profileConsent -> platform.profileRedis "read recipient profile"
            platform.deliveryDispatcher -> platform.kafka "publish channel command"
            platform.kafka -> platform.mailSender "consume mail command"
            platform.mailSender -> platform.mailDedupRedis "deduplicate command"
            platform.mailSender -> platform.cancellationService "check delivery allowed"
            platform.cancellationService -> platform.cancellationRedis "read cancellation mark"
            platform.mailSender -> emailProvider "send email"
            platform.mailSender -> platform.kafka "publish delivery status"
            platform.kafka -> platform.historyWriter "consume delivery status"
            platform.historyWriter -> platform.historyDb "write delivery history"
            autolayout lr
        }

        dynamic platform "ProcessingFlowPresentation" "Notification processing flow for presentation: Facade -> Dispatcher -> Sender -> History." {
            clientSystem -> platform.facade "create or trigger"
            platform.facade -> platform.kafka "publish delivery.dispatcher"
            platform.kafka -> platform.deliveryDispatcher "consume dispatch request"
            platform.deliveryDispatcher -> platform.kafka "publish sender command"
            platform.kafka -> platform.mailSender "consume command"
            platform.mailSender -> platform.kafka "publish status"
            platform.kafka -> platform.historyWriter "consume status"
            autolayout lr
        }

        dynamic platform "SchedulingFlowPresentation" "Delayed dispatch flow for presentation: Facade -> Kafka -> Delivery Dispatcher -> Sender -> History." {
            clientSystem -> platform.facade "trigger delayed"
            platform.facade -> platform.kafka "publish delivery.dispatcher"
            platform.kafka -> platform.deliveryDispatcher "consume request"
            platform.deliveryDispatcher -> platform.dispatcherDb "store delayed task"
            platform.deliveryDispatcher -> platform.kafka "publish sender command when due"
            platform.kafka -> platform.mailSender "consume due command"
            platform.mailSender -> platform.kafka "publish status"
            platform.kafka -> platform.historyWriter "consume status"
            autolayout lr
        }

        styles {
            element "Person" {
                shape person
                background "#08427b"
                color "#ffffff"
            }

            element "Software System" {
                shape roundedbox
                background "#1168bd"
                color "#ffffff"
            }

            element "Container" {
                shape roundedbox
                background "#438dd5"
                color "#ffffff"
            }

            element "Database" {
                shape cylinder
                background "#26a69a"
                color "#ffffff"
            }

            element "RedisStore" {
                background "#ffe082"
                color "#1b1b1b"
            }

            element "ClickHouseStore" {
                background "#1b5e20"
                color "#ffffff"
            }

            element "MongoStore" {
                background "#7ed957"
                color "#1b1b1b"
            }

            element "MessageBus" {
                shape pipe
                background "#f57c00"
                color "#ffffff"
            }

            element "External" {
                background "#999999"
                color "#ffffff"
            }
        }
    }
}
