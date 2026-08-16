package ar.edu.uade.citypass.loginfederado.controller;

import ar.edu.uade.citypass.loginfederado.config.JwtProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.interfaces.RSAPublicKey;
import java.util.Map;

/**
 * Expone la clave pública del módulo en formato JWKS estándar, para que
 * los otros 7 módulos puedan validar los JWT emitidos acá SIN llamar a
 * este servicio en cada request. Solo se expone la clave PÚBLICA: nunca
 * la privada.
 *
 * Endpoint convencional (bien conocido por librerías JWT de cualquier
 * lenguaje): /.well-known/jwks.json
 */
@RestController
public class JwksController {

    private final RSAPublicKey publicKey;
    private final JwtProperties jwtProperties;

    public JwksController(RSAPublicKey publicKey, JwtProperties jwtProperties) {
        this.publicKey = publicKey;
        this.jwtProperties = jwtProperties;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .keyID(jwtProperties.keyId())
                .algorithm(JWSAlgorithm.RS256)
                .build();
        return new JWKSet(rsaKey).toJSONObject();
    }
}