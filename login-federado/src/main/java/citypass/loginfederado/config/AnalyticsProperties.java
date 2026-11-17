package citypass.loginfederado.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/** Configuración del proxy autenticado hacia el HTML de Analítica. */
@ConfigurationProperties(prefix = "analytics")
public record AnalyticsProperties(
        String baseUrl,
        String embedPath,
        Duration connectTimeout,
        Duration readTimeout
) {
    private static final String DEFAULT_BASE_URL =
            "https://citypass-backend-prod-becqetepcjbqa8c7.brazilsouth-01.azurewebsites.net";
    private static final String DEFAULT_EMBED_PATH = "/embed/identidad";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(15);

    public AnalyticsProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        URI baseUri;
        try {
            baseUri = URI.create(baseUrl);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("analytics.base-url no es una URL base válida", ex);
        }
        if (!"https".equalsIgnoreCase(baseUri.getScheme())) {
            throw new IllegalArgumentException("analytics.base-url debe utilizar HTTPS");
        }
        if (baseUri.getHost() == null || baseUri.getUserInfo() != null
                || baseUri.getQuery() != null || baseUri.getFragment() != null) {
            throw new IllegalArgumentException("analytics.base-url no es una URL base válida");
        }
        baseUrl = baseUrl.replaceAll("/+$", "");

        if (embedPath == null || embedPath.isBlank()) {
            embedPath = DEFAULT_EMBED_PATH;
        }
        if (!embedPath.startsWith("/") || embedPath.startsWith("//")
                || embedPath.contains("://") || embedPath.contains("?")
                || embedPath.contains("#") || embedPath.contains("\\")
                || embedPath.chars().anyMatch(Character::isWhitespace)
                || containsDotSegment(embedPath)) {
            throw new IllegalArgumentException("analytics.embed-path debe ser una ruta absoluta sin query");
        }

        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            readTimeout = DEFAULT_READ_TIMEOUT;
        }
    }

    private static boolean containsDotSegment(String path) {
        String lowerCasePath = path.toLowerCase(Locale.ROOT);
        return lowerCasePath.equals("/.") || lowerCasePath.equals("/..")
                || lowerCasePath.startsWith("/./") || lowerCasePath.startsWith("/../")
                || lowerCasePath.endsWith("/.") || lowerCasePath.endsWith("/..")
                || lowerCasePath.contains("/./") || lowerCasePath.contains("/../");
    }
}
