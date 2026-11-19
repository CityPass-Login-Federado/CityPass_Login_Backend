from pydantic import BaseModel
from typing import Literal

class LoginAttemptFeatures(BaseModel):
    username: str
    ip: str
    user_agent: str
    timestamp: str          # ISO 8601
    success: bool

class RiskScoreResponse(BaseModel):

    risk_score: float

    decision: Literal[
        "ALLOW", "REVIEW", "BLOCK"
    ]

    reasons: list[str]

    proposed_decision: Literal[
        "ALLOW", "REVIEW", "BLOCK"
    ]

    model_version: str

    model_sha256: str

    policy_sha256: str

    feature_semantics: str

    mode: Literal["SHADOW", "ENFORCE"]

    features: dict[str, int]