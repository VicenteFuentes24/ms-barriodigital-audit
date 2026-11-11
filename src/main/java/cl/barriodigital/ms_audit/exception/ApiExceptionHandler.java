package cl.barriodigital.ms_audit.exception;

import org.slf4j.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    public record ApiError(String message) { }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> invalid(Exception ignored) { return response(400, "Filtros inválidos. Revisa fecha, tipo de evento y paginación."); }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missing(Exception ignored) { return response(404, "Recurso no encontrado."); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> unavailable(Exception ignored) {
        LOG.warn("audit_query_database_unavailable");
        return response(503, "El servicio de auditoría no está disponible temporalmente.");
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ignored) {
        LOG.error("audit_query_unexpected_failure");
        return response(500, "No fue posible consultar la auditoría.");
    }
    private ResponseEntity<ApiError> response(int status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ApiError(message));
    }
}
