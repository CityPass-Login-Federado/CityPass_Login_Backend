package citypass.loginfederado.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaSqlTest {

    @Test
    void loginLockoutsDefinitionIsIdempotentAndNonDestructive() throws IOException {
        try (InputStream resource = SchemaSqlTest.class.getResourceAsStream("/schema.sql")) {
            assertThat(resource).as("schema.sql debe existir").isNotNull();
            String schema = new String(resource.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(schema)
                    .doesNotContainIgnoringCase("DROP TABLE IF EXISTS login_lockouts")
                    .containsIgnoringCase("CREATE TABLE IF NOT EXISTS login_lockouts (")
                    .contains("username VARCHAR(255) PRIMARY KEY")
                    .contains("locked_until TIMESTAMP NOT NULL")
                    .containsIgnoringCase("CREATE TABLE IF NOT EXISTS revoked_access_tokens (")
                    .containsIgnoringCase("CREATE TABLE IF NOT EXISTS revoked_subject_cutoffs (");
        }
    }
}
