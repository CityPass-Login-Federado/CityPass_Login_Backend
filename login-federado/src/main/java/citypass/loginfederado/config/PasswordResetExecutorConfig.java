package citypass.loginfederado.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Executor acotado para el trabajo REAL del recupero (persistir el token y
 * mandar el mail) DESPUÉS de responder 204.
 *
 * Por qué async: el tiempo de un SMTP real (segundos) haría que la respuesta
 * del endpoint tarde distinto según exista o no la cuenta con mail —
 * oráculo de enumeración por timing. Respondiendo tras un camino uniforme
 * corto y encolando el resto, ambos casos se ven iguales desde afuera.
 *
 * Un solo hilo con cola acotada implícita: el envío de mails no necesita
 * paralelismo y así no se amplifica un abuso del endpoint público.
 */
@Configuration
public class PasswordResetExecutorConfig {

    @Bean(name = "passwordResetExecutor")
    public Executor passwordResetExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "password-reset-mailer");
            thread.setDaemon(true);
            return thread;
        });
    }
}
