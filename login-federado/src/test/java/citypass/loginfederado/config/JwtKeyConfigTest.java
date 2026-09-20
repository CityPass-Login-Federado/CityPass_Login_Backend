package citypass.loginfederado.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtKeyConfigTest {

    private static final String ISSUER = "https://idp.citypass.local";

    @TempDir Path temporaryDirectory;

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
        var decoder = config.jwtDecoder(publicKey);

        String validToken = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(),
                JwtClaimsSet.builder()
                        .issuer(ISSUER)
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
                        .subject("U000042")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build()))
                .getTokenValue();

        assertThatThrownBy(() -> decoder.decode(foreignIssuerToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("iss claim");
    }

    private JwtKeyConfig config(boolean autoGenerateKeys) {
        String privateKeyPath = temporaryDirectory.resolve("keys/private.pem").toUri().toString();
        String publicKeyPath = temporaryDirectory.resolve("keys/public.pem").toUri().toString();
        JwtProperties properties = new JwtProperties(ISSUER, 15, 8, 60,
                privateKeyPath, publicKeyPath);
        return new JwtKeyConfig(new DefaultResourceLoader(), properties, autoGenerateKeys);
    }
}
