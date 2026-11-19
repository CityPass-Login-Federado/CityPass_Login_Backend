import os

from datetime import datetime, timezone

from sqlalchemy import create_engine, text

from app.feature_contract import (
    FAILURE_WINDOW_SECONDS,
    MAX_HISTORY,
    normalize_signal,
)


engine = create_engine(
    os.environ["ANOMALY_DB_URL"]
)


def to_utc_naive(timestamp: datetime) -> datetime:

    if timestamp.tzinfo is not None:

        return timestamp.astimezone(
            timezone.utc
        ).replace(tzinfo=None)

    return timestamp


def build_features(
    username: str,
    ip: str,
    user_agent: str,
    ts: datetime,
) -> dict:

    ts = to_utc_naive(ts)

    ip = normalize_signal(ip)
    user_agent = normalize_signal(user_agent)

    with engine.connect() as conn:

        conn.execute(
            text("SET LOCAL TIME ZONE 'UTC'")
        )

        history = conn.execute(
            text("""
                SELECT
                    ip_address,
                    user_agent,
                    attempted_at,
                    successful
                FROM login_attempts
                WHERE username = :u
                  AND attempted_at < :ts
                ORDER BY attempted_at DESC, id DESC
                LIMIT :history_limit
            """),
            {
                "u": username,
                "ts": ts,
                "history_limit": MAX_HISTORY,
            },
        ).fetchall()

    known_ips = {
        normalize_signal(row.ip_address)
        for row in history
        if row.successful
    }

    known_agents = {
        normalize_signal(row.user_agent)
        for row in history
        if row.successful
    }

    recent_failures = sum(
        1
        for row in history
        if not row.successful
        and 0 <= (
            ts - to_utc_naive(row.attempted_at)
        ).total_seconds() < FAILURE_WINDOW_SECONDS
    )

    return {
        "hour_of_day": ts.hour,
        "is_new_ip": int(ip not in known_ips),
        "is_new_device": int(user_agent not in known_agents),
        "recent_failures_15min": recent_failures,
        "history_size": len(history),
    }