package cl.barriodigital.ms_audit.service;

import cl.barriodigital.ms_audit.kafka.EventCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecoveryService {
    private final JdbcTemplate jdbc;
    private final EventCodec codec;
    private final EventProcessingService events;
    public RecoveryService(JdbcTemplate jdbc, EventCodec codec, EventProcessingService events) {
        this.jdbc = jdbc; this.codec = codec; this.events = events;
    }
    @Transactional(timeout=30)
    public boolean recover(long id) {
        if (id <= 0) throw new IllegalArgumentException("Invalid recovery id");
        var rows = jdbc.query("""
            SELECT * FROM bd_audit.failed_event WHERE id=? AND recovered_at IS NULL FOR UPDATE
            """, (rs, row) -> new FailedRecord(rs.getString("source_topic"), rs.getInt("source_partition"),
                rs.getLong("source_offset"), rs.getBytes("record_key"), rs.getBytes("payload")), id);
        if (rows.isEmpty()) return false;
        var row = rows.getFirst();
        var event = codec.decode(row.payload(), row.key());
        events.process(event, row.topic(), row.partition(), row.offset());
        jdbc.update("""
            UPDATE bd_audit.failed_event SET recovered_at=clock_timestamp(),last_attempt_at=clock_timestamp(),
                recovery_attempts=recovery_attempts+1 WHERE id=?
            """, id);
        return true;
    }
    @Transactional(timeout=30)
    public void failedAttempt(long id) {
        jdbc.update("""
            UPDATE bd_audit.failed_event SET last_attempt_at=clock_timestamp(),recovery_attempts=recovery_attempts+1
            WHERE id=? AND recovered_at IS NULL
            """, id);
    }
    private record FailedRecord(String topic, int partition, long offset, byte[] key, byte[] payload) { }
}
