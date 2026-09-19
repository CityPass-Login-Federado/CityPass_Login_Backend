package citypass.loginfederado.service;

import citypass.loginfederado.model.PasswordResetToken;
import citypass.loginfederado.repository.PasswordResetTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordResetTokenStoreTest {

    private PasswordResetTokenRepository repository;
    private PasswordResetTokenStore store;

    @BeforeEach
    void setUp() {
        repository = mock(PasswordResetTokenRepository.class);
        store = new PasswordResetTokenStore(repository);
    }

    @Test
    void issueReplacesPreviousTokenOfTheAccount() {
        Instant before = Instant.now();
        when(repository.save(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PasswordResetToken issued = store.issue("U000042", "jperez", "hash", 30);

        verify(repository).deleteBySub("U000042");
        assertThat(issued.getSub()).isEqualTo("U000042");
        assertThat(issued.getUid()).isEqualTo("jperez");
        assertThat(issued.getTokenHash()).isEqualTo("hash");
        assertThat(issued.getRequestedAt()).isAfterOrEqualTo(before);
        assertThat(issued.getExpiresAt()).isEqualTo(issued.getRequestedAt().plusSeconds(30 * 60L));
        assertThat(issued.getUsedAt()).isNull();
        assertThat(issued.isUsable()).isTrue();
        assertThat(issued.isExpired()).isFalse();
        assertThat(issued.isUsed()).isFalse();
    }

    @Test
    void issuedTokenExposesItsIdAsNullBeforePersist() {
        when(repository.save(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PasswordResetToken issued = store.issue("U000042", "jperez", "hash", 30);

        assertThat(issued.getId()).isNull();
    }

    @Test
    void findByHashDelegates() {
        var token = new PasswordResetToken("U000042", "jperez", "hash",
                Instant.now(), Instant.now().plusSeconds(60));
        when(repository.findByTokenHash("hash")).thenReturn(Optional.of(token));

        assertThat(store.findByHash("hash")).contains(token);
    }

    @Test
    void consumeReturnsTrueOnlyForWinner() {
        when(repository.markUsedIfActive(eq("hash"), any(Instant.class))).thenReturn(1);
        assertThat(store.consumeIfUsable("hash", Instant.now())).isTrue();

        when(repository.markUsedIfActive(eq("hash"), any(Instant.class))).thenReturn(0);
        assertThat(store.consumeIfUsable("hash", Instant.now())).isFalse();
    }

    @Test
    void discardRemovesAccountTokens() {
        store.discard("U000042");

        verify(repository).deleteBySub("U000042");
    }

    @Test
    void usedTokenIsNotUsableAndKeepsFirstUse() {
        PasswordResetToken token = new PasswordResetToken("U000042", "jperez", "hash",
                Instant.now(), Instant.now().plusSeconds(3600));
        Instant first = Instant.now();
        token.markUsed(first);
        token.markUsed(first.plusSeconds(60));

        assertThat(token.isUsed()).isTrue();
        assertThat(token.isUsable()).isFalse();
        assertThat(token.getUsedAt()).isEqualTo(first);
    }

    @Test
    void expiredTokenIsNotUsable() {
        PasswordResetToken token = new PasswordResetToken("U000042", "jperez", "hash",
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(60));

        assertThat(token.isExpired()).isTrue();
        assertThat(token.isUsable()).isFalse();
    }
}
