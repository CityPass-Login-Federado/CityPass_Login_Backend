package citypass.loginfederado.controller;

import citypass.loginfederado.config.JwtProperties;
import citypass.loginfederado.dto.ServiceTokenResponse;
import citypass.loginfederado.identity.ClientRegistry;
import citypass.loginfederado.token.AccessTokenIssuer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Token de servicio backend-a-backend (client_credentials, contrato §7).
 *
 * Autenticación por Basic (client_id:client_secret) resuelta a mano: el
 * resource-server de Spring espera JWTs y este endpoint es justo el que los
 * EMITE, así que no pasa por el filtro.
 *
 * El token resultante NO tiene groups: un servicio no es una persona. Su
 * identidad en el bus es su namespace. Regla de frontera del contrato: la
 * identidad de una persona viaja como dato del evento (actorSub), jamás
 * reenviando su token humano al bus.
 */
@RestController
public class OAuthTokenController {

    private final ClientRegistry clientRegistry;
    private final AccessTokenIssuer accessTokenIssuer;
    private final JwtProperties jwtProperties;

    public OAuthTokenController(ClientRegistry clientRegistry,
                                AccessTokenIssuer accessTokenIssuer,
                                JwtProperties jwtProperties) {
        this.clientRegistry = clientRegistry;
        this.accessTokenIssuer = accessTokenIssuer;
        this.jwtProperties = jwtProperties;
    }

    @PostMapping(value = "/oauth/token", consumes = "application/x-www-form-urlencoded")
    public ServiceTokenResponse token(@RequestParam("grant_type") String grantType,
                                      HttpServletRequest request) {
        if (!"client_credentials".equals(grantType)) {
            throw new BadCredentialsException(ClientRegistry.GENERIC_ERROR_MESSAGE);
        }

        String[] credentials = extractBasicCredentials(request);
        if (credentials == null) {
            throw new BadCredentialsException(ClientRegistry.GENERIC_ERROR_MESSAGE);
        }

        var client = clientRegistry.authenticateService(credentials[0], credentials[1]);
        String token = accessTokenIssuer.issueService(client);
        return new ServiceTokenResponse(
                token,
                "Bearer",
                jwtProperties.serviceTokenExpirationMinutes() * 60);
    }

    private String[] extractBasicCredentials(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Basic ")) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            if (separator <= 0) {
                return null;
            }
            return new String[]{decoded.substring(0, separator), decoded.substring(separator + 1)};
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
