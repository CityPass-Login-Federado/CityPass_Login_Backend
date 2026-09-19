package citypass.loginfederado.panel;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Reglas estáticas del directorio del panel (spec §5.2): módulos, nombres,
 * límites y escapado. Sin estado ni LDAP — puras funciones y constantes
 * compartidas por los tres servicios del panel (personas, grupos, cuenta).
 */
public final class PanelDirectoryRules {

    private PanelDirectoryRules() {
    }

    /** Módulos fijos — mismas OUs que crea el seed (spec §2.2). */
    public static final List<String> MODULES =
            List.of("movilidad", "residuos", "reclamos", "emergencias", "espacios", "analitica");

    public static final String DELEGADOS = "delegados";

    /** D6: solo minúsculas, números y guiones (sin guion inicial/final/doble). */
    public static final Pattern GROUP_NAME = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    /** Username: minúsculas/números/._-, 3–32 chars. */
    public static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,31}$");

    public static final int MAX_GROUPS = 50;   // D5: bloqueo duro (token bloat)
    public static final int WARN_GROUPS = 30;  // D5: aviso preventivo

    /** Escapado RFC 4515 para filtros LDAP — nunca concatenar crudo. */
    public static String escapeFilter(String raw) {
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
