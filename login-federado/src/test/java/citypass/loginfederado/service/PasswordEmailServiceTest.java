package citypass.loginfederado.service;

import citypass.loginfederado.config.PasswordResetProperties;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.MailSendException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordEmailServiceTest {

    private final PasswordResetProperties properties =
            new PasswordResetProperties("no-reply@citypass.local", true);

    @Test
    void withoutSmtpConfiguredLogsFallbackAndNeverThrows() {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        PasswordEmailService service = new PasswordEmailService(provider, properties);

        assertThatCode(() ->
                service.sendTemporaryPassword("jperez@citypass.local", "jperez", "Temp12345Ab"))
                .doesNotThrowAnyException();
    }

    @Test
    void withSmtpConfiguredSendsMessage() {
        JavaMailSender sender = mock(JavaMailSender.class);
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(mock(MimeMessage.class));
        PasswordEmailService service = new PasswordEmailService(provider, properties);

        service.sendTemporaryPassword("jperez@citypass.local", "jperez", "Temp12345Ab");

        verify(sender).send(any(MimeMessage.class));
    }

    @Test
    void sendFailureIsSwallowed() throws Exception {
        MimeMessage message = mock(MimeMessage.class);
        JavaMailSender sender = mock(JavaMailSender.class);
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(message);
        doThrow(new MailSendException("smtp caido")).when(sender).send(message);
        PasswordEmailService service = new PasswordEmailService(provider, properties);

        assertThatCode(() ->
                service.sendTemporaryPassword("j@x.com", "jperez", "Temp12345Ab"))
                .doesNotThrowAnyException();
    }
}