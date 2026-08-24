package citypass.loginfederado.panel.dto;

/**
 * Vista de persona para el panel. `disabled` sale del atributo
 * pwdAccountLockedTime (D7): no hay flag propio, la baja es del directorio.
 */
public record PersonView(
        String employeeNumber,
        String uid,
        String givenName,
        String sn,
        String email,
        boolean disabled
) {
}
