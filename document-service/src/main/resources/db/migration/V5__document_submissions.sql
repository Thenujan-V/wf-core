-- One row per time a written document is sent for approval (written-documents spec §6): the text as it was sent,
-- the PDF the approvers read, the request it went to and how that ended.
CREATE TABLE document_submission (
    id            VARCHAR(36)  NOT NULL,
    document_id   VARCHAR(36)  NOT NULL,
    instance_id   VARCHAR(36)  NOT NULL,
    content_html  MEDIUMTEXT   NOT NULL,
    review_s3_key VARCHAR(255) NOT NULL,
    review_size   BIGINT       NOT NULL,
    review_sha256 CHAR(64)     NOT NULL,
    -- PENDING while the request runs, then APPROVED or NOT_APPROVED.
    status        VARCHAR(20)  NOT NULL,
    -- The request's end status as workflow-service reported it; NULL while pending and for a cancelled request.
    end_status    VARCHAR(100) NULL,
    submitted_by  VARCHAR(255) NOT NULL,
    submitted_at  DATETIME(6)  NOT NULL,
    decided_at    DATETIME(6)  NULL,
    -- The version the approved PDF became, once approval makes one.
    version_id    VARCHAR(36)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_submission_document FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE,
    CONSTRAINT uk_submission_instance UNIQUE (instance_id),
    INDEX ix_submission_document (document_id, submitted_at)
);
