package citypass.loginfederado.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración del envío de la contraseña temporal (prefijo "app.password-reset").
 *
 * - from: remitente del mail.
 * - debug-log: cuando NO hay SMTP configurado (desarrollo), la contraseña
 *   temporal se loguea en consola para no quedar bloqueado sin verla. En
 *   producción debe ir en false: sin SMTP, el recupero simplemente no
 *   notifica y el usuario vuelve a intentar.
 */
@ConfigurationProperties(prefix = "app.password-reset")
public record PasswordResetProperties(
        String from,
        boolean debugLog
) {

    public PasswordResetProperties {
        if (from == null || from.isBlank()) {
            from = "no-reply@citypass.local";
        }
    }
}