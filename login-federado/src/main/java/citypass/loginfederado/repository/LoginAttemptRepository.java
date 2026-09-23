package citypass.loginfederado.repository;

import citypass.loginfederado.model.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, UUID> {

    long countByUsernameAndSuccessfulFalseAndAttemptedAtAfter(
            String username,
            Instant since
    );

    void deleteByUsernameAndSuccessfulFalse(String username);

    @Query(value = """
            SELECT EXTRACT(HOUR FROM attempted_at)::int AS hourOfDay,
                COUNT(*) AS loginCount
            FROM login_attempts
            WHERE successful = true
                AND attempted_at >= :from
                AND attempted_at < :to
            GROUP BY hourOfDay
            ORDER BY hourOfDay
            """, nativeQuery = true)
    List<HourlyLoginCount> countSuccessfulLoginsByHourBetween(
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    @Query(value = """
            SELECT COUNT(DISTINCT username)
            FROM login_attempts
            WHERE successful = true
                AND attempted_at >= :from
                AND attempted_at < :to
            """, nativeQuery = true)
    long countDistinctActiveUsersBetween(
            @Param("from") Instant from,
            @Param("to") Instant to
    );
}