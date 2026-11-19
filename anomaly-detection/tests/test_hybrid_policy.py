import unittest

from app.risk_policy import (
    resolve_hybrid_decision,
)


class HybridPolicyTests(unittest.TestCase):

    def setUp(self):

        self.review = 0.45
        self.block = 0.55

        self.features = {
            "hour_of_day": 15,
            "is_new_ip": 1,
            "is_new_device": 1,
            "recent_failures_15min": 2,
            "history_size": 30,
        }

    def decide(self, score, **changes):

        features = {
            **self.features,
            **changes,
        }

        return resolve_hybrid_decision(
            score,
            features,
            self.review,
            self.block,
        )

    def test_high_risk_multiple_signals(self):

        self.assertEqual(
            self.decide(0.65),
            "BLOCK",
        )

    def test_low_score(self):

        self.assertEqual(
            self.decide(0.30),
            "ALLOW",
        )

    def test_intermediate_score(self):

        self.assertEqual(
            self.decide(0.50),
            "REVIEW",
        )

    def test_known_ip(self):

        self.assertEqual(
            self.decide(0.65, is_new_ip=0),
            "REVIEW",
        )

    def test_known_device(self):

        self.assertEqual(
            self.decide(0.65, is_new_device=0),
            "REVIEW",
        )

    def test_single_failure(self):

        self.assertEqual(
            self.decide(
                0.65,
                recent_failures_15min=1,
            ),
            "REVIEW",
        )

    def test_cold_start(self):

        self.assertEqual(
            self.decide(0.65, history_size=0),
            "REVIEW",
        )

    def test_legitimate_multiple_changes(self):

        self.assertEqual(
            self.decide(
                0.65,
                recent_failures_15min=0,
            ),
            "REVIEW",
        )

    def test_block_boundary(self):

        self.assertEqual(
            self.decide(
                0.55,
                history_size=5,
            ),
            "BLOCK",
        )

    def test_insufficient_history(self):

        self.assertEqual(
            self.decide(0.65, history_size=4),
            "REVIEW",
        )


if __name__ == "__main__":
    unittest.main()