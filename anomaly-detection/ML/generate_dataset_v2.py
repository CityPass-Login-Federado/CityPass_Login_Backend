import random
import uuid
from datetime import datetime, timedelta, timezone

import pandas as pd

from ML.config import DATA_DIR, RANDOM_SEED


NUMBER_OF_USERS = 200
OUTPUT_PATH = DATA_DIR / "synthetic_events_v2.csv"

LOCAL_TZ = timezone(timedelta(hours=-3))

ANOMALY_TYPES = [
    "new_ip",
    "new_device",
    "unusual_hour",
    "recent_failures",
    "multiple_changes",
]

USER_AGENTS = [
    "Chrome-Windows",
    "Firefox-Windows",
    "Edge-Windows",
    "Chrome-Android",
]

rng = random.Random(RANDOM_SEED)


def generate_dataset():
    events = []

    # 100 anomalías de cada tipo.
    scenarios = ANOMALY_TYPES * 100
    rng.shuffle(scenarios)

    # Historiales de distintos tamaños.
    # En conjunto suman 10.000 registros.
    user_counts = []

    for _ in range(NUMBER_OF_USERS // 2):
        count = rng.randint(25, 50)
        user_counts.extend([count, 100 - count])

    rng.shuffle(user_counts)

    for user_index in range(1, NUMBER_OF_USERS + 1):

        username = f"user{user_index:03d}"
        target_count = user_counts[user_index - 1]

        habitual_ip = f"10.10.{user_index}.10"
        alternative_ip = f"10.10.{user_index}.20"

        habitual_agent = rng.choice(USER_AGENTS)

        alternative_agent = rng.choice([
            agent for agent in USER_AGENTS
            if agent != habitual_agent
        ])

        # Cada usuario tiene 2 o 3 anomalías.
        anomaly_count = 3 if user_index <= 100 else 2

        user_scenarios = [
            scenarios.pop()
            for _ in range(anomaly_count)
        ]

        # Tres secuencias de errores legítimos.
        blocks = ["legitimate_typo"] * 3
        blocks.extend(user_scenarios)

        def block_size(kind):
            if kind == "legitimate_typo":
                return 2
            if kind == "recent_failures":
                return 4
            if kind == "multiple_changes":
                return 3
            return 1

        occupied = sum(
            block_size(kind) for kind in blocks
        )

        # Completar el historial con logins normales.
        blocks.extend(
            ["normal"] * (target_count - occupied - 1)
        )

        rng.shuffle(blocks)

        # Primer login legítimo: escenario cold start.
        blocks.insert(0, "normal")

        current_date = datetime(
            2026, 1, 1,
            tzinfo=LOCAL_TZ,
        ) + timedelta(days=rng.randint(0, 14))

        sequence = 0

        def add_event(
            timestamp,
            ip,
            agent,
            successful,
            label,
            anomaly_type,
            ml_eligible,
        ):
            nonlocal sequence

            sequence += 1

            event_id = uuid.uuid5(
                uuid.NAMESPACE_DNS,
                f"citypass-v2:{username}:{sequence}",
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
                "ml_eligible": ml_eligible,
            })

        for block_index, kind in enumerate(blocks):

            current_date += timedelta(
                days=rng.randint(1, 3)
            )

            hour = rng.randint(8, 18)

            # Algunos accesos legítimos fuera
            # del horario habitual.
            if kind == "normal" and rng.random() < 0.03:
                hour = rng.randint(0, 5)

            if kind in (
                "unusual_hour",
                "multiple_changes",
            ):
                hour = rng.randint(0, 4)

            timestamp = current_date.replace(
                hour=hour,
                minute=rng.randint(0, 59),
                second=rng.randint(0, 59),
                microsecond=0,
            )

            ip = habitual_ip
            agent = habitual_agent

            # Cambios legítimos de red y dispositivo.
            if kind in ("normal", "legitimate_typo"):

                if rng.random() < 0.12:
                    ip = alternative_ip

                if rng.random() < 0.10:
                    agent = alternative_agent

            # Errores legítimos de contraseña.
            if kind == "legitimate_typo":

                add_event(
                    timestamp - timedelta(minutes=2),
                    ip,
                    agent,
                    False,
                    0,
                    "legitimate_failure",
                    False,
                )

            # Intentos fallidos previos a una
            # autenticación sospechosa.
            if kind in (
                "recent_failures",
                "multiple_changes",
            ):
                failures = (
                    3 if kind == "recent_failures"
                    else 2
                )

                for attempt in range(failures, 0, -1):

                    add_event(
                        timestamp - timedelta(
                            minutes=attempt * 2
                        ),
                        habitual_ip,
                        habitual_agent,
                        False,
                        0,
                        "failure_context",
                        False,
                    )

            # IP no observada previamente.
            if kind in ("new_ip", "multiple_changes"):
                ip = (
                    f"10.200.{user_index}."
                    f"{block_index + 1}"
                )

            # Dispositivo no observado previamente.
            if kind in (
                "new_device",
                "multiple_changes",
            ):
                agent = f"Safari-macOS-{block_index}"

            is_anomaly = kind in ANOMALY_TYPES

            add_event(
                timestamp,
                ip,
                agent,
                True,
                int(is_anomaly),
                kind if is_anomaly else "normal",
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

    # Validaciones del dataset.
    assert len(dataframe) == 10_000
    assert dataframe["synthetic_label"].sum() == 500
    assert dataframe["username"].nunique() == 200
    assert dataframe["eventId"].is_unique

    assert (
        (~dataframe["successful"]).sum()
        == 1_100
    )

    DATA_DIR.mkdir(
        parents=True,
        exist_ok=True,
    )

    dataframe.to_csv(
        OUTPUT_PATH,
        index=False,
    )

    return dataframe


if __name__ == "__main__":

    dataset = generate_dataset()

    print("\nSynthetic dataset V2")
    print("--------------------")
    print("Total:", len(dataset))
    print("Usuarios:", dataset["username"].nunique())

    print("\nResultado de autenticaciones:")
    print(dataset["successful"].value_counts())

    print("\nDistribución de anomalías:")
    print(dataset["synthetic_label"].value_counts())

    print("\nTipos de eventos:")
    print(dataset["anomaly_type"].value_counts())

    print("\nElegibles para ML:")
    print(dataset["ml_eligible"].value_counts())

    print("\nDataset guardado en:")
    print(OUTPUT_PATH)