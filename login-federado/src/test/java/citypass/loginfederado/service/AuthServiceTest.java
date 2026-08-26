package citypass.loginfederado.service;

import citypass.loginfederado.config.CitypassProperties;
import citypass.loginfederado.config.JwtProperties;
import citypass.loginfederado.dto.LoginRequest;
import citypass.loginfederado.dto.RefreshRequest;
import citypass.loginfederado.event.EventPublisher;
import citypass.loginfederado.identity.ClientRegistry;
import citypass.loginfederado.identity.LdapDirectory;
import citypass.loginfederado.identity.LdapDirectoryPerson;
import citypass.loginfederado.security.AnomalyRiskClient;
import citypass.loginfederado.token.AccessTokenIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private static final String GENERIC = ClientRegistry.GENERIC_ERROR_MESSAGE;
    private final CitypassProperties.Client client = new CitypassProperties.Client(
            "citypass-reclamos-web", null, "citypass-reclamos-api", "reclamos", false, "human", null);
    private final LdapDirectoryPerson person = new LdapDirectoryPerson(
            "uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local",
            "U000042", "jperez", "Juan Perez", "jperez@citypass.local", "reclamos",
            List.of("soporte-n2"));

    private LdapDirectory ldap;
    private ClientRegistry clients;
    private AccessTokenIssuer issuer;
    private RefreshTokenService refresh;
    private JwtProperties jwtProperties;
    private EventPublisher events;
    private LoginAttemptService attempts;
    private AnomalyRiskClient anomaly;
    private AuthService service;

    @BeforeEach
    void setUp() {
        ldap = mock(LdapDirectory.class);
        clients = mock(ClientRegistry.class);
        issuer = mock(AccessTokenIssuer.class);
        refresh = mock(RefreshTokenService.class);
        jwtProperties = new JwtProperties("issuer", 15, 8, 60, "k", "k");
        events = mock(EventPublisher.class);
        attempts = mock(LoginAttemptService.class);
        anomaly = mock(AnomalyRiskClient.class);
        service = new AuthService(ldap, clients, issuer, refresh, jwtProperties, events, attempts, anomaly);
        when(clients.requireHuman(client.clientId())).thenReturn(client);
    }

    @Test
    void rejectsEmptyPasswordBeforeTouchingLdap() {
        LoginRequest request = new LoginRequest("jperez", "", client.clientId());
        assertThatThrownBy(() -> service.login(request, "1.2.3.4", "JUnit"))
                .isInstanceOf(BadCredentialsException.class).hasMessage(GENERIC);
        verify(ldap, never()).findByUid(anyString());
        verify(attempts).recordAttempt("jperez", "1.2.3.4", "JUnit", false);
    }

    @Test
    void unknownUserUsesDummyBindAndGenericError() {
        when(ldap.findByUid("jperez")).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.login(new LoginRequest("jperez", "secret", client.clientId()), "1.2.3.4", null))
                .isInstanceOf(BadCredentialsException.class).hasMessage(GENERIC);
        verify(ldap).dummyBind("secret");
        verify(attempts).recordAttempt("jperez", "1.2.3.4", null, false);
    }

    @Test
    void rejectsWrongModuleWithSameGenericError() throws Exception {
        LdapDirectoryPerson mobility = 
            new LdapDirectoryPerson(
                person.dn(),
                person.sub(),
                person.uid(),
                person.fullName(),
                person.email(),
                "movilidad",
                person.groups()
            );
        
        when(ldap.findByUid("jperez")).thenReturn(java.util.Optional.of(mobility));
        when(clients.acceptsModule(client, "movilidad")).thenReturn(false);

        assertThatThrownBy(() -> 
            service.login(
                new LoginRequest(
                    "jperez",
                    "secret",
                    client.clientId()
                ),
                "ip",
                "ua"
            )
        )
            .isInstanceOf(BadCredentialsException.class).hasMessage(GENERIC);
        
        verify(ldap, never()).bind(anyString(), anyString());
        verify(attempts).recordAttempt("jperez", "ip", "ua", false);
    }

    @Test
    void successfulLoginIssuesAccessRefreshAndEvent() throws Exception {
        when(ldap.findByUid("jperez")).thenReturn(java.util.Optional.of(person));
        when(clients.acceptsModule(client, "reclamos")).thenReturn(true);
        when(anomaly.score("jperez", "ip", "ua"))
            .thenReturn(
                new citypass.loginfederado.dto.AnomalyScoreResponse(
                    0.01,
                    "ALLOW",
                    List.of()
                )
            );
        
        when(issuer.issueHuman(person, client)).thenReturn("access");
        when(refresh.issueInitial(person, client)).thenReturn("refresh");

        var response = service.login(new LoginRequest("jperez", "secret", client.clientId()), "ip", "ua");

        assertThat(response.accessToken()).isEqualTo("access");
        assertThat(response.refreshToken()).isEqualTo("refresh");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
        verify(attempts).recordAttempt("jperez", "ip", "ua", true);
        verify(events).publish(eq("usuario.autenticado"), any());
    }

    @Test
    void anomalyBlockProducesGenericErrorAndFailedAttempt() throws Exception {
        when(ldap.findByUid("jperez")).thenReturn(java.util.Optional.of(person));
        when(clients.acceptsModule(client, "reclamos")).thenReturn(true);
        when(anomaly.score(anyString(), anyString(), any()))
            .thenReturn(
                new citypass.loginfederado.dto.AnomalyScoreResponse(
                    0.99,
                    "BLOCK",
                    List.of("risk")
                )
            );

        assertThatThrownBy(() -> 
            service.login(
                new LoginRequest(
                    "jperez",
                    "secret",
                    client.clientId()
                ),
                "ip",
                "ua"
            )
        ).isInstanceOf(BadCredentialsException.class).hasMessage(GENERIC);
        
        verify(attempts).recordAttempt("jperez", "ip", "ua", false);
        verify(issuer, never()).issueHuman(any(), any());
    }

    @Test
    void refreshRotatesAccessAndRefresh() {
        UUID chain = UUID.randomUUID();
        when(refresh.continueChain("old"))
            .thenReturn(new RefreshTokenService.ChainContinuation(person, chain, client));
        when(issuer.issueHuman(person, client)).thenReturn("new-access");
        when(refresh.issueNext(person, chain, client)).thenReturn("new-refresh");

        var response = service.refresh(new RefreshRequest("old"));
        assertThat(response.accessToken()).isEqualTo("new-access");
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
        verify(refresh).issueNext(person, chain, client);
    }

    @Test
    void logoutDelegatesToRefreshService() {
        service.logout("refresh");
        verify(refresh).revokeSingle("refresh");
    }
}
