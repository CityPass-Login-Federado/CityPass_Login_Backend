package citypass.loginfederado.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Registro de clientes del IdP (application.yml, prefijo "citypass").
 *
 * Cada cliente que puede pedir un token está declarado acá, con su audience.
 * El chequeo de módulo del login compara la OU donde apareció la ficha contra
 * el "module" del cliente; el único cliente transversal es el del panel
 * (spec 01-DISENO-IDENTIDAD.md §5.1). Los clientes de servicio se autentican
 * con client_secret vía client_credentials y viajan al bus con su namespace.
 */
@ConfigurationProperties(prefix = "citypass")
public record CitypassProperties(List<Client> clients) {

    public record Client(
            String clientId,
            String clientSecret,
            String audience,
            String module,
            Boolean transversal,
            String type,
            String namespace
    ) {
        public boolean isService() {
            return "service".equals(type);
        }

        public boolean isTransversal() {
            return Boolean.TRUE.equals(transversal);
        }
    }
}
