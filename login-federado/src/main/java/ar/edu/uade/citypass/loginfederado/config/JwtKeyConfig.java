package ar.edu.uade.citypass.loginfederado.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Carga el par de claves RSA (private_key.pem / public_key.pem) y expone:
 *  - JwtEncoder: usado por AuthService para FIRMAR los tokens que emite este módulo.
 *  - JwtDecoder: usado por el filtro de Resource Server para VALIDAR tokens
 *    en cada request a un endpoint protegido.
 *
 * Los otros 7 módulos NO usan esta clase: ellos validan contra el endpoint
 * JWKS público (futuro JwksController) usando solo la clave pública.
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, LockoutProperties.class})
public class JwtKeyConfig {

    private final ResourceLoader resourceLoader;
    private final JwtProperties jwtProperties;

    public JwtKeyConfig(ResourceLoader resourceLoader, JwtProperties jwtProperties) {
        this.resourceLoader = resourceLoader;
        this.jwtProperties = jwtProperties;
    }

    @Bean
    public RSAPrivateKey rsaPrivateKey() throws Exception {
        byte[] decoded = Base64.getDecoder().decode(readPemBody(jwtProperties.privateKeyPath()));
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    @Bean
    public RSAPublicKey rsaPublicKey() throws Exception {
        byte[] decoded = Base64.getDecoder().decode(readPemBody(jwtProperties.publicKeyPath()));
        X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    @Bean
    public JwtEncoder jwtEncoder(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(jwtProperties.keyId())
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAPublicKey publicKey) {
        return NimbusJwtDecoder.withPublicKey(publicKey).build();
    }

    private String readPemBody(String location) throws IOException {
        Resource resource = resourceLoader.getResource(location);
        try (InputStream is = resource.getInputStream()) {
            String content = new String(is.readAllBytes());
            return content
                    .replaceAll("-----BEGIN (.*)-----", "")
                    .replaceAll("-----END (.*)-----", "")
                    .replaceAll("\\s", "");
        }
    }
}