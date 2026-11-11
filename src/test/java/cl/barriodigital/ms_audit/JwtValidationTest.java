package cl.barriodigital.ms_audit;

import cl.barriodigital.ms_audit.security.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.security.*;
import java.security.interfaces.*;
import java.time.Instant;
import java.util.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;
import static org.assertj.core.api.Assertions.*;

class JwtValidationTest {
    static final AuditSecurityProperties SETTINGS = new AuditSecurityProperties("11111111-1111-1111-1111-111111111111", "audit-test");
    static KeyPair key;
    NimbusJwtDecoder decoder;
    @BeforeAll static void keys() throws Exception { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key = generator.generateKeyPair(); }
    @BeforeEach void decoder() { decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) key.getPublic()).build(); decoder.setJwtValidator(SecurityConfig.validators(SETTINGS)); }
    String token(String issuer, String audience, Instant expiry, KeyPair pair) throws Exception {
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder().issuer(issuer)
            .audience(audience).subject("unit-test").issueTime(Date.from(Instant.now().minusSeconds(600)))
            .expirationTime(Date.from(expiry)).claim("scp","access_as_user").claim("roles",List.of("Admin")).build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) pair.getPrivate())); return jwt.serialize();
    }
    @Test void validSignatureIssuerAndAudience() throws Exception {
        assertThat(decoder.decode(token(SETTINGS.issuer(),SETTINGS.audience(),Instant.now().plusSeconds(600),key)).getSubject()).isEqualTo("unit-test");
    }
    @Test void wrongIssuerRejected() throws Exception {
        var token = token("https://invalid.example",SETTINGS.audience(),Instant.now().plusSeconds(600),key);
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
    @Test void wrongAudienceRejected() throws Exception {
        var token = token(SETTINGS.issuer(),"other-api",Instant.now().plusSeconds(600),key);
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
    @Test void expiredRejected() throws Exception {
        var token = token(SETTINGS.issuer(),SETTINGS.audience(),Instant.now().minusSeconds(300),key);
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
    @Test void wrongSignatureRejected() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var token = token(SETTINGS.issuer(),SETTINGS.audience(),Instant.now().plusSeconds(600),generator.generateKeyPair());
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
    @Test void missingSettingsInvalid() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new AuditSecurityProperties("", ""))).hasSizeGreaterThanOrEqualTo(2);
            assertThat(factory.getValidator().validate(SETTINGS)).isEmpty();
        }
    }
}
