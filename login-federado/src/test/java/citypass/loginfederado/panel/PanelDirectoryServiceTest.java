package citypass.loginfederado.panel;

import citypass.loginfederado.panel.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.ArgumentCaptor;
import org.springframework.ldap.NameAlreadyBoundException;
import org.springframework.ldap.NameNotFoundException;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.security.access.AccessDeniedException;

import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.ModificationItem;
import javax.naming.ldap.LdapName;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PanelDirectoryServiceTest {
    private LdapTemplate ldap;
    private PanelAuditService audit;
    private PanelDirectoryService service;
    private final PanelAuthorization.Delegate actor = new PanelAuthorization.Delegate("U000001", "admin", "reclamos");

    @BeforeEach
    void setUp() {
        ldap = mock(LdapTemplate.class);
        audit = mock(PanelAuditService.class);
        service = new PanelDirectoryService(ldap, audit);
    }

    @Test
    void rejectsUnknownModule() {
        assertThatThrownBy(() -> service.listPeople("desconocido"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
        void listPeopleSortsByUid() throws Exception {
        when(ldap.search(any(LdapName.class), eq("(objectClass=inetOrgPerson)"), ArgumentMatchers.<AttributesMapper<PersonView>>any()))
                                .thenAnswer(invocation -> {
                                        AttributesMapper<PersonView> mapper = invocation.getArgument(2);
                                            return List.of(mapper.mapFromAttributes(person("zeta")), mapper.mapFromAttributes(person("alpha")));
                                });
        assertThat(service.listPeople("reclamos")).extracting(PersonView::uid)
                .containsExactly("alpha", "zeta");
    }

    @Test
    void findPersonReturnsEmptyWhenMissing() {
        when(ldap.lookupContext(any(LdapName.class))).thenThrow(new NameNotFoundException("missing"));
        assertThat(service.findPerson("reclamos", "nobody")).isEmpty();
    }

    @Test
    void findPersonMapsDisabledFlag() {
        DirContextOperations ctx = mock(DirContextOperations.class);
        when(ctx.getStringAttribute("employeeNumber")).thenReturn("U000042");
        when(ctx.getStringAttribute("uid")).thenReturn("jperez");
        when(ctx.getStringAttribute("givenName")).thenReturn("Juan");
        when(ctx.getStringAttribute("sn")).thenReturn("Perez");
        when(ctx.getStringAttribute("mail")).thenReturn("j@x.com");
        when(ctx.getStringAttribute("pwdAccountLockedTime")).thenReturn("000001010000Z");
        when(ldap.lookupContext(any(LdapName.class))).thenReturn(ctx);
        assertThat(service.findPerson("reclamos", "jperez").orElseThrow().disabled()).isTrue();
    }

    @Test
    void createPersonRejectsInvalidEmailAndPassword() {
        var badEmail = new NewPersonRequest("Juan", "Perez", "jperez", "not-an-email", "12345678");
        assertThatThrownBy(() -> service.createPerson(actor, "reclamos", badEmail))
                .isInstanceOf(IllegalArgumentException.class);
        var badPassword = new NewPersonRequest("Juan", "Perez", "jperez", "j@x.com", "short");
        assertThatThrownBy(() -> service.createPerson(actor, "reclamos", badPassword))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ldap);
    }

    @Test
    void createPersonRejectsDuplicateUsernameOrEmail() {
                when(ldap.search(any(LdapName.class), contains("objectClass=inetOrgPerson"), ArgumentMatchers.<AttributesMapper<String>>any()))
                .thenReturn(List.of("existing"));
        var req = new NewPersonRequest("Juan", "Perez", "jperez", "j@x.com", "12345678");
        assertThatThrownBy(() -> service.createPerson(actor, "reclamos", req))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createPersonAssignsNextEmployeeNumberAndAudits() {
                when(ldap.search(any(LdapName.class), eq("(&(objectClass=inetOrgPerson)(employeeNumber=U*))"), ArgumentMatchers.<AttributesMapper<Integer>>any()))
                .thenReturn(List.of(41));
        when(ldap.search(any(LdapName.class), eq("(&(objectClass=inetOrgPerson)(|(uid=jperez)(mail=j@x.com)))"), ArgumentMatchers.<AttributesMapper<String>>any()))
                .thenReturn(List.of());
        DirContextOperations ctx = mock(DirContextOperations.class);
        when(ctx.getStringAttribute("employeeNumber")).thenReturn("U000042");
        when(ctx.getStringAttribute("uid")).thenReturn("jperez");
        when(ctx.getStringAttribute("givenName")).thenReturn("Juan");
        when(ctx.getStringAttribute("sn")).thenReturn("Perez");
        when(ctx.getStringAttribute("mail")).thenReturn("j@x.com");
        when(ctx.getStringAttribute("pwdAccountLockedTime")).thenReturn(null);
        when(ldap.lookupContext(any(LdapName.class))).thenReturn(ctx);

        var result = service.createPerson(actor, "reclamos",
                new NewPersonRequest("Juan", "Perez", "jperez", "j@x.com", "12345678"));
        assertThat(result.employeeNumber()).isEqualTo("U000042");
        verify(ldap).bind(any(LdapName.class), isNull(), any(Attributes.class));
        verify(audit).record(eq(actor), eq("PERSON_CREATED"), anyString(), contains("employeeNumber=U000042"));
    }

    @Test
    void updatePersonChangesFieldsAndAudits() {
                doReturn(personContext("jperez")).when(ldap).lookupContext(any(LdapName.class));
        var result = service.updatePerson(actor, "reclamos", "jperez",
                new UpdatePersonRequest("Juan Carlos", null, null, null));
        assertThat(result.uid()).isEqualTo("jperez");
        verify(ldap).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        verify(audit).record(eq(actor), eq("PERSON_UPDATED"), anyString(), anyString());
    }

    @Test
    void updatePersonRejectsDuplicateEmail() {
                doReturn(personContext("jperez")).when(ldap).lookupContext(any(LdapName.class));
                when(ldap.search(any(LdapName.class), contains("mail="), ArgumentMatchers.<AttributesMapper<String>>any()))
                .thenReturn(List.of("other"));
        assertThatThrownBy(() -> service.updatePerson(actor, "reclamos", "jperez",
                new UpdatePersonRequest(null, null, "other@x.com", null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void disableEnableAndResetPasswordWriteExpectedAttributes() {
                doReturn(personContext("jperez")).when(ldap).lookupContext(any(LdapName.class));
        service.disablePerson(actor, "reclamos", "jperez");
        service.enablePerson(actor, "reclamos", "jperez");
        service.resetPassword(actor, "reclamos", "jperez", "12345678");
        verify(ldap, times(3)).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        verify(audit).record(eq(actor), eq("PERSON_DISABLED"), anyString(), isNull());
        verify(audit).record(eq(actor), eq("PERSON_ENABLED"), anyString(), isNull());
        verify(audit).record(eq(actor), eq("PASSWORD_RESET"), anyString(), isNull());
    }

    @Test
    void resetPasswordRejectsShortPassword() {
        assertThatThrownBy(() -> service.resetPassword(actor, "reclamos", "jperez", "123"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ldap);
    }

    @Test
    void createGroupUsesPlaceholderAndReturnsSortedMembers() {
        DirContextOperations group = mock(DirContextOperations.class);
        when(group.getStringAttributes("member")).thenReturn(new String[]{
                "uid=zeta,ou=People,ou=Reclamos,dc=citypass,dc=local",
                "cn=empty-group-placeholder,ou=ServiceAccounts,dc=citypass,dc=local",
                "uid=alpha,ou=People,ou=Reclamos,dc=citypass,dc=local"});
        when(ldap.lookupContext(any(LdapName.class))).thenReturn(group);
        var result = service.createGroup(actor, "reclamos", "soporte-n2");
        assertThat(result.members()).containsExactly("alpha", "zeta");
        verify(ldap).bind(any(LdapName.class), isNull(), any(Attributes.class));
        verify(audit).record(eq(actor), eq("GROUP_CREATED"), anyString(), isNull());
    }

    @Test
    void createGroupRejectsReservedAndDuplicateNames() {
        assertThatThrownBy(() -> service.createGroup(actor, "reclamos", "delegados"))
                .isInstanceOf(IllegalStateException.class);
        doThrow(new NameAlreadyBoundException(new javax.naming.NameAlreadyBoundException("duplicate")))
                .when(ldap).bind(any(LdapName.class), isNull(), any(Attributes.class));
        assertThatThrownBy(() -> service.createGroup(actor, "reclamos", "ops"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deleteGroupRejectsReservedAndDeletesNormalGroup() {
        assertThatThrownBy(() -> service.deleteGroup(actor, "reclamos", "delegados"))
                .isInstanceOf(IllegalStateException.class);
        doReturn(personContext("ops")).when(ldap).lookupContext(any(LdapName.class));
        service.deleteGroup(actor, "reclamos", "ops");
        verify(ldap).unbind(any(LdapName.class));
        verify(audit).record(eq(actor), eq("GROUP_DELETED"), anyString(), isNull());
    }

    @Test
    void addMemberWarnsAtThirtyGroups() {
                doReturn(personContext("ops")).when(ldap).lookupContext(any(LdapName.class));
                when(ldap.search(any(LdapName.class), contains("uid=jperez"), any(javax.naming.directory.SearchControls.class), ArgumentMatchers.<org.springframework.ldap.core.ContextMapper<Integer>>any()))
                .thenReturn(List.of(29))
                .thenReturn(List.of(30));
        DirContextOperations group = mock(DirContextOperations.class);
        when(group.getStringAttributes("member")).thenReturn(new String[]{"uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local"});
        doReturn(personContext("ops"), group).when(ldap).lookupContext(any(LdapName.class));
        var result = service.addMember(actor, "reclamos", "ops", "jperez");
        assertThat(result.warnings()).hasSize(1);
        verify(audit).record(eq(actor), eq("MEMBER_ADDED"), anyString(), eq("uid=jperez"));
    }

    @Test
    void addMemberRejectsAtMaximum() {
                doReturn(personContext("ops")).when(ldap).lookupContext(any(LdapName.class));
                when(ldap.search(any(LdapName.class), contains("uid=jperez"), any(javax.naming.directory.SearchControls.class), ArgumentMatchers.<org.springframework.ldap.core.ContextMapper<Integer>>any()))
                .thenReturn(List.of(50));
        assertThatThrownBy(() -> service.addMember(actor, "reclamos", "ops", "jperez"))
                .isInstanceOf(IllegalStateException.class);
        verify(ldap, never()).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
    }

    @Test
    void removeMemberFromDelegadosCannotRemoveLastHuman() {
        DirContextOperations group = mock(DirContextOperations.class);
        when(group.getStringAttributes("member")).thenReturn(new String[]{"uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local"});
        when(ldap.lookupContext(any(LdapName.class))).thenReturn(group);
        assertThatThrownBy(() -> service.removeMember(actor, "reclamos", "delegados", "jperez"))
                .isInstanceOf(IllegalStateException.class);
        verify(ldap, never()).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
    }

    @Test
    void removeMemberSucceedsForNormalGroup() {
        DirContextOperations group = mock(DirContextOperations.class);
        when(group.getStringAttributes("member")).thenReturn(new String[]{"uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local"});
        doReturn(personContext("ops"), group).when(ldap).lookupContext(any(LdapName.class));
        var result = service.removeMember(actor, "reclamos", "ops", "jperez");
        assertThat(result.warnings()).isEmpty();
        verify(ldap).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        verify(audit).record(eq(actor), eq("MEMBER_REMOVED"), anyString(), eq("uid=jperez"));
    }

    @Test
    void listGroupsSortsAndHidesPlaceholder() {
        Attributes a = new BasicAttributes(true);
        a.put("cn", "zeta");
        BasicAttribute zetaMembers = new BasicAttribute("member");
        zetaMembers.add("uid=zeta,ou=People,ou=Reclamos,dc=citypass,dc=local");
        zetaMembers.add("cn=empty-group-placeholder,ou=ServiceAccounts,dc=citypass,dc=local");
        a.put(zetaMembers);
        Attributes b = new BasicAttributes(true);
        b.put("cn", "alpha");
        b.put("member", "uid=alpha,ou=People,ou=Reclamos,dc=citypass,dc=local");
        when(ldap.search(any(LdapName.class), eq("(objectClass=groupOfNames)"), ArgumentMatchers.<AttributesMapper<GroupView>>any()))
                .thenAnswer(invocation -> {
                    AttributesMapper<GroupView> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapFromAttributes(a), mapper.mapFromAttributes(b));
                });
        var groups = service.listGroups("reclamos");
        assertThat(groups).extracting(GroupView::name).containsExactly("alpha", "zeta");
        assertThat(groups.get(1).members()).containsExactly("zeta");
    }

    @Test
    void invalidGroupNameIsRejectedBeforeLdap() {
        assertThatThrownBy(() -> service.createGroup(actor, "reclamos", "Bad Name"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ldap);
    }

    @Test
    void invalidUsernameIsRejectedBeforeLdap() {
        var req = new NewPersonRequest("Juan", "Perez", "Bad Name", "j@x.com", "12345678");
        assertThatThrownBy(() -> service.createPerson(actor, "reclamos", req))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ldap);
    }

    @Test
    void updatePersonRejectsInvalidEmail() {
                doReturn(personContext("jperez")).when(ldap).lookupContext(any(LdapName.class));
        assertThatThrownBy(() -> service.updatePerson(actor, "reclamos", "jperez",
                new UpdatePersonRequest(null, null, "invalid", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addMemberRejectsMissingPerson() {
        DirContextOperations group = personContext("ops");
        when(ldap.lookupContext(any(LdapName.class)))
                .thenReturn(group)
                .thenThrow(new NameNotFoundException("missing"));
        assertThatThrownBy(() -> service.addMember(actor, "reclamos", "ops", "jperez"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void addMemberTranslatesDuplicateDirectoryError() {
                doReturn(personContext("ops")).when(ldap).lookupContext(any(LdapName.class));
                when(ldap.search(any(LdapName.class), contains("uid=jperez"), any(javax.naming.directory.SearchControls.class), ArgumentMatchers.<org.springframework.ldap.core.ContextMapper<Integer>>any()))
                .thenReturn(List.of(0));
        doThrow(new org.springframework.ldap.UncategorizedLdapException(new RuntimeException("duplicate")))
                .when(ldap).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        assertThatThrownBy(() -> service.addMember(actor, "reclamos", "ops", "jperez"))
                .isInstanceOf(IllegalStateException.class);
    }

    private Attributes person(String uid) {
        BasicAttributes a = new BasicAttributes(true);
        a.put(new BasicAttribute("uid", uid));
        a.put(new BasicAttribute("employeeNumber", "U000042"));
        a.put(new BasicAttribute("givenName", "Juan"));
        a.put(new BasicAttribute("sn", "Perez"));
        a.put(new BasicAttribute("mail", uid + "@x.com"));
        return a;
    }

    private DirContextOperations personContext(String uid) {
        DirContextOperations ctx = mock(DirContextOperations.class);
        when(ctx.getStringAttribute("employeeNumber")).thenReturn("U000042");
        when(ctx.getStringAttribute("uid")).thenReturn(uid);
        when(ctx.getStringAttribute("givenName")).thenReturn("Juan");
        when(ctx.getStringAttribute("sn")).thenReturn("Perez");
        when(ctx.getStringAttribute("mail")).thenReturn(uid + "@x.com");
        when(ctx.getStringAttribute("pwdAccountLockedTime")).thenReturn(null);
        return ctx;
    }
}
