package citypass.loginfederado.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mapea las propiedades bajo el prefijo "jwt" de application.yml.
 *
 * El kid NO está acá: se deriva del contenido de la clave (huella RFC 7638).
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String issuer,
        long accessTokenExpirationMinutes,
        long refreshTokenExpirationHours,
        long serviceTokenExpirationMinutes,
        String privateKeyPath,
        String publicKeyPath
) {
}
