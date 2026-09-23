package citypass.loginfederado.service;

import citypass.loginfederado.config.PasswordResetProperties;
import citypass.loginfederado.dto.MeResponse;
import citypass.loginfederado.identity.LdapDirectory;
import citypass.loginfederado.identity.LdapDirectoryPerson;
import citypass.loginfederado.model.PasswordResetToken;
import citypass.loginfederado.panel.PanelAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Self-service de contraseñas (olvidé mi clave + cambio desde el perfil).
 *
 * - requestPasswordReset: NO escribe en LDAP. Emite un token aleatorio de un
 *   solo uso, persiste SOLO su hash SHA-256 (un activo por cuenta) y encola
 *   el mail con el enlace. Exista o no el usuario, haya o no mail, falle el
 *   SMTP o limite el freno: el endpoint responde 204 igual, no se puede
 *   enumerar el directorio con este flujo. Como el LDAP no se toca al pedir,
 *   un fallo de mail jamás deja al usuario sin su contraseña vigente.
 * - redeemResetToken: canjea el token ( landed del enlace) junto a la NUEVA
 *   contraseña. Recién acá se escribe LDAP — y al hacerlo se revocan TODAS
 *   las sesiones (refresh tokens), igual que el cambio desde perfil.
 * - changePassword: relee la ficha por sub (employeeNumber del JWT), pide la
 *   clave ACTUAL como segundo factor, la verifica por bind contra LDAP y
 *   recién ahí escribe la nueva, revocando sesiones.
 *
 * Anti-timing: la respuesta 204 sale tras un camino uniforme corto
 * (normalizar → limiter → lookup LDAP → generar token + hashear, SIEMPRE
 * incluso para usuarios inexistentes con un token ficticio). El trabajo
 * real y lento (persistir + SMTP de segundos) corre async después de
 * responder. Nada de lo que tarda distinto según exista la cuenta ocurre
 * antes del 204.
 */
@Service
public class PasswordService {

    private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");
    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    private static final int RESET_TOKEN_BYTE_LENGTH = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final LdapDirectory ldapDirectory;
    private final PanelAccountService directory;
    private final PasswordEmailService emailSender;
    private final RefreshTokenService refreshTokenService;
    private final PasswordResetLimiter limiter;
    private final PasswordResetTokenStore tokenStore;
    private final PasswordResetProperties properties;
    private final Executor resetExecutor;

    public PasswordService(LdapDirectory ldapDirectory,
                           PanelAccountService directory,
                           PasswordEmailService emailSender,
                           RefreshTokenService refreshTokenService,
                           PasswordResetLimiter limiter,
                           PasswordResetTokenStore tokenStore,
                           PasswordResetProperties properties,
                           @Qualifier("passwordResetExecutor") Executor resetExecutor) {
        this.ldapDirectory = ldapDirectory;
        this.directory = directory;
        this.emailSender = emailSender;
        this.refreshTokenService = refreshTokenService;
        this.limiter = limiter;
        this.tokenStore = tokenStore;
        this.properties = properties;
        this.resetExecutor = resetExecutor;
    }

    /**
     * Solicitud de recupero. Nunca lanza: el usuario pida lo que pida, haya
     * o no cuenta, falle lo que falle, el resultado visible es el mismo 204.
     * La causa real va al log, nunca al cliente.
     */
    public void requestPasswordReset(String uid, String ipAddress) {
        try {
            doRequestPasswordReset(uid, ipAddress);
        } catch (Exception ex) {
            // Contrato: SIEMPRE 204. Filtrar un 500/422 permitiría enumerar
            // el directorio por diferencia de respuestas.
            log.error("Recupero de contraseña fallido para '{}': se responde 204 igual",
                    uid, ex);
        }
    }

    private void doRequestPasswordReset(String uid, String ipAddress) {
        String key = uid == null ? "" : uid.trim();

        // Freno anti-abuso (cooldown por cuenta + topes por cuenta e IP).
        // Rechazado ⇒ 204 igual: el límite no revela si la cuenta existe.
        if (!limiter.tryAcquire(key, ipAddress)) {
            return;
        }

        Optional<LdapDirectoryPerson> found = ldapDirectory.findByUid(key);
        if (found.isEmpty() || found.get() == null
                || found.get().email() == null || found.get().email().isBlank()) {
            // Usuario inexistente, deshabilitado, ficha corrupta o sin mail:
            // trabajo ficticio comparable (generar + hashear) para no
            // distinguir por timing, y mismo silencio que el éxito.
            hashToken(generateRawToken());
            if (found.isPresent() && found.get() != null) {
                securityLog.warn("Recupero de contraseña omitido para sub={}: sin mail configurado",
                        found.get().sub());
            }
            return;
        }

        LdapDirectoryPerson person = found.get();
        String rawToken = generateRawToken();
        String tokenHash = hashToken(rawToken);

        // Reserva atómica del único token activo (borra el anterior, si hay).
        // LDAP sigue intacto: si todo lo demás falla, la clave vigente vale.
        tokenStore.issue(person.sub(), person.uid(), tokenHash, properties.tokenTtlMinutes());
        securityLog.info("Token de recupero emitido para sub={} (uid={})", person.sub(), person.uid());

        String resetLink = properties.resetLinkBase()
                + "?token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);

        // El envío (lento, segundos en SMTP real) corre DESPUÉS de responder.
        resetExecutor.execute(() -> deliverResetLink(person, resetLink));
    }

    /**
     * Entrega async del enlace. Si no se pudo notificar, se libera el token
     * pendiente para que el usuario pueda reintentar de inmediato — y como
     * LDAP nunca se tocó, su contraseña actual sigue funcionando.
     */
    private void deliverResetLink(LdapDirectoryPerson person, String resetLink) {
        try {
            boolean notified = emailSender.sendResetLink(person.email(), person.uid(), resetLink);
            if (!notified) {
                tokenStore.discard(person.sub());
                securityLog.warn("Enlace de recupero no entregado a sub={}: token liberado para reintento",
                        person.sub());
            }
        } catch (Exception ex) {
            log.error("Fallo async de recupero para sub={}: se libera el token", person.sub(), ex);
            try {
                tokenStore.discard(person.sub());
            } catch (Exception inner) {
                log.error("No se pudo liberar el token pendiente de sub={}", person.sub(), inner);
            }
        }
    }

    /**
     * Canje del token: define la nueva contraseña. Token inválido, usado o
     * vencido, cuenta borrada o deshabilitada entre la solicitud y el canje:
     * el MISMO 422 genérico (los tokens son aleatorios de 256 bits, no hay
     * oráculo de enumeración posible).
     */
    public void redeemResetToken(String rawToken, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("La nueva contraseña debe tener al menos 8 caracteres");
        }

        String tokenHash = (rawToken == null || rawToken.isBlank()) ? "" : hashToken(rawToken.trim());
        Optional<PasswordResetToken> stored = tokenHash.isEmpty()
                ? Optional.empty()
                : tokenStore.findByHash(tokenHash);
        if (stored.isEmpty() || !stored.get().isUsable()) {
            rejectInvalidToken();
        }
        PasswordResetToken token = stored.get();

        // La cuenta pudo borrarse o deshabilitarse DESPUÉS de pedir el token:
        // se revalida contra LDAP igual que en cada canje de refresh.
        LdapDirectoryPerson person = ldapDirectory.reloadBySub(token.getSub())
                .orElseThrow(PasswordService::invalidToken);

        // Consumo atómico: si otro hilo ganó el canje, este pierde y el
        // token ya no sirve (single-use de verdad, aun concurrente).
        if (!tokenStore.consumeIfUsable(tokenHash, Instant.now())) {
            rejectInvalidToken();
        }

        // Recién ACÁ se escribe LDAP — y al hacerlo mueren todas las
        // sesiones vigentes: quien tuviera un refresh anterior al recupero
        // no puede seguir refrescando (mismo orden que changePassword).
        directory.setPassword(person.module(), person.uid(), newPassword);
        int revoked = refreshTokenService.revokeAllForSub(person.sub());
        securityLog.info("Contraseña restablecida vía token para sub={} (sesiones revocadas={})",
                person.sub(), revoked);
    }

    private static IllegalArgumentException invalidToken() {
        return new IllegalArgumentException("El enlace es inválido o expiró");
    }

    /**
     * Rechazo uniforme del canje inválido. El dummyBind quema un round-trip
     * LDAP comparable al reloadBySub del camino válido, para no distinguir
     * "token inexistente" de "token válido de cuenta borrada" por timing.
     */
    private void rejectInvalidToken() {
        try {
            ldapDirectory.dummyBind("reset-token-rejected");
        } catch (Exception ignored) {
            // El dummy nunca debe romper el rechazo uniforme.
        }
        throw invalidToken();
    }

    /**
     * Perfil propio: relee la ficha por sub (employeeNumber del JWT) directo
     * del directorio. Si la cuenta se borró o deshabilitó después de emitir
     * el token, 404 (el token por sí solo ya no alcanza para nada).
     */
    public MeResponse getProfile(String sub) {
        LdapDirectoryPerson person = ldapDirectory.reloadBySub(sub)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Su cuenta ya no existe o está deshabilitada"));
        return new MeResponse(person.uid(), person.sub(), person.fullName(),
                person.email(), person.module(), person.groups());
    }

    /**
     * Cambio de contraseña desde el perfil. La clave actual se valida por bind
     * contra LDAP: un JWT robado sin la clave vigente no alcanza.
     */
    public void changePassword(String sub, String currentPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("La nueva contraseña debe tener al menos 8 caracteres");
        }

        LdapDirectoryPerson person = ldapDirectory.reloadBySub(sub)
                .orElseThrow(() -> new IllegalArgumentException("Su cuenta ya no existe o está deshabilitada"));

        try {
            ldapDirectory.bind(person.dn(), currentPassword);
        } catch (javax.naming.NamingException | org.springframework.ldap.NamingException ex) {
            // Bind inválido = clave errónea. Spring LDAP envuelve los errores
            // JNDI como runtime (org.springframework.ldap.*): hay que cubrir
            // ambos árboles, igual que hace AuthService.
            throw new IllegalArgumentException("La contraseña actual es incorrecta");
        }

        directory.setPassword(person.module(), person.uid(), newPassword);
        int revoked = refreshTokenService.revokeAllForSub(person.sub());
        securityLog.info("Contraseña cambiada por el usuario sub={} (sesiones revocadas={})",
                person.sub(), revoked);
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[RESET_TOKEN_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Solo el hash viaja a la base; el crudo va UNA vez en el enlace. */
    private static String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
