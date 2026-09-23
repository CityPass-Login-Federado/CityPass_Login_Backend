package citypass.loginfederado.controller;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.http.HttpStatus;

import citypass.loginfederado.config.CitypassProperties;
import citypass.loginfederado.config.JwtProperties;
import citypass.loginfederado.event.EdaOAuthException;
import citypass.loginfederado.identity.ClientRegistry;
import citypass.loginfederado.token.AccessTokenIssuer;
import jakarta.servlet.http.HttpServletRequest;

class OAuthTokenControllerTest {

    private final ClientRegistry clientRegistry = mock(ClientRegistry.class);
    private final AccessTokenIssuer accessTokenIssuer = mock(AccessTokenIssuer.class);
    private final JwtProperties jwtProperties = new JwtProperties("issuer", 15, 8, 60, "k", "k");
    private final OAuthTokenController controller = new OAuthTokenController(clientRegistry, accessTokenIssuer, jwtProperties);

    @Test
    void rejectsUnsupportedGrantType() {
        HttpServletRequest request = mock(HttpServletRequest.class);

        assertThatThrownBy(() -> controller.token("password", request))
                .isInstanceOf(EdaOAuthException.class)
                .satisfies(ex -> {
                    EdaOAuthException error = (EdaOAuthException) ex;
                    assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.error()).isEqualTo("unsupported_grant_type");
                });
    }

    @Test
    void issuesTokenWhenBasicAuthCredentialsAreValid() {
        CitypassProperties.Client client = new CitypassProperties.Client(
                "eda-client",
                null,
                "citypass-eda-api",
                "reclamos",
                false,
                "service",
                null);
        HttpServletRequest request = mock(HttpServletRequest.class);
        String header = "Basic " + Base64.getEncoder().encodeToString("eda-client:secret".getBytes(StandardCharsets.UTF_8));
        when(request.getHeader("Authorization")).thenReturn(header);
        when(clientRegistry.authenticateService("eda-client", "secret")).thenReturn(client);
        when(accessTokenIssuer.issueService(client)).thenReturn("mocked-service-token");

        var response = controller.token("client_credentials", request);

        assertThat(response.accessToken()).isEqualTo("mocked-service-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(60 * 60);
        verify(clientRegistry).authenticateService("eda-client", "secret");
        verify(accessTokenIssuer).issueService(client);
    }

    @Test
    void rejectsInvalidClientCredentials() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        String header = "Basic " + Base64.getEncoder().encodeToString("eda-client:bad".getBytes(StandardCharsets.UTF_8));
        when(request.getHeader("Authorization")).thenReturn(header);
        when(clientRegistry.authenticateService("eda-client", "bad")).thenThrow(new RuntimeException("bad creds"));

        assertThatThrownBy(() -> controller.token("client_credentials", request))
                .isInstanceOf(EdaOAuthException.class)
                .satisfies(ex -> {
                    EdaOAuthException error = (EdaOAuthException) ex;
                    assertThat(error.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(error.error()).isEqualTo("invalid_client");
                });
    }

    @Test
    void acceptsFormParametersWhenBasicHeaderIsMissing() {
        CitypassProperties.Client client = new CitypassProperties.Client(
                "eda-client",
                null,
                "citypass-eda-api",
                "reclamos",
                false,
                "service",
                null);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getParameter("client_id")).thenReturn("eda-client");
        when(request.getParameter("client_secret")).thenReturn("secret");
        when(clientRegistry.authenticateService("eda-client", "secret")).thenReturn(client);
        when(accessTokenIssuer.issueService(client)).thenReturn("form-token");

        var response = controller.token("client_credentials", request);

        assertThat(response.accessToken()).isEqualTo("form-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
    }

    @Test
    void rejectsMissingCredentialsWhenHeaderAndFormAreAbsent() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getParameter("client_id")).thenReturn(null);
        when(request.getParameter("client_secret")).thenReturn(null);

        assertThatThrownBy(() -> controller.token("client_credentials", request))
                .isInstanceOf(EdaOAuthException.class)
                .satisfies(ex -> {
                    EdaOAuthException error = (EdaOAuthException) ex;
                    assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.error()).isEqualTo("invalid_request");
                });
    }
}
