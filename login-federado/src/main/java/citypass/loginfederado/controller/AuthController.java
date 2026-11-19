package citypass.loginfederado.controller;

import citypass.loginfederado.dto.ForgotPasswordRequest;
import citypass.loginfederado.dto.LoginRequest;
import citypass.loginfederado.dto.LoginResponse;
import citypass.loginfederado.dto.RefreshRequest;
import citypass.loginfederado.dto.ResetPasswordRequest;
import citypass.loginfederado.service.AuthService;
import citypass.loginfederado.service.PasswordService;
import citypass.loginfederado.web.ClientIpResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints públicos de autenticación definidos en el contrato
 * (specs/03-CONTRATO-TOKEN.md §2). El logout va por refresh_token en el body:
 * es público y no requiere access token, porque la sesión vive del refresh.
 */
@Tag(name = "Autenticación", description = "Endpoints públicos de login, refresh y logout (contrato §2). "
        + "Todo error responde el mismo cuerpo genérico para no enumerar usuarios.")
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordService passwordService;
    private final ClientIpResolver clientIpResolver;

    public AuthController(AuthService authService, PasswordService passwordService,
                          ClientIpResolver clientIpResolver) {
        this.authService = authService;
        this.passwordService = passwordService;
        this.clientIpResolver = clientIpResolver;
    }

    @Operation(summary = "Login de usuario",
            description = "Autentica contra LDAP y emite access + refresh token. "
                    + "Los errores de credencial, usuario inexistente, cliente desconocido y lockout "
                    + "devuelven exactamente la misma respuesta 401 (anti-enumeración).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Access + refresh token emitidos"),
            @ApiResponse(responseCode = "400", description = "Validación de campos fallida"),
            @ApiResponse(responseCode = "401", description = "Credenciales inválidas (genérico)")
    })
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest) {
        String ipAddress = clientIpResolver.resolve(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        return ResponseEntity.ok(authService.login(request, ipAddress, userAgent));
    }

    @Operation(summary = "Refresh token",
            description = "Canjea un refresh token opaco por un par nuevo (rotación RFC 9700). "
                    + "Revalida contra LDAP en cada canje. El reuso de un token ya canjeado revoca toda la cadena.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Par de tokens rotados"),
            @ApiResponse(responseCode = "400", description = "Validación de campos fallida"),
            @ApiResponse(responseCode = "401", description = "Refresh token inválido, revocado o cadena muerta")
    })
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request,
                                                 HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.refresh(
                request, clientIpResolver.resolve(httpRequest), httpRequest.getHeader("User-Agent")));
    }

    @Operation(summary = "Logout",
            description = "Invalida la sesión (revoca el refresh canjeable) y, si se envía el "
                    + "access token en el header Authorization: Bearer, lo denylista por jti. "
                    + "Persiste. Token desconocido → 204 igual: no se revela si alguna vez existió.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Sesión invalidada (token inexistente devuelve lo mismo)")
    })
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request,
                                       HttpServletRequest httpRequest) {
        authService.logout(request.refreshToken(), extractBearerToken(httpRequest),
                clientIpResolver.resolve(httpRequest),
                httpRequest.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    /** Access token opcional del logout: `Authorization: Bearer <jwt>`. */
    private static String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = header.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    @Operation(summary = "Solicitar recupero de contraseña",
            description = "Emite un token de un solo uso y lo manda por mail como enlace. "
                    + "NO escribe en LDAP al pedir: la contraseña cambia recién al canjear el "
                    + "token en /auth/reset-password. Responde SIEMPRE 204 (usuario existente "
                    + "o no, limitado o no): no se puede enumerar el directorio ni detectar "
                    + "el freno anti-abuso. Cooldown por cuenta + topes por cuenta e IP.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Procesado (el mail llega solo si el usuario existe y tiene email)"),
            @ApiResponse(responseCode = "400", description = "Validación de campos fallida")
    })
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                               HttpServletRequest httpRequest) {
        passwordService.requestPasswordReset(request.uid(), clientIpResolver.resolve(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Canjear token de recupero",
            description = "Define la nueva contraseña con el token del enlace del mail "
                    + "(un solo uso, con vencimiento). Al canjear se revocan TODAS las "
                    + "sesiones vigentes de la cuenta, igual que en /me/change-password.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Contraseña restablecida y sesiones revocadas"),
            @ApiResponse(responseCode = "400", description = "Validación de campos fallida"),
            @ApiResponse(responseCode = "422", description = "Enlace inválido o expiró")
    })
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordService.redeemResetToken(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}