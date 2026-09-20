package citypass.loginfederado.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Respuesta de login/refresh EXACTAMENTE como la define el contrato público
 * (snake_case): access_token, refresh_token, token_type, expires_in.
 */
@Schema(description = "Par de tokens emitidos en login/refresh (contrato, snake_case).")
public record LoginResponse(
        @JsonProperty("access_token")
        @Schema(description = "JWT RS256, vida 15 min") String accessToken,
        @JsonProperty("refresh_token")
        @Schema(description = "Token opaco rotativo, vida 8 h") String refreshToken,
        @JsonProperty("token_type")
        @Schema(description = "Tipo de token", example = "Bearer") String tokenType,
        @JsonProperty("expires_in")
        @Schema(description = "Segundos hasta expiración del access token", example = "900") long expiresIn
) {
}