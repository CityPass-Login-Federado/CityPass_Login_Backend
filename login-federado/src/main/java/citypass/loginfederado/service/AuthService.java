package citypass.loginfederado.service;

import citypass.loginfederado.config.CitypassProperties;
import citypass.loginfederado.config.JwtProperties;
import citypass.loginfederado.dto.LoginRequest;
import citypass.loginfederado.dto.LoginResponse;
import citypass.loginfederado.dto.RefreshRequest;
import citypass.loginfederado.event.EventPublisher;
import citypass.loginfederado.event.UsuarioAutenticadoEvent;
import citypass.loginfederado.exception.AccountLockedException;
import citypass.loginfederado.identity.ClientRegistry;
import citypass.loginfederado.identity.LdapDirectory;
import citypass.loginfederado.identity.LdapDirectoryPerson;
import citypass.loginfederado.security.AnomalyRiskClient;
import citypass.loginfederado.token.AccessTokenIssuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import javax.naming.NamingException;

/**
 * Flujo de autenticación según spec 01-DISENO-IDENTIDAD.md §4:
 *
 *   0) bloqueo por ventana deslizante (ADR-004)
 *   1) rechazar contraseña vacía ANTES de tocar LDAP (bind con contraseña
 *      vacía = conexión anónima y devuelve ÉXITO: rareza histórica del
 *      protocolo que dejaría entrar a cualquiera)
 *   2) búsqueda GLOBAL del uid en todo el árbol (es único por overlay unique)
 *   3) cualquier resultado distinto de exactamente 1 → falla genérica
 *   4) chequeo de módulo: OU donde apareció la ficha vs módulo del client_id
 *      (el panel es el único cliente transversal). Módulo equivocado produce
 *      EXACTAMENTE el mismo error que una contraseña mal tipeada
 *   5) bind con el DN encontrado, en conexión nueva que se cierra
 *   6) emitir access token (15 min) + refresh token (8 h, rotativo)
 *
 * TODOS los caminos de falla devuelven el mismo error, byte por byte:
 * nadie puede mapear qué usuarios existen ni quién pertenece a qué módulo.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");

    private final LdapDirectory ldapDirectory;
    private final ClientRegistry clientRegistry;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenService refreshTokenService;
    private final JwtProperties jwtProperties;
    private final EventPublisher eventPublisher;
    private final LoginAttemptService loginAttemptService;
    private final AnomalyRiskClient anomalyRiskClient;

    public AuthService(LdapDirectory ldapDirectory,
                       ClientRegistry clientRegistry,
                       AccessTokenIssuer accessTokenIssuer,
                       RefreshTokenService refreshTokenService,
                       JwtProperties jwtProperties,
                       EventPublisher eventPublisher,
                       LoginAttemptService loginAttemptService,
                       AnomalyRiskClient anomalyRiskClient) {
        this.ldapDirectory = ldapDirectory;
        this.clientRegistry = clientRegistry;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenService = refreshTokenService;
        this.jwtProperties = jwtProperties;
        this.eventPublisher = eventPublisher;
        this.loginAttemptService = loginAttemptService;
        this.anomalyRiskClient = anomalyRiskClient;
    }

    public LoginResponse login(LoginRequest request, String ipAddress, String userAgent) {
        try {
            return doLogin(request, ipAddress, userAgent);
        } catch (AccountLockedException ex) {
            // Capa 1 (ventana deslizante) disparada: mismo error que todo lo
            // demás. El detalle queda en el log de seguridad, no en la respuesta.
            securityLog.warn("Login bloqueado por ventana deslizante: usuario={} ip={}",
                    request.username(), ipAddress);
            throw new BadCredentialsException(ClientRegistry.GENERIC_ERROR_MESSAGE);
        } catch (org.springframework.ldap.NamingException ex) {
            // Fallas LDAP runtime (bind inválido, directorio caído): al
            // atacante el error genérico; la causa real va al log.
            log.error("Falla LDAP durante login de '{}'", request.username(), ex);
            throw new BadCredentialsException(ClientRegistry.GENERIC_ERROR_MESSAGE);
        } catch (NamingException ex) {
            // LDAP caído o problema de red: al atacante se le muestra el error
            // genérico; la causa real va al log para operación.
            log.error("Falla LDAP durante login de '{}'", request.username(), ex);
            throw new BadCredentialsException(ClientRegistry.GENERIC_ERROR_MESSAGE);
        }
    }

    private LoginResponse doLogin(LoginRequest request, String ipAddress, String userAgent) throws NamingException {
        CitypassProperties.Client client = clientRegistry.requireHuman(request.clientId());
        loginAttemptService.assertNotLocked(request.username());

        // Paso 1: contraseña vacía NUNCA llega a LDAP.
        if (request.password() == null || request.password().isEmpty()) {
            loginAttemptService.recordAttempt(request.username(), ipAddress, userAgent, false);
            throw new BadCredentialsException(ClientRegistry.GENERIC_ERROR_MESSAGE);
        }

        LdapDirectoryPerson person = ldapDirectory.findByUid(request.username()).orElse(null);

        if (person == null) {
            // Emparejamiento de tiempos: un usuario inexistente cuesta lo mismo
            // que uno existente con contraseña mala (no se enumera con cronómetro).
            ldapDirectory.dummyBind(request.password());
            loginAttemptService.recordAttempt(request.username(), ipAddress, userAgent, false);
            throw new BadCredentialsException(ClientRegistry.GENERIC_ERROR_MESSAGE);
        }

        // Paso 4: chequeo de módulo contra el cliente.
        if (!clientRegistry.acceptsModule(client, person.module())) {
            securityLog.warn("Módulo incompatible: usuario={} módulo={} client_id={}",
                    request.username(), person.module(), request.clientId());
            loginAttemptService.recordAttempt(request.username(), ipAddress, userAgent, false);
            throw new BadCredentialsException(ClientRegistry.GENERIC_ERROR_MESSAGE);
        }

        // Paso 5: bind con el DN exacto que devolvió la búsqueda global.
        // Spring LDAP envuelve los errores JNDI en excepciones RUNTIME
        // (org.springframework.ldap.*): hay que capturarlas también, si no,
        // un bind inválido llega al handler genérico como 500 y el intento
        // fallido no se registra (ventana deslizante muerta).
        try {
            ldapDirectory.bind(person.dn(), request.password());
        } catch (NamingException ex) {
            loginAttemptService.recordAttempt(request.username(), ipAddress, userAgent, false);
            throw ex;
        } catch (org.springframework.ldap.NamingException ex) {
            loginAttemptService.recordAttempt(request.username(), ipAddress, userAgent, false);
            throw ex;
        }

        // Capa 2: consulta al microservicio de detección de anomalías.
        // Corre DESPUÉS de la Capa 1 (bloqueo por umbral) y del login LDAP exitoso.
        var riskAssessment = anomalyRiskClient.score(
                request.username(), ipAddress, userAgent
        );
        if ("BLOCK".equals(riskAssessment.decision())) {
            // La razón del bloqueo NO sale en la respuesta (mismo error que todo).
            securityLog.warn("Login rechazado por anomalías: usuario={} razones={}",
                    request.username(), riskAssessment.reasons());
            loginAttemptService.recordAttempt(request.username(), ipAddress, userAgent, false);
            throw new BadCredentialsException(ClientRegistry.GENERIC_ERROR_MESSAGE);
        }
        // TODO: si decision == "REVIEW", marcar el LoginAttempt para auditoría
        // (requiere agregar una columna tipo `flagged_for_review` a login_attempts).

        loginAttemptService.recordAttempt(request.username(), ipAddress, userAgent, true);

        String accessToken = accessTokenIssuer.issueHuman(person, client);
        String refreshToken = refreshTokenService.issueInitial(person, client);

        eventPublisher.publish(
                "usuario.autenticado",
                UsuarioAutenticadoEvent.of(person.sub(), person.uid(), person.module(), person.groups())
        );

        return response(accessToken, refreshToken);
    }

    /**
     * Canje de refresh por un par nuevo. REVALIDA CONTRA LDAP EN CADA CANJE
     * (spec §4.2, regla innegociable #1): releer habilitación y grupos
     * actuales, jamás copiar los grupos guardados al momento del login — si
     * se copiaran, alguien deshabilitado o degradado seguiría recibiendo
     * tokens frescos con permisos viejos durante horas, sin que nada falle.
     */
    public LoginResponse refresh(RefreshRequest request) {
        RefreshTokenService.ChainContinuation continuation =
                refreshTokenService.continueChain(request.refreshToken());

        String accessToken = accessTokenIssuer.issueHuman(continuation.person(), continuation.client());
        String newRefreshToken = refreshTokenService.issueNext(
                continuation.person(), continuation.chainId(), continuation.client());

        return response(accessToken, newRefreshToken);
    }

    /** Logout por refresh_token (contrato público): persiste la revocación. */
    public void logout(String refreshToken) {
        refreshTokenService.revokeSingle(refreshToken);
    }

    private LoginResponse response(String accessToken, String refreshToken) {
        return new LoginResponse(
                accessToken,
                refreshToken,
                "Bearer",
                jwtProperties.accessTokenExpirationMinutes() * 60
        );
    }
}
