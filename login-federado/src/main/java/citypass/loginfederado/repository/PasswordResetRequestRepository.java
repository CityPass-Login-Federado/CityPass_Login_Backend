package citypass.loginfederado.repository;

import citypass.loginfederado.model.PasswordResetRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface PasswordResetRequestRepository extends JpaRepository<PasswordResetRequest, UUID> {

    long countByUidAndRequestedAtAfter(String uid, Instant since);

    long countByIpAddressAndRequestedAtAfter(String ipAddress, Instant since);
}
