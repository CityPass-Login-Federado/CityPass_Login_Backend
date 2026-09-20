package citypass.loginfederado.controller;

import citypass.loginfederado.dto.ForgotPasswordRequest;
import citypass.loginfederado.dto.LoginRequest;
import citypass.loginfederado.dto.LoginResponse;
import citypass.loginfederado.dto.RefreshRequest;
import citypass.loginfederado.dto.ResetPasswordRequest;
import citypass.loginfederado.service.AuthService;
import citypass.loginfederado.service.PasswordService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private AuthService authService;
    private PasswordService passwordService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        passwordService = mock(PasswordService.class);
        controller = new AuthController(authService, passwordService);
    }

    @Test
    void loginForwardsIpAndUserAgent() {
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getHeader("X-Forwarded-For")).thenReturn(null);
        when(http.getHeader("User-Agent")).thenReturn("JUnit");
        when(http.getRemoteAddr()).thenReturn("1.2.3.4");
        var request = new LoginRequest("jperez", "clave", "citypass-admin-web");
        var expected = new LoginResponse("a", "r", "Bearer", 900L);
        when(authService.login(eq(request), eq("1.2.3.4"), eq("JUnit"))).thenReturn(expected);

        assertThat(controller.login(request, http).getBody()).isSameAs(expected);
    }

    @Test
    void loginPrefersForwardedForHeader() {
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getHeader("X-Forwarded-For")).thenReturn("9.9.9.9, 8.8.8.8");
        when(http.getHeader("User-Agent")).thenReturn(null);
        var request = new LoginRequest("jperez", "clave", "citypass-admin-web");

        controller.login(request, http);

        verify(authService).login(eq(request), eq("9.9.9.9"), eq((String) null));
    }

    @Test
    void refreshDelegates() {
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getHeader("X-Forwarded-For")).thenReturn(null);
        when(http.getHeader("User-Agent")).thenReturn("JUnit");
        when(http.getRemoteAddr()).thenReturn("1.2.3.4");
        var request = new RefreshRequest("rt");
        var expected = new LoginResponse("a", "r", "Bearer", 900L);
        when(authService.refresh(request, "1.2.3.4", "JUnit")).thenReturn(expected);

        assertThat(controller.refresh(request, http).getBody()).isSameAs(expected);
    }

    @Test
    void logoutIsAlways204() {
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getHeader("X-Forwarded-For")).thenReturn(null);
        when(http.getHeader("User-Agent")).thenReturn("JUnit");
        when(http.getRemoteAddr()).thenReturn("1.2.3.4");
        var request = new RefreshRequest("rt");

        assertThat(controller.logout(request, http).getStatusCode().value()).isEqualTo(204);
        verify(authService).logout("rt", "1.2.3.4", "JUnit");
    }

    @Test
    void forgotPasswordIsAlways204WithClientIp() {
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getHeader("X-Forwarded-For")).thenReturn(null);
        when(http.getRemoteAddr()).thenReturn("10.0.0.1");

        assertThat(controller.forgotPassword(new ForgotPasswordRequest("jperez"), http)
                .getStatusCode().value()).isEqualTo(204);
        verify(passwordService).requestPasswordReset("jperez", "10.0.0.1");
    }

    @Test
    void resetPasswordIsAlways204() {
        var request = new ResetPasswordRequest("token-crudo", "nuevaClave123");

        assertThat(controller.resetPassword(request).getStatusCode().value()).isEqualTo(204);
        verify(passwordService).redeemResetToken("token-crudo", "nuevaClave123");
    }
}
