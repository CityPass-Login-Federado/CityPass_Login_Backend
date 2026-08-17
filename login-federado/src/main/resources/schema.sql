CREATE TABLE IF NOT EXISTS login_attempts (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    successful BOOLEAN NOT NULL,
    attempted_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_login_attempts_username_time
    ON login_attempts (username, successful, attempted_at);