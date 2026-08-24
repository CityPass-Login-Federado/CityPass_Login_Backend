package citypass.loginfederado.panel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reglas de datos del panel (manual §5.2 / decisiones D3, D5, D6): validadas
 * acá ANTES de tocar LDAP, con el directorio como última línea de defensa.
 */
class PanelDirectoryServiceRulesTest {

    // ---- D6: nombres de grupo ----

    @Test
    void validGroupNames() {
        for (String name : List.of("delegados", "soporte-n2", "a", "analitica-lectura-2024")) {
            assertThat(PanelDirectoryService.GROUP_NAME.matcher(name).matches())
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
            assertThat(PanelDirectoryService.GROUP_NAME.matcher(name).matches())
                    .as(name).isFalse();
        }
    }

    // ---- Usernames ----

    @Test
    void validUsernames() {
        for (String u : List.of("jperez", "m.gomez", "user_1", "abc", "a.b-c_d9")) {
            assertThat(PanelDirectoryService.USERNAME.matcher(u).matches())
                    .as(u).isTrue();
        }
    }

    @Test
    void invalidUsernames() {
        for (String u : List.of("", "x", ".punto", "-guion", "tiene espacio", "MAYUS", "a".repeat(33))) {
            assertThat(PanelDirectoryService.USERNAME.matcher(u).matches())
                    .as(u).isFalse();
        }
    }

    // ---- D5: límites de grupos ----

    @Test
    void groupLimitsMatchTheSpec() {
        assertThat(PanelDirectoryService.MAX_GROUPS).isEqualTo(50);
        assertThat(PanelDirectoryService.WARN_GROUPS).isEqualTo(30);
    }

    // ---- Módulos fijos del árbol ----

    @Test
    void modulesMatchTheSeedTree() {
        assertThat(PanelDirectoryService.MODULES).containsExactlyInAnyOrder(
                "movilidad", "residuos", "reclamos", "emergencias", "espacios", "analitica");
    }

    // ---- Escapado RFC 4515 de filtros (inyección LDAP) ----

    @Test
    void filterValuesAreEscaped() {
        assertThat(PanelDirectoryService.escapeFilter("a*b"))
                .isEqualTo("a\\2ab");
        assertThat(PanelDirectoryService.escapeFilter("(uid=x)"))
                .isEqualTo("\\28uid=x\\29");
        assertThat(PanelDirectoryService.escapeFilter("back\\slash"))
                .isEqualTo("back\\5cslash");
    }
}
