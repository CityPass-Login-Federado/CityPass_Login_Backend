package citypass.loginfederado.event;

import org.springframework.http.HttpStatus;

public class EdaOAuthException extends RuntimeException {
    private final HttpStatus status;
    private final String error;

    public EdaOAuthException(HttpStatus status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }

    public HttpStatus status() { return status; }
    public String error() { return error; }
}