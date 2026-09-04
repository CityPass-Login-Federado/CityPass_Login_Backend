package citypass.loginfederado.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/auth/**",
            "/oauth/**",
            "/.well-known/**",
            "/jwks/**"
    };

    /**
     * Swagger UI y OpenAPI JSON: SIN filtro JWT.
     * Se evalúa PRIMERO por @Order(1).
     */
    @Bean
    @Order(1)
    public SecurityFilterChain docsFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(
                        "/docs/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/webjars/**"
                )
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth ->
                        auth.anyRequest().permitAll()
                );

        return http.build();
    }

    /**
     * Cadena principal: endpoints de API.
     * Requiere JWT válido firmado con RS256 vía JwtDecoder.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {

        http
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt ->
                                jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                )
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                response.sendError(
                                        401,
                                        "Token invalido, expirado o ausente"
                                )
                        )
                        .accessDeniedHandler((request, response, ex) ->
                                response.sendError(
                                        403,
                                        "No tiene permisos para acceder a este recurso"
                                )
                        )
                );

        return http.build();
    }

    /**
     * Los grupos crudos del token (claim "groups") pasan como authorities
     * sin prefijo ni mayúsculas.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {

        JwtAuthenticationConverter converter =
                new JwtAuthenticationConverter();

        converter.setJwtGrantedAuthoritiesConverter(jwt -> {

            List<String> groups =
                    jwt.getClaimAsStringList("groups");

            List<GrantedAuthority> authorities =
                    new ArrayList<>();

            if (groups != null) {
                for (String group : groups) {
                    authorities.add(
                            new SimpleGrantedAuthority(group)
                    );
                }
            }

            return authorities;
        });

        return converter;
    }
}