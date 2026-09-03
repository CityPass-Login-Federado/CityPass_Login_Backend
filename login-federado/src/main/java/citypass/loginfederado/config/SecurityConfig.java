package citypass.loginfederado.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
            "/actuator/health"
                    };

/**
 * Swagger UI y OpenAPI JSON: SIN filtro JWT. Se evalúa PRIMERO por @Order(1).
 * Usa AntPathRequestMatcher directamente para evitar problemas con
 * MvcRequestMatcher que Spring Security 6 usa por defecto con securityMatcher(String...).
 */
@Bean
@Order(1)
public SecurityFilterChain docsFilterChain(HttpSecurity http) throws Exception {
    http
            .securityMatcher(
                    "/docs/**", "/v3/api-docs/**", "/swagger-ui/**",
                    "/swagger-ui.html", "/webjars/**"
            )
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
}

/**
 * Cadena principal: endpoints de API (auth, panel, oauth, jwks).
 * Requiere JWT válido firmado con RS256 vía JwtDecoder.
 */
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    @Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
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