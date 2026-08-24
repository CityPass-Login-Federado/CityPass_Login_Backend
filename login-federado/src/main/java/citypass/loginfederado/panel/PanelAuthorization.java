package citypass.loginfederado.panel;

import citypass.loginfederado.identity.ClientRegistry;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Autorización del panel, en un solo lugar y explícita (spec §5.1):
 *
 * El panel es un consumidor más de nuestros propios tokens. Para operar hace
 * falta que el JWT:
 *   - tenga la audience del panel (citypass-admin-api),
 *   - sea token_use=human y ver=1 (contrato vigente),
 *   - declare un module (el módulo que el delegado administra),
 *   - contenga el grupo reservado "delegados".
 *
 * La simetría no es casualidad: si nuestro contrato alcanza para nuestro
 * propio panel, alcanza para los otros siete equipos.
 */
@Component
public class PanelAuthorization {

    public static final String DELEGADOS_GROUP = "delegados";

    /** Quién está operando el panel y sobre qué módulo. */
    public record Delegate(String sub, String uid, String module) {
    }

    public Delegate requireDelegate(Jwt jwt) {
        List<String> audience = jwt.getAudience();
        if (audience == null || !audience.contains(ClientRegistry.ADMIN_AUDIENCE)) {
            throw new AccessDeniedException("Token sin audience del panel");
        }

        String tokenUse = jwt.getClaimAsString("token_use");
        if (!AccessTokenClaims.TOKEN_USE_HUMAN.equals(tokenUse)) {
            throw new AccessDeniedException("El panel requiere token humano");
        }

        // La librería JWT deserializa los números JSON como Long; tipar el
        // claim como Integer lanza ClassCastException en runtime. Comparar
        // por valor numérico acepta cualquier representación entera.
        Number verClaim = jwt.getClaim("ver");
        if (verClaim == null || verClaim.intValue() != AccessTokenClaims.CONTRACT_VERSION) {
            throw new AccessDeniedException("Versión de contrato no soportada");
        }

        List<String> groups = jwt.getClaimAsStringList("groups");
        if (groups == null || !groups.contains(DELEGADOS_GROUP)) {
            throw new AccessDeniedException("Se requiere el grupo " + DELEGADOS_GROUP);
        }

        String module = jwt.getClaimAsString("module");
        if (module == null || module.isBlank()) {
            // Sin module no hay scope: el aislamiento entre módulos vive acá.
            throw new AccessDeniedException("Token sin claim module");
        }

        return new Delegate(jwt.getSubject(), jwt.getClaimAsString("preferred_username"), module.toLowerCase());
    }

    /** Constantes espejadas del emisor para evitar dependencia circular. */
    private static final class AccessTokenClaims {
        static final String TOKEN_USE_HUMAN = "human";
        static final int CONTRACT_VERSION = 1;
    }
}
