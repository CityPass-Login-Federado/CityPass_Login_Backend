package citypass.loginfederado.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración del recupero de contraseña por token de un solo uso
 * (prefijo "app.password-reset").
 *
 * - from: remitente del mail con el enlace.
 * - debug-log: cuando NO hay SMTP configurado (desarrollo), el enlace de
 *   recupero se loguea en consola para no quedar bloqueado sin verlo. En
 *   producción debe ir en false: sin SMTP, el recupero simplemente no
 *   notifica y el usuario vuelve a intentar (el token queda pendiente hasta
 *   su TTL y NO se toca LDAP).
 * - reset-link-base: base del enlace que viaja en el mail; el token crudo se
 *   agrega como query param `token`. Debe apuntar a la ruta que el frontend
 *   exponga para el formulario de nueva contraseña.
 * - token-ttl-minutes: vida útil del token (un solo uso, expira igual).
 * - cooldown-minutes: espera mínima entre dos solicitudes ACEPTADAS para la
 *   MISMA cuenta (anti-spam de buzón + anti-DoS de credencial).
 * - max-per-account-per-hour: tope de solicitudes aceptadas por cuenta y hora.
 * - max-per-ip-per-hour: tope de solicitudes aceptadas por IP y hora.
 */
@ConfigurationProperties(prefix = "app.password-reset")
public record PasswordResetProperties(
        String from,
        boolean debugLog,
        String resetLinkBase,
        int tokenTtlMinutes,
        int cooldownMinutes,
        int maxPerAccountPerHour,
        int maxPerIpPerHour
) {

    public PasswordResetProperties {
        if (from == null || from.isBlank()) {
            from = "no-reply@citypass.local";
        }
        if (resetLinkBase == null || resetLinkBase.isBlank()) {
            resetLinkBase = "http://localhost:3000/reset-password";
        }
        if (tokenTtlMinutes <= 0) {
            tokenTtlMinutes = 30;
        }
        if (cooldownMinutes <= 0) {
            cooldownMinutes = 5;
        }
        if (maxPerAccountPerHour <= 0) {
            maxPerAccountPerHour = 3;
        }
        if (maxPerIpPerHour <= 0) {
            maxPerIpPerHour = 50;
        }
    }
}
