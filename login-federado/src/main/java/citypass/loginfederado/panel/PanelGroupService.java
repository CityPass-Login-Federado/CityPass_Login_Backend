package citypass.loginfederado.panel;

import citypass.loginfederado.panel.dto.GroupSearchCriteria;
import citypass.loginfederado.panel.dto.GroupView;
import citypass.loginfederado.panel.dto.MembershipChangeResponse;
import citypass.loginfederado.panel.dto.PaginatedResponse;
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
import java.util.List;

import static citypass.loginfederado.panel.PanelDirectoryRules.DELEGADOS;
import static citypass.loginfederado.panel.PanelDirectoryRules.MAX_GROUPS;
import static citypass.loginfederado.panel.PanelDirectoryRules.WARN_GROUPS;
import static citypass.loginfederado.panel.PanelDirectoryRules.validateGroupName;
import static citypass.loginfederado.panel.PanelLdapSupport.PLACEHOLDER_DN;
import static citypass.loginfederado.panel.PanelLdapSupport.addObjectClasses;
import static citypass.loginfederado.panel.PanelLdapSupport.addValue;
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
