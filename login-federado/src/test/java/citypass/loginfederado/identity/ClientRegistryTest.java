package citypass.loginfederado.identity;

import citypass.loginfederado.config.CitypassProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El registro de clientes es la primera valla del login: cliente humano,
 * cliente de servicio y el chequeo de módulo (solo el panel cruza módulos).
 */
class ClientRegistryTest {

    private static final String GENERIC = ClientRegistry.GENERIC_ERROR_MESSAGE;

    private final CitypassProperties.Client reclamosWeb = new CitypassProperties.Client(
            "citypass-reclamos-web", null, "citypass-reclamos-api", "reclamos", false, "human", null);

    private final CitypassProperties.Client adminWeb = new CitypassProperties.Client(
            "citypass-admin-web", null, ClientRegistry.ADMIN_AUDIENCE, null, true, "human", null);

    private final CitypassProperties.Client grupo1 = new CitypassProperties.Client(
            "svc-grupo1", "secreto-grupo1", "citypass-bus", null, false, "service", "grupo1");

    private ClientRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ClientRegistry(new CitypassProperties(List.of(reclamosWeb, adminWeb, grupo1)));
    }

    // ---- requireHuman ----

    @Test
    void resolvesKnownHumanClient() {
        assertThat(registry.requireHuman("citypass-reclamos-web")).isEqualTo(reclamosWeb);
    }

    @Test
    void unknownClientFailsWithGenericError() {
        assertThatThrownBy(() -> registry.requireHuman("no-existe"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage(GENERIC);
    }

    @Test
    void serviceClientCannotActAsHuman() {
        assertThatThrownBy(() -> registry.requireHuman("svc-grupo1"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage(GENERIC);
    }

    // ---- acceptsModule (chequeo de segregación del login) ----

    @Test
    void moduleClientOnlyAcceptsItsOwnModule() {
        assertThat(registry.acceptsModule(reclamosWeb, "reclamos")).isTrue();
        assertThat(registry.acceptsModule(reclamosWeb, "RECLAMOS")).isTrue();
        assertThat(registry.acceptsModule(reclamosWeb, "movilidad")).isFalse();
    }

    @Test
    void onlyTheTransversalAdminClientCrossesModules() {
        assertThat(registry.acceptsModule(adminWeb, "residuos")).isTrue();
        assertThat(registry.acceptsModule(adminWeb, "espacios")).isTrue();
        assertThat(registry.acceptsModule(adminWeb, "")).isTrue();
    }

    // ---- authenticateService (client_credentials) ----

    @Test
    void serviceAuthenticatesWithExactSecret() {
        assertThat(registry.authenticateService("svc-grupo1", "secreto-grupo1")).isEqualTo(grupo1);
    }

    @Test
    void wrongSecretIsRejected() {
        assertThatThrownBy(() -> registry.authenticateService("svc-grupo1", "mal"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage(GENERIC);
    }

    @Test
    void nullSecretIsRejected() {
        assertThatThrownBy(() -> registry.authenticateService("svc-grupo1", null))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage(GENERIC);
    }

    @Test
    void unknownServiceClientIdIsRejected() {
        assertThatThrownBy(() -> registry.authenticateService("otro-svc", "lo-que-sea"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage(GENERIC);
    }

    @Test
    void humanClientCannotUseServiceFlow() {
        assertThatThrownBy(() -> registry.authenticateService("citypass-reclamos-web", "x"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage(GENERIC);
    }
}
