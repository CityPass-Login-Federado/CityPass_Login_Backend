package citypass.loginfederado.service;

import citypass.loginfederado.config.PasswordResetProperties;
import citypass.loginfederado.identity.LdapDirectory;
import citypass.loginfederado.identity.LdapDirectoryPerson;
import citypass.loginfederado.model.PasswordResetToken;
import citypass.loginfederado.panel.PanelAccountService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PasswordServiceTest {

    private final LdapDirectoryPerson person = new LdapDirectoryPerson(
            "uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local",
            "U000042", "jperez", "Juan Perez", "jperez@citypass.local", "reclamos",
            List.of("soporte-n2"));

    private final PasswordResetProperties properties = new PasswordResetProperties(
            "no-reply@citypass.local", true, "http://localhost:3000/reset-password",
            30, 5, 3, 50);

    // Executor directo: el trabajo async corre sincrónico en el test.
    private final Executor directExecutor = Runnable::run;

    private LdapDirectory ldap;
    private PanelAccountService accounts;
    private PasswordEmailService emailSender;
    private RefreshTokenService refresh;
    private PasswordResetLimiter limiter;
    private PasswordResetTokenStore tokenStore;
    private PasswordService service;

    @BeforeEach
    void setUp() {
        ldap = mock(LdapDirectory.class);
        accounts = mock(PanelAccountService.class);
        emailSender = mock(PasswordEmailService.class);
        refresh = mock(RefreshTokenService.class);
        limiter = mock(PasswordResetLimiter.class);
        tokenStore = mock(PasswordResetTokenStore.class);
        service = new PasswordService(ldap, accounts, emailSender, refresh,
                limiter, tokenStore, properties, directExecutor);
    }

    private void acceptRequest() {
        when(limiter.tryAcquire(anyString(), any())).thenReturn(true);
        when(emailSender.sendResetLink(anyString(), anyString(), anyString())).thenReturn(true);
    }

    private PasswordResetToken usableToken() {
        return new PasswordResetToken("U000042", "jperez", "hash-valido",
                Instant.now(), Instant.now().plusSeconds(1800));
    }

    // --- Solicitud: emite token, NUNCA escribe LDAP ---

    @Test
    void requestPasswordResetIssuesTokenAndSendsLinkWithoutTouchingLdap() {
        when(ldap.findByUid("jperez")).thenReturn(Optional.of(person));
        acceptRequest();

        assertThatCode(() -> service.requestPasswordReset(" jperez ", "10.0.0.1"))
                .doesNotThrowAnyException();

        verify(tokenStore).issue(eq("U000042"), eq("jperez"), anyString(), eq(30));
        verify(emailSender).sendResetLink(eq("jperez@citypass.local"), eq("jperez"), anyString());
        // La solicitud JAMÁS escribe la credencial: solo el canje toca LDAP.
        verify(accounts, never()).setPassword(anyString(), anyString(), anyString());
    }

    @Test
    void requestPasswordResetThrottledIsSilent() {
        when(limiter.tryAcquire("jperez", "10.0.0.1")).thenReturn(false);

        assertThatCode(() -> service.requestPasswordReset("jperez", "10.0.0.1"))
                .doesNotThrowAnyException();

        verifyNoInteractions(ldap, accounts, tokenStore, emailSender);
    }

    @Test
    void requestPasswordResetUnknownUserIsSilent() {
        when(ldap.findByUid("nobody")).thenReturn(Optional.empty());
        when(limiter.tryAcquire(anyString(), any())).thenReturn(true);

        assertThatCode(() -> service.requestPasswordReset("nobody", "10.0.0.1"))
                .doesNotThrowAnyException();

        verify(tokenStore, never()).issue(anyString(), anyString(), anyString(), anyInt());
        verifyNoInteractions(accounts, emailSender);
    }

    @Test
    void requestPasswordResetWithoutEmailDoesNothing() {
        LdapDirectoryPerson noMail = new LdapDirectoryPerson(
                person.dn(), person.sub(), person.uid(), person.fullName(),
                null, person.module(), person.groups());
        when(ldap.findByUid("jperez")).thenReturn(Optional.of(noMail));
        when(limiter.tryAcquire(anyString(), any())).thenReturn(true);

        service.requestPasswordReset("jperez", "10.0.0.1");

        verify(tokenStore, never()).issue(anyString(), anyString(), anyString(), anyInt());
        verifyNoInteractions(accounts, emailSender);
    }

    @Test
    void requestPasswordResetMailFailureKeepsPriorCredentialAndReleasesToken() {
        // Issue 3: si el SMTP falla, el usuario conserva su contraseña
        // vigente (LDAP intacto) y el token se libera para reintentar.
        when(ldap.findByUid("jperez")).thenReturn(Optional.of(person));
        when(limiter.tryAcquire(anyString(), any())).thenReturn(true);
        when(emailSender.sendResetLink(anyString(), anyString(), anyString())).thenReturn(false);

        assertThatCode(() -> service.requestPasswordReset("jperez", "10.0.0.1"))
                .doesNotThrowAnyException();

        verify(accounts, never()).setPassword(anyString(), anyString(), anyString());
        verify(tokenStore).discard("U000042");
    }

    @Test
    void requestPasswordResetAsyncFailureIsSilentAndReleasesToken() {
        // El envío corre en otro hilo: si el mail explota (no solo false) y
        // hasta el discard falla, la solicitud sigue siendo 204.
        when(ldap.findByUid("jperez")).thenReturn(Optional.of(person));
        when(limiter.tryAcquire(anyString(), any())).thenReturn(true);
        when(emailSender.sendResetLink(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("smtp roto"));
        doThrow(new RuntimeException("db caída")).when(tokenStore).discard(anyString());

        assertThatCode(() -> service.requestPasswordReset("jperez", "10.0.0.1"))
                .doesNotThrowAnyException();

        verify(accounts, never()).setPassword(anyString(), anyString(), anyString());
    }

    @Test
    void redeemResetTokenSurvivesBrokenDirectoryLookup() {
        // rejectInvalidToken es a prueba de todo: aunque el dummyBind mismo
        // explote (ContextSource nulo), el rechazo sigue siendo el 422
        // uniforme y nunca se escribe ni se revoca nada.
        org.springframework.ldap.core.LdapTemplate brokenTemplate =
                mock(org.springframework.ldap.core.LdapTemplate.class);
        when(brokenTemplate.getContextSource()).thenReturn(null);
        PasswordService strictService = new PasswordService(
                new citypass.loginfederado.identity.LdapDirectory(brokenTemplate),
                accounts, emailSender, refresh, limiter, tokenStore, properties, directExecutor);
        when(tokenStore.findByHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> strictService.redeemResetToken("falso", "nuevaClave123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inválido o expiró");

        verify(accounts, never()).setPassword(anyString(), anyString(), anyString());
        verify(refresh, never()).revokeAllForSub(anyString(), anyString());
    }

    @Test
    void redeemResetTokenBlankTokenFailsWithoutSideEffects() {
        assertThatThrownBy(() -> service.redeemResetToken("", "nuevaClave123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inválido o expiró");
        assertThatThrownBy(() -> service.redeemResetToken(null, "nuevaClave123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inválido o expiró");

        verifyNoInteractions(tokenStore, accounts, refresh);
        verify(ldap, never()).reloadBySub(anyString());
    }

    @Test
    void requestPasswordResetLookupFailureIsSilent() {
        when(ldap.findByUid("jperez")).thenThrow(new RuntimeException("ldap down"));
        when(limiter.tryAcquire(anyString(), any())).thenReturn(true);

        assertThatCode(() -> service.requestPasswordReset("jperez", "10.0.0.1"))
                .doesNotThrowAnyException();

        verifyNoInteractions(accounts, tokenStore, emailSender);
    }

    // --- Canje: escribe LDAP + revoca sesiones ---

    @Test
    void redeemResetTokenWritesLdapAndRevokesSessions() {
        // El servicio hashea el crudo antes de buscar: el hash exacto es
        // detalle interno, se matchea por tipo.
        when(tokenStore.findByHash(anyString())).thenReturn(Optional.of(usableToken()));
        when(ldap.reloadBySub("U000042")).thenReturn(Optional.of(person));
        when(tokenStore.consumeIfUsable(anyString(), any(Instant.class))).thenReturn(true);
        when(refresh.revokeAllForSub("U000042", "reclamos")).thenReturn(3);

        assertThatCode(() -> service.redeemResetToken("token-crudo-del-enlace", "nuevaClave123"))
                .doesNotThrowAnyException();

        verify(accounts).setPassword("reclamos", "jperez", "nuevaClave123");
        verify(refresh).revokeAllForSub("U000042", "reclamos");
    }

    @Test
    void redeemResetTokenUnknownTokenFailsWithoutSideEffects() {
        when(tokenStore.findByHash("falso")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.redeemResetToken("falso", "nuevaClave123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inválido o expiró");

        verify(accounts, never()).setPassword(anyString(), anyString(), anyString());
        verify(refresh, never()).revokeAllForSub(anyString(), anyString());
    }

    @Test
    void redeemResetTokenUsedOrExpiredFailsWithoutSideEffects() {
        PasswordResetToken used = usableToken();
        used.markUsed(Instant.now());
        when(tokenStore.findByHash(anyString())).thenReturn(Optional.of(used));

        assertThatThrownBy(() -> service.redeemResetToken("usado", "nuevaClave123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inválido o expiró");

        verify(accounts, never()).setPassword(anyString(), anyString(), anyString());
        verify(refresh, never()).revokeAllForSub(anyString(), anyString());
        verify(ldap, never()).reloadBySub(anyString());
    }

    @Test
    void redeemResetTokenDisabledAccountFailsWithoutSideEffects() {
        when(tokenStore.findByHash(anyString())).thenReturn(Optional.of(usableToken()));
        when(ldap.reloadBySub("U000042")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.redeemResetToken("hash-crudo", "nuevaClave123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inválido o expiró");

        verify(accounts, never()).setPassword(anyString(), anyString(), anyString());
        verify(refresh, never()).revokeAllForSub(anyString(), anyString());
    }

    @Test
    void redeemResetTokenLostRaceFailsWithoutWriting() {
        when(tokenStore.findByHash(anyString())).thenReturn(Optional.of(usableToken()));
        when(ldap.reloadBySub("U000042")).thenReturn(Optional.of(person));
        when(tokenStore.consumeIfUsable(anyString(), any(Instant.class))).thenReturn(false);

        assertThatThrownBy(() -> service.redeemResetToken("hash-crudo", "nuevaClave123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inválido o expiró");

        verify(accounts, never()).setPassword(anyString(), anyString(), anyString());
        verify(refresh, never()).revokeAllForSub(anyString(), anyString());
    }

    @Test
    void redeemResetTokenRejectsShortNewPassword() {
        assertThatThrownBy(() -> service.redeemResetToken("cualquiera", "corta"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ldap, accounts, refresh, tokenStore);
    }

    // --- Perfil propio ---

    @Test
    void getProfileMapsDirectoryPerson() {
        when(ldap.reloadBySub("U000042")).thenReturn(Optional.of(person));

        var profile = service.getProfile("U000042");

        assertThat(profile.uid()).isEqualTo("jperez");
        assertThat(profile.employeeNumber()).isEqualTo("U000042");
        assertThat(profile.fullName()).isEqualTo("Juan Perez");
        assertThat(profile.email()).isEqualTo("jperez@citypass.local");
        assertThat(profile.module()).isEqualTo("reclamos");
        assertThat(profile.groups()).containsExactly("soporte-n2");
    }

    @Test
    void getProfileMissingOrDisabledAccountIs404() {
        when(ldap.reloadBySub("U000042")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProfile("U000042"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // --- Cambio desde perfil (sin cambios de comportamiento) ---

    @Test
    void changePasswordRejectsShortNewPassword() {
        assertThatThrownBy(() -> service.changePassword("U000042", "actual", "corta"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ldap, accounts, refresh);
    }

    @Test
    void changePasswordRejectsMissingOrDisabledAccount() {
        when(ldap.reloadBySub("U000042")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changePassword("U000042", "actual", "12345678"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ya no existe");
        verify(accounts, never()).setPassword(anyString(), anyString(), anyString());
    }

    @Test
    void changePasswordRejectsWrongCurrentPassword() throws Exception {
        when(ldap.reloadBySub("U000042")).thenReturn(Optional.of(person));
        doThrow(new org.springframework.ldap.UncategorizedLdapException(new RuntimeException("bad password")))
                .when(ldap).bind(person.dn(), "mal");
        assertThatThrownBy(() -> service.changePassword("U000042", "mal", "12345678"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actual es incorrecta");
        verify(accounts, never()).setPassword(anyString(), anyString(), anyString());
        verify(refresh, never()).revokeAllForSub(anyString(), anyString());
    }

    @Test
    void changePasswordSuccessWritesAndRevokesSessions() throws Exception {
        when(ldap.reloadBySub("U000042")).thenReturn(Optional.of(person));
        when(refresh.revokeAllForSub("U000042", "reclamos")).thenReturn(3);
        service.changePassword("U000042", "actual", "12345678");
        verify(ldap).bind(person.dn(), "actual");
        verify(accounts).setPassword("reclamos", "jperez", "12345678");
        verify(refresh).revokeAllForSub("U000042", "reclamos");
    }
}
