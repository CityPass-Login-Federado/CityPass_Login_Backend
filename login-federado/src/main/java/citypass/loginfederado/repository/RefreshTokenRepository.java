package ar.edu.uade.citypass.loginfederado.repository;

import ar.edu.uade.citypass.loginfederado.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    List<RefreshToken> findAllByUsernameAndRevokedFalse(String username);
}