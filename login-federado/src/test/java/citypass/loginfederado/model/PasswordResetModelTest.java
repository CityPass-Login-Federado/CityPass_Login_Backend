package citypass.loginfederado.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordResetModelTest {

    @Test
    void tokenRequestExposesItsData() {
        Instant at = Instant.now();
        PasswordResetRequest request = new PasswordResetRequest("jperez", "10.0.0.1", at);

        assertThat(request.getId()).isNull();
        assertThat(request.getUid()).isEqualTo("jperez");
        assertThat(request.getIpAddress()).isEqualTo("10.0.0.1");
        assertThat(request.getRequestedAt()).isEqualTo(at);
    }

    @Test
    void entitiesHaveJpaNoArgConstructors() throws Exception {
        // Solo JPA los usa (protected): se verifican por reflexión.
        var tokenCtor = PasswordResetToken.class.getDeclaredConstructor();
        tokenCtor.setAccessible(true);
        assertThat(tokenCtor.newInstance()).isNotNull();

        var requestCtor = PasswordResetRequest.class.getDeclaredConstructor();
        requestCtor.setAccessible(true);
        assertThat(requestCtor.newInstance()).isNotNull();
    }
}
