import math


def normalize_risk_score(decision_score: float) -> float:
    """
    Transforma el decision score de Isolation Forest
    en un indicador de riesgo entre 0 y 1.

    Un valor mayor representa mayor riesgo.
    No representa una probabilidad de fraude.
    """

    decision_score = float(decision_score)

    if not math.isfinite(decision_score):
        raise ValueError("Invalid decision score")

    risk_score = 0.5 - decision_score

    return min(max(risk_score, 0.0), 1.0)


def resolve_decision(
    risk_score: float,
    review_threshold: float,
    block_threshold: float,
) -> str:

    if not (
        0 <= review_threshold < block_threshold <= 1
    ):
        raise ValueError("Invalid risk thresholds")

    if not 0 <= risk_score <= 1:
        raise ValueError("Invalid risk score")

    if risk_score >= block_threshold:
        return "BLOCK"

    if risk_score >= review_threshold:
        return "REVIEW"

    return "ALLOW"

def has_block_context(features) -> bool:
    """
    Determina si existen suficientes señales
    contextuales para considerar un bloqueo por ML.
    """

    return (
        features["history_size"] >= 5
        and features["is_new_ip"] == 1
        and features["is_new_device"] == 1
        and features["recent_failures_15min"] >= 2
    )


def resolve_hybrid_decision(
    risk_score: float,
    features,
    review_threshold: float,
    block_threshold: float,
) -> str:

    decision = resolve_decision(
        risk_score,
        review_threshold,
        block_threshold,
    )

    if decision == "BLOCK":

        if not has_block_context(features):
            return "REVIEW"

    return decision