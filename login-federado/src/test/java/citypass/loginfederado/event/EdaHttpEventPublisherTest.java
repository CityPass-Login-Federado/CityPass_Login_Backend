package citypass.loginfederado.event;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

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
        String token = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJzZXJ2aWNlIiwibmFtZXNwYWNlIjoiY29tLmNpdHlwYXNzLmF1dGgiLCJqdGkiOiJ0b2tlbi0xIn0.signature";

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
}
