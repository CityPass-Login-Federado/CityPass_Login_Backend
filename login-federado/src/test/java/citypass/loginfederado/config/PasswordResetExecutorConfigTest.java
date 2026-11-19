package citypass.loginfederado.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordResetExecutorConfigTest {

    @Test
    void executorRunsSubmittedWork() throws Exception {
        Executor executor = new PasswordResetExecutorConfig().passwordResetExecutor();
        CountDownLatch latch = new CountDownLatch(1);

        executor.execute(latch::countDown);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
