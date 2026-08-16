package ar.edu.uade.citypass.loginfederado.service;

import ar.edu.uade.citypass.loginfederado.config.JwtProperties;
import ar.edu.uade.citypass.loginfederado.model.RefreshToken;
import ar.edu.uade.citypass.loginfederado.repository.RefreshTokenRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Genera, persiste y valida refresh tokens. El token es un valor opaco
 * (NO un JWT) de alta entropía. Solo se persiste su hash SHA-256; el
 * valor crudo se entrega una única vez al cliente en el momento de la
 * emisión, igual que se hace con una contraseña.
 *
 * Rotación: cada refresh token se usa una única vez. Al validarlo se
 * revoca inmediatamente y se emite uno nuevo — si alguien reutiliza un
 * token ya usado, falla, lo que ayuda a detectar robo de tokens.
 */
@Service
public class RefreshTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTE_LENGTH = 64;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, JwtProperties jwtProperties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProperties = jwtProperties;
    }

    public String issueFor(String username, String fullName, String email, List<String> roles) {
        String rawToken = generateRawToken();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(jwtProperties.refreshTokenExpirationDays() * 24 * 60 * 60);

        refreshTokenRepository.save(
                new RefreshToken(username, fullName, email, hash(rawToken), roles, now, expiresAt)
        );

        return rawToken;
    }

    public RefreshTokenPrincipal validateAndRotate(String rawToken) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new BadCredentialsException("Refresh token inválido"));

        if (!stored.isValid()) {
            throw new BadCredentialsException("Refresh token expirado o revocado");
        }

        stored.revoke();
        refreshTokenRepository.save(stored);

        return new RefreshTokenPrincipal(stored.getUsername(), stored.getFullName(),
                stored.getEmail(), stored.getRolesList());
    }

    /** Usado en logout: invalida todas las sesiones activas del usuario. */
    public void revokeAllFor(String username) {
        refreshTokenRepository.findAllByUsernameAndRevokedFalse(username)
                .forEach(RefreshToken::revoke);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(rawToken.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}