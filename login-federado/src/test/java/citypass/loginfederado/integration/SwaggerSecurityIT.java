package citypass.loginfederado.integration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import citypass.loginfederado.config.OpenApiConfig;
import citypass.loginfederado.config.SecurityConfig;

@Testcontainers
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "spring.docker.compose.enabled=false",
        "spring.ldap.urls=ldap://localhost:389"
})
@AutoConfigureMockMvc
@SuppressWarnings("resource")
class SwaggerSecurityIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("login_federado")
            .withUsername("citypass")
            .withPassword("citypass");

    @DynamicPropertySource
        @SuppressWarnings("unused")
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired OpenApiConfig openApiConfig;
    @Autowired SecurityConfig securityConfig;

    @Test
    void exposesSwaggerUiAndOpenApiWithoutJwt() throws Exception {
        mockMvc.perform(get("/docs"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/docs/swagger-ui/index.html"));

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    @Test
    void protectsNonPublicApiRoutesWithoutJwt() throws Exception {
        mockMvc.perform(get("/route-not-public"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void leavesAuthenticationEndpointsPublic() throws Exception {
        mockMvc.perform(get("/auth/route-not-public"))
                .andExpect(status().isNotFound());
    }

    @Test
    void acceptsJwtForProtectedRoutes() throws Exception {
        Jwt token = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("groups", List.of("delegados", "soporte-n2"))
                .build();

        mockMvc.perform(get("/route-not-public").with(jwt().jwt(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void exposesExpectedOpenApiMetadataAndBearerScheme() {
        var openApi = openApiConfig.cityPassOpenAPI();

        assertThat(openApi.getInfo().getTitle()).isEqualTo("CityPass+ Login Federado — API");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(openApi.getServers()).extracting(server -> server.getUrl())
                .containsExactly("http://localhost:8081");
        assertThat(openApi.getSecurity()).hasSize(1);
        assertThat(openApi.getComponents().getSecuritySchemes()).containsKey("bearer-jwt");
        assertThat(openApi.getComponents().getSecuritySchemes().get("bearer-jwt"))
                .satisfies(scheme -> {
                    assertThat(scheme.getType().toString()).isEqualTo("http");
                    assertThat(scheme.getScheme()).isEqualTo("bearer");
                    assertThat(scheme.getBearerFormat()).isEqualTo("JWT");
                });
    }

    @Test
    void mapsGroupsWithoutRolePrefixAndHandlesMissingGroups() {
        var converter = securityConfig.jwtAuthenticationConverter();
        Jwt withGroups = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("groups", List.of("delegados", "soporte-n2"))
                .build();
        Jwt withoutGroups = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .build();

        assertThat(converter.convert(withGroups).getAuthorities())
                .extracting(authority -> authority.getAuthority())
                .containsExactly("delegados", "soporte-n2");
        assertThat(converter.convert(withoutGroups).getAuthorities()).isEmpty();
    }
}