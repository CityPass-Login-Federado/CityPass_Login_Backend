package citypass.loginfederado.repository;

import citypass.loginfederado.model.EventOutbox;
import citypass.loginfederado.model.EventOutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EventOutboxRepository extends JpaRepository<EventOutbox, UUID>, EventOutboxRepositoryCustom {

    long countByStatus(EventOutboxStatus status);
}
