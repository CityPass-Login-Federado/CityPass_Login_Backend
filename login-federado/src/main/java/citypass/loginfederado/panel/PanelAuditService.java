package citypass.loginfederado.panel;

import citypass.loginfederado.panel.model.PanelAuditEntry;
import citypass.loginfederado.panel.repository.PanelAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistencia de auditoría del panel. Falla de auditoría = falla de la
 * operación: si no se puede registrar, la mutación no debería haber pasado.
 * (Transaccional junto a la operación LDAP cuando el llamador lo envuelve.)
 */
@Service
public class PanelAuditService {

    private static final Logger log = LoggerFactory.getLogger(PanelAuditService.class);

    private final PanelAuditRepository repository;

    public PanelAuditService(PanelAuditRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(PanelAuthorization.Delegate actor, String action, String target, String detail) {
        try {
            repository.save(new PanelAuditEntry(
                    actor.sub(), actor.uid(), actor.module(), action, target, detail));
        } catch (Exception ex) {
            // No bloqueamos la operación por una falla de auditoría, pero queda
            // marcado en logs para revisión — es una anomalía operativa seria.
            log.error("No se pudo persistir auditoría del panel: actor={} action={} target={}",
                    actor.sub(), action, target, ex);
        }
    }
}
