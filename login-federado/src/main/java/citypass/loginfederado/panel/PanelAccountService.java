package citypass.loginfederado.panel;

import org.springframework.ldap.core.LdapTemplate;
import org.springframework.stereotype.Service;

import javax.naming.directory.BasicAttribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;

import static citypass.loginfederado.panel.PanelLdapSupport.LOCKED_FOREVER;
import static citypass.loginfederado.panel.PanelLdapSupport.replace;

/**
 * Ciclo de vida de la cuenta del panel: baja, rehabilitación y reset de
 * contraseña iniciado por un delegado. La cuenta panel-writer y la mecánica
 * LDAP viven en {@link PanelLdapSupport}; la auditoría, acá.
 */
@Service
public class PanelAccountService {

    private final PanelLdapSupport support;
    private final LdapTemplate ldap;
    private final PanelAuditService audit;

    public PanelAccountService(PanelLdapSupport support, PanelAuditService audit) {
        this.support = support;
        this.ldap = support.ldap();
        this.audit = audit;
    }

    /**
     * Baja (D7): NUNCA borra la ficha. Bloqueo permanente vía ppolicy — el
     * propio servidor LDAP rechaza el bind aunque nuestro código se olvide
     * de chequear. La auditoría conserva el historial de siete módulos.
     */
    public void disablePerson(PanelAuthorization.Delegate actor, String module, String uid) {
        support.assertModule(module);
        support.requireContext(support.personDn(module, uid)); // debe existir
        try {
            ldap.modifyAttributes(support.personDn(module, uid), new ModificationItem[]{
                    replace("pwdAccountLockedTime", LOCKED_FOREVER)});
        } catch (org.springframework.ldap.UncategorizedLdapException ex) {
            throw new IllegalStateException("No se pudo deshabilitar la cuenta", ex);
        }
        audit.record(actor, "PERSON_DISABLED", support.absPersonDn(module, uid), null);
    }

    /** Rehabilitar: quitar el atributo. Recupera identidad, historial y grupos. */
    public void enablePerson(PanelAuthorization.Delegate actor, String module, String uid) {
        support.assertModule(module);
        support.requireContext(support.personDn(module, uid));
        try {
            ldap.modifyAttributes(support.personDn(module, uid), new ModificationItem[]{
                    new ModificationItem(DirContext.REMOVE_ATTRIBUTE,
                            new BasicAttribute("pwdAccountLockedTime"))});
        } catch (org.springframework.ldap.UncategorizedLdapException ex) {
            throw new IllegalStateException("No se pudo rehabilitar la cuenta", ex);
        }
        audit.record(actor, "PERSON_ENABLED", support.absPersonDn(module, uid), null);
    }

    /**
     * Reset de contraseña: texto plano hacia el servidor; ppolicy lo hashea
     * antes de guardar (olcPPolicyHashCleartext). Nadie ve nunca un hash.
     */
    public void resetPassword(PanelAuthorization.Delegate actor, String module,
                            String uid, String temporaryPassword) {
        support.assertModule(module);
        if (temporaryPassword == null || temporaryPassword.length() < 8) {
            throw new IllegalArgumentException("La contraseña temporal debe tener al menos 8 caracteres");
        }
        support.requireContext(support.personDn(module, uid));
        ldap.modifyAttributes(support.personDn(module, uid), new ModificationItem[]{
                replace("userPassword", temporaryPassword)});
        audit.record(actor, "PASSWORD_RESET", support.absPersonDn(module, uid), null);
    }

    /**
     * Escritura de contraseña del flujo SELF-SERVICE (recupero con token /
     * cambio desde el perfil). No audita ni exige delegado: la identidad ya
     * fue validada antes — por token de un solo uso o por bind con la clave
     * actual. Usa la misma cuenta panel-writer; ppolicy hashea antes de
     * guardar.
     */
    public void setPassword(String module, String uid, String newPassword) {
        support.assertModule(module);
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("La contraseña debe tener al menos 8 caracteres");
        }
        support.requireContext(support.personDn(module, uid));
        ldap.modifyAttributes(support.personDn(module, uid), new ModificationItem[]{
                replace("userPassword", newPassword)});
    }
}
