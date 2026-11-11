package cl.barriodigital.ms_audit.entity;

import cl.barriodigital.ms_audit.dto.AuditEventResponse;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity @Immutable @Table(name="audit_event", schema="bd_audit")
public class AuditEvent {
    @Id @Column(name="event_id") private UUID id;
    @Column(name="request_id", nullable=false) private Long requestId;
    @Column(name="request_version", nullable=false) private Long requestVersion;
    @Column(name="event_type", nullable=false, length=40) private String eventType;
    @Column(length=250) private String actor;
    @Column(name="procedure_name", nullable=false, length=250) private String procedureName;
    @Column(name="previous_status", length=30) private String previousStatus;
    @Column(nullable=false, length=30) private String status;
    @Column(name="occurred_at", nullable=false) private Instant occurredAt;
    protected AuditEvent() { }
    public AuditEventResponse response() {
        String change = previousStatus == null ? "Creación: " + status : previousStatus + " → " + status;
        return new AuditEventResponse(id.toString(), actor, eventType, "REQUEST", requestId.toString(), occurredAt,
                procedureName + " · " + change + " · versión " + requestVersion);
    }
}
