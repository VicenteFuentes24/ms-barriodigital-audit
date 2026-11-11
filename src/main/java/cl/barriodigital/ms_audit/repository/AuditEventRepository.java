package cl.barriodigital.ms_audit.repository;

import cl.barriodigital.ms_audit.entity.AuditEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

/** Used only for queries; database triggers also prevent inherited bulk mutations. */
public interface AuditEventRepository extends Repository<AuditEvent, UUID>, JpaSpecificationExecutor<AuditEvent> { }
