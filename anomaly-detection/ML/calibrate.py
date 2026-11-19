import hashlib
import json
import warnings
from pathlib import Path

import joblib
import numpy as np
import pandas as pd

from sklearn.exceptions import InconsistentVersionWarning

from app.feature_contract import FEATURE_ORDER
from app.risk_policy import (
    normalize_risk_score,
    resolve_decision,
)

from ML.config import DATA_DIR, MODELS_DIR


INPUT_PATH = DATA_DIR / "validation_v2.csv"

MODEL_PATH = (
    Path("models")
    / "isolation_forest_v2_candidate.pkl"
)

OUTPUT_PATH = (
    MODELS_DIR
    / "risk_policy_v2_candidate.metadata.json"
)

MAX_BLOCK_FPR = 0.01
MAX_FLAGGED_FPR = 0.10


def calculate_metrics(labels, predictions):

    labels = np.asarray(labels)
    predictions = np.asarray(predictions)

    tp = int(np.sum((labels == 1) & predictions))
    fp = int(np.sum((labels == 0) & predictions))

    tn = int(np.sum((labels == 0) & ~predictions))
    fn = int(np.sum((labels == 1) & ~predictions))

    precision = (
        tp / (tp + fp)
        if tp + fp > 0 else 0.0
    )

    recall = (
        tp / (tp + fn)
        if tp + fn > 0 else 0.0
    )

    fpr = (
        fp / (fp + tn)
        if fp + tn > 0 else 0.0
    )

    f1 = (
        2 * precision * recall
        / (precision + recall)
        if precision + recall > 0 else 0.0
    )

    return {
        "tp": tp,
        "fp": fp,
        "tn": tn,
        "fn": fn,
        "precision": precision,
        "recall": recall,
        "fpr": fpr,
        "f1": f1,
    }


def find_threshold(
    scores,
    labels,
    max_fpr,
    upper_bound=None,
):

    candidates = []

    # Evaluar umbrales entre 0 y 1
    # con incrementos de 0.001.
    for index in range(1001):

        threshold = index / 1000

        if (
            upper_bound is not None
            and threshold >= upper_bound
        ):
            continue

        predictions = scores >= threshold

        metrics = calculate_metrics(
            labels,
            predictions,
        )

        if metrics["fpr"] <= max_fpr:

            candidates.append({
                "threshold": threshold,
                **metrics,
            })

    if not candidates:
        raise ValueError(
            "No threshold satisfies the FPR constraint"
        )

    # Priorizar:
    # 1. Mayor detección de anomalías.
    # 2. Menor cantidad de falsos positivos.
    # 3. Umbral más conservador en caso de empate.
    best = max(
        candidates,
        key=lambda item: (
            item["tp"],
            -item["fp"],
            item["threshold"],
        ),
    )

    return best


def main():

    dataframe = pd.read_csv(INPUT_PATH)

    labels = dataframe["synthetic_label"].to_numpy()

    if set(labels) != {0, 1}:
        raise ValueError(
            "Validation must contain both classes"
        )

    # No permitir cargar modelos incompatibles.
    with warnings.catch_warnings():

        warnings.simplefilter(
            "error",
            InconsistentVersionWarning,
        )

        model = joblib.load(MODEL_PATH)

    if list(model.feature_names_in_) != FEATURE_ORDER:
        raise ValueError("Incompatible model features")

    decision_scores = model.decision_function(
        dataframe[FEATURE_ORDER]
    )

    scores = np.array([
        normalize_risk_score(score)
        for score in decision_scores
    ])

    dataframe["risk_score"] = scores

    # Referencia con los umbrales anteriores.
    baseline = calculate_metrics(
        labels,
        scores >= 0.7,
    )

    # Calibrar el bloqueo.
    block_result = find_threshold(
        scores,
        labels,
        MAX_BLOCK_FPR,
    )

    block_threshold = block_result["threshold"]

    # Calibrar REVIEW + BLOCK.
    review_result = find_threshold(
        scores,
        labels,
        MAX_FLAGGED_FPR,
        upper_bound=block_threshold,
    )

    review_threshold = review_result["threshold"]

    dataframe["decision"] = [
        resolve_decision(
            score,
            review_threshold,
            block_threshold,
        )
        for score in scores
    ]

    print("\nRisk score distribution")
    print("-----------------------")
    print(
        dataframe.groupby(
            "synthetic_label"
        )["risk_score"].describe()
    )

    print("\nPrevious BLOCK policy")
    print("---------------------")
    print(json.dumps(baseline, indent=2))

    print("\nCalibrated thresholds")
    print("---------------------")
    print("REVIEW:", review_threshold)
    print("BLOCK:", block_threshold)

    print("\nBLOCK metrics")
    print("-------------")
    print(json.dumps(block_result, indent=2))

    print("\nREVIEW + BLOCK metrics")
    print("----------------------")
    print(json.dumps(review_result, indent=2))

    print("\nDecision distribution")
    print("---------------------")
    print(dataframe["decision"].value_counts())

    print("\nDecisions by label")
    print("------------------")
    print(pd.crosstab(
        dataframe["synthetic_label"],
        dataframe["decision"],
    ))

    print("\nDecisions by anomaly type")
    print("-------------------------")
    print(pd.crosstab(
        dataframe["anomaly_type"],
        dataframe["decision"],
    ))

    policy = {
        "review_threshold": review_threshold,
        "block_threshold": block_threshold,
        "max_block_fpr": MAX_BLOCK_FPR,
        "max_flagged_fpr": MAX_FLAGGED_FPR,
        "calibration_records": len(dataframe),
        "model_sha256": hashlib.sha256(
            MODEL_PATH.read_bytes()
        ).hexdigest(),
        "score_transform": "clip(0.5 - decision_score, 0, 1)",
        "block_metrics": block_result,
        "flagged_metrics": review_result,
    }

    OUTPUT_PATH.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    with OUTPUT_PATH.open(
        "w",
        encoding="utf-8",
    ) as file:

        json.dump(
            policy,
            file,
            indent=2,
            allow_nan=False,
        )

    if block_result["tp"] == 0:
        print(
            "\nWARNING: No anomalies were blocked "
            "under the configured constraint."
        )

    print("\nRisk policy saved at:")
    print(OUTPUT_PATH)


if __name__ == "__main__":
    main()