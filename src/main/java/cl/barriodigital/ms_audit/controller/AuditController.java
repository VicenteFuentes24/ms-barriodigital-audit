package cl.barriodigital.ms_audit.controller;

import cl.barriodigital.ms_audit.dto.AuditEventResponse;
import cl.barriodigital.ms_audit.service.AuditQueryService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/audit")
public class AuditController {
    private final AuditQueryService queries;
    public AuditController(AuditQueryService queries) { this.queries = queries; }
    @GetMapping({"", "/"})
    public ResponseEntity<List<AuditEventResponse>> list(
            @RequestParam(required=false) String user,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required=false) String eventType,
            @RequestParam(required=false) Long requestId,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="100") int size) {
        var result = queries.list(user, date, eventType, requestId, page, size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Total-Count", Long.toString(result.getTotalElements()))
                .header("X-Has-Next", Boolean.toString(result.hasNext()))
                .body(result.getContent());
    }
}
