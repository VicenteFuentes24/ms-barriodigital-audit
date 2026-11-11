package cl.barriodigital.ms_audit.dto;

import java.time.Instant;
import java.util.UUID;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record RequestEventV1(
        @NotNull UUID eventId, @NotNull EventType eventType,
        @NotNull @Min(1) @Max(1) Integer schemaVersion,
        @NotNull @Positive Long requestId, @NotNull @Positive Long requestVersion,
        @NotBlank @Size(max=100) String procedureId,
        @NotBlank @Size(max=250) String procedureName,
        RequestStatus previousStatus, @NotNull RequestStatus status, @NotNull Instant occurredAt) {
    public enum EventType { REQUEST_CREATED, REQUEST_STATUS_CHANGED }
    public enum RequestStatus { INGRESADO, ADMITIDO, EN_GESTION, EN_TERRENO, RESUELTO, RECHAZADO }
    @JsonIgnore @AssertTrue
    public boolean isConsistent() {
        if (eventType == null || requestVersion == null || status == null) return true;
        return eventType == EventType.REQUEST_CREATED
                ? requestVersion == 1 && previousStatus == null && status == RequestStatus.INGRESADO
                : requestVersion > 1 && previousStatus != null && previousStatus != status;
    }
}
