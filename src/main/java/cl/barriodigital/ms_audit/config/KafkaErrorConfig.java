package cl.barriodigital.ms_audit.config;

import cl.barriodigital.ms_audit.exception.InvalidEventException;
import cl.barriodigital.ms_audit.service.FailedEventService;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaErrorConfig {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaErrorConfig.class);
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(FailedEventService failures,
            @Value("${audit.consumer-retries}") long retries, @Value("${audit.retry-delay-ms}") long delay) {
        if (retries < 0 || retries > 10 || delay < 100 || delay > 10000) throw new IllegalArgumentException("Invalid Kafka retry bounds");
        var handler = new DefaultErrorHandler((record, error) -> {
            failures.record(record, error);
            LOG.error("audit_event_quarantined partition={} offset={} code={}", record.partition(), record.offset(), FailedEventService.code(error));
        }, new FixedBackOff(delay, retries));
        handler.addNotRetryableExceptions(InvalidEventException.class);
        handler.setCommitRecovered(true);
        handler.setResetStateOnRecoveryFailure(true);
        handler.setRetryListeners((record, error, attempt) -> LOG.warn(
                "audit_processing_failed partition={} offset={} attempt={} code={}",
                record.partition(), record.offset(), attempt, FailedEventService.code(error)));
        return handler;
    }
}
