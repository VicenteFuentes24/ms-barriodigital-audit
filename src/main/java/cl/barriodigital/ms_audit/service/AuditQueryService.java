package cl.barriodigital.ms_audit.service;

import cl.barriodigital.ms_audit.dto.*;
import cl.barriodigital.ms_audit.dto.RequestEventV1.EventType;
import cl.barriodigital.ms_audit.entity.AuditEvent;
import cl.barriodigital.ms_audit.repository.AuditEventRepository;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditQueryService {
    private final AuditEventRepository repository;
    private final ZoneId zone;
    public AuditQueryService(AuditEventRepository repository, @Value("${audit.query-zone}") String zone) {
        this.repository = repository; this.zone = ZoneId.of(zone);
    }
    @Transactional(readOnly=true, timeout=10)
    public Page<AuditEventResponse> list(String user, LocalDate date, String eventType, Long requestId, int page, int size) {
        String actor = user == null ? "" : user.trim();
        if (actor.length() > 250 || page < 0 || page > 10000 || size < 1 || size > 200 || (requestId != null && requestId <= 0))
            throw new IllegalArgumentException("INVALID_FILTER");
        if (date != null && (date.getYear() < 1 || date.getYear() > 9999)) throw new IllegalArgumentException("INVALID_DATE");
        EventType type = eventType == null || eventType.isBlank() ? null : EventType.valueOf(eventType.trim().toUpperCase(Locale.ROOT));
        Specification<AuditEvent> filters = (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (!actor.isEmpty()) {
                String escaped = actor.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                predicates.add(cb.like(cb.lower(root.get("actor")), "%" + escaped + "%", '\\'));
            }
            if (date != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), date.atStartOfDay(zone).toInstant()));
                predicates.add(cb.lessThan(root.get("occurredAt"), date.plusDays(1).atStartOfDay(zone).toInstant()));
            }
            if (type != null) predicates.add(cb.equal(root.get("eventType"), type.name()));
            if (requestId != null) predicates.add(cb.equal(root.get("requestId"), requestId));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return repository.findAll(filters, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt", "id")))
                .map(AuditEvent::response);
    }
}
