package ar.edu.uade.citypass.loginfederado.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mapea las propiedades bajo el prefijo "jwt" de application.yml.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String issuer,
        String audience,
        long accessTokenExpirationMinutes,
        long refreshTokenExpirationDays,
        String privateKeyPath,
        String publicKeyPath
) {
}