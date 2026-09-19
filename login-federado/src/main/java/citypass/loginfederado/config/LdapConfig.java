package citypass.loginfederado.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

/**
 * Dos conexiones al directorio, con dos cuentas de servicio distintas y
 * poderes distintos (spec 01-DISENO-IDENTIDAD.md §2.8):
 *
 * - contextSource (cn=readonly): SOLO LECTURA + binds. La usa el IdP para
 *   buscar fichas y autenticar por bind. La ACL del directorio le niega
 *   ver hashes y escribir: aunque el código tuviera un bug, no podría
 *   modificar el árbol.
 *
 * - panelWriterContextSource (cn=panel-writer): ESCRITURA en People/Groups.
 *   Exclusiva del backend del panel (PanelDirectoryService). Nunca se usa
 *   en el flujo de login.
 *
 * Ambas cuentas viven fuera de las OUs de módulo: la búsqueda de personas
 * es por atributo sobre todo el árbol, pero las fichas de servicio no
 * llevan uid — estructuralmente NO pueden autenticarse como personas.
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, LockoutProperties.class,
        CitypassProperties.class, PanelProperties.class})
public class LdapConfig {

    @Value("${spring.ldap.urls}")
    private String ldapUrl;

    @Value("${spring.ldap.base}")
    private String ldapBase;

    @Value("${spring.ldap.username}")
    private String readonlyAccountDn;

    @Value("${spring.ldap.password}")
    private String readonlyAccountPassword;

    @Bean
    public LdapContextSource contextSource() {
        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl(ldapUrl);
        contextSource.setBase(ldapBase);
        contextSource.setUserDn(readonlyAccountDn);
        contextSource.setPassword(readonlyAccountPassword);
        contextSource.afterPropertiesSet();
        return contextSource;
    }

    @Bean
    public LdapTemplate ldapTemplate(LdapContextSource contextSource) {
        // Los atributos operacionales (memberOf, pwdAccountLockedTime) los
        // pedimos explícitamente en cada búsqueda; ver LdapDirectory.
        return new LdapTemplate(contextSource);
    }

    @Bean
    public LdapContextSource panelWriterContextSource(PanelProperties panelProperties) {
        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl(ldapUrl);
        contextSource.setBase(ldapBase);
        contextSource.setUserDn(panelProperties.ldap().username());
        contextSource.setPassword(panelProperties.ldap().password());
        contextSource.afterPropertiesSet();
        return contextSource;
    }

    @Bean
    public LdapTemplate panelLdapTemplate(LdapContextSource panelWriterContextSource) {
        return new LdapTemplate(panelWriterContextSource);
    }
}
