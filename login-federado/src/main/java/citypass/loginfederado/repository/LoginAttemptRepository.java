package citypass.loginfederado.repository;

import citypass.loginfederado.model.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, UUID> {

    long countByUsernameAndSuccessfulFalseAndAttemptedAtAfter(String username, Instant since);
}