"""Shared feature contract for training and runtime inference."""

FEATURE_ORDER = [
    "hour_of_day",
    "is_new_ip",
    "is_new_device",
    "recent_failures_15min",
    "history_size",
]

MAX_HISTORY = 200
FAILURE_WINDOW_SECONDS = 15 * 60


def normalize_signal(value: object) -> str:
    if value is None or str(value).strip() == "":
        return "<unknown>"
    return str(value)

