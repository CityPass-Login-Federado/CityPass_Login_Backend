package citypass.loginfederado.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpenApiConfigTest {

    @Test
    void createsCityPassOpenApiDefinition() {
        var openApi = new OpenApiConfig().cityPassOpenAPI();

        assertThat(openApi.getInfo().getTitle()).isEqualTo("CityPass+ Login Federado — API");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(openApi.getInfo().getDescription()).contains("Proveedor de identidad");
        assertThat(openApi.getInfo().getContact().getName()).isEqualTo("Grupo 2");
        assertThat(openApi.getInfo().getLicense().getName()).isEqualTo("UADE");
        assertThat(openApi.getServers()).extracting(server -> server.getUrl())
                .containsExactly("http://localhost:8081");
        assertThat(openApi.getSecurity()).singleElement()
                .satisfies(requirement -> assertThat(requirement).containsKey("bearer-jwt"));

        var bearerScheme = openApi.getComponents().getSecuritySchemes().get("bearer-jwt");
        assertThat(bearerScheme.getType().toString()).isEqualTo("http");
        assertThat(bearerScheme.getScheme()).isEqualTo("bearer");
        assertThat(bearerScheme.getBearerFormat()).isEqualTo("JWT");
        assertThat(bearerScheme.getDescription()).contains("audience citypass-admin-api");
    }
}