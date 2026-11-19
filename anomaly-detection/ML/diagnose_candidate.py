import pandas as pd

from ML.config import DATA_DIR
from app.feature_contract import FEATURE_ORDER

from app.risk_policy import normalize_risk_score

from ML.evaluate_candidate import (
    load_model_and_policy,
)


def analyze_distribution():

    datasets = {
        "TRAIN": "train_v2.csv",
        "VALIDATION": "validation_v2.csv",
        "TEST": "test_v2.csv",
    }

    for name, filename in datasets.items():

        dataframe = pd.read_csv(
            DATA_DIR / filename
        )

        normal = dataframe[
            dataframe["synthetic_label"] == 0
        ]

        print(f"\n{name} - NORMAL EVENTS")
        print("-" * 40)

        print(
            normal[FEATURE_ORDER]
            .describe()
            .round(4)
        )


def analyze_false_blocks():

    dataframe = pd.read_csv(
        DATA_DIR / "evaluation_results_v2.csv"
    )

    normal = dataframe[
        dataframe["synthetic_label"] == 0
    ].copy()

    normal["blocked"] = (
        normal["decision"] == "BLOCK"
    ).astype(int)

    normal["history_group"] = pd.cut(
        normal["history_size"],
        bins=[-1, 0, 10, 20, 30, 40, 50, 100, 200],
    )

    print("\nFALSE BLOCKS BY HISTORY SIZE")
    print("-" * 40)

    print(
        normal.groupby(
            "history_group",
            observed=True,
        ).agg(
            total=("blocked", "size"),
            false_blocks=("blocked", "sum"),
            fpr=("blocked", "mean"),
            mean_risk=("risk_score", "mean"),
        ).round(4)
    )

    print("\nFALSE BLOCKS BY RECENT FAILURES")
    print("-" * 40)

    print(
        normal.groupby(
            "recent_failures_15min"
        ).agg(
            total=("blocked", "size"),
            false_blocks=("blocked", "sum"),
            fpr=("blocked", "mean"),
            mean_risk=("risk_score", "mean"),
        ).round(4)
    )

    print("\nFALSE BLOCKS BY HOUR")
    print("-" * 40)

    print(
        normal.groupby(
            "hour_of_day"
        ).agg(
            total=("blocked", "size"),
            false_blocks=("blocked", "sum"),
            fpr=("blocked", "mean"),
            mean_risk=("risk_score", "mean"),
        ).round(4)
    )

    print("\nFALSE BLOCKS BY IP AND DEVICE")
    print("-" * 40)

    print(
        normal.groupby([
            "is_new_ip",
            "is_new_device",
        ]).agg(
            total=("blocked", "size"),
            false_blocks=("blocked", "sum"),
            fpr=("blocked", "mean"),
        ).round(4)
    )


def analyze_history_sensitivity():

    model, policy = load_model_and_policy()

    rows = []

    # Mismo comportamiento, diferente historial.
    for history in (
        0, 1, 5, 10, 20, 30,
        40, 50, 75, 100, 150, 200
    ):

        for failures in (0, 1):

            rows.append({
                "hour_of_day": 15,
                "is_new_ip": 0,
                "is_new_device": 0,
                "recent_failures_15min": failures,
                "history_size": history,
            })

    dataframe = pd.DataFrame(rows)

    scores = model.decision_function(
        dataframe[FEATURE_ORDER]
    )

    dataframe["risk_score"] = [
        normalize_risk_score(score)
        for score in scores
    ]

    print("\nHISTORY SIZE SENSITIVITY")
    print("-" * 40)

    print(
        dataframe.pivot(
            index="history_size",
            columns="recent_failures_15min",
            values="risk_score",
        ).round(4)
    )

    print("\nCurrent BLOCK threshold:")
    print(policy["block_threshold"])


if __name__ == "__main__":

    analyze_distribution()
    analyze_false_blocks()
    analyze_history_sensitivity()