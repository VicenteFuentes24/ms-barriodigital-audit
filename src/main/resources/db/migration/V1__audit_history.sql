-- Only bd_audit. Flyway owns its own schema history. No changes to other schemas.
CREATE TABLE bd_audit.audit_event (
    event_id uuid PRIMARY KEY,
    request_id bigint NOT NULL CHECK(request_id > 0),
    request_version bigint NOT NULL CHECK(request_version > 0),
    schema_version integer NOT NULL CHECK(schema_version = 1),
    event_type varchar(40) NOT NULL CHECK(event_type IN ('REQUEST_CREATED','REQUEST_STATUS_CHANGED')),
    actor varchar(250), -- v1 carries no actor; never inferred from an HTTP reader.
    procedure_id varchar(100) NOT NULL,
    procedure_name varchar(250) NOT NULL,
    previous_status varchar(30),
    status varchar(30) NOT NULL CHECK(status IN ('INGRESADO','ADMITIDO','EN_GESTION','EN_TERRENO','RESUELTO','RECHAZADO')),
    occurred_at timestamptz NOT NULL,
    processed_at timestamptz NOT NULL,
    payload jsonb NOT NULL,
    source_topic varchar(249) NOT NULL,
    source_partition integer NOT NULL,
    source_offset bigint NOT NULL,
    UNIQUE(request_id,request_version),
    CHECK(length(trim(procedure_id)) > 0 AND length(trim(procedure_name)) > 0),
    CHECK(previous_status IS NULL OR previous_status IN ('INGRESADO','ADMITIDO','EN_GESTION','EN_TERRENO','RESUELTO','RECHAZADO')),
    CHECK((event_type='REQUEST_CREATED' AND request_version=1 AND previous_status IS NULL AND status='INGRESADO')
       OR (event_type='REQUEST_STATUS_CHANGED' AND request_version>1 AND previous_status IS NOT NULL AND previous_status<>status))
);
CREATE INDEX audit_event_date_idx ON bd_audit.audit_event(occurred_at DESC,event_id DESC);
CREATE INDEX audit_event_type_date_idx ON bd_audit.audit_event(event_type,occurred_at DESC,event_id DESC);
CREATE INDEX audit_event_actor_date_idx ON bd_audit.audit_event(lower(actor),occurred_at DESC) WHERE actor IS NOT NULL;
-- request_id/request_version already indexed by the unique constraint.
CREATE FUNCTION bd_audit.reject_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Audit history is append-only'; END;
$$;
CREATE TRIGGER audit_history_immutable BEFORE UPDATE OR DELETE ON bd_audit.audit_event
FOR EACH ROW EXECUTE FUNCTION bd_audit.reject_history_mutation();

CREATE TABLE bd_audit.failed_event (
    id bigserial PRIMARY KEY,
    source_topic varchar(249) NOT NULL,
    source_partition integer NOT NULL,
    source_offset bigint NOT NULL,
    record_key bytea,
    payload bytea,
    failure_code varchar(80) NOT NULL,
    failed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    recovery_attempts integer NOT NULL DEFAULT 0,
    last_attempt_at timestamptz,
    recovered_at timestamptz,
    UNIQUE(source_topic,source_partition,source_offset)
);
CREATE INDEX audit_failed_pending_idx ON bd_audit.failed_event(id) WHERE recovered_at IS NULL;
