package citypass.loginfederado.analytics;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

public record AnalyticsProxyResponse(
        HttpStatusCode status,
        HttpHeaders headers,
        byte[] body
) {
}
