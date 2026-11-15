package citypass.loginfederado.repository;

import citypass.loginfederado.model.EventOutbox;

import java.time.Instant;
import java.util.List;

public interface EventOutboxRepositoryCustom {

    List<EventOutbox> lockDue(Instant now, int limit);
}
