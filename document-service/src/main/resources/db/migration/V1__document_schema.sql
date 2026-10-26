-- Full-text indexes without the default stopword list: with the ngram parser a stopword such as "in" would
-- otherwise remove every two-letter piece of a word that contains it (the "in" of "invoice").
SET SESSION innodb_ft_enable_stopword = OFF;

-- Folders form a tree; each folder inherits its parent's ACL unless inherit_acl is false (spec §6).
CREATE TABLE folder (
    id          VARCHAR(36)  NOT NULL,
    parent_id   VARCHAR(36)  NULL,
    name        VARCHAR(255) NOT NULL,
    inherit_acl BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by  VARCHAR(255) NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_folder_parent FOREIGN KEY (parent_id) REFERENCES folder (id),
    CONSTRAINT uk_folder_name UNIQUE (parent_id, name)
);

-- One permission per principal and folder; the highest one given.
CREATE TABLE folder_acl (
    folder_id      VARCHAR(36)  NOT NULL,
    principal_type VARCHAR(10)  NOT NULL,
    principal_id   VARCHAR(255) NOT NULL,
    permission     VARCHAR(10)  NOT NULL,
    PRIMARY KEY (folder_id, principal_type, principal_id),
    CONSTRAINT fk_acl_folder FOREIGN KEY (folder_id) REFERENCES folder (id) ON DELETE CASCADE
);

-- A type's metadata fields are a published form (key, version) in form-service.
CREATE TABLE document_type (
    id           VARCHAR(36)  NOT NULL,
    name         VARCHAR(255) NOT NULL,
    form_key     VARCHAR(100) NULL,
    form_version INT          NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_document_type_name UNIQUE (name)
);

CREATE TABLE document (
    id                 VARCHAR(36)  NOT NULL,
    folder_id          VARCHAR(36)  NOT NULL,
    name               VARCHAR(255) NOT NULL,
    type_id            VARCHAR(36)  NULL,
    metadata_json      JSON         NOT NULL,
    -- Name and metadata values, for search.
    search_text        TEXT         NOT NULL,
    current_version_id VARCHAR(36)  NULL,
    checked_out_by     VARCHAR(255) NULL,
    checked_out_at     DATETIME(6)  NULL,
    linked_instance_id VARCHAR(36)  NULL,
    created_by         VARCHAR(255) NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_document_folder FOREIGN KEY (folder_id) REFERENCES folder (id),
    CONSTRAINT fk_document_type FOREIGN KEY (type_id) REFERENCES document_type (id),
    CONSTRAINT uk_document_name UNIQUE (folder_id, name),
    FULLTEXT INDEX ft_document_search (search_text) WITH PARSER ngram
);

CREATE TABLE document_version (
    id          VARCHAR(36)   NOT NULL,
    document_id VARCHAR(36)   NOT NULL,
    major       INT           NOT NULL,
    minor       INT           NOT NULL,
    s3_key      VARCHAR(255)  NOT NULL,
    size        BIGINT        NOT NULL,
    mime        VARCHAR(255)  NOT NULL,
    sha256      CHAR(64)      NOT NULL,
    file_name   VARCHAR(255)  NOT NULL,
    created_by  VARCHAR(255)  NOT NULL,
    created_at  DATETIME(6)   NOT NULL,
    comment     VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_version_document FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE,
    CONSTRAINT uk_version_number UNIQUE (document_id, major, minor)
);

-- Text extracted by Apache Tika, at most 1,000,000 characters per version.
CREATE TABLE document_text (
    version_id   VARCHAR(36) NOT NULL,
    document_id  VARCHAR(36) NOT NULL,
    content      MEDIUMTEXT  NOT NULL,
    extracted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (version_id),
    CONSTRAINT fk_text_version FOREIGN KEY (version_id) REFERENCES document_version (id) ON DELETE CASCADE,
    FULLTEXT INDEX ft_document_text (content) WITH PARSER ngram
);

-- A presigned upload waiting for its confirm; either a new document (folder_id) or a new version (document_id).
CREATE TABLE upload (
    id            VARCHAR(36)   NOT NULL,
    s3_key        VARCHAR(255)  NOT NULL,
    file_name     VARCHAR(255)  NOT NULL,
    size          BIGINT        NOT NULL,
    sha256        CHAR(64)      NOT NULL,
    mime          VARCHAR(255)  NOT NULL,
    folder_id     VARCHAR(36)   NULL,
    document_id   VARCHAR(36)   NULL,
    name          VARCHAR(255)  NULL,
    type_id       VARCHAR(36)   NULL,
    metadata_json JSON          NULL,
    major_version BOOLEAN       NOT NULL DEFAULT FALSE,
    comment       VARCHAR(1000) NULL,
    created_by    VARCHAR(255)  NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    expires_at    DATETIME(6)   NOT NULL,
    confirmed_at  DATETIME(6)   NULL,
    PRIMARY KEY (id),
    INDEX ix_upload_created (confirmed_at, created_at)
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

-- Idempotent consumers (spec §2.2): a message id is handled once per consumer.
CREATE TABLE processed_message (
    message_id   VARCHAR(36)  NOT NULL,
    consumer     VARCHAR(100) NOT NULL,
    processed_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (message_id, consumer)
);

INSERT INTO folder (id, parent_id, name, inherit_acl, created_by, created_at)
VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'Library', TRUE, 'system', UTC_TIMESTAMP(6));
INSERT INTO folder_acl (folder_id, principal_type, principal_id, permission) VALUES
    ('00000000-0000-0000-0000-000000000001', 'ROLE', 'user', 'READ'),
    ('00000000-0000-0000-0000-000000000001', 'ROLE', 'document-admin', 'MANAGE');
