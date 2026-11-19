import hashlib
import json
import warnings

from pathlib import Path

import joblib
import numpy as np
import pandas as pd

from sklearn.exceptions import InconsistentVersionWarning

from ML.config import DATA_DIR, MODELS_DIR

from ML.calibrate import (
    calculate_metrics,
    find_threshold,
)

from app.feature_contract import FEATURE_ORDER

from app.risk_policy import (
    normalize_risk_score,
    resolve_decision,
    resolve_hybrid_decision,
    has_block_context,
)


MODEL_PATH = (
    Path(__file__).resolve().parents[1]
    / "models"
    / "isolation_forest_v4_candidate.pkl"
)

INPUT_PATH = DATA_DIR / "validation_v4.csv"

OUTPUT_PATH = (
    MODELS_DIR
    / "risk_policy_v4_candidate.metadata.json"
)

MAX_BLOCK_FPR = 0.01
MAX_FLAGGED_FPR = 0.10


def find_hybrid_block_threshold(
    scores,
    labels,
    context_mask,
    review_threshold,
):

    candidates = []

    for index in range(1001):

        threshold = index / 1000

        if threshold <= review_threshold:
            continue

        predictions = (
            (scores >= threshold)
            & context_mask
        )

        metrics = calculate_metrics(
            labels,
            predictions,
        )

        if metrics["fpr"] <= MAX_BLOCK_FPR:

            candidates.append({
                "threshold": threshold,
                **metrics,
            })

    if not candidates:
        raise ValueError(
            "No valid hybrid threshold found"
        )

    return max(
        candidates,
        key=lambda item: (
            item["tp"],
            -item["fp"],
            item["threshold"],
        ),
    )


def print_results(title, dataframe, decisions):

    results = dataframe.copy()

    results["decision"] = decisions

    labels = results["synthetic_label"].to_numpy()

    blocked = (
        results["decision"] == "BLOCK"
    ).to_numpy()

    flagged = (
        results["decision"] != "ALLOW"
    ).to_numpy()

    block_metrics = calculate_metrics(
        labels,
        blocked,
    )

    flagged_metrics = calculate_metrics(
        labels,
        flagged,
    )

    print(f"\n{title}")
    print("=" * 45)

    print("\nBLOCK metrics:")
    print(json.dumps(
        block_metrics,
        indent=2,
    ))

    print("\nREVIEW + BLOCK metrics:")
    print(json.dumps(
        flagged_metrics,
        indent=2,
    ))

    print("\nDecisions by label:")
    print(pd.crosstab(
        results["synthetic_label"],
        results["decision"],
    ).reindex(
        columns=["ALLOW", "REVIEW", "BLOCK"],
        fill_value=0,
    ))

    print("\nDecisions by scenario:")
    print(pd.crosstab(
        results["scenario"],
        results["decision"],
    ).reindex(
        columns=["ALLOW", "REVIEW", "BLOCK"],
        fill_value=0,
    ))

    return block_metrics, flagged_metrics


def main():

    dataframe = pd.read_csv(INPUT_PATH)

    labels = dataframe[
        "synthetic_label"
    ].to_numpy()

    if set(labels) != {0, 1}:
        raise ValueError(
            "Validation must contain both classes"
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

    features = dataframe[FEATURE_ORDER]

    scores = np.array([
        normalize_risk_score(score)
        for score in model.decision_function(features)
    ])

    # ---------------------------------------
    # POLÍTICA BASADA ÚNICAMENTE EN ML
    # ---------------------------------------

    pure_block = find_threshold(
        scores,
        labels,
        MAX_BLOCK_FPR,
    )

    pure_review = find_threshold(
        scores,
        labels,
        MAX_FLAGGED_FPR,
        upper_bound=pure_block["threshold"],
    )

    pure_decisions = [
        resolve_decision(
            score,
            pure_review["threshold"],
            pure_block["threshold"],
        )
        for score in scores
    ]

    pure_metrics, pure_flagged = print_results(
        "PURE ML POLICY",
        dataframe,
        pure_decisions,
    )

    # ---------------------------------------
    # POLÍTICA HÍBRIDA
    # ---------------------------------------

    context_mask = np.array([
        has_block_context(row)
        for _, row in features.iterrows()
    ])
    
    context_only_metrics = calculate_metrics(
        labels,
        context_mask,
    )

    print("\nCONTEXT-ONLY BLOCK METRICS")
    print("--------------------------")

    print(json.dumps(
        context_only_metrics,
        indent=2,
    ))
    review_threshold = pure_review["threshold"]

    hybrid_block = find_hybrid_block_threshold(
        scores,
        labels,
        context_mask,
        review_threshold,
    )

    block_threshold = hybrid_block["threshold"]

    hybrid_decisions = [
        resolve_hybrid_decision(
            score,
            row,
            review_threshold,
            block_threshold,
        )
        for score, (_, row) in zip(
            scores,
            features.iterrows(),
        )
    ]

    hybrid_metrics, hybrid_flagged = print_results(
        "HYBRID ML POLICY",
        dataframe,
        hybrid_decisions,
    )

    print("\nHybrid thresholds")
    print("-----------------")
    print("REVIEW:", review_threshold)
    print("BLOCK:", block_threshold)

    print("\nContext eligibility")
    print("-------------------")

    print(pd.crosstab(
        dataframe["scenario"],
        context_mask,
    ))

    # ---------------------------------------
    # COMPARACIÓN FINAL
    # ---------------------------------------

    comparison = pd.DataFrame([
        {
            "policy": "Pure ML",
            "precision": pure_metrics["precision"],
            "recall": pure_metrics["recall"],
            "fpr": pure_metrics["fpr"],
            "f1": pure_metrics["f1"],
        },
        {
            "policy": "Hybrid ML",
            "precision": hybrid_metrics["precision"],
            "recall": hybrid_metrics["recall"],
            "fpr": hybrid_metrics["fpr"],
            "f1": hybrid_metrics["f1"],
        },
    ])

    print("\nFINAL COMPARISON")
    print("----------------")

    print(
        comparison.round(4)
        .to_string(index=False)
    )

    policy = {
        "policy_type": "hybrid_v4",
        "review_threshold": review_threshold,
        "block_threshold": block_threshold,
        "feature_semantics": "successful_history_v1",
        "max_block_fpr": MAX_BLOCK_FPR,
        "max_flagged_fpr": MAX_FLAGGED_FPR,
        "model_sha256": hashlib.sha256(
            MODEL_PATH.read_bytes()
        ).hexdigest(),
        "score_transform": (
            "clip(0.5 - decision_score, 0, 1)"
        ),
        "context_requirements": {
            "minimum_history": 5,
            "require_new_ip": True,
            "require_new_device": True,
            "minimum_recent_failures": 2,
        },
        "block_metrics": hybrid_metrics,
        "flagged_metrics": hybrid_flagged,
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

    print("\nPolicy saved at:")
    print(OUTPUT_PATH)


if __name__ == "__main__":
    main()