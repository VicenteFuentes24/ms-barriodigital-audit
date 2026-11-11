package cl.barriodigital.ms_audit.repository;

import cl.barriodigital.ms_audit.dto.RequestEventV1;
import cl.barriodigital.ms_audit.exception.InvalidEventException;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EventStore {
    private final JdbcTemplate jdbc;
    public EventStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean append(RequestEventV1 e, String payload, String topic, int partition, long offset) {
        int inserted = jdbc.update("""
            INSERT INTO bd_audit.audit_event(event_id,request_id,request_version,schema_version,event_type,
                procedure_id,procedure_name,previous_status,status,occurred_at,processed_at,payload,
                source_topic,source_partition,source_offset)
            VALUES (?,?,?,?,?,?,?,?,?,?,clock_timestamp(),CAST(? AS jsonb),?,?,?) ON CONFLICT DO NOTHING
            """, e.eventId(), e.requestId(), e.requestVersion(), e.schemaVersion(), e.eventType().name(),
                e.procedureId(), e.procedureName(), e.previousStatus() == null ? null : e.previousStatus().name(),
                e.status().name(), Timestamp.from(e.occurredAt()), payload, topic, partition, offset);
        if (inserted == 1) return true;
        // Conflict is only a duplicate when BOTH identity and complete canonical content match.
        Boolean same = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM bd_audit.audit_event WHERE event_id=? AND payload=CAST(? AS jsonb))
            """, Boolean.class, e.eventId(), payload);
        if (!Boolean.TRUE.equals(same)) throw new InvalidEventException("EVENT_ID_OR_VERSION_CONFLICT");
        return false;
    }
}
