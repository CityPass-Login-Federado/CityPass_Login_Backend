package citypass.loginfederado.event;

import citypass.loginfederado.config.EdaProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** Publica al event-gateway cuando EDA_GATEWAY_URL esta configurada. */
@Component
@ConditionalOnProperty(prefix = "eda", name = "publisher", havingValue = "http")
public class EdaHttpEventPublisher implements EventPublisher {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final EdaProperties properties;
    private final EdaEventEnvelopeFactory envelopeFactory;
    private volatile ServiceToken serviceToken;

    public EdaHttpEventPublisher(RestClient.Builder restClientBuilder,
                                 ObjectMapper objectMapper,
                                 EdaProperties properties,
                                 EdaEventEnvelopeFactory envelopeFactory) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.envelopeFactory = envelopeFactory;
    }

    @Override
    public void publish(String eventType, Object payload) {
        ServiceToken token = getServiceToken();
        EdaEventEnvelope envelope = envelopeFactory.create(
                eventType, payload, token.source(), token.namespace(), token.jti());
        restClient.post()
                .uri(properties.gatewayUrl() + properties.eventPath())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token.value())
                .body(envelope)
                .retrieve()
                .toBodilessEntity();
    }

    private synchronized ServiceToken getServiceToken() {
        if (serviceToken != null && serviceToken.expiresAt().isAfter(Instant.now().plusSeconds(30))) {
            return serviceToken;
        }
        String credentials = properties.clientId() + ":" + properties.clientSecret();
        String basic = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        TokenResponse response = restClient.post()
                .uri(properties.tokenUrl() + properties.tokenPath())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Authorization", "Basic " + basic)
                .body("grant_type=client_credentials")
                .retrieve()
                .body(TokenResponse.class);
        if (response == null || response.accessToken() == null) {
            throw new IllegalStateException("El event-gateway no devolvio un token de servicio");
        }
        serviceToken = ServiceToken.parse(response.accessToken(), response.expiresIn(), objectMapper);
        return serviceToken;
    }

    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn
    ) {
    }

    private record ServiceToken(String value, String source, String namespace, String jti,
                                Instant expiresAt) {
        static ServiceToken parse(String value, long expiresIn, ObjectMapper objectMapper) {
            try {
                String[] parts = value.split("\\.");
                JsonNode claims = objectMapper.readTree(
                        Base64.getUrlDecoder().decode(parts[1]));
                return new ServiceToken(value, claims.path("sub").asText(),
                        claims.path("namespace").asText(), claims.path("jti").asText(),
                        Instant.now().plusSeconds(expiresIn));
            } catch (Exception ex) {
                throw new IllegalStateException("Token de servicio invalido", ex);
            }
        }
    }
}