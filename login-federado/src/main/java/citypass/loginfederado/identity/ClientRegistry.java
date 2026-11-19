package citypass.loginfederado.identity;

import citypass.loginfederado.config.CitypassProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Registro en memoria de los clientes autorizados a pedir tokens.
 *
 * Mensaje de error genérico y único para TODOS los caminos de falla de login:
 * la spec exige que usuario inexistente, contraseña mala, módulo equivocado,
 * cuenta deshabilitada o cliente desconocido sean indistinguibles byte por
 * byte (nadie puede mapear qué existe probando logins).
 */
@Component
public class ClientRegistry {

    /** Audience reservada del panel: el único cliente transversal. */
    public static final String ADMIN_AUDIENCE = "citypass-admin-api";

    public static final String GENERIC_ERROR_MESSAGE = "Usuario o contraseña inválidos";

    private static final Logger log = LoggerFactory.getLogger(ClientRegistry.class);

    private final Map<String, CitypassProperties.Client> clientsByClientId;
    private final Set<String> knownAudiences;

    public ClientRegistry(CitypassProperties properties) {
        this.clientsByClientId = properties.clients().stream()
                .collect(Collectors.toMap(CitypassProperties.Client::clientId, Function.identity()));
        this.knownAudiences = Stream.concat(
                        clientsByClientId.values().stream().map(CitypassProperties.Client::audience),
                        Stream.of(ADMIN_AUDIENCE))
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        log.info("Registro de clientes inicializado: {} clientes ({})",
                clientsByClientId.size(),
                clientsByClientId.values().stream().filter(CitypassProperties.Client::isService).count() + " de servicio");
    }

    /**
     * Audiences que este IdP puede emitir (las de cada cliente declarado, más
     * la reservada del panel). El decoder las usa para rechazar tokens cuyo
     * `aud` no sea de ningún emisor conocido: un token firmado con la clave
     * del IdP pero destinado a otro sistema no debe servir acá.
     */
    public Set<String> knownAudiences() {
        return knownAudiences;
    }

    /** Cliente humano por client_id. Falla genérica si no existe o es de servicio. */
    public CitypassProperties.Client requireHuman(String clientId) {
        CitypassProperties.Client client = clientsByClientId.get(clientId);
        if (client == null || client.isService()) {
            throw new BadCredentialsException(GENERIC_ERROR_MESSAGE);
        }
        return client;
    }

    /**
     * El chequeo de módulo del login: la ficha apareció en la OU del módulo X;
     * ¿este cliente puede emitir tokens para personas de X? Solo el cliente
     * transversal del panel cruza módulos.
     */
    public boolean acceptsModule(CitypassProperties.Client client, String module) {
        return client.isTransversal() || client.module().equalsIgnoreCase(module);
    }

    /**
     * Autenticación backend-a-backend: client_id + client_secret
     * (contrato público §7). Mismo error genérico que el login de personas.
     */
    public CitypassProperties.Client authenticateService(String clientId, String clientSecret) {
        CitypassProperties.Client client = clientsByClientId.get(clientId);
        if (client == null || !client.isService()) {
            throw new BadCredentialsException(GENERIC_ERROR_MESSAGE);
        }
        if (clientSecret == null || !constantTimeEquals(clientSecret, client.clientSecret())) {
            throw new BadCredentialsException(GENERIC_ERROR_MESSAGE);
        }
        return client;
    }

    /** Comparación en tiempo constante: evita oráculo de timing sobre secretos. */
    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }
}
