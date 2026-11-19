import contextlib
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import pandas as pd
import numpy as np

from ML import calibrate, calibrate_hybrid_v4, evaluate_hybrid_v4, generate_dataset_v4
from ML import prepare_dataset_v4, stress_test_v4
from app.feature_contract import FEATURE_ORDER


class FakeModel:
    feature_names_in_ = FEATURE_ORDER

    def __init__(self, decision_scores):
        self.decision_scores = np.asarray(decision_scores)

    def decision_function(self, dataframe):
        self.received_features = list(dataframe.columns)
        return self.decision_scores[:len(dataframe)]


def evaluation_fixture():
    rows = [
        {
            "synthetic_label": 1,
            "anomaly_type": "multiple_changes",
            "scenario": "anomaly-a",
            "hour_of_day": 12,
            "is_new_ip": 1,
            "is_new_device": 1,
            "recent_failures_15min": 2,
            "history_size": 20,
        },
        {
            "synthetic_label": 1,
            "anomaly_type": "new_ip",
            "scenario": "anomaly-b",
            "hour_of_day": 12,
            "is_new_ip": 1,
            "is_new_device": 1,
            "recent_failures_15min": 2,
            "history_size": 20,
        },
        {
            "synthetic_label": 0,
            "anomaly_type": "normal",
            "scenario": "normal-known-device",
            "hour_of_day": 12,
            "is_new_ip": 0,
            "is_new_device": 0,
            "recent_failures_15min": 0,
            "history_size": 20,
        },
        {
            "synthetic_label": 0,
            "anomaly_type": "normal",
            "scenario": "normal-new-device",
            "hour_of_day": 12,
            "is_new_ip": 1,
            "is_new_device": 1,
            "recent_failures_15min": 0,
            "history_size": 20,
        },
    ]
    return pd.DataFrame(rows)


class V4DatasetTests(unittest.TestCase):
    def test_generator_creates_balanced_targets_and_unique_events(self):
        with tempfile.TemporaryDirectory() as directory:
            with (
                patch.object(generate_dataset_v4, "DATA_DIR", Path(directory)),
                contextlib.redirect_stdout(io.StringIO()),
            ):
                dataframe = generate_dataset_v4.generate_dataset("test")

            targets = dataframe[dataframe["evaluation_target"]]
            self.assertEqual(len(targets), 2000)
            self.assertEqual(targets["synthetic_label"].sum(), 100)
            self.assertEqual(
                targets.loc[
                    targets["synthetic_label"].eq(1),
                    "anomaly_type",
                ].value_counts().to_dict(),
                {
                    "new_ip": 20,
                    "new_device": 20,
                    "unusual_hour": 20,
                    "recent_failures": 20,
                    "multiple_changes": 20,
                },
            )
            self.assertTrue(dataframe["eventId"].is_unique)
            self.assertTrue(targets["ml_eligible"].all())
            self.assertTrue(
                (Path(directory) / "synthetic_events_v4_test.csv").is_file()
            )

    def test_prepare_filters_train_and_validation_rows(self):
        rows = [
            {
                "evaluation_target": "true",
                "ml_eligible": "true",
                "successful": "true",
                "synthetic_label": 0,
                "history_size": 5,
                "recent_failures_15min": 0,
                "scenario": "routine",
            },
            {
                "evaluation_target": "true",
                "ml_eligible": "true",
                "successful": "true",
                "synthetic_label": 1,
                "history_size": 20,
                "recent_failures_15min": 2,
                "scenario": "new_ip",
            },
            {
                "evaluation_target": "true",
                "ml_eligible": "false",
                "successful": "true",
                "synthetic_label": 1,
                "history_size": 20,
                "recent_failures_15min": 2,
                "scenario": "not-eligible",
            },
            {
                "evaluation_target": "true",
                "ml_eligible": "true",
                "successful": "false",
                "synthetic_label": 1,
                "history_size": 20,
                "recent_failures_15min": 2,
                "scenario": "failed-login",
            },
        ]

        with tempfile.TemporaryDirectory() as directory:
            data_dir = Path(directory)
            train_input = data_dir / "processed_features_v4_train.csv"
            pd.DataFrame(rows).to_csv(train_input, index=False)
            with (
                patch.object(prepare_dataset_v4, "DATA_DIR", data_dir),
                contextlib.redirect_stdout(io.StringIO()),
            ):
                prepare_dataset_v4.prepare_dataset("train")

            train = pd.read_csv(data_dir / "train_v4.csv")
            self.assertEqual(len(train), 1)
            self.assertEqual(train["synthetic_label"].tolist(), [0])

            validation_input = data_dir / "processed_features_v4_validation.csv"
            pd.DataFrame(rows[:2]).to_csv(validation_input, index=False)
            with (
                patch.object(prepare_dataset_v4, "DATA_DIR", data_dir),
                contextlib.redirect_stdout(io.StringIO()),
            ):
                prepare_dataset_v4.prepare_dataset("validation")

            validation = pd.read_csv(data_dir / "validation_v4.csv")
            self.assertEqual(validation["synthetic_label"].tolist(), [0, 1])


class V4EvaluationTests(unittest.TestCase):
    def test_hybrid_evaluation_applies_context_gate(self):
        class FakeModel:
            def decision_function(self, dataframe):
                self.received_features = list(dataframe.columns)
                return [-0.2, -0.15]

        model = FakeModel()
        dataframe = pd.DataFrame([
            {
                "hour_of_day": 12,
                "is_new_ip": 1,
                "is_new_device": 1,
                "recent_failures_15min": 2,
                "history_size": 20,
            },
            {
                "hour_of_day": 12,
                "is_new_ip": 1,
                "is_new_device": 0,
                "recent_failures_15min": 2,
                "history_size": 20,
            },
        ])
        policy = {"review_threshold": 0.45, "block_threshold": 0.55}

        result = evaluate_hybrid_v4.evaluate_dataframe(
            dataframe, model, policy
        )

        self.assertEqual(model.received_features, FEATURE_ORDER)
        self.assertEqual(result["decision"].tolist(), ["BLOCK", "REVIEW"])
        self.assertEqual(
            result["context_eligible"].tolist(),
            [True, False],
        )
        self.assertNotIn("decision", dataframe.columns)

    def test_hybrid_calibration_selects_highest_valid_threshold(self):
        result = calibrate_hybrid_v4.find_hybrid_block_threshold(
            scores=np.array([0.8, 0.65, 0.6, 0.2]),
            labels=[1, 1, 0, 0],
            context_mask=[True, True, False, True],
            review_threshold=0.45,
        )

        self.assertEqual(result["threshold"], 0.65)
        self.assertEqual(result["tp"], 2)
        self.assertEqual(result["fp"], 0)

    def test_hybrid_calibration_rejects_all_false_positive_candidates(self):
        with self.assertRaisesRegex(
            ValueError,
            "No valid hybrid threshold found",
        ):
            calibrate_hybrid_v4.find_hybrid_block_threshold(
                scores=np.array([1.0]),
                labels=[0],
                context_mask=[True],
                review_threshold=0.45,
            )

    def test_pure_threshold_calibration_rejects_unachievable_fpr(self):
        with self.assertRaisesRegex(
            ValueError,
            "No threshold satisfies the FPR constraint",
        ):
            calibrate.find_threshold(
                scores=np.array([1.0, 1.0]),
                labels=[0, 0],
                max_fpr=0.0,
            )

    def test_calibrators_reject_missing_classes_and_incompatible_models(self):
        single_class = evaluation_fixture()
        single_class["synthetic_label"] = 0
        compatible_labels = evaluation_fixture()
        incompatible_model = type(
            "IncompatibleModel",
            (),
            {"feature_names_in_": ["unexpected"]},
        )()

        for module in (calibrate, calibrate_hybrid_v4):
            with self.subTest(module=module.__name__, error="labels"):
                with (
                    patch.object(module, "INPUT_PATH", Path("unused.csv")),
                    patch.object(module.pd, "read_csv", return_value=single_class),
                    contextlib.redirect_stdout(io.StringIO()),
                ):
                    with self.assertRaisesRegex(
                        ValueError,
                        "contain both classes",
                    ):
                        module.main()

            with self.subTest(module=module.__name__, error="features"):
                with (
                    patch.object(module, "INPUT_PATH", Path("unused.csv")),
                    patch.object(module, "MODEL_PATH", Path("unused.pkl")),
                    patch.object(
                        module.pd,
                        "read_csv",
                        return_value=compatible_labels,
                    ),
                    patch.object(
                        module.joblib,
                        "load",
                        return_value=incompatible_model,
                    ),
                    contextlib.redirect_stdout(io.StringIO()),
                ):
                    with self.assertRaisesRegex(
                        ValueError,
                        "Incompatible model features",
                    ):
                        module.main()

    def test_evaluation_bundle_rejects_incompatible_policy_and_model(self):
        valid_policy = {
            "feature_semantics": "successful_history_v1",
            "policy_type": "hybrid_v4",
            "score_transform": "clip(0.5 - decision_score, 0, 1)",
            "context_requirements": {
                "minimum_history": 5,
                "require_new_ip": True,
                "require_new_device": True,
                "minimum_recent_failures": 2,
            },
        }

        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            model_path = base / "model.pkl"
            policy_path = base / "policy.json"
            model_path.write_bytes(b"candidate-model")
            valid_policy["model_sha256"] = hashlib.sha256(
                b"candidate-model"
            ).hexdigest()

            policies = [
                {"feature_semantics": "legacy"},
                {"model_sha256": "incorrect"},
                {"policy_type": "hybrid_v3"},
                {"score_transform": "raw"},
                {"context_requirements": {}},
            ]
            for changes in policies:
                policy_path.write_text(
                    json.dumps({**valid_policy, **changes}),
                    encoding="utf-8",
                )
                with self.subTest(policy=changes):
                    with (
                        patch.object(
                            evaluate_hybrid_v4,
                            "MODEL_PATH",
                            model_path,
                        ),
                        patch.object(
                            evaluate_hybrid_v4,
                            "POLICY_PATH",
                            policy_path,
                        ),
                    ):
                        with self.assertRaises(ValueError):
                            evaluate_hybrid_v4.load_model_and_policy()

            policy_path.write_text(
                json.dumps(valid_policy),
                encoding="utf-8",
            )
            with (
                patch.object(evaluate_hybrid_v4, "MODEL_PATH", model_path),
                patch.object(evaluate_hybrid_v4, "POLICY_PATH", policy_path),
                patch.object(
                    evaluate_hybrid_v4.joblib,
                    "load",
                    return_value=type(
                        "IncompatibleModel",
                        (),
                        {"feature_names_in_": ["unexpected"]},
                    )(),
                ),
            ):
                with self.assertRaisesRegex(
                    ValueError,
                    "Incompatible model features",
                ):
                    evaluate_hybrid_v4.load_model_and_policy()

    def test_evaluation_main_rejects_test_data_without_both_classes(self):
        with (
            patch.object(evaluate_hybrid_v4, "INPUT_PATH", Path("unused.csv")),
            patch.object(
                evaluate_hybrid_v4.pd,
                "read_csv",
                return_value=pd.DataFrame({"synthetic_label": [0, 0]}),
            ),
        ):
            with self.assertRaisesRegex(
                ValueError,
                "Test must contain both classes",
            ):
                evaluate_hybrid_v4.main()

    def test_stress_cases_cover_all_targeted_contexts(self):
        events = stress_test_v4.generate_stress_cases()
        targets = events[events["evaluation_target"]]

        self.assertEqual(len(targets), 8)
        self.assertEqual(set(targets["synthetic_label"]), {0, 1})
        self.assertEqual(targets["scenario"].nunique(), 8)
        self.assertEqual(events["username"].nunique(), 8)

    def test_calibration_main_writes_a_hashed_hybrid_policy(self):
        dataframe = evaluation_fixture()
        decision_scores = [-0.3, -0.2, 0.1, -0.1]

        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            input_path = base / "validation.csv"
            model_path = base / "model.pkl"
            output_path = base / "policy.json"
            dataframe.to_csv(input_path, index=False)
            model_path.write_bytes(b"candidate-model")

            with (
                patch.object(calibrate_hybrid_v4, "INPUT_PATH", input_path),
                patch.object(calibrate_hybrid_v4, "MODEL_PATH", model_path),
                patch.object(calibrate_hybrid_v4, "OUTPUT_PATH", output_path),
                patch.object(
                    calibrate_hybrid_v4.joblib,
                    "load",
                    return_value=FakeModel(decision_scores),
                ),
                contextlib.redirect_stdout(io.StringIO()),
            ):
                calibrate_hybrid_v4.main()

            policy = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(policy["policy_type"], "hybrid_v4")
            self.assertEqual(
                policy["model_sha256"],
                hashlib.sha256(b"candidate-model").hexdigest(),
            )
            self.assertGreater(
                policy["block_threshold"],
                policy["review_threshold"],
            )
            self.assertEqual(policy["block_metrics"]["tp"], 2)

    def test_calibration_main_writes_thresholds_and_metrics(self):
        dataframe = evaluation_fixture()
        decision_scores = [-0.3, -0.2, 0.1, -0.1]

        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            input_path = base / "validation.csv"
            model_path = base / "model.pkl"
            output_path = base / "policy.json"
            dataframe.to_csv(input_path, index=False)
            model_path.write_bytes(b"candidate-model")

            with (
                patch.object(calibrate, "INPUT_PATH", input_path),
                patch.object(calibrate, "MODEL_PATH", model_path),
                patch.object(calibrate, "OUTPUT_PATH", output_path),
                patch.object(
                    calibrate.joblib,
                    "load",
                    return_value=FakeModel(decision_scores),
                ),
                contextlib.redirect_stdout(io.StringIO()),
            ):
                calibrate.main()

            policy = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(policy["calibration_records"], 4)
            self.assertGreater(
                policy["block_threshold"],
                policy["review_threshold"],
            )
            self.assertEqual(policy["block_metrics"]["tp"], 2)

    def test_evaluation_main_validates_bundle_and_writes_results(self):
        dataframe = evaluation_fixture()
        decision_scores = [-0.3, -0.2, 0.1, -0.1]
        context_requirements = {
            "minimum_history": 5,
            "require_new_ip": True,
            "require_new_device": True,
            "minimum_recent_failures": 2,
        }

        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            input_path = base / "test.csv"
            model_path = base / "model.pkl"
            policy_path = base / "policy.json"
            output_path = base / "results.csv"
            dataframe.to_csv(input_path, index=False)
            model_path.write_bytes(b"candidate-model")
            policy = {
                "model_sha256": hashlib.sha256(
                    b"candidate-model"
                ).hexdigest(),
                "policy_type": "hybrid_v4",
                "feature_semantics": "successful_history_v1",
                "score_transform": "clip(0.5 - decision_score, 0, 1)",
                "context_requirements": context_requirements,
                "review_threshold": 0.45,
                "block_threshold": 0.7,
                "max_block_fpr": 0.01,
                "max_flagged_fpr": 0.1,
            }
            policy_path.write_text(json.dumps(policy), encoding="utf-8")

            with (
                patch.object(evaluate_hybrid_v4, "INPUT_PATH", input_path),
                patch.object(evaluate_hybrid_v4, "MODEL_PATH", model_path),
                patch.object(evaluate_hybrid_v4, "POLICY_PATH", policy_path),
                patch.object(evaluate_hybrid_v4, "OUTPUT_PATH", output_path),
                patch.object(
                    evaluate_hybrid_v4.joblib,
                    "load",
                    return_value=FakeModel(decision_scores),
                ),
                contextlib.redirect_stdout(io.StringIO()),
            ):
                evaluate_hybrid_v4.main()

            results = pd.read_csv(output_path)
            self.assertEqual(
                results["decision"].tolist(),
                ["BLOCK", "BLOCK", "ALLOW", "REVIEW"],
            )
            self.assertTrue(results["context_eligible"].tolist()[0])
            self.assertEqual(len(results), 4)

    def test_stress_main_runs_generated_cases_through_policy(self):
        class FakeStressModel:
            def decision_function(self, dataframe):
                return np.full(len(dataframe), -0.2)

        with (
            patch.object(
                stress_test_v4,
                "load_model_and_policy",
                return_value=(
                    FakeStressModel(),
                    {"review_threshold": 0.45, "block_threshold": 0.7},
                ),
            ),
            contextlib.redirect_stdout(io.StringIO()) as output,
        ):
            stress_test_v4.main()

        self.assertIn("STRESS TEST V4", output.getvalue())


if __name__ == "__main__":
    unittest.main()
