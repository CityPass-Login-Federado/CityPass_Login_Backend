package citypass.loginfederado.panel;

import citypass.loginfederado.exception.GlobalExceptionHandler;
import citypass.loginfederado.panel.dto.BulkMembershipRequest;
import citypass.loginfederado.panel.dto.BulkMembershipResponse;
import citypass.loginfederado.panel.dto.BulkMembershipStatus;
import citypass.loginfederado.panel.dto.GlobalPersonView;
import citypass.loginfederado.panel.dto.GroupCreateRequest;
import citypass.loginfederado.panel.dto.GroupSearchCriteria;
import citypass.loginfederado.panel.dto.GroupView;
import citypass.loginfederado.panel.dto.MemberRequest;
import citypass.loginfederado.panel.dto.MembershipChangeResponse;
import citypass.loginfederado.panel.dto.NewPersonRequest;
import citypass.loginfederado.panel.dto.PaginatedResponse;
import citypass.loginfederado.panel.dto.PasswordResetRequest;
import citypass.loginfederado.panel.dto.PeopleSearchCriteria;
import citypass.loginfederado.panel.dto.PersonView;
import citypass.loginfederado.panel.dto.UpdatePersonRequest;
import citypass.loginfederado.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PanelControllerTest {

    private final Jwt jwt = mock(Jwt.class);
    private final PanelAuthorization.Delegate normalDelegate =
            new PanelAuthorization.Delegate("U000001", "delegate", "reclamos");
    private final PanelAuthorization.Delegate globalDelegate =
            new PanelAuthorization.Delegate("U000007", "admin-global", "", true);

    private PanelPersonService persons;
    private PanelGroupService groups;
    private PanelAccountService accounts;
    private PanelAuthorization authorization;
    private PanelController controller;

    @BeforeEach
    void setUp() {
        persons = mock(PanelPersonService.class);
        groups = mock(PanelGroupService.class);
        accounts = mock(PanelAccountService.class);
        authorization = mock(PanelAuthorization.class);
        controller = new PanelController(persons, groups, accounts, authorization, mock(PanelAuditService.class),
                mock(RefreshTokenService.class));
    }

    @Test
    void normalDelegateAlwaysUsesModuleFromItsToken() {
        when(authorization.requireDelegate(jwt)).thenReturn(normalDelegate);
        var expected = new PaginatedResponse<PersonView>(List.of(), 0, 0, 0, 10);
        when(persons.listPeople(eq("reclamos"), any(PeopleSearchCriteria.class))).thenReturn(expected);

        assertThat(controller.listPeople(jwt, "movilidad", 0, 10, null, null, null)).isSameAs(expected);

        verify(persons).listPeople(eq("reclamos"), any(PeopleSearchCriteria.class));
    }

    @Test
    void globalAdminRoutesEveryPanelOperationToNormalizedRequestedModule() {
        when(authorization.requireDelegate(jwt)).thenReturn(globalDelegate);
        var person = new PersonView("U000042", "jperez", "Juan", "Perez", "j@x.com", false);
        var group = new GroupView("ops", List.of(), false);
        var people = new PaginatedResponse<>(List.of(person), 1, 1, 0, 10);
        var groupPage = new PaginatedResponse<>(List.of(group), 1, 1, 0, 10);
        var membership = new MembershipChangeResponse(group, List.of());
        when(persons.listPeople(eq("movilidad"), any(PeopleSearchCriteria.class))).thenReturn(people);
        when(persons.findPerson("movilidad", "jperez")).thenReturn(Optional.of(person));
        when(persons.createPerson(any(), eq("movilidad"), any())).thenReturn(person);
        when(persons.updatePerson(any(), eq("movilidad"), eq("jperez"), any())).thenReturn(person);
        when(groups.listGroups(eq("movilidad"), any(GroupSearchCriteria.class))).thenReturn(groupPage);
        when(groups.createGroup(any(), eq("movilidad"), eq("ops"))).thenReturn(group);
        when(groups.addMember(any(), eq("movilidad"), eq("ops"), eq("jperez"))).thenReturn(membership);
        when(groups.removeMember(any(), eq("movilidad"), eq("ops"), eq("jperez"))).thenReturn(membership);

        controller.listPeople(jwt, " Movilidad ", 0, 10, null, null, null);
        assertThat(controller.getPerson(jwt, "MOVILIDAD", "jperez")).isEqualTo(person);
        controller.createPerson(jwt, "movilidad", new NewPersonRequest("Juan", "Perez", "jperez", "j@x.com", "password1"));
        controller.updatePerson(jwt, "movilidad", "jperez", new UpdatePersonRequest(null, null, null, null));
        controller.disablePerson(jwt, "movilidad", "jperez");
        controller.enablePerson(jwt, "movilidad", "jperez");
        controller.resetPassword(jwt, "movilidad", "jperez", new PasswordResetRequest("password1"));
        controller.listGroups(jwt, "movilidad", 0, 10, null, null);
        controller.createGroup(jwt, "movilidad", new GroupCreateRequest("ops"));
        controller.deleteGroup(jwt, "movilidad", "ops");
        controller.addMember(jwt, "movilidad", "ops", new MemberRequest("jperez"));
        controller.removeMember(jwt, "movilidad", "ops", "jperez");

        verify(accounts).disablePerson(any(), eq("movilidad"), eq("jperez"));
        verify(accounts).enablePerson(any(), eq("movilidad"), eq("jperez"));
        verify(accounts).resetPassword(any(), eq("movilidad"), eq("jperez"), eq("password1"));
        verify(groups).deleteGroup(any(), eq("movilidad"), eq("ops"));
        verify(groups).addMember(any(), eq("movilidad"), eq("ops"), eq("jperez"));
        verify(groups).removeMember(any(), eq("movilidad"), eq("ops"), eq("jperez"));
    }

    @Test
    void listAllPeopleReturnsEveryModuleForGlobalAdmin() {
        when(authorization.requireDelegate(jwt)).thenReturn(globalDelegate);
        var people = List.of(
                new GlobalPersonView("reclamos", "U000001", "jperez", "Juan", "Perez", "j@x.com", false),
                new GlobalPersonView("movilidad", "U000002", "mlopez", "Maria", "Lopez", "m@x.com", true)
        );
        when(persons.listAllPeopleGlobal()).thenReturn(people);

        assertThat(controller.listAllPeople(jwt, null)).containsExactlyElementsOf(people);
    }

    @Test
    void listAllPeopleForSelectedModuleUsesLowercaseModuleAndSortsByUid() {
        when(authorization.requireDelegate(jwt)).thenReturn(globalDelegate);
        var person = new PersonView("U000021", "zperez", "Zoe", "Perez", "z@x.com", false);
        when(persons.listPeople(eq("reclamos"), any(PeopleSearchCriteria.class)))
                .thenReturn(new PaginatedResponse<>(List.of(person), 1, 1, 0, 10));

        var response = controller.listAllPeople(jwt, "RECLAMOS");

        assertThat(response)
                .extracting(GlobalPersonView::uid)
                .containsExactly("zperez");
        assertThat(response.getFirst().module()).isEqualTo("reclamos");
    }

    @Test
    void getPersonMissingReturns404() {
        when(authorization.requireDelegate(jwt)).thenReturn(globalDelegate);
        when(persons.findPerson("movilidad", "nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getPerson(jwt, "movilidad", "nobody"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void disablePersonMissingReturns404() {
        when(authorization.requireDelegate(jwt)).thenReturn(globalDelegate);
        when(persons.findPerson("movilidad", "nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.disablePerson(jwt, "movilidad", "nobody"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verify(accounts, never()).disablePerson(any(), any(), any());
    }

    @Test
    void globalAdminMustProvideKnownModule() {
        when(authorization.requireDelegate(jwt)).thenReturn(globalDelegate);

        assertBadRequest(() -> controller.listPeople(jwt, null, 0, 10, null, null, null), "Módulo requerido");
        assertBadRequest(() -> controller.listGroups(jwt, "unknown", 0, 10, null, null), "Módulo inválido");

        verify(persons, never()).listPeople(any(), any());
        verify(groups, never()).listGroups(any(), any());
    }

    @Test
    void modulesEndpointRequiresAuthorizationAndReturnsDirectoryModules() {
        when(authorization.requireDelegate(jwt)).thenReturn(globalDelegate);

        assertThat(controller.listModules(jwt)).containsExactlyElementsOf(PanelDirectoryRules.MODULES);

        verify(authorization).requireDelegate(jwt);
    }

    @Test
    void bulkMembershipEndpointUsesNormalDelegateModuleAndPropagatesResponse() {
        when(authorization.requireDelegate(jwt)).thenReturn(normalDelegate);
        var request = new BulkMembershipRequest(List.of(" usuario1 "), List.of(" grupo-a "));
        var expected = new BulkMembershipResponse(BulkMembershipStatus.SUCCESS, 1, 1, 0, 0,
                List.of(), List.of());
        when(groups.addMembersBulk(normalDelegate, "reclamos", request)).thenReturn(expected);

        assertThat(controller.addMembersBulk(jwt, "movilidad", request)).isSameAs(expected);

        verify(groups).addMembersBulk(normalDelegate, "reclamos", request);
    }

    @Test
    void bulkMembershipEndpointUsesNormalizedModuleForGlobalAdmin() {
        when(authorization.requireDelegate(jwt)).thenReturn(globalDelegate);
        var request = new BulkMembershipRequest(List.of("usuario1"), List.of("grupo-a"));
        var expected = new BulkMembershipResponse(BulkMembershipStatus.SUCCESS, 1, 1, 0, 0,
                List.of(), List.of());
        when(groups.addMembersBulk(any(), eq("movilidad"), eq(request))).thenReturn(expected);

        assertThat(controller.addMembersBulk(jwt, " Movilidad ", request)).isSameAs(expected);

        verify(groups).addMembersBulk(
                eq(new PanelAuthorization.Delegate("U000007", "admin-global", "movilidad", true)),
                eq("movilidad"), eq(request));
    }

    @Test
    void bulkMembershipEndpointRejectsMissingOrUnknownModuleForGlobalAdmin() {
        when(authorization.requireDelegate(jwt)).thenReturn(globalDelegate);
        var request = new BulkMembershipRequest(List.of("usuario1"), List.of("grupo-a"));

        assertBadRequest(() -> controller.addMembersBulk(jwt, null, request), "Módulo requerido");
        assertBadRequest(() -> controller.addMembersBulk(jwt, "desconocido", request), "Módulo inválido");

        verify(groups, never()).addMembersBulk(any(), any(), any());
    }

    @Test
    void bulkMembershipBeanValidationReturns400ForMissingEmptyNullAndBlankLists() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        List<String> invalidBodies = List.of(
                "{}",
                "{\"memberUids\":[],\"groupNames\":[\"grupo-a\"]}",
                "{\"memberUids\":[\"usuario1\"],\"groupNames\":[]}",
                "{\"memberUids\":[null],\"groupNames\":[\"grupo-a\"]}",
                "{\"memberUids\":[\"   \"],\"groupNames\":[\"grupo-a\"]}",
                "{\"memberUids\":[\"usuario1\"],\"groupNames\":[null]}",
                "{\"memberUids\":[\"usuario1\"],\"groupNames\":[\"   \"]}"
        );

        for (String body : invalidBodies) {
            mockMvc.perform(post("/panel/group-memberships/bulk")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(groups);
    }

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                         String expectedReason) {
        assertThatThrownBy(call)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getReason()).contains(expectedReason);
                });
    }
}
