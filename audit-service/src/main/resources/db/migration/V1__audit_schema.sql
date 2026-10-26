-- Spec §8: audit_entry (actor, action, entity type/id, data, correlation id, at). Append-only: the service's own
-- database user has SELECT and INSERT only. All DATETIME columns hold UTC.
CREATE TABLE audit_entry (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    message_id     VARCHAR(36)  NOT NULL,
    action         VARCHAR(100) NOT NULL,
    actor_id       VARCHAR(255) NULL,
    entity_type    VARCHAR(40)  NOT NULL,
    entity_id      VARCHAR(64)  NULL,
    data_json      JSON         NOT NULL,
    correlation_id VARCHAR(64)  NULL,
    occurred_at    DATETIME(6)  NOT NULL,
    recorded_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- A redelivered event is the same entry.
    CONSTRAINT uk_audit_message UNIQUE (message_id),
    INDEX ix_audit_time (occurred_at, id),
    INDEX ix_audit_actor (actor_id, occurred_at),
    INDEX ix_audit_entity (entity_type, entity_id, occurred_at)
);
