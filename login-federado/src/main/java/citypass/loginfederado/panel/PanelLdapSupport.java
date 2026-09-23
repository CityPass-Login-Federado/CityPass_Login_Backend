package citypass.loginfederado.panel;

import citypass.loginfederado.panel.dto.GroupView;
import citypass.loginfederado.panel.dto.PersonView;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.ldap.support.LdapUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchControls;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static citypass.loginfederado.panel.PanelDirectoryRules.DELEGADOS;
import static citypass.loginfederado.panel.PanelDirectoryRules.MODULES;
import static citypass.loginfederado.panel.PanelDirectoryRules.escapeFilter;

/**
 * Mecánica LDAP compartida por los tres servicios del panel (personas,
 * grupos, cuenta): construcción de DNs, asserts, lecturas de bajo nivel y
 * mapeos. Sin reglas de negocio ni auditoría — eso vive en cada servicio.
 *
 * Es package-private a propósito: nadie fuera de `panel` debería operar el
 * directorio con la cuenta panel-writer.
 */
@Service
class PanelLdapSupport {

    static final String PLACEHOLDER_DN = "cn=empty-group-placeholder,ou=ServiceAccounts,dc=citypass,dc=local";
    static final String LOCKED_FOREVER = "000001010000Z";

    private final LdapTemplate ldap;

    PanelLdapSupport(LdapTemplate panelLdapTemplate) {
        this.ldap = panelLdapTemplate;
    }

    LdapTemplate ldap() {
        return ldap;
    }

    /** El delegado SOLO opera su módulo: cualquier otro scope se niega (403). */
    void assertModule(String module) {
        if (module == null || !MODULES.contains(module.toLowerCase(Locale.ROOT))) {
            throw new AccessDeniedException("Módulo fuera de su alcance");
        }
    }

    /**
     * Exige que la entrada exista. Si no está, 404 (no 409): el handler ya
     * propaga el status de ResponseStatusException, así todos los endpoints
     * devuelven el "inexistente" que documentan en su contrato.
     */
    DirContextOperations requireContext(LdapName dn) {
        try {
            return ldap.lookupContext(dn);
        } catch (org.springframework.ldap.NameNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe en su módulo");
        }
    }

    /** ¿Existe la ficha de esa persona en el módulo? (sin construir la vista). */
    boolean personExists(String module, String uid) {
        try {
            ldap.lookupContext(personDn(module, uid));
            return true;
        } catch (org.springframework.ldap.NameNotFoundException ex) {
            return false;
        }
    }

    LdapName peopleBase(String module) {
        // RELATIVO al base del ContextSource y en orden padre->hijo.
        // (LdapNameBuilder invertía el orden y producía DNs inexistentes.)
        return dnOf("ou=People,ou=" + capitalize(module));
    }

    LdapName groupsBase(String module) {
        return dnOf("ou=Groups,ou=" + capitalize(module));
    }

    /**
     * Nombre de entrada RELATIVO al base: así resuelven las operaciones.
     * El uid se escapa RFC 4514 (Rdn.escapeValue): viene de input de usuario
     * (path params, formularios) y una coma/cruz sin escapar rompería el DN
     * o cambiaría la entrada apuntada (inyección LDAP).
     */
    LdapName personDn(String module, String uid) {
        return dnOf("uid=" + Rdn.escapeValue(uid) + ",ou=People,ou=" + capitalize(module));
    }

    /**
     * DN ABSOLUTO: los VALORES de atributos como `member` siempre son DNs
     * completos, independientemente del base de la conexión.
     */
    String absPersonDn(String module, String uid) {
        return "uid=" + uid + ",ou=People,ou=" + capitalize(module) + ",dc=citypass,dc=local";
    }

    /** Igual que personDn: el cn del grupo también es input de usuario. */
    LdapName groupDn(String module, String cn) {
        return dnOf("cn=" + Rdn.escapeValue(cn) + ",ou=Groups,ou=" + capitalize(module));
    }

    static LdapName dnOf(String dn) {
        try {
            return new LdapName(dn);
        } catch (javax.naming.InvalidNameException ex) {
            throw new IllegalStateException("DN inválido: " + dn, ex);
        }
    }

    static String capitalize(String module) {
        char first = Character.toUpperCase(module.charAt(0));
        return first + module.substring(1).toLowerCase(Locale.ROOT);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static ModificationItem replace(String attr, String value) {
        return new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute(attr, value));
    }

    static ModificationItem addValue(String attr, String value) {
        return new ModificationItem(DirContext.ADD_ATTRIBUTE, new BasicAttribute(attr, value));
    }

    static ModificationItem addValues(String attr, Collection<String> values) {
        BasicAttribute attribute = new BasicAttribute(attr);
        values.forEach(attribute::add);
        return new ModificationItem(DirContext.ADD_ATTRIBUTE, attribute);
    }

    static ModificationItem removeValue(String attr, String value) {
        return new ModificationItem(DirContext.REMOVE_ATTRIBUTE, new BasicAttribute(attr, value));
    }

    static void addObjectClasses(Attributes attrs, String... classes) {
        BasicAttribute oc = new BasicAttribute("objectClass");
        for (String c : classes) oc.add(c);
        attrs.put(oc);
    }

    static String attrValue(Attributes attrs, String name) {
        try {
            Attribute attr = attrs.get(name);
            return attr != null && attr.size() > 0 ? String.valueOf(attr.get(0)) : null;
        } catch (javax.naming.NamingException e) {
            return null;
        }
    }

    static String required(Attributes attrs, String name) throws javax.naming.NamingException {
        Attribute attr = attrs.get(name);
        if (attr == null || attr.size() == 0) {
            throw new IllegalStateException("Atributo esperado ausente: " + name);
        }
        return String.valueOf(attr.get(0));
    }

    static String firstRdnValue(String dn) {
        int end = dn.indexOf(',');
        String rdn = end > 0 ? dn.substring(0, end) : dn;
        int eq = rdn.indexOf('=');
        return eq > 0 ? rdn.substring(eq + 1) : rdn;
    }

    static PersonView toView(Attributes attrs) {
        String locked = attrValue(attrs, "pwdAccountLockedTime");
        return new PersonView(
                attrValue(attrs, "employeeNumber"),
                attrValue(attrs, "uid"),
                attrValue(attrs, "givenName"),
                attrValue(attrs, "sn"),
                attrValue(attrs, "mail"),
                locked != null && !locked.isBlank());
    }

    static boolean isDisabled(DirContextOperations ctx) {
        String locked = ctx.getStringAttribute("pwdAccountLockedTime");
        return locked != null && !locked.isBlank();
    }

    static GroupView toGroupView(Attributes attrs) {
        try {
            String cn = required(attrs, "cn");
            List<String> members = new ArrayList<>();
            Attribute member = attrs.get("member");
            if (member != null) {
                for (int i = 0; i < member.size(); i++) {
                    String dn = String.valueOf(member.get(i));
                    if (PLACEHOLDER_DN.equalsIgnoreCase(dn)) continue;
                    members.add(firstRdnValue(dn));
                }
            }
            return new GroupView(cn, members.stream().sorted().toList(), DELEGADOS.equalsIgnoreCase(cn));
        } catch (javax.naming.NamingException e) {
            throw new IllegalStateException("No se pudo leer el grupo", e);
        }
    }

    /** Grupos del módulo cuyo `member` incluye exactamente ese DN. */
    Set<String> groupCnsContaining(String memberDn, String module) {
        String filter = "(member=" + escapeFilter(memberDn) + ")";
        return new LinkedHashSet<>(ldap.search(groupsBase(module), filter,
                (AttributesMapper<String>) attrs -> attrValue(attrs, "cn")));
    }

    /**
     * Cantidad de grupos de una persona leyendo SU atributo operacional
     * memberOf (pedido explícitamente — trampa clásica, spec §2.7).
     */
    int membershipCount(String uid) {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[]{"memberOf"});

        ContextMapper<Integer> counter = ctx -> {
            String[] dns = ((DirContextOperations) ctx).getStringAttributes("memberOf");
            return dns == null ? 0 : dns.length;
        };
        List<Integer> counts = ldap.search(
                LdapUtils.emptyLdapName(),
                "(&(objectClass=inetOrgPerson)(uid=" + escapeFilter(uid) + "))",
                controls,
                counter);
        return counts.stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Reparación de membresías tras un renombre: los `member` de los grupos
     * apuntan al DN viejo; se reescriben explícitamente al nuevo.
     */
    void repairMemberships(String module, String oldUid, String newUid) {
        Set<String> groupsBefore = groupCnsContaining(absPersonDn(module, oldUid), module);
        for (String cn : groupsBefore) {
            ldap.modifyAttributes(groupDn(module, cn), new ModificationItem[]{
                    removeValue("member", absPersonDn(module, oldUid)),
                    addValue("member", absPersonDn(module, newUid))
            });
        }
    }
}
