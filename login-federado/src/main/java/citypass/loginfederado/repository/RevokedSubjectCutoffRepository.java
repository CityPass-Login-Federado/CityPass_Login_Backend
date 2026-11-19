package citypass.loginfederado.repository;

import citypass.loginfederado.model.RevokedSubjectCutoff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface RevokedSubjectCutoffRepository extends JpaRepository<RevokedSubjectCutoff, String> {

    long deleteByExpiresAtBefore(Instant cutoff);
}
