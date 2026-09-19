package citypass.loginfederado.service;

import citypass.loginfederado.config.PasswordResetProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Envío del mail con el ENLACE de recupero (token de un solo uso).
 *
 * El mail NUNCA lleva credenciales: solo el enlace con el token crudo. LDAP
 * no se tocó todavía cuando esto se manda — si el envío falla, la
 * contraseña vigente sigue intacta.
 *
 * Usa JavaMailSender SOLO si hay SMTP REAL configurado: bean presente Y host
 * no blanco. Un host vacío ("") cuenta como ausente — Boot lo interpreta
 * como configurado y crearía el sender igual (issue del compose), así que
 * se detecta explícitamente acá y se usa el mismo fallback que sin sender.
 *
 * Sin SMTP (desarrollo) respeta `debug-log`: si es true imprime el enlace
 * en consola para poder probar sin servidor de mail; si es false, no
 * intenta mandar nada y REPORTA el fallo (false).
 *
 * Devuelve true si el usuario quedó notificado (mail enviado o logueado en
 * dev), false si no. El llamador decide con ese booleano (p.ej. liberar el
 * token pendiente para que el usuario pueda reintentar de inmediato).
 * Nunca lanza: fallar ruidoso hacia afuera permitiría enumerar el
 * directorio por diferencia de respuestas.
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
     * @param resetLink enlace completo con el token crudo (ya armado).
     * @return true si se notificó (enviado o debug-log), false si no.
     */
    public boolean sendResetLink(String to, String username, String resetLink) {
        if (!hasUsableSmtp(mailSenderProvider.getIfAvailable())) {
            if (properties.debugLog()) {
                log.warn("[DEV] SMTP no configurado: enlace de recupero de {} ({}) -> {}",
                        username, to, resetLink);
                return true;
            }
            log.error("Recupero de contraseña de {} ({}) sin SMTP configurado: no se pudo notificar",
                    username, to);
            return false;
        }

        try {
            JavaMailSender sender = mailSenderProvider.getIfAvailable();
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(properties.from());
            helper.setTo(to);
            helper.setSubject("CityPass+ - Recuperar contraseña");
            helper.setText("""
                    Hola %s,

                    Pediste recuperar tu contraseña de CityPass+. Entrá a este enlace
                    para elegir una nueva (vence en %d minutos y es de un solo uso):

                        %s

                    Si no fuiste vos, ignorá este mensaje: tu contraseña actual
                    sigue funcionando.
                    """.formatted(username, properties.tokenTtlMinutes(), resetLink));
            sender.send(message);
            log.info("Enlace de recupero enviado a {}", to);
            return true;
        } catch (MessagingException | MailException | IllegalStateException ex) {
            // El SMTP real lanza MailException (runtime) en el send; la
            // MessagingException cubre el armado del mensaje. Se registra y se
            // REPORTA (false): el llamador invalida el token pendiente para
            // que el usuario pueda reintentar, sin tocar LDAP.
            log.error("No se pudo enviar el enlace de recupero a {}", to, ex);
            return false;
        }
    }

    /**
     * SMTP usable = bean presente y host no blanco. El host vacío que exporta
     * el compose por defecto ("${VAR:-}") hace que Boot cree el sender igual:
     * tratarlo como ausente restaura el fallback de desarrollo.
     */
    private static boolean hasUsableSmtp(JavaMailSender sender) {
        if (sender == null) {
            return false;
        }
        if (sender instanceof JavaMailSenderImpl impl) {
            String host = impl.getHost();
            return host != null && !host.isBlank();
        }
        return true;
    }
}
