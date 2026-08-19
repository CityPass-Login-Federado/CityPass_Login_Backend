package citypass.loginfederado.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.security.ldap.search.LdapUserSearch;
import org.springframework.security.ldap.userdetails.DefaultLdapAuthoritiesPopulator;
import org.springframework.security.ldap.userdetails.LdapAuthoritiesPopulator;

import citypass.loginfederado.security.CustomLdapUserDetailsMapper;

import java.util.List;

/**
 * Configura la autenticación contra el directorio LDAP siguiendo el patrón
 * "bind authentication": se busca el DN del usuario por uid, y luego se
 * intenta un bind con la contraseña provista. Nunca se compara ni se
 * maneja el hash de la contraseña dentro de esta aplicación.
 *
 * IMPORTANTE: la cuenta configurada en spring.ldap.username/password
 * (usada para las búsquedas) debe tener SOLO permisos de lectura sobre
 * ou=usuarios y ou=grupos. Nunca usar la cuenta admin del LDAP para esto.
 *
 * Esta configuración NO se conecta al filtro de seguridad HTTP (SecurityConfig);
 * se usa de forma programática desde AuthService al procesar /auth/login,
 * porque el resultado de esa autenticación es la emisión de un JWT, no una
 * sesión HTTP.
 */
@Configuration
public class LdapConfig {

    @Value("${spring.ldap.urls}")
    private String ldapUrl;

    @Value("${spring.ldap.base}")
    private String ldapBase;

    @Value("${spring.ldap.username}")
    private String ldapServiceAccountDn;

    @Value("${spring.ldap.password}")
    private String ldapServiceAccountPassword;

    @Bean
    public LdapContextSource contextSource() {
        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl(ldapUrl);
        contextSource.setBase(ldapBase);
        contextSource.setUserDn(ldapServiceAccountDn);
        contextSource.setPassword(ldapServiceAccountPassword);
        contextSource.afterPropertiesSet();
        return contextSource;
    }

    /**
     * Busca la entrada del usuario por uid dentro de ou=usuarios.
     * Usa siempre filtros parametrizados (nunca concatenación manual de
     * strings) para evitar LDAP injection.
     */
    @Bean
    public LdapUserSearch ldapUserSearch(LdapContextSource contextSource) {
        return new FilterBasedLdapUserSearch("ou=usuarios", "(uid={0})", contextSource);
    }

    /**
     * Resuelve los grupos (ou=grupos) a los que pertenece el usuario y los
     * expone como authorities con prefijo ROLE_ (ej. cn=admin -> ROLE_ADMIN).
     * Esto es lo que después se copia al claim "roles" del JWT.
     */
    @Bean
    public LdapAuthoritiesPopulator ldapAuthoritiesPopulator(LdapContextSource contextSource) {
        DefaultLdapAuthoritiesPopulator populator =
                new DefaultLdapAuthoritiesPopulator(contextSource, "ou=grupos");
        populator.setGroupSearchFilter("(member={0})");
        populator.setGroupRoleAttribute("cn");
        populator.setRolePrefix("ROLE_");
        populator.setConvertToUpperCase(true);
        return populator;
    }

    @Bean
    public BindAuthenticator bindAuthenticator(LdapContextSource contextSource, LdapUserSearch ldapUserSearch) {
        BindAuthenticator authenticator = new BindAuthenticator(contextSource);
        authenticator.setUserSearch(ldapUserSearch);
        return authenticator;
    }

    @Bean
    public CustomLdapUserDetailsMapper customLdapUserDetailsMapper() {
        return new CustomLdapUserDetailsMapper();
    }
    @Bean
    public LdapAuthenticationProvider ldapAuthenticationProvider(BindAuthenticator bindAuthenticator,
                                                                LdapAuthoritiesPopulator authoritiesPopulator,
                                                                CustomLdapUserDetailsMapper customLdapUserDetailsMapper) {
        LdapAuthenticationProvider provider =
                new LdapAuthenticationProvider(bindAuthenticator, authoritiesPopulator);
        provider.setUserDetailsContextMapper(customLdapUserDetailsMapper);
        return provider;
    }

    /**
     * AuthenticationManager inyectable en AuthService para ejecutar el bind
     * contra LDAP a partir de un UsernamePasswordAuthenticationToken.
     */
    @Bean
    public AuthenticationManager authenticationManager(LdapAuthenticationProvider ldapAuthenticationProvider) {
        return new ProviderManager(List.of(ldapAuthenticationProvider));
    }
}