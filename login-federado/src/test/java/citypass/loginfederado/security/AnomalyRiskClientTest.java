package citypass.loginfederado.security;

import citypass.loginfederado.exception.AnomalyServiceUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.*;

class AnomalyRiskClientTest {
    private HttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void returnsRiskAssessmentFromService() {
        server.createContext("/score", exchange -> {
            byte[] body = "{\"risk_score\":0.12,\"decision\":\"ALLOW\",\"reasons\":[]}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        var client = new AnomalyRiskClient("http://localhost:" + server.getAddress().getPort());
        var result = client.score("jperez", "127.0.0.1", "JUnit");
        assertThat(result.decision()).isEqualTo("ALLOW");
        assertThat(result.riskScore()).isEqualTo(0.12);
    }

    @Test
    void failsClosedWhenServiceIsUnavailable() {
        var client = new AnomalyRiskClient("http://localhost:" + server.getAddress().getPort());
        assertThatThrownBy(() -> client.score("jperez", "127.0.0.1", "JUnit"))
                .isInstanceOf(AnomalyServiceUnavailableException.class);
    }
}
