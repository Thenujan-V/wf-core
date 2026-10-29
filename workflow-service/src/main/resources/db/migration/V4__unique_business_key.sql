-- A business key names one request, so a retried or doubled start returns the existing instance instead of a
-- duplicate. MySQL allows any number of NULLs in a unique index: requests started without a key are unaffected.
-- Fails if existing rows already share a key; resolve those before migrating.
CREATE UNIQUE INDEX ux_instance_business_key ON process_instance (business_key);
DROP INDEX ix_instance_business_key ON process_instance;
