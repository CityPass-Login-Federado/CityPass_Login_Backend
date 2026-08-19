package citypass.loginfederado.service;

import citypass.loginfederado.config.JwtProperties;
import citypass.loginfederado.dto.AnomalyScoreResponse;
import citypass.loginfederado.dto.LoginRequest;
import citypass.loginfederado.dto.LoginResponse;
import citypass.loginfederado.dto.RefreshRequest;
import citypass.loginfederado.event.EventPublisher;
import citypass.loginfederado.event.UsuarioAutenticadoEvent;
import citypass.loginfederado.exception.AnomalyBlockedException;
import citypass.loginfederado.security.AnomalyRiskClient;
import citypass.loginfederado.security.LdapUserPrincipal;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private final AuthenticationManager ldapAuthenticationManager;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final RefreshTokenService refreshTokenService;
    private final EventPublisher eventPublisher;
    private final LoginAttemptService loginAttemptService;
    private final AnomalyRiskClient anomalyRiskClient;

    public AuthService(AuthenticationManager ldapAuthenticationManager,
                        JwtEncoder jwtEncoder,
                        JwtProperties jwtProperties,
                        RefreshTokenService refreshTokenService,
                        EventPublisher eventPublisher,
                        LoginAttemptService loginAttemptService,
                        AnomalyRiskClient anomalyRiskClient) {
        this.ldapAuthenticationManager = ldapAuthenticationManager;
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
        this.refreshTokenService = refreshTokenService;
        this.eventPublisher = eventPublisher;
        this.loginAttemptService = loginAttemptService;
        this.anomalyRiskClient = anomalyRiskClient;
    }

    public LoginResponse login(LoginRequest request, String ipAddress, String userAgent) {
        loginAttemptService.assertNotLocked(request.username());

        Authentication authentication;
        try {
            authentication = ldapAuthenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );
        } catch (BadCredentialsException ex) {
            loginAttemptService.recordAttempt(request.username(), ipAddress, userAgent, false);
            throw new BadCredentialsException("Usuario o contraseña inválidos");
        }

        loginAttemptService.recordAttempt(request.username(), ipAddress, userAgent, true);

        // Capa 2: consulta al microservicio de detección de anomalías.
        // Corre DESPUÉS de la Capa 1 (bloqueo por umbral) y del login LDAP exitoso,
        // porque necesita saber que la contraseña era correcta para decidir si,
        // aun así, el patrón de acceso amerita rechazar o marcar para revisión.
        AnomalyScoreResponse riskAssessment = anomalyRiskClient.score(
                request.username(), ipAddress, userAgent
        );
        if ("BLOCK".equals(riskAssessment.decision())) {
            throw new AnomalyBlockedException(
                    "Login rechazado por actividad anómala: " + riskAssessment.reasons()
            );
        }
        // TODO: si decision == "REVIEW", marcar el LoginAttempt para auditoría
        // (requiere agregar una columna tipo `flagged_for_review` a login_attempts).

        LdapUserPrincipal principal = (LdapUserPrincipal) authentication.getPrincipal();
        List<String> roles = extractRoles(authentication);

        String accessToken = buildAccessToken(principal.getUsername(), principal.getFullName(),
                principal.getEmail(), roles);
        String refreshToken = refreshTokenService.issueFor(principal.getUsername(),
                principal.getFullName(), principal.getEmail(), roles);

        eventPublisher.publish(
                "usuario.autenticado",
                UsuarioAutenticadoEvent.of(principal.getUsername(), principal.getEmail(), roles)
        );

        return new LoginResponse(accessToken, refreshToken, "Bearer",
                jwtProperties.accessTokenExpirationMinutes() * 60);
    }

    public LoginResponse refresh(RefreshRequest request) {
        RefreshTokenPrincipal principal = refreshTokenService.validateAndRotate(request.refreshToken());

        String accessToken = buildAccessToken(principal.username(), principal.fullName(),
                principal.email(), principal.roles());
        String newRefreshToken = refreshTokenService.issueFor(principal.username(), principal.fullName(),
                principal.email(), principal.roles());

        return new LoginResponse(accessToken, newRefreshToken, "Bearer",
                jwtProperties.accessTokenExpirationMinutes() * 60);
    }

    public void logout(String username) {
        refreshTokenService.revokeAllFor(username);
    }

    private String buildAccessToken(String username, String fullName, String email, List<String> roles) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(jwtProperties.accessTokenExpirationMinutes() * 60);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .audience(List.of(jwtProperties.audience()))
                .subject(username)
                .issuedAt(now)
                .expiresAt(expiry)
                .id(UUID.randomUUID().toString())
                .claim("roles", roles)
                .claim("name", fullName)
                .claim("email", email)
                .claim("token_type", "access")
                .build();

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(jwtProperties.keyId())
                .build();

        Jwt jwt = jwtEncoder.encode(JwtEncoderParameters.from(header, claims));
        return jwt.getTokenValue();
    }

    private List<String> extractRoles(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(role -> role.replaceFirst("^ROLE_", ""))
                .collect(Collectors.toList());
    }
}