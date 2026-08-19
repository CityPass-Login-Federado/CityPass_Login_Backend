package citypass.loginfederado.security;

import citypass.loginfederado.dto.AnomalyScoreRequest;
import citypass.loginfederado.dto.AnomalyScoreResponse;
import citypass.loginfederado.exception.AnomalyServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;

@Component
public class AnomalyRiskClient {

    private static final Logger log = LoggerFactory.getLogger(AnomalyRiskClient.class);

    private final RestClient restClient;

    public AnomalyRiskClient(@Value("${anomaly.service.url}") String baseUrl) {
        var settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(3));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    public AnomalyScoreResponse score(String username, String ip, String userAgent) {
        AnomalyScoreRequest request = new AnomalyScoreRequest(
                username, ip, userAgent, Instant.now().toString(), true
        );

        try {
            return restClient.post()
                    .uri("/score")
                    .body(request)
                    .retrieve()
                    .body(AnomalyScoreResponse.class);
        } catch (RestClientException ex) {
            // Fail-closed: si no se puede contactar/consultar al servicio de IA,
            // NO se asume ALLOW por defecto — se rechaza el login.
            log.error("Fallo al consultar el servicio de anomalías", ex);
            throw new AnomalyServiceUnavailableException(
                    "No se pudo evaluar el riesgo del login (servicio de anomalías no disponible)", ex
            );
        }
    }
}