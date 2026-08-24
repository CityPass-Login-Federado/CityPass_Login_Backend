package citypass.loginfederado.token;

import citypass.loginfederado.config.CitypassProperties;
import citypass.loginfederado.config.JwtProperties;
import citypass.loginfederado.identity.LdapDirectoryPerson;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.text.ParseException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El contrato de tokens está CONGELADO (specs/03-CONTRATO-TOKEN.md): este
 * test es el candado. Si algo de acá cambia sin subir "ver" y coordinar con
 * los siete módulos, este test debe explotar primero.
 */
class AccessTokenIssuerTest {

    private static final String ISSUER = "https://idp.citypass.local";
    private static AccessTokenIssuer issuer;
    private static RSAKey rsaKey;

    private final CitypassProperties.Client humanClient = new CitypassProperties.Client(
            "citypass-reclamos-web", null, "citypass-reclamos-api", "reclamos", false, "human", null);

    private final CitypassProperties.Client serviceClient = new CitypassProperties.Client(
            "svc-grupo1", "secreto", "citypass-bus", null, false, "service", "grupo1");

    @BeforeAll
    static void setUp() {
        try {
            rsaKey = new RSAKeyGenerator(2048).keyID("thumbprint-test").generate();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("No se pudo generar la clave de prueba", e);
        }
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
        JwtProperties props = new JwtProperties(ISSUER, 15, 8, 60, "ignored", "ignored");
        issuer = new AccessTokenIssuer(encoder, props, rsaKey);
    }

    @Test
    void humanTokenMatchesFrozenContract() throws ParseException {
        LdapDirectoryPerson person = new LdapDirectoryPerson(
                "uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local",
                "U000042", "jperez", "Juan Perez", "jperez@citypass.local",
                "Reclamos", List.of("soporte-n2", "delegados"));

        SignedJWT jwt = SignedJWT.parse(issuer.issueHuman(person, humanClient));

        assertThat(jwt.getHeader().getKeyID()).isEqualTo(rsaKey.getKeyID());

        var claims = jwt.getJWTClaimsSet();
        // sub = employeeNumber, NUNCA el uid (D3)
        assertThat(claims.getSubject()).isEqualTo("U000042");
        // aud SIEMPRE lista aunque tenga un elemento
        assertThat(claims.getAudience()).containsExactly("citypass-reclamos-api");
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.getStringClaim("token_use")).isEqualTo("human");
        assertThat(claims.getLongClaim("ver")).isEqualTo(1L);
        assertThat(claims.getStringClaim("preferred_username")).isEqualTo("jperez");
        // module en minúsculas tal como vino del árbol
        assertThat(claims.getStringClaim("module")).isEqualTo("reclamos");
        // grupos pelados, sin mapeo a roles ni filtrado (D1/D2/D8)
        assertThat(claims.getStringListClaim("groups"))
                .containsExactlyInAnyOrder("soporte-n2", "delegados");
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(claims.getExpirationTime()).isAfter(claims.getIssueTime());
    }

    @Test
    void humanTokenWithEmptyGroupsIsValid() throws ParseException {
        LdapDirectoryPerson person = new LdapDirectoryPerson(
                "uid=nadie,ou=People,ou=Movilidad,dc=citypass,dc=local",
                "U000099", "nadie", "Nadie", null,
                "Movilidad", List.of());

        var claims = SignedJWT.parse(issuer.issueHuman(person, humanClient)).getJWTClaimsSet();

        // Un grupo vacío es VÁLIDO: la librería LDAP devuelve null, no []
        assertThat(claims.getStringListClaim("groups")).isEmpty();
    }

    @Test
    void serviceTokenHasNamespaceAndNoGroups() throws ParseException {
        var claims = SignedJWT.parse(issuer.issueService(serviceClient)).getJWTClaimsSet();

        assertThat(claims.getSubject()).isEqualTo("svc-grupo1");
        assertThat(claims.getAudience()).containsExactly("citypass-bus");
        assertThat(claims.getStringClaim("token_use")).isEqualTo("service");
        assertThat(claims.getLongClaim("ver")).isEqualTo(1L);
        assertThat(claims.getStringClaim("namespace")).isEqualTo("grupo1");
        // Un token de servicio jamás lleva grupos
        assertThat(claims.getClaim("groups")).isNull();
        assertThat(claims.getClaim("preferred_username")).isNull();
    }
}
