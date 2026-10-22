package citypass.loginfederado.event;

import citypass.loginfederado.config.EdaProperties;
import citypass.loginfederado.metrics.RawAuthenticationEvent;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component
@ConditionalOnProperty(
        prefix = "eda",
        name = "publisher",
        havingValue = "http"
)
public class EdaHttpEventPublisher implements EventPublisher {

    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 30;

    private final RestClient restClient;
    private final EdaProperties properties;
    private final EdaEventPayloadMapper payloadMapper;

    private volatile ServiceToken serviceToken;

    public EdaHttpEventPublisher(
            RestClient.Builder restClientBuilder,
            EdaProperties properties,
            EdaEventPayloadMapper payloadMapper
    ) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
        this.payloadMapper = payloadMapper;
    }

    @Override
    public void publish(String eventType, Object payload) {

        // Validate that the payload is an authentication event.
        if (!(payload instanceof RawAuthenticationEvent event)) {
            throw new IllegalArgumentException(
                    "Unsupported EDA payload: expected RawAuthenticationEvent"
            );
        }

        if (!eventType.equals(event.eventType())) {
            throw new IllegalArgumentException(
                    "Event type does not match the payload type"
            );
        }

        // Resolve the registered EDA event type.
        EdaEventType edaEventType =
                EdaEventType.fromInternalType(eventType);

        // Convert the internal event to the EDA business payload.
        ObjectNode data = payloadMapper.toPayload(event);

        // Obtain the OAuth2 service token.
        ServiceToken token = getServiceToken();

        // Build the Event Gateway publication URL.
        String gatewayUrl = properties.gatewayUrl()
                .replaceAll("/+$", "");

        String eventUrl = gatewayUrl
                + "/api/v1/event-types/"
                + edaEventType.fqn()
                + "/events";

        // Publish only business data.
        // The Event Gateway generates the event metadata.
        restClient.post()
                .uri(eventUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .header(
                        "Authorization",
                        "Bearer " + token.value()
                )
                .body(data)
                .retrieve()
                .toBodilessEntity();
    }

    private synchronized ServiceToken getServiceToken() {

        Instant now = Instant.now();

        // Reuse the token while it is still valid.
        if (serviceToken != null
                && serviceToken.expiresAt().isAfter(
                        now.plusSeconds(TOKEN_REFRESH_MARGIN_SECONDS))) {
            return serviceToken;
        }

        // Request a new service token using OAuth2 client_credentials.
        TokenResponse response = restClient.post()
                .uri(properties.tokenUrl() + properties.tokenPath())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers(headers -> headers.setBasicAuth(
                        properties.clientId(),
                        properties.clientSecret(),
                        StandardCharsets.UTF_8
                ))
                .body("grant_type=client_credentials")
                .retrieve()
                .body(TokenResponse.class);

        if (response == null
                || response.accessToken() == null
                || response.accessToken().isBlank()
                || response.expiresIn() <= 0) {

            throw new IllegalStateException(
                    "Invalid OAuth2 service token response"
            );
        }

        // Cache the service token until its expiration.
        serviceToken = new ServiceToken(
                response.accessToken(),
                Instant.now().plusSeconds(response.expiresIn())
        );

        return serviceToken;
    }

    private record TokenResponse(
            @JsonProperty("access_token")
            String accessToken,

            @JsonProperty("expires_in")
            long expiresIn
    ) {
    }

    private record ServiceToken(
            String value,
            Instant expiresAt
    ) {
    }
}
