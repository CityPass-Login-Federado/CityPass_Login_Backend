package citypass.loginfederado.repository;

import citypass.loginfederado.model.LoginLockout;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginLockoutRepository
        extends JpaRepository<LoginLockout, String> {
}