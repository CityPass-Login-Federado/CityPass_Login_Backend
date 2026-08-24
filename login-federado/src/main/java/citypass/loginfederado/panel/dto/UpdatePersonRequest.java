package citypass.loginfederado.panel.dto;

/**
 * Corrección de datos de una persona. El employeeNumber es inmutable por
 * diseño (D3): no existe camino para cambiarlo, ni acá ni nunca.
 * newUsername dispara el renombre con reparación de membresías.
 */
public record UpdatePersonRequest(
        String givenName,
        String sn,
        String email,
        String newUsername
) {
}
