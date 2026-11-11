package cl.barriodigital.ms_audit;

import cl.barriodigital.ms_audit.config.KafkaErrorConfig;
import cl.barriodigital.ms_audit.exception.InvalidEventException;
import cl.barriodigital.ms_audit.service.FailedEventService;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaErrorHandlerTest {
    final FailedEventService failures = mock(FailedEventService.class);
    final Consumer<?,?> consumer = mock(Consumer.class);
    final MessageListenerContainer container = mock(MessageListenerContainer.class);
    @BeforeEach void setup() {
        var props = new ContainerProperties("unit-test"); props.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        props.setSyncCommits(true);
        props.setSyncCommitTimeout(Duration.ofSeconds(10));
        when(container.getContainerProperties()).thenReturn(props);
    }
    @Test void permanentErrorDurablyRecoversBeforeOffsetCommit() {
        var handler = new KafkaErrorConfig().kafkaErrorHandler(failures, 1, 100);
        var record = TestEvents.record(TestEvents.created(1), 4);
        var error = new InvalidEventException("INVALID_EVENT_FIELDS");
        handler.handleRemaining(error,List.of(record),consumer,container);
        var order = inOrder(failures,consumer);
        order.verify(failures).record(record,error);
        order.verify(consumer).commitSync(anyMap(),any(Duration.class));
    }
    @Test void transientFailureRetriesBeforeQuarantine() {
        var handler = new KafkaErrorConfig().kafkaErrorHandler(failures,1,100);
        var record = TestEvents.record(TestEvents.created(1),4);
        var error = new DataAccessResourceFailureException("unavailable");
        assertThatThrownBy(() -> handler.handleRemaining(error,List.of(record),consumer,container))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("Record in retry");
        verifyNoInteractions(failures); verify(consumer,never()).commitSync(anyMap(),any(Duration.class));
        handler.handleRemaining(error,List.of(record),consumer,container);
        verify(failures).record(record,error); verify(consumer).commitSync(anyMap(),any(Duration.class));
    }
    @Test void unavailableFailureStoreDoesNotCommit() {
        var handler = new KafkaErrorConfig().kafkaErrorHandler(failures,0,100);
        doThrow(new DataAccessResourceFailureException("unavailable")).when(failures).record(any(),any());
        var record = TestEvents.record(TestEvents.created(1),4);
        assertThatThrownBy(() -> handler.handleRemaining(new InvalidEventException("INVALID_EVENT_FIELDS"),List.of(record),consumer,container))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("Record in retry");
        verify(consumer,never()).commitSync(anyMap(),any(Duration.class));
    }
    @Test void retryBoundsValidated() {
        assertThatThrownBy(() -> new KafkaErrorConfig().kafkaErrorHandler(failures,11,100)).isInstanceOf(IllegalArgumentException.class);
    }
}
