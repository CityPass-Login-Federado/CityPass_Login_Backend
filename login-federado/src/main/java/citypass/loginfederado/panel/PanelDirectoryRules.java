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

    /**
     * D6: solo minúsculas, números y guiones (sin guion inicial/final/doble).
     * Chequeo MANUAL en vez de regex: el patrón `^[a-z0-9]+(-[a-z0-9]+)*$`
     * tiene cuantificadores anidados (backtracking super-lineal y riesgo de
     * StackOverflow con entradas largas). Lineal, sin pila.
     */
    public static boolean isValidGroupName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        boolean prevHyphen = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '-') {
                if (i == 0 || prevHyphen) {
                    return false;
                }
                prevHyphen = true;
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                prevHyphen = false;
            } else {
                return false;
            }
        }
        return !prevHyphen;
    }

    /** Username: minúsculas/números/._-, 3–32 chars (lineal, sin anidamiento). */
    public static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,31}$");

    public static final int MAX_GROUPS = 50;   // D5: bloqueo duro (token bloat)
    public static final int WARN_GROUPS = 30;  // D5: aviso preventivo

    /** D6 aplicado: nombre de grupo inválido → 400 con mensaje amable. */
    public static void validateGroupName(String name) {
        if (!isValidGroupName(name)) {
            throw new IllegalArgumentException(
                    "Nombre de grupo inválido: solo minúsculas, números y guiones (ej. soporte-n2)");
        }
        if (name.length() > 64) {
            throw new IllegalArgumentException("Nombre de grupo demasiado largo (máx 64)");
        }
    }

    /**
     * Mail válido: un solo @, local no vacío, dominio con punto no en los
     * bordes, sin espacios. Chequeo MANUAL en vez del regex
     * `^[^@\s]+@[^@\s]+\.[^@\s]+$` (misma semántica, tiempo lineal).
     */
    public static boolean isValidEmail(String email) {
        if (email == null || email.isEmpty()) {
            return false;
        }
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@')) {
            return false;
        }
        String domain = email.substring(at + 1);
        int dot = domain.indexOf('.');
        if (dot <= 0 || dot == domain.length() - 1) {
            return false;
        }
        for (int i = 0; i < email.length(); i++) {
            if (Character.isWhitespace(email.charAt(i))) {
                return false;
            }
        }
        return true;
    }

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
