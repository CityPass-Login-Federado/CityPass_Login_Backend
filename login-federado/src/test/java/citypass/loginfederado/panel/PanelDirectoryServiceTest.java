package citypass.loginfederado.panel;

import java.util.List;

import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.ModificationItem;
import javax.naming.ldap.LdapName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.springframework.ldap.NameAlreadyBoundException;
import org.springframework.ldap.NameNotFoundException;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.security.access.AccessDeniedException;

import citypass.loginfederado.panel.dto.GroupSearchCriteria;
import citypass.loginfederado.panel.dto.GroupView;
import citypass.loginfederado.panel.dto.NewPersonRequest;
import citypass.loginfederado.panel.dto.PeopleSearchCriteria;
import citypass.loginfederado.panel.dto.PersonView;
import citypass.loginfederado.panel.dto.UpdatePersonRequest;

class PanelDirectoryServiceTest {
    private LdapTemplate ldap;
    private PanelAuditService audit;
    private PanelDirectoryService service;
    private final PanelAuthorization.Delegate actor = new PanelAuthorization.Delegate("U000001", "admin", "reclamos");
    private final PanelAuthorization.Delegate globalActor = new PanelAuthorization.Delegate("U000007", "admin-global", "analitica", true);

    @BeforeEach
    void setUp() {
        ldap = mock(LdapTemplate.class);
        audit = mock(PanelAuditService.class);
        service = new PanelDirectoryService(ldap, audit);
    }

    @Test
    void rejectsUnknownModule() {
        assertThatThrownBy(() -> service.listPeople("desconocido", new PeopleSearchCriteria(0, 10, null, null, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
        void listPeopleSortsByUid() throws Exception {
        when(ldap.search(any(org.springframework.ldap.query.LdapQuery.class), ArgumentMatchers.<AttributesMapper<PersonView>>any()))
                                .thenAnswer(invocation -> {
                                        AttributesMapper<PersonView> mapper = invocation.getArgument(1);
                                            return List.of(mapper.mapFromAttributes(person("zeta")), mapper.mapFromAttributes(person("alpha")));
                                });
        assertThat(service.listPeople("reclamos", new PeopleSearchCriteria(0, 10, null, null, null)).content()).extracting(PersonView::uid)
                .containsExactly("alpha", "zeta");
    }

    @Test
    void listPeopleFiltersDisabledUsersSortsAndPaginates() {
        when(ldap.search(any(org.springframework.ldap.query.LdapQuery.class),
                ArgumentMatchers.<AttributesMapper<PersonView>>any()))
                .thenAnswer(invocation -> {
                    AttributesMapper<PersonView> mapper = invocation.getArgument(1);
                    Attributes disabled = person("bravo");
                    disabled.put("pwdAccountLockedTime", "000001010000Z");
                    return List.of(
                            mapper.mapFromAttributes(person("zeta")),
                            mapper.mapFromAttributes(disabled),
                            mapper.mapFromAttributes(person("alpha")));
                });

        var result = service.listPeople("reclamos",
                new PeopleSearchCriteria(1, 1, " jperez ", "ops", false));

        assertThat(result.content()).extracting(PersonView::uid).containsExactly("zeta");
        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.currentPage()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(1);
    }

    @Test
    void listPeopleUsesDefaultsForNonPositivePaginationAndReturnsEmptyOutOfRange() {
        when(ldap.search(any(org.springframework.ldap.query.LdapQuery.class),
                ArgumentMatchers.<AttributesMapper<PersonView>>any()))
                .thenAnswer(invocation -> {
                    AttributesMapper<PersonView> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapFromAttributes(person("alpha")));
                });

        var result = service.listPeople("reclamos",
                new PeopleSearchCriteria(-1, 0, " ", " ", null));
        var emptyPage = service.listPeople("reclamos",
                new PeopleSearchCriteria(2, 1, null, null, null));

        assertThat(result.content()).extracting(PersonView::uid).containsExactly("alpha");
        assertThat(result.currentPage()).isZero();
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(emptyPage.content()).isEmpty();
    }

        @Test
        void listPeopleCanSelectDisabledUsers() {
                when(ldap.search(any(org.springframework.ldap.query.LdapQuery.class),
                                ArgumentMatchers.<AttributesMapper<PersonView>>any()))
                                .thenAnswer(invocation -> {
                                        AttributesMapper<PersonView> mapper = invocation.getArgument(1);
                                        Attributes disabled = person("locked");
                                        disabled.put("pwdAccountLockedTime", "000001010000Z");
                                        return List.of(mapper.mapFromAttributes(disabled),
                                                        mapper.mapFromAttributes(person("active")));
                                });

                var result = service.listPeople("reclamos",
                                new PeopleSearchCriteria(0, 10, null, null, true));

                assertThat(result.content()).extracting(PersonView::uid).containsExactly("locked");
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
    void globalAdminCanDeleteReservedDelegadosGroup() {
        doReturn(personContext("delegados")).when(ldap).lookupContext(any(LdapName.class));
        service.deleteGroup(globalActor, "movilidad", "delegados");
        verify(ldap).unbind(any(LdapName.class));
        verify(audit).record(eq(globalActor), eq("GROUP_DELETED"), anyString(), isNull());
    }

    @Test
    void globalAdminCanCreateReservedDelegadosGroup() {
        DirContextOperations group = mock(DirContextOperations.class);
        when(group.getStringAttributes("member")).thenReturn(new String[]{
                "cn=empty-group-placeholder,ou=ServiceAccounts,dc=citypass,dc=local"});
        when(ldap.lookupContext(any(LdapName.class))).thenReturn(group);
        service.createGroup(globalActor, "movilidad", "delegados");
        verify(ldap).bind(any(LdapName.class), isNull(), any(Attributes.class));
        verify(audit).record(eq(globalActor), eq("GROUP_CREATED"), anyString(), isNull());
    }

    @Test
    void globalAdminIsStillScopedToKnownModules() {
        assertThatThrownBy(() -> service.deleteGroup(globalActor, "desconocido", "delegados"))
                .isInstanceOf(AccessDeniedException.class);
        verify(ldap, never()).lookupContext(any(LdapName.class));
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
    void globalAdminCanRemoveLastHumanFromDelegados() {
        DirContextOperations group = mock(DirContextOperations.class);
        when(group.getStringAttributes("member")).thenReturn(new String[]{"uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local"});
        when(ldap.lookupContext(any(LdapName.class))).thenReturn(group);
        var result = service.removeMember(globalActor, "reclamos", "delegados", "jperez");
        assertThat(result.warnings()).isEmpty();
        verify(ldap).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        verify(audit).record(eq(globalActor), eq("MEMBER_REMOVED"), anyString(), eq("uid=jperez"));
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
        when(ldap.search(any(org.springframework.ldap.query.LdapQuery.class), ArgumentMatchers.<AttributesMapper<GroupView>>any()))
                .thenAnswer(invocation -> {
                    AttributesMapper<GroupView> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapFromAttributes(a), mapper.mapFromAttributes(b));
                });
        var groups = service.listGroups("reclamos", new citypass.loginfederado.panel.dto.GroupSearchCriteria(0, 10, null, null)).content();
        assertThat(groups).extracting(GroupView::name).containsExactly("alpha", "zeta");
        assertThat(groups.get(1).members()).containsExactly("zeta");
    }

    @Test
    void listGroupsFiltersReservedGroupsAndPaginates() {
        Attributes reserved = new BasicAttributes(true);
        reserved.put("cn", "delegados");
        reserved.put("member", "uid=admin,ou=People,ou=Reclamos,dc=citypass,dc=local");
        Attributes normal = new BasicAttributes(true);
        normal.put("cn", "ops");
        normal.put("member", "uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local");
        Attributes other = new BasicAttributes(true);
        other.put("cn", "zeta");
        other.put("member", "uid=zeta,ou=People,ou=Reclamos,dc=citypass,dc=local");
        when(ldap.search(any(org.springframework.ldap.query.LdapQuery.class),
                ArgumentMatchers.<AttributesMapper<GroupView>>any()))
                .thenAnswer(invocation -> {
                    AttributesMapper<GroupView> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapFromAttributes(other),
                            mapper.mapFromAttributes(reserved), mapper.mapFromAttributes(normal));
                });

        var result = service.listGroups("reclamos",
                new GroupSearchCriteria(0, 1, " ops ", false));

        assertThat(result.content()).extracting(GroupView::name).containsExactly("ops");
        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.currentPage()).isZero();
        assertThat(result.size()).isEqualTo(1);
    }

    @Test
    void listGroupsUsesDefaultsForNonPositivePaginationAndHandlesEmptyPage() {
        when(ldap.search(any(org.springframework.ldap.query.LdapQuery.class),
                ArgumentMatchers.<AttributesMapper<GroupView>>any()))
                .thenReturn(List.of());

        var result = service.listGroups("reclamos",
                new GroupSearchCriteria(-1, 0, " ", null));

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
        assertThat(result.currentPage()).isZero();
        assertThat(result.size()).isEqualTo(10);
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

    @Test
    void addMemberTranslatesDuplicateValueError() {
                doReturn(personContext("ops")).when(ldap).lookupContext(any(LdapName.class));
                when(ldap.search(any(LdapName.class), contains("uid=jperez"), any(javax.naming.directory.SearchControls.class), ArgumentMatchers.<org.springframework.ldap.core.ContextMapper<Integer>>any()))
                .thenReturn(List.of(0));
        doThrow(new org.springframework.ldap.AttributeInUseException(new javax.naming.directory.AttributeInUseException("already member")))
                .when(ldap).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        assertThatThrownBy(() -> service.addMember(actor, "reclamos", "ops", "jperez"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void removeMemberTranslatesMissingValueError() {
                doReturn(personContext("ops")).when(ldap).lookupContext(any(LdapName.class));
        doThrow(new org.springframework.ldap.NoSuchAttributeException(new javax.naming.directory.NoSuchAttributeException("not a member")))
                .when(ldap).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        assertThatThrownBy(() -> service.removeMember(actor, "reclamos", "ops", "jperez"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void listPeopleRejectsInvalidGroupFilterNoLdap() {
        assertThatThrownBy(() -> service.listPeople("reclamos",
                new PeopleSearchCriteria(0, 10, null, "Bad Name", null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ldap);
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
