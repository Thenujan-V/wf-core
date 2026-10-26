-- Spec §6: request attachments live under /Processes/<processKey>/<instanceId>. The folder has no ACL and does not
-- inherit, so only library admins see it in the library; the request decides who reads its documents.
-- A library that already has a root folder of that name (in any letter case) keeps it, renamed.
UPDATE folder SET name = CONCAT(name, ' (library)')
WHERE parent_id = '00000000-0000-0000-0000-000000000001' AND name = 'Processes';

INSERT INTO folder (id, parent_id, name, inherit_acl, created_by, created_at)
VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'Processes', FALSE, 'system',
        UTC_TIMESTAMP(6));

-- An upload that becomes an attachment of a request.
ALTER TABLE upload ADD COLUMN instance_id VARCHAR(36) NULL;
