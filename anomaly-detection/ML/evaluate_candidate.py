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

from ML.calibrate import calculate_metrics
from ML.preprocess import coerce_boolean
from ML.config import DATA_DIR, MODELS_DIR


INPUT_PATH = DATA_DIR / "test_v2.csv"

MODEL_PATH = (
    Path(__file__).resolve().parents[1]
    / "models"
    / "isolation_forest_v2_candidate.pkl"
)

POLICY_PATH = (
    MODELS_DIR
    / "risk_policy_v2_candidate.metadata.json"
)

OUTPUT_PATH = (
    DATA_DIR
    / "evaluation_results_v2.csv"
)


def load_model_and_policy():

    with POLICY_PATH.open(
        "r",
        encoding="utf-8",
    ) as file:

        policy = json.load(file)

    model_hash = hashlib.sha256(
        MODEL_PATH.read_bytes()
    ).hexdigest()

    if model_hash != policy["model_sha256"]:
        raise ValueError(
            "Risk policy belongs to a different model"
        )

    if policy["score_transform"] != (
        "clip(0.5 - decision_score, 0, 1)"
    ):
        raise ValueError(
            "Incompatible score transformation"
        )

    with warnings.catch_warnings():

        warnings.simplefilter(
            "error",
            InconsistentVersionWarning,
        )

        model = joblib.load(MODEL_PATH)

    if list(model.feature_names_in_) != FEATURE_ORDER:
        raise ValueError(
            "Incompatible model features"
        )

    return model, policy


def print_metrics(title, metrics):

    print(f"\n{title}")
    print("-" * 40)

    for key, value in metrics.items():

        if isinstance(value, float):
            print(f"{key}: {value:.4f}")

        else:
            print(f"{key}: {value}")


def main():

    dataframe = pd.read_csv(INPUT_PATH)

    # Verificar que todos los eventos evaluados
    # correspondan a la capa de detección ML.
    if not dataframe["ml_eligible"].map(
        coerce_boolean
    ).all():

        raise ValueError(
            "Test contains events not eligible for ML"
        )

    labels = dataframe[
        "synthetic_label"
    ].to_numpy()

    if set(labels) != {0, 1}:
        raise ValueError(
            "Test must contain both classes"
        )

    model, policy = load_model_and_policy()

    review_threshold = policy[
        "review_threshold"
    ]

    block_threshold = policy[
        "block_threshold"
    ]

    decision_scores = model.decision_function(
        dataframe[FEATURE_ORDER]
    )

    scores = np.array([
        normalize_risk_score(score)
        for score in decision_scores
    ])

    dataframe["decision_score"] = decision_scores
    dataframe["risk_score"] = scores

    dataframe["decision"] = [
        resolve_decision(
            score,
            review_threshold,
            block_threshold,
        )
        for score in scores
    ]

    # Evaluar la política anterior sobre
    # el mismo modelo y conjunto de datos.
    baseline_block = calculate_metrics(
        labels,
        scores >= 0.7,
    )

    baseline_flagged = calculate_metrics(
        labels,
        scores >= 0.4,
    )

    # Evaluar la política calibrada.
    block_metrics = calculate_metrics(
        labels,
        scores >= block_threshold,
    )

    flagged_metrics = calculate_metrics(
        labels,
        scores >= review_threshold,
    )

    # Evaluación binaria propia de Isolation Forest.
    model_metrics = calculate_metrics(
        labels,
        model.predict(
            dataframe[FEATURE_ORDER]
        ) == -1,
    )

    print("\nIndependent candidate evaluation")
    print("--------------------------------")
    print("Test records:", len(dataframe))
    print("REVIEW threshold:", review_threshold)
    print("BLOCK threshold:", block_threshold)

    print("\nModel SHA-256 verified:")
    print(policy["model_sha256"])

    print_metrics(
        "ISOLATION FOREST METRICS",
        model_metrics,
    )

    print_metrics(
        "PREVIOUS BLOCK POLICY",
        baseline_block,
    )

    print_metrics(
        "PREVIOUS REVIEW + BLOCK POLICY",
        baseline_flagged,
    )

    print_metrics(
        "CALIBRATED BLOCK POLICY",
        block_metrics,
    )

    print_metrics(
        "CALIBRATED REVIEW + BLOCK POLICY",
        flagged_metrics,
    )

    print("\nDecision distribution")
    print("---------------------")

    print(
        dataframe["decision"].value_counts()
    )

    print("\nDecisions by label")
    print("------------------")

    print(
        pd.crosstab(
            dataframe["synthetic_label"],
            dataframe["decision"],
        ).reindex(
            columns=["ALLOW", "REVIEW", "BLOCK"],
            fill_value=0,
        )
    )

    print("\nDecisions by anomaly type")
    print("-------------------------")

    print(
        pd.crosstab(
            dataframe["anomaly_type"],
            dataframe["decision"],
        ).reindex(
            columns=["ALLOW", "REVIEW", "BLOCK"],
            fill_value=0,
        )
    )

    print("\nTarget verification")
    print("-------------------")

    block_target = (
        block_metrics["fpr"]
        <= policy["max_block_fpr"]
    )

    flagged_target = (
        flagged_metrics["fpr"]
        <= policy["max_flagged_fpr"]
    )

    print(
        "BLOCK FPR target:",
        "PASS" if block_target else "FAIL",
    )

    print(
        "REVIEW + BLOCK FPR target:",
        "PASS" if flagged_target else "FAIL",
    )

    print("\nCold start records:")
    print(
        int(
            dataframe["history_size"].eq(0).sum()
        )
    )

    print("\nFalse block examples:")
    print("---------------------")

    false_blocks = dataframe[
        (dataframe["synthetic_label"] == 0)
        & (dataframe["decision"] == "BLOCK")
    ]

    print(
        false_blocks[
            FEATURE_ORDER + ["risk_score"]
        ].head(10).to_string(index=False)
    )

    dataframe.to_csv(
        OUTPUT_PATH,
        index=False,
    )

    print("\nEvaluation saved at:")
    print(OUTPUT_PATH)


if __name__ == "__main__":
    main()