package citypass.loginfederado.metrics;

import java.time.Instant;
import java.util.UUID;

/** Hecho crudo de autenticacion para el equipo de Metricas. */
public record RawAuthenticationEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String userSub,
        String username,
        String department,
        String clientId,
        UUID chainId,
        String ipAddress,
        String userAgent
) {
public static RawAuthenticationEvent login(
        String userSub, String username, String department, String clientId,
        String ipAddress, String userAgent) {
        return create("identidad.login", userSub, username, department, clientId,
                null, ipAddress, userAgent);
}

public static RawAuthenticationEvent refresh(
        String userSub, String username, String department, String clientId,
        UUID chainId, String ipAddress, String userAgent) {
        return create("identidad.refresh", userSub, username, department, clientId,
                chainId, ipAddress, userAgent);
}

public static RawAuthenticationEvent logout(
        String userSub, String department, String clientId, UUID chainId,
        String ipAddress, String userAgent) {
        return create("identidad.logout", userSub, null, department, clientId,
                chainId, ipAddress, userAgent);
}

private static RawAuthenticationEvent create(
        String eventType, String userSub, String username, String department,
        String clientId, UUID chainId, String ipAddress, String userAgent) {
        return new RawAuthenticationEvent(
                UUID.randomUUID().toString(), eventType,
                Instant.now(), userSub,
                username, department, clientId, chainId, ipAddress, userAgent);
}
}