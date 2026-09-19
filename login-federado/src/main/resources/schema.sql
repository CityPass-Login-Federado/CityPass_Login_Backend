-- =============================================================================
-- CityPass+ Login Federado — esquema (dev)
--
-- ATENCIÓN: sql.init.mode=always ejecuta este archivo en CADA arranque.
-- Los DROP TABLE son a propósito en desarrollo: el formato de refresh_tokens
-- cambió respecto del PoC (cadena, sub, audience) y la tabla vieja es
-- incompatible. En producción esto se reemplaza por migraciones versionadas
-- (Flyway/Liquibase). Reiniciar la app invalida sesiones activas: esperado acá.
-- =============================================================================

DROP TABLE IF EXISTS panel_audit;
DROP TABLE IF EXISTS refresh_tokens;
DROP TABLE IF EXISTS login_attempts;

-- Refresh tokens OPACOS con rotación y cadena (spec §4.2 / D9).
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    sub VARCHAR(16) NOT NULL,                -- employeeNumber (U000042)
    chain_id UUID NOT NULL,                  -- sesión: todos los eslabones
    client_id VARCHAR(255) NOT NULL,         -- para reemitir misma audience
    audience VARCHAR(255) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,  -- SHA-256; nunca el valor crudo
    issued_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL                -- NULL = vivo
);

CREATE INDEX idx_refresh_tokens_sub ON refresh_tokens (sub);
CREATE INDEX idx_refresh_tokens_chain ON refresh_tokens (chain_id);

-- Intentos de login para la ventana deslizante (ADR-004).
CREATE TABLE login_attempts (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    successful BOOLEAN NOT NULL,
    attempted_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_login_attempts_username ON login_attempts (username);
CREATE INDEX idx_login_attempts_attempted_at ON login_attempts (attempted_at);

-- Auditoría del panel: cada mutación queda registrada (manual del panel §8:
-- "las altas, bajas y cambios de grupos quedan registrados").
CREATE TABLE panel_audit (
    id UUID PRIMARY KEY,
    actor_sub VARCHAR(16) NOT NULL,          -- quién (delegado)
    actor_uid VARCHAR(255) NOT NULL,
    module VARCHAR(64) NOT NULL,             -- módulo afectado/scope
    action VARCHAR(64) NOT NULL,             -- ej. PERSON_CREATED
    target VARCHAR(512) NOT NULL,            -- DN o identificador objetivo
    detail VARCHAR(1024),
    occurred_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_panel_audit_module ON panel_audit (module);
CREATE INDEX idx_panel_audit_occurred_at ON panel_audit (occurred_at);
