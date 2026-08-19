package ar.edu.uade.citypass.loginfederado.security;

import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.ldap.userdetails.UserDetailsContextMapper;

import java.util.Collection;

/**
 * Lee los atributos cn y mail de la entrada LDAP justo después de un bind
 * exitoso, y arma un LdapUserPrincipal con ellos. Sin esto, Spring
 * Security solo expondría username + roles.
 */
public class CustomLdapUserDetailsMapper implements UserDetailsContextMapper {

    @Override
    public UserDetails mapUserFromContext(DirContextOperations ctx, String username,
                                        Collection<? extends GrantedAuthority> authorities) {
        String fullName = ctx.getStringAttribute("cn");
        String email = ctx.getStringAttribute("mail");
        return new LdapUserPrincipal(username, fullName, email, authorities);
    }

    @Override
    public void mapUserToContext(UserDetails user, DirContextAdapter ctx) {
        throw new UnsupportedOperationException("Este módulo no escribe usuarios en LDAP desde este flujo.");
    }
}