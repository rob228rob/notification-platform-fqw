-- 001_init_tables.sql
-- Liquibase formatted SQL file

-- Liquibase ChangeSet
-- Эта миграция предполагает создание следующих таблиц и объектов:
-- 0) users, roles, users_roles - таблички для управления ролями и разграничения прав
-- 1) dormitories: Список общежитий
-- 2) machine_types: Типы машин (стиральная/сушильная)
-- 3) machines: Конкретные машины, привязанные к общежитию
-- 4) time_slots: Временные слоты по дням недели
-- 5) reservations: Таблица бронирований машин пользователями с добавлением статуса
-- 6) reservation_logs: Логирование действий с бронированиями (после вставки или обновления статуса)
-- 7) Триггер и функция для логирования вставок в reservations
-- 8) Процедура для обновления статуса бронирования
-- 9) Инициализация начальных данных для общежитий, типов машин и временных слотов


-- changeset admin:001 createTable
CREATE TABLE IF NOT EXISTS users (
                                     id            UUID PRIMARY KEY,
                                     first_name    VARCHAR(255),
    last_name     VARCHAR(255),
    email         VARCHAR(255) NOT NULL UNIQUE,
    password      VARCHAR(255) NOT NULL,
    enabled       BOOLEAN   DEFAULT TRUE,
    token_expired BOOLEAN   DEFAULT FALSE,
    creation_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    modified_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

CREATE TABLE IF NOT EXISTS roles (
    id SERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS users_roles (
    user_id UUID NOT NULL,
    role_id INT  NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE
    );

-- общежития, каждая машинка привязывается к определнной общаге
CREATE TABLE IF NOT EXISTS dormitories (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL UNIQUE,
    address         VARCHAR(255),
    creation_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    modified_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- типы машинок, пока есть только DRY, WASHING
CREATE TABLE IF NOT EXISTS machine_types (
    id      SERIAL PRIMARY KEY,
    name    VARCHAR(50) NOT NULL UNIQUE
);

-- непосредственно машинки
CREATE TABLE IF NOT EXISTS machines (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dormitory_id    UUID NOT NULL REFERENCES dormitories(id) ON DELETE CASCADE,
    machine_type_id INT NOT NULL REFERENCES machine_types(id) ON DELETE RESTRICT,
    name            VARCHAR(255) NOT NULL,
    creation_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    modified_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (dormitory_id, machine_type_id, name)
);

CREATE TABLE IF NOT EXISTS reservations (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    machine_id    UUID NOT NULL REFERENCES machines(id) ON DELETE CASCADE,
    res_date      DATE NOT NULL,
    start_time    TIME NOT NULL,
    end_time      TIME NOT NULL,
    status        VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    creation_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    modified_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (machine_id, res_date, start_time, end_time)
    );

-- таблица логов действий на бронированиях
CREATE TABLE IF NOT EXISTS reservation_logs (
                                                id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id  UUID,
    action          TEXT NOT NULL,  -- Тип действия (INSERT, UPDATE, DELETE)
    old_data        JSONB,  -- Старые данные (для UPDATE и DELETE)
    new_data        JSONB,  -- Новые данные (для INSERT и UPDATE)
    action_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- Инициализация базовых типов машин
INSERT INTO machine_types (name) VALUES ('WASHER')
    ON CONFLICT (name) DO NOTHING;
INSERT INTO machine_types (name) VALUES ('DRYER')
    ON CONFLICT (name) DO NOTHING;

-- Инициализация общежитий
INSERT INTO dormitories (name, address) VALUES ('Морг', 'Улица Академика Павлова, д. 15')
    ON CONFLICT (name) DO NOTHING;
INSERT INTO dormitories (name, address) VALUES ('Башня', 'Проспект Мира, д. 10')
    ON CONFLICT (name) DO NOTHING;
INSERT INTO dormitories (name, address) VALUES ('Икар', 'Улица Студенческая, д. 25')
    ON CONFLICT (name) DO NOTHING;
INSERT INTO dormitories (name, address) VALUES ('Альфа', 'Переулок Технологов, д. 5')
    ON CONFLICT (name) DO NOTHING;

-- Процедура для записи в таблицу логов действий о бронированиях
CREATE OR REPLACE FUNCTION log_reservation_changes()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO reservation_logs (reservation_id, action, old_data, new_data, action_time)
        VALUES (NEW.id, 'INSERT', NULL, row_to_json(NEW), CURRENT_TIMESTAMP);
    ELSIF TG_OP = 'UPDATE' THEN
        INSERT INTO reservation_logs (reservation_id, action, old_data, new_data, action_time)
        VALUES (NEW.id, 'UPDATE', row_to_json(OLD), row_to_json(NEW), CURRENT_TIMESTAMP);
    ELSIF TG_OP = 'DELETE' THEN
        INSERT INTO reservation_logs (reservation_id, action, old_data, new_data, action_time)
        VALUES (OLD.id, 'DELETE', row_to_json(OLD), NULL, CURRENT_TIMESTAMP);
END IF;
RETURN NEW;
END;
$$ LANGUAGE plpgsql;


-- Триггер для вставок
CREATE TRIGGER tr_reservation_insert
    AFTER INSERT ON reservations
    FOR EACH ROW
    EXECUTE PROCEDURE log_reservation_changes();

-- Триггер для обновлений
CREATE TRIGGER tr_reservation_update
    AFTER UPDATE ON reservations
    FOR EACH ROW
    EXECUTE PROCEDURE log_reservation_changes();

-- Триггер для удалений
CREATE TRIGGER tr_reservation_delete
    AFTER DELETE ON reservations
    FOR EACH ROW
    EXECUTE PROCEDURE log_reservation_changes();


-- Процедура для обновления статуса бронирования
CREATE OR REPLACE PROCEDURE update_reservation_status(res_id UUID, new_status VARCHAR)
LANGUAGE plpgsql
AS $$
BEGIN
UPDATE reservations
SET status = new_status, modified_time = NOW()
WHERE id = res_id;

INSERT INTO reservation_logs (reservation_id, action)
VALUES (res_id, 'UPDATE_STATUS TO ' || new_status);

-- Если бронирование отменено, ранее мы обновляли machine_time_slots, теперь слотов нет в БД (раньше были :) )
END;
$$;

-- Вставка машин
WITH dorm_morg AS (
    SELECT id FROM dormitories WHERE name = 'Морг'
),
     dorm_bashnia AS (
         SELECT id FROM dormitories WHERE name = 'Башня'
     ),
     dorm_ikar AS (
         SELECT id FROM dormitories WHERE name = 'Икар'
     ),
     dorm_alfa AS (
         SELECT id FROM dormitories WHERE name = 'Альфа'
     )
INSERT INTO machines (dormitory_id, machine_type_id, name)
VALUES
    -- Общежитие "Морг"
    ((SELECT id FROM dorm_morg), 1, 'Стиральная машина 1 Морг'),
    ((SELECT id FROM dorm_morg), 2, 'Сушильная машина 2 Морг'),

    -- Общежитие "Башня"
    ((SELECT id FROM dorm_bashnia), 1, 'Стиральная машина 1 Башня'),
    ((SELECT id FROM dorm_bashnia), 2, 'Сушильная машина 2 Башня')

ON CONFLICT DO NOTHING;