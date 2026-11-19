package citypass.loginfederado.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** Respuesta de POST /oauth/token (contrato §7), formato snake_case. */
@Schema(description = "Respuesta de POST /oauth/token (token de servicio, contrato §7).")
public record ServiceTokenResponse(
        @JsonProperty("access_token")
        @Schema(description = "JWT RS256 de servicio (sin groups ni module)") String accessToken,
        @JsonProperty("token_type")
        @Schema(description = "Tipo de token", example = "Bearer") String tokenType,
        @JsonProperty("expires_in")
        @Schema(description = "Segundos hasta expiración", example = "3600") long expiresIn
) {
}