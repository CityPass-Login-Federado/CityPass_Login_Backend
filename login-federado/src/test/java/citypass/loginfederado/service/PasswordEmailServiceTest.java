package citypass.loginfederado.service;

import citypass.loginfederado.config.PasswordResetProperties;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.MailSendException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordEmailServiceTest {

    private static final String LINK = "http://localhost:3000/reset-password?token=abc123";

    private final PasswordResetProperties properties = new PasswordResetProperties(
            "no-reply@citypass.local", true, "http://localhost:3000/reset-password",
            30, 5, 3, 50);

    private PasswordEmailService serviceWith(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return new PasswordEmailService(provider, properties);
    }

    @Test
    void withoutSmtpConfiguredUsesDebugFallbackAndReportsNotified() {
        PasswordEmailService service = serviceWith(null);

        assertThat(service.sendResetLink("jperez@citypass.local", "jperez", LINK)).isTrue();
    }

    @Test
    void withoutSmtpAndDebugOffReportsFailureWithoutThrowing() {
        PasswordResetProperties noDebug = new PasswordResetProperties(
                "no-reply@citypass.local", false, "http://localhost:3000/reset-password",
                30, 5, 3, 50);
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        PasswordEmailService service = new PasswordEmailService(provider, noDebug);

        assertThatCode(() ->
                assertThat(service.sendResetLink("j@x.com", "jperez", LINK)).isFalse())
                .doesNotThrowAnyException();
    }

    @Test
    void blankSmtpHostCountsAsUnconfigured() {
        // Issue: el compose exportaba SPRING_MAIL_HOST vacío y Boot creaba el
        // sender igual. Un host en blanco debe usar el mismo fallback.
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("");
        PasswordEmailService service = serviceWith(sender);

        assertThat(service.sendResetLink("jperez@citypass.local", "jperez", LINK)).isTrue();
    }

    @Test
    void withSmtpConfiguredSendsMessageAndReportsNotified() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(mock(MimeMessage.class));
        PasswordEmailService service = serviceWith(sender);

        assertThat(service.sendResetLink("jperez@citypass.local", "jperez", LINK)).isTrue();

        verify(sender).send(any(MimeMessage.class));
    }

    @Test
    void sendFailureIsReportedWithoutThrowing() {
        MimeMessage message = mock(MimeMessage.class);
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(message);
        doThrow(new MailSendException("smtp caido")).when(sender).send(message);
        PasswordEmailService service = serviceWith(sender);

        assertThatCode(() ->
                assertThat(service.sendResetLink("j@x.com", "jperez", LINK)).isFalse())
                .doesNotThrowAnyException();
    }
}
