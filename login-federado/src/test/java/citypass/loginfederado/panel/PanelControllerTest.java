package citypass.loginfederado.panel;

import citypass.loginfederado.panel.dto.GroupView;
import citypass.loginfederado.panel.dto.MemberRequest;
import citypass.loginfederado.panel.dto.MembershipChangeResponse;
import citypass.loginfederado.panel.dto.NewPersonRequest;
import citypass.loginfederado.panel.dto.PersonView;
import citypass.loginfederado.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PanelControllerTest {

    private PanelDirectoryService directory;
    private PanelAuditService audit;
    private RefreshTokenService refreshTokens;
    private PanelController controller;

    @BeforeEach
    void setUp() {
        directory = mock(PanelDirectoryService.class);
        audit = mock(PanelAuditService.class);
        refreshTokens = mock(RefreshTokenService.class);
        controller = new PanelController(directory, new PanelAuthorization(), audit, refreshTokens);
    }

    @Test
    void listPeopleUsesDelegateModule() {
        Jwt jwt = validDelegateJwt("delegados", "reclamos");
        when(directory.listPeople("reclamos")).thenReturn(List.of(new PersonView("U000042", "jperez", "Juan", "Perez", "j@x.com", false)));

        var result = controller.listPeople(jwt, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo("jperez");
        verify(directory).listPeople("reclamos");
    }

    @Test
    void listAllPeopleRequiresGlobalPrivileges() {
        Jwt jwt = validDelegateJwt("delegados", "reclamos");

        assertThatThrownBy(() -> controller.listAllPeople(jwt, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("global");
    }

    @Test
    void listAllPeopleForGlobalReturnsAllModules() {
        Jwt jwt = validDelegateJwt("admin-global", "");
        when(directory.listAllPeopleGlobal()).thenReturn(List.of());

        var result = controller.listAllPeople(jwt, null);

        assertThat(result).isEmpty();
        verify(directory).listAllPeopleGlobal();
    }

    @Test
    void createPersonReturnsCreatedStatus() {
        Jwt jwt = validDelegateJwt("delegados", "reclamos");
        var req = new NewPersonRequest("Juan", "Perez", "jperez", "j@x.com", "12345678");
        when(directory.createPerson(any(), eq("reclamos"), eq(req)))
                .thenReturn(new PersonView("U000042", "jperez", "Juan", "Perez", "j@x.com", false));

        var response = controller.createPerson(jwt, req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().uid()).isEqualTo("jperez");
    }

    @Test
    void addMemberReturnsMembershipResponse() {
        Jwt jwt = validDelegateJwt("delegados", "reclamos");
        var req = new MemberRequest("jperez");
        var expected = new MembershipChangeResponse(new GroupView("soporte", List.of("jperez"), false), List.of());
        when(directory.addMember(any(), eq("reclamos"), eq("soporte"), eq("jperez"))).thenReturn(expected);

        var response = controller.addMember(jwt, "soporte", req);

        assertThat(response.group().name()).isEqualTo("soporte");
        assertThat(response.warnings()).isEmpty();
    }

    private Jwt validDelegateJwt(String group, String module) {
        return new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of(
                        "aud", List.of("citypass-admin-api"),
                        "token_use", "human",
                        "ver", 1L,
                        "groups", List.of(group),
                        "module", module,
                        "preferred_username", "admin",
                        "sub", "U000001"
                )
        );
    }
}
