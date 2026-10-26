-- Spec §8. All DATETIME columns hold UTC.

-- What a person sees in the app.
CREATE TABLE notification (
    id         VARCHAR(36)   NOT NULL,
    user_id    VARCHAR(255)  NOT NULL,
    kind       VARCHAR(40)   NOT NULL,
    title      VARCHAR(255)  NOT NULL,
    body       VARCHAR(1000) NOT NULL,
    link       VARCHAR(500)  NULL,
    created_at DATETIME(6)   NOT NULL,
    read_at    DATETIME(6)   NULL,
    PRIMARY KEY (id),
    INDEX ix_notification_user (user_id, created_at),
    INDEX ix_notification_unread (user_id, read_at)
);

-- A person's choice for one kind of notification; no row means the kind's defaults.
CREATE TABLE notification_preference (
    user_id VARCHAR(255) NOT NULL,
    kind    VARCHAR(40)  NOT NULL,
    in_app  BOOLEAN      NOT NULL,
    email   BOOLEAN      NOT NULL,
    PRIMARY KEY (user_id, kind)
);

-- Emails wait here until the mail server takes them; a failed one is tried again.
CREATE TABLE email_message (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    to_address VARCHAR(320) NOT NULL,
    subject    VARCHAR(255) NOT NULL,
    body       TEXT         NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    attempts   INT          NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NULL,
    -- Set while one instance is sending it, so another does not send it too.
    claimed_until DATETIME(6) NULL,
    sent_at    DATETIME(6)  NULL,
    PRIMARY KEY (id),
    INDEX ix_email_pending (sent_at, id)
);

-- Idempotent consumers (spec §2.2).
CREATE TABLE processed_message (
    message_id   VARCHAR(36)  NOT NULL,
    consumer     VARCHAR(100) NOT NULL,
    processed_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (message_id, consumer)
);
