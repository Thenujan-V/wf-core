-- One editable draft per process key; published versions are immutable (spec §4.1).
CREATE TABLE process_draft (
    process_key VARCHAR(100) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    graph_json  JSON         NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (process_key)
);

CREATE TABLE process_definition (
    id           VARCHAR(36)  NOT NULL,
    process_key  VARCHAR(100) NOT NULL,
    version      INT          NOT NULL,
    name         VARCHAR(255) NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    graph_json   JSON         NOT NULL,
    published_at DATETIME(6)  NOT NULL,
    published_by VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_definition_key_version UNIQUE (process_key, version)
);

CREATE TABLE process_instance (
    id              VARCHAR(36)  NOT NULL,
    definition_id   VARCHAR(36)  NOT NULL,
    process_key     VARCHAR(100) NOT NULL,
    process_version INT          NOT NULL,
    business_key    VARCHAR(255) NULL,
    status          VARCHAR(20)  NOT NULL,
    end_status      VARCHAR(100) NULL,
    variables_json  JSON         NOT NULL,
    initiator_id    VARCHAR(255) NOT NULL,
    started_at      DATETIME(6)  NOT NULL,
    ended_at        DATETIME(6)  NULL,
    version         INT          NOT NULL,
    PRIMARY KEY (id),
    INDEX ix_instance_initiator (initiator_id, started_at),
    CONSTRAINT fk_instance_definition FOREIGN KEY (definition_id) REFERENCES process_definition (id)
);

CREATE TABLE execution_token (
    id          VARCHAR(36)  NOT NULL,
    instance_id VARCHAR(36)  NOT NULL,
    node_id     VARCHAR(100) NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    scope_id    VARCHAR(36)  NULL,
    PRIMARY KEY (id),
    INDEX ix_token_instance (instance_id),
    CONSTRAINT fk_token_instance FOREIGN KEY (instance_id) REFERENCES process_instance (id) ON DELETE CASCADE
);

CREATE TABLE fork_scope (
    id              VARCHAR(36)  NOT NULL,
    instance_id     VARCHAR(36)  NOT NULL,
    parent_scope_id VARCHAR(36)  NULL,
    split_node_id   VARCHAR(100) NOT NULL,
    join_node_id    VARCHAR(100) NOT NULL,
    expected        INT          NOT NULL,
    arrived         INT          NOT NULL,
    PRIMARY KEY (id),
    INDEX ix_scope_instance (instance_id),
    CONSTRAINT fk_scope_instance FOREIGN KEY (instance_id) REFERENCES process_instance (id) ON DELETE CASCADE
);

CREATE TABLE task (
    id                VARCHAR(36)  NOT NULL,
    instance_id       VARCHAR(36)  NOT NULL,
    token_id          VARCHAR(36)  NOT NULL,
    activation_id     VARCHAR(36)  NOT NULL,
    node_id           VARCHAR(100) NOT NULL,
    name              VARCHAR(255) NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    assignee_id       VARCHAR(255) NULL,
    outcome           VARCHAR(100) NULL,
    completed_by      VARCHAR(255) NULL,
    unassigned_reason VARCHAR(500) NULL,
    created_at        DATETIME(6)  NOT NULL,
    due_at            DATETIME(6)  NULL,
    completed_at      DATETIME(6)  NULL,
    PRIMARY KEY (id),
    INDEX ix_task_assignee (assignee_id, status),
    INDEX ix_task_instance (instance_id),
    CONSTRAINT fk_task_instance FOREIGN KEY (instance_id) REFERENCES process_instance (id) ON DELETE CASCADE
);

-- "group:<id>" / "role:<name>" pools a claimable task is offered to.
CREATE TABLE task_candidate (
    task_id   VARCHAR(36)  NOT NULL,
    candidate VARCHAR(300) NOT NULL,
    PRIMARY KEY (task_id, candidate),
    INDEX ix_candidate (candidate),
    CONSTRAINT fk_candidate_task FOREIGN KEY (task_id) REFERENCES task (id) ON DELETE CASCADE
);

-- locked_by/locked_until is the executor lease (spec §4.4 "RUNNING").
CREATE TABLE job (
    id           VARCHAR(36)   NOT NULL,
    instance_id  VARCHAR(36)   NOT NULL,
    type         VARCHAR(20)   NOT NULL,
    token_id     VARCHAR(36)   NOT NULL,
    task_id      VARCHAR(36)   NULL,
    due_at       DATETIME(6)   NOT NULL,
    status       VARCHAR(20)   NOT NULL,
    attempts     INT           NOT NULL,
    last_error   VARCHAR(2000) NULL,
    locked_by    VARCHAR(100)  NULL,
    locked_until DATETIME(6)   NULL,
    PRIMARY KEY (id),
    INDEX ix_job_due (status, due_at),
    INDEX ix_job_instance (instance_id),
    CONSTRAINT fk_job_instance FOREIGN KEY (instance_id) REFERENCES process_instance (id) ON DELETE CASCADE
);

CREATE TABLE history_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    instance_id VARCHAR(36)  NOT NULL,
    event_type  VARCHAR(50)  NOT NULL,
    actor_id    VARCHAR(255) NULL,
    data_json   JSON         NOT NULL,
    occurred_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX ix_history_instance (instance_id, id),
    CONSTRAINT fk_history_instance FOREIGN KEY (instance_id) REFERENCES process_instance (id) ON DELETE CASCADE
);

-- Transactional outbox (spec §2.2): written with the business change, relayed to RabbitMQ afterwards.
CREATE TABLE outbox (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    message_id     VARCHAR(36)  NOT NULL,
    routing_key    VARCHAR(100) NOT NULL,
    payload_json   JSON         NOT NULL,
    correlation_id VARCHAR(64)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    published_at   DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_outbox_message UNIQUE (message_id),
    INDEX ix_outbox_pending (published_at, id)
);
