package citypass.loginfederado.identity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Reglas puras del mapeo LDAP→token: memberOf operacional y módulo por OU. */
class LdapDirectoryTest {

    @Test
    void memberOfReducesToBareNames() {
        List<String> groups = LdapDirectory.reduceToBareNames(
                "cn=soporte-n2,ou=Groups,ou=Reclamos,dc=citypass,dc=local",
                "cn=delegados,ou=Groups,ou=Reclamos,dc=citypass,dc=local");

        // D2: el contrato lleva nombres pelados, jamás DNs
        assertThat(groups).containsExactly("soporte-n2", "delegados");
    }

    @Test
    void missingMemberOfMeansEmptyGroupsNotFailure() {
        // Sin grupos LDAP no devuelve el atributo (null): un grupo vacío es válido
        assertThat(LdapDirectory.reduceToBareNames(null)).isEmpty();
        assertThat(LdapDirectory.reduceToBareNames()).isEmpty();
    }

    @Test
    void moduleComesFromTheOuAfterPeople() {
        assertThat(LdapDirectory.extractModule(
                "uid=jperez,ou=People,ou=Reclamos,dc=citypass,dc=local"))
                .isEqualTo("reclamos");
        assertThat(LdapDirectory.extractModule(
                "uid=x,ou=People,ou=Movilidad,dc=citypass,dc=local"))
                .isEqualTo("movilidad");
    }

    @Test
    void serviceAccountsOutsidePeopleHaveNoModule() {
        assertThat(LdapDirectory.extractModule(
                "cn=readonly,ou=ServiceAccounts,dc=citypass,dc=local"))
                .isEmpty();
    }
}
