package citypass.loginfederado.panel;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.ldap.LdapName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;

import java.util.List;

/**
 * Mecánica del support: ramas defensivas (DN inválido, atributo ausente o
 * corrupto) y lambdas que los mocks de LdapTemplate no invocan solos.
 */
class PanelLdapSupportTest {

    private LdapTemplate ldap;
    private PanelLdapSupport support;

    @BeforeEach
    void setUp() {
        ldap = mock(LdapTemplate.class);
        support = new PanelLdapSupport(ldap);
    }

    @Test
    void dnOfRejectsMalformedDn() {
        assertThatThrownBy(() -> PanelLdapSupport.dnOf("not-a-dn"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DN inválido");
        assertThat(PanelLdapSupport.dnOf("uid=x,ou=People,ou=Reclamos").toString())
                .isEqualTo("uid=x,ou=People,ou=Reclamos");
    }

    @Test
    void attrValueReturnsNullOnDirectoryError() throws Exception {
        Attributes attrs = new BasicAttributes(true);
        Attribute boom = mock(Attribute.class);
        when(boom.getID()).thenReturn("uid");
        when(boom.size()).thenReturn(1);
        when(boom.get(0)).thenThrow(new NamingException("boom"));
        attrs.put(boom);
        assertThat(PanelLdapSupport.attrValue(attrs, "uid")).isNull();
    }

    @Test
    void requiredRejectsMissingAttribute() {
        assertThatThrownBy(() -> PanelLdapSupport.required(new BasicAttributes(true), "cn"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Atributo esperado ausente");
    }

    @Test
    void firstRdnValueHandlesBareValues() {
        assertThat(PanelLdapSupport.firstRdnValue("weirdvalue")).isEqualTo("weirdvalue");
        assertThat(PanelLdapSupport.firstRdnValue("cn=ops,ou=Groups,ou=Reclamos,dc=citypass,dc=local"))
                .isEqualTo("ops");
    }

    @Test
    void membershipCountHandlesMissingMemberOf() {
        when(ldap.search(any(LdapName.class), anyString(), any(javax.naming.directory.SearchControls.class), ArgumentMatchers.<ContextMapper<Integer>>any()))
                .thenAnswer(invocation -> {
                    ContextMapper<Integer> counter = invocation.getArgument(3);
                    DirContextOperations withoutGroups = mock(DirContextOperations.class);
                    when(withoutGroups.getStringAttributes("memberOf")).thenReturn(null);
                    DirContextOperations withGroups = mock(DirContextOperations.class);
                    when(withGroups.getStringAttributes("memberOf")).thenReturn(new String[]{"a", "b", "c"});
                    return List.of(counter.mapFromContext(withoutGroups), counter.mapFromContext(withGroups));
                });
        assertThat(support.membershipCount("jperez")).isEqualTo(3);
    }

    @Test
    void groupCnsContainingReadsCnValues() {
        when(ldap.search(any(LdapName.class), anyString(), ArgumentMatchers.<AttributesMapper<String>>any()))
                .thenAnswer(invocation -> {
                    AttributesMapper<String> mapper = invocation.getArgument(2);
                    BasicAttributes attrs = new BasicAttributes(true);
                    attrs.put("cn", "ops");
                    return List.of(mapper.mapFromAttributes(attrs));
                });
        assertThat(support.groupCnsContaining(
                "uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local", "reclamos"))
                .containsExactly("ops");
    }

    @Test
    void brokenGroupEntryFailsLoudly() throws Exception {
        Attributes broken = new BasicAttributes(true);
        broken.put("cn", "ops");
        Attribute member = mock(Attribute.class);
        when(member.getID()).thenReturn("member");
        when(member.size()).thenReturn(1);
        when(member.get(0)).thenThrow(new NamingException("boom"));
        broken.put(member);
        assertThatThrownBy(() -> PanelLdapSupport.toGroupView(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No se pudo leer el grupo");
    }
}
