package citypass.loginfederado.controller;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Infraestructura", description = "Descubrimiento de claves públicas y estado del servicio.")
@RestController
public class JwksController {

    private final RSAKey rsaKey;

    public JwksController(RSAKey rsaKey) {
        this.rsaKey = rsaKey;
    }

    @Operation(summary = "Clave pública JWKS",
            description = "Expone la clave pública RS256 del IdP en formato JWKS (RFC 7517) "
                    + "para que otros módulos validen tokens sin llamarnos.")
    @ApiResponse(responseCode = "200", description = "JWKSet con la clave pública de firma (nunca la privada)")
    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        // toPublicJWK(): descarta la parte privada antes de exponer.
        return new JWKSet(rsaKey.toPublicJWK()).toJSONObject(true);
    }
}