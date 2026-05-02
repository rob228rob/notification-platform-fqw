workspace "Notification Platform - Current Implementation" "Actual architecture of the implemented notification platform" {

    !identifiers hierarchical

    model {
        clientSystem = softwareSystem "Client Information System" "External system that creates notification events and triggers dispatches." {
            tags "External"
        }

        emailProvider = softwareSystem "Email Provider" "External provider or SMTP server used by the mail sender." {
            tags "External"
        }

        smsProvider = softwareSystem "SMS Provider" "External SMS gateway or provider API used by the SMS sender." {
            tags "External"
        }

        platform = softwareSystem "Notification Platform" "Distributed event-driven platform for multi-channel notification delivery." {
            facade = container "Notification Facade" "Accepts gRPC commands, manages notification events and dispatches, and publishes outbox messages." "Java, Spring Boot, gRPC"
            templateRegistry = container "Template Registry" "Stores template versions and renders subject/body for a chosen channel." "Java, Spring Boot, gRPC"
            profileConsent = container "Profile Consent" "Provides recipient channel permissions, destinations and preferred channel." "Java, Spring Boot, gRPC"
            schedulerDelivery = container "Scheduler Delivery" "Stores delayed mail dispatches and republishes due tasks to the working topic." "Java, Spring Boot, Kafka"
            mailSender = container "Notification Mail Sender" "Consumes notification events and mail dispatches, plans deliveries, sends e-mail and publishes statuses." "Java, Spring Boot, Kafka"
            smsSender = container "Notification SMS Sender" "Consumes notification events and SMS dispatches, plans deliveries, sends SMS and publishes statuses." "Java, Spring Boot, Kafka"
            historyWriter = container "History Writer" "Consumes delivery statuses and provides gRPC access to delivery history and summaries." "Java, Spring Boot, gRPC, Kafka"

            facadeDb = container "Facade PostgreSQL" "Stores notification events, audience, dispatches and outbox records owned by the facade." "PostgreSQL" {
                tags "Database"
            }

            schedulerDb = container "Scheduler PostgreSQL" "Stores delayed publication tasks and scheduler state." "PostgreSQL" {
                tags "Database"
            }

            mailDb = container "Mail PostgreSQL" "Stores mail deliveries, attempts, inbox records and sender-side status data." "PostgreSQL" {
                tags "Database"
            }

            smsDb = container "SMS PostgreSQL" "Stores SMS deliveries, attempts, inbox records and sender-side status data." "PostgreSQL" {
                tags "Database"
            }

            historyDb = container "History PostgreSQL" "Stores delivery status history and summary read models." "PostgreSQL" {
                tags "Database"
            }

            templateMongo = container "MongoDB Templates" "Stores templates, versions, channel contents and activeVersion." "MongoDB" {
                tags "Database"
            }

            redis = container "Redis Recipient Profiles" "Recipient profile storage used only by the Profile Consent service." "Redis" {
                tags "Database"
            }

            kafka = container "Kafka" "Asynchronous transport for notification events, dispatch commands and delivery statuses." "Apache Kafka" {
                tags "MessageBus"
            }

            prometheus = container "Prometheus" "Collects service metrics from Actuator and Micrometer endpoints." "Prometheus"
            grafana = container "Grafana" "Visualizes system quality and observability metrics." "Grafana"
        }

        clientSystem -> platform.facade "Creates notification events, sets audience, triggers dispatch" "gRPC"

        platform.facade -> platform.templateRegistry "Renders template preview when inline content is absent" "gRPC"
        platform.facade -> platform.facadeDb "Reads and writes notification_event, audience, dispatch and outbox tables" "JDBC"
        platform.facade -> platform.kafka "Publishes EventCreated, MailDispatchRequested and SmsDispatchRequested via outbox relay" "Kafka"

        platform.templateRegistry -> platform.templateMongo "Reads and writes template documents and versions" "MongoDB"

        platform.profileConsent -> platform.redis "Reads recipient profiles" "Redis"

        platform.schedulerDelivery -> platform.kafka "Consumes delayed mail dispatches and republishes due ones" "Kafka"
        platform.schedulerDelivery -> platform.schedulerDb "Reads and writes scheduled_delivery_task records" "JDBC"

        platform.mailSender -> platform.kafka "Consumes notification events and mail dispatches, publishes mail statuses" "Kafka"
        platform.mailSender -> platform.profileConsent "Checks email channel and destination" "gRPC"
        platform.mailSender -> platform.historyWriter "Reads recipient delivery summary" "gRPC"
        platform.mailSender -> platform.mailDb "Reads and writes mail_delivery, attempts and inbox tables" "JDBC"
        platform.mailSender -> emailProvider "Sends e-mail messages" "SMTP / JavaMail"

        platform.smsSender -> platform.kafka "Consumes notification events and SMS dispatches, publishes SMS statuses" "Kafka"
        platform.smsSender -> platform.profileConsent "Checks SMS channel and destination" "gRPC"
        platform.smsSender -> platform.historyWriter "Reads recipient delivery summary" "gRPC"
        platform.smsSender -> platform.smsDb "Reads and writes sms_delivery, attempts and inbox tables" "JDBC"
        platform.smsSender -> smsProvider "Sends SMS messages" "Provider API / adapter"

        platform.historyWriter -> platform.kafka "Consumes mail and SMS delivery statuses" "Kafka"
        platform.historyWriter -> platform.historyDb "Reads and writes delivery history and summary tables" "JDBC"

        platform.prometheus -> platform.facade "Scrapes metrics" "HTTP / Actuator"
        platform.prometheus -> platform.templateRegistry "Scrapes metrics" "HTTP / Actuator"
        platform.prometheus -> platform.profileConsent "Scrapes metrics" "HTTP / Actuator"
        platform.prometheus -> platform.schedulerDelivery "Scrapes metrics" "HTTP / Actuator"
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

        container platform "ContainersCurrent" "Container view of the current notification platform implementation." {
            include *
            exclude platform.prometheus
            exclude platform.grafana
            autolayout lr
        }

        container platform "ContainersDataOwnership" "Container architecture with explicit data ownership per service." {
            include *
            exclude platform.prometheus
            exclude platform.grafana
            autolayout lr
        }

        container platform "ServicesPresentation" "High-level service architecture for presentation: processing services and Kafka only." {
            include platform.facade
            include platform.templateRegistry
            include platform.profileConsent
            include platform.schedulerDelivery
            include platform.mailSender
            include platform.smsSender
            include platform.historyWriter
            include platform.kafka
            autolayout lr
        }

        container platform "IngressContourPresentation" "Input contour: facade, template service, owned storages and Kafka." {
            include clientSystem
            include platform.facade
            include platform.facadeDb
            include platform.templateRegistry
            include platform.templateMongo
            include platform.kafka
            autolayout lr
        }

        container platform "DeliveryHandlersPresentation" "Notification handlers contour: Kafka, senders, owned storages, profile consent, history writer and external providers." {
            include platform.kafka
            include platform.mailSender
            include platform.mailDb
            include platform.smsSender
            include platform.smsDb
            include platform.profileConsent
            include platform.redis
            include platform.historyWriter
            include platform.historyDb
            include emailProvider
            include smsProvider
            autolayout lr
        }

        dynamic platform "CreateEventFlow" "CreateNotificationEvent and EventCreated flow." {
            clientSystem -> platform.facade "CreateNotificationEvent"
            platform.facade -> platform.templateRegistry "RenderPreview (optional)"
            platform.facade -> platform.facadeDb "Insert notification_event, audience and outbox"
            platform.facade -> platform.kafka "Publish EventCreated"
            platform.kafka -> platform.mailSender "Consume EventCreated"
            platform.kafka -> platform.smsSender "Consume EventCreated"
            autolayout lr
        }

        dynamic platform "DelayedMailDispatchFlow" "TriggerDispatch for delayed mail delivery." {
            clientSystem -> platform.facade "TriggerDispatch"
            platform.facade -> platform.facadeDb "Insert dispatch and dispatch_target"
            platform.facade -> platform.kafka "Publish MailDispatchRequested to scheduled topic"
            platform.kafka -> platform.schedulerDelivery "Consume delayed mail dispatch"
            platform.schedulerDelivery -> platform.schedulerDb "Persist scheduled_delivery_task"
            platform.schedulerDelivery -> platform.kafka "Republish due task to notification.mail.dispatches"
            platform.kafka -> platform.mailSender "Consume MailDispatchRequested"
            autolayout lr
        }

        dynamic platform "DeliveryAndHistoryFlow" "Mail delivery, policy checks and history update." {
            platform.kafka -> platform.mailSender "Consume MailDispatchRequested"
            platform.mailSender -> platform.mailDb "Persist / lock delivery state"
            platform.mailSender -> platform.profileConsent "CheckRecipientChannel(EMAIL)"
            platform.mailSender -> platform.historyWriter "GetRecipientDeliverySummary"
            platform.mailSender -> emailProvider "Send e-mail"
            platform.mailSender -> platform.kafka "Publish MailDeliveryStatusChanged"
            platform.kafka -> platform.historyWriter "Consume delivery status"
            platform.historyWriter -> platform.historyDb "Persist delivery_history"
            autolayout lr
        }

        dynamic platform "ProcessingFlowPresentation" "Notification processing flow for presentation: Facade -> Kafka -> Sender -> History." {
            clientSystem -> platform.facade "CreateNotificationEvent / TriggerDispatch"
            platform.facade -> platform.kafka "Publish notification event"
            platform.kafka -> platform.mailSender "Consume event/dispatch"
            platform.mailSender -> platform.kafka "Publish delivery status"
            platform.kafka -> platform.historyWriter "Consume delivery status"
            autolayout lr
        }

        dynamic platform "SchedulingFlowPresentation" "Delayed dispatch flow for presentation: Facade -> Kafka -> Scheduler -> Kafka -> Sender -> History." {
            clientSystem -> platform.facade "TriggerDispatch (delayed)"
            platform.facade -> platform.kafka "Publish MailDispatchRequested (scheduled)"
            platform.kafka -> platform.schedulerDelivery "Consume scheduled dispatch"
            platform.schedulerDelivery -> platform.kafka "Republish due dispatch"
            platform.kafka -> platform.mailSender "Consume due dispatch"
            platform.mailSender -> platform.kafka "Publish delivery status"
            platform.kafka -> platform.historyWriter "Consume delivery status"
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
                background "#2e7d32"
                color "#ffffff"
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
