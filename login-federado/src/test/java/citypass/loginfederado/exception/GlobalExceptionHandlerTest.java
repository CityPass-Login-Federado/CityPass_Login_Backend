package citypass.loginfederado.exception;

import citypass.loginfederado.event.EdaOAuthException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Constructor;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handlesGenericAuthFailure() {
        var response = handler.handleBadCredentials(new BadCredentialsException("bad"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("status", 401);
        assertThat(response.getBody()).containsEntry("error", "Credenciales inválidas");
        assertThat(response.getBody()).containsEntry("message", "Usuario o contraseña inválidos");
    }

    @Test
    void handlesSpecificSecurityExceptions() {
        assertThat(handler.handleAccountLocked(new AccountLockedException("locked")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(handler.handleAnomalyBlocked(new AnomalyBlockedException("blocked")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(handler.handleAnomalyServiceUnavailable(new AnomalyServiceUnavailableException("down", new RuntimeException()))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void handlesEdaOAuthException() {
        EdaOAuthException ex = new EdaOAuthException(HttpStatus.UNAUTHORIZED, "invalid_client", "bad credentials");

        var response = handler.handleEdaOAuth(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "invalid_client");
        assertThat(response.getBody()).containsEntry("error_description", "bad credentials");
    }

    @Test
    void handlesAccessDeniedValidationAndBusinessRules() {
        var accessDenied = handler.handleAccessDenied(new AccessDeniedException("nope"));

        BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "payload");
        var validation = handler.handleValidation(new MethodArgumentNotValidException(null, bindingResult));

        var illegalArgument = handler.handleIllegalArgument(new IllegalArgumentException("bad payload"));
        var illegalState = handler.handleIllegalState(new IllegalStateException("conflict"));

        assertThat(accessDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(validation.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(illegalArgument.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(illegalArgument.getBody()).containsEntry("message", "bad payload");
        assertThat(illegalState.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void handlesHttpStatusAndMethodErrors() throws Exception {
        var status = handler.handleStatus(new ResponseStatusException(HttpStatus.NOT_FOUND, "missing"));

        Constructor<HttpRequestMethodNotSupportedException> constructor =
                HttpRequestMethodNotSupportedException.class.getDeclaredConstructor(String.class, String[].class);
        constructor.setAccessible(true);
        var httpMethod = handler.handleMethod(constructor.newInstance("GET", new String[]{"POST"}));

        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status.getBody()).containsEntry("status", 404);
        assertThat(status.getBody()).containsEntry("message", "missing");
        assertThat(httpMethod.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(httpMethod.getBody()).containsEntry("status", 405);
    }

    @Test
    void handlesUnexpectedExceptions() {
        var response = handler.handleUnexpected(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("status", 500);
        assertThat(response.getBody()).containsEntry("error", "Error interno");
        assertThat(response.getBody()).containsEntry("message", "Ocurrió un error inesperado. Intente nuevamente.");
    }
}
