package citypass.loginfederado.identity;

import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.support.LdapUtils;
import org.springframework.stereotype.Service;

import javax.naming.NamingException;
import javax.naming.directory.SearchControls;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Acceso de LECTURA al directorio con la cuenta readonly del IdP
 * (la ACL le niega ver hashes y escribir). Dos operaciones y nada más:
 *
 * 1) findByUid — búsqueda GLOBAL por uid para el login (es único en todo
 *    el sistema por el overlay unique; si algún día hubiera dos resultados,
 *    la falla es genérica — spec §4.1 paso 3).
 * 2) reloadBySub — relectura por employeeNumber para REVALIDAR en cada
 *    canje de refresh: ¿sigue habilitada? ¿qué grupos tiene AHORA?
 *
 * TRAMPA CLÁSICA (spec §2.7): memberOf es un atributo OPERACIONAL y LDAP no
 * lo devuelve si pedís "todos los atributos". Está nombrado explícitamente
 * en SearchControls de las dos búsquedas. Si alguien lo saca: el login
 * funciona perfecto y el token sale sin grupos, sin ningún error.
 */
@Service
public class LdapDirectory {

    /**
     * DN inexistente usado SOLO para normalizar tiempos: cuando el usuario no
     * existe se ejecuta igualmente un bind fallido contra este DN, para que un
     * atacante no distinga "usuario inexistente" de "contraseña mala"
     * cronometrando (spec §4.1, pendiente declarado del PoC).
     */
    static final String TIMING_DUMMY_DN = "uid=timing-normalization,ou=People,ou=Reclamos,dc=citypass,dc=local";

    private static final String[] PERSON_ATTRIBUTES = {
            "uid", "cn", "sn", "mail", "employeeNumber",
            "memberOf", "pwdAccountLockedTime"
    };

    private final LdapTemplate ldapTemplate;

    public LdapDirectory(LdapTemplate ldapTemplate) {
        this.ldapTemplate = ldapTemplate;
    }

    /** Búsqueda global del uid en todo el árbol. Exactamente 1 o vacío. */
    public Optional<LdapDirectoryPerson> findByUid(String uid) {
        ContextMapper<LdapDirectoryPerson> mapper = ctx -> mapPerson((DirContextOperations) ctx);
        List<LdapDirectoryPerson> found = ldapTemplate.search(
                LdapUtils.emptyLdapName(),
                "(&(objectClass=inetOrgPerson)(uid=" + escapeFilterValue(uid) + "))",
                personSearchControls(),
                mapper
        );
        if (found.size() > 1) {
            // Inalcanzable mientras el overlay unique viva; si pasa, es una
            // corrupción grave y NO se revela cuál de los dos es válido.
            return Optional.empty();
        }
        return found.stream().findFirst();
    }

    /** Relectura por employeeNumber (sub) para revalidar sesiones. */
    public Optional<LdapDirectoryPerson> reloadBySub(String employeeNumber) {
        ContextMapper<LdapDirectoryPerson> mapper = ctx -> mapPerson((DirContextOperations) ctx);
        List<LdapDirectoryPerson> found = ldapTemplate.search(
                LdapUtils.emptyLdapName(),
                "(&(objectClass=inetOrgPerson)(employeeNumber=" + escapeFilterValue(employeeNumber) + "))",
                personSearchControls(),
                mapper
        );
        return found.stream().findFirst();
    }

    /**
     * Bind con el DN encontrado y la contraseña presentada, sobre una conexión
     * nueva que se usa solo para eso y se cierra. Nosotros nunca leemos ni
     * comparamos contraseñas guardadas — autentica el propio servidor.
     */
    public void bind(String dn, String password) throws NamingException {
        javax.naming.directory.DirContext ctx =
                ldapTemplate.getContextSource().getContext(dn, password);
        try {
            // Autenticación exitosa: nada que leer acá.
        } finally {
            ctx.close();
        }
    }

    /** Bind deliberadamente fallido para emparejar tiempos (ver constante). */
    public void dummyBind(String password) {
        try {
            javax.naming.directory.DirContext ignored =
                    ldapTemplate.getContextSource().getContext(TIMING_DUMMY_DN, password);
            try {
                // No debería llegar: DN inexistente.
            } finally {
                ignored.close();
            }
        } catch (org.springframework.ldap.NamingException expected) {
            // Ignorado a propósito: era exactamente el objetivo del bind falso.
            // Spring LDAP envuelve el error 49 del DN trampa en esta runtime;
            // capturar solo la checked de JNDI dejaría escapar la excepción.
        } catch (NamingException expectedChecked) {
            // Variante checked (p.ej. al cerrar el contexto).
        }
    }

    private SearchControls personSearchControls() {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(PERSON_ATTRIBUTES);
        return controls;
    }

    /** Escapado RFC 4515 para valores dentro de filtros — nunca concatenar crudo. */
    private static String escapeFilterValue(String raw) {
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

    private LdapDirectoryPerson mapPerson(DirContextOperations ctx) {
        String employeeNumber = ctx.getStringAttribute("employeeNumber");
        String lockedSince = ctx.getStringAttribute("pwdAccountLockedTime");

        // Cuenta deshabilitada (D7): pwdAccountLockedTime presente. No entra,
        // ni por login ni por refresh. Mismo tratamiento que "no existe":
        // el error que ve el cliente es idéntico.
        if (lockedSince != null && !lockedSince.isBlank()) {
            return null;
        }
        if (employeeNumber == null || employeeNumber.isBlank()) {
            return null;
        }

        // getNameInNamespace() = DN COMPLETO incluido el base del
        // ContextSource. ctx.getDn() trae el DN RELATIVO al base y con él el
        // bind del paso 5 daría siempre error 49 (DN inexistente).
        String fullDn = ctx.getNameInNamespace();
        return new LdapDirectoryPerson(
                fullDn,
                employeeNumber,
                ctx.getStringAttribute("uid"),
                ctx.getStringAttribute("cn"),
                ctx.getStringAttribute("mail"),
                extractModule(fullDn),
                reduceToBareNames(ctx.getStringAttributes("memberOf"))
        );
    }

    /**
     * memberOf trae DNs completos ("cn=soporte-n2,ou=Groups,ou=Reclamos,...").
     * El contrato lleva nombres pelados (D2): nos quedamos con el primer cn.
     */
    static List<String> reduceToBareNames(String... memberOfDns) {
        if (memberOfDns == null || memberOfDns.length == 0) {
            // OJO: sin grupos, LDAP directamente no tiene el atributo — la
            // librería devuelve null, no []. Un grupo vacío es VÁLIDO.
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String dn : memberOfDns) {
            int end = dn.indexOf(',');
            String rdn = end > 0 ? dn.substring(0, end) : dn;
            // "cn=soporte-n2" → "soporte-n2": el contrato lleva nombres pelados
            int eq = rdn.indexOf('=');
            names.add(eq >= 0 ? rdn.substring(eq + 1) : rdn);
        }
        return names;
    }

    /**
     * El claim `module` sale del árbol: la OU de módulo donde apareció la ficha
     * (uid=x,ou=People,ou=RECLAMOS,... → "reclamos"), en minúsculas.
     */
    static String extractModule(String dn) {
        String[] tokens = dn.split("(?<!\\\\),");
        for (int i = 0; i < tokens.length - 1; i++) {
            if ("ou=People".equalsIgnoreCase(tokens[i].trim())) {
                String next = tokens[i + 1].trim();
                if (next.toLowerCase().startsWith("ou=")) {
                    return next.substring(3).toLowerCase();
                }
            }
        }
        return "";
    }

}
