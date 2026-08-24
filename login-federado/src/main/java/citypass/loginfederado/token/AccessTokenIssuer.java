package citypass.loginfederado.token;

import citypass.loginfederado.config.CitypassProperties;
import citypass.loginfederado.config.JwtProperties;
import citypass.loginfederado.identity.LdapDirectoryPerson;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Construye y firma los DOS tipos de token del contrato público
 * (specs/03-CONTRATO-TOKEN.md). El formato está CONGELADO: cambiar algo acá
 * es un cambio de contrato y exige subir "ver" coordinando con siete equipos.
 *
 * Humano:  sub=employeeNumber | aud=[audience del cliente] | token_use=human
 *          ver=1 | preferred_username | module | groups (nombres pelados)
 * Servicio: sub=client_id | token_use=service | namespace | SIN groups.
 */
@Component
public class AccessTokenIssuer {

    public static final int CONTRACT_VERSION = 1;
    public static final String TOKEN_USE_HUMAN = "human";
    public static final String TOKEN_USE_SERVICE = "service";

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final RSAKey rsaKey;

    public AccessTokenIssuer(JwtEncoder jwtEncoder, JwtProperties jwtProperties, RSAKey rsaKey) {
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
        this.rsaKey = rsaKey;
    }

    public String issueHuman(LdapDirectoryPerson person, CitypassProperties.Client client) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(jwtProperties.accessTokenExpirationMinutes() * 60);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                // aud SIEMPRE es una lista, aunque tenga un solo elemento
                // (contrato §3, regla 2 de las más rotas).
                .audience(List.of(client.audience()))
                .subject(person.sub())
                .issuedAt(now)
                .expiresAt(expiry)
                .id(UUID.randomUUID().toString())
                .claim("token_use", TOKEN_USE_HUMAN)
                .claim("ver", CONTRACT_VERSION)
                .claim("preferred_username", person.uid())
                .claim("module", person.module().toLowerCase())
                // Los grupos pasan tal cual: sin tabla de mapeo grupo→rol ni
                // filtrado (D1/D2/D8). Puede venir vacío y es válido.
                .claim("groups", person.groups())
                .build();

        return encode(claims);
    }

    public String issueService(CitypassProperties.Client client) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(jwtProperties.serviceTokenExpirationMinutes() * 60);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .audience(List.of(client.audience()))
                .subject(client.clientId())
                .issuedAt(now)
                .expiresAt(expiry)
                .id(UUID.randomUUID().toString())
                .claim("token_use", TOKEN_USE_SERVICE)
                .claim("ver", CONTRACT_VERSION)
                .claim("namespace", client.namespace())
                .build();

        return encode(claims);
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                // kid derivado del contenido de la clave (huella RFC 7638):
                // misma clave, mismo kid, siempre — permite rotar sin cortar.
                .keyId(rsaKey.getKeyID())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
