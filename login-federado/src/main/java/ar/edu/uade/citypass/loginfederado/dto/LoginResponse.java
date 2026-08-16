package ar.edu.uade.citypass.loginfederado.dto;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds
) {
}