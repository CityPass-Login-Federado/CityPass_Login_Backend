package citypass.loginfederado.event;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class HealthControllerTest {

    @Test
    void healthEndpointReturnsUpStatus() {
        var controller = new HealthController();

        var response = controller.health();

        assertThat(response).containsEntry("status", "UP");
    }
}
