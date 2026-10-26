-- Keycloak ids of LDAP/AD users that are not imported look like f:<componentId>:<externalId>
-- and exceed 36 characters. Widen every user-id column; MySQL needs the foreign keys dropped first.
ALTER TABLE app_user DROP FOREIGN KEY fk_app_user_manager;
ALTER TABLE user_group DROP FOREIGN KEY fk_user_group_user;
ALTER TABLE user_role DROP FOREIGN KEY fk_user_role_user;

ALTER TABLE app_user
    MODIFY id VARCHAR(255) NOT NULL,
    MODIFY manager_id VARCHAR(255) NULL;
ALTER TABLE user_group MODIFY user_id VARCHAR(255) NOT NULL;
ALTER TABLE user_role MODIFY user_id VARCHAR(255) NOT NULL;

ALTER TABLE app_user
    ADD CONSTRAINT fk_app_user_manager FOREIGN KEY (manager_id) REFERENCES app_user (id) ON DELETE SET NULL;
ALTER TABLE user_group
    ADD CONSTRAINT fk_user_group_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE;
ALTER TABLE user_role
    ADD CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE;
