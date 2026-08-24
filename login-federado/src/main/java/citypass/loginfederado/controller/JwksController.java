package citypass.loginfederado.controller;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Expone la clave pública del IdP en formato JWKS estándar, para que los
 * otros 7 módulos validen los JWT SIN llamarnos en cada request. Solo se
 * expone la clave PÚBLICA: nunca la privada.
 *
 * El kid que viaja acá es el mismo del encabezado de cada token: la huella
 * RFC 7638 calculada sobre esta misma clave (ver JwtKeyConfig).
 */
@RestController
public class JwksController {

    private final RSAKey rsaKey;

    public JwksController(RSAKey rsaKey) {
        this.rsaKey = rsaKey;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        // toPublicJWK(): descarta la parte privada antes de exponer.
        return new JWKSet(rsaKey.toPublicJWK()).toJSONObject(true);
    }
}
