-- The monitor looks requests up by reference and by process; without these every search scanned the table.
CREATE INDEX ix_instance_business_key ON process_instance (business_key);
CREATE INDEX ix_instance_process_started ON process_instance (process_key, started_at);

-- The dashboard counts per process what started and ended in a period. Indexes that also hold the columns it reads
-- answer those questions without touching the rows (which carry each request's variables).
CREATE INDEX ix_instance_started_process ON process_instance (started_at, process_key);
CREATE INDEX ix_instance_ended_cover ON process_instance (ended_at, status, process_key, started_at);
DROP INDEX ix_instance_started ON process_instance;
DROP INDEX ix_instance_ended ON process_instance;
