-- One editable draft per form key; published versions are immutable (spec §5).
CREATE TABLE form_draft (
    form_key        VARCHAR(100) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    definition_json JSON         NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    PRIMARY KEY (form_key)
);

CREATE TABLE form_definition (
    id              VARCHAR(36)  NOT NULL,
    form_key        VARCHAR(100) NOT NULL,
    version         INT          NOT NULL,
    name            VARCHAR(255) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    definition_json JSON         NOT NULL,
    published_at    DATETIME(6)  NOT NULL,
    published_by    VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_form_key_version UNIQUE (form_key, version)
);

-- Each submission pins (form_key, form_version); context_ref links it to e.g. a workflow task.
CREATE TABLE form_submission (
    id           VARCHAR(36)  NOT NULL,
    form_key     VARCHAR(100) NOT NULL,
    form_version INT          NOT NULL,
    data_json    JSON         NOT NULL,
    submitted_by VARCHAR(255) NOT NULL,
    submitted_at DATETIME(6)  NOT NULL,
    context_ref  VARCHAR(255) NULL,
    PRIMARY KEY (id),
    INDEX ix_submission_context (context_ref),
    CONSTRAINT fk_submission_form FOREIGN KEY (form_key, form_version) REFERENCES form_definition (form_key, version)
);
