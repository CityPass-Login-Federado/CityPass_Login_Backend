package citypass.loginfederado.metrics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Payload del evento "identidad.metricas.diarias", publicado una vez por día
 * con las métricas del día anterior (UTC). Lo consume el equipo de
 * Analítica (Grupo 8) suscribiéndose al bus — nunca accede a nuestra base
 * ni a nuestra API directamente; el envelope (topic, formato común) lo
 * define el contrato del Grupo 1, igual que "usuario.autenticado".
 */
public record DailyLoginMetricEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        LocalDate periodo,
        long usuariosActivosDiarios,
        long usuariosActivosMensuales,
        List<HourlyLoginBucket> horariosLogin,
        long sesionesFinalizadas,
        double duracionPromedioSegundos,
        double duracionMinimaSegundos,
        double duracionMaximaSegundos
) {
public static DailyLoginMetricEvent of(LocalDate periodo,
                                                long usuariosActivosDiarios,
                                                long usuariosActivosMensuales,
                                                List<HourlyLoginBucket> horariosLogin,
                                                long sesionesFinalizadas,
                                                double duracionPromedioSegundos,
                                                double duracionMinimaSegundos,
                                                double duracionMaximaSegundos) {
        return new DailyLoginMetricEvent(
                UUID.randomUUID().toString(),
                "identidad.metricas.diarias",
                Instant.now(),
                periodo,
                usuariosActivosDiarios,
                usuariosActivosMensuales,
                horariosLogin,
                sesionesFinalizadas,
                duracionPromedioSegundos,
                duracionMinimaSegundos,
                duracionMaximaSegundos
        );
        }
}