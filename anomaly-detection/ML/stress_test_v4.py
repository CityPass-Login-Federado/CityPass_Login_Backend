from datetime import datetime, timedelta, timezone

import pandas as pd

from ML.preprocess import build_offline_features

from ML.evaluate_hybrid_v4 import (
    load_model_and_policy,
    evaluate_dataframe,
)

from app.feature_contract import FEATURE_ORDER


def generate_stress_cases():

    events = []

    anchor = datetime(
        2026, 8, 1, 15, 0,
        tzinfo=timezone.utc,
    )

    cases = [
        {
            "name": "legitimate_new_environment",
            "label": 0,
            "history": 30,
            "new_ip": True,
            "new_device": True,
            "failures": 2,
            "failures_from_new_environment": False,
        },
        {
            "name": "attack_new_environment",
            "label": 1,
            "history": 30,
            "new_ip": True,
            "new_device": True,
            "failures": 2,
            "failures_from_new_environment": False,
        },
        {
            "name": "legitimate_typo_new_device",
            "label": 0,
            "history": 30,
            "new_ip": True,
            "new_device": True,
            "failures": 3,
            "failures_from_new_environment": True,
        },
        {
            "name": "attack_repeated_new_device",
            "label": 1,
            "history": 30,
            "new_ip": True,
            "new_device": True,
            "failures": 3,
            "failures_from_new_environment": True,
        },
        {
            "name": "legitimate_known_environment",
            "label": 0,
            "history": 200,
            "new_ip": False,
            "new_device": False,
            "failures": 3,
            "failures_from_new_environment": False,
        },
        {
            "name": "attack_known_environment",
            "label": 1,
            "history": 200,
            "new_ip": False,
            "new_device": False,
            "failures": 3,
            "failures_from_new_environment": False,
        },
        {
            "name": "legitimate_cold_start",
            "label": 0,
            "history": 0,
            "new_ip": True,
            "new_device": True,
            "failures": 0,
            "failures_from_new_environment": False,
        },
        {
            "name": "attack_cold_start",
            "label": 1,
            "history": 0,
            "new_ip": True,
            "new_device": True,
            "failures": 0,
            "failures_from_new_environment": False,
        },
    ]

    for index, case in enumerate(cases):

        username = f"stress-{index}"

        known_ip = "10.10.0.10"
        known_agent = "Chrome-Windows"

        target_ip = (
            "10.20.0.20"
            if case["new_ip"]
            else known_ip
        )

        target_agent = (
            "Safari-iOS"
            if case["new_device"]
            else known_agent
        )

        def add_event(
            timestamp,
            ip,
            agent,
            successful,
            target,
        ):

            events.append({
                "eventId": f"stress-{len(events)}",
                "occurredAt": timestamp,
                "username": username,
                "ipAddress": ip,
                "userAgent": agent,
                "successful": successful,
                "synthetic_label": (
                    case["label"] if target else 0
                ),
                "anomaly_type": (
                    case["name"] if target else "context"
                ),
                "scenario": (
                    case["name"] if target else "context"
                ),
                "ml_eligible": successful,
                "evaluation_target": target,
            })

        # Historial de accesos legítimos.
        for day in range(case["history"], 0, -1):

            add_event(
                anchor - timedelta(days=day),
                known_ip,
                known_agent,
                True,
                False,
            )

        # Fallos previos al login evaluado.
        for attempt in range(case["failures"], 0, -1):

            from_new = case[
                "failures_from_new_environment"
            ]

            add_event(
                anchor - timedelta(minutes=attempt * 2),
                target_ip if from_new else known_ip,
                target_agent if from_new else known_agent,
                False,
                False,
            )

        # Login que alcanza el detector ML.
        add_event(
            anchor,
            target_ip,
            target_agent,
            True,
            True,
        )

    return pd.DataFrame(events)


def main():

    raw = generate_stress_cases()

    processed = build_offline_features(raw)

    targets = processed[
        processed["evaluation_target"].eq(True)
    ].copy()

    model, policy = load_model_and_policy()

    results = evaluate_dataframe(
        targets,
        model,
        policy,
    )

    columns = [
        "scenario",
        "synthetic_label",
        *FEATURE_ORDER,
        "risk_score",
        "context_eligible",
        "decision",
    ]

    print("\nSTRESS TEST V4")
    print("--------------")

    print(
        results[columns]
        .round(4)
        .to_string(index=False)
    )

    print("\nFALSE BLOCKS IN STRESS CASES")
    print("----------------------------")

    print(
        results[
            results["synthetic_label"].eq(0)
            & results["decision"].eq("BLOCK")
        ][["scenario", "risk_score"]]
        .to_string(index=False)
    )


if __name__ == "__main__":
    main()