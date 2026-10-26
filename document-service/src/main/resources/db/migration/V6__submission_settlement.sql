-- When a round whose request has not reported back was last checked against workflow-service, so the check is
-- not repeated on every pass (written-documents phase 3).
ALTER TABLE document_submission ADD COLUMN checked_at DATETIME(6) NULL;
