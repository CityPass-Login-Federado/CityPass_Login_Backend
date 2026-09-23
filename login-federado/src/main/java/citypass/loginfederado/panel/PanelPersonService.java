package citypass.loginfederado.panel;

import citypass.loginfederado.panel.dto.NewPersonRequest;
import citypass.loginfederado.panel.dto.PaginatedResponse;
import citypass.loginfederado.panel.dto.PeopleSearchCriteria;
import citypass.loginfederado.panel.dto.PersonView;
import citypass.loginfederado.panel.dto.UpdatePersonRequest;
import org.springframework.http.HttpStatus;
import org.springframework.ldap.AttributeInUseException;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.filter.AndFilter;
import org.springframework.ldap.filter.EqualsFilter;
import org.springframework.ldap.filter.LikeFilter;
import org.springframework.ldap.filter.OrFilter;
import org.springframework.ldap.query.LdapQuery;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.ldap.query.LdapQueryBuilder.query;
import org.springframework.stereotype.Service;

import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.ModificationItem;
import javax.naming.ldap.LdapName;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static citypass.loginfederado.panel.PanelDirectoryRules.MODULES;
import static citypass.loginfederado.panel.PanelDirectoryRules.USERNAME;
import static citypass.loginfederado.panel.PanelLdapSupport.addObjectClasses;
import static citypass.loginfederado.panel.PanelLdapSupport.attrValue;
import static citypass.loginfederado.panel.PanelLdapSupport.blankToNull;
import static citypass.loginfederado.panel.PanelLdapSupport.capitalize;
import static citypass.loginfederado.panel.PanelLdapSupport.replace;

/**
 * Personas del directorio del panel (lectura, alta, corrección, renombre).
 * Reglas de spec §5.2 hechas "opciones que no existen"; la cuenta
 * panel-writer y la mecánica LDAP viven en {@link PanelLdapSupport}.
 */
@Service
public class PanelPersonService {

    private final PanelLdapSupport support;
    private final LdapTemplate ldap;
    private final PanelAuditService audit;

    public PanelPersonService(PanelLdapSupport support, PanelAuditService audit) {
        this.support = support;
        this.ldap = support.ldap();
        this.audit = audit;
    }

    public PaginatedResponse<PersonView> listPeople(String module, PeopleSearchCriteria criteria) {
        support.assertModule(module);

        AndFilter andFilter = new AndFilter();
        andFilter.and(new EqualsFilter("objectClass", "inetOrgPerson"));

        if (criteria.search() != null && !criteria.search().isBlank()) {
            OrFilter searchFilter = new OrFilter();
            String term = "*" + criteria.search().trim() + "*";
            searchFilter.or(new LikeFilter("uid", term));
            searchFilter.or(new LikeFilter("cn", term));
            searchFilter.or(new LikeFilter("mail", term));
            andFilter.and(searchFilter);
        }

        if (criteria.group() != null && !criteria.group().isBlank()) {
            String group = criteria.group().trim().toLowerCase(Locale.ROOT);
            PanelDirectoryRules.validateGroupName(group);
            // memberOf guarda DNs COMPLETOS (spec §2.7): filtrar por el nombre
            // corto jamás matchea y devuelve listas vacías sin error. Se busca
            // por el DN absoluto del grupo dentro del módulo consultado.
            andFilter.and(new EqualsFilter("memberOf",
                    "cn=" + group + ",ou=Groups,ou=" + capitalize(module) + ",dc=citypass,dc=local"));
        }

        LdapQuery query = query()
                .base(support.peopleBase(module))
                .attributes("uid", "cn", "sn", "givenName", "mail", "employeeNumber", "pwdAccountLockedTime")
                .filter(andFilter);

        List<PersonView> allFiltered = ldap.search(
                query,
                (AttributesMapper<PersonView>) PanelLdapSupport::toView
        ).stream()
         .filter(p -> criteria.disabled() == null || p.disabled() == criteria.disabled().booleanValue())
         .sorted(java.util.Comparator.comparing(PersonView::uid))
         .toList();

        int totalElements = allFiltered.size();
        int size = criteria.size() > 0 ? criteria.size() : 10;
        int page = criteria.page() >= 0 ? criteria.page() : 0;

        int totalPages = (int) Math.ceil((double) totalElements / size);
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, totalElements);

        List<PersonView> pageContent = fromIndex < totalElements
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

    public Optional<PersonView> findPerson(String module, String uid) {
        support.assertModule(module);
        try {
            var ctx = ldap.lookupContext(support.personDn(module, uid));
            return Optional.of(new PersonView(
                    ctx.getStringAttribute("employeeNumber"),
                    ctx.getStringAttribute("uid"),
                    ctx.getStringAttribute("givenName"),
                    ctx.getStringAttribute("sn"),
                    ctx.getStringAttribute("mail"),
                    PanelLdapSupport.isDisabled(ctx)));
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
        support.assertModule(module);
        validateUsername(req.username());
        if (!PanelDirectoryRules.isValidEmail(req.email())) {
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
                ldap.bind(support.personDn(module, req.username()), null, attrs);
            } catch (AttributeInUseException | org.springframework.ldap.UncategorizedLdapException ex) {
                // Carrera por el número: el overlay unique rechazó → reintento.
                if (attempt == maxAttempts - 1) {
                    throw new IllegalStateException("No se pudo asignar identificador único, reintente", ex);
                }
                continue;
            }
            audit.record(actor, "PERSON_CREATED",
                    support.absPersonDn(module, req.username()), "employeeNumber=" + employeeNumber);
            return new PersonView(employeeNumber, req.username(), req.givenName(), req.sn(),
                    req.email(), false);
        }
        throw new IllegalStateException("No se pudo crear la persona");
    }

    /** Corrección de datos y/o renombre (con reparación de membresías). */
    public PersonView updatePerson(PanelAuthorization.Delegate actor, String module,
                                String uid, UpdatePersonRequest req) {
        support.assertModule(module);
        support.requireContext(support.personDn(module, uid));

        List<ModificationItem> mods = new ArrayList<>();
        String givenName = blankToNull(req.givenName());
        String sn = blankToNull(req.sn());

        if (sn != null) mods.add(replace("sn", sn));
        if (givenName != null) mods.add(replace("givenName", givenName));
        if (givenName != null || sn != null) {
            var current = findPerson(module, uid)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "No existe esa persona en su módulo"));
            String newGiven = givenName != null ? givenName : current.givenName();
            String newSn = sn != null ? sn : current.sn();
            mods.add(replace("cn", (newGiven + " " + newSn).trim()));
        }

        if (req.email() != null && !req.email().isBlank()) {
            if (!PanelDirectoryRules.isValidEmail(req.email())) {
                throw new IllegalArgumentException("Email inválido");
            }
            if (findGlobalByUidOrMail(null, req.email(), uid).isPresent()) {
                throw new IllegalStateException("Ese mail ya pertenece a otra persona");
            }
            mods.add(replace("mail", req.email()));
        }

        if (!mods.isEmpty()) {
            ldap.modifyAttributes(support.personDn(module, uid), mods.toArray(ModificationItem[]::new));
        }

        if (req.newUsername() != null && !req.newUsername().isBlank()
                && !req.newUsername().equalsIgnoreCase(uid)) {
            renamePerson(actor, module, uid, req.newUsername().toLowerCase(Locale.ROOT));
            uid = req.newUsername().toLowerCase(Locale.ROOT);
        } else if (!mods.isEmpty()) {
            audit.record(actor, "PERSON_UPDATED", support.absPersonDn(module, uid),
                    "campos actualizados");
        }
        return findPerson(module, uid).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Persona desapareció tras actualizar"));
    }

    /**
     * Renombre con reparación de membresías: los `member` de los grupos
     * apuntan al DN viejo; después del modrdn se reescriben explícitamente
     * (mecánica en el support, auditoría acá).
     */
    private void renamePerson(PanelAuthorization.Delegate actor, String module,
                            String oldUid, String newUid) {
        validateUsername(newUid);
        LdapName oldDn = support.personDn(module, oldUid);
        LdapName newDn = support.personDn(module, newUid);

        if (findGlobalByUidOrMail(newUid, null, oldUid).isPresent()) {
            throw new IllegalStateException("El username ya existe en CityPass+");
        }

        ldap.rename(oldDn, newDn);
        support.repairMemberships(module, oldUid, newUid);
        audit.record(actor, "PERSON_RENAMED", support.absPersonDn(module, newUid), "antes=" + oldUid);
    }

    /**
     * Colisión GLOBAL de username o mail (unicidad transversal, spec §2.5).
     * excludeUid permite ignorar a la propia persona (chequeo de renombre o
     * de mail sobre sí misma). Devuelve el uid del ocupante, si lo hay.
     */
    private Optional<String> findGlobalByUidOrMail(String uid, String mail, String excludeUid) {
        StringBuilder or = new StringBuilder();
        if (uid != null && !uid.isBlank()) {
            or.append("(uid=").append(PanelDirectoryRules.escapeFilter(uid)).append(")");
        }
        if (mail != null && !mail.isBlank()) {
            or.append("(mail=").append(PanelDirectoryRules.escapeFilter(mail)).append(")");
        }
        if (or.length() == 0) {
            return Optional.empty();
        }
        String filter = "(&(objectClass=inetOrgPerson)(|" + or + "))";

        List<String> uids = ldap.search(org.springframework.ldap.support.LdapUtils.emptyLdapName(), filter,
                (AttributesMapper<String>) attrs -> PanelLdapSupport.attrValue(attrs, "uid"));
        return uids.stream()
                .filter(found -> found != null && !found.equalsIgnoreCase(excludeUid))
                .findFirst();
    }

    /** Máximo global escaneado + offset del intento (reintento ante carrera). */
    private String nextEmployeeNumber(int attemptOffset) {
        int max = 0;
        for (String m : MODULES) {
            List<Integer> numbers = ldap.search(support.peopleBase(m),
                    "(&(objectClass=inetOrgPerson)(employeeNumber=U*))",
                    (AttributesMapper<Integer>) attrs -> {
                        String n = PanelLdapSupport.attrValue(attrs, "employeeNumber");
                        return n != null && n.matches("U\\d{6}") ? Integer.parseInt(n.substring(1)) : 0;
                    });
            for (int n : numbers) max = Math.max(max, n);
        }
        return "U%06d".formatted(max + 1 + attemptOffset);
    }

    private void validateUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException(
                    "Nombre de usuario inválido: use 3-32 caracteres de a-z, 0-9, punto, guion o guion bajo");
        }
    }
}
