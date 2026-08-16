package ar.edu.uade.citypass.loginfederado.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cadena de seguridad HTTP del módulo. Dos mundos conviven acá:
 *
 *  1) Endpoints públicos de autenticación (/auth/login, /auth/registro,
 *     /auth/refresh): NO pasan por validación de JWT, porque son el punto
 *     de entrada donde todavía no existe un token. La autenticación ahí
 *     se resuelve a mano en AuthService contra LDAP (ver LdapConfig).
 *
 *  2) Cualquier otro endpoint: requiere un JWT válido, firmado con la
 *     clave privada de este módulo (RS256) y validado acá con la clave
 *     pública vía JwtDecoder (ver JwtKeyConfig).
 *
 * La API es completamente STATELESS: no hay sesión de servidor ni cookies,
 * todo el estado de autenticación viaja en el token en cada request.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/auth/login",
            "/auth/registro",
            "/auth/refresh",
            "/auth/recuperar-password",
            "/.well-known/jwks.json",
            "/docs/**",
            "/v3/api-docs/**",
            "/actuator/health"
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Sin sesión: cada request se autentica de forma independiente vía JWT.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // No hay formularios ni cookies de sesión que proteger con CSRF en una API stateless.
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                )
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                response.sendError(401, "Token invalido, expirado o ausente"))
                        .accessDeniedHandler((request, response, ex) ->
                                response.sendError(403, "No tiene permisos para acceder a este recurso"))
                );

        return http.build();
    }

    /**
     * Traduce el claim custom "roles" del JWT (ej. ["ciudadano", "admin"])
     * a GrantedAuthority de Spring Security con prefijo ROLE_, para poder
     * usar @PreAuthorize("hasRole('ADMIN')") o hasRole(...) en los endpoints.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }
}