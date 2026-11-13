import argparse
from collections import defaultdict, deque
from pathlib import Path
from tempfile import NamedTemporaryFile

import pandas as pd

from app.feature_contract import (
    FAILURE_WINDOW_SECONDS,
    FEATURE_ORDER,
    MAX_HISTORY,
    normalize_signal,
)
from ML.config import DATASET_PATH, PROCESSED_DATASET_PATH


REQUIRED_COLUMNS = [
    "occurredAt",
    "username",
    "ipAddress",
    "userAgent",
    "successful",
]


def coerce_boolean(value) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)) and value in (0, 1):
        return bool(value)
    normalized = str(value).strip().lower()
    if normalized in {"true", "1", "yes"}:
        return True
    if normalized in {"false", "0", "no"}:
        return False
    raise ValueError(f"Valor booleano invalido en successful: {value!r}")


def normalize_nullable(value) -> str:
    if pd.isna(value):
        return "<unknown>"
    return normalize_signal(value)


def validate_raw_dataset(dataframe: pd.DataFrame) -> None:
    missing = [column for column in REQUIRED_COLUMNS if column not in dataframe.columns]
    if missing:
        raise ValueError(f"Missing expected raw columns: {missing}")
    if "userSub" not in dataframe.columns and dataframe["username"].isna().any():
        raise ValueError("Rows without username require userSub as stable identity.")


def build_offline_features(dataframe: pd.DataFrame) -> pd.DataFrame:
    """Build features using only events preceding the current row."""
    validate_raw_dataset(dataframe)
    dataframe = dataframe.copy()
    dataframe["occurredAt"] = pd.to_datetime(
        dataframe["occurredAt"], errors="raise", utc=True
    )
    dataframe["successful"] = dataframe["successful"].map(coerce_boolean)

    sort_columns = ["occurredAt"]
    if "eventId" in dataframe.columns:
        sort_columns.append("eventId")
    dataframe = dataframe.sort_values(by=sort_columns).reset_index(drop=True)

    histories = defaultdict(lambda: deque(maxlen=MAX_HISTORY))
    processed_rows = []

    for _, row in dataframe.iterrows():
        username = row["username"]
        identity = username if pd.notna(username) else row["userSub"]
        history = histories[str(identity)]
        timestamp = row["occurredAt"]
        ip_address = normalize_nullable(row["ipAddress"])
        user_agent = normalize_nullable(row["userAgent"])

        known_ips = {event["ipAddress"] for event in history}
        known_agents = {event["userAgent"] for event in history}
        recent_failures = sum(
            1
            for event in history
            if not event["successful"]
            and 0
            <= (timestamp - event["occurredAt"]).total_seconds()
            < FAILURE_WINDOW_SECONDS
        )

        features = {
            "hour_of_day": timestamp.hour,
            "is_new_ip": int(ip_address not in known_ips),
            "is_new_device": int(user_agent not in known_agents),
            "recent_failures_15min": recent_failures,
            "history_size": len(history),
            "successful": row["successful"],
            "occurred_at": timestamp.isoformat(),
        }
        for optional_column in ("synthetic_label", "anomaly_type"):
            if optional_column in dataframe.columns:
                features[optional_column] = row[optional_column]
        processed_rows.append(features)

        history.append(
            {
                "occurredAt": timestamp,
                "ipAddress": ip_address,
                "userAgent": user_agent,
                "successful": row["successful"],
            }
        )

    return pd.DataFrame(processed_rows)


def validate_dataset(dataframe: pd.DataFrame) -> None:
    missing = [feature for feature in FEATURE_ORDER if feature not in dataframe.columns]
    if missing:
        raise ValueError(f"Missing expected features: {missing}")
    if dataframe[FEATURE_ORDER].isnull().any().any():
        raise ValueError("Processed features contain null values.")


def write_csv_atomically(dataframe: pd.DataFrame, output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = None
    try:
        with NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="",
            suffix=".csv.tmp",
            dir=output_path.parent,
            delete=False,
        ) as temporary_file:
            temporary_path = Path(temporary_file.name)
            dataframe.to_csv(temporary_file, index=False)
        temporary_path.replace(output_path)
    finally:
        if temporary_path is not None and temporary_path.exists():
            temporary_path.unlink()


def preprocess_file(input_path: Path, output_path: Path) -> pd.DataFrame:
    dataframe = pd.read_csv(input_path)
    processed = build_offline_features(dataframe)
    validate_dataset(processed)
    write_csv_atomically(processed, output_path)
    return processed


def print_summary(dataframe: pd.DataFrame) -> None:
    print("\nProcessed ML dataset")
    print("--------------------")
    print(f"Total records: {len(dataframe)}")
    print("\nFeature statistics:")
    print(dataframe[FEATURE_ORDER].describe())
    if "anomaly_type" in dataframe.columns:
        print("\nAnomaly distribution:")
        print(dataframe["anomaly_type"].value_counts())


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Transforma eventos sinteticos o reales en features de ML."
    )
    parser.add_argument("--input", type=Path, default=DATASET_PATH)
    parser.add_argument("--output", type=Path, default=PROCESSED_DATASET_PATH)
    return parser.parse_args()


def main() -> None:
    args = parse_arguments()
    processed = preprocess_file(args.input, args.output)
    print_summary(processed)
    print(f"\nProcessed dataset saved at:\n{args.output.resolve()}")


if __name__ == "__main__":
    main()
