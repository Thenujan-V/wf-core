CREATE TABLE app_user (
    id             VARCHAR(36)  NOT NULL,
    username       VARCHAR(255) NOT NULL,
    email          VARCHAR(320) NULL,
    first_name     VARCHAR(255) NULL,
    last_name      VARCHAR(255) NULL,
    enabled        BOOLEAN      NOT NULL,
    department     VARCHAR(255) NULL,
    position_title VARCHAR(255) NULL,
    manager_id     VARCHAR(36)  NULL,
    synced_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- Not unique: a user deleted and re-created in Keycloak gets a new id with the same username.
    INDEX ix_app_user_username (username),
    CONSTRAINT fk_app_user_manager FOREIGN KEY (manager_id) REFERENCES app_user (id) ON DELETE SET NULL
);

CREATE TABLE app_group (
    id   VARCHAR(36)   NOT NULL,
    name VARCHAR(255)  NOT NULL,
    path VARCHAR(1024) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE user_group (
    user_id  VARCHAR(36) NOT NULL,
    group_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (user_id, group_id),
    INDEX ix_user_group_group (group_id),
    CONSTRAINT fk_user_group_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_group_group FOREIGN KEY (group_id) REFERENCES app_group (id) ON DELETE CASCADE
);

CREATE TABLE user_role (
    user_id   VARCHAR(36)  NOT NULL,
    role_name VARCHAR(255) NOT NULL,
    PRIMARY KEY (user_id, role_name),
    INDEX ix_user_role_role (role_name),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

-- Lease so that only one replica runs the full directory sync at a time.
CREATE TABLE sync_lease (
    name        VARCHAR(64) NOT NULL,
    owner       VARCHAR(64) NULL,
    lease_until DATETIME(6) NOT NULL,
    PRIMARY KEY (name)
);

INSERT INTO sync_lease (name, owner, lease_until) VALUES ('identity-full-sync', NULL, '1970-01-01 00:00:00');
