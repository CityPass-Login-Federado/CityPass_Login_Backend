from fastapi import FastAPI
from app.schemas import LoginAttemptFeatures, RiskScoreResponse
from app.features import build_features
from app.model import model_status, score_details
from datetime import datetime
from pathlib import Path

APP_VERSION = (Path(__file__).resolve().parents[1] / "VERSION").read_text(encoding="utf-8").strip()

app = FastAPI(title="CityPass Anomaly Detection", version=APP_VERSION)

@app.get("/health")
def health():
    return {"status": "ok", "model": model_status()}

@app.post("/score", response_model=RiskScoreResponse)
def score_login(attempt: LoginAttemptFeatures):

    ts = datetime.fromisoformat(
        attempt.timestamp
    )

    features = build_features(
        attempt.username,
        attempt.ip,
        attempt.user_agent,
        ts,
    )

    result = score_details(features)

    return RiskScoreResponse(**result)
