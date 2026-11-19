import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import joblib
import pandas as pd

from app import model as runtime_model
from app.feature_contract import FEATURE_ORDER
from export_real_events import EXPORT_COLUMNS, export_real_events
from ML.config import MODEL_PATH
from ML.preprocess import build_offline_features, preprocess_file
from ML.train import load_training_data, train_from_file


def real_events_fixture() -> pd.DataFrame:
    return pd.DataFrame(
        [
            {
                "eventId": "1",
                "eventType": "identidad.login",
                "occurredAt": "2026-01-01T10:00:00Z",
                "username": "alice",
                "ipAddress": "10.0.0.1",
                "userAgent": "browser-a",
                "successful": True,
            },
            {
                "eventId": "2",
                "eventType": "identidad.login.failed",
                "occurredAt": "2026-01-01T10:05:00Z",
                "username": "alice",
                "ipAddress": "10.0.0.1",
                "userAgent": "browser-a",
                "successful": False,
            },
            {
                "eventId": "3",
                "eventType": "identidad.login.failed",
                "occurredAt": "2026-01-01T10:10:00Z",
                "username": "alice",
                "ipAddress": "10.0.0.2",
                "userAgent": "browser-a",
                "successful": False,
            },
            {
                "eventId": "4",
                "eventType": "identidad.login",
                "occurredAt": "2026-01-01T10:12:00Z",
                "username": "alice",
                "ipAddress": "10.0.0.1",
                "userAgent": "browser-a",
                "successful": True,
            },
            {
                "eventId": "5",
                "eventType": "identidad.login",
                "occurredAt": "2026-01-01T10:31:00Z",
                "username": "alice",
                "ipAddress": "10.0.0.1",
                "userAgent": "browser-a",
                "successful": True,
            },
        ]
    )


class OfflineFeatureTests(unittest.TestCase):
    def test_builds_features_without_current_event_leakage(self):
        processed = build_offline_features(real_events_fixture())

        self.assertEqual(processed.loc[0, "history_size"], 0)
        self.assertEqual(processed.loc[0, "is_new_ip"], 1)
        self.assertEqual(processed.loc[0, "is_new_device"], 1)
        self.assertEqual(processed.loc[1, "is_new_ip"], 0)
        self.assertEqual(processed.loc[1, "is_new_device"], 0)
        self.assertEqual(processed.loc[2, "recent_failures_15min"], 1)
        self.assertEqual(processed.loc[3, "recent_failures_15min"], 2)
        self.assertEqual(processed.loc[4, "recent_failures_15min"], 0)

    def test_real_dataset_does_not_require_synthetic_labels(self):
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "events.csv"
            output_path = Path(directory) / "features.csv"
            real_events_fixture().to_csv(input_path, index=False)

            processed = preprocess_file(input_path, output_path)

            self.assertTrue(output_path.exists())
            self.assertNotIn("synthetic_label", processed.columns)
            self.assertNotIn("anomaly_type", processed.columns)
            self.assertTrue(set(FEATURE_ORDER).issubset(processed.columns))


class TrainingTests(unittest.TestCase):
    def test_versioned_artifact_uses_runtime_feature_contract(self):
        model = joblib.load(MODEL_PATH)

        self.assertEqual(list(model.feature_names_in_), FEATURE_ORDER)
        self.assertEqual(model.n_features_in_, len(FEATURE_ORDER))

        features = {
            "hour_of_day": 2,
            "is_new_ip": 1,
            "is_new_device": 1,
            "recent_failures_15min": 4,
            "history_size": 50,
        }
        with patch.object(runtime_model, "_model", model):
            risk_score, reasons, decision = runtime_model.score(features)

        self.assertGreaterEqual(risk_score, 0.0)
        self.assertLessEqual(risk_score, 1.0)
        self.assertTrue(reasons)
        self.assertIn(decision, {"ALLOW", "REVIEW", "BLOCK"})

    def test_uses_failures_as_context_but_not_as_training_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "features.csv"
            build_offline_features(real_events_fixture()).to_csv(input_path, index=False)

            dataframe, features = load_training_data(
                input_path, successful_only=True, minimum_records=1
            )

            self.assertEqual(len(dataframe), 3)
            self.assertEqual(list(features.columns), FEATURE_ORDER)
            self.assertEqual(dataframe.iloc[1]["recent_failures_15min"], 2)

    def test_generated_model_is_compatible_with_runtime_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            input_path = base / "features.csv"
            model_path = base / "isolation_forest.pkl"
            metadata_path = base / "isolation_forest.metadata.json"
            build_offline_features(real_events_fixture()).to_csv(input_path, index=False)

            train_from_file(
                input_path=input_path,
                model_path=model_path,
                metadata_path=metadata_path,
                minimum_records=1,
                contamination="auto",
            )
            model = joblib.load(model_path)

            self.assertEqual(list(model.feature_names_in_), FEATURE_ORDER)
            self.assertEqual(model.n_features_in_, len(FEATURE_ORDER))
            self.assertTrue(metadata_path.exists())


class ExportTests(unittest.TestCase):
    def test_export_is_atomic_and_uses_expected_contract(self):
        fixture = real_events_fixture().reindex(columns=EXPORT_COLUMNS)
        with tempfile.TemporaryDirectory() as directory:
            output_path = Path(directory) / "real_events.csv"
            with patch("export_real_events.load_real_events", return_value=fixture):
                exported = export_real_events(
                    "postgresql://unused", output_path, minimum_records=1
                )

            self.assertEqual(list(exported.columns), EXPORT_COLUMNS)
            self.assertEqual(len(pd.read_csv(output_path)), len(fixture))
            self.assertEqual(list(output_path.parent.glob("*.tmp")), [])


if __name__ == "__main__":
    unittest.main()

