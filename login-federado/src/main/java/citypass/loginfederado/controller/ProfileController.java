package citypass.loginfederado.controller;

import citypass.loginfederado.dto.ChangePasswordRequest;
import citypass.loginfederado.dto.MeResponse;
import citypass.loginfederado.service.PasswordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints del usuario sobre su PROPIA cuenta (perfil). Fuera de /auth de
 * propósito: requieren access token válido, a diferencia del flujo público.
 * El sub del JWT (employeeNumber) identifica a la persona; su clave actual
 * viaja en el body como segundo factor.
 */
@Tag(name = "Perfil", description = "Acciones autenticadas del usuario sobre su propia cuenta.")
@RestController
@RequestMapping("/me")
public class ProfileController {

    private final PasswordService passwordService;

    public ProfileController(PasswordService passwordService) {
        this.passwordService = passwordService;
    }

    @Operation(summary = "Mi perfil",
            description = "Datos de la persona autenticada (uid, nombre, mail, módulo y grupos), "
                    + "releídos del directorio en cada llamada.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Perfil de la persona autenticada"),
            @ApiResponse(responseCode = "401", description = "Token ausente o inválido"),
            @ApiResponse(responseCode = "403", description = "Token de servicio: el perfil es solo para personas"),
            @ApiResponse(responseCode = "404", description = "La cuenta ya no existe o está deshabilitada")
    })
    @GetMapping
    public MeResponse getProfile(@AuthenticationPrincipal Jwt jwt) {
        if (!"human".equals(jwt.getClaimAsString("token_use"))) {
            throw new AccessDeniedException("El perfil requiere un token de persona");
        }
        return passwordService.getProfile(jwt.getSubject());
    }

    @Operation(summary = "Cambiar mi contraseña",
            description = "Cambia la contraseña de la persona autenticada. Valida la contraseña actual "
                    + "contra LDAP antes de escribir la nueva y revoca todas sus sesiones (refresh tokens).")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Contraseña cambiada y sesiones revocadas"),
            @ApiResponse(responseCode = "400", description = "Validación de campos fallida"),
            @ApiResponse(responseCode = "401", description = "Token ausente o inválido"),
            @ApiResponse(responseCode = "403", description = "Token de servicio: el cambio es solo para personas"),
            @ApiResponse(responseCode = "422", description = "Contraseña actual incorrecta o nueva muy corta")
    })
    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody ChangePasswordRequest request) {
        if (!"human".equals(jwt.getClaimAsString("token_use"))) {
            throw new AccessDeniedException("El cambio de contraseña requiere un token de persona");
        }
        passwordService.changePassword(jwt.getSubject(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}