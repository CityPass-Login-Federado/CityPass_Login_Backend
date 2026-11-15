package citypass.loginfederado.exception;

/**
 * Se dispara cuando una IP supera el tope de intentos de sesión en la ventana
 * configurada. Se mapea a HTTP 429 (no revela nada de la cuenta).
 */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
