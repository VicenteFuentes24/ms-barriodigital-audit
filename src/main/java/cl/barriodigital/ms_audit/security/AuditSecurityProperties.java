package cl.barriodigital.ms_audit.security;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated @ConfigurationProperties("audit.security")
public record AuditSecurityProperties(
        @NotBlank @Pattern(regexp="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String tenantId,
        @NotBlank String audience) {
    public String issuer() { return "https://login.microsoftonline.com/" + tenantId + "/v2.0"; }
    public String jwks() { return "https://login.microsoftonline.com/" + tenantId + "/discovery/v2.0/keys"; }
}
