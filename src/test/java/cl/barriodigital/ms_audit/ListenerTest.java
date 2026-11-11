package cl.barriodigital.ms_audit;

import cl.barriodigital.ms_audit.kafka.*;
import cl.barriodigital.ms_audit.service.EventProcessingService;
import cl.barriodigital.ms_audit.exception.InvalidEventException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static cl.barriodigital.ms_audit.TestEvents.*;

class ListenerTest {
    final EventCodec codec = mock(EventCodec.class);
    final EventProcessingService service = mock(EventProcessingService.class);
    final Acknowledgment ack = mock(Acknowledgment.class);
    final RequestEventListener listener = new RequestEventListener(codec, service);
    @Test void acknowledgesAfterServiceReturns() {
        var e = created(1); var record = record(e, 1);
        when(codec.decode(record.value(), record.key())).thenReturn(e);
        when(service.process(e, record.topic(), 0, 1)).thenReturn(true);
        listener.consume(record, ack);
        var order = inOrder(service, ack);
        order.verify(service).process(e, record.topic(), 0, 1); order.verify(ack).acknowledge();
    }
    @Test void duplicatesAlsoAcknowledge() {
        var e = created(1); var record = record(e, 1);
        when(codec.decode(record.value(), record.key())).thenReturn(e);
        listener.consume(record, ack); verify(ack).acknowledge();
    }
    @Test void databaseFailureDoesNotAcknowledge() {
        var e = created(1); var record = record(e, 1);
        when(codec.decode(record.value(), record.key())).thenReturn(e);
        when(service.process(e, record.topic(), 0, 1)).thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThatThrownBy(() -> listener.consume(record, ack)).isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(ack);
    }
    @Test void invalidEventDoesNotAcknowledgeOrPersist() {
        var record = record(created(1), 1);
        when(codec.decode(record.value(), record.key())).thenThrow(new InvalidEventException("INVALID_JSON"));
        assertThatThrownBy(() -> listener.consume(record, ack)).isInstanceOf(InvalidEventException.class);
        verifyNoInteractions(ack, service);
    }
}
