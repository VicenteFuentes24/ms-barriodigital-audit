package cl.barriodigital.ms_audit.config;

import cl.barriodigital.ms_audit.service.RecoveryService;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Explicit offline recovery of one quarantined record; never publishes to Kafka. */
@Component @Profile("recover")
public class RecoveryCommand implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(RecoveryCommand.class);
    private final RecoveryService recovery;
    private final long id;
    private final ConfigurableApplicationContext context;
    public RecoveryCommand(RecoveryService recovery, @Value("${audit.recovery-id}") long id, ConfigurableApplicationContext context) {
        this.recovery = recovery; this.id = id; this.context = context;
    }
    @Override public void run(ApplicationArguments args) {
        try {
            boolean applied = recovery.recover(id);
            LOG.info("audit_recovery_completed failureId={} applied={}", id, applied);
        } catch (RuntimeException failure) {
            try { recovery.failedAttempt(id); }
            catch (RuntimeException unavailable) { LOG.error("audit_recovery_diagnostic_unavailable failureId={}", id); }
            // Do not expose SQL, payloads or database connection details in the startup exception.
            throw new IllegalStateException("Audit recovery failed; quarantined data remains intact");
        } finally { context.close(); }
    }
}
