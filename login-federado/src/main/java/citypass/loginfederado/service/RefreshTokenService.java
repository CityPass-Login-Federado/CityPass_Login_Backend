package citypass.loginfederado.service;

import citypass.loginfederado.config.CitypassProperties;
import citypass.loginfederado.config.JwtProperties;
import citypass.loginfederado.identity.ClientRegistry;
import citypass.loginfederado.identity.LdapDirectory;
import citypass.loginfederado.identity.LdapDirectoryPerson;
import citypass.loginfederado.model.RefreshToken;
import citypass.loginfederado.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Ciclo de vida del refresh token opaco (spec §4.2 / decisión D9):
 *
 * - issueInitial: alta con cadena nueva (chain_id).
 * - continueChain: valida el token crudo contra su hash, REVISA reuso,
 *   revoca el canjeado (persistido), y REVALIDA la persona contra LDAP
 *   en cada canje — habilitación y grupos ACTUALES, jamás los guardados.
 * - issueNext: nace el eslabón siguiente de la misma cadena.
 * - revokeSingle: logout; la revocación se PERSISTE.
 *
 * Reuso = robo: un token ya canjeado que reaparece significa dos copias
 * circulando; rechazar solo ese no sirve (el ladrón tiene el siguiente).
 * Se revoca TODA la cadena y se fuerza login (RFC 9700).
 */
@Service
public class RefreshTokenService {

    private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTE_LENGTH = 64;

    private final RefreshTokenRepository repository;
    private final LdapDirectory ldapDirectory;
    private final ClientRegistry clientRegistry;
    private final JwtProperties jwtProperties;

    public RefreshTokenService(RefreshTokenRepository repository,
                               LdapDirectory ldapDirectory,
                               ClientRegistry clientRegistry,
                               JwtProperties jwtProperties) {
        this.repository = repository;
        this.ldapDirectory = ldapDirectory;
        this.clientRegistry = clientRegistry;
        this.jwtProperties = jwtProperties;
    }

    /** El resultado de un canje exitoso: persona REVALIDADA + cadena viva. */
    public record ChainContinuation(
            LdapDirectoryPerson person,
            UUID chainId,
            CitypassProperties.Client client
    ) {
    }

    public String issueInitial(LdapDirectoryPerson person, CitypassProperties.Client client) {
        return persist(person, client, UUID.randomUUID());
    }

    public String issueNext(LdapDirectoryPerson person, UUID chainId, CitypassProperties.Client client) {
        return persist(person, client, chainId);
    }

    /**
     * Valida y rota. Devuelve la persona releída desde LDAP (fresca) junto
     * con su cadena y cliente originales.
     */
    @Transactional
    public ChainContinuation continueChain(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BadCredentialsException(ClientRegistry.GENERIC_ERROR_MESSAGE);
        }

        var stored = repository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new BadCredentialsException(ClientRegistry.GENERIC_ERROR_MESSAGE));

        // --- Regla innegociable #3: reuso = robo ---
        if (stored.isRevoked()) {
            int revoked = repository.revokeChain(stored.getChainId(), Instant.now());
            securityLog.warn("REUSO DE REFRESH TOKEN detectado: cadena completa revocada " +
                    "(chain={} tokens_revocados={})", stored.getChainId(), revoked);
            throw new BadCredentialsException(ClientRegistry.GENERIC_ERROR_MESSAGE);
        }
        if (stored.isExpired()) {
            throw new BadCredentialsException(ClientRegistry.GENERIC_ERROR_MESSAGE);
        }

        // Rotación: el canjeado muere AHORA, con persistencia real.
        stored.revoke(Instant.now());
        repository.save(stored);

        // --- Regla innegociable #1: revalidar contra LDAP en cada canje ---
        LdapDirectoryPerson person = ldapDirectory.reloadBySub(stored.getSub())
                // Borrada o deshabilitada entre canjes: sesión muerta de verdad.
                .orElseThrow(() -> new BadCredentialsException(ClientRegistry.GENERIC_ERROR_MESSAGE));

        CitypassProperties.Client client = clientRegistry.requireHuman(stored.getClientId());
        if (!client.audience().equals(stored.getAudience())) {
            // El registro de clientes cambió bajo nuestros pies: fallar ruidoso
            // para nosotros, genérico para afuera.
            securityLog.error("Audience del refresh ({}) difiere del registro actual ({})",
                    stored.getAudience(), client.audience());
            throw new BadCredentialsException(ClientRegistry.GENERIC_ERROR_MESSAGE);
        }

        return new ChainContinuation(person, stored.getChainId(), client);
    }

    /**
     * Logout por refresh_token (contrato público). Persiste la revocación —
     * en main anterior el logout modificaba objetos en memoria y nunca
     * guardaba: el botón existía y no hacía nada. Token desconocido: 204
     * silencioso (no se revela si alguna vez existió).
     */
    @Transactional
    public void revokeSingle(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        repository.findByTokenHash(hash(rawToken)).ifPresent(stored -> {
            stored.revoke(Instant.now());
            repository.save(stored);
        });
    }

    /** Logout masivo / deshabilitación: mata todas las sesiones de una persona. */
    @Transactional
    public int revokeAllForSub(String sub) {
        return repository.revokeAllForSub(sub, Instant.now());
    }

    private String persist(LdapDirectoryPerson person, CitypassProperties.Client client, UUID chainId) {
        String rawToken = generateRawToken();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(jwtProperties.refreshTokenExpirationHours() * 60 * 60);

        repository.save(new RefreshToken(
                person.sub(), chainId, client.clientId(), client.audience(),
                hash(rawToken), now, expiresAt));

        return rawToken;
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(rawToken.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
