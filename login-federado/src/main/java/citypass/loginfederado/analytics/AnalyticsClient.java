package citypass.loginfederado.analytics;

import citypass.loginfederado.config.AnalyticsProperties;
import citypass.loginfederado.exception.AnalyticsInvalidResponseException;
import citypass.loginfederado.exception.AnalyticsServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;

@Component
public class AnalyticsClient {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsClient.class);

    private final RestClient restClient;
    private final AnalyticsProperties properties;

    @Autowired
    public AnalyticsClient(RestClient.Builder builder, AnalyticsProperties properties) {
        var settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
        this.properties = properties;
    }

    AnalyticsClient(RestClient restClient, AnalyticsProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public AnalyticsProxyResponse fetchEmbed(String accessToken) {
        try {
            return restClient.get()
                    .uri(properties.embedPath())
                    .accept(MediaType.TEXT_HTML)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        HttpHeaders headers = new HttpHeaders(response.getHeaders());
                        byte[] body = StreamUtils.copyToByteArray(response.getBody());

                        if (status.is2xxSuccessful()
                                && (!hasValidHtmlContentType(headers) || isEmpty(body))) {
                            log.warn("Respuesta exitosa inválida de Analítica: status={}, contentType={}",
                                    status.value(), headers.getFirst(HttpHeaders.CONTENT_TYPE));
                            throw new AnalyticsInvalidResponseException(
                                    "Analítica devolvió una respuesta exitosa inválida");
                        }

                        return new AnalyticsProxyResponse(status, headers, body);
                    });
        } catch (RestClientException ex) {
            log.warn("Falla de comunicación con Analítica: {}", ex.getClass().getSimpleName());
            throw new AnalyticsServiceUnavailableException(
                    "El servicio de Analítica no está disponible", ex);
        }
    }

    private static boolean hasValidHtmlContentType(HttpHeaders headers) {
        try {
            MediaType contentType = headers.getContentType();
            if (contentType == null
                    || !"text".equalsIgnoreCase(contentType.getType())
                    || !"html".equalsIgnoreCase(contentType.getSubtype())) {
                return false;
            }
            return contentType.getCharset() == null
                    || StandardCharsets.UTF_8.equals(contentType.getCharset());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean isEmpty(byte[] body) {
        if (body.length == 0) {
            return true;
        }
        for (byte value : body) {
            if (value != ' ' && value != '\t' && value != '\r'
                    && value != '\n' && value != '\f') {
                return false;
            }
        }
        return true;
    }
}
