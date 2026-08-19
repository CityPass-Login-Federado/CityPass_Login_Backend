package citypass.loginfederado.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(citypass.loginfederado.exception.AccountLockedException.class)
    public ResponseEntity<Map<String, Object>> handleAccountLocked(
            citypass.loginfederado.exception.AccountLockedException ex) {
        return ResponseEntity.status(HttpStatus.LOCKED).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 423,
                "error", "Cuenta bloqueada",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(citypass.loginfederado.exception.AnomalyBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleAnomalyBlocked(
            citypass.loginfederado.exception.AnomalyBlockedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 403,
                "error", "Actividad anómala detectada",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(citypass.loginfederado.exception.AnomalyServiceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleAnomalyServiceUnavailable(
            citypass.loginfederado.exception.AnomalyServiceUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 503,
                "error", "Servicio no disponible",
                "message", "No se pudo procesar el login en este momento. Intente nuevamente."
        ));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
            "timestamp", Instant.now().toString(),
            "status", 401,
            "error", "Credenciales inválidas",
            "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
            "timestamp", Instant.now().toString(),
            "status", 500,
            "error", "Error interno",
            "message", "Ocurrió un error inesperado. Intente nuevamente."
        ));
    }
}