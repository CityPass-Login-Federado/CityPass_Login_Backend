package citypass.loginfederado.panel;

import citypass.loginfederado.panel.repository.PanelAuditRepository;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class PanelAuditServiceTest {
    @Test
    void persistsAuditEntry() {
        var repo = mock(PanelAuditRepository.class);
        var service = new PanelAuditService(repo);
        var actor = new PanelAuthorization.Delegate("U1", "admin", "reclamos");
        service.record(actor, "GROUP_CREATED", "cn=ops", "detail");
        verify(repo).save(any());
    }

    @Test
    void auditFailureDoesNotBreakBusinessOperation() {
        var repo = mock(PanelAuditRepository.class);
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        var service = new PanelAuditService(repo);
        service.record(new PanelAuthorization.Delegate("U1", "admin", "reclamos"), "X", "target", null);
        verify(repo).save(any());
    }
}
