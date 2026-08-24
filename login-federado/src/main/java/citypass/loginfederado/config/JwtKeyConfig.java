package citypass.loginfederado.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Carga el par de claves RSA usadas para firmar (JwtEncoder) y validar
 * (JwtDecoder) los JWT del módulo.
 *
 * Si los archivos configurados en jwt.private-key-path / jwt.public-key-path
 * no existen, se GENERAN AUTOMÁTICAMENTE al arrancar la aplicación y se
 * persisten en disco -- así cualquiera que clone el repo puede correr la
 * app sin pasos manuales (no hace falta tener openssl instalado).
 *
 * IMPORTANTE: esto es una comodidad para DESARROLLO. En un ambiente real
 * (staging/producción) las claves tienen que administrarse como secrets
 * del proveedor cloud (variables de entorno, Vault, etc.), no autogenerarse
 * en el filesystem del servidor.
 *
 * El kid del JWK NO es un string fijo: se deriva del contenido de la clave
 * pública (huella RFC 7638). Misma clave → mismo kid, siempre. Como el JWKS
 * puede exponer varias claves a la vez, esto permite rotar sin cortar el
 * servicio (spec 01-DISENO-IDENTIDAD.md §3.5).
 */
@Configuration
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    private final ResourceLoader resourceLoader;
    private final JwtProperties jwtProperties;

    public JwtKeyConfig(ResourceLoader resourceLoader, JwtProperties jwtProperties) {
        this.resourceLoader = resourceLoader;
        this.jwtProperties = jwtProperties;
    }

    @Bean
    public RSAPrivateKey rsaPrivateKey() throws Exception {
        ensureKeysExist();
        byte[] decoded = Base64.getDecoder().decode(readPemBody(jwtProperties.privateKeyPath()));
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    @Bean
    public RSAPublicKey rsaPublicKey() throws Exception {
        ensureKeysExist();
        byte[] decoded = Base64.getDecoder().decode(readPemBody(jwtProperties.publicKeyPath()));
        X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    /**
     * El JWK único de este IdP: clave pública + privada + kid por huella
     * RFC 7638. Lo consumen el encoder, el decoder y el endpoint JWKS,
     * garantizando que los tres SIEMPRE anuncien el mismo kid.
     */
    @Bean
    public RSAKey rsaKey(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        // computeThumbprint() implementa exactamente RFC 7638: hash SHA-256
        // del JSON canónico {"e","kty","n"} en base64url.
        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(computeThumbprint(publicKey))
                .build();
    }

    @Bean
    public JwtEncoder jwtEncoder(RSAKey rsaKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAPublicKey publicKey) {
        return NimbusJwtDecoder.withPublicKey(publicKey).build();
    }

    static String computeThumbprint(RSAPublicKey publicKey) {
        try {
            RSAKey bare = new RSAKey.Builder(publicKey).build();
            return bare.computeThumbprint().toString();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo calcular la huella RFC 7638 de la clave", e);
        }
    }

    /**
     * Si falta cualquiera de los dos archivos, genera un par de claves RSA
     * de 2048 bits nuevo y los persiste. Los dos beans (rsaPrivateKey y
     * rsaPublicKey) llaman a este método antes de leer, por eso es
     * "synchronized" -- para que si Spring los instancia casi al mismo
     * tiempo, no se pisen generando dos pares de claves distintos.
     */
    private synchronized void ensureKeysExist() throws Exception {
        Resource privateResource = resourceLoader.getResource(jwtProperties.privateKeyPath());
        Resource publicResource = resourceLoader.getResource(jwtProperties.publicKeyPath());

        if (privateResource.exists() && publicResource.exists()) {
            return;
        }

        log.warn("No se encontraron las claves RSA en {} / {} -- generando un par nuevo automáticamente. " +
                        "Esto es solo para DESARROLLO: en un ambiente real las claves deben administrarse " +
                        "como secrets del proveedor cloud, no autogenerarse en el filesystem.",
                jwtProperties.privateKeyPath(), jwtProperties.publicKeyPath());

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        writePem(privateResource, "PRIVATE KEY", keyPair.getPrivate().getEncoded());
        writePem(publicResource, "PUBLIC KEY", keyPair.getPublic().getEncoded());

        log.info("Par de claves RSA generado y guardado en {} / {}",
                jwtProperties.privateKeyPath(), jwtProperties.publicKeyPath());
    }

    private void writePem(Resource resource, String type, byte[] encoded) throws IOException {
        File file = resource.getFile();
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            Files.createDirectories(parentDir.toPath());
        }

        String base64 = Base64.getEncoder().encodeToString(encoded);
        StringBuilder pem = new StringBuilder();
        pem.append("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            pem.append(base64, i, Math.min(i + 64, base64.length())).append("\n");
        }
        pem.append("-----END ").append(type).append("-----\n");

        Files.writeString(file.toPath(), pem.toString(), StandardCharsets.UTF_8);
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
