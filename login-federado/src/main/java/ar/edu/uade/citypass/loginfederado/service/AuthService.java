package ar.edu.uade.citypass.loginfederado.service;

import ar.edu.uade.citypass.loginfederado.config.JwtProperties;
import ar.edu.uade.citypass.loginfederado.dto.LoginRequest;
import ar.edu.uade.citypass.loginfederado.dto.LoginResponse;
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

/**
 * Orquesta el login: autentica contra LDAP (bind authentication, ver
 * LdapConfig) y, si es exitoso, emite un JWT firmado en RS256 con los
 * roles resueltos desde los grupos LDAP del usuario.
 */
@Service
public class AuthService {

    private final AuthenticationManager ldapAuthenticationManager;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public AuthService(AuthenticationManager ldapAuthenticationManager,
                        JwtEncoder jwtEncoder,
                        JwtProperties jwtProperties) {
        this.ldapAuthenticationManager = ldapAuthenticationManager;
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
    }

    public LoginResponse login(LoginRequest request) {
        Authentication authentication;
        try {
            authentication = ldapAuthenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );
        } catch (BadCredentialsException ex) {
            // Mensaje genérico: no revelar si falló el usuario o la contraseña.
            throw new BadCredentialsException("Usuario o contraseña inválidos");
        }

        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(role -> role.replaceFirst("^ROLE_", ""))
                .collect(Collectors.toList());

        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(jwtProperties.accessTokenExpirationMinutes() * 60);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .audience(List.of(jwtProperties.audience()))
                .subject(authentication.getName())
                .issuedAt(now)
                .expiresAt(expiry)
                .id(UUID.randomUUID().toString())
                .claim("roles", roles)
                .claim("token_type", "access")
                .build();

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(jwtProperties.keyId())
                .build();

        Jwt jwt = jwtEncoder.encode(JwtEncoderParameters.from(header, claims));

        return new LoginResponse(jwt.getTokenValue(), "Bearer", jwtProperties.accessTokenExpirationMinutes() * 60);
    }
}