package citypass.loginfederado.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ForgotPasswordRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializesUidField() throws Exception {
        ForgotPasswordRequest request =
                mapper.readValue("{\"uid\":\"jperez\"}", ForgotPasswordRequest.class);
        assertThat(request.uid()).isEqualTo("jperez");
    }

    @Test
    void deserializesLegacyUsernameAlias() throws Exception {
        // Backward compat: clientes viejos mandan "username".
        ForgotPasswordRequest request =
                mapper.readValue("{\"username\":\"jperez\"}", ForgotPasswordRequest.class);
        assertThat(request.uid()).isEqualTo("jperez");
    }
}
