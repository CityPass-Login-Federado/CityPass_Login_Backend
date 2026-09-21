package citypass.loginfederado.event;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;

import citypass.loginfederado.config.EdaProperties;

class EdaHttpEventPublisherTest {

    @Test
    void enabledRequiresGatewayUrl() {
        assertThat(new EdaProperties(
                "https://gateway.example.com",
                "https://gateway.example.com",
                "/api/events",
                "/oauth/token",
                "client",
                "secret").enabled()).isTrue();
        assertThat(new EdaProperties(
                null,
                "https://gateway.example.com",
                "/api/events",
                "/oauth/token",
                "client",
                "secret").enabled()).isFalse();
    }

    @Test
    void parsesServiceTokenClaimsAndExpiresAt() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String token = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJzZXJ2aWNlIiwibmFtZXNwYWNlIjoiY29tLmNpdHlwYXNzLmF1dGgiLCJqdGkiOiJ0b2tlbi0xIn0.dummy-signature";

        Class<?> serviceTokenClass = Arrays.stream(EdaHttpEventPublisher.class.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals("ServiceToken"))
                .findFirst()
                .orElseThrow();
        Method parse = serviceTokenClass.getDeclaredMethod("parse", String.class, long.class, ObjectMapper.class);
        parse.setAccessible(true);

        Object parsed = parse.invoke(null, token, 300L, objectMapper);
        assertThat(serviceTokenClass.getMethod("value").invoke(parsed)).isEqualTo(token);
        assertThat(serviceTokenClass.getMethod("source").invoke(parsed)).isEqualTo("service");
        assertThat(serviceTokenClass.getMethod("namespace").invoke(parsed)).isEqualTo("com.citypass.auth");
        assertThat(serviceTokenClass.getMethod("jti").invoke(parsed)).isEqualTo("token-1");
        assertThat(serviceTokenClass.getMethod("expiresAt").invoke(parsed)).isNotNull();
    }

    @Test
    void publishesEventUsingCachedServiceToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EdaProperties properties = new EdaProperties(
                "https://gateway.example.com",
                "https://gateway.example.com",
                "/api/events",
                "/oauth/token",
                "client",
                "secret");
        EdaHttpEventPublisher publisher = new EdaHttpEventPublisher(
                builder,
                new ObjectMapper(),
                properties,
                new EdaEventEnvelopeFactory(new ObjectMapper()));

        String token = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJzZXJ2aWNlIiwibmFtZXNwYWNlIjoiY29tLmNpdHlwYXNzLmF1dGgiLCJqdGkiOiJ0b2tlbi0xIn0.dummy-signature";
        String expectedBasic = Base64.getEncoder().encodeToString("client:secret".getBytes(StandardCharsets.UTF_8));

        server.expect(requestTo("https://gateway.example.com/oauth/token"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Basic " + expectedBasic))
                .andRespond(withSuccess("{\"access_token\":\"" + token + "\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://gateway.example.com/api/events"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", startsWith("Bearer ")))
                .andRespond(withSuccess());
        server.expect(requestTo("https://gateway.example.com/api/events"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", startsWith("Bearer ")))
                .andRespond(withSuccess());

        publisher.publish("user.login", Map.of("userSub", "u-1", "occurredAt", "2025-01-01T00:00:00Z"));
        publisher.publish("user.login", Map.of("userSub", "u-2", "occurredAt", "2025-01-01T00:00:00Z"));

        server.verify();
    }

    @Test
    void rejectsInvalidServiceToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EdaProperties properties = new EdaProperties(
                "https://gateway.example.com",
                "https://gateway.example.com",
                "/api/events",
                "/oauth/token",
                "client",
                "secret");
        EdaHttpEventPublisher publisher = new EdaHttpEventPublisher(
                builder,
                new ObjectMapper(),
                properties,
                new EdaEventEnvelopeFactory(new ObjectMapper()));

        server.expect(requestTo("https://gateway.example.com/oauth/token"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"not-a-jwt\",\"expires_in\":300}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> publisher.publish("user.login", Map.of("userSub", "u-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Token de servicio invalido");

        server.verify();
    }
}
