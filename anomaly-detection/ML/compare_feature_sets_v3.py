import numpy as np
import pandas as pd

from ML.config import DATA_DIR
from ML.train import train_model

from ML.calibrate import find_threshold

from app.feature_contract import FEATURE_ORDER

from app.risk_policy import normalize_risk_score


def evaluate_configuration(
    name,
    feature_columns,
    train,
    validation,
):

    print(f"\n{name}")
    print("=" * 45)

    print("Features:", feature_columns)

    model = train_model(
        train[feature_columns],
        contamination=0.05,
    )

    decision_scores = model.decision_function(
        validation[feature_columns]
    )

    scores = np.array([
        normalize_risk_score(score)
        for score in decision_scores
    ])

    labels = validation[
        "synthetic_label"
    ].to_numpy()

    block = find_threshold(
        scores,
        labels,
        max_fpr=0.01,
    )

    review = find_threshold(
        scores,
        labels,
        max_fpr=0.10,
        upper_bound=block["threshold"],
    )

    print("\nBLOCK threshold:", block["threshold"])
    print("BLOCK precision:", round(block["precision"], 4))
    print("BLOCK recall:", round(block["recall"], 4))
    print("BLOCK FPR:", round(block["fpr"], 4))
    print("BLOCK F1:", round(block["f1"], 4))

    print("\nREVIEW threshold:", review["threshold"])
    print("Flagged recall:", round(review["recall"], 4))
    print("Flagged FPR:", round(review["fpr"], 4))

    results = validation.copy()

    results["blocked"] = (
        scores >= block["threshold"]
    )

    results["flagged"] = (
        scores >= review["threshold"]
    )

    print("\nRESULTS BY ANOMALY TYPE")

    print(
        results.groupby(
            "anomaly_type"
        ).agg(
            total=("blocked", "size"),
            blocked=("blocked", "sum"),
            flagged=("flagged", "sum"),
            block_rate=("blocked", "mean"),
        ).round(4)
    )

    return {
        "configuration": name,
        "features": len(feature_columns),
        "block_threshold": block["threshold"],
        "block_precision": block["precision"],
        "block_recall": block["recall"],
        "block_fpr": block["fpr"],
        "block_f1": block["f1"],
        "flagged_recall": review["recall"],
        "flagged_fpr": review["fpr"],
    }


def main():

    train = pd.read_csv(
        DATA_DIR / "train_v3.csv"
    )

    validation = pd.read_csv(
        DATA_DIR / "validation_v3.csv"
    )

    configurations = {
        "V2 - FIVE FEATURES": FEATURE_ORDER,

        "EXPERIMENT - WITHOUT HISTORY SIZE": [
            feature
            for feature in FEATURE_ORDER
            if feature != "history_size"
        ],
    }

    results = []

    for name, features in configurations.items():

        results.append(
            evaluate_configuration(
                name,
                features,
                train,
                validation,
            )
        )

    print("\nFINAL COMPARISON - VALIDATION ONLY")
    print("=" * 45)

    comparison = pd.DataFrame(results)

    print(
        comparison.round(4)
        .to_string(index=False)
    )


if __name__ == "__main__":
    main()