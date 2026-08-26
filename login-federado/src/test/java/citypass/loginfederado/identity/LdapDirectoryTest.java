package citypass.loginfederado.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;

import javax.naming.directory.SearchControls;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pure mapping plus LDAP query contract tests. */
class LdapDirectoryTest {
    private LdapTemplate ldap;
    private LdapDirectory directory;

    @BeforeEach
    void setUp() {
        ldap = mock(LdapTemplate.class);
        directory = new LdapDirectory(ldap);
    }

    @Test
    void memberOfReducesToBareNames() {
        List<String> groups = LdapDirectory.reduceToBareNames(
                "cn=soporte-n2,ou=Groups,ou=Reclamos,dc=citypass,dc=local",
                "cn=delegados,ou=Groups,ou=Reclamos,dc=citypass,dc=local");
        assertThat(groups).containsExactly("soporte-n2", "delegados");
    }

    @Test
    void missingMemberOfMeansEmptyGroupsNotFailure() {
        assertThat(LdapDirectory.reduceToBareNames(null)).isEmpty();
        assertThat(LdapDirectory.reduceToBareNames()).isEmpty();
    }

    @Test
    void moduleComesFromTheOuAfterPeople() {
        assertThat(LdapDirectory.extractModule(
                "uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local")).isEqualTo("reclamos");
        assertThat(LdapDirectory.extractModule(
                "uid=x,ou=People,ou=Movilidad,dc=citypass,dc=local")).isEqualTo("movilidad");
    }

    @Test
    void serviceAccountsOutsidePeopleHaveNoModule() {
        assertThat(LdapDirectory.extractModule(
                "cn=readonly,ou=ServiceAccounts,dc=citypass,dc=local")).isEmpty();
    }

    @Test
    void findByUidUsesSafeFilterAndMapsPerson() {
        DirContextOperations ctx = context("uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local", false, "U000042");
        when(ldap.search(any(javax.naming.Name.class), contains("uid=jperez"), any(SearchControls.class), any(ContextMapper.class)))
                .thenAnswer(invocation -> {
                    ContextMapper<LdapDirectoryPerson> mapper = invocation.getArgument(3);
                    return List.of(mapper.mapFromContext(ctx));
                });

        var person = directory.findByUid("jperez").orElseThrow();
        assertThat(person.sub()).isEqualTo("U000042");
        assertThat(person.module()).isEqualTo("reclamos");
        assertThat(person.groups()).containsExactly("delegados");
    }

    @Test
    void findByUidReturnsEmptyForDuplicateResults() {
        when(ldap.search(any(javax.naming.Name.class), anyString(), any(SearchControls.class), any(ContextMapper.class)))
                .thenReturn(List.of(mock(LdapDirectoryPerson.class), mock(LdapDirectoryPerson.class)));
        assertThat(directory.findByUid("jperez")).isEmpty();
    }

    @Test
    void disabledOrMalformedEntriesAreIgnored() {
        DirContextOperations locked = context("uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local", true, "U000042");
        DirContextOperations noSub = context("uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local", false, "");
        when(ldap.search(any(javax.naming.Name.class), anyString(), any(SearchControls.class), any(ContextMapper.class)))
                .thenAnswer(invocation -> {
                    ContextMapper<LdapDirectoryPerson> mapper = invocation.getArgument(3);
                    return java.util.Arrays.asList(mapper.mapFromContext(locked), mapper.mapFromContext(noSub));
                });
        assertThat(directory.findByUid("jperez")).isEmpty();
    }

    @Test
    void reloadBySubUsesEmployeeNumberFilter() {
        when(ldap.search(any(javax.naming.Name.class), contains("employeeNumber=U000042"), any(SearchControls.class), any(ContextMapper.class)))
                .thenReturn(List.of(new LdapDirectoryPerson("dn", "U000042", "jperez", "Juan", null, "reclamos", List.of())));
        assertThat(directory.reloadBySub("U000042")).isPresent();
    }

    @Test
    void filterCharactersAreEscaped() {
        when(ldap.search(any(javax.naming.Name.class), anyString(), any(SearchControls.class), any(ContextMapper.class)))
                .thenReturn(List.of());
        directory.findByUid("a*b");
        verify(ldap).search(any(javax.naming.Name.class), contains("a\\2ab"), any(SearchControls.class), any(ContextMapper.class));
    }

    private DirContextOperations context(String dn, boolean locked, String sub) {
        DirContextOperations ctx = mock(DirContextOperations.class);
        when(ctx.getNameInNamespace()).thenReturn(dn);
        when(ctx.getStringAttribute("employeeNumber")).thenReturn(sub);
        when(ctx.getStringAttribute("uid")).thenReturn("jperez");
        when(ctx.getStringAttribute("cn")).thenReturn("Juan Perez");
        when(ctx.getStringAttribute("mail")).thenReturn("jperez@x.com");
        when(ctx.getStringAttribute("pwdAccountLockedTime")).thenReturn(locked ? "000001010000Z" : null);
        when(ctx.getStringAttributes("memberOf")).thenReturn(new String[]{
                "cn=delegados,ou=Groups,ou=Reclamos,dc=citypass,dc=local"});
        return ctx;
    }
}
