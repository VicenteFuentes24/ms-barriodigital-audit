package cl.barriodigital.ms_audit;

import cl.barriodigital.ms_audit.controller.AuditController;
import cl.barriodigital.ms_audit.dto.AuditEventResponse;
import cl.barriodigital.ms_audit.security.SecurityConfig;
import cl.barriodigital.ms_audit.service.AuditQueryService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=AuditController.class, properties={
        "audit.security.tenant-id=11111111-1111-1111-1111-111111111111", "audit.security.audience=unit-test-api",
        "debug=false", "logging.level.org.springframework=WARN", "logging.level.root=WARN", "spring.main.banner-mode=off"})
@Import(SecurityConfig.class)
class AuditControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuditQueryService queries;
    @MockitoBean JwtDecoder decoder;
    @BeforeEach void setup() {
        when(queries.list(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of()));
        token("Admin", "access_as_user");
    }
    void token(String role, String scope) {
        when(decoder.decode("test-token")).thenReturn(Jwt.withTokenValue("test-token").header("alg","RS256")
            .subject("test-user").claim("roles", List.of(role)).claim("scp", scope).build());
    }
    @Test void noToken401() throws Exception { mvc.perform(get("/api/audit")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").exists()); }
    @Test void forgedIdentityHeaders401() throws Exception {
        mvc.perform(get("/api/audit").header("X-User-Roles","Admin").header("X-User-Id","admin"))
            .andExpect(status().isUnauthorized());
    }
    @Test void invalidToken401() throws Exception {
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("private failure"));
        mvc.perform(get("/api/audit").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    }
    @ParameterizedTest @ValueSource(strings={"Operador","Cliente","","admin"})
    void wrongRole403(String role) throws Exception {
        token(role, "access_as_user"); mvc.perform(get("/api/audit").header("Authorization","Bearer test-token")).andExpect(status().isForbidden());
    }
    @Test void missingScope403() throws Exception { token("Admin", "other"); mvc.perform(get("/api/audit").header("Authorization","Bearer test-token")).andExpect(status().isForbidden()); }
    @ParameterizedTest @ValueSource(strings={"Admin","Auditor"})
    void authorizedArrayEmpty(String role) throws Exception {
        token(role,"access_as_user"); mvc.perform(get("/api/audit").header("Authorization","Bearer test-token"))
            .andExpect(status().isOk()).andExpect(content().json("[]")).andExpect(header().string("X-Total-Count","0"));
    }
    @Test void realReactContractAndFilters() throws Exception {
        var event = new AuditEventResponse(UUID.randomUUID().toString(), null, "REQUEST_CREATED", "REQUEST", "9", TestEvents.TIME, "Permiso · INGRESADO");
        when(queries.list("ana", LocalDate.of(2026,11,10), "REQUEST_CREATED", 9L, 0, 10)).thenReturn(new PageImpl<>(List.of(event)));
        mvc.perform(get("/api/audit").param("user","ana").param("date","2026-11-10").param("eventType","REQUEST_CREATED")
            .param("requestId","9").param("size","10").header("Authorization","Bearer test-token"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(event.id()))
            .andExpect(jsonPath("$[0].user").isEmpty()).andExpect(jsonPath("$[0].entityId").value("9"))
            .andExpect(jsonPath("$[0].occurredAt").value("2026-11-10T12:00:00Z"))
            .andExpect(jsonPath("$[0].detail").value(event.detail()));
    }
    @Test void invalidDate400() throws Exception { mvc.perform(get("/api/audit").param("date","yesterday").header("Authorization","Bearer test-token")).andExpect(status().isBadRequest()); }
    @Test void invalidFilter400() throws Exception {
        when(queries.list(any(),any(),any(),any(),anyInt(),anyInt())).thenThrow(new IllegalArgumentException("private"));
        mvc.perform(get("/api/audit").header("Authorization","Bearer test-token")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
    }
    @Test void databaseUnavailable503() throws Exception {
        when(queries.list(any(),any(),any(),any(),anyInt(),anyInt())).thenThrow(new DataAccessResourceFailureException("secret"));
        mvc.perform(get("/api/audit").header("Authorization","Bearer test-token")).andExpect(status().isServiceUnavailable())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }
    @Test void unexpected500() throws Exception {
        when(queries.list(any(),any(),any(),any(),anyInt(),anyInt())).thenThrow(new IllegalStateException("secret"));
        mvc.perform(get("/api/audit").header("Authorization","Bearer test-token")).andExpect(status().isInternalServerError());
    }
    @Test void modificationForbidden() throws Exception { mvc.perform(post("/api/audit").header("Authorization","Bearer test-token")).andExpect(status().isForbidden()); }
    @Test void unknownAuditRoute404() throws Exception { mvc.perform(get("/api/audit/unknown").header("Authorization","Bearer test-token")).andExpect(status().isNotFound()); }
}
