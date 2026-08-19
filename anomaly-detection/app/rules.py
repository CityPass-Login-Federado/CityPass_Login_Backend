def rule_based_score(features: dict) -> tuple[float, list[str]]:
    score = 0.0
    reasons = []

    if features["is_new_ip"]:
        score += 0.3
        reasons.append("IP nunca vista para este usuario")
    if features["is_new_device"]:
        score += 0.2
        reasons.append("Dispositivo/user-agent nunca visto")
    if features["recent_failures_15min"] >= 3:
        score += 0.4
        reasons.append("Múltiples fallos recientes")
    if features["hour_of_day"] < 5 or features["hour_of_day"] > 23:
        score += 0.1
        reasons.append("Horario inusual")

    return min(score, 1.0), reasons