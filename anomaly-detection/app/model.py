import joblib
import pandas as pd
from pathlib import Path

from app.feature_contract import FEATURE_ORDER
from app.rules import rule_based_score

MODEL_PATH = Path(__file__).resolve().parents[1] / "models" / "isolation_forest.pkl"

_model = None
if MODEL_PATH.exists():
    _model = joblib.load(MODEL_PATH)
    trained_features = list(getattr(_model, "feature_names_in_", []))
    if trained_features and trained_features != FEATURE_ORDER:
        raise RuntimeError(
            "El modelo fue entrenado con features incompatibles: "
            f"{trained_features}; se esperaban {FEATURE_ORDER}"
        )
    if getattr(_model, "n_features_in_", len(FEATURE_ORDER)) != len(FEATURE_ORDER):
        raise RuntimeError(
            "El modelo tiene una cantidad de features incompatible con el runtime"
        )


def model_status() -> dict:
    return {
        "loaded": _model is not None,
        "path": str(MODEL_PATH),
        "features": FEATURE_ORDER,
    }

def score(features: dict) -> tuple[float, list[str], str]:
    if _model is None:
        raw_score, reasons = rule_based_score(features)
    else:
        df = pd.DataFrame([{k: features[k] for k in FEATURE_ORDER}])
        # decision_function: valores negativos = más anómalo
        raw = -_model.decision_function(df)[0]
        raw_score = float(min(max((raw + 0.5), 0), 1))  # normalizado aprox a 0-1
        reasons = ["Score calculado por Isolation Forest"]

    if raw_score >= 0.7:
        decision = "BLOCK"
    elif raw_score >= 0.4:
        decision = "REVIEW"
    else:
        decision = "ALLOW"

    return raw_score, reasons, decision
