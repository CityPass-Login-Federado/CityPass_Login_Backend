package citypass.loginfederado.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI cityPassOpenAPI() {
        SecurityScheme bearerScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("JWT emitido por el IdP. "
                        + "Los endpoints de panel requieren: "
                        + "audience citypass-admin-api + token_use=human "
                        + "+ grupo delegados (claim module) "
                        + "O grupo admin-global (módulo vía ?module= en cada request).");

        return new OpenAPI()
                .info(new Info()
                        .title("CityPass+ Login Federado — API")
                        .description("""
                                Proveedor de identidad (IdP) de CityPass+.

                                **Autenticación** (`/auth`): login, refresh y logout con LDAP + JWT (RS256).
                                **Panel de administración** (`/panel`): ABM de personas y grupos, exclusivo para delegados de módulo.
                                **OAuth** (`/oauth/token`): tokens de servicio backend-a-backend (client_credentials).
                                **JWKS** (`/.well-known/jwks.json`): clave pública para que otros módulos validen tokens sin llamarnos.

                                Swagger UI disponible en `/docs`.
                                """)
                        .version("0.1.0-SNAPSHOT")
                        .contact(new Contact().name("Grupo 2").email("grupo2@citypass.local"))
                        .license(new License().name("UADE")))
                .servers(List.of(
                        new Server().url("http://localhost:8081").description("Desarrollo")))
                .tags(List.of(
                        new Tag().name("Autenticación")
                                .description("Login, refresh y logout públicos (contrato §2)."),
                        new Tag().name("Panel — Personas")
                                .description("ABM de personas, exclusivo para delegados de módulo o admin-global (con ?module=)."),
                        new Tag().name("Panel — Grupos")
                                .description("ABM de grupos y membresías, exclusivo para delegados de módulo o admin-global (con ?module=)."),
                        new Tag().name("OAuth")
                                .description("Token de servicio backend-a-backend (client_credentials)."),
                        new Tag().name("Infraestructura")
                                .description("Descubrimiento de claves públicas y estado del servicio.")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"))
                .components(new Components()
                        .addSecuritySchemes("bearer-jwt", bearerScheme));
    }
}