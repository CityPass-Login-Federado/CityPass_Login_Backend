package citypass.loginfederado.analytics;

import citypass.loginfederado.panel.PanelAuthorization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsControllerTest {

    private AnalyticsClient client;
    private PanelAuthorization authorization;
    private AnalyticsController controller;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        client = mock(AnalyticsClient.class);
        authorization = mock(PanelAuthorization.class);
        controller = new AnalyticsController(client, authorization);
        jwt = mock(Jwt.class);
    }

    @Test
    void returnsUnmodifiedBodyAndOnlyContractHeaders() {
        byte[] html = "<html>á</html>".getBytes(StandardCharsets.UTF_8);
        HttpHeaders upstreamHeaders = new HttpHeaders();
        upstreamHeaders.setContentType(MediaType.parseMediaType("text/html;charset=UTF-8"));
        upstreamHeaders.setCacheControl("no-store");
        upstreamHeaders.set("Content-Security-Policy", "default-src 'none'");
        upstreamHeaders.set("X-Content-Type-Options", "nosniff");
        upstreamHeaders.set("Referrer-Policy", "no-referrer");
        upstreamHeaders.set("Set-Cookie", "must-not-be-forwarded=true");

        when(authorization.requireDelegate(jwt)).thenReturn(
                new PanelAuthorization.Delegate("U1", "admin", "", true));
        when(jwt.getTokenValue()).thenReturn("signed-human-token");
        when(client.fetchEmbed("signed-human-token")).thenReturn(
                new AnalyticsProxyResponse(HttpStatus.OK, upstreamHeaders, html));

        var response = controller.analytics(jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(html);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.parseMediaType("text/html;charset=UTF-8"));
        assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
                .isEqualTo("default-src 'none'");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options"))
                .isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Referrer-Policy"))
                .isEqualTo("no-referrer");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders()).doesNotContainKey("Set-Cookie");
    }

    @Test
    void addsNoStoreWhenUpstreamOmitsCacheControl() {
        when(authorization.requireDelegate(jwt)).thenReturn(
                new PanelAuthorization.Delegate("U1", "admin", "", true));
        when(jwt.getTokenValue()).thenReturn("token");
        when(client.fetchEmbed("token")).thenReturn(
                new AnalyticsProxyResponse(HttpStatus.OK, new HttpHeaders(), new byte[0]));

        var response = controller.analytics(jwt);

        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void rejectsNonGlobalDelegateBeforeCallingAnalytics() {
        when(authorization.requireDelegate(jwt)).thenReturn(
                new PanelAuthorization.Delegate("U1", "delegate", "reclamos", false));

        assertThatThrownBy(() -> controller.analytics(jwt))
                .isInstanceOf(AccessDeniedException.class);
        verify(client, never()).fetchEmbed(org.mockito.ArgumentMatchers.anyString());
    }
}
