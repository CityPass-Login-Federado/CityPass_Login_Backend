package citypass.loginfederado.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

/**
 * Representa al usuario autenticado contra LDAP, incluyendo los atributos
 * cn (nombre) y mail (email) que el UserDetails por default de Spring
 * Security NO trae — solo trae username + authorities.
 */
public class LdapUserPrincipal implements UserDetails {

    private final String username;
    private final String fullName;
    private final String email;
    private final Collection<? extends GrantedAuthority> authorities;

    public LdapUserPrincipal(String username, String fullName, String email,
                            Collection<? extends GrantedAuthority> authorities) {
        this.username = username;
        this.fullName = fullName;
        this.email = email;
        this.authorities = authorities;
    }

    public String getFullName() { return fullName; }
    public String getEmail() { return email; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }

    @Override
    public String getPassword() { return null; } // Nunca se retiene la contraseña acá.

    @Override
    public String getUsername() { return username; }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return true; }
}