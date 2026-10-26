-- Spec §5 file fields: a file uploaded while a form is being filled in waits here, in a folder of its own, until the
-- form is submitted (it then moves to the request's folder) or for at most a day. Only its uploader reads it.
INSERT INTO folder (id, parent_id, name, inherit_acl, created_by, created_at)
VALUES ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000002', 'Staged uploads', FALSE, 'system',
        UTC_TIMESTAMP(6));
