import os
import unittest

from datetime import datetime, timezone, timedelta
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pandas as pd

from ML.preprocess import build_offline_features
from app.feature_contract import FEATURE_ORDER


def create_events():

    events = []

    attempts = [
        ("10:00:00", "10.0.0.1", "Chrome", True),
        ("10:05:00", "10.0.0.2", "Firefox", False),
        ("10:07:00", "10.0.0.2", "Firefox", False),
        ("10:09:00", "10.0.0.2", "Firefox", True),
        ("10:11:00", "10.0.0.2", "Firefox", True),
    ]

    for index, attempt in enumerate(attempts):

        hour, ip, agent, successful = attempt

        events.append({
            "eventId": str(index),
            "eventType": (
                "identidad.login"
                if successful
                else "identidad.login.failed"
            ),
            "occurredAt": f"2026-01-01T{hour}Z",
            "username": "test-user",
            "ipAddress": ip,
            "userAgent": agent,
            "successful": successful,
        })

    return pd.DataFrame(events)


class FeatureTrustTests(unittest.TestCase):

    def setUp(self):

        self.events = create_events()

        self.processed = build_offline_features(
            self.events
        )

    def test_failed_attempt_does_not_trust_ip(self):

        self.assertEqual(
            self.processed.loc[2, "is_new_ip"],
            1,
        )

        self.assertEqual(
            self.processed.loc[3, "is_new_ip"],
            1,
        )

    def test_failed_attempt_does_not_trust_device(self):

        self.assertEqual(
            self.processed.loc[2, "is_new_device"],
            1,
        )

        self.assertEqual(
            self.processed.loc[3, "is_new_device"],
            1,
        )

    def test_successful_login_trusts_ip_and_device(self):

        self.assertEqual(
            self.processed.loc[4, "is_new_ip"],
            0,
        )

        self.assertEqual(
            self.processed.loc[4, "is_new_device"],
            0,
        )

    def test_failures_remain_in_history(self):

        self.assertEqual(
            self.processed.loc[3, "recent_failures_15min"],
            2,
        )

        self.assertEqual(
            self.processed.loc[3, "history_size"],
            3,
        )

    def test_offline_runtime_feature_parity(self):

        with patch.dict(
            os.environ,
            {"ANOMALY_DB_URL": "sqlite:///:memory:"},
        ):
            from app import features as runtime_features

        history = [

            SimpleNamespace(
                ip_address="10.0.0.2",
                user_agent="Firefox",
                attempted_at=datetime(
                    2026, 1, 1, 10, 7
                ),
                successful=False,
            ),

            SimpleNamespace(
                ip_address="10.0.0.2",
                user_agent="Firefox",
                attempted_at=datetime(
                    2026, 1, 1, 10, 5
                ),
                successful=False,
            ),

            SimpleNamespace(
                ip_address="10.0.0.1",
                user_agent="Chrome",
                attempted_at=datetime(
                    2026, 1, 1, 10, 0
                ),
                successful=True,
            ),
        ]

        mock_engine = MagicMock()

        connection = (
            mock_engine.connect
            .return_value
            .__enter__
            .return_value
        )

        connection.execute.return_value.fetchall.return_value = (
            history
        )

        with patch.object(
            runtime_features,
            "engine",
            mock_engine,
        ):

            online_features = runtime_features.build_features(
                username="test-user",
                ip="10.0.0.2",
                user_agent="Firefox",
                ts=datetime(
                    2026, 1, 1, 10, 9
                ),
            )

        offline_features = {
            feature: int(
                self.processed.loc[3, feature]
            )
            for feature in FEATURE_ORDER
        }

        self.assertEqual(
            online_features,
            offline_features,
        )

    def test_simultaneous_events_are_not_used_as_history(self):

        events = create_events()

        events.loc[2, "occurredAt"] = (
            events.loc[1, "occurredAt"]
        )

        processed = build_offline_features(events)

        self.assertEqual(
            processed.loc[1, "history_size"],
            1,
        )

        self.assertEqual(
            processed.loc[2, "history_size"],
            1,
        )

        self.assertEqual(
            processed.loc[2, "recent_failures_15min"],
            0,
        )

        self.assertEqual(
            processed.loc[3, "recent_failures_15min"],
            2,
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

        query = str(args[0])
        parameters = args[1]

        self.assertIn(
            "attempted_at < :ts",
            query,
        )

        self.assertEqual(
            parameters["ts"],
            datetime(2026, 1, 1, 10, 9),
        )

        self.assertEqual(
            result["hour_of_day"],
            10,
        )


if __name__ == "__main__":
    unittest.main()