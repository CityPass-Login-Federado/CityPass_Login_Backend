package citypass.loginfederado.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.List;

/**
 * Cadena de seguridad HTTP del módulo. Dos mundos conviven acá:
 *
 *  1) Endpoints públicos de autenticación (/auth/login, /auth/refresh,
 *     /auth/logout, /oauth/token) y /.well-known/jwks.json: NO pasan por
 *     validación de JWT, porque son el punto de entrada donde todavía no
 *     existe un token (o exponen la clave pública). La autenticación se
 *     resuelve dentro de cada endpoint (LDAP / Basic).
 *
 *  2) Cualquier otro endpoint (incluido /panel/**): requiere un JWT válido,
 *     firmado con RS256 y validado acá con la clave pública vía JwtDecoder.
 *     Qué más exige cada endpoint (audience, grupo delegados, module) lo
 *     aplica PanelAuthorization — el filtro solo garantiza firma y vigencia.
 *
 * La API es completamente STATELESS: no hay sesión de servidor ni cookies,
 * todo el estado de autenticación viaja en el token en cada request.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/auth/login",
            "/auth/refresh",
            "/auth/logout",
            "/oauth/token",
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
     * Los GRUPOS crudos del token (claim "groups") pasan como authorities SIN
     * prefijo ni mayúsculas: son nombres pelados comparables letra por letra
     * por los consumidores (D1/D2/D6). Nada de ROLE_ ni uppercase: eso
     * invitaría a tratar membresías como roles ya decididos.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> groups = jwt.getClaimAsStringList("groups");
            List<GrantedAuthority> authorities = new ArrayList<>();
            if (groups != null) {
                for (String group : groups) {
                    authorities.add(new SimpleGrantedAuthority(group));
                }
            }
            return authorities;
        });
        return converter;
    }
}
