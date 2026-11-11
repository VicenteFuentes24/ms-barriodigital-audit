package cl.barriodigital.ms_audit.dto;

import java.time.Instant;

/** Exact array element expected by frontend types/audit.ts. Actor is absent in v1. */
public record AuditEventResponse(String id, String user, String eventType, String entityType,
                                 String entityId, Instant occurredAt, String detail) { }
