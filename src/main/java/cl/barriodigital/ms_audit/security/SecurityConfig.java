package cl.barriodigital.ms_audit.security;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.SecurityFilterChain;
import jakarta.servlet.http.HttpServletResponse;

@Configuration @ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AuditSecurityProperties.class)
public class SecurityConfig {
    @Bean
    public JwtDecoder jwtDecoder(AuditSecurityProperties properties) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwks()).build();
        decoder.setJwtValidator(validators(properties));
        return decoder;
    }
    public static OAuth2TokenValidator<Jwt> validators(AuditSecurityProperties properties) {
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(properties.audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(properties.issuer()), audience);
    }
    public static JwtAuthenticationConverter converter() {
        var converter = new JwtAuthenticationConverter();
        var scopes = new JwtGrantedAuthoritiesConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
            Object roles = jwt.getClaims().get("roles");
            if (roles instanceof Collection<?> values) for (Object role : values) {
                if ("Admin".equals(role) || "Auditor".equals(role)) authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
            return authorities;
        });
        return converter;
    }
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Bearer-only API: no cookie authentication; all modifying routes are denied.
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/audit", "/api/audit/**").access(AuthorizationManagers.allOf(
                    AuthorityAuthorizationManager.hasAuthority("SCOPE_access_as_user"),
                    AuthorityAuthorizationManager.hasAnyRole("Admin", "Auditor")))
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, ex) -> error(res, 401))
                .accessDeniedHandler((req, res, ex) -> error(res, 403)))
            .oauth2ResourceServer(oauth -> oauth
                .authenticationEntryPoint((req, res, ex) -> error(res, 401))
                .accessDeniedHandler((req, res, ex) -> error(res, 403))
                .jwt(jwt -> jwt.jwtAuthenticationConverter(converter())));
        return http.build();
    }
    private static void error(HttpServletResponse response, int status) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.getWriter().write(status == 401 ? "{\"message\":\"Autenticación requerida.\"}" : "{\"message\":\"Acceso no autorizado.\"}");
    }
}
