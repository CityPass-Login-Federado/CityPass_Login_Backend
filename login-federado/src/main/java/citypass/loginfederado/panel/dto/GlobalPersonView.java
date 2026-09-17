package citypass.loginfederado.panel.dto;

public record GlobalPersonView(
        String module,
        String employeeNumber,
        String uid,
        String givenName,
        String sn,
        String email,
        boolean disabled
) {
}