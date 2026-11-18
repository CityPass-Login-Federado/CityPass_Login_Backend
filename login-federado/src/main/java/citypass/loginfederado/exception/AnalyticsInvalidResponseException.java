package citypass.loginfederado.exception;

/** Analítica respondió 2xx, pero sin un documento HTML utilizable. */
public class AnalyticsInvalidResponseException extends RuntimeException {

    public AnalyticsInvalidResponseException(String message) {
        super(message);
    }
}
