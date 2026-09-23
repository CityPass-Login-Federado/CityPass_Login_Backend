package citypass.loginfederado.panel;

import citypass.loginfederado.panel.dto.AdminGroupView;
import citypass.loginfederado.panel.dto.BulkMembershipRequest;
import citypass.loginfederado.panel.dto.BulkMembershipResponse;
import citypass.loginfederado.panel.dto.BulkMembershipStatus;
import citypass.loginfederado.panel.dto.GroupSearchCriteria;
import citypass.loginfederado.panel.dto.GroupView;
import citypass.loginfederado.panel.dto.MembershipChangeResponse;
import citypass.loginfederado.panel.dto.MembershipOperationResult;
import citypass.loginfederado.panel.dto.MembershipOperationStatus;
import citypass.loginfederado.panel.dto.MembershipWarning;
import citypass.loginfederado.panel.dto.PaginatedResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.AttributeInUseException;
import org.springframework.ldap.NoSuchAttributeException;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.filter.AndFilter;
import org.springframework.ldap.filter.EqualsFilter;
import org.springframework.ldap.filter.LikeFilter;
import org.springframework.ldap.query.LdapQuery;
import static org.springframework.ldap.query.LdapQueryBuilder.query;
import org.springframework.stereotype.Service;

import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.ModificationItem;
import javax.naming.ldap.LdapName;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static citypass.loginfederado.panel.PanelDirectoryRules.DELEGADOS;
import static citypass.loginfederado.panel.PanelDirectoryRules.MAX_BULK_MEMBERSHIPS;
import static citypass.loginfederado.panel.PanelDirectoryRules.MAX_GROUPS;
import static citypass.loginfederado.panel.PanelDirectoryRules.WARN_GROUPS;
import static citypass.loginfederado.panel.PanelDirectoryRules.validateGroupName;
import static citypass.loginfederado.panel.PanelLdapSupport.PLACEHOLDER_DN;
import static citypass.loginfederado.panel.PanelLdapSupport.addObjectClasses;
import static citypass.loginfederado.panel.PanelLdapSupport.addValue;
import static citypass.loginfederado.panel.PanelLdapSupport.addValues;
import static citypass.loginfederado.panel.PanelLdapSupport.firstRdnValue;
import static citypass.loginfederado.panel.PanelLdapSupport.removeValue;

/**
 * Grupos del directorio del panel (lectura, alta, baja, membresías).
 * La existencia de la persona al agregar se chequea liviano
 * ({@link PanelLdapSupport#personExists}: solo importa que esté, la vista
 * no se usa). La cuenta panel-writer y la mecánica LDAP viven en el support.
 */
@Service
public class PanelGroupService {

    private static final Logger log = LoggerFactory.getLogger(PanelGroupService.class);
    private static final String ALREADY_MEMBER_MESSAGE = "El usuario ya pertenecía al grupo";
    private static final String GROUP_UPDATE_FAILED_MESSAGE = "No se pudo actualizar el grupo";

    private final PanelLdapSupport support;
    private final LdapTemplate ldap;
    private final PanelAuditService audit;

    public PanelGroupService(PanelLdapSupport support, PanelAuditService audit) {
        this.support = support;
        this.ldap = support.ldap();
        this.audit = audit;
    }

    public PaginatedResponse<GroupView> listGroups(String module, GroupSearchCriteria criteria) {
        support.assertModule(module);

        AndFilter andFilter = new AndFilter();
        andFilter.and(new EqualsFilter("objectClass", "groupOfNames"));

        if (criteria.search() != null && !criteria.search().isBlank()) {
            String term = "*" + criteria.search().trim() + "*";
            andFilter.and(new LikeFilter("cn", term));
        }

        LdapQuery query = query()
                .base(support.groupsBase(module))
                .filter(andFilter);

        List<GroupView> allFiltered = ldap.search(query,
                        (AttributesMapper<GroupView>) PanelLdapSupport::toGroupView)
                .stream()
                .filter(g -> criteria.reserved() == null || g.reserved() == criteria.reserved().booleanValue())
                .sorted(java.util.Comparator.comparing(GroupView::name))
                .toList();

        int totalElements = allFiltered.size();
        int size = criteria.size() > 0 ? criteria.size() : 10;
        int page = criteria.page() >= 0 ? criteria.page() : 0;

        int totalPages = (int) Math.ceil((double) totalElements / size);
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, totalElements);

        List<GroupView> pageContent = fromIndex < totalElements
                ? allFiltered.subList(fromIndex, toIndex)
                : List.of();

        return new PaginatedResponse<>(
                pageContent,
                totalElements,
                totalPages,
                page,
                size
        );
    }

    /**
     * Listado TRANSVERSAL (solo admin global): agrega los grupos de los 6
     * módulos en una sola página global. Reusa el listado por módulo (con sus
     * filtros search/reserved) y re-pagina el resultado mezclado, ordenado
     * por nombre. Cada fila lleva su módulo.
     */
    public PaginatedResponse<AdminGroupView> listAllGroups(GroupSearchCriteria criteria) {
        List<AdminGroupView> all = new ArrayList<>();
        for (String module : PanelDirectoryRules.MODULES) {
            PaginatedResponse<GroupView> page = listGroups(module,
                    new GroupSearchCriteria(0, Integer.MAX_VALUE,
                            criteria.search(), criteria.reserved()));
            for (GroupView group : page.content()) {
                all.add(AdminGroupView.of(module, group));
            }
        }
        all.sort(java.util.Comparator.comparing(AdminGroupView::name));

        int totalElements = all.size();
        int size = criteria.size() > 0 ? criteria.size() : 10;
        int page = criteria.page() >= 0 ? criteria.page() : 0;

        int totalPages = (int) Math.ceil((double) totalElements / size);
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, totalElements);

        List<AdminGroupView> pageContent = fromIndex < totalElements
                ? all.subList(fromIndex, toIndex)
                : List.of();

        return new PaginatedResponse<>(
                pageContent,
                totalElements,
                totalPages,
                page,
                size
        );
    }

    /** Alta con placeholder como miembro técnico: ningún grupo nace vacío. */
    public GroupView createGroup(PanelAuthorization.Delegate actor, String module, String name) {
        support.assertModule(module);
        validateGroupName(name);
        if (DELEGADOS.equals(name) && !actor.global()) {
            throw new IllegalStateException("El grupo '" + DELEGADOS + "' es reservado y ya existe en su módulo");
        }
        LdapName dn = support.groupDn(module, name);
        try {
            Attributes attrs = new BasicAttributes(true);
            addObjectClasses(attrs, "top", "groupOfNames");
            attrs.put("cn", name);
            attrs.put("member", PLACEHOLDER_DN);
            ldap.bind(dn, null, attrs);
        } catch (org.springframework.ldap.NameAlreadyBoundException ex) {
            throw new IllegalStateException("Ya existe un grupo con ese nombre en su módulo");
        }
        audit.record(actor, "GROUP_CREATED", dn.toString(), null);
        return buildGroupView(name, module);
    }

    /** Baja física del grupo. delegados NO se borra jamás... salvo admin global. */
    public void deleteGroup(PanelAuthorization.Delegate actor, String module, String name) {
        support.assertModule(module);
        if (DELEGADOS.equalsIgnoreCase(name) && !actor.global()) {
            throw new IllegalStateException("El grupo '" + DELEGADOS + "' no se puede borrar");
        }
        LdapName dn = support.groupDn(module, name);
        support.requireContext(dn);
        // refint limpia los memberOf de las personas automáticamente.
        ldap.unbind(dn);
        audit.record(actor, "GROUP_DELETED", dn.toString(), null);
    }

    /**
     * Sumar una persona. Reglas D4/D5 aplicadas acá Y respaldadas por el
     * directorio: solo personas (constraint anti-anidamiento), máximo 50
     * grupos con aviso desde 30 (token bloat).
     */
    public MembershipChangeResponse addMember(PanelAuthorization.Delegate actor, String module,
                                            String groupName, String memberUid) {
        support.assertModule(module);
        LdapName groupDn = support.groupDn(module, groupName);
        support.requireContext(groupDn);

        if (!support.personExists(module, memberUid)) {
            throw new IllegalStateException("No existe esa persona en su módulo");
        }

        int before = support.membershipCount(memberUid);
        if (before >= MAX_GROUPS) {
            throw new IllegalStateException("Máximo %d grupos por persona (tiene %d)".formatted(MAX_GROUPS, before));
        }

        try {
            ldap.modifyAttributes(groupDn, new ModificationItem[]{
                    addValue("member", support.absPersonDn(module, memberUid))});
        } catch (AttributeInUseException | org.springframework.ldap.UncategorizedLdapException ex) {
            throw new IllegalStateException("No se pudo agregar: ¿ya es miembro del grupo?", ex);
        }

        int after = support.membershipCount(memberUid);
        audit.record(actor, "MEMBER_ADDED", groupDn.toString(), "uid=" + memberUid);
        return new MembershipChangeResponse(buildGroupView(groupName, module), warningsFor(after));
    }

    /**
     * Asigna el producto cartesiano de personas y grupos. Toda validación de
     * existencia y límites sucede antes de la primera escritura; luego cada
     * grupo se modifica de manera independiente y atómica.
     */
    public BulkMembershipResponse addMembersBulk(PanelAuthorization.Delegate actor, String module,
                                                  BulkMembershipRequest request) {
        support.assertModule(module);

        List<String> memberUids = normalizedDistinct(request.memberUids(), "Debe indicar al menos un usuario");
        List<String> groupNames = normalizedDistinct(request.groupNames(), "Debe indicar al menos un grupo");
        long requested = (long) memberUids.size() * groupNames.size();
        if (requested > MAX_BULK_MEMBERSHIPS) {
            throw new IllegalStateException(
                    "La operación masiva solicita %d relaciones y supera el máximo permitido de %d"
                            .formatted(requested, MAX_BULK_MEMBERSHIPS));
        }

        groupNames.forEach(PanelDirectoryRules::validateGroupName);

        Map<String, LdapName> groupDns = new LinkedHashMap<>();
        Map<String, Set<String>> existingMembers = new LinkedHashMap<>();
        for (String groupName : groupNames) {
            LdapName groupDn = support.groupDn(module, groupName);
            DirContextOperations group;
            try {
                group = support.requireContext(groupDn);
            } catch (IllegalStateException ex) {
                throw new IllegalStateException(
                        "No existe el grupo '%s' en el módulo".formatted(groupName), ex);
            }
            groupDns.put(groupName, groupDn);
            existingMembers.put(groupName, normalizedMemberDns(group.getStringAttributes("member")));
        }

        for (String memberUid : memberUids) {
            if (!support.personExists(module, memberUid)) {
                throw new IllegalStateException(
                        "No existe el usuario '%s' en el módulo".formatted(memberUid));
            }
        }

        Map<String, Integer> initialMemberships = new LinkedHashMap<>();
        for (String memberUid : memberUids) {
            int current = support.membershipCount(memberUid);
            int planned = 0;
            String memberDnKey = memberDnKey(support.absPersonDn(module, memberUid));
            for (String groupName : groupNames) {
                if (!existingMembers.get(groupName).contains(memberDnKey)) {
                    planned++;
                }
            }
            int projected = current + planned;
            if (projected > MAX_GROUPS) {
                throw new IllegalStateException(
                        "El usuario '%s' superaría el máximo de %d grupos (total proyectado: %d)"
                                .formatted(memberUid, MAX_GROUPS, projected));
            }
            initialMemberships.put(memberUid, current);
        }

        List<MembershipOperationResult> results = new ArrayList<>((int) requested);
        Map<String, Integer> assignedByMember = new LinkedHashMap<>();
        memberUids.forEach(uid -> assignedByMember.put(uid, 0));

        for (String groupName : groupNames) {
            LdapName groupDn = groupDns.get(groupName);
            Set<String> currentMembers = existingMembers.get(groupName);
            List<String> newMemberUids = memberUids.stream()
                    .filter(uid -> !currentMembers.contains(memberDnKey(support.absPersonDn(module, uid))))
                    .toList();

            boolean groupUpdated = true;
            if (!newMemberUids.isEmpty()) {
                List<String> newMemberDns = newMemberUids.stream()
                        .map(uid -> support.absPersonDn(module, uid))
                        .toList();
                try {
                    ldap.modifyAttributes(groupDn, new ModificationItem[]{addValues("member", newMemberDns)});
                } catch (RuntimeException ex) {
                    groupUpdated = false;
                    log.error("Falló la asignación masiva para grupo={} módulo={}", groupName, module, ex);
                }
            }

            for (String memberUid : memberUids) {
                boolean alreadyMember = currentMembers.contains(
                        memberDnKey(support.absPersonDn(module, memberUid)));
                if (alreadyMember) {
                    results.add(new MembershipOperationResult(memberUid, groupName,
                            MembershipOperationStatus.ALREADY_MEMBER, ALREADY_MEMBER_MESSAGE));
                } else if (groupUpdated) {
                    results.add(new MembershipOperationResult(memberUid, groupName,
                            MembershipOperationStatus.ASSIGNED, null));
                    assignedByMember.computeIfPresent(memberUid, (uid, count) -> count + 1);
                    audit.record(actor, "MEMBER_ADDED", groupDn.toString(), "uid=" + memberUid);
                } else {
                    results.add(new MembershipOperationResult(memberUid, groupName,
                            MembershipOperationStatus.FAILED, GROUP_UPDATE_FAILED_MESSAGE));
                }
            }
        }

        int assigned = count(results, MembershipOperationStatus.ASSIGNED);
        int skipped = count(results, MembershipOperationStatus.ALREADY_MEMBER);
        int failed = count(results, MembershipOperationStatus.FAILED);
        BulkMembershipStatus status = failed == 0
                ? BulkMembershipStatus.SUCCESS
                : assigned + skipped > 0 ? BulkMembershipStatus.PARTIAL : BulkMembershipStatus.FAILED;

        List<MembershipWarning> warnings = new ArrayList<>();
        for (String memberUid : memberUids) {
            int total = initialMemberships.get(memberUid) + assignedByMember.get(memberUid);
            warningsFor(total).stream().findFirst()
                    .ifPresent(message -> warnings.add(new MembershipWarning(memberUid, total, message)));
        }

        return new BulkMembershipResponse(status, results.size(), assigned, skipped, failed,
                List.copyOf(results), List.copyOf(warnings));
    }

    public MembershipChangeResponse removeMember(PanelAuthorization.Delegate actor, String module,
                                                String groupName, String memberUid) {
        support.assertModule(module);
        LdapName groupDn = support.groupDn(module, groupName);
        support.requireContext(groupDn);

        if (DELEGADOS.equalsIgnoreCase(groupName) && !actor.global()) {
            // Solo el delegado normal está obligado a no dejar el grupo vacío;
            // el admin global puede dejar 'delegados' sin integrantes.
            ensureDelegadosSurvivesRemoval(module, memberUid);
        }

        try {
            ldap.modifyAttributes(groupDn, new ModificationItem[]{
                    removeValue("member", support.absPersonDn(module, memberUid))});
        } catch (NoSuchAttributeException | org.springframework.ldap.UncategorizedLdapException ex) {
            throw new IllegalStateException("No se pudo quitar: ¿está realmente en ese grupo?", ex);
        }
        audit.record(actor, "MEMBER_REMOVED", groupDn.toString(), "uid=" + memberUid);
        return new MembershipChangeResponse(buildGroupView(groupName, module), List.of());
    }

    private List<String> warningsFor(int totalGroups) {
        if (totalGroups >= WARN_GROUPS && totalGroups < MAX_GROUPS) {
            return List.of("La persona acumula %d grupos (aviso desde %d): revise membresías viejas antes de llegar al límite"
                    .formatted(totalGroups, WARN_GROUPS));
        }
        return List.of();
    }

    private static List<String> normalizedDistinct(List<String> values, String emptyMessage) {
        if (values == null) {
            throw new IllegalArgumentException(emptyMessage);
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(emptyMessage);
        }
        return List.copyOf(normalized);
    }

    private static Set<String> normalizedMemberDns(String[] memberDns) {
        Set<String> normalized = new LinkedHashSet<>();
        if (memberDns != null) {
            for (String memberDn : memberDns) {
                normalized.add(memberDnKey(memberDn));
            }
        }
        return normalized;
    }

    private static String memberDnKey(String memberDn) {
        return memberDn.toLowerCase(Locale.ROOT);
    }

    private static int count(List<MembershipOperationResult> results, MembershipOperationStatus status) {
        return (int) results.stream().filter(result -> result.status() == status).count();
    }

    private void ensureDelegadosSurvivesRemoval(String module, String leavingUid) {
        GroupView delegados = buildGroupView(DELEGADOS, module);
        long remainingHumans = delegados.members().stream()
                .filter(m -> !m.equalsIgnoreCase(leavingUid))
                .count();
        if (remainingHumans == 0) {
            throw new IllegalStateException(
                    "No puede dejar 'delegados' sin integrantes: sume a otro delegado primero");
        }
    }

    private GroupView buildGroupView(String name, String module) {
        DirContextOperations ctx = support.requireContext(support.groupDn(module, name));
        List<String> members = new ArrayList<>();
        String[] dns = ctx.getStringAttributes("member");
        if (dns != null) {
            for (String dn : dns) {
                if (PLACEHOLDER_DN.equalsIgnoreCase(dn)) continue;
                members.add(firstRdnValue(dn));
            }
        }
        return new GroupView(name, members.stream().sorted().toList(), DELEGADOS.equalsIgnoreCase(name));
    }
}
