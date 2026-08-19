import joblib
import os
import pandas as pd
from app.rules import rule_based_score

MODEL_PATH = "models/isolation_forest.pkl"
FEATURE_ORDER = ["hour_of_day", "is_new_ip", "is_new_device", "recent_failures_15min"]

_model = None
if os.path.exists(MODEL_PATH):
    _model = joblib.load(MODEL_PATH)

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