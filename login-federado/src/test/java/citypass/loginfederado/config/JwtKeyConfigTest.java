package citypass.loginfederado.config;

import citypass.loginfederado.identity.ClientRegistry;
import citypass.loginfederado.repository.RevokedAccessTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class JwtKeyConfigTest {

    private static final String ISSUER = "https://idp.citypass.local";
    private static final String AUDIENCE = "citypass-reclamos-api";

    @TempDir Path temporaryDirectory;

    private ClientRegistry clientRegistry() {
        return new ClientRegistry(new CitypassProperties(List.of(
                new CitypassProperties.Client(
                        "citypass-reclamos-web", null, AUDIENCE, "reclamos", false, "human", null))));
    }

    private RevokedAccessTokenRepository revokedAccessTokenRepository() {
        return mock(RevokedAccessTokenRepository.class);
    }

    @Test
    void failsFastWhenKeysAreMissingAndAutoGenerationIsDisabled() {
        JwtKeyConfig config = config(false);

        assertThatThrownBy(config::rsaPrivateKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.auto-generate-keys=false")
                .hasMessageContaining("provéalas como secrets");
    }

    @Test
    void decoderAcceptsTokensFromConfiguredIssuerOnly() throws Exception {
        JwtKeyConfig config = config(true);
        var privateKey = config.rsaPrivateKey();
        var publicKey = config.rsaPublicKey();
        var encoder = config.jwtEncoder(config.rsaKey(publicKey, privateKey));
        var decoder = config.jwtDecoder(publicKey, clientRegistry(), revokedAccessTokenRepository());

        String validToken = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(),
                JwtClaimsSet.builder()
                        .issuer(ISSUER)
                        .audience(List.of(AUDIENCE))
                        .subject("U000042")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build()))
                .getTokenValue();
        assertThat(decoder.decode(validToken).getIssuer().toString()).isEqualTo(ISSUER);

        String foreignIssuerToken = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(),
                JwtClaimsSet.builder()
                        .issuer("https://untrusted.example")
                        .audience(List.of(AUDIENCE))
                        .subject("U000042")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build()))
                .getTokenValue();

        assertThatThrownBy(() -> decoder.decode(foreignIssuerToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("iss claim");
    }

    @Test
    void decoderRejectsTokenForAnUnknownAudience() throws Exception {
        JwtKeyConfig config = config(true);
        var privateKey = config.rsaPrivateKey();
        var publicKey = config.rsaPublicKey();
        var encoder = config.jwtEncoder(config.rsaKey(publicKey, privateKey));
        var decoder = config.jwtDecoder(publicKey, clientRegistry(), revokedAccessTokenRepository());

        String foreignAudienceToken = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(),
                JwtClaimsSet.builder()
                        .issuer(ISSUER)
                        .audience(List.of("otro-sistema-ajeno"))
                        .subject("U000042")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build()))
                .getTokenValue();

        assertThatThrownBy(() -> decoder.decode(foreignAudienceToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("audience");
    }

    private JwtKeyConfig config(boolean autoGenerateKeys) {
        String privateKeyPath = temporaryDirectory.resolve("keys/private.pem").toUri().toString();
        String publicKeyPath = temporaryDirectory.resolve("keys/public.pem").toUri().toString();
        JwtProperties properties = new JwtProperties(ISSUER, 15, 8, 60,
                privateKeyPath, publicKeyPath);
        return new JwtKeyConfig(new DefaultResourceLoader(), properties, autoGenerateKeys);
    }
}
