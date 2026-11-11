package cl.barriodigital.ms_audit;

import cl.barriodigital.ms_audit.dto.RequestEventV1;
import cl.barriodigital.ms_audit.dto.RequestEventV1.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import tools.jackson.databind.json.JsonMapper;

public final class TestEvents {
    public static final Instant TIME = Instant.parse("2026-11-10T12:00:00Z");
    public static RequestEventV1 created(long id) {
        return new RequestEventV1(UUID.randomUUID(), EventType.REQUEST_CREATED, 1, id, 1L,
                "P-1", "Permiso de circulación", null, RequestStatus.INGRESADO, TIME);
    }
    public static RequestEventV1 changed(long id, long version) {
        return new RequestEventV1(UUID.randomUUID(), EventType.REQUEST_STATUS_CHANGED, 1, id, version,
                "P-1", "Permiso de circulación", RequestStatus.INGRESADO, RequestStatus.ADMITIDO, TIME.plusSeconds(version));
    }
    public static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    public static byte[] json(RequestEventV1 event) { return new JsonMapper().writeValueAsBytes(event); }
    public static ConsumerRecord<byte[],byte[]> record(RequestEventV1 event, long offset) {
        return new ConsumerRecord<>("bd.requests.events.v1", 0, offset, bytes(event.requestId().toString()), json(event));
    }
}
