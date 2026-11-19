import argparse
import random
import uuid

from datetime import datetime, timedelta, timezone
from ipaddress import IPv4Address

import pandas as pd

from ML.config import DATA_DIR


LOCAL_TZ = timezone(timedelta(hours=-3))

SPLITS = {
    "train": (142, 300, 20, datetime(2026, 1, 1)),
    "validation": (143, 200, 10, datetime(2026, 4, 1)),
    "test": (144, 200, 10, datetime(2026, 6, 1)),
}

HISTORY_SIZES = [
    0, 0, 3, 15, 40, 100, 150, 200, 220
]

ANOMALY_TYPES = [
    "new_ip",
    "new_device",
    "unusual_hour",
    "recent_failures",
    "multiple_changes",
]

NORMAL_SCENARIOS = [
    "routine",
    "legitimate_off_hours",
    "legitimate_new_ip",
    "legitimate_new_device",
    "legitimate_multiple_changes",
    "legitimate_failures",
    "legitimate_new_environment_failures",
]

NORMAL_WEIGHTS = [
    62, 8, 8, 6, 3, 10, 3
]

USER_AGENTS = [
    "Chrome-Windows",
    "Firefox-Windows",
    "Edge-Windows",
    "Chrome-Android",
    "Safari-iOS",
]


def generate_ip(user_index, slot):

    base = int(IPv4Address("10.64.0.0"))

    return str(
        IPv4Address(
            base + user_index * 512 + slot
        )
    )


def generate_dataset(split):

    seed, users, targets_per_user, anchor = SPLITS[split]

    rng = random.Random(seed)

    anchor = anchor.replace(tzinfo=LOCAL_TZ)

    total_targets = users * targets_per_user

    anomalies_per_type = total_targets // 100

    scenarios = (
        ANOMALY_TYPES * anomalies_per_type
    )

    scenarios.extend(
        ["normal"] * (
            total_targets - len(scenarios)
        )
    )

    rng.shuffle(scenarios)

    events = []

    for user_index in range(1, users + 1):

        username = (
            f"v4-{split}-{user_index:03d}"
        )

        habitual_ip = generate_ip(user_index, 1)

        alternative_ip = generate_ip(user_index, 2)

        habitual_agent = rng.choice(USER_AGENTS)

        alternative_agent = rng.choice([
            agent for agent in USER_AGENTS
            if agent != habitual_agent
        ])

        shift = rng.choices(
            ["day", "evening", "night"],
            weights=[70, 20, 10],
        )[0]

        if shift == "day":
            habitual_hours = list(range(8, 19))

        elif shift == "evening":
            habitual_hours = list(range(15, 24))

        else:
            habitual_hours = list(range(0, 8))

        unusual_hours = [
            hour for hour in range(24)
            if hour not in habitual_hours
        ]

        warmup_count = HISTORY_SIZES[
            (user_index - 1) % len(HISTORY_SIZES)
        ]

        sequence = 0

        def add_event(
            timestamp,
            ip,
            agent,
            successful,
            label,
            anomaly_type,
            scenario,
            evaluation_target,
        ):

            nonlocal sequence

            sequence += 1

            event_id = uuid.uuid5(
                uuid.NAMESPACE_DNS,
                f"{username}:{sequence}",
            )

            events.append({
                "eventId": str(event_id),
                "eventType": (
                    "identidad.login"
                    if successful
                    else "identidad.login.failed"
                ),
                "occurredAt": timestamp.astimezone(
                    timezone.utc
                ).isoformat(),
                "username": username,
                "ipAddress": ip,
                "userAgent": agent,
                "successful": successful,
                "synthetic_label": label,
                "anomaly_type": anomaly_type,
                "scenario": scenario,
                "ml_eligible": successful,
                "evaluation_target": evaluation_target,
            })

        # ---------------------------------------
        # HISTORIAL PREVIO DEL USUARIO
        # ---------------------------------------

        for index in range(warmup_count):

            day = anchor - timedelta(
                days=warmup_count - index
            )

            timestamp = day.replace(
                hour=rng.choice(habitual_hours),
                minute=rng.randint(0, 59),
                second=rng.randint(0, 59),
            )

            ip = (
                alternative_ip
                if rng.random() < 0.15
                else habitual_ip
            )

            agent = (
                alternative_agent
                if rng.random() < 0.15
                else habitual_agent
            )

            add_event(
                timestamp,
                ip,
                agent,
                True,
                0,
                "warmup",
                "warmup",
                False,
            )

        # ---------------------------------------
        # EVENTOS PARA ENTRENAR O EVALUAR
        # ---------------------------------------

        for index in range(targets_per_user):

            selected = scenarios.pop()

            is_anomaly = selected != "normal"

            scenario = selected

            if not is_anomaly:

                scenario = rng.choices(
                    NORMAL_SCENARIOS,
                    weights=NORMAL_WEIGHTS,
                )[0]

            day = anchor + timedelta(
                days=index * 2 + user_index % 7
            )

            hour = rng.choice(habitual_hours)

            if scenario in (
                "legitimate_off_hours",
                "unusual_hour",
                "multiple_changes",
            ):

                hour = rng.choice(unusual_hours)

            timestamp = day.replace(
                hour=hour,
                minute=rng.randint(0, 59),
                second=rng.randint(0, 59),
            )

            ip = habitual_ip
            agent = habitual_agent

            # Alternativas legítimas conocidas.
            if rng.random() < 0.15:
                ip = alternative_ip

            if rng.random() < 0.15:
                agent = alternative_agent

            # Cambios de IP legítimos y anómalos.
            if scenario in (
                "legitimate_new_ip",
                "legitimate_multiple_changes",
                "legitimate_new_environment_failures",
                "new_ip",
                "multiple_changes",
            ):

                ip = generate_ip(
                    user_index,
                    10 + index,
                )

            # Cambios de dispositivo.
            if scenario in (
                "legitimate_new_device",
                "legitimate_multiple_changes",
                "legitimate_new_environment_failures",
                "new_device",
                "multiple_changes",
            ):

                agent = (
                    f"Chrome-Windows/{130 + index}"
                )

            # Fallos recientes de contraseña.
            failures = 0

            if scenario == "legitimate_failures":
                failures = rng.randint(1, 3)

            elif scenario == "legitimate_new_environment_failures":
                failures = rng.randint(2, 3)

            elif scenario == "recent_failures":
                failures = 3

            elif scenario == "multiple_changes":
                failures = rng.choices(
                    [0, 1, 2, 3],
                    weights=[15, 15, 40, 30],
                )[0]

            for attempt in range(failures, 0, -1):

                failure_timestamp = (
                    timestamp
                    - timedelta(minutes=attempt * 2)
                )

                # Los fallos legítimos normalmente
                # ocurren desde el mismo dispositivo.
                
                from_target_environment = (
                    not is_anomaly
                    or rng.random() < 0.5
                )
                failure_ip = (
                    ip
                    if from_target_environment
                    else habitual_ip
                )

                failure_agent = (
                    agent
                    if from_target_environment
                    else habitual_agent
                )

                add_event(
                    failure_timestamp,
                    failure_ip,
                    failure_agent,
                    False,
                    0,
                    "failure_context",
                    "failure_context",
                    False,
                )

            # Intento con credenciales válidas.
            add_event(
                timestamp,
                ip,
                agent,
                True,
                int(is_anomaly),
                selected,
                scenario,
                True,
            )

    dataframe = pd.DataFrame(events)

    dataframe["occurredAt"] = pd.to_datetime(
        dataframe["occurredAt"],
        utc=True,
    )

    dataframe = dataframe.sort_values(
        ["occurredAt", "eventId"]
    ).reset_index(drop=True)

    targets = dataframe[
        dataframe["evaluation_target"]
    ]

    assert len(targets) == total_targets

    assert dataframe["eventId"].is_unique

    assert targets["ml_eligible"].all()

    assert targets["synthetic_label"].sum() == (
        anomalies_per_type * len(ANOMALY_TYPES)
    )

    DATA_DIR.mkdir(
        parents=True,
        exist_ok=True,
    )

    output_path = (
        DATA_DIR / f"synthetic_events_v4_{split}.csv"
    )

    dataframe.to_csv(output_path, index=False)

    print(f"\nDATASET V4 - {split.upper()}")
    print("-" * 40)

    print("Total events:", len(dataframe))
    print("Target events:", len(targets))
    print("Users:", dataframe["username"].nunique())

    print("\nTarget labels:")
    print(targets["synthetic_label"].value_counts())

    print("\nTarget scenarios:")
    print(targets["scenario"].value_counts())

    print("\nSaved at:")
    print(output_path)

    return dataframe


if __name__ == "__main__":

    parser = argparse.ArgumentParser()

    parser.add_argument(
        "--split",
        choices=SPLITS.keys(),
        required=True,
    )

    args = parser.parse_args()

    generate_dataset(args.split)