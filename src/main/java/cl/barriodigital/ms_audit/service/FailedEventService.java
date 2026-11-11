package cl.barriodigital.ms_audit.service;

import cl.barriodigital.ms_audit.exception.InvalidEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class FailedEventService {
    private final JdbcTemplate jdbc;
    public FailedEventService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional(propagation=Propagation.REQUIRES_NEW, timeout=30)
    public void record(ConsumerRecord<?, ?> record, Exception failure) {
        jdbc.update("""
            INSERT INTO bd_audit.failed_event(source_topic,source_partition,source_offset,record_key,payload,failure_code)
            VALUES (?,?,?,?,?,?) ON CONFLICT(source_topic,source_partition,source_offset) DO NOTHING
            """, record.topic(), record.partition(), record.offset(), (byte[]) record.key(), (byte[]) record.value(), code(failure));
        // Any database/commit error escapes: the recoverer must not commit a lost record.
    }
    public static String code(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidEventException) return cause.getMessage();
        }
        return "PROCESSING_FAILED_AFTER_RETRIES";
    }
}
