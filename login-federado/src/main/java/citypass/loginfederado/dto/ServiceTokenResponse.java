package citypass.loginfederado.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Respuesta de POST /oauth/token (contrato §7), formato snake_case. */
public record ServiceTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn
) {
}
