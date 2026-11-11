package cl.barriodigital.ms_audit;

import cl.barriodigital.ms_audit.kafka.EventCodec;
import cl.barriodigital.ms_audit.exception.InvalidEventException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static cl.barriodigital.ms_audit.TestEvents.*;

class EventCodecTest {
    static final jakarta.validation.ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    final EventCodec codec = new EventCodec(FACTORY.getValidator());
    @AfterAll static void close() { FACTORY.close(); }
    @Test void createdContract() {
        var event = created(1);
        assertThat(codec.decode(json(event), bytes("1"))).isEqualTo(event);
        assertThat(codec.encode(event)).doesNotContain("consistent", "actor", "user");
    }
    @Test void changedContract() {
        var event = changed(1, 2);
        assertThat(codec.decode(json(event), bytes("1"))).isEqualTo(event);
    }
    @ParameterizedTest @ValueSource(strings={"{", "null", "[]", "{}", "{\"schemaVersion\":2}"})
    void invalidJsonOrMissingFields(String json) {
        assertThatThrownBy(() -> codec.decode(bytes(json), bytes("1"))).isInstanceOf(InvalidEventException.class);
    }
    @Test void rejectsFutureVersion() { reject(codec.encode(created(1)).replace("\"schemaVersion\":1", "\"schemaVersion\":2")); }
    @Test void rejectsUnknownFields() { reject(codec.encode(created(1)).replaceFirst("\\{", "{\"actor\":\"not-in-contract\",")); }
    @Test void rejectsInvalidCreationVersion() { reject(codec.encode(created(1)).replace("\"requestVersion\":1", "\"requestVersion\":2")); }
    @Test void rejectsUnchangedStatus() { reject(codec.encode(changed(1,2)).replace("ADMITIDO", "INGRESADO")); }
    @Test void rejectsNonpositiveRequest() { reject(codec.encode(created(1)).replace("\"requestId\":1", "\"requestId\":0")); }
    @Test void rejectsTrailingContent() { reject(codec.encode(created(1)) + " {}"); }
    @Test void rejectsWrongKey() { assertThatThrownBy(() -> codec.decode(json(created(1)), bytes("01"))).isInstanceOf(InvalidEventException.class); }
    @Test void rejectsTombstoneAndInvalidUtf8() {
        assertThatThrownBy(() -> codec.decode(null, bytes("1"))).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> codec.decode(new byte[]{(byte)0xff}, bytes("1"))).isInstanceOf(InvalidEventException.class);
    }
    private void reject(String value) { assertThatThrownBy(() -> codec.decode(bytes(value), bytes("1"))).isInstanceOf(InvalidEventException.class); }
}
