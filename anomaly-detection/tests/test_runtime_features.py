import os
import unittest

from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

os.environ.setdefault("ANOMALY_DB_URL", "sqlite:///:memory:")

from app import features
from app.feature_contract import MAX_HISTORY


class RuntimeFeatureTests(unittest.TestCase):
    def setUp(self):
        self.engine = MagicMock()
        self.connection = (
            self.engine.connect.return_value.__enter__.return_value
        )

    def test_build_features_trusts_only_successful_history(self):
        timestamp = datetime(2026, 1, 1, 10, 0)
        self.connection.execute.return_value.fetchall.return_value = [
            SimpleNamespace(
                ip_address="known-ip",
                user_agent="known-agent",
                attempted_at=timestamp - timedelta(days=1),
                successful=True,
            ),
            SimpleNamespace(
                ip_address="failed-ip",
                user_agent="failed-agent",
                attempted_at=timestamp - timedelta(seconds=899),
                successful=False,
            ),
            SimpleNamespace(
                ip_address="old-ip",
                user_agent="old-agent",
                attempted_at=timestamp - timedelta(seconds=900),
                successful=False,
            ),
            SimpleNamespace(
                ip_address="future-ip",
                user_agent="future-agent",
                attempted_at=timestamp + timedelta(seconds=1),
                successful=False,
            ),
        ]

        with patch.object(features, "engine", self.engine):
            result = features.build_features(
                "alice", "known-ip", "known-agent", timestamp
            )

        self.assertEqual(
            result,
            {
                "hour_of_day": 10,
                "is_new_ip": 0,
                "is_new_device": 0,
                "recent_failures_15min": 1,
                "history_size": 4,
            },
        )
        self.assertEqual(
            self.connection.execute.call_args_list[1].args[1]["history_limit"],
            MAX_HISTORY,
        )
        self.assertEqual(
            self.connection.execute.call_args_list[0].args[0].text,
            "SET LOCAL TIME ZONE 'UTC'",
        )

    def test_missing_signals_match_missing_successful_history(self):
        timestamp = datetime(2026, 1, 1, 10, 0)
        self.connection.execute.return_value.fetchall.return_value = [
            SimpleNamespace(
                ip_address=None,
                user_agent=" ",
                attempted_at=timestamp - timedelta(days=1),
                successful=True,
            )
        ]

        with patch.object(features, "engine", self.engine):
            result = features.build_features(
                "alice", None, None, timestamp
            )

        self.assertEqual(result["is_new_ip"], 0)
        self.assertEqual(result["is_new_device"], 0)
        self.assertEqual(result["recent_failures_15min"], 0)

    def test_timestamp_is_normalized_to_utc_before_query(self):
        timestamp = datetime(
            2026, 1, 1, 7, 9,
            tzinfo=timezone(timedelta(hours=-3)),
        )
        self.connection.execute.return_value.fetchall.return_value = []

        with patch.object(features, "engine", self.engine):
            result = features.build_features(
                "alice", "ip", "agent", timestamp
            )

        query = self.connection.execute.call_args_list[1]
        self.assertEqual(
            query.args[1]["ts"],
            datetime(2026, 1, 1, 10, 9),
        )
        self.assertEqual(result["hour_of_day"], 10)


if __name__ == "__main__":
    unittest.main()
