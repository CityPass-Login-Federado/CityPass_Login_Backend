package citypass.loginfederado.service;

import citypass.loginfederado.config.CitypassProperties;
import citypass.loginfederado.config.JwtProperties;
import citypass.loginfederado.identity.ClientRegistry;
import citypass.loginfederado.identity.LdapDirectory;
import citypass.loginfederado.identity.LdapDirectoryPerson;
import citypass.loginfederado.model.RefreshToken;
import citypass.loginfederado.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.BadCredentialsException;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Las tres reglas innegociables del refresh (D9 / spec §4.2):
 * rotación persistida, reuso = revocación de TODA la cadena (RFC 9700) y
 * revalidación contra LDAP en cada canje. Más el logout que SÍ guarda.
 */
class RefreshTokenServiceTest {

    private static final UUID CHAIN_ID = UUID.randomUUID();
    private static final String RAW_TOKEN = "token-crudo-de-prueba-0123456789abcdef";
    private static final String TOKEN_HASH = sha256B64(RAW_TOKEN);

    private RefreshTokenRepository repository;
    private LdapDirectory ldapDirectory;
    private ClientRegistry clientRegistry;
    private RefreshTokenService service;

    private final CitypassProperties.Client client = new CitypassProperties.Client(
            "citypass-reclamos-web", null, "citypass-reclamos-api", "reclamos", false, "human", null);

    private final LdapDirectoryPerson person = new LdapDirectoryPerson(
            "uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local",
            "U000042", "jperez", "Juan Perez", null, "Reclamos", List.of("delegados"));

    @BeforeEach
    void setUp() {
        repository = mock(RefreshTokenRepository.class);
        ldapDirectory = mock(LdapDirectory.class);
        var props = new CitypassProperties(List.of(client));
        clientRegistry = new ClientRegistry(props);
        var jwtProps = new JwtProperties("https://idp.citypass.local", 15, 8, 60, "k", "k");
        service = new RefreshTokenService(repository, ldapDirectory, clientRegistry, jwtProps);
    }

    @Test
    void reuseOfRotatedTokenRevokesWholeChain() {
        RefreshToken reused = storedToken(true);
        when(repository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(reused));

        assertThatThrownBy(() -> service.continueChain(RAW_TOKEN))
                .isInstanceOf(BadCredentialsException.class);

        // RFC 9700: no basta rechazar este token — muere la cadena entera.
        verify(repository).revokeChain(eq(CHAIN_ID), any(Instant.class));
        // El reuso se corta ANTES de tocar LDAP: nada que revalidar para un ladrón.
        verify(ldapDirectory, never()).reloadBySub(any());
    }

    @Test
    void validExchangeRevokesOldLinkAndReloadsPersonFromLdap() {
        RefreshToken stored = storedToken(false);
        when(repository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(stored));
        when(ldapDirectory.reloadBySub("U000042")).thenReturn(Optional.of(person));

        RefreshTokenService.ChainContinuation continuation = service.continueChain(RAW_TOKEN);

        // La persona viene de LDAP FRESCA (grupos/habilitación de AHORA)
        assertThat(continuation.person()).isEqualTo(person);
        assertThat(continuation.chainId()).isEqualTo(CHAIN_ID);
        assertThat(continuation.client().clientId()).isEqualTo(client.clientId());

        // Rotación con persistencia real: el eslabón canjeado queda revocado
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isRevoked()).isTrue();
        // La búsqueda fue por HASH: el valor crudo jamás toca la base
        verify(repository).findByTokenHash(TOKEN_HASH);
    }

    @Test
    void personDeletedOrDisabledBetweenExchangesKillsSession() {
        RefreshToken stored = storedToken(false);
        when(repository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(stored));
        when(ldapDirectory.reloadBySub("U000042")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.continueChain(RAW_TOKEN))
                .isInstanceOf(BadCredentialsException.class);

        // Y el token ya consumido NO vuelve a circular: quedó revocado igual
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isRevoked()).isTrue();
    }

    @Test
    void unknownTokenFailsWithGenericError() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.continueChain(RAW_TOKEN))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage(ClientRegistry.GENERIC_ERROR_MESSAGE);
    }

    @Test
    void expiredTokenIsRejected() {
        RefreshToken expired = new RefreshToken("U000042", CHAIN_ID, client.clientId(),
                client.audience(), TOKEN_HASH,
                Instant.now().minusSeconds(100_000), Instant.now().minusSeconds(10));
        when(repository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.continueChain(RAW_TOKEN))
                .isInstanceOf(BadCredentialsException.class);
        verify(ldapDirectory, never()).reloadBySub(any());
    }

    @Test
    void logoutActuallyPersistsTheRevocation() {
        // Regresión del bug del main anterior: revokeAllFor sin save() = logout fantasma
        RefreshToken alive = storedToken(false);
        when(repository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(alive));

        service.revokeSingle(RAW_TOKEN);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRevokedAt()).isNotNull();
    }

    @Test
    void logoutOfUnknownTokenIsSilent204Behavior() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        service.revokeSingle(RAW_TOKEN);

        verify(repository, never()).save(any());
    }

    @Test
    void initialIssuePersistsHashOnlyAndReturnsRawOnce() {
        String raw = service.issueInitial(person, client);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        RefreshToken persisted = saved.getValue();
        assertThat(persisted.getSub()).isEqualTo("U000042");
        assertThat(persisted.getChainId()).isNotNull();
        assertThat(persisted.getClientId()).isEqualTo(client.clientId());
        assertThat(persisted.getAudience()).isEqualTo(client.audience());
        assertThat(raw).isNotBlank();
        // 8 horas de vida (D9)
        assertThat(persisted.getExpiresAt())
                .isAfter(Instant.now().plusSeconds(8 * 3600 - 120))
                .isBefore(Instant.now().plusSeconds(8 * 3600 + 120));
    }

    private RefreshToken storedToken(boolean revoked) {
        RefreshToken token = new RefreshToken("U000042", CHAIN_ID, client.clientId(),
                client.audience(), TOKEN_HASH,
                Instant.now().minusSeconds(600),
                Instant.now().plusSeconds(7 * 3600));
        if (revoked) {
            token.revoke(Instant.now().minusSeconds(300));
        }
        return token;
    }

    private static String sha256B64(String raw) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(raw.getBytes()));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
