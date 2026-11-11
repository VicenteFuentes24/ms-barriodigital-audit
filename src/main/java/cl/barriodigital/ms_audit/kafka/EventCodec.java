package cl.barriodigital.ms_audit.kafka;

import cl.barriodigital.ms_audit.dto.RequestEventV1;
import cl.barriodigital.ms_audit.exception.InvalidEventException;
import jakarta.validation.Validator;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public class EventCodec {
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Validator validator;
    public EventCodec(Validator validator) { this.validator = validator; }
    public RequestEventV1 decode(byte[] payload, byte[] key) {
        RequestEventV1 event;
        try { event = mapper.readValue(utf8(payload), RequestEventV1.class); }
        catch (RuntimeException failure) { throw new InvalidEventException("INVALID_JSON_OR_CONTRACT"); }
        if (event == null || !validator.validate(event).isEmpty()) throw new InvalidEventException("INVALID_EVENT_FIELDS");
        if (!event.requestId().toString().equals(utf8(key))) throw new InvalidEventException("INVALID_REQUEST_KEY");
        return event;
    }
    private String utf8(byte[] bytes) {
        if (bytes == null) throw new InvalidEventException("NULL_RECORD");
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException failure) { throw new InvalidEventException("INVALID_UTF8"); }
    }
    public String encode(RequestEventV1 event) { return mapper.writeValueAsString(event); }
}
