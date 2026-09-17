package citypass.loginfederado.service;

import citypass.loginfederado.config.PasswordResetProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Envío del mail con la contraseña temporal.
 *
 * Usa JavaMailSender SOLO si hay SMTP configurado (spring.mail.host: lo
 * auto-configura Boot). Sin SMTP (desarrollo) respeta `debug-log`: si es true
 * imprime la clave en consola para poder entrar sin levantar un servidor de
 * mail; si es false, no intenta mandar nada y loguea el problema.
 *
 * Cualquier falla de envío se registra y NO se propaga: el endpoint de
 * recupero responde siempre 204 (anti-enumeración del directorio).
 */
@Service
public class PasswordEmailService {

    private static final Logger log = LoggerFactory.getLogger(PasswordEmailService.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final PasswordResetProperties properties;

    public PasswordEmailService(ObjectProvider<JavaMailSender> mailSenderProvider,
                                PasswordResetProperties properties) {
        this.mailSenderProvider = mailSenderProvider;
        this.properties = properties;
    }

    /**
     * @param temporaryPassword la clave recién generada (ya persistida en LDAP).
     */
    public void sendTemporaryPassword(String to, String username, String temporaryPassword) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            if (properties.debugLog()) {
                log.warn("[DEV] SMTP no configurado: contraseña temporal de {} ({}) -> {}",
                        username, to, temporaryPassword);
            } else {
                log.error("Recupero de contraseña de {} ({}) sin SMTP configurado: no se pudo notificar",
                        username, to);
            }
            return;
        }

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(properties.from());
            helper.setTo(to);
            helper.setSubject("CityPass+ - Contraseña temporal");
            helper.setText("""
                    Hola %s,

                    Solicitaste cambiar tu contraseña de CityPass+. Tu contraseña temporal es:

                        %s

                    Entrá con esa contraseña y luego cámbiala desde tu perfil.
                    """.formatted(username, temporaryPassword));
            sender.send(message);
            log.info("Contraseña temporal enviada a {}", to);
        } catch (MessagingException | MailException ex) {
            // El SMTP real lanza MailException (runtime) en el send; la
            // MessagingException cubre el armado del mensaje. Cualquiera de las
            // dos: se registra y NO se propaga (el endpoint responde 204 igual).
            log.error("No se pudo enviar la contraseña temporal a {}", to, ex);
        }
    }
}