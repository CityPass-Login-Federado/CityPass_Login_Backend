package ar.edu.uade.citypass.loginfederado.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ar.edu.uade.citypass.loginfederado.exception.AccountLockedException.class)
    public ResponseEntity<Map<String, Object>> handleAccountLocked(
            ar.edu.uade.citypass.loginfederado.exception.AccountLockedException ex) {
        return ResponseEntity.status(HttpStatus.LOCKED).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 423,
                "error", "Cuenta bloqueada",
                "message", ex.getMessage()
        ));
    }
}