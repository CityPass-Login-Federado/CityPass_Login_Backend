package citypass.loginfederado.analytics;

import citypass.loginfederado.panel.PanelAuthorization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin")
public class AnalyticsController {

    private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    private static final String REFERRER_POLICY = "Referrer-Policy";

    private static final List<String> FORWARDED_HEADERS = List.of(
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.CACHE_CONTROL,
            CONTENT_SECURITY_POLICY,
            X_CONTENT_TYPE_OPTIONS,
            REFERRER_POLICY
    );

    private final AnalyticsClient analyticsClient;
    private final PanelAuthorization authorization;

    public AnalyticsController(AnalyticsClient analyticsClient, PanelAuthorization authorization) {
        this.analyticsClient = analyticsClient;
        this.authorization = authorization;
    }

    @Operation(
            summary = "Obtener el tablero HTML de Analítica",
            description = "Proxy autenticado del HTML de Identidad. Reenvía el access token humano "
                    + "y devuelve el contenido sin modificar.",
            tags = "Panel — Analítica")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "HTML autocontenido del tablero"),
            @ApiResponse(responseCode = "401", description = "Token ausente o inválido"),
            @ApiResponse(responseCode = "403", description = "Token sin permisos del panel"),
            @ApiResponse(responseCode = "502", description = "Respuesta exitosa inválida de Analítica"),
            @ApiResponse(responseCode = "503", description = "Analítica no disponible")
    })
    @GetMapping("/analitica")
    public ResponseEntity<byte[]> analytics(@AuthenticationPrincipal Jwt jwt) {
        PanelAuthorization.Delegate delegate = authorization.requireDelegate(jwt);
        if (!delegate.global()) {
            throw new AccessDeniedException("El tablero de Identidad requiere admin global");
        }
        AnalyticsProxyResponse upstream = analyticsClient.fetchEmbed(jwt.getTokenValue());

        HttpHeaders headers = new HttpHeaders();
        FORWARDED_HEADERS.forEach(name -> {
            List<String> values = upstream.headers().get(name);
            if (values != null) {
                headers.put(name, List.copyOf(values));
            }
        });
        if (!headers.containsKey(HttpHeaders.CACHE_CONTROL)) {
            headers.setCacheControl(CacheControl.noStore());
        }

        return ResponseEntity.status(upstream.status())
                .headers(headers)
                .body(upstream.body());
    }
}
