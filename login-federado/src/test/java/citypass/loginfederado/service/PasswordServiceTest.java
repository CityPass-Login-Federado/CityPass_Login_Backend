package citypass.loginfederado.service;

import citypass.loginfederado.identity.LdapDirectory;
import citypass.loginfederado.identity.LdapDirectoryPerson;
import citypass.loginfederado.panel.PanelDirectoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PasswordServiceTest {

    private static final String GENERATED = "AbC123XyZ987";

    private final LdapDirectoryPerson person = new LdapDirectoryPerson(
            "uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local",
            "U000042", "jperez", "Juan Perez", "jperez@citypass.local", "reclamos",
            List.of("soporte-n2"));

    private LdapDirectory ldap;
    private PanelDirectoryService directory;
    private PasswordEmailService emailSender;
    private RefreshTokenService refresh;
    private PasswordService service;

    @BeforeEach
    void setUp() throws Exception {
        ldap = mock(LdapDirectory.class);
        directory = mock(PanelDirectoryService.class);
        emailSender = mock(PasswordEmailService.class);
        refresh = mock(RefreshTokenService.class);
        service = new PasswordService(ldap, directory, emailSender, refresh);
    }

    @Test
    void requestTemporaryPasswordWritesDirectoryAndSendsEmail() {
        when(ldap.findByUid("jperez")).thenReturn(Optional.of(person));
        service.requestTemporaryPassword(" jperez ");
        verify(directory).setPassword(eq("reclamos"), eq("jperez"), anyString());
        verify(emailSender).sendTemporaryPassword(eq("jperez@citypass.local"), eq("jperez"), anyString());
    }

    @Test
    void requestTemporaryPasswordUnknownUserIsSilent() {
        when(ldap.findByUid("nobody")).thenReturn(Optional.empty());
        service.requestTemporaryPassword("nobody");
        verifyNoInteractions(directory);
        verifyNoInteractions(emailSender);
    }

    @Test
    void requestTemporaryPasswordWithoutEmailDoesNothing() {
        LdapDirectoryPerson noMail = new LdapDirectoryPerson(
                person.dn(), person.sub(), person.uid(), person.fullName(),
                null, person.module(), person.groups());
        when(ldap.findByUid("jperez")).thenReturn(Optional.of(noMail));
        service.requestTemporaryPassword("jperez");
        verifyNoInteractions(directory);
        verifyNoInteractions(emailSender);
    }

    @Test
    void requestTemporaryPasswordWriteFailureIsSilent() {
        // Contrato: SIEMPRE 204. Un fallo de escritura LDAP no debe propagarse.
        when(ldap.findByUid("jperez")).thenReturn(Optional.of(person));
        doThrow(new org.springframework.ldap.UncategorizedLdapException(new RuntimeException("ldap down")))
                .when(directory).setPassword(eq("reclamos"), eq("jperez"), anyString());
        service.requestTemporaryPassword("jperez");
        verify(emailSender, never()).sendTemporaryPassword(anyString(), anyString(), anyString());
    }

    @Test
    void requestTemporaryPasswordLookupFailureIsSilent() {
        when(ldap.findByUid("jperez")).thenThrow(new RuntimeException("ldap down"));
        service.requestTemporaryPassword("jperez");
        verifyNoInteractions(directory);
        verifyNoInteractions(emailSender);
    }

    @Test
    void requestTemporaryPasswordNullPersonIsSilent() {
        // Mapper devuelve null en fichas corruptas/deshabilitadas: no debe dar NPE/500.
        when(ldap.findByUid("jperez")).thenReturn(Optional.ofNullable(null));
        service.requestTemporaryPassword("jperez");
        verifyNoInteractions(directory);
        verifyNoInteractions(emailSender);
    }

    @Test
    void changePasswordRejectsShortNewPassword() {
        assertThatThrownBy(() -> service.changePassword("U000042", "actual", "corta"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ldap, directory, refresh);
    }

    @Test
    void changePasswordRejectsMissingOrDisabledAccount() {
        when(ldap.reloadBySub("U000042")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changePassword("U000042", "actual", "12345678"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ya no existe");
        verify(directory, never()).setPassword(anyString(), anyString(), anyString());
    }

    @Test
    void changePasswordRejectsWrongCurrentPassword() throws Exception {
        when(ldap.reloadBySub("U000042")).thenReturn(Optional.of(person));
        doThrow(new org.springframework.ldap.UncategorizedLdapException(new RuntimeException("bad password")))
                .when(ldap).bind(person.dn(), "mal");
        assertThatThrownBy(() -> service.changePassword("U000042", "mal", "12345678"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actual es incorrecta");
        verify(directory, never()).setPassword(anyString(), anyString(), anyString());
        verify(refresh, never()).revokeAllForSub(anyString());
    }

    @Test
    void changePasswordSuccessWritesAndRevokesSessions() throws Exception {
        when(ldap.reloadBySub("U000042")).thenReturn(Optional.of(person));
        when(refresh.revokeAllForSub("U000042")).thenReturn(3);
        service.changePassword("U000042", "actual", "12345678");
        verify(ldap).bind(person.dn(), "actual");
        verify(directory).setPassword("reclamos", "jperez", "12345678");
        verify(refresh).revokeAllForSub("U000042");
    }
}