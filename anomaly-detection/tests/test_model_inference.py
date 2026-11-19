import hashlib
import json
import os
import runpy
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from app import model as runtime_model
from app.feature_contract import FEATURE_ORDER


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

    def test_invalid_anomaly_mode_fails_startup(self):
        with patch.dict(os.environ, {"ANOMALY_MODE": "invalid"}):
            with self.assertRaisesRegex(
                RuntimeError,
                "Invalid ANOMALY_MODE",
            ):
                runpy.run_path(
                    str(Path(runtime_model.__file__)),
                    run_name="invalid_anomaly_mode",
                )

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

    def test_load_bundle_rejects_missing_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            model_path = base / "missing.pkl"
            policy_path = base / "missing.json"

            with self.assertRaisesRegex(RuntimeError, "ML model not found"):
                runtime_model.load_bundle(model_path, policy_path)

            model_path.write_bytes(b"model")
            with self.assertRaisesRegex(
                RuntimeError,
                "Risk policy not found",
            ):
                runtime_model.load_bundle(model_path, policy_path)

    def test_load_bundle_rejects_incompatible_policy_and_model(self):
        invalid_policies = [
            ("policy_type", {"policy_type": "hybrid_v3"}),
            ("feature_semantics", {"feature_semantics": "legacy"}),
            ("score_transform", {"score_transform": "raw"}),
            (
                "context_requirements",
                {"context_requirements": {"minimum_history": 0}},
            ),
            (
                "risk_thresholds",
                {"review_threshold": 0.7, "block_threshold": 0.6},
            ),
        ]

        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            model_path = base / "model.pkl"
            policy_path = base / "policy.json"
            model_path.write_bytes(b"model")

            valid_policy = json.loads(
                runtime_model.POLICY_PATH.read_text(encoding="utf-8")
            )
            valid_policy["model_sha256"] = hashlib.sha256(
                model_path.read_bytes()
            ).hexdigest()
            compatible_model = SimpleNamespace(
                feature_names_in_=FEATURE_ORDER,
                n_features_in_=len(FEATURE_ORDER),
            )

            for name, changes in invalid_policies:
                with self.subTest(policy=name):
                    policy = {**valid_policy, **changes}
                    policy_path.write_text(
                        json.dumps(policy),
                        encoding="utf-8",
                    )

                    with patch.object(
                        runtime_model.joblib,
                        "load",
                        return_value=compatible_model,
                    ):
                        with self.assertRaises(RuntimeError):
                            runtime_model.load_bundle(
                                model_path,
                                policy_path,
                            )

            policy_path.write_text(
                json.dumps(valid_policy),
                encoding="utf-8",
            )
            invalid_models = [
                SimpleNamespace(
                    feature_names_in_=["different"],
                    n_features_in_=len(FEATURE_ORDER),
                ),
                SimpleNamespace(
                    feature_names_in_=FEATURE_ORDER,
                    n_features_in_=len(FEATURE_ORDER) - 1,
                ),
            ]

            for model in invalid_models:
                with self.subTest(model=model):
                    with patch.object(
                        runtime_model.joblib,
                        "load",
                        return_value=model,
                    ):
                        with self.assertRaises(RuntimeError):
                            runtime_model.load_bundle(
                                model_path,
                                policy_path,
                            )


if __name__ == "__main__":
    unittest.main()