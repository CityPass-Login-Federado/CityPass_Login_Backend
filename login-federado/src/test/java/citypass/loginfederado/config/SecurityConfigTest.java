package citypass.loginfederado.config;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.jwk.RSAKey;

import citypass.loginfederado.identity.ClientRegistry;
import citypass.loginfederado.panel.PanelAuditService;
import citypass.loginfederado.panel.PanelAuthorization;
import citypass.loginfederado.panel.PanelDirectoryService;
import citypass.loginfederado.service.AuthService;
import citypass.loginfederado.service.RefreshTokenService;
import citypass.loginfederado.token.AccessTokenIssuer;

@WebMvcTest(controllers = SecurityConfigTest.ProbeController.class)
@Import({SecurityConfig.class, SecurityConfigTest.MethodSecurityConfig.class})
@EnableMethodSecurity
@SuppressWarnings("unused")
class SecurityConfigTest {

    @Autowired MockMvc mockMvc;
    @Autowired SecurityConfig securityConfig;
    @Autowired FilterChainProxy filterChainProxy;
        @MockBean @SuppressWarnings("unused") JwtDecoder jwtDecoder;
    @MockBean AuthService authService;
    @MockBean RSAKey rsaKey;
    @MockBean ClientRegistry clientRegistry;
    @MockBean AccessTokenIssuer accessTokenIssuer;
    @MockBean JwtProperties jwtProperties;
    @MockBean PanelDirectoryService panelDirectoryService;
    @MockBean PanelAuthorization panelAuthorization;
    @MockBean PanelAuditService panelAuditService;
    @MockBean RefreshTokenService refreshTokenService;

    @Test
    void permitsDocumentationMatcherWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/docs/unknown"))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    @Test
    void permitsConfiguredPublicApiMatcherWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/auth/unknown"))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    @Test
    void rejectsUnmatchedApiRouteWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/private/unknown"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsAuthenticatedJwtOnUnmatchedApiRoute() throws Exception {
        mockMvc.perform(get("/private/unknown").with(jwt()))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    @Test
    void returnsForbiddenWhenAuthenticatedUserLacksRequiredAuthority() throws Exception {
        var exceptionTranslationFilter = filterChainProxy.getFilters("/private/unknown").stream()
            .filter(ExceptionTranslationFilter.class::isInstance)
            .map(ExceptionTranslationFilter.class::cast)
            .findFirst()
            .orElseThrow();
        var accessDeniedHandler = ReflectionTestUtils.getField(
            exceptionTranslationFilter, "accessDeniedHandler");
        var request = new MockHttpServletRequest("GET", "/private/unknown");
        var response = new MockHttpServletResponse();

        var handler = (org.springframework.security.web.access.AccessDeniedHandler)
            Objects.requireNonNull(accessDeniedHandler);
        handler
            .handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getErrorMessage()).isEqualTo("No tiene permisos para acceder a este recurso");
    }

    @Test
    void convertsGroupsToUnprefixedAuthoritiesAndHandlesMissingClaim() {
        var converter = securityConfig.jwtAuthenticationConverter();
        Jwt withGroups = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("groups", List.of("delegados", "soporte-n2"))
                .build();
        Jwt withoutGroups = Jwt.withTokenValue("token")
                .header("alg", "RS256")
            .subject("test-user")
                .build();

        assertThat(converter.convert(withGroups).getAuthorities())
                .extracting(authority -> authority.getAuthority())
                .containsExactly("delegados", "soporte-n2");
        assertThat(converter.convert(withoutGroups).getAuthorities()).isEmpty();
    }

    @RestController
    public static class ProbeController {
        @GetMapping({"/docs/unknown", "/auth/unknown", "/private/unknown"})
        String probe() {
            return "ok";
        }

        @GetMapping("/private/forbidden")
        @PreAuthorize("hasAuthority('required')")
        String forbidden() {
            return "forbidden";
        }
    }

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }
}