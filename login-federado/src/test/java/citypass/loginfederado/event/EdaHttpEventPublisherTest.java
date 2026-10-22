package citypass.loginfederado.event;

import citypass.loginfederado.config.EdaProperties;
import citypass.loginfederado.metrics.RawAuthenticationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class EdaHttpEventPublisherTest {

    private static final String GATEWAY_URL = "http://eda-gateway:8080";
    private static final String TOKEN_URL = "http://auth-service:8081";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private EdaProperties properties() {
        return new EdaProperties(
                GATEWAY_URL,
                TOKEN_URL,
                "/oauth/token",
                "grupo2",
                "secret"
        );
    }

    private EdaHttpEventPublisher createPublisher(
            RestClient.Builder builder
    ) {
        return new EdaHttpEventPublisher(
                builder,
                properties(),
                new EdaEventPayloadMapper(objectMapper)
        );
    }

    private void expectServiceToken(MockRestServiceServer server) {
        String credentials = Base64.getEncoder().encodeToString(
                "grupo2:secret".getBytes(StandardCharsets.UTF_8)
        );

        server.expect(requestTo(TOKEN_URL + "/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic " + credentials))
                .andExpect(content().string("grant_type=client_credentials"))
                .andRespond(withSuccess(
                        "{\"access_token\":\"test-service-token\","
                                + "\"expires_in\":900}",
                        MediaType.APPLICATION_JSON
                ));
    }

    @Test
    void enabledRequiresGatewayUrl() {
        assertThat(properties().enabled()).isTrue();

        EdaProperties disabled = new EdaProperties(
                "",
                TOKEN_URL,
                "/oauth/token",
                "grupo2",
                "secret"
        );

        assertThat(disabled.enabled()).isFalse();
    }

    @Test
    void publishesLoginEventToCorrectEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();

        EdaHttpEventPublisher publisher = createPublisher(builder);

        RawAuthenticationEvent event = RawAuthenticationEvent.login(
                "U000001",
                "jperez",
                "reclamos",
                "citypass-reclamos-web",
                "192.168.1.25",
                "Chrome-Windows"
        );

        expectServiceToken(server);

        server.expect(requestTo(
                        GATEWAY_URL
                                + "/api/v1/event-types/"
                                + "com.citypass.auth.LoginExitoso/events"
                ))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(
                        "Authorization",
                        "Bearer test-service-token"
                ))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.actorSub").value("U000001"))
                .andExpect(jsonPath("$.username").value("jperez"))
                .andExpect(jsonPath("$.department").value("reclamos"))
                .andExpect(jsonPath("$.authEventId").value(event.eventId()))
                .andExpect(jsonPath("$.metadata").doesNotExist())
                .andExpect(jsonPath("$.type").doesNotExist())
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        publisher.publish("identidad.login", event);

        server.verify();
    }

    @Test
    void publishesRefreshAndLogoutUsingCachedToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();

        EdaHttpEventPublisher publisher = createPublisher(builder);

        UUID chainId = UUID.randomUUID();

        RawAuthenticationEvent refresh = RawAuthenticationEvent.refresh(
                "U000001",
                "jperez",
                "reclamos",
                "citypass-reclamos-web",
                chainId,
                "192.168.1.25",
                "Chrome-Windows"
        );

        RawAuthenticationEvent logout = RawAuthenticationEvent.logout(
                "U000001",
                "reclamos",
                "citypass-reclamos-web",
                chainId,
                "192.168.1.25",
                "Chrome-Windows"
        );

        // The OAuth endpoint should be called only once.
        expectServiceToken(server);

        server.expect(requestTo(
                        GATEWAY_URL
                                + "/api/v1/event-types/"
                                + "com.citypass.auth.TokenRenovado/events"
                ))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.chainId").value(chainId.toString()))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        server.expect(requestTo(
                        GATEWAY_URL
                                + "/api/v1/event-types/"
                                + "com.citypass.auth.SesionCerrada/events"
                ))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.actorSub").value("U000001"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        publisher.publish("identidad.refresh", refresh);
        publisher.publish("identidad.logout", logout);

        server.verify();
    }

    @Test
    void rejectsUnsupportedPayload() {
        EdaHttpEventPublisher publisher =
                createPublisher(RestClient.builder());

        assertThatThrownBy(() ->
                publisher.publish("identidad.login", "invalid-payload")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RawAuthenticationEvent");
    }

    @Test
    void rejectsMismatchedEventType() {
        EdaHttpEventPublisher publisher =
                createPublisher(RestClient.builder());

        RawAuthenticationEvent event = RawAuthenticationEvent.login(
                "U000001",
                "jperez",
                "reclamos",
                "citypass-reclamos-web",
                "127.0.0.1",
                "Chrome"
        );

        assertThatThrownBy(() ->
                publisher.publish("identidad.logout", event)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }
}
