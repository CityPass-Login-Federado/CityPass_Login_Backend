package citypass.loginfederado.panel;

import citypass.loginfederado.panel.dto.GroupView;
import citypass.loginfederado.panel.dto.MembershipChangeResponse;
import citypass.loginfederado.panel.dto.NewPersonRequest;
import citypass.loginfederado.panel.dto.PersonView;
import citypass.loginfederado.panel.dto.UpdatePersonRequest;
import org.springframework.ldap.NameAlreadyBoundException;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.support.LdapUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchControls;
import javax.naming.ldap.LdapName;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Toda la ESCRITURA al directorio del backend del panel, con la cuenta
 * panel-writer y todas las reglas de spec §5.2 hechas "opciones que no
 * existen" (una regla escrita se incumple; un botón ausente, no).
 *
 * El directorio es la última línea de defensa (overlays unique/constraint/
 * ppolicy); el panel es la primera: valida antes para dar errores amables.
 */
@Service
public class PanelDirectoryService {

    /** Módulos fijos — mismas OUs que crea el seed (spec §2.2). */
    public static final List<String> MODULES =
            List.of("movilidad", "residuos", "reclamos", "emergencias", "espacios", "analitica");

    public static final String DELEGADOS = "delegados";
    static final String PLACEHOLDER_DN = "cn=empty-group-placeholder,ou=ServiceAccounts,dc=citypass,dc=local";
    static final String LOCKED_FOREVER = "000001010000Z";

    /** D6: solo minúsculas, números y guiones (sin guion inicial/final/doble). */
    public static final Pattern GROUP_NAME = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    /** Username: minúsculas/números/._-, 3–32 chars. */
    public static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,31}$");

    public static final int MAX_GROUPS = 50;   // D5: bloqueo duro (token bloat)
    public static final int WARN_GROUPS = 30;  // D5: aviso preventivo

    private final LdapTemplate ldap;
    private final PanelAuditService audit;

    public PanelDirectoryService(LdapTemplate panelLdapTemplate, PanelAuditService audit) {
        this.ldap = panelLdapTemplate;
        this.audit = audit;
    }

    // ------------------------------------------------------------------
    // Personas
    // ------------------------------------------------------------------

    public List<PersonView> listPeople(String module) {
        assertModule(module);
        return ldap.search(
                peopleBase(module),
                "(objectClass=inetOrgPerson)",
                (AttributesMapper<PersonView>) PanelDirectoryService::toView
        ).stream().sorted(java.util.Comparator.comparing(PersonView::uid)).toList();
    }

    public Optional<PersonView> findPerson(String module, String uid) {
        assertModule(module);
        try {
            DirContextOperations ctx = ldap.lookupContext(personDn(module, uid));
            return Optional.of(new PersonView(
                    ctx.getStringAttribute("employeeNumber"),
                    ctx.getStringAttribute("uid"),
                    ctx.getStringAttribute("givenName"),
                    ctx.getStringAttribute("sn"),
                    ctx.getStringAttribute("mail"),
                    isDisabled(ctx)));
        } catch (org.springframework.ldap.NameNotFoundException ex) {
            return Optional.empty();
        }
    }

    /**
     * Alta de persona con ID automático secuencial (D3): el delegado nunca lo
     * ve ni lo elige. Unicidad global pre-chequeada para error amable; el
     * overlay unique es la red de seguridad si algo corre en paralelo.
     */
    public PersonView createPerson(PanelAuthorization.Delegate actor, String module, NewPersonRequest req) {
        assertModule(module);
        validateUsername(req.username());
        if (req.email() == null || !req.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException("Email inválido");
        }
        if (req.temporaryPassword().length() < 8) {
            throw new IllegalArgumentException("La contraseña inicial debe tener al menos 8 caracteres");
        }
        if (findGlobalByUidOrMail(req.username(), req.email(), null).isPresent()) {
            throw new IllegalStateException(
                    "El username o el mail ya existen en CityPass+ — son únicos en TODO el sistema");
        }

        Attributes attrs = new BasicAttributes(true);
        addObjectClasses(attrs, "top", "person", "organizationalPerson", "inetOrgPerson");
        attrs.put("uid", req.username());
        attrs.put("cn", (req.givenName() + " " + req.sn()).trim());
        attrs.put("sn", req.sn());
        attrs.put("givenName", req.givenName());
        attrs.put("mail", req.email());
        // Sin esquema {SSHA}: ppolicy (olcPPolicyHashCleartext) hashea en el servidor.
        attrs.put("userPassword", req.temporaryPassword());

        int maxAttempts = 5;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            String employeeNumber = nextEmployeeNumber(attempt);
            attrs.put("employeeNumber", employeeNumber);
            try {
                ldap.bind(personDn(module, req.username()), null, attrs);
            } catch (org.springframework.ldap.UncategorizedLdapException ex) {
                // Carrera por el número: el overlay unique rechazó → reintento.
                if (attempt == maxAttempts - 1) {
                    throw new IllegalStateException("No se pudo asignar identificador único, reintente", ex);
                }
                continue;
            }
            audit.record(actor, "PERSON_CREATED",
                    absPersonDn(module, req.username()), "employeeNumber=" + employeeNumber);
            return new PersonView(employeeNumber, req.username(), req.givenName(), req.sn(),
                    req.email(), false);
        }
        throw new IllegalStateException("No se pudo crear la persona");
    }

    /** Corrección de datos y/o renombre (con reparación de membresías). */
    public PersonView updatePerson(PanelAuthorization.Delegate actor, String module,
                                String uid, UpdatePersonRequest req) {
        assertModule(module);
        requireContext(personDn(module, uid));

        List<ModificationItem> mods = new ArrayList<>();
        String givenName = blankToNull(req.givenName());
        String sn = blankToNull(req.sn());

        if (sn != null) mods.add(replace("sn", sn));
        if (givenName != null) mods.add(replace("givenName", givenName));
        if (givenName != null || sn != null) {
            var current = findPerson(module, uid).orElseThrow();
            String newGiven = givenName != null ? givenName : current.givenName();
            String newSn = sn != null ? sn : current.sn();
            mods.add(replace("cn", (newGiven + " " + newSn).trim()));
        }

        if (req.email() != null && !req.email().isBlank()) {
            if (!req.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw new IllegalArgumentException("Email inválido");
            }
            if (findGlobalByUidOrMail(null, req.email(), uid).isPresent()) {
                throw new IllegalStateException("Ese mail ya pertenece a otra persona");
            }
            mods.add(replace("mail", req.email()));
        }

        if (!mods.isEmpty()) {
            ldap.modifyAttributes(personDn(module, uid), mods.toArray(ModificationItem[]::new));
        }

        if (req.newUsername() != null && !req.newUsername().isBlank()
                && !req.newUsername().equalsIgnoreCase(uid)) {
            renamePerson(actor, module, uid, req.newUsername().toLowerCase(Locale.ROOT));
            uid = req.newUsername().toLowerCase(Locale.ROOT);
        } else if (!mods.isEmpty()) {
            audit.record(actor, "PERSON_UPDATED", absPersonDn(module, uid),
                    "campos actualizados");
        }
        return findPerson(module, uid).orElseThrow(() -> new IllegalStateException("Persona desapareció tras actualizar"));
    }

    /**
     * Renombre con reparación de membresías: los `member` de los grupos
     * apuntan al DN viejo; después del modrdn se reescriben explícitamente.
     */
    private void renamePerson(PanelAuthorization.Delegate actor, String module,
                            String oldUid, String newUid) {
        validateUsername(newUid);
        LdapName oldDn = personDn(module, oldUid);
        LdapName newDn = personDn(module, newUid);

        if (findGlobalByUidOrMail(newUid, null, oldUid).isPresent()) {
            throw new IllegalStateException("El username ya existe en CityPass+");
        }

        Set<String> groupsBefore = groupCnsContaining(absPersonDn(module, oldUid), module);
        ldap.rename(oldDn, newDn);
        for (String cn : groupsBefore) {
            ldap.modifyAttributes(groupDn(module, cn), new ModificationItem[]{
                    removeValue("member", absPersonDn(module, oldUid)),
                    addValue("member", absPersonDn(module, newUid))
            });
        }
        audit.record(actor, "PERSON_RENAMED", absPersonDn(module, newUid), "antes=" + oldUid);
    }

    /**
     * Baja (D7): NUNCA borra la ficha. Bloqueo permanente vía ppolicy — el
     * propio servidor LDAP rechaza el bind aunque nuestro código se olvide
     * de chequear. La auditoría conserva el historial de siete módulos.
     */
    public void disablePerson(PanelAuthorization.Delegate actor, String module, String uid) {
        assertModule(module);
        requireContext(personDn(module, uid)); // debe existir
        ldap.modifyAttributes(personDn(module, uid), new ModificationItem[]{
                replace("pwdAccountLockedTime", LOCKED_FOREVER)});
        audit.record(actor, "PERSON_DISABLED", absPersonDn(module, uid), null);
    }

    /** Rehabilitar: quitar el atributo. Recupera identidad, historial y grupos. */
    public void enablePerson(PanelAuthorization.Delegate actor, String module, String uid) {
        assertModule(module);
        requireContext(personDn(module, uid));
        ldap.modifyAttributes(personDn(module, uid), new ModificationItem[]{
                new ModificationItem(DirContext.REMOVE_ATTRIBUTE,
                        new BasicAttribute("pwdAccountLockedTime"))});
        audit.record(actor, "PERSON_ENABLED", absPersonDn(module, uid), null);
    }

    /**
     * Reset de contraseña: texto plano hacia el servidor; ppolicy lo hashea
     * antes de guardar (olcPPolicyHashCleartext). Nadie ve nunca un hash.
     */
    public void resetPassword(PanelAuthorization.Delegate actor, String module,
                            String uid, String temporaryPassword) {
        assertModule(module);
        if (temporaryPassword == null || temporaryPassword.length() < 8) {
            throw new IllegalArgumentException("La contraseña temporal debe tener al menos 8 caracteres");
        }
        requireContext(personDn(module, uid));
        ldap.modifyAttributes(personDn(module, uid), new ModificationItem[]{
                replace("userPassword", temporaryPassword)});
        audit.record(actor, "PASSWORD_RESET", absPersonDn(module, uid), null);
    }

    // ------------------------------------------------------------------
    // Grupos
    // ------------------------------------------------------------------

    public List<GroupView> listGroups(String module) {
        assertModule(module);
        return ldap.search(groupsBase(module), "(objectClass=groupOfNames)",
                        (AttributesMapper<GroupView>) PanelDirectoryService::toGroupView)
                .stream().sorted(java.util.Comparator.comparing(GroupView::name)).toList();
    }

    /** Alta con placeholder como miembro técnico: ningún grupo nace vacío. */
    public GroupView createGroup(PanelAuthorization.Delegate actor, String module, String name) {
        assertModule(module);
        validateGroupName(name);
        if (DELEGADOS.equals(name)) {
            throw new IllegalStateException("El grupo '" + DELEGADOS + "' es reservado y ya existe en su módulo");
        }
        LdapName dn = groupDn(module, name);
        try {
            Attributes attrs = new BasicAttributes(true);
            addObjectClasses(attrs, "top", "groupOfNames");
            attrs.put("cn", name);
            attrs.put("member", PLACEHOLDER_DN);
            ldap.bind(dn, null, attrs);
        } catch (NameAlreadyBoundException ex) {
            throw new IllegalStateException("Ya existe un grupo con ese nombre en su módulo");
        }
        audit.record(actor, "GROUP_CREATED", dn.toString(), null);
        return buildGroupView(name, module);
    }

    /** Baja física del grupo. delegados NO se borra jamás (spec §7 del manual). */
    public void deleteGroup(PanelAuthorization.Delegate actor, String module, String name) {
        assertModule(module);
        if (DELEGADOS.equalsIgnoreCase(name)) {
            throw new IllegalStateException("El grupo '" + DELEGADOS + "' no se puede borrar");
        }
        LdapName dn = groupDn(module, name);
        requireContext(dn);
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
        assertModule(module);
        LdapName groupDn = groupDn(module, groupName);
        requireContext(groupDn);

        PersonView person = findPerson(module, memberUid)
                .orElseThrow(() -> new IllegalStateException("No existe esa persona en su módulo"));

        int before = membershipCount(memberUid);
        if (before >= MAX_GROUPS) {
            throw new IllegalStateException("Máximo %d grupos por persona (tiene %d)".formatted(MAX_GROUPS, before));
        }

        try {
            ldap.modifyAttributes(groupDn, new ModificationItem[]{
                    addValue("member", absPersonDn(module, memberUid))});
        } catch (org.springframework.ldap.UncategorizedLdapException ex) {
            throw new IllegalStateException("No se pudo agregar: ¿ya es miembro del grupo?", ex);
        }

        int after = membershipCount(memberUid);
        audit.record(actor, "MEMBER_ADDED", groupDn.toString(), "uid=" + memberUid);
        return new MembershipChangeResponse(buildGroupView(groupName, module), warningsFor(after));
    }

    public MembershipChangeResponse removeMember(PanelAuthorization.Delegate actor, String module,
                                                String groupName, String memberUid) {
        assertModule(module);
        LdapName groupDn = groupDn(module, groupName);
        requireContext(groupDn);

        if (DELEGADOS.equalsIgnoreCase(groupName)) {
            ensureDelegadosSurvivesRemoval(module, memberUid);
        }

        try {
            ldap.modifyAttributes(groupDn, new ModificationItem[]{
                    removeValue("member", absPersonDn(module, memberUid))});
        } catch (org.springframework.ldap.UncategorizedLdapException ex) {
            throw new IllegalStateException("No se pudo quitar: ¿está realmente en ese grupo?", ex);
        }
        audit.record(actor, "MEMBER_REMOVED", groupDn.toString(), "uid=" + memberUid);
        return new MembershipChangeResponse(buildGroupView(groupName, module), List.of());
    }

    // ------------------------------------------------------------------
    // Helpers internos
    // ------------------------------------------------------------------

    private List<String> warningsFor(int totalGroups) {
        if (totalGroups >= WARN_GROUPS && totalGroups < MAX_GROUPS) {
            return List.of("La persona acumula %d grupos (aviso desde %d): revise membresías viejas antes de llegar al límite"
                    .formatted(totalGroups, WARN_GROUPS));
        }
        return List.of();
    }

    private void validateGroupName(String name) {
        if (name == null || !GROUP_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Nombre de grupo inválido: solo minúsculas, números y guiones (ej. soporte-n2)");
        }
        if (name.length() > 64) {
            throw new IllegalArgumentException("Nombre de grupo demasiado largo (máx 64)");
        }
    }

    private void validateUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException(
                    "Nombre de usuario inválido: use 3-32 caracteres de a-z, 0-9, punto, guion o guion bajo");
        }
    }

    /** El delegado SOLO opera su módulo: cualquier otro scope se niega (403). */
    private void assertModule(String module) {
        if (module == null || !MODULES.contains(module.toLowerCase(Locale.ROOT))) {
            throw new AccessDeniedException("Módulo fuera de su alcance");
        }
    }

    /** Máximo global escaneado + offset del intento (reintento ante carrera). */
    private String nextEmployeeNumber(int attemptOffset) {
        int max = 0;
        for (String m : MODULES) {
            List<Integer> numbers = ldap.search(peopleBase(m),
                    "(&(objectClass=inetOrgPerson)(employeeNumber=U*))",
                    (AttributesMapper<Integer>) attrs -> {
                        String n = attrValue(attrs, "employeeNumber");
                        return n != null && n.matches("U\\d{6}") ? Integer.parseInt(n.substring(1)) : 0;
                    });
            for (int n : numbers) max = Math.max(max, n);
        }
        return "U%06d".formatted(max + 1 + attemptOffset);
    }

    /**
     * Colisión GLOBAL de username o mail (unicidad transversal, spec §2.5).
     * excludeUid permite ignorar a la propia persona (chequeo de renombre o
     * de mail sobre sí misma). Devuelve el uid del ocupante, si lo hay.
     */
    private Optional<String> findGlobalByUidOrMail(String uid, String mail, String excludeUid) {
        StringBuilder or = new StringBuilder();
        if (uid != null && !uid.isBlank()) {
            or.append("(uid=").append(escapeFilter(uid)).append(")");
        }
        if (mail != null && !mail.isBlank()) {
            or.append("(mail=").append(escapeFilter(mail)).append(")");
        }
        if (or.length() == 0) {
            return Optional.empty();
        }
        String filter = "(&(objectClass=inetOrgPerson)(|" + or + "))";

        List<String> uids = ldap.search(LdapUtils.emptyLdapName(), filter,
                (AttributesMapper<String>) attrs -> attrValue(attrs, "uid"));
        return uids.stream()
                .filter(found -> found != null && !found.equalsIgnoreCase(excludeUid))
                .findFirst();
    }

    /** Grupos del módulo cuyo `member` incluye exactamente ese DN. */
    private Set<String> groupCnsContaining(String memberDn, String module) {
        String filter = "(member=" + escapeFilter(memberDn) + ")";
        return new LinkedHashSet<>(ldap.search(groupsBase(module), filter,
                (AttributesMapper<String>) attrs -> attrValue(attrs, "cn")));
    }

    /**
     * Cantidad de grupos de una persona leyendo SU atributo operacional
     * memberOf (pedido explícitamente — trampa clásica, spec §2.7).
     */
    private int membershipCount(String uid) {
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
        DirContextOperations ctx = requireContext(groupDn(module, name));
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

    private static GroupView toGroupView(Attributes attrs) {
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

    private static PersonView toView(Attributes attrs) {
        String locked = attrValue(attrs, "pwdAccountLockedTime");
        return new PersonView(
                attrValue(attrs, "employeeNumber"),
                attrValue(attrs, "uid"),
                attrValue(attrs, "givenName"),
                attrValue(attrs, "sn"),
                attrValue(attrs, "mail"),
                locked != null && !locked.isBlank());
    }

    private static boolean isDisabled(DirContextOperations ctx) {
        String locked = ctx.getStringAttribute("pwdAccountLockedTime");
        return locked != null && !locked.isBlank();
    }

    private DirContextOperations requireContext(LdapName dn) {
        try {
            return ldap.lookupContext(dn);
        } catch (org.springframework.ldap.NameNotFoundException ex) {
            throw new IllegalStateException("No existe en su módulo");
        }
    }

    private LdapName peopleBase(String module) {
        // RELATIVO al base del ContextSource y en orden padre->hijo.
        // (LdapNameBuilder invertía el orden y producía DNs inexistentes.)
        return dnOf("ou=People,ou=" + capitalize(module));
    }

    private LdapName groupsBase(String module) {
        return dnOf("ou=Groups,ou=" + capitalize(module));
    }

    /** Nombre de entrada RELATIVO al base: así resuelven las operaciones. */
    LdapName personDn(String module, String uid) {
        return dnOf("uid=" + uid + ",ou=People,ou=" + capitalize(module));
    }

    /**
     * DN ABSOLUTO: los VALORES de atributos como `member` siempre son DNs
     * completos, independientemente del base de la conexión.
     */
    private String absPersonDn(String module, String uid) {
        return "uid=" + uid + ",ou=People,ou=" + capitalize(module) + ",dc=citypass,dc=local";
    }

    private LdapName groupDn(String module, String cn) {
        return dnOf("cn=" + cn + ",ou=Groups,ou=" + capitalize(module));
    }

    private static LdapName dnOf(String dn) {
        try {
            return new LdapName(dn);
        } catch (javax.naming.InvalidNameException ex) {
            throw new IllegalStateException("DN inválido: " + dn, ex);
        }
    }

    private static String capitalize(String module) {
        char first = Character.toUpperCase(module.charAt(0));
        return first + module.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ModificationItem replace(String attr, String value) {
        return new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute(attr, value));
    }

    private static ModificationItem addValue(String attr, String value) {
        return new ModificationItem(DirContext.ADD_ATTRIBUTE, new BasicAttribute(attr, value));
    }

    private static ModificationItem removeValue(String attr, String value) {
        return new ModificationItem(DirContext.REMOVE_ATTRIBUTE, new BasicAttribute(attr, value));
    }

    private static void addObjectClasses(Attributes attrs, String... classes) {
        BasicAttribute oc = new BasicAttribute("objectClass");
        for (String c : classes) oc.add(c);
        attrs.put(oc);
    }

    private static String attrValue(Attributes attrs, String name) {
        try {
            Attribute attr = attrs.get(name);
            return attr != null && attr.size() > 0 ? String.valueOf(attr.get(0)) : null;
        } catch (javax.naming.NamingException e) {
            return null;
        }
    }

    private static String required(Attributes attrs, String name) throws javax.naming.NamingException {
        Attribute attr = attrs.get(name);
        if (attr == null || attr.size() == 0) {
            throw new IllegalStateException("Atributo esperado ausente: " + name);
        }
        return String.valueOf(attr.get(0));
    }

    private static String firstRdnValue(String dn) {
        int end = dn.indexOf(',');
        String rdn = end > 0 ? dn.substring(0, end) : dn;
        int eq = rdn.indexOf('=');
        return eq > 0 ? rdn.substring(eq + 1) : rdn;
    }

    /** Escapado RFC 4515 para filtros LDAP — nunca concatenar crudo. */
    static String escapeFilter(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\5c");
                case '*' -> sb.append("\\2a");
                case '(' -> sb.append("\\28");
                case ')' -> sb.append("\\29");
                case '\u0000' -> sb.append("\\00");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
