package citypass.loginfederado.controller;

import citypass.loginfederado.dto.ChangePasswordRequest;
import citypass.loginfederado.service.PasswordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfileControllerTest {

    private PasswordService passwordService;
    private ProfileController controller;

    @BeforeEach
    void setUp() {
        passwordService = mock(PasswordService.class);
        controller = new ProfileController(passwordService);
    }

    @Test
    void humanTokenChangesPassword() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString("token_use")).thenReturn("human");
        when(jwt.getSubject()).thenReturn("U000042");
        var request = new ChangePasswordRequest("actual", "nuevaClave123");

        assertThat(controller.changePassword(jwt, request).getStatusCode().value()).isEqualTo(204);
        verify(passwordService).changePassword("U000042", "actual", "nuevaClave123");
    }

    @Test
    void serviceTokenIsRejected() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString("token_use")).thenReturn("service");
        var request = new ChangePasswordRequest("actual", "nuevaClave123");

        assertThatThrownBy(() -> controller.changePassword(jwt, request))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void humanTokenReadsOwnProfile() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString("token_use")).thenReturn("human");
        when(jwt.getSubject()).thenReturn("U000042");
        var expected = new citypass.loginfederado.dto.MeResponse(
                "jperez", "U000042", "Juan Perez", "jperez@citypass.local",
                "reclamos", List.of("soporte-n2"));
        when(passwordService.getProfile("U000042")).thenReturn(expected);

        assertThat(controller.getProfile(jwt)).isSameAs(expected);
    }

    @Test
    void serviceTokenCannotReadProfile() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString("token_use")).thenReturn("service");

        assertThatThrownBy(() -> controller.getProfile(jwt))
                .isInstanceOf(AccessDeniedException.class);
    }
}
