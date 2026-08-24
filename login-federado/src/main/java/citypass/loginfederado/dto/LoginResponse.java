package citypass.loginfederado.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Respuesta de login/refresh EXACTAMENTE como la define el contrato público
 * (snake_case): access_token, refresh_token, token_type, expires_in.
 */
public record LoginResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn
) {
}
