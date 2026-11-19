import json
import tempfile
import unittest

from pathlib import Path
from unittest.mock import patch

from app import model as runtime_model


class ModelInferenceTests(unittest.TestCase):

    def setUp(self):

        # Caso observado en Test V4.
        # Su score supera el umbral de bloqueo.
        self.high_risk = {
            "hour_of_day": 12,
            "is_new_ip": 1,
            "is_new_device": 1,
            "recent_failures_15min": 3,
            "history_size": 200,
        }

        self.cold_start = {
            "hour_of_day": 15,
            "is_new_ip": 1,
            "is_new_device": 1,
            "recent_failures_15min": 0,
            "history_size": 0,
        }

    def test_model_loaded(self):

        status = runtime_model.model_status()

        self.assertTrue(status["loaded"])
        self.assertEqual(status["version"], "v4")

    def test_calibrated_thresholds(self):

        status = runtime_model.model_status()

        self.assertAlmostEqual(
            status["review_threshold"],
            0.479,
        )

        self.assertAlmostEqual(
            status["block_threshold"],
            0.543,
        )

    def test_shadow_does_not_block(self):

        with patch.object(
            runtime_model,
            "ANOMALY_MODE",
            "SHADOW",
        ):

            risk, reasons, decision = (
                runtime_model.score(self.high_risk)
            )

        self.assertGreaterEqual(risk, 0.543)
        self.assertEqual(decision, "REVIEW")

        self.assertTrue(
            any("SHADOW" in reason for reason in reasons)
        )

    def test_enforce_applies_block(self):

        with patch.object(
            runtime_model,
            "ANOMALY_MODE",
            "ENFORCE",
        ):

            risk, reasons, decision = (
                runtime_model.score(self.high_risk)
            )

        self.assertGreaterEqual(risk, 0.543)
        self.assertEqual(decision, "BLOCK")

    def test_cold_start_not_blocked(self):

        with patch.object(
            runtime_model,
            "ANOMALY_MODE",
            "ENFORCE",
        ):

            _, _, decision = runtime_model.score(
                self.cold_start
            )

        self.assertNotEqual(decision, "BLOCK")

    def test_risk_score_matches_evaluation(self):

        risk, _, _ = runtime_model.score(
            self.high_risk
        )

        self.assertAlmostEqual(
            risk,
            0.561129,
            places=5,
        )

    def test_incorrect_model_hash_is_rejected(self):

        with tempfile.TemporaryDirectory() as directory:

            policy_path = (
                Path(directory) / "invalid_policy.json"
            )

            policy = json.loads(
                runtime_model.POLICY_PATH.read_text(
                    encoding="utf-8"
                )
            )

            policy["model_sha256"] = "invalid"

            policy_path.write_text(
                json.dumps(policy),
                encoding="utf-8",
            )

            with self.assertRaises(RuntimeError):

                runtime_model.load_bundle(
                    runtime_model.MODEL_PATH,
                    policy_path,
                )


if __name__ == "__main__":
    unittest.main()