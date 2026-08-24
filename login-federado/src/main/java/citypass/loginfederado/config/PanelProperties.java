package citypass.loginfederado.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credenciales del backend del panel contra el directorio. Es una cuenta
 * interna de servicio (cn=panel-writer): los delegados jamás la conocen —
 * ellos se autentican con su token humano como cualquier usuario (spec §5.1).
 */
@ConfigurationProperties(prefix = "panel")
public record PanelProperties(Ldap ldap) {

    public record Ldap(String username, String password) {
    }
}
