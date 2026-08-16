package ar.edu.uade.citypass.loginfederado.service;

import ar.edu.uade.citypass.loginfederado.config.JwtProperties;
import ar.edu.uade.citypass.loginfederado.dto.LoginRequest;
import ar.edu.uade.citypass.loginfederado.dto.LoginResponse;
import ar.edu.uade.citypass.loginfederado.dto.RefreshRequest;
import ar.edu.uade.citypass.loginfederado.security.LdapUserPrincipal;
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

    public AuthService(AuthenticationManager ldapAuthenticationManager,
                        JwtEncoder jwtEncoder,
                        JwtProperties jwtProperties,
                        RefreshTokenService refreshTokenService) {
        this.ldapAuthenticationManager = ldapAuthenticationManager;
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
        this.refreshTokenService = refreshTokenService;
    }

    public LoginResponse login(LoginRequest request) {
        Authentication authentication;
        try {
            authentication = ldapAuthenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );
        } catch (BadCredentialsException ex) {
            throw new BadCredentialsException("Usuario o contraseña inválidos");
        }

        LdapUserPrincipal principal = (LdapUserPrincipal) authentication.getPrincipal();
        List<String> roles = extractRoles(authentication);

        String accessToken = buildAccessToken(principal.getUsername(), principal.getFullName(),
                principal.getEmail(), roles);
        String refreshToken = refreshTokenService.issueFor(principal.getUsername(),
                principal.getFullName(), principal.getEmail(), roles);

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