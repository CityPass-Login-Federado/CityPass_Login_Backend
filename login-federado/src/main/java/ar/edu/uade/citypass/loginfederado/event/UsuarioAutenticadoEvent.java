package ar.edu.uade.citypass.loginfederado.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload del evento "usuario.autenticado", publicado cada vez que un
 * login es exitoso. El formato exacto del "sobre" (envelope) del evento
 * -- topic, versionado, metadata común -- lo define el contrato del
 * Grupo 1 (EDA); esta clase representa el PAYLOAD específico de este
 * módulo, que se ajustará una vez ese contrato esté publicado.
 */
public record UsuarioAutenticadoEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String username,
        String email,
        List<String> roles
) {
    public static UsuarioAutenticadoEvent of(String username, String email, List<String> roles) {
        return new UsuarioAutenticadoEvent(
                UUID.randomUUID().toString(),
                "usuario.autenticado",
                Instant.now(),
                username,
                email,
                roles
        );
    }
}