package citypass.loginfederado.panel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

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
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
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
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.security.access.AccessDeniedException;

import citypass.loginfederado.panel.dto.GroupSearchCriteria;
import citypass.loginfederado.panel.dto.GroupView;
import citypass.loginfederado.panel.dto.BulkMembershipRequest;
import citypass.loginfederado.panel.dto.BulkMembershipStatus;
import citypass.loginfederado.panel.dto.MembershipOperationStatus;

class PanelGroupServiceTest {
    private LdapTemplate ldap;
    private PanelAuditService audit;
    private PanelGroupService service;
    private final PanelAuthorization.Delegate actor = new PanelAuthorization.Delegate("U000001", "admin", "reclamos");
    private final PanelAuthorization.Delegate globalActor = new PanelAuthorization.Delegate("U000007", "admin-global", "analitica", true);

    @BeforeEach
    void setUp() {
        ldap = mock(LdapTemplate.class);
        audit = mock(PanelAuditService.class);
        service = new PanelGroupService(new PanelLdapSupport(ldap), audit);
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
    void removeMemberFromDelegadosWithSurvivorsSucceeds() {
        DirContextOperations group = mock(DirContextOperations.class);
        when(group.getStringAttributes("member")).thenReturn(new String[]{
                "uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local",
                "uid=other,ou=People,ou=Reclamos,dc=citypass,dc=local"});
        when(ldap.lookupContext(any(LdapName.class))).thenReturn(group);
        var result = service.removeMember(actor, "reclamos", "delegados", "jperez");
        assertThat(result.warnings()).isEmpty();
        verify(ldap).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        verify(audit).record(eq(actor), eq("MEMBER_REMOVED"), anyString(), eq("uid=jperez"));
    }

    @Test
    void deleteGroupMissingGroupFails() {
        when(ldap.lookupContext(any(LdapName.class))).thenThrow(new NameNotFoundException("missing"));
        assertThatThrownBy(() -> service.deleteGroup(actor, "reclamos", "ops"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No existe en su módulo");
        verify(ldap, never()).unbind(any(LdapName.class));
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
    void addMemberSuccessWithoutWarnings() {
        DirContextOperations group = mock(DirContextOperations.class);
        when(group.getStringAttributes("member")).thenReturn(new String[]{"uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local"});
        when(ldap.lookupContext(any(LdapName.class))).thenReturn(group);
        when(ldap.search(any(LdapName.class), contains("uid=jperez"), any(javax.naming.directory.SearchControls.class), ArgumentMatchers.<org.springframework.ldap.core.ContextMapper<Integer>>any()))
                .thenReturn(List.of(5))
                .thenReturn(List.of(6));

        var result = service.addMember(actor, "reclamos", "ops", "jperez");

        assertThat(result.warnings()).isEmpty();
        assertThat(result.group().members()).containsExactly("jperez");
        verify(audit).record(eq(actor), eq("MEMBER_ADDED"), anyString(), eq("uid=jperez"));
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
    void bulkAssignsOneUserToOneGroup() {
        stubBulkDirectory(Map.of("grupo-a", List.of()), Map.of("usuario1", 3));

        var response = service.addMembersBulk(actor, "reclamos",
                bulk(List.of("usuario1"), List.of("grupo-a")));

        assertThat(response.status()).isEqualTo(BulkMembershipStatus.SUCCESS);
        assertThat(response.requested()).isEqualTo(1);
        assertThat(response.assigned()).isEqualTo(1);
        assertThat(response.skipped()).isZero();
        assertThat(response.failed()).isZero();
        assertThat(response.results()).singleElement().satisfies(result -> {
            assertThat(result.memberUid()).isEqualTo("usuario1");
            assertThat(result.groupName()).isEqualTo("grupo-a");
            assertThat(result.status()).isEqualTo(MembershipOperationStatus.ASSIGNED);
        });
        verify(ldap).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        verify(audit).record(eq(actor), eq("MEMBER_ADDED"), anyString(), eq("uid=usuario1"));
    }

    @Test
    void bulkBuildsDeduplicatedCartesianProductInGroupThenUserOrder() {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("grupo-b", List.of());
        groups.put("grupo-a", List.of());
        stubBulkDirectory(groups, Map.of("usuario1", 0, "usuario2", 0));

        var response = service.addMembersBulk(actor, "reclamos", bulk(
                List.of(" usuario2 ", "usuario1", "usuario2"),
                List.of(" grupo-b ", "grupo-a", "grupo-b")));

        assertThat(response.requested()).isEqualTo(4);
        assertThat(response.results())
                .extracting(result -> result.memberUid() + "/" + result.groupName())
                .containsExactly("usuario2/grupo-b", "usuario1/grupo-b",
                        "usuario2/grupo-a", "usuario1/grupo-a");
        assertConsistentCounters(response);

        ArgumentCaptor<ModificationItem[]> modifications = ArgumentCaptor.forClass(ModificationItem[].class);
        verify(ldap, times(2)).modifyAttributes(any(LdapName.class), modifications.capture());
        assertThat(modifications.getAllValues())
                .allSatisfy(items -> {
                    assertThat(items).hasSize(1);
                    assertThat(items[0].getAttribute().size()).isEqualTo(2);
                });
        verify(audit, times(4)).record(eq(actor), eq("MEMBER_ADDED"), anyString(), anyString());
    }

    @Test
    void bulkTreatsExistingRelationsAsSkippedAndDoesNotConsumeCapacity() {
        stubBulkDirectory(Map.of("grupo-a", List.of("usuario1")), Map.of("usuario1", 50));

        var response = service.addMembersBulk(actor, "reclamos",
                bulk(List.of("usuario1"), List.of("grupo-a")));

        assertThat(response.status()).isEqualTo(BulkMembershipStatus.SUCCESS);
        assertThat(response.assigned()).isZero();
        assertThat(response.skipped()).isEqualTo(1);
        assertThat(response.failed()).isZero();
        assertThat(response.results().getFirst().status()).isEqualTo(MembershipOperationStatus.ALREADY_MEMBER);
        assertThat(response.results().getFirst().message()).isEqualTo("El usuario ya pertenecía al grupo");
        verify(ldap, never()).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        verifyNoInteractions(audit);
    }

    @Test
    void bulkRejectsMissingUserBeforeAnyWriteOrAudit() {
        stubBulkDirectory(Map.of("grupo-a", List.of()), Map.of("usuario1", 0));

        assertThatThrownBy(() -> service.addMembersBulk(actor, "reclamos",
                bulk(List.of("usuario1", "inexistente"), List.of("grupo-a"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inexistente");

        verify(ldap, never()).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        verifyNoInteractions(audit);
    }

    @Test
    void bulkRejectsMissingGroupBeforeAnyWriteOrAudit() {
        stubBulkDirectory(Map.of("grupo-a", List.of()), Map.of("usuario1", 0));

        assertThatThrownBy(() -> service.addMembersBulk(actor, "reclamos",
                bulk(List.of("usuario1"), List.of("grupo-a", "grupo-inexistente"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("grupo-inexistente");

        verify(ldap, never()).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        verifyNoInteractions(audit);
    }

    @Test
    void bulkRejectsInvalidGroupNameBeforeLdap() {
        assertThatThrownBy(() -> service.addMembersBulk(actor, "reclamos",
                bulk(List.of("usuario1"), List.of("Grupo inválido"))))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(ldap, audit);
    }

    @Test
    void bulkRejectsProductOverMaximumBeforeLdap() {
        List<String> groups = IntStream.range(0, 501).mapToObj(i -> "grupo-" + i).toList();

        assertThatThrownBy(() -> service.addMembersBulk(actor, "reclamos",
                bulk(List.of("usuario1", "usuario2"), groups)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1002")
                .hasMessageContaining("1000");

        verifyNoInteractions(ldap, audit);
    }

    @Test
    void bulkAllowsUserToReachExactlyFiftyGroups() {
        stubBulkDirectory(Map.of("grupo-a", List.of()), Map.of("usuario1", 49));

        var response = service.addMembersBulk(actor, "reclamos",
                bulk(List.of("usuario1"), List.of("grupo-a")));

        assertThat(response.assigned()).isEqualTo(1);
        assertThat(response.warnings()).isEmpty();
        verify(ldap).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
    }

    @Test
    void bulkRejectsProjectedMembershipsOverFiftyWithoutWriting() {
        stubBulkDirectory(Map.of("grupo-a", List.of(), "grupo-b", List.of()), Map.of("usuario1", 49));

        assertThatThrownBy(() -> service.addMembersBulk(actor, "reclamos",
                bulk(List.of("usuario1"), List.of("grupo-a", "grupo-b"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("usuario1")
                .hasMessageContaining("51");

        verify(ldap, never()).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        verifyNoInteractions(audit);
    }

    @Test
    void bulkEmitsOneWarningPerUserWhenEffectiveTotalReachesThirty() {
        stubBulkDirectory(Map.of("grupo-a", List.of(), "grupo-b", List.of()), Map.of("usuario1", 28));

        var response = service.addMembersBulk(actor, "reclamos",
                bulk(List.of("usuario1"), List.of("grupo-a", "grupo-b")));

        assertThat(response.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.memberUid()).isEqualTo("usuario1");
            assertThat(warning.totalGroups()).isEqualTo(30);
            assertThat(warning.message()).contains("30 grupos");
        });
    }

    @Test
    void bulkContinuesAfterOneGroupFailsAndUsesOnlySuccessfulAssignmentsForWarnings() {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("grupo-falla", List.of());
        groups.put("grupo-ok", List.of());
        stubBulkDirectory(groups, Map.of("usuario1", 28));
        doAnswer(invocation -> {
            LdapName dn = invocation.getArgument(0);
            if (dn.toString().startsWith("cn=grupo-falla,")) {
                throw new org.springframework.ldap.UncategorizedLdapException(new RuntimeException("detalle LDAP sensible"));
            }
            return null;
        }).when(ldap).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));

        var response = service.addMembersBulk(actor, "reclamos",
                bulk(List.of("usuario1"), List.of("grupo-falla", "grupo-ok")));

        assertThat(response.status()).isEqualTo(BulkMembershipStatus.PARTIAL);
        assertThat(response.assigned()).isEqualTo(1);
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.warnings()).isEmpty();
        assertThat(response.results()).extracting(result -> result.status())
                .containsExactly(MembershipOperationStatus.FAILED, MembershipOperationStatus.ASSIGNED);
        assertThat(response.results().getFirst().message()).isEqualTo("No se pudo actualizar el grupo");
        verify(ldap, times(2)).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        verify(audit).record(eq(actor), eq("MEMBER_ADDED"), anyString(), eq("uid=usuario1"));
        assertConsistentCounters(response);
    }

    @Test
    void bulkReturnsFailedWhenNoRelationCouldBeAssigned() {
        stubBulkDirectory(Map.of("grupo-falla", List.of()), Map.of("usuario1", 2, "usuario2", 4));
        doThrow(new org.springframework.ldap.UncategorizedLdapException(new RuntimeException("directory down")))
                .when(ldap).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));

        var response = service.addMembersBulk(actor, "reclamos",
                bulk(List.of("usuario1", "usuario2"), List.of("grupo-falla")));

        assertThat(response.status()).isEqualTo(BulkMembershipStatus.FAILED);
        assertThat(response.assigned()).isZero();
        assertThat(response.skipped()).isZero();
        assertThat(response.failed()).isEqualTo(2);
        verifyNoInteractions(audit);
        assertConsistentCounters(response);
    }

    private void stubBulkDirectory(Map<String, List<String>> groupMembers,
                                   Map<String, Integer> membershipCounts) {
        when(ldap.lookupContext(any(LdapName.class))).thenAnswer(invocation -> {
            String dn = invocation.<LdapName>getArgument(0).toString();
            if (dn.startsWith("cn=")) {
                String groupName = dn.substring(3, dn.indexOf(','));
                if (!groupMembers.containsKey(groupName)) {
                    throw new NameNotFoundException("missing group");
                }
                DirContextOperations group = mock(DirContextOperations.class);
                List<String> members = groupMembers.get(groupName).stream()
                        .map(PanelGroupServiceTest::absolutePersonDn)
                        .toList();
                when(group.getStringAttributes("member")).thenReturn(members.toArray(String[]::new));
                return group;
            }
            if (dn.startsWith("uid=")) {
                String uid = dn.substring(4, dn.indexOf(','));
                if (!membershipCounts.containsKey(uid)) {
                    throw new NameNotFoundException("missing person");
                }
                return personContext(uid);
            }
            throw new NameNotFoundException("unexpected dn");
        });
        when(ldap.search(any(LdapName.class), anyString(), any(javax.naming.directory.SearchControls.class),
                ArgumentMatchers.<ContextMapper<Integer>>any())).thenAnswer(invocation -> {
                    String filter = invocation.getArgument(1);
                    String uid = filter.substring(filter.indexOf("(uid=") + 5, filter.lastIndexOf("))"));
                    return List.of(membershipCounts.get(uid));
                });
    }

    private static BulkMembershipRequest bulk(List<String> memberUids, List<String> groupNames) {
        return new BulkMembershipRequest(memberUids, groupNames);
    }

    private static String absolutePersonDn(String uid) {
        return "uid=" + uid + ",ou=People,ou=Reclamos,dc=citypass,dc=local";
    }

    private static void assertConsistentCounters(citypass.loginfederado.panel.dto.BulkMembershipResponse response) {
        assertThat(response.results()).hasSize(response.requested());
        assertThat(response.results().stream().filter(r -> r.status() == MembershipOperationStatus.ASSIGNED).count())
                .isEqualTo(response.assigned());
        assertThat(response.results().stream().filter(r -> r.status() == MembershipOperationStatus.ALREADY_MEMBER).count())
                .isEqualTo(response.skipped());
        assertThat(response.results().stream().filter(r -> r.status() == MembershipOperationStatus.FAILED).count())
                .isEqualTo(response.failed());
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
