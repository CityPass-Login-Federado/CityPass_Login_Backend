from pydantic import BaseModel
from typing import Literal

class LoginAttemptFeatures(BaseModel):
    username: str
    ip: str
    user_agent: str
    timestamp: str          # ISO 8601
    success: bool

class RiskScoreResponse(BaseModel):
    risk_score: float       # 0.0 (normal) a 1.0 (muy anómalo)
    decision: Literal["ALLOW", "REVIEW", "BLOCK"]
    reasons: list[str]      # para auditoría/logging