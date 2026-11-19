import os
import unittest

from datetime import datetime, timezone, timedelta
from unittest.mock import MagicMock, patch

import pandas as pd

from ML.preprocess import build_offline_features


class TemporalFeatureTests(unittest.TestCase):

    def test_simultaneous_events_are_excluded(self):

        events = pd.DataFrame([
            {
                "eventId": str(index),
                "username": "test-user",
                "occurredAt": timestamp,
                "ipAddress": "10.0.0.1",
                "userAgent": "Chrome",
                "successful": successful,
            }
            for index, (timestamp, successful)
            in enumerate([
                ("2026-01-01T10:00:00Z", True),
                ("2026-01-01T10:05:00Z", False),
                ("2026-01-01T10:05:00Z", False),
                ("2026-01-01T10:10:00Z", True),
            ])
        ])

        processed = build_offline_features(events)

        self.assertEqual(
            processed["history_size"].tolist(),
            [0, 1, 1, 3],
        )

        self.assertEqual(
            processed["recent_failures_15min"].tolist(),
            [0, 0, 0, 2],
        )

    def test_runtime_uses_utc_cutoff(self):

        with patch.dict(
            os.environ,
            {"ANOMALY_DB_URL": "sqlite:///:memory:"},
        ):
            from app import features as runtime_features

        mock_engine = MagicMock()

        connection = (
            mock_engine.connect
            .return_value
            .__enter__
            .return_value
        )

        connection.execute.return_value.fetchall.return_value = []

        timestamp = datetime(
            2026, 1, 1, 7, 9,
            tzinfo=timezone(timedelta(hours=-3)),
        )

        with patch.object(
            runtime_features,
            "engine",
            mock_engine,
        ):

            result = runtime_features.build_features(
                username="test-user",
                ip="10.0.0.2",
                user_agent="Firefox",
                ts=timestamp,
            )

        args = connection.execute.call_args.args

        self.assertIn(
            "attempted_at < :ts",
            str(args[0]),
        )

        self.assertEqual(
            args[1]["ts"],
            datetime(2026, 1, 1, 10, 9),
        )

        self.assertEqual(result["hour_of_day"], 10)


if __name__ == "__main__":
    unittest.main()