package citypass.loginfederado.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload del evento "usuario.autenticado", publicado en cada login exitoso.
 * El envelope (topic, metadata común) lo definirá el contrato del Grupo 1;
 * el payload identifica a la persona por sub + uid y su módulo — sin email
 * ni datos de contacto: el bus no es un padrón.
 */
public record UsuarioAutenticadoEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String sub,
        String uid,
        String module,
        List<String> groups
) {
    public static UsuarioAutenticadoEvent of(String sub, String uid, String module, List<String> groups) {
        return new UsuarioAutenticadoEvent(
                UUID.randomUUID().toString(),
                "usuario.autenticado",
                Instant.now(),
                sub,
                uid,
                module,
                groups == null ? List.of() : List.copyOf(groups)
        );
    }
}
