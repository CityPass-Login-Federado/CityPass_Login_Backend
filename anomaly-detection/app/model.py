import hashlib
import json
import logging
import os
import warnings

from pathlib import Path

import joblib
import pandas as pd

from sklearn.exceptions import InconsistentVersionWarning

from app.feature_contract import FEATURE_ORDER

from app.risk_policy import (
    normalize_risk_score,
    resolve_hybrid_decision,
)


logger = logging.getLogger("uvicorn.error")

BASE_DIR = Path(__file__).resolve().parents[1]

# En desarrollo utilizamos los artefactos versionados.
# En Docker utilizamos los archivos copiados al runtime.
SOURCE_MODELS_DIR = BASE_DIR / "ML" / "models"

MODELS_DIR = (
    SOURCE_MODELS_DIR
    if SOURCE_MODELS_DIR.is_dir()
    else BASE_DIR / "models"
)

MODEL_PATH = MODELS_DIR / "isolation_forest_v4.pkl"

POLICY_PATH = MODELS_DIR / "risk_policy_v4.json"

MODEL_VERSION = "v4"

ANOMALY_MODE = os.getenv(
    "ANOMALY_MODE",
    "SHADOW",
).strip().upper()

if ANOMALY_MODE not in {"SHADOW", "ENFORCE"}:
    raise RuntimeError("Invalid ANOMALY_MODE")


def load_bundle(model_path, policy_path):

    if not model_path.is_file():
        raise RuntimeError("ML model not found")

    if not policy_path.is_file():
        raise RuntimeError("Risk policy not found")

    with policy_path.open(
        "r",
        encoding="utf-8",
    ) as file:

        policy = json.load(file)

    model_hash = hashlib.sha256(
        model_path.read_bytes()
    ).hexdigest()

    if model_hash != policy["model_sha256"]:
        raise RuntimeError("Model hash mismatch")

    if policy["policy_type"] != "hybrid_v4":
        raise RuntimeError("Invalid policy type")

    if policy["feature_semantics"] != (
        "successful_history_v1"
    ):
        raise RuntimeError("Invalid feature semantics")

    if policy["score_transform"] != (
        "clip(0.5 - decision_score, 0, 1)"
    ):
        raise RuntimeError("Invalid score transformation")

    expected_context = {
        "minimum_history": 5,
        "require_new_ip": True,
        "require_new_device": True,
        "minimum_recent_failures": 2,
    }

    if policy["context_requirements"] != expected_context:
        raise RuntimeError("Invalid context requirements")

    review = policy["review_threshold"]
    block = policy["block_threshold"]

    if not 0 <= review < block <= 1:
        raise RuntimeError("Invalid risk thresholds")

    with warnings.catch_warnings():

        warnings.simplefilter(
            "error",
            InconsistentVersionWarning,
        )

        model = joblib.load(model_path)

    if list(model.feature_names_in_) != FEATURE_ORDER:
        raise RuntimeError("Invalid model features")

    if model.n_features_in_ != len(FEATURE_ORDER):
        raise RuntimeError("Invalid number of features")

    return model, policy


_model, _policy = load_bundle(
    MODEL_PATH,
    POLICY_PATH,
)

POLICY_SHA256 = hashlib.sha256(
    POLICY_PATH.read_bytes()
).hexdigest()

def model_status() -> dict:

    return {
        "loaded": _model is not None,
        "version": MODEL_VERSION,
        "mode": ANOMALY_MODE,
        "features": FEATURE_ORDER,
        "model_sha256": _policy["model_sha256"],
        "feature_semantics": _policy["feature_semantics"],
        "review_threshold": _policy["review_threshold"],
        "block_threshold": _policy["block_threshold"],
    }


def score_details(features: dict) -> dict:

    dataframe = pd.DataFrame(
        [{
            key: features[key]
            for key in FEATURE_ORDER
        }],
        columns=FEATURE_ORDER,
    )

    decision_score = _model.decision_function(
        dataframe
    )[0]

    risk_score = normalize_risk_score(
        decision_score
    )

    proposed_decision = resolve_hybrid_decision(
        risk_score,
        features,
        _policy["review_threshold"],
        _policy["block_threshold"],
    )

    reasons = [
        "Score calculado por Isolation Forest V4"
    ]

    if features["is_new_ip"]:
        reasons.append(
            "IP sin autenticaciones exitosas previas"
        )

    if features["is_new_device"]:
        reasons.append(
            "User-agent sin autenticaciones exitosas previas"
        )

    if features["recent_failures_15min"] > 0:
        reasons.append(
            "Fallos recientes: "
            f"{features['recent_failures_15min']}"
        )

    if features["history_size"] < 5:
        reasons.append(
            "Historial insuficiente"
        )

    decision = proposed_decision

    # En SHADOW registramos los bloqueos propuestos,
    # pero no impedimos el acceso por decisión ML.
    if (
        ANOMALY_MODE == "SHADOW"
        and proposed_decision == "BLOCK"
    ):

        decision = "REVIEW"

        reasons.append(
            "SHADOW: bloqueo propuesto no aplicado"
        )

    logger.info(
        "ML_EVALUATION model=%s mode=%s "
        "risk=%.6f proposed=%s effective=%s",
        MODEL_VERSION,
        ANOMALY_MODE,
        risk_score,
        proposed_decision,
        decision,
    )

    return {
        "risk_score": risk_score,
        "reasons": reasons,
        "decision": decision,
        "proposed_decision": proposed_decision,
        "model_version": MODEL_VERSION,
        "model_sha256": _policy["model_sha256"],
        "policy_sha256": POLICY_SHA256,
        "feature_semantics": _policy["feature_semantics"],
        "mode": ANOMALY_MODE,
        "features": {
            key: int(features[key])
            for key in FEATURE_ORDER
        },
    }
def score(features: dict) -> tuple[float, list[str], str]:

    result = score_details(features)

    return (
        result["risk_score"],
        result["reasons"],
        result["decision"],
    )