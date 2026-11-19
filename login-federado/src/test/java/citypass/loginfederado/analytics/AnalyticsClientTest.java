package citypass.loginfederado.analytics;

import citypass.loginfederado.config.AnalyticsProperties;
import citypass.loginfederado.exception.AnalyticsInvalidResponseException;
import citypass.loginfederado.exception.AnalyticsServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

class AnalyticsClientTest {

    private static final String BASE_URL = "https://analytics.example.com";
    private static final String EMBED_PATH = "/embed/identidad";

    @Test
    void forwardsHumanBearerAndReturnsHtmlBytesAndHeaders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalyticsClient client = new AnalyticsClient(builder.baseUrl(BASE_URL).build(), properties());

        server.expect(requestTo(BASE_URL + EMBED_PATH))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer human-token"))
                .andExpect(header("Accept", MediaType.TEXT_HTML_VALUE))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                        .header("Cache-Control", "no-store")
                        .header("Content-Security-Policy", "default-src 'none'")
                        .body("<html><body>tablero de análisis</body></html>"));

        AnalyticsProxyResponse response = client.fetchEmbed("human-token");

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().getContentType())
                .isEqualTo(MediaType.parseMediaType("text/html;charset=UTF-8"));
        assertThat(response.headers().getFirst("Content-Security-Policy"))
                .isEqualTo("default-src 'none'");
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .isEqualTo("<html><body>tablero de análisis</body></html>");
        server.verify();
    }

    @Test
    void preservesUpstreamUnauthorizedStatusAndBody() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalyticsClient client = new AnalyticsClient(builder.baseUrl(BASE_URL).build(), properties());

        server.expect(requestTo(BASE_URL + EMBED_PATH))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"token inválido\"}"));

        AnalyticsProxyResponse response = client.fetchEmbed("human-token");

        assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .contains("token inválido");
        server.verify();
    }

    @Test
    void preservesUpstreamForbiddenStatusAndBody() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalyticsClient client = new AnalyticsClient(builder.baseUrl(BASE_URL).build(), properties());

        server.expect(requestTo(BASE_URL + EMBED_PATH))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"sin permisos\"}"));

        AnalyticsProxyResponse response = client.fetchEmbed("human-token");

        assertThat(response.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .contains("sin permisos");
        server.verify();
    }

    @Test
    void rejectsSuccessfulNonHtmlResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalyticsClient client = new AnalyticsClient(builder.baseUrl(BASE_URL).build(), properties());

        server.expect(requestTo(BASE_URL + EMBED_PATH))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"ok\"}"));

        assertThatThrownBy(() -> client.fetchEmbed("human-token"))
                .isInstanceOf(AnalyticsInvalidResponseException.class);
        server.verify();
    }

    @Test
    void rejectsSuccessfulHtmlWithNonUtf8Charset() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalyticsClient client = new AnalyticsClient(builder.baseUrl(BASE_URL).build(), properties());

        server.expect(requestTo(BASE_URL + EMBED_PATH))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.parseMediaType("text/html;charset=ISO-8859-1"))
                        .body("<html>tablero</html>"));

        assertThatThrownBy(() -> client.fetchEmbed("human-token"))
                .isInstanceOf(AnalyticsInvalidResponseException.class);
        server.verify();
    }

    @Test
    void rejectsSuccessfulEmptyHtmlBody() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalyticsClient client = new AnalyticsClient(builder.baseUrl(BASE_URL).build(), properties());

        server.expect(requestTo(BASE_URL + EMBED_PATH))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.TEXT_HTML)
                        .body("  \r\n\t"));

        assertThatThrownBy(() -> client.fetchEmbed("human-token"))
                .isInstanceOf(AnalyticsInvalidResponseException.class);
        server.verify();
    }

    @Test
    void convertsConnectionFailureToServiceUnavailable() {
        assertCommunicationFailure(new ConnectException("connection refused"));
    }

    @Test
    void convertsTimeoutToServiceUnavailable() {
        assertCommunicationFailure(new SocketTimeoutException("read timed out"));
    }

    private void assertCommunicationFailure(java.io.IOException failure) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnalyticsClient client = new AnalyticsClient(builder.baseUrl(BASE_URL).build(), properties());

        server.expect(requestTo(BASE_URL + EMBED_PATH))
                .andRespond(withException(failure));

        assertThatThrownBy(() -> client.fetchEmbed("human-token"))
                .isInstanceOf(AnalyticsServiceUnavailableException.class)
                .hasCauseInstanceOf(org.springframework.web.client.ResourceAccessException.class);
        server.verify();
    }

    private AnalyticsProperties properties() {
        return new AnalyticsProperties(
                BASE_URL, EMBED_PATH, Duration.ofSeconds(1), Duration.ofSeconds(2));
    }
}
