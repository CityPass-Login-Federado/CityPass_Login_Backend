import os
import unittest
from datetime import datetime, timezone
from unittest.mock import patch

os.environ.setdefault("ANOMALY_DB_URL", "sqlite:///:memory:")

from app import main
from app.schemas import LoginAttemptFeatures


class RuntimeApiTests(unittest.TestCase):
    def test_health_returns_model_status(self):
        status = {"loaded": True, "version": "v4"}

        with patch.object(main, "model_status", return_value=status):
            self.assertEqual(
                main.health(),
                {"status": "ok", "model": status},
            )

    def test_score_login_builds_features_and_returns_full_assessment(self):
        features = {
            "hour_of_day": 10,
            "is_new_ip": 1,
            "is_new_device": 0,
            "recent_failures_15min": 2,
            "history_size": 10,
        }
        assessment = {
            "risk_score": 0.6,
            "decision": "REVIEW",
            "reasons": ["Fallos recientes: 2"],
            "proposed_decision": "REVIEW",
            "model_version": "v4",
            "model_sha256": "model-hash",
            "policy_sha256": "policy-hash",
            "feature_semantics": "successful_history_v1",
            "mode": "ENFORCE",
            "features": features,
        }
        attempt = LoginAttemptFeatures(
            username="alice",
            ip="192.0.2.1",
            user_agent="browser",
            timestamp="2026-01-01T10:00:00+00:00",
            success=True,
        )

        with (
            patch.object(main, "build_features", return_value=features) as build,
            patch.object(main, "score_details", return_value=assessment) as score,
        ):
            result = main.score_login(attempt)

        build.assert_called_once_with(
            "alice",
            "192.0.2.1",
            "browser",
            datetime(2026, 1, 1, 10, 0, tzinfo=timezone.utc),
        )
        score.assert_called_once_with(features)
        self.assertEqual(result.model_dump(), assessment)


if __name__ == "__main__":
    unittest.main()
