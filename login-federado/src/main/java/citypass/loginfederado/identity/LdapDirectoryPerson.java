package citypass.loginfederado.identity;

import java.util.List;

/**
 * La ficha de la persona, tal como el IdP la necesita para armar un token.
 * `sub` es el employeeNumber (U000042): el identificador estable que los
 * siete módulos guardan en sus bases — nunca el uid, que puede corregirse.
 *
 * `dn` viaja para poder hacer bind con EXACTAMENTE el DN encontrado en la
 * búsqueda global, sin reconstruirlo por string.
 */
public record LdapDirectoryPerson(
        String dn,
        String sub,
        String uid,
        String fullName,
        String email,
        String module,
        List<String> groups
) {
}
