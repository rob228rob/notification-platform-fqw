workspace "Платформа мультиканальной доставки уведомлений" "C4-модель отказоустойчивой распределённой платформы гарантированной доставки уведомлений" {

    ////////////////////////////////////////////////////////
    // М О Д Е Л Ь
    ////////////////////////////////////////////////////////
    model {

        ////////////////////////////////////////////////////////
        // Л Ю Д И
        ////////////////////////////////////////////////////////
        user = person "Клиент" "Пользователь веб/мобильных сервисов компании, получает уведомления."
        // operator = person "Оператор" "Работает в бизнес-системе, настраивает кампании и анализирует доставку."

        ////////////////////////////////////////////////////////
        // В Н Е Ш Н И Е  С И С Т Е М Ы
        ////////////////////////////////////////////////////////

        crm = softwareSystem "CRM / Профиль и согласия" "Операционные функции: источник истины по профилю клиента, контактам и согласиям, инициатор транзакционных уведомлений." {
            tags "External"
        }

        decisionEngine = softwareSystem "Система персонализации" "Платформа персонализации, выбирающая аудиторию, канал и параметры для рекалмных доставок" {
            tags "External"
        }

        apiGateway = softwareSystem "API Gateway (Infra)" "Инфраструктурный API Gateway. Терминирует внешний трафик и маршрутизирует запросы в сервисы платформы." {
            tags "External"
        }

        // monitoring = softwareSystem "Мониторинг и логирование" "Системы сбора метрик, логов и алёртов по платформе уведомлений." {
         //   tags "External"
        //}

       // smsProvider = softwareSystem "SMS-провайдер" "Внешний SMS-агрегатор: принимает SMS и возвращает статусы доставки и входящие сообщения" {
       //     tags "External"
       // }

        emailProvider = softwareSystem "Провайдер канала связи" "Выбранный провайдер: отправляет уведомления и возвращает статусы доставки" {
            tags "External"
        }

        ////////////////////////////////////////////////////////
        // О С Н О В Н А Я  С И С Т Е М А
        ////////////////////////////////////////////////////////

        platform = softwareSystem "Платформа мультиканальной доставки уведомлений" "Отказоустойчивая распределённая платформа гарантированной доставки уведомлени" {

            // Периметр / входной слой
            // Оркестрация и планирование
            orchestrator = container "Notification Facade" "Основная бизнес-логика: валидация команд, идемпотентность, приоритизация, выбор каналов и постановка задач в очереди без работы с сырыми контактами." "Java, Spring Boot, Kubernetes"
            scheduler = container "Scheduler Delivery" "Обработка отложенных и периодических отправок, запуск уведомлений по расписанию и с учётом окон «не беспокоить»." "Java, Spring Boot, Kubernetes"
            schedulerDeliveryDb = container "Scheduler Delivery DB" "Хранилище расписаний, триггеров и состояния отложенных/периодических отправок." "PostgreSQL" {
                tags "Database"
            }

            // Конфигурация платформы
            platformConfigDb = container "Platform Config DB" "Хранение конфигурации платформы: зарегистрированные системы-инициаторы, доступные каналы, лимиты, политики ретраев, глобальные настройки." "PostgreSQL" {
                tags "Database"
            }
            templateRegistry = container "Template Registry" "Сервис хранения и выдачи шаблонов уведомлений для каналов доставки." "Java, Spring Boot, Kubernetes"
            templateDb = container "Template DB" "База шаблонов уведомлений, версий шаблонов и параметров рендеринга." "PostgreSQL" {
                tags "Database"
            }

            // Контакты и кэш (TEMP disabled)
            // contactCache = container "Contact Cache Service" "Локальная витрина контактных данных и флагов согласий (id, email, phone, consent-flags), использующая ContactDb и Redis." "Java, Spring Boot, Kubernetes"

            // contactDb = container "Contact DB" "Локальная витрина контактов и согласий, реплицируемая из CRM (ETL/CDC)." "PostgreSQL" {
            //     tags "Database"
            // }

            // redisCache = container "Redis Cache" "Кэш горячих данных профиля и согласий, загружаемых из CRM и используемых ContactCache и воркерами для быстрой проверки." "Redis"

            // Очереди / события
            kafka = container "Apache Kafka" "Брокер сообщений: очереди команд на отправку по каналам (sms-out/email-out) и топик результатов доставки (delivery-results), а также DLQ." "Apache Kafka"

            // Канальные воркеры (диспатчеры)
           //  smsDispatcher = container "SMS Channel Dispatcher" "Консьюмер SMS-очередей: читает задачи, через ContactCache проверяет контакты и согласия, отправляет SMS в провайдера, публикует результаты доставки в Kafka." "Java, Spring Boot, Kubernetes"
            emailSender = container "Channel Sender" "Канальный воркер e-mail: читает задачи из Kafka, отправляет уведомления провайдеру и публикует статусы доставки." "Java, Spring Boot, Kubernetes"
            channelSenderDb = container "Channel Sender DB" "Хранилище состояния канального отправителя: шаблоны, технические статусы попыток и служебные записи отправки." "PostgreSQL" {
                tags "Database"
            }

            // Callback-обработчики
            callbackHandlers = container "Callback Handlers" "Приём callback-ов от провайдеров, публикация событий о доставке/отписках в Kafka и, при необходимости, вызовы CRM." "Java, Spring Boot, Kubernetes"

            // История доставок
            deliveryLogWriter = container "Delivery History Writer" "Сервис истории доставок: подписан на Kafka, агрегирует результаты по каналам и пишет их в DeliveryLogDb, предоставляет данные для админки/отчётности." "Java, Spring Boot, Kubernetes"

            deliveryLogDb = container "History Log DB" "Хранение истории уведомлений и их статусов по каналам" "PostgreSQL" {
                tags "Database"
            }

            // Админка
            //adminUi = container "Admin UI" "Веб-интерфейс операторов для просмотра истории уведомлений, DLQ, метрик и базовой конфигурации платформы." "Web SPA (React/Vue)"
        }

        ////////////////////////////////////////////////////////
        // С В Я З И  (К О Н Т Е К С Т)
        ////////////////////////////////////////////////////////ф

        // Люди <-> внешние системы
       // operator -> crm "Работает с клиентами, настройкой кампаний и анализом откликов" "Web UI"
        // Клиент получает уведомления через провайдеров (ниже)
        user -> platform "Отзывает согласие на обработку данных"
        user -> emailProvider "Отписывается от канала связи"
        // Внешние системы <-> платформа (на уровне систем)
        crm -> platform "Отправляет транзакционные уведомления" "GRPC/PROTO"
        decisionEngine -> platform "Отправляет маркетинговые уведомления" "GRPC/PROTO или Kafka"

       // platform -> crm "Использует CRM как источник истины по профилю и согласиям" "ETL / REST / события"
       //  platform -> monitoring "Публикует метрики и логи по работе платформы" "metrics/logs"

       // platform -> smsProvider "Отправляет SMS" "HTTPS API"
        // smsProvider -> platform "Возвращает статусы доставки и входящие сообщения" "HTTPS callbacks"

        platform -> emailProvider "Отправляет уведомление по выбранному каналу связи" "HTTPS/SMTP"
        emailProvider -> platform "Возвращает статусы доставки" "HTTPS callbacks"

        // smsProvider -> user "Доставляет SMS"
        // emailProvider -> user "Доставляет уведомления"

        // Оператор и админка
       // operator -> adminUi "Просматривает историю уведомлений и конфигурацию" "HTTPS"
       // operator -> apiGateway "Просматривает историю уведомлений и конфигурацию" "HTTPS"
        ////////////////////////////////////////////////////////
        // В Н У Т Р Е Н Н И Е  С В Я З И  П Л А Т Ф О Р М Ы
        ////////////////////////////////////////////////////////

        // Вход в платформу по API
        crm -> apiGateway "Создаёт транзакционные/маркетинговые уведомления" "GRPC/PROTO"
        decisionEngine -> apiGateway "Создаёт маркетинговые уведомления" "GRPC/PROTO"

        // Админка -> платформа
        //adminUi -> apiGateway "Запрашивает историю, DLQ, конфигурацию" "GRPC/PROTO"

        // API Gateway к внутренним сервисам
        apiGateway -> orchestrator "Передаёт команды на доставку уведомлений (без контактов)" "GRPC/PROTO"
       // apiGateway -> scheduler "Регистрирует отложенные и периодические уведомления" "GRPC/PROTO"
       kafka -> scheduler "Получает задачи на отложенные и плановые сообщения"
       scheduler -> kafka "Инициирует отправку в соответствии с расписанием"
       scheduler -> schedulerDeliveryDb "Читает/пишет расписания и состояние задач" "JDBC"

       // apiGateway -> deliveryLogWriter "Запрашивает историю доставок для отображения в админке" "GRPC/PROTO"

        // Планирование
        // scheduler -> orchestrator "Активирует отложенные/плановые уведомления" "GRPC/PROTO"

        // Оркестратор: бизнес-решения без ПДн
        orchestrator -> platformConfigDb "Читает настройки платформы, лимиты и политики" "JDBC"
        orchestrator -> templateRegistry "Запрашивает шаблоны уведомлений для формирования отправки" "GRPC/PROTO"
        orchestrator -> kafka "Публикует задачи на отправку по каналам (sms-out/email-out), без контактов и ПДн" "Kafka producers"
        templateRegistry -> templateDb "Читает/пишет шаблоны и версии" "JDBC"

        // Локальная витрина контактов и согласий (TEMP disabled)
        // contactCache -> contactDb "Читает/обновляет витрину контактов и согласий" "JDBC"
        // contactCache -> redisCache "Использует кэш контактов и согласий для быстрых запросов" "GET/SET"

        // Репликация из CRM в витрину и Redis (TEMP disabled)
        // crm -> contactDb "Реплицирует контактные данные и согласия (ETL)" "Streaming"
        // crm -> redisCache "Обновляет кэш контактов и согласий" "Streaming"

        // Очереди каналов
      //  kafka -> smsDispatcher "Поставляет задачи SMS (sms-out)" "Kafka consumers"
        kafka -> emailSender "Поставляет задачи на отправку email" "Kafka consumers"

        // Воркеры: контакты, согласия, отправка, результаты
      //  smsDispatcher -> contactCache "Получает контактные данные и флаги согласий для SMS" "GRPC/PROTO"
        // emailSender -> contactCache "Получает контактные данные и флаги согласий" "GRPC/PROTO"
        emailSender -> channelSenderDb "Читает/пишет служебные данные отправки" "JDBC"

      //  smsDispatcher -> smsProvider "Отправляет SMS" "HTTPS API"
        emailSender -> emailProvider "Отправляет уведомление провайдеру" "HTTPS/SMTP"
        emailProvider -> user "Доставляет уведомление конечному пользователю"
       // user -> emailProvider "Отказы от рассылок и отзывы согласий"

        // Результаты доставки от воркеров → Kafka
        // smsDispatcher -> kafka "Публикует результаты попыток доставки SMS" "Kafka producers (delivery-results)"
      //  emailSender -> kafka "Публикует результаты попыток доставки выбранного канала связи" "Kafka producers (delivery-results)"

        // Callback-обработчики: асинхронные статусы и отписки
       // smsProvider -> callbackHandlers "Присылает статусы доставки и входящие сообщения (STOP)" "HTTPS callbacks"
        emailProvider -> callbackHandlers "Присылает статусы доставки и отписки" "HTTPS callbacks"

        callbackHandlers -> kafka "Публикует события о финальных статусах доставки и отписках" "Kafka producers"
        callbackHandlers -> crm "Фиксирует изменения согласий в CRM" "GRPC/PROTO"

        kafka -> deliveryLogWriter "Поставляет результаты доставок и события отписок" "Kafka consumers"
        deliveryLogWriter -> deliveryLogDb "Пишет историю уведомлений и статусы по каналам" "JDBC"

        // История может по необходимости агрегироваться обратно в CRM
        deliveryLogWriter -> crm "Передаёт агрегированные статусы и отчёты по кампании" "GRPC/PROTO"

        // Метрики и логи
       // apiGateway      -> monitoring "Публикует метрики и логи" "metrics/logs"
    //    orchestrator    -> monitoring "Публикует метрики и логи" "metrics/logs"
      //  scheduler       -> monitoring "Метрики задач по расписанию" "metrics/logs"
        //smsDispatcher   -> monitoring "Метрики канала SMS" "metrics/logs"
        //emailSender -> monitoring "Метрики канала e-mail" "metrics/logs"
        //deliveryLogWriter -> monitoring "Метрики истории доставок" "metrics/logs"
    }

    ////////////////////////////////////////////////////////
    // П Р Е Д С Т А В Л Е Н И Я (VIEWS)
    ////////////////////////////////////////////////////////
    views {

        // Контекст системы
        systemContext platform "SystemContext" "Контекст платформы мультиканальной доставки уведомлений." {
            include *
            autolayout lr
        }

        // Контейнеры внутри платформы
        container platform "Containers" "Контейнерная диаграмма платформы." {
            include *
            autolayout lr
        }

        // Блок 1: Вход и оркестрация
        container platform "Containers-Block-1-Entry-Orchestration" "API, оркестрация и планирование." {
            include apiGateway
            include orchestrator
            include scheduler
            include schedulerDeliveryDb
            include kafka
            include crm
            include decisionEngine
            autolayout lr
        }

        // Блок 2: Шаблоны и конфигурация
        container platform "Containers-Block-2-Templates-Config" "Хранение и выдача шаблонов + конфигурация платформы." {
            include orchestrator
            include templateRegistry
            include templateDb
            include platformConfigDb
            autolayout lr
        }

        // Блок 3: Канальная отправка
        container platform "Containers-Block-3-Channel-Sender" "Отправка уведомлений и канал связи." {
            include kafka
            include emailSender
            include channelSenderDb
            include emailProvider
            include user
            autolayout lr
        }

        // Блок 4: Callback и история
        container platform "Containers-Block-4-Callbacks-History" "Обработка callback, история доставок и обратная запись в CRM." {
            include callbackHandlers
            include deliveryLogWriter
            include deliveryLogDb
            include kafka
            include crm
            include emailProvider
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
                shape hexagon
                background "#438dd5"
                color "#ffffff"
            }

            element "Database" {
                shape cylinder
            }

            element "External" {
                background "#999999"
                color "#ffffff"
                border dashed
            }
        }
    }
}
