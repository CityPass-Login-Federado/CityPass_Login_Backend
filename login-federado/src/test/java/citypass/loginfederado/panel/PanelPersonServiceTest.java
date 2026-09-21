package citypass.loginfederado.panel;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.ldap.NameNotFoundException;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.security.access.AccessDeniedException;

import citypass.loginfederado.panel.dto.GlobalPersonView;
import citypass.loginfederado.panel.dto.NewPersonRequest;
import citypass.loginfederado.panel.dto.PeopleSearchCriteria;
import citypass.loginfederado.panel.dto.PersonView;
import citypass.loginfederado.panel.dto.UpdatePersonRequest;

class PanelPersonServiceTest {
    private LdapTemplate ldap;
    private PanelAuditService audit;
    private PanelPersonService service;
    private final PanelAuthorization.Delegate actor = new PanelAuthorization.Delegate("U000001", "admin", "reclamos");

    @BeforeEach
    void setUp() {
        ldap = mock(LdapTemplate.class);
        audit = mock(PanelAuditService.class);
        service = new PanelPersonService(new PanelLdapSupport(ldap), audit);
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
    void listAllPeopleGlobalAggregatesAcrossModulesAndSorts() {
        AtomicInteger callIndex = new AtomicInteger();
        when(ldap.search(any(org.springframework.ldap.query.LdapQuery.class),
                ArgumentMatchers.<AttributesMapper<PersonView>>any()))
                .thenAnswer(invocation -> {
                    AttributesMapper<PersonView> mapper = invocation.getArgument(1);
                    if (callIndex.getAndIncrement() == 0) {
                        return List.of(
                                mapper.mapFromAttributes(person("zeta")),
                                mapper.mapFromAttributes(person("alpha")));
                    }
                    return List.of();
                });

        var result = service.listAllPeopleGlobal();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(GlobalPersonView::uid)
                .containsExactly("alpha", "zeta");
        assertThat(result).extracting(GlobalPersonView::module)
                .containsExactly("movilidad", "movilidad");
    }

    @Test
    void updatePersonRenamesAndRepairsMemberships() {
        doReturn(personContext("jperez")).when(ldap).lookupContext(any(LdapName.class));
        when(ldap.search(any(LdapName.class), contains("uid=juan.perez"), ArgumentMatchers.<AttributesMapper<String>>any()))
                .thenReturn(List.of());
        when(ldap.search(any(LdapName.class), contains("(member="), ArgumentMatchers.<AttributesMapper<String>>any()))
                .thenAnswer(invocation -> {
                    AttributesMapper<String> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapFromAttributes(cnAttrs("ops")));
                });

        var result = service.updatePerson(actor, "reclamos", "jperez",
                new UpdatePersonRequest(null, null, null, "juan.perez"));

        verify(ldap).rename(any(LdapName.class), any(LdapName.class));
        verify(ldap).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        verify(audit).record(eq(actor), eq("PERSON_RENAMED"), anyString(), eq("antes=jperez"));
        assertThat(result.uid()).isEqualTo("jperez");
    }

    @Test
    void updatePersonRenameCollisionFails() {
        doReturn(personContext("jperez")).when(ldap).lookupContext(any(LdapName.class));
        when(ldap.search(any(LdapName.class), contains("uid=juan.perez"), ArgumentMatchers.<AttributesMapper<String>>any()))
                .thenAnswer(invocation -> {
                    AttributesMapper<String> mapper = invocation.getArgument(2);
                    // Arrays.asList (no List.of): el primer mapeo da null a
                    // propósito (ficha sin uid) y List.of lo rechaza.
                    return Arrays.asList(
                            mapper.mapFromAttributes(new BasicAttributes(true)),
                            mapper.mapFromAttributes(uidAttrs("other")),
                            mapper.mapFromAttributes(uidAttrs("jperez")));
                });

        assertThatThrownBy(() -> service.updatePerson(actor, "reclamos", "jperez",
                new UpdatePersonRequest(null, null, null, "juan.perez")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ya existe");
        verify(ldap, never()).rename(any(LdapName.class), any(LdapName.class));
    }

    @Test
    void createPersonRetriesEmployeeNumberOnCollision() {
        when(ldap.search(any(LdapName.class), contains("employeeNumber=U*"), ArgumentMatchers.<AttributesMapper<Integer>>any()))
                .thenAnswer(invocation -> {
                    AttributesMapper<Integer> mapper = invocation.getArgument(2);
                    return List.of(
                            mapper.mapFromAttributes(empAttrs("U000041")),
                            mapper.mapFromAttributes(empAttrs("XYZ")));
                });
        when(ldap.search(any(LdapName.class), contains("uid=jperez"), ArgumentMatchers.<AttributesMapper<String>>any()))
                .thenReturn(List.of());
        doThrow(new org.springframework.ldap.AttributeInUseException(new javax.naming.directory.AttributeInUseException("collision")))
                .doNothing().when(ldap).bind(any(LdapName.class), isNull(), any(Attributes.class));

        var result = service.createPerson(actor, "reclamos",
                new NewPersonRequest("Juan", "Perez", "jperez", "j@x.com", "12345678"));

        assertThat(result.employeeNumber()).isEqualTo("U000043");
        verify(ldap, times(2)).bind(any(LdapName.class), isNull(), any(Attributes.class));
        verify(audit).record(eq(actor), eq("PERSON_CREATED"), anyString(), contains("employeeNumber=U000043"));
    }

    @Test
    void createPersonGivesUpAfterRepeatedCollisions() {
        when(ldap.search(any(LdapName.class), contains("employeeNumber=U*"), ArgumentMatchers.<AttributesMapper<Integer>>any()))
                .thenReturn(List.of(41));
        when(ldap.search(any(LdapName.class), contains("uid=jperez"), ArgumentMatchers.<AttributesMapper<String>>any()))
                .thenReturn(List.of());
        doThrow(new org.springframework.ldap.AttributeInUseException(new javax.naming.directory.AttributeInUseException("collision")))
                .when(ldap).bind(any(LdapName.class), isNull(), any(Attributes.class));

        assertThatThrownBy(() -> service.createPerson(actor, "reclamos",
                new NewPersonRequest("Juan", "Perez", "jperez", "j@x.com", "12345678")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identificador único");
        verify(ldap, times(5)).bind(any(LdapName.class), isNull(), any(Attributes.class));
    }

    @Test
    void findPersonEscapesDnSpecialChars() {
        // Inyección LDAP por DN: una coma sin escapar rompería el RDN
        // (uid=a,b,... apuntaría a otra entrada). El support la escapa RFC 4514.
        when(ldap.lookupContext(any(LdapName.class))).thenThrow(new NameNotFoundException("missing"));
        assertThat(service.findPerson("reclamos", "a,b")).isEmpty();
        var captor = org.mockito.ArgumentCaptor.forClass(LdapName.class);
        verify(ldap).lookupContext(captor.capture());
        assertThat(captor.getValue().toString()).isEqualTo("uid=a\\,b,ou=People,ou=Reclamos");
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
    void updatePersonChangesSnOnly() {
        doReturn(personContext("jperez")).when(ldap).lookupContext(any(LdapName.class));
        var result = service.updatePerson(actor, "reclamos", "jperez",
                new UpdatePersonRequest(null, "Pereyra", null, null));
        assertThat(result.uid()).isEqualTo("jperez");
        verify(ldap).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        verify(audit).record(eq(actor), eq("PERSON_UPDATED"), anyString(), anyString());
    }

    @Test
    void updatePersonChangesEmailSuccessfully() {
        doReturn(personContext("jperez")).when(ldap).lookupContext(any(LdapName.class));
        when(ldap.search(any(LdapName.class), contains("mail=nuevo@x.com"), ArgumentMatchers.<AttributesMapper<String>>any()))
                .thenReturn(List.of());
        var result = service.updatePerson(actor, "reclamos", "jperez",
                new UpdatePersonRequest(null, null, "nuevo@x.com", null));
        assertThat(result.uid()).isEqualTo("jperez");
        verify(ldap).modifyAttributes(any(LdapName.class), any(ModificationItem[].class));
        verify(audit).record(eq(actor), eq("PERSON_UPDATED"), anyString(), anyString());
    }

    @Test
    void updatePersonReportsDisappearance() {
        // doReturn(varargs)+doThrow (no when/thenReturn ni doReturn en cadena:
        // personContext() arma stubs internos y cada doX() exige su when();
        // un segundo doX() antes del when() rompe el stubbeo anterior).
        doReturn(personContext("jperez"), personContext("jperez"))
                .doThrow(new NameNotFoundException("gone"))
                .when(ldap).lookupContext(any(LdapName.class));
        assertThatThrownBy(() -> service.updatePerson(actor, "reclamos", "jperez",
                new UpdatePersonRequest("Juan Carlos", null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("desapareció");
    }

    @Test
    void invalidUsernameIsRejectedBeforeLdap() {
        var req = new NewPersonRequest("Juan", "Perez", "Bad Name", "j@x.com", "12345678");
        assertThatThrownBy(() -> service.createPerson(actor, "reclamos", req))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ldap);
    }

    @Test
    void findGlobalByUidOrMailReturnsEmptyWhenNoCriteriaProvided() throws Exception {
        var method = PanelPersonService.class.getDeclaredMethod(
                "findGlobalByUidOrMail", String.class, String.class, String.class);
        method.setAccessible(true);

        var result = (Optional<String>) method.invoke(service, null, null, null);

        assertThat(result).isEmpty();
    }

    @Test
    void findGlobalByUidOrMailRejectsCollisionsOnRenamesAndMails() {
        doReturn(personContext("jperez")).when(ldap).lookupContext(any(LdapName.class));
        when(ldap.search(any(LdapName.class), contains("uid=juan.perez"),
                ArgumentMatchers.<AttributesMapper<String>>any()))
                .thenReturn(List.of("juan.perez"));
        when(ldap.search(any(LdapName.class), contains("mail=other@x.com"),
                ArgumentMatchers.<AttributesMapper<String>>any()))
                .thenReturn(List.of("other@x.com"));

        assertThatThrownBy(() -> service.updatePerson(actor, "reclamos", "jperez",
                new UpdatePersonRequest(null, null, "other@x.com", "juan.perez")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createPersonRejectsInvalidUsernameAndShortPassword() {
        assertThatThrownBy(() -> service.createPerson(actor, "reclamos",
                new NewPersonRequest("Juan", "Perez", "bad_name", "j@x.com", "short")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createPerson(actor, "reclamos",
                new NewPersonRequest("Juan", "Perez", "jperez", "j@x.com", "short")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updatePersonRejectsInvalidEmail() {
                doReturn(personContext("jperez")).when(ldap).lookupContext(any(LdapName.class));
        assertThatThrownBy(() -> service.updatePerson(actor, "reclamos", "jperez",
                new UpdatePersonRequest(null, null, "invalid", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listPeopleRejectsInvalidGroupFilterNoLdap() {
        assertThatThrownBy(() -> service.listPeople("reclamos",
                new PeopleSearchCriteria(0, 10, null, "Bad Name", null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ldap);
    }

    private Attributes uidAttrs(String uid) {
        BasicAttributes a = new BasicAttributes(true);
        a.put(new BasicAttribute("uid", uid));
        return a;
    }

    private Attributes cnAttrs(String cn) {
        BasicAttributes a = new BasicAttributes(true);
        a.put(new BasicAttribute("cn", cn));
        return a;
    }

    private Attributes empAttrs(String employeeNumber) {
        BasicAttributes a = new BasicAttributes(true);
        a.put(new BasicAttribute("employeeNumber", employeeNumber));
        return a;
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
