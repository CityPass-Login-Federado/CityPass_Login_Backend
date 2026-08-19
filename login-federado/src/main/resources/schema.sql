CREATE TABLE IF NOT EXISTS refresh_tokens (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    email VARCHAR(255),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    roles VARCHAR(500) NOT NULL,
    issued_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_username ON refresh_tokens (username);

CREATE TABLE IF NOT EXISTS login_attempts (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    successful BOOLEAN NOT NULL,
    attempted_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_login_attempts_username ON login_attempts (username);
CREATE INDEX IF NOT EXISTS idx_login_attempts_attempted_at ON login_attempts (attempted_at);