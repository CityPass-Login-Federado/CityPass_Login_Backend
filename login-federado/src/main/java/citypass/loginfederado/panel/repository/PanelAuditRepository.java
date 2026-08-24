package citypass.loginfederado.panel.repository;

import citypass.loginfederado.panel.model.PanelAuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PanelAuditRepository extends JpaRepository<PanelAuditEntry, UUID> {
}
