-- Written documents (written-documents spec §3, §6): a document is an uploaded FILE or one WRITTEN in the app.
ALTER TABLE document ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'FILE';

-- The text being worked on: one row per written document, overwritten on every save. revision goes up by one each
-- time, so a save made against an older text is refused.
CREATE TABLE document_draft (
    document_id  VARCHAR(36)  NOT NULL,
    content_html MEDIUMTEXT   NOT NULL,
    revision     INT          NOT NULL,
    updated_by   VARCHAR(255) NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (document_id),
    CONSTRAINT fk_draft_document FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE
);
