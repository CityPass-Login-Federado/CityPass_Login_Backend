package citypass.loginfederado.panel;

import citypass.loginfederado.panel.dto.GroupCreateRequest;
import citypass.loginfederado.panel.dto.GroupView;
import citypass.loginfederado.panel.dto.MemberRequest;
import citypass.loginfederado.panel.dto.MembershipChangeResponse;
import citypass.loginfederado.panel.dto.NewPersonRequest;
import citypass.loginfederado.panel.dto.PasswordResetRequest;
import citypass.loginfederado.panel.dto.PersonView;
import citypass.loginfederado.panel.dto.UpdatePersonRequest;
import citypass.loginfederado.service.RefreshTokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Único entrypoint HTTP del backend del panel (manual §5-§6).
 *
 * Regla de segregación hecha "opción que no existe": el módulo operado sale
 * SIEMPRE del claim `module` del token del delegado — ningún endpoint acepta
 * un módulo por parámetro, así nadie puede siquiera nombrar otro módulo.
 */
@RestController
@RequestMapping("/panel")
public class PanelController {

    private final PanelDirectoryService directory;
    private final PanelAuthorization authorization;
    private final PanelAuditService audit;
    private final RefreshTokenService refreshTokens;

    public PanelController(PanelDirectoryService directory,
                        PanelAuthorization authorization,
                        PanelAuditService audit,
                        RefreshTokenService refreshTokens) {
        this.directory = directory;
        this.authorization = authorization;
        this.audit = audit;
        this.refreshTokens = refreshTokens;
    }

    // ------------------------------------------------------------------
    // Personas
    // ------------------------------------------------------------------

    @GetMapping("/people")
    public List<PersonView> listPeople(@AuthenticationPrincipal Jwt jwt) {
        return directory.listPeople(module(jwt));
    }

    @GetMapping("/people/{uid}")
    public PersonView getPerson(@AuthenticationPrincipal Jwt jwt, @PathVariable String uid) {
        return directory.findPerson(module(jwt), uid)
                .orElseThrow(() -> notFound("No existe esa persona en su módulo"));
    }

    @PostMapping("/people")
    public ResponseEntity<PersonView> createPerson(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody NewPersonRequest request) {
        PanelAuthorization.Delegate delegate = delegate(jwt);
        PersonView created = directory.createPerson(delegate, delegate.module(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Corrección de datos y/o renombre con reparación de membresías. */
    @PutMapping("/people/{uid}")
    public PersonView updatePerson(@AuthenticationPrincipal Jwt jwt,
                                @PathVariable String uid,
                                @RequestBody UpdatePersonRequest request) {
        PanelAuthorization.Delegate delegate = delegate(jwt);
        return directory.updatePerson(delegate, delegate.module(), uid, request);
    }

    /**
     * Baja (D7): nunca borra la ficha; bloquea vía ppolicy Y mata todas las
     * sesiones vivas (refresh tokens) al instante.
     */
    @PostMapping("/people/{uid}/disable")
    public ResponseEntity<Void> disablePerson(@AuthenticationPrincipal Jwt jwt,
                                            @PathVariable String uid) {
        PanelAuthorization.Delegate delegate = delegate(jwt);
        PersonView person = directory.findPerson(delegate.module(), uid)
                .orElseThrow(() -> notFound("No existe esa persona en su módulo"));
        directory.disablePerson(delegate, delegate.module(), uid);
        refreshTokens.revokeAllForSub(person.employeeNumber());
        audit.record(delegate, "SESSIONS_REVOKED", person.uid(), "baja de persona");
        return ResponseEntity.noContent().build();
    }

    /** Rehabilitación: recupera identidad, historial y grupos intactos. */
    @PostMapping("/people/{uid}/enable")
    public ResponseEntity<Void> enablePerson(@AuthenticationPrincipal Jwt jwt,
                                            @PathVariable String uid) {
        PanelAuthorization.Delegate delegate = delegate(jwt);
        directory.enablePerson(delegate, delegate.module(), uid);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/people/{uid}/reset-password")
    public ResponseEntity<Void> resetPassword(@AuthenticationPrincipal Jwt jwt,
                                            @PathVariable String uid,
                                            @Valid @RequestBody PasswordResetRequest request) {
        PanelAuthorization.Delegate delegate = delegate(jwt);
        directory.resetPassword(delegate, delegate.module(), uid, request.temporaryPassword());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // Grupos
    // ------------------------------------------------------------------

    @GetMapping("/groups")
    public List<GroupView> listGroups(@AuthenticationPrincipal Jwt jwt) {
        return directory.listGroups(module(jwt));
    }

    @PostMapping("/groups")
    public ResponseEntity<GroupView> createGroup(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody GroupCreateRequest request) {
        PanelAuthorization.Delegate delegate = delegate(jwt);
        GroupView created = directory.createGroup(delegate, delegate.module(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** El grupo reservado 'delegados' se niega dentro del servicio. */
    @DeleteMapping("/groups/{name}")
    public ResponseEntity<Void> deleteGroup(@AuthenticationPrincipal Jwt jwt,
                                            @PathVariable String name) {
        PanelAuthorization.Delegate delegate = delegate(jwt);
        directory.deleteGroup(delegate, delegate.module(), name);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/groups/{name}/members")
    public MembershipChangeResponse addMember(@AuthenticationPrincipal Jwt jwt,
                                            @PathVariable String name,
                                            @Valid @RequestBody MemberRequest request) {
        PanelAuthorization.Delegate delegate = delegate(jwt);
        return directory.addMember(delegate, delegate.module(), name, request.memberUid());
    }

    @DeleteMapping("/groups/{name}/members/{uid}")
    public MembershipChangeResponse removeMember(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable String name,
                                                @PathVariable String uid) {
        PanelAuthorization.Delegate delegate = delegate(jwt);
        return directory.removeMember(delegate, delegate.module(), name, uid);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private PanelAuthorization.Delegate delegate(Jwt jwt) {
        return authorization.requireDelegate(jwt);
    }

    private String module(Jwt jwt) {
        return delegate(jwt).module();
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
