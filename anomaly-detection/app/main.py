from fastapi import FastAPI
from app.schemas import LoginAttemptFeatures, RiskScoreResponse
from app.features import build_features
from app.model import score
from datetime import datetime

app = FastAPI(title="CityPass Anomaly Detection")

@app.get("/health")
def health():
    return {"status": "ok"}

@app.post("/score", response_model=RiskScoreResponse)
def score_login(attempt: LoginAttemptFeatures):
    ts = datetime.fromisoformat(attempt.timestamp)
    features = build_features(attempt.username, attempt.ip, attempt.user_agent, ts)
    risk_score, reasons, decision = score(features)
    return RiskScoreResponse(risk_score=risk_score, decision=decision, reasons=reasons)