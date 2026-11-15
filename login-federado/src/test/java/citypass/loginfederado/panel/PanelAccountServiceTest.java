package citypass.loginfederado.panel;

import citypass.loginfederado.service.LoginAttemptService;
import javax.naming.directory.ModificationItem;
import javax.naming.ldap.LdapName;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PanelAccountServiceTest {
    private LdapTemplate ldap;
    private PanelAuditService audit;
    private LoginAttemptService loginAttempts;
    private PanelAccountService service;
    private final PanelAuthorization.Delegate actor = new PanelAuthorization.Delegate("U000001", "admin", "reclamos");

    @BeforeEach
    void setUp() {
        ldap = mock(LdapTemplate.class);
        audit = mock(PanelAuditService.class);
        loginAttempts = mock(LoginAttemptService.class);
        service = new PanelAccountService(new PanelLdapSupport(ldap), audit, loginAttempts);
    }

    @Test
    void disableEnableAndResetPasswordWriteExpectedAttributes() {
                doReturn(personContext("jperez")).when(ldap).lookupContext(any(LdapName.class));
        service.disablePerson(actor, "reclamos", "jperez");
        service.enablePerson(actor, "reclamos", "jperez");
        service.resetPassword(actor, "reclamos", "jperez", "12345678");
        verify(ldap, times(3)).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        verify(audit).record(eq(actor), eq("PERSON_DISABLED"), anyString(), isNull());
        verify(audit).record(eq(actor), eq("PERSON_ENABLED"), anyString(), isNull());
        verify(audit).record(eq(actor), eq("PASSWORD_RESET"), anyString(), isNull());
    }

    @Test
    void disableTranslatesLdapError() {
        doReturn(personContext("jperez")).when(ldap).lookupContext(any(LdapName.class));
        doThrow(new org.springframework.ldap.UncategorizedLdapException(new RuntimeException("no se pudo escribir")))
                .when(ldap).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        assertThatThrownBy(() -> service.disablePerson(actor, "reclamos", "jperez"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No se pudo deshabilitar");
    }

    @Test
    void enableTranslatesLdapError() {
        doReturn(personContext("jperez")).when(ldap).lookupContext(any(LdapName.class));
        doThrow(new org.springframework.ldap.UncategorizedLdapException(new RuntimeException("no se pudo escribir")))
                .when(ldap).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        assertThatThrownBy(() -> service.enablePerson(actor, "reclamos", "jperez"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No se pudo rehabilitar");
    }

    @Test
    void resetPasswordRejectsShortPassword() {
        assertThatThrownBy(() -> service.resetPassword(actor, "reclamos", "jperez", "123"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ldap);
    }

    @Test
    void setPasswordWritesUserPasswordAndRejectsShortValues() {
        doReturn(personContext("jperez")).when(ldap).lookupContext(any(LdapName.class));
        service.setPassword("reclamos", "jperez", "12345678");
        assertThatThrownBy(() -> service.setPassword("reclamos", "jperez", "123"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(ldap).lookupContext(any(LdapName.class));
        verify(ldap).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
    }

    @Test
    void clearLoginLockoutValidatesPersonClearsFailuresAndAuditsWithoutWritingLdap() {
        doReturn(personContext("jperez")).when(ldap).lookupContext(any(LdapName.class));

        service.clearLoginLockout(actor, "reclamos", "jperez");

        verify(loginAttempts).clearLockout("jperez");
        verify(audit).record(actor, "LOGIN_LOCKOUT_CLEARED",
                "uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local",
                "Bloqueo temporal e intentos fallidos limpiados");
        verify(ldap, never()).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
    }

    @Test
    void clearLoginLockoutReturns404AndDoesNothingWhenPersonDoesNotExist() {
        doThrow(new org.springframework.ldap.NameNotFoundException("missing"))
                .when(ldap).lookupContext(any(LdapName.class));

        assertThatThrownBy(() -> service.clearLoginLockout(actor, "reclamos", "nobody"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                        ((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        verifyNoInteractions(loginAttempts);
        verifyNoInteractions(audit);
        verify(ldap, never()).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
    }

    private DirContextOperations personContext(String uid) {
        DirContextOperations ctx = mock(DirContextOperations.class);
        when(ctx.getStringAttribute("employeeNumber")).thenReturn("U000042");
        when(ctx.getStringAttribute("uid")).thenReturn(uid);
        when(ctx.getStringAttribute("givenName")).thenReturn("Juan");
        when(ctx.getStringAttribute("sn")).thenReturn("Perez");
        when(ctx.getStringAttribute("mail")).thenReturn(uid + "@x.com");
        when(ctx.getStringAttribute("pwdAccountLockedTime")).thenReturn(null);
        return ctx;
    }
}
