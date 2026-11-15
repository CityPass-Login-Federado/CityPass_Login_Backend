package citypass.loginfederado.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Freno por IP de los endpoints públicos de sesión (login/refresh). El lockout
 * de `security.lockout` es por USUARIO: no frena el credential stuffing
 * distribuido (muchas cuentas, una IP o muchas IPs). Este límite cuenta
 * intentos por IP en una ventana deslizante.
 */
@ConfigurationProperties(prefix = "security.login-rate-limit")
public record LoginRateLimitProperties(
        int maxPerIpPerWindow,
        long windowMinutes
) {
}
