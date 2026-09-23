from collections import defaultdict, deque

import pandas as pd

from config import (
    DATASET_PATH,
    PROCESSED_DATASET_PATH,
    DATA_DIR,
)


MAX_HISTORY = 200
FAILURE_WINDOW_SECONDS = 15 * 60


def build_offline_features(dataframe: pd.DataFrame) -> pd.DataFrame:
    """
    Builds ML features using the same behavioral logic as the production
    build_features() function.

    Features:
    - hour_of_day
    - is_new_ip
    - is_new_device
    - recent_failures_15min
    - history_size

    Only previous events are used when calculating each row.
    """

    dataframe = dataframe.copy()

    dataframe["occurredAt"] = pd.to_datetime(
        dataframe["occurredAt"]
    )

    dataframe = dataframe.sort_values(
        by="occurredAt"
    ).reset_index(drop=True)

    # Keep up to 200 previous events per user,
    # matching the production SQL query LIMIT 200.
    histories = defaultdict(
        lambda: deque(maxlen=MAX_HISTORY)
    )

    processed_rows = []

    for _, row in dataframe.iterrows():
        username = row["username"]

        # Logout events may not contain username.
        # userSub gives us a stable identity for synthetic processing.
        identity = (
            username
            if pd.notna(username)
            else row["userSub"]
        )

        history = histories[identity]
        timestamp = row["occurredAt"]

        known_ips = {
            event["ipAddress"]
            for event in history
        }

        known_agents = {
            event["userAgent"]
            for event in history
        }

        recent_failures = sum(
            1
            for event in history
            if (
                not event["successful"]
                and (
                    timestamp - event["occurredAt"]
                ).total_seconds() < FAILURE_WINDOW_SECONDS
            )
        )

        features = {
            "hour_of_day": timestamp.hour,
            "is_new_ip": int(
                row["ipAddress"] not in known_ips
            ),
            "is_new_device": int(
                row["userAgent"] not in known_agents
            ),
            "recent_failures_15min": recent_failures,
            "history_size": len(history),

            # These two fields are NOT training features.
            # They are kept only for evaluation.
            "synthetic_label": row["synthetic_label"],
            "anomaly_type": row["anomaly_type"],
        }

        processed_rows.append(features)

        # Add the current event only AFTER calculating its features.
        # This prevents data leakage from the current row.
        history.append(
            {
                "occurredAt": timestamp,
                "ipAddress": row["ipAddress"],
                "userAgent": row["userAgent"],
                "successful": bool(row["successful"]),
            }
        )

    return pd.DataFrame(processed_rows)


def validate_dataset(dataframe: pd.DataFrame):
    """
    Performs basic validation of the processed dataset.
    """

    expected_features = [
        "hour_of_day",
        "is_new_ip",
        "is_new_device",
        "recent_failures_15min",
        "history_size",
    ]

    for feature in expected_features:
        if feature not in dataframe.columns:
            raise ValueError(
                f"Missing expected feature: {feature}"
            )

    if dataframe[expected_features].isnull().any().any():
        raise ValueError(
            "Processed features contain null values."
        )


def print_summary(dataframe: pd.DataFrame):
    """
    Prints useful information about the processed dataset.
    """

    print()
    print("Processed ML dataset")
    print("--------------------")
    print(f"Total records: {len(dataframe)}")

    print()
    print("Feature statistics:")
    print(
        dataframe[
            [
                "hour_of_day",
                "is_new_ip",
                "is_new_device",
                "recent_failures_15min",
                "history_size",
            ]
        ].describe()
    )

    print()
    print("New IP events:")
    print(
        dataframe["is_new_ip"].value_counts()
    )

    print()
    print("New device events:")
    print(
        dataframe["is_new_device"].value_counts()
    )

    print()
    print("Anomaly distribution:")
    print(
        dataframe["anomaly_type"].value_counts()
    )


def main():
    dataframe = pd.read_csv(DATASET_PATH)

    processed = build_offline_features(
        dataframe
    )

    validate_dataset(processed)

    DATA_DIR.mkdir(
        parents=True,
        exist_ok=True,
    )

    processed.to_csv(
        PROCESSED_DATASET_PATH,
        index=False,
    )

    print_summary(processed)

    print()
    print("Processed dataset saved at:")
    print(PROCESSED_DATASET_PATH)


if __name__ == "__main__":
    main()