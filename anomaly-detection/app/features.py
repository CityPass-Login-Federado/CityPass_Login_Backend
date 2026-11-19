from datetime import datetime
from sqlalchemy import create_engine, text
import os

from app.feature_contract import (
    FAILURE_WINDOW_SECONDS,
    MAX_HISTORY,
    normalize_signal,
)

engine = create_engine(os.environ["ANOMALY_DB_URL"])  # misma Postgres, usuario solo-lectura

def build_features(username: str, ip: str, user_agent: str, ts: datetime) -> dict:
    # Postgres guarda attempted_at sin timezone (naive). Si ts viene con
    # timezone (por ejemplo desde Instant.now() de Java, que trae "Z"),
    # lo normalizamos a naive para poder comparar ambos datetimes.
    if ts.tzinfo is not None:
        ts = ts.replace(tzinfo=None)

    ip = normalize_signal(ip)
    user_agent = normalize_signal(user_agent)

    with engine.connect() as conn:
        history = conn.execute(
            text("""
                SELECT ip_address, user_agent, attempted_at, successful
                FROM login_attempts
                WHERE username = :u
                ORDER BY attempted_at DESC
                LIMIT :history_limit
            """),
            {"u": username, "history_limit": MAX_HISTORY}
        ).fetchall()

    known_ips = {row.ip_address for row in history}
    known_agents = {row.user_agent for row in history}
    recent_failures = sum(
        1 for row in history
        if not row.successful
        and 0 <= (ts - row.attempted_at).total_seconds() < FAILURE_WINDOW_SECONDS
    )

    return {
        "hour_of_day": ts.hour,
        "is_new_ip": int(ip not in known_ips),
        "is_new_device": int(user_agent not in known_agents),
        "recent_failures_15min": recent_failures,
        "history_size": len(history),
    }
