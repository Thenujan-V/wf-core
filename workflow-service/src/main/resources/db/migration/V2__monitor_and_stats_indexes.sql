-- The admin monitor lists every request newest first and by status; the dashboard counts what started and ended
-- in a period and the SLA breaches recorded in it (spec §9).
CREATE INDEX ix_instance_started ON process_instance (started_at);
CREATE INDEX ix_instance_status_started ON process_instance (status, started_at);
CREATE INDEX ix_instance_ended ON process_instance (ended_at);
CREATE INDEX ix_history_type_time ON history_event (event_type, occurred_at);
