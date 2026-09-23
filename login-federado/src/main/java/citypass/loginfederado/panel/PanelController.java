package citypass.loginfederado.panel;

<<<<<<< Updated upstream
import citypass.loginfederado.panel.dto.GlobalPersonView;
=======
import citypass.loginfederado.panel.dto.AdminGroupView;
import citypass.loginfederado.panel.dto.AdminPersonView;
>>>>>>> Stashed changes
import citypass.loginfederado.panel.dto.GroupCreateRequest;
import citypass.loginfederado.panel.dto.GroupView;
import citypass.loginfederado.panel.dto.MemberRequest;
import citypass.loginfederado.panel.dto.MembershipChangeResponse;
import citypass.loginfederado.panel.dto.NewPersonRequest;
import citypass.loginfederado.panel.dto.PasswordResetRequest;
import citypass.loginfederado.panel.dto.PersonView;
import citypass.loginfederado.panel.dto.UpdatePersonRequest;
import citypass.loginfederado.panel.dto.PeopleSearchCriteria;
import citypass.loginfederado.panel.dto.GroupSearchCriteria;
import citypass.loginfederado.panel.dto.PaginatedResponse;
import citypass.loginfederado.service.RefreshTokenService;
import org.springframework.security.access.AccessDeniedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/**
 * Único entrypoint HTTP del backend del panel (manual §5-§6).
 *
 * Regla de segregación hecha "opción que no existe": el módulo operado sale
 * SIEMPRE del claim `module` del token del delegado — ningún endpoint acepta
 * un módulo por parámetro, así nadie puede siquiera nombrar otro módulo.
 *
 * Todos los endpoints requieren un access token humano con:
 * audience=citypass-admin-api, token_use=human, grupo delegados y claim module.
 */
@RestController
@RequestMapping("/panel")
public class PanelController {

    private final PanelPersonService persons;
    private final PanelGroupService groups;
    private final PanelAccountService accounts;
    private final PanelAuthorization authorization;
    private final PanelAuditService audit;
    private final RefreshTokenService refreshTokens;

    public PanelController(PanelPersonService persons,
                        PanelGroupService groups,
                        PanelAccountService accounts,
                        PanelAuthorization authorization,
                        PanelAuditService audit,
                        RefreshTokenService refreshTokens) {
        this.persons = persons;
        this.groups = groups;
        this.accounts = accounts;
        this.authorization = authorization;
        this.audit = audit;
        this.refreshTokens = refreshTokens;
    }

    // ------------------------------------------------------------------
    // Personas
    // ------------------------------------------------------------------

    @Operation(
            summary = "Listar personas del módulo",
            description = "Lista paginada de personas del módulo del delegado. "
                    + "Solo ve el módulo de su token: nunca personas de otros módulos. "
                    + "Filtros opcionales: search, group y disabled.",
            tags = "Panel — Personas")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Página de personas del módulo"),
            @ApiResponse(responseCode = "401", description = "Token ausente o inválido"),
            @ApiResponse(responseCode = "403", description = "Token sin claims de delegado")
    })
    @GetMapping("/people")
    public PaginatedResponse<PersonView> listPeople(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Módulo a operar (solo admin global)") @RequestParam(required = false) String module,
            @Parameter(description = "Número de página (base 0)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "Texto libre en nombre, apellido, uid o mail") @RequestParam(required = false) String search,
            @Parameter(description = "Nombre de grupo para filtrar por membresía") @RequestParam(required = false) String group,
            @Parameter(description = "true: solo deshabilitadas; false: solo habilitadas") @RequestParam(required = false) Boolean disabled) {
        return persons.listPeople(delegate(jwt, module).module(), new PeopleSearchCriteria(page, size, search, group, disabled));
    }

    @Operation(summary = "Obtener persona por UID",
            description = "Ficha completa de una persona del propio módulo.",
            tags = "Panel — Personas")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ficha de la persona"),
            @ApiResponse(responseCode = "401", description = "Token ausente o inválido"),
            @ApiResponse(responseCode = "403", description = "Token sin claims de delegado"),
            @ApiResponse(responseCode = "404", description = "Persona inexistente en el módulo")
    })
    @GetMapping("/people/{uid}")
    public PersonView getPerson(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Módulo a operar (solo admin global)") @RequestParam(required = false) String module,
            @Parameter(description = "UID (preferred_username) de la persona") @PathVariable String uid) {
        return persons.findPerson(delegate(jwt, module).module(), uid)
                .orElseThrow(() -> notFound("No existe esa persona en su módulo"));
    }

    @Operation(summary = "Crear persona",
            description = "Alta con ID automático secuencial (D3): el employeeNumber lo asigna el sistema, "
                    + "nadie lo elige. Unicidad global de username y email pre-chequeada.",
            tags = "Panel — Personas")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Persona creada"),
            @ApiResponse(responseCode = "400", description = "Validación de campos fallida"),
            @ApiResponse(responseCode = "401", description = "Token ausente o inválido"),
            @ApiResponse(responseCode = "403", description = "Token sin claims de delegado"),
            @ApiResponse(responseCode = "422", description = "Regla de negocio incumplida (ej: username duplicado)")
    })
    @PostMapping("/people")
    public ResponseEntity<PersonView> createPerson(@AuthenticationPrincipal Jwt jwt,
                                                @Parameter(description = "Módulo a operar (solo admin global)") @RequestParam(required = false) String module,
                                                @Valid @RequestBody NewPersonRequest request) {
        var delegate = delegate(jwt, module);
        PersonView created = persons.createPerson(delegate, delegate.module(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Actualizar persona",
            description = "Corrección de datos y/o renombre con reparación de membresías. "
                    + "El employeeNumber es inmutable (D3): no existe campo para cambiarlo.",
            tags = "Panel — Personas")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Persona actualizada"),
            @ApiResponse(responseCode = "400", description = "Validación de campos fallida"),
            @ApiResponse(responseCode = "401", description = "Token ausente o inválido"),
            @ApiResponse(responseCode = "403", description = "Token sin claims de delegado"),
            @ApiResponse(responseCode = "404", description = "Persona inexistente en el módulo"),
            @ApiResponse(responseCode = "422", description = "Regla de negocio incumplida")
    })
    @PutMapping("/people/{uid}")
    public PersonView updatePerson(@AuthenticationPrincipal Jwt jwt,
                                @Parameter(description = "Módulo a operar (solo admin global)") @RequestParam(required = false) String module,
                                @Parameter(description = "UID (preferred_username) de la persona") @PathVariable String uid,
                                @RequestBody UpdatePersonRequest request) {
        var delegate = delegate(jwt, module);
        return persons.updatePerson(delegate, delegate.module(), uid, request);
    }

    //Lista a toda las personas
    @GetMapping("/people/all")
        public List<GlobalPersonView> listAllPeople(
                @AuthenticationPrincipal Jwt jwt,
                @RequestParam(required = false) String module) {
                
            PanelAuthorization.Delegate delegate =
                    authorization.requireDelegate(jwt);
                
            if (!delegate.global()) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Solo un admin global puede listar todos los módulos");
            }
        
            if (module == null || module.isBlank()) {
                return persons.listAllPeopleGlobal();
            }
        
            String selectedModule = module.toLowerCase(Locale.ROOT);

            return persons.listPeople(
                selectedModule,
                new PeopleSearchCriteria(0, Integer.MAX_VALUE, null, null, null)
                ).content().stream()
                        .map(person -> new GlobalPersonView(
                                selectedModule,
                                person.employeeNumber(),
                                person.uid(),
                                person.givenName(),
                                person.sn(),
                                person.email(),
                                person.disabled()
                        ))
                        .sorted(java.util.Comparator.comparing(GlobalPersonView::uid))
                        .toList();
    }

    @Operation(summary = "Deshabilitar persona (baja D7)",
            description = "Nunca borra la ficha: bloquea vía ppolicy Y mata todas las sesiones "
                    + "vivas (refresh tokens) al instante.",
            tags = "Panel — Personas")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Persona deshabilitada y sesiones revocadas"),
            @ApiResponse(responseCode = "401", description = "Token ausente o inválido"),
            @ApiResponse(responseCode = "403", description = "Token sin claims de delegado"),
            @ApiResponse(responseCode = "404", description = "Persona inexistente en el módulo")
    })
    @PostMapping("/people/{uid}/disable")
    public ResponseEntity<Void> disablePerson(@AuthenticationPrincipal Jwt jwt,
                                            @Parameter(description = "Módulo a operar (solo admin global)") @RequestParam(required = false) String module,
                                            @Parameter(description = "UID (preferred_username) de la persona") @PathVariable String uid) {
        var delegate = delegate(jwt, module);
        PersonView person = persons.findPerson(delegate.module(), uid)
                .orElseThrow(() -> notFound("No existe esa persona en su módulo"));
        accounts.disablePerson(delegate, delegate.module(), uid);
        refreshTokens.revokeAllForSub(
                person.employeeNumber(),
                delegate.module()
        );
        audit.record(delegate, "SESSIONS_REVOKED", person.uid(), "baja de persona");
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Rehabilitar persona",
            description = "Recupera identidad, historial y grupos intactos.",
            tags = "Panel — Personas")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Persona rehabilitada"),
            @ApiResponse(responseCode = "401", description = "Token ausente o inválido"),
            @ApiResponse(responseCode = "403", description = "Token sin claims de delegado"),
            @ApiResponse(responseCode = "404", description = "Persona inexistente en el módulo")
    })
    @PostMapping("/people/{uid}/enable")
    public ResponseEntity<Void> enablePerson(@AuthenticationPrincipal Jwt jwt,
                                            @Parameter(description = "Módulo a operar (solo admin global)") @RequestParam(required = false) String module,
                                            @Parameter(description = "UID (preferred_username) de la persona") @PathVariable String uid) {
        var delegate = delegate(jwt, module);
        accounts.enablePerson(delegate, delegate.module(), uid);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Resetear contraseña",
            description = "Asigna una contraseña temporal. La persona debe cambiarla al entrar.",
            tags = "Panel — Personas")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Contraseña reiniciada"),
            @ApiResponse(responseCode = "400", description = "Validación de campos fallida"),
            @ApiResponse(responseCode = "401", description = "Token ausente o inválido"),
            @ApiResponse(responseCode = "403", description = "Token sin claims de delegado"),
            @ApiResponse(responseCode = "404", description = "Persona inexistente en el módulo")
    })
    @PostMapping("/people/{uid}/reset-password")
    public ResponseEntity<Void> resetPassword(@AuthenticationPrincipal Jwt jwt,
                                            @Parameter(description = "Módulo a operar (solo admin global)") @RequestParam(required = false) String module,
                                            @Parameter(description = "UID (preferred_username) de la persona") @PathVariable String uid,
                                            @Valid @RequestBody PasswordResetRequest request) {
        var delegate = delegate(jwt, module);
        accounts.resetPassword(delegate, delegate.module(), uid, request.temporaryPassword());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // Grupos
    // ------------------------------------------------------------------

    @Operation(summary = "Listar grupos del módulo",
            description = "Lista paginada de grupos del módulo del delegado. "
                    + "El grupo reservado 'delegados' aparece marcado con reserved=true.",
            tags = "Panel — Grupos")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Página de grupos del módulo"),
            @ApiResponse(responseCode = "401", description = "Token ausente o inválido"),
            @ApiResponse(responseCode = "403", description = "Token sin claims de delegado")
    })
    @GetMapping("/groups")
    public PaginatedResponse<GroupView> listGroups(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Módulo a operar (solo admin global)") @RequestParam(required = false) String module,
            @Parameter(description = "Número de página (base 0)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "Texto libre en el nombre del grupo") @RequestParam(required = false) String search,
            @Parameter(description = "true: solo grupos reservados; false: solo no reservados") @RequestParam(required = false) Boolean reserved) {
        return groups.listGroups(delegate(jwt, module).module(), new GroupSearchCriteria(page, size, search, reserved));
    }

    @Operation(summary = "Crear grupo",
            description = "D6: solo minúsculas, números y guiones. 'delegados' es reservado y se niega dentro del servicio.",
            tags = "Panel — Grupos")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Grupo creado"),
            @ApiResponse(responseCode = "400", description = "Validación de campos fallida"),
            @ApiResponse(responseCode = "401", description = "Token ausente o inválido"),
            @ApiResponse(responseCode = "403", description = "Token sin claims de delegado"),
            @ApiResponse(responseCode = "409", description = "Conflicto: el grupo ya existe o es 'delegados'"),
            @ApiResponse(responseCode = "422", description = "Regla de negocio incumplida (nombre inválido D6)")
    })
    @PostMapping("/groups")
    public ResponseEntity<GroupView> createGroup(@AuthenticationPrincipal Jwt jwt,
                                                @Parameter(description = "Módulo a operar (solo admin global)") @RequestParam(required = false) String module,
                                                @Valid @RequestBody GroupCreateRequest request) {
        var delegate = delegate(jwt, module);
        GroupView created = groups.createGroup(delegate, delegate.module(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Eliminar grupo",
            description = "El grupo reservado 'delegados' no se puede borrar.",
            tags = "Panel — Grupos")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Grupo eliminado"),
            @ApiResponse(responseCode = "401", description = "Token ausente o inválido"),
            @ApiResponse(responseCode = "403", description = "Token sin claims de delegado"),
            @ApiResponse(responseCode = "409", description = "Conflicto: es el grupo reservado 'delegados'")
    })
    @DeleteMapping("/groups/{name}")
    public ResponseEntity<Void> deleteGroup(@AuthenticationPrincipal Jwt jwt,
                                            @Parameter(description = "Módulo a operar (solo admin global)") @RequestParam(required = false) String module,
                                            @Parameter(description = "Nombre del grupo (solo minúsculas, números, guiones)") @PathVariable String name) {
        var delegate = delegate(jwt, module);
        groups.deleteGroup(delegate, delegate.module(), name);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Agregar miembro a grupo",
            description = "Solo se agregan personas (no grupos). D5: máximo 50 grupos por persona.",
            tags = "Panel — Grupos")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Grupo resultante con el miembro agregado"),
            @ApiResponse(responseCode = "401", description = "Token ausente o inválido"),
            @ApiResponse(responseCode = "403", description = "Token sin claims de delegado"),
            @ApiResponse(responseCode = "422", description = "Regla de negocio incumplida (ej: tope D5)")
    })
    @PostMapping("/groups/{name}/members")
    public MembershipChangeResponse addMember(@AuthenticationPrincipal Jwt jwt,
                                            @Parameter(description = "Módulo a operar (solo admin global)") @RequestParam(required = false) String module,
                                            @Parameter(description = "Nombre del grupo (solo minúsculas, números, guiones)") @PathVariable String name,
                                            @Valid @RequestBody MemberRequest request) {
        var delegate = delegate(jwt, module);
        return groups.addMember(delegate, delegate.module(), name, request.memberUid());
    }

    @Operation(summary = "Quitar miembro de grupo",
            description = "Si el grupo es 'delegados', no puede quedar vacío.",
            tags = "Panel — Grupos")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Grupo resultante sin el miembro"),
            @ApiResponse(responseCode = "401", description = "Token ausente o inválido"),
            @ApiResponse(responseCode = "403", description = "Token sin claims de delegado"),
            @ApiResponse(responseCode = "422", description = "Regla de negocio incumplida (ej: 'delegados' no puede quedar vacío)")
    })
    @DeleteMapping("/groups/{name}/members/{uid}")
    public MembershipChangeResponse removeMember(@AuthenticationPrincipal Jwt jwt,
                                                @Parameter(description = "Módulo a operar (solo admin global)") @RequestParam(required = false) String module,
                                                @Parameter(description = "Nombre del grupo (solo minúsculas, números, guiones)") @PathVariable String name,
                                                @Parameter(description = "UID (preferred_username) de la persona") @PathVariable String uid) {
        var delegate = delegate(jwt, module);
        return groups.removeMember(delegate, delegate.module(), name, uid);
    }

    @Operation(summary = "Listar módulos existentes",
            description = "Los 6 módulos del directorio (spec §2.2). "
                    + "Pensado para que el admin global sepa qué valores acepta ?module=?; "
                    + "un delegado normal solo recuerda que su módulo viene de su token.",
            tags = "Panel — Grupos")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista de módulos"),
            @ApiResponse(responseCode = "401", description = "Token ausente o inválido"),
            @ApiResponse(responseCode = "403", description = "Token sin claims de delegado")
    })
    @GetMapping("/modules")
    public List<String> listModules(@AuthenticationPrincipal Jwt jwt) {
        authorization.requireDelegate(jwt);
        return PanelDirectoryRules.MODULES;
    }

    // ------------------------------------------------------------------
    // Vista transversal (solo admin global)
    // ------------------------------------------------------------------

    @Operation(
            summary = "Listar TODAS las personas (admin global)",
            description = "Agrega las personas de los 6 módulos en una sola página global, "
                    + "ordenadas por uid. Cada fila lleva su módulo. Filtros opcionales: search, "
                    + "group y disabled (se aplican por módulo). Solo admin global: un delegado "
                    + "normal recibe 403.",
            tags = "Panel — Personas")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Página global de personas"),
            @ApiResponse(responseCode = "401", description = "Token ausente o inválido"),
            @ApiResponse(responseCode = "403", description = "No es admin global")
    })
    @GetMapping("/admin/people")
    public PaginatedResponse<AdminPersonView> listAllPeople(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Número de página (base 0)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "Texto libre en nombre, apellido, uid o mail") @RequestParam(required = false) String search,
            @Parameter(description = "Nombre de grupo para filtrar por membresía") @RequestParam(required = false) String group,
            @Parameter(description = "true: solo deshabilitadas; false: solo habilitadas") @RequestParam(required = false) Boolean disabled) {
        adminGlobal(jwt);
        return persons.listAllPeople(new PeopleSearchCriteria(page, size, search, group, disabled));
    }

    @Operation(summary = "Listar TODOS los grupos (admin global)",
            description = "Agrega los grupos de los 6 módulos en una sola página global, "
                    + "ordenados por nombre. Cada fila lleva su módulo. Filtros opcionales: "
                    + "search y reserved (se aplican por módulo). Solo admin global: un delegado "
                    + "normal recibe 403.",
            tags = "Panel — Grupos")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Página global de grupos"),
            @ApiResponse(responseCode = "401", description = "Token ausente o inválido"),
            @ApiResponse(responseCode = "403", description = "No es admin global")
    })
    @GetMapping("/admin/groups")
    public PaginatedResponse<AdminGroupView> listAllGroups(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Número de página (base 0)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "Texto libre en el nombre del grupo") @RequestParam(required = false) String search,
            @Parameter(description = "true: solo grupos reservados; false: solo no reservados") @RequestParam(required = false) Boolean reserved) {
        adminGlobal(jwt);
        return groups.listAllGroups(new GroupSearchCriteria(page, size, search, reserved));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------


    /**
     * Resuelve quién opera y sobre qué módulo. Un delegado NORMAL solo puede
     * operar el módulo del claim de su token (ignora cualquier ?module=).
     * Un admin GLOBAL opera el módulo que indica en ?module=, obligatorio.
     */
    private PanelAuthorization.Delegate delegate(Jwt jwt, String moduleParam) {
        PanelAuthorization.Delegate base = authorization.requireDelegate(jwt);
        if (!base.global()) {
            return base;
        }
        if (moduleParam == null || moduleParam.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Módulo requerido para admin global");
        }
        String module = moduleParam.trim().toLowerCase(Locale.ROOT);
        if (!PanelDirectoryRules.MODULES.contains(module)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Módulo inválido: " + moduleParam);
        }
        return new PanelAuthorization.Delegate(base.sub(), base.uid(), module, true);
    }

    /**
     * Exige admin GLOBAL (para la vista transversal). A diferencia de
     * {@link #delegate}, acá no hay ?module= que resolver: el scope son
     * todos los módulos y un delegado normal no entra.
     */
    private PanelAuthorization.Delegate adminGlobal(Jwt jwt) {
        PanelAuthorization.Delegate base = authorization.requireDelegate(jwt);
        if (!base.global()) {
            throw new AccessDeniedException("Solo admin global");
        }
        return base;
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}