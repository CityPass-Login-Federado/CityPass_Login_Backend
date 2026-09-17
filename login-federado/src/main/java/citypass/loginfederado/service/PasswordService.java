package citypass.loginfederado.service;

import citypass.loginfederado.identity.LdapDirectory;
import citypass.loginfederado.identity.LdapDirectoryPerson;
import citypass.loginfederado.panel.PanelDirectoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Optional;

/**
 * Self-service de contraseñas (olvidé mi clave + cambio desde el perfil):
 *
 * - requestTemporaryPassword: busca la ficha GLOBAL por uid, escribe una clave
 *   random en LDAP (panel-writer) y la manda por mail. Si el usuario no existe
 *   (o no tiene mail) NO hace nada: el endpoint responde 204 igual, no se
 *   puede enumerar el directorio con este flujo.
 *
 * - changePassword: relee la ficha por sub (employeeNumber del JWT), pide la
 *   clave ACTUAL como segundo factor, la verifica por bind contra LDAP y recién
 *   ahí escribe la nueva. Al cambiar, se revocan todas las sesiones (refresh
 *   tokens) de la persona.
 */
@Service
public class PasswordService {

    private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");
    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    private static final int TEMP_PASSWORD_LENGTH = 12;
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final LdapDirectory ldapDirectory;
    private final PanelDirectoryService directory;
    private final PasswordEmailService emailSender;
    private final RefreshTokenService refreshTokenService;

    public PasswordService(LdapDirectory ldapDirectory,
                           PanelDirectoryService directory,
                           PasswordEmailService emailSender,
                           RefreshTokenService refreshTokenService) {
        this.ldapDirectory = ldapDirectory;
        this.directory = directory;
        this.emailSender = emailSender;
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * Genera, persiste y notifica una contraseña temporal. Nunca lanza: el
     * usuario pida lo que pida, el resultado que ve es el mismo 204.
     * Cualquier fallo interno (LDAP caído, escritura denegada, ppolicy,
     * entrada corrupta) se loguea y se silencia: filtrarlo como 500/422
     * permitiría enumerar el directorio por diferencia de respuestas.
     */
    public void requestTemporaryPassword(String username) {
        try {
            doRequestTemporaryPassword(username);
        } catch (Exception ex) {
            // Contrato: SIEMPRE 204. La causa real va al log, nunca al cliente.
            log.error("Recupero de contraseña fallido para '{}': se responde 204 igual",
                    username, ex);
        }
    }

    private void doRequestTemporaryPassword(String username) {
        String uid = username == null ? "" : username.trim();
        Optional<LdapDirectoryPerson> found = ldapDirectory.findByUid(uid);
        if (found.isEmpty() || found.get() == null) {
            // Usuario inexistente, deshabilitado o ficha corrupta (mapper
            // devuelve null): mismo silencio que el éxito.
            return;
        }
        LdapDirectoryPerson person = found.get();
        if (person.email() == null || person.email().isBlank()) {
            securityLog.warn("Recupero de contraseña omitido para sub={}: sin mail configurado", person.sub());
            return;
        }

        String temporaryPassword = generateTemporaryPassword();
        directory.setPassword(person.module(), person.uid(), temporaryPassword);
        emailSender.sendTemporaryPassword(person.email(), person.uid(), temporaryPassword);
        securityLog.info("Contraseña temporal asignada a sub={} (uid={})", person.sub(), person.uid());
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

    private static String generateTemporaryPassword() {
        StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(SECURE_RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }
}