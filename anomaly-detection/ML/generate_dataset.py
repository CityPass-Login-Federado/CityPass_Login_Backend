import random
import uuid
from datetime import datetime, timedelta

import numpy as np
import pandas as pd

from config import (
    RANDOM_SEED,
    TOTAL_RECORDS,
    NORMAL_RECORDS,
    ANOMALY_RECORDS,
    DATASET_PATH,
    DATA_DIR,
    DEPARTMENTS,
    CLIENT_IDS,
    USER_AGENTS,
)


random.seed(RANDOM_SEED)
np.random.seed(RANDOM_SEED)


# Number of synthetic users in the simulated CityPass environment.
NUMBER_OF_USERS = 200


def generate_ip():
    """
    Generates a synthetic private IP address.
    """
    return (
        f"10."
        f"{random.randint(1, 20)}."
        f"{random.randint(1, 254)}."
        f"{random.randint(1, 254)}"
    )


def generate_external_ip():
    """
    Generates an IP different from the user's usual internal IP.
    Used for anomalous events.
    """
    return (
        f"{random.randint(20, 220)}."
        f"{random.randint(1, 254)}."
        f"{random.randint(1, 254)}."
        f"{random.randint(1, 254)}"
    )


def generate_users():
    """
    Creates synthetic CityPass users.

    Each user receives a habitual behavioral profile:
    - department
    - IP
    - client
    - user agent
    - usual login time range
    """
    users = []

    for index in range(1, NUMBER_OF_USERS + 1):
        sub = f"U{index:06d}"
        username = f"user{index:03d}"

        department = random.choice(DEPARTMENTS)
        habitual_ip = generate_ip()
        habitual_client = random.choice(CLIENT_IDS)
        habitual_user_agent = random.choice(USER_AGENTS)

        # Most users operate during normal working hours.
        habitual_start_hour = random.choice([7, 8, 9])
        habitual_end_hour = random.choice([17, 18, 19])

        users.append(
            {
                "userSub": sub,
                "username": username,
                "department": department,
                "habitual_ip": habitual_ip,
                "habitual_client": habitual_client,
                "habitual_user_agent": habitual_user_agent,
                "habitual_start_hour": habitual_start_hour,
                "habitual_end_hour": habitual_end_hour,
            }
        )

    return users


def random_business_datetime(start_date, end_date, user):
    """
    Generates a timestamp compatible with the user's habitual activity.

    Normal activity happens:
    - Monday to Friday
    - inside the user's usual working hours
    """
    total_days = (end_date - start_date).days

    while True:
        selected_day = start_date + timedelta(
            days=random.randint(0, total_days)
        )

        # Monday = 0, Sunday = 6
        if selected_day.weekday() < 5:
            break

    hour = random.randint(
        user["habitual_start_hour"],
        user["habitual_end_hour"],
    )

    minute = random.randint(0, 59)
    second = random.randint(0, 59)

    return selected_day.replace(
        hour=hour,
        minute=minute,
        second=second,
        microsecond=0,
    )


def choose_event_type():
    """
    Simulates a realistic authentication event distribution.

    Login and refresh events are more common than logout events.
    """
    return random.choices(
        population=[
            "identidad.login",
            "identidad.refresh",
            "identidad.logout",
        ],
        weights=[
            0.40,
            0.40,
            0.20,
        ],
        k=1,
    )[0]


def generate_chain_id(event_type):
    """
    Refresh and logout events may belong to an authentication chain.
    Login events start a new chain and therefore do not contain one.
    """
    if event_type == "identidad.login":
        return None

    return str(uuid.uuid4())


def create_event(
    user,
    occurred_at,
    event_type,
    ip_address,
    user_agent,
    client_id,
    synthetic_label,
    anomaly_type,
    successful=True,
):
    """
    Creates one synthetic event following the RawAuthenticationEvent structure.
    """
    username = user["username"]

    if event_type == "identidad.logout":
        username = None

    return {
        "eventId": str(uuid.uuid4()),
        "eventType": event_type,
        "occurredAt": occurred_at.isoformat(),
        "userSub": user["userSub"],
        "username": username,
        "department": user["department"],
        "clientId": client_id,
        "chainId": generate_chain_id(event_type),
        "ipAddress": ip_address,
        "userAgent": user_agent,
        "successful": successful,
        "synthetic_label": synthetic_label,
        "anomaly_type": anomaly_type,
    }


def generate_normal_event(user, start_date, end_date):
    """
    Generates one event that follows the user's usual behavior.
    """
    occurred_at = random_business_datetime(
        start_date,
        end_date,
        user,
    )

    event_type = choose_event_type()

    return create_event(
        user=user,
        occurred_at=occurred_at,
        event_type=event_type,
        ip_address=user["habitual_ip"],
        user_agent=user["habitual_user_agent"],
        client_id=user["habitual_client"],
        synthetic_label=0,
        anomaly_type="normal",
    )


def generate_unusual_hour_event(user, start_date, end_date):
    """
    Generates activity during unusual hours such as midnight to 05:00.
    """
    total_days = (end_date - start_date).days

    selected_day = start_date + timedelta(
        days=random.randint(0, total_days)
    )

    occurred_at = selected_day.replace(
        hour=random.randint(0, 5),
        minute=random.randint(0, 59),
        second=random.randint(0, 59),
        microsecond=0,
    )

    return create_event(
        user=user,
        occurred_at=occurred_at,
        event_type=choose_event_type(),
        ip_address=user["habitual_ip"],
        user_agent=user["habitual_user_agent"],
        client_id=user["habitual_client"],
        synthetic_label=1,
        anomaly_type="unusual_hour",
    )


def generate_new_ip_event(user, start_date, end_date):
    """
    Generates an event from an IP that does not belong to the user's usual profile.
    """
    occurred_at = random_business_datetime(
        start_date,
        end_date,
        user,
    )

    return create_event(
        user=user,
        occurred_at=occurred_at,
        event_type=choose_event_type(),
        ip_address=generate_external_ip(),
        user_agent=user["habitual_user_agent"],
        client_id=user["habitual_client"],
        synthetic_label=1,
        anomaly_type="new_ip",
    )


def generate_unusual_client_event(user, start_date, end_date):
    """
    Generates an event from a client or device that differs from the habitual one.
    """
    occurred_at = random_business_datetime(
        start_date,
        end_date,
        user,
    )

    available_user_agents = [
        agent
        for agent in USER_AGENTS
        if agent != user["habitual_user_agent"]
    ]

    available_clients = [
        client
        for client in CLIENT_IDS
        if client != user["habitual_client"]
    ]

    anomalous_agent = random.choice(available_user_agents)

    if available_clients:
        anomalous_client = random.choice(available_clients)
    else:
        anomalous_client = user["habitual_client"]

    return create_event(
        user=user,
        occurred_at=occurred_at,
        event_type=choose_event_type(),
        ip_address=user["habitual_ip"],
        user_agent=anomalous_agent,
        client_id=anomalous_client,
        synthetic_label=1,
        anomaly_type="unusual_client",
    )



def generate_multiple_changes_event(user, start_date, end_date):
    """
    Generates a strongly anomalous event combining several changes:
    - unusual hour
    - unknown IP
    - unusual user agent
    - potentially different client
    """
    total_days = (end_date - start_date).days

    selected_day = start_date + timedelta(
        days=random.randint(0, total_days)
    )

    occurred_at = selected_day.replace(
        hour=random.randint(0, 4),
        minute=random.randint(0, 59),
        second=random.randint(0, 59),
        microsecond=0,
    )

    available_agents = [
        agent
        for agent in USER_AGENTS
        if agent != user["habitual_user_agent"]
    ]

    available_clients = [
        client
        for client in CLIENT_IDS
        if client != user["habitual_client"]
    ]

    return create_event(
        user=user,
        occurred_at=occurred_at,
        event_type="identidad.login",
        ip_address=generate_external_ip(),
        user_agent=random.choice(available_agents),
        client_id=(
            random.choice(available_clients)
            if available_clients
            else user["habitual_client"]
        ),
        synthetic_label=1,
        anomaly_type="multiple_changes",
    )


def generate_dataset():
    """
    Generates the complete synthetic authentication dataset.
    """
    users = generate_users()

    start_date = datetime(2026, 1, 1)
    end_date = datetime(2026, 8, 31)

    events = []

    # ---------------------------------------------------------
    # Normal events
    # ---------------------------------------------------------

    for _ in range(NORMAL_RECORDS):
        user = random.choice(users)

        events.append(
            generate_normal_event(
                user,
                start_date,
                end_date,
            )
        )

    # ---------------------------------------------------------
    # Anomalous events
    # ---------------------------------------------------------

    anomaly_generators = [
        generate_unusual_hour_event,
        generate_new_ip_event,
        generate_unusual_client_event,
        generate_multiple_changes_event,
    ]

    anomalies_per_type = ANOMALY_RECORDS // len(anomaly_generators)

    generated_anomalies = 0

    for generator in anomaly_generators:
        for _ in range(anomalies_per_type):
            user = random.choice(users)

            events.append(
                generator(
                    user,
                    start_date,
                    end_date,
                )
            )

            generated_anomalies += 1

    # Handle any remainder if ANOMALY_RECORDS is not divisible exactly.
    while generated_anomalies < ANOMALY_RECORDS:
        user = random.choice(users)
        generator = random.choice(anomaly_generators)

        events.append(
            generator(
                user,
                start_date,
                end_date,
            )
        )

        generated_anomalies += 1

    # ---------------------------------------------------------
    # Build dataframe
    # ---------------------------------------------------------

    dataframe = pd.DataFrame(events)

    dataframe["occurredAt"] = pd.to_datetime(
        dataframe["occurredAt"]
    )

    dataframe = dataframe.sort_values(
        by="occurredAt"
    ).reset_index(drop=True)

    # ---------------------------------------------------------
    # Validation
    # ---------------------------------------------------------

    assert len(dataframe) == TOTAL_RECORDS

    normal_count = (
        dataframe["synthetic_label"] == 0
    ).sum()

    anomaly_count = (
        dataframe["synthetic_label"] == 1
    ).sum()

    assert normal_count == NORMAL_RECORDS
    assert anomaly_count == ANOMALY_RECORDS

    # ---------------------------------------------------------
    # Save CSV
    # ---------------------------------------------------------

    DATA_DIR.mkdir(
        parents=True,
        exist_ok=True,
    )

    dataframe.to_csv(
        DATASET_PATH,
        index=False,
    )

    return dataframe


def print_dataset_summary(dataframe):
    """
    Prints basic information about the generated dataset.
    """
    total = len(dataframe)

    normal_count = (
        dataframe["synthetic_label"] == 0
    ).sum()

    anomaly_count = (
        dataframe["synthetic_label"] == 1
    ).sum()

    print()
    print("Synthetic authentication dataset generated")
    print("------------------------------------------")
    print(f"Total events:     {total}")
    print(f"Normal events:    {normal_count}")
    print(f"Anomalous events: {anomaly_count}")
    print()

    print("Anomaly distribution:")
    print(
        dataframe[
            dataframe["synthetic_label"] == 1
        ]["anomaly_type"].value_counts()
    )

    print()
    print(f"Dataset saved at:")
    print(DATASET_PATH)


if __name__ == "__main__":
    dataset = generate_dataset()
    print_dataset_summary(dataset)