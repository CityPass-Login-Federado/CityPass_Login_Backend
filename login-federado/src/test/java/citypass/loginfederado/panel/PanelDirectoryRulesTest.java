package citypass.loginfederado.panel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reglas de datos del panel (manual §5.2 / decisiones D3, D5, D6): validadas
 * acá ANTES de tocar LDAP, con el directorio como última línea de defensa.
 */
class PanelDirectoryRulesTest {

    // ---- D6: nombres de grupo ----

    @Test
    void validGroupNames() {
        for (String name : List.of("delegados", "soporte-n2", "a", "analitica-lectura-2024")) {
            assertThat(PanelDirectoryRules.isValidGroupName(name))
                    .as(name).isTrue();
        }
    }

    @Test
    void invalidGroupNamesAreRejectedByRegex() {
        for (String name : List.of(
                "Soporte",          // mayúsculas
                "soporte n2",       // espacio
                "-empieza",         // guion inicial
                "termina-",         // guion final
                "do--guiones",      // guion doble
                "número",           // no ascii
                "",                 // vacío
                "con_underscore")) {
            assertThat(PanelDirectoryRules.isValidGroupName(name))
                    .as(name).isFalse();
        }
    }

    // ---- Usernames ----

    @Test
    void validUsernames() {
        for (String u : List.of("jperez", "m.gomez", "user_1", "abc", "a.b-c_d9")) {
            assertThat(PanelDirectoryRules.USERNAME.matcher(u).matches())
                    .as(u).isTrue();
        }
    }

    @Test
    void invalidUsernames() {
        for (String u : List.of("", "x", ".punto", "-guion", "tiene espacio", "MAYUS", "a".repeat(33))) {
            assertThat(PanelDirectoryRules.USERNAME.matcher(u).matches())
                    .as(u).isFalse();
        }
    }

    // ---- D5: límites de grupos ----

    @Test
    void groupLimitsMatchTheSpec() {
        assertThat(PanelDirectoryRules.MAX_GROUPS).isEqualTo(50);
        assertThat(PanelDirectoryRules.WARN_GROUPS).isEqualTo(30);
    }

    // ---- Módulos fijos del árbol ----

    @Test
    void modulesMatchTheSeedTree() {
        assertThat(PanelDirectoryRules.MODULES).containsExactlyInAnyOrder(
                "movilidad", "residuos", "reclamos", "emergencias", "espacios", "analitica");
    }

    // ---- Mail válido (misma semántica que el regex anterior, lineal) ----

    @Test
    void validEmails() {
        for (String mail : List.of("jperez@citypass.local", "a.b@x.co", "u@a.b.c")) {
            assertThat(PanelDirectoryRules.isValidEmail(mail))
                    .as(mail).isTrue();
        }
    }

    @Test
    void invalidEmails() {
        assertThat(PanelDirectoryRules.isValidEmail(null)).isFalse();
        for (String mail : List.of(
                "",                 // vacío
                "not-an-email",     // sin @
                "a@b",              // dominio sin punto
                "a@b.",             // punto al final
                "a@.b",             // punto al inicio del dominio
                "@b.c",             // local vacío
                "a@@b.c",           // doble @
                "a b@c.d",          // espacio en local
                "a@b c.d")) {       // espacio en dominio
            assertThat(PanelDirectoryRules.isValidEmail(mail))
                    .as(mail).isFalse();
        }
    }

    // ---- Escapado RFC 4515 de filtros (inyección LDAP) ----

    @Test
    void filterValuesAreEscaped() {
        assertThat(PanelDirectoryRules.escapeFilter("a*b"))
                .isEqualTo("a\\2ab");
        assertThat(PanelDirectoryRules.escapeFilter("(uid=x)"))
                .isEqualTo("\\28uid=x\\29");
        assertThat(PanelDirectoryRules.escapeFilter("back\\slash"))
                .isEqualTo("back\\5cslash");
    }
}
