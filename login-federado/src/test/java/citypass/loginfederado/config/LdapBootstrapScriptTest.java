package citypass.loginfederado.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LdapBootstrapScriptTest {

    @Test
    void migratesHistoricalAdminGlobalFromAnaliticaToLogin() throws IOException {
        String script = Files.readString(Path.of("ldap/config/apply-config.sh"));

        assertThat(script)
                .contains("uid=admin-global,ou=People,ou=Analitica,dc=citypass,dc=local")
                .contains("ou=People,ou=Login,dc=citypass,dc=local")
                .contains("ldapmodrdn")
                .contains("migrado de ou=Analitica a ou=Login");
    }
}
