package citypass.loginfederado.panel.repository;

import citypass.loginfederado.panel.model.PanelAuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface PanelAuditRepository extends JpaRepository<PanelAuditEntry, UUID>,
        JpaSpecificationExecutor<PanelAuditEntry> {
}
