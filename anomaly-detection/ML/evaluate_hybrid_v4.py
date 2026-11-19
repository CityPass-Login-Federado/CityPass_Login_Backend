import hashlib
import json
import warnings

from pathlib import Path

import joblib
import numpy as np
import pandas as pd

from sklearn.exceptions import InconsistentVersionWarning

from ML.config import DATA_DIR, MODELS_DIR
from ML.calibrate import calculate_metrics
from ML.calibrate_hybrid_v4 import print_results

from app.feature_contract import FEATURE_ORDER

from app.risk_policy import (
    normalize_risk_score,
    resolve_hybrid_decision,
    has_block_context,
)


MODEL_PATH = (
    Path(__file__).resolve().parents[1]
    / "models"
    / "isolation_forest_v4_candidate.pkl"
)

POLICY_PATH = (
    MODELS_DIR
    / "risk_policy_v4_candidate.metadata.json"
)

INPUT_PATH = DATA_DIR / "test_v4.csv"

OUTPUT_PATH = (
    DATA_DIR / "evaluation_results_v4.csv"
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
    
    if policy.get("feature_semantics") != "successful_history_v1":
        raise ValueError(
            "Incompatible feature semantics"
    )
    if model_hash != policy["model_sha256"]:
        raise ValueError("Model hash mismatch")

    if policy["policy_type"] != "hybrid_v4":
        raise ValueError("Invalid policy type")

    if policy["score_transform"] != (
        "clip(0.5 - decision_score, 0, 1)"
    ):
        raise ValueError("Invalid score transformation")

    expected_context = {
        "minimum_history": 5,
        "require_new_ip": True,
        "require_new_device": True,
        "minimum_recent_failures": 2,
    }

    if policy["context_requirements"] != expected_context:
        raise ValueError("Incompatible context requirements")

    with warnings.catch_warnings():

        warnings.simplefilter(
            "error",
            InconsistentVersionWarning,
        )

        model = joblib.load(MODEL_PATH)

    if list(model.feature_names_in_) != FEATURE_ORDER:
        raise ValueError("Incompatible model features")

    return model, policy


def evaluate_dataframe(dataframe, model, policy):

    dataframe = dataframe.copy()

    features = dataframe[FEATURE_ORDER]

    decision_scores = model.decision_function(features)

    scores = np.array([
        normalize_risk_score(score)
        for score in decision_scores
    ])

    dataframe["decision_score"] = decision_scores
    dataframe["risk_score"] = scores

    dataframe["context_eligible"] = [
        has_block_context(row)
        for _, row in features.iterrows()
    ]

    dataframe["decision"] = [
        resolve_hybrid_decision(
            score,
            row,
            policy["review_threshold"],
            policy["block_threshold"],
        )
        for score, (_, row) in zip(
            scores,
            features.iterrows(),
        )
    ]

    return dataframe


def main():

    dataframe = pd.read_csv(INPUT_PATH)

    labels = dataframe["synthetic_label"].to_numpy()

    if set(labels) != {0, 1}:
        raise ValueError("Test must contain both classes")

    model, policy = load_model_and_policy()

    results = evaluate_dataframe(
        dataframe,
        model,
        policy,
    )

    print("\nINDEPENDENT TEST V4")
    print("-------------------")

    print("Records:", len(results))
    print("REVIEW:", policy["review_threshold"])
    print("BLOCK:", policy["block_threshold"])

    hybrid_metrics, flagged_metrics = print_results(
        "HYBRID POLICY - TEST",
        results,
        results["decision"],
    )

    # Comparar contra una regla contextual
    # que no utiliza Isolation Forest.
    rules_metrics = calculate_metrics(
        labels,
        results["context_eligible"].to_numpy(),
    )

    print("\nCONTEXT-ONLY BLOCK METRICS")
    print("--------------------------")

    print(json.dumps(
        rules_metrics,
        indent=2,
    ))

    print("\nISOLATION FOREST CONTRIBUTION")
    print("-----------------------------")

    prevented_blocks = (
        results["context_eligible"]
        & results["decision"].ne("BLOCK")
    )

    print(pd.crosstab(
        results["synthetic_label"],
        prevented_blocks,
    ))

    print("\nTARGET VERIFICATION")
    print("-------------------")

    print(
        "BLOCK FPR:",
        "PASS"
        if hybrid_metrics["fpr"]
        <= policy["max_block_fpr"]
        else "FAIL",
    )

    print(
        "FLAGGED FPR:",
        "PASS"
        if flagged_metrics["fpr"]
        <= policy["max_flagged_fpr"]
        else "FAIL",
    )

    print("\nCOLD START DECISIONS")
    print("--------------------")

    cold_start = results[
        results["history_size"] == 0
    ]

    print(pd.crosstab(
        cold_start["synthetic_label"],
        cold_start["decision"],
    ))

    print("\nFALSE BLOCKS")
    print("------------")

    false_blocks = results[
        (results["synthetic_label"] == 0)
        & results["decision"].eq("BLOCK")
    ]

    print(
        false_blocks[
            FEATURE_ORDER
            + ["scenario", "risk_score"]
        ].to_string(index=False)
    )

    results.to_csv(
        OUTPUT_PATH,
        index=False,
    )

    print("\nSaved at:")
    print(OUTPUT_PATH)


if __name__ == "__main__":
    main()