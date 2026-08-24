package citypass.loginfederado.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Regla de oro del login: TODOS los caminos de falla devuelven EXACTAMENTE
 * el mismo cuerpo (byte por byte) — usuario inexistente, contraseña mala,
 * módulo equivocado, cuenta deshabilitada, cuenta bloqueada por fuerza
 * bruta, login anómalo o LDAP caído son indistinguibles para el cliente.
 * Las causas reales van a los logs (SECURITY), nunca a la respuesta.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String GENERIC_ERROR = "Credenciales inválidas";
    private static final String GENERIC_MESSAGE = "Usuario o contraseña inválidos";

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException ex) {
        return genericAuthFailure();
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<Map<String, Object>> handleAccountLocked(AccountLockedException ex) {
        // Bloqueo por ventana deslizante (ADR-004): indistinguible del resto.
        return genericAuthFailure();
    }

    @ExceptionHandler(AnomalyBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleAnomalyBlocked(AnomalyBlockedException ex) {
        return genericAuthFailure();
    }

    @ExceptionHandler(AnomalyServiceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleAnomalyServiceUnavailable(AnomalyServiceUnavailableException ex) {
        return genericAuthFailure();
    }

    private ResponseEntity<Map<String, Object>> genericAuthFailure() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", 401);
        body.put("error", GENERIC_ERROR);
        body.put("message", GENERIC_MESSAGE);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /** El panel negó el acceso por claims (audience/grupo/module). */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", 403);
        body.put("error", "Acceso denegado");
        body.put("message", "No tiene permisos para acceder a este recurso");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /** Validación de DTOs: mensajes estáticos, sin filtrar detalles internos. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", 400);
        body.put("error", "Petición inválida");
        body.put("message", "Revise los campos enviados");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** Reglas de negocio del panel violadas (nombres, límites, duplicados). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", 422);
        body.put("error", "Regla incumplida");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", 409);
        body.put("error", "Conflicto");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Los 404 del panel (ResponseStatusException) y los 405 de verbo
     * equivocado NO son errores internos: sin este handler caen en el
     * catch-all y salen como 500 mintiendo sobre lo que pasó.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", ex.getStatusCode().value());
        body.put("error", ex.getStatusCode().toString());
        body.put("message", ex.getReason() != null ? ex.getReason() : "Recurso no encontrado");
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethod(HttpRequestMethodNotSupportedException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", 405);
        body.put("error", "Método no permitido");
        body.put("message", "Verbo HTTP no soportado para esta ruta");
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Error interno no esperado", ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", 500);
        body.put("error", "Error interno");
        body.put("message", "Ocurrió un error inesperado. Intente nuevamente.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
