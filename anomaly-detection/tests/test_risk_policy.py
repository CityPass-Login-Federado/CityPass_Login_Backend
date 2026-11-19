import unittest

from app.risk_policy import (
    normalize_risk_score,
    resolve_decision,
)


class RiskPolicyTests(unittest.TestCase):

    def setUp(self):
        self.review_threshold = 0.471
        self.block_threshold = 0.557

    def test_normalize_risk_score(self):
        self.assertAlmostEqual(
            normalize_risk_score(0.2),
            0.3,
        )

        self.assertAlmostEqual(
            normalize_risk_score(-0.1),
            0.6,
        )

        self.assertAlmostEqual(
            normalize_risk_score(0.0),
            0.5,
        )

    def test_normalization_limits(self):
        self.assertEqual(
            normalize_risk_score(2.0),
            0.0,
        )

        self.assertEqual(
            normalize_risk_score(-2.0),
            1.0,
        )

    def test_invalid_decision_score(self):
        for score in (
            float("nan"),
            float("inf"),
            float("-inf"),
        ):
            with self.subTest(score=score):
                with self.assertRaises(ValueError):
                    normalize_risk_score(score)

    def test_allow_decision(self):
        self.assertEqual(
            resolve_decision(
                0.470,
                self.review_threshold,
                self.block_threshold,
            ),
            "ALLOW",
        )

    def test_review_decision(self):
        for score in (0.471, 0.500, 0.556):

            with self.subTest(score=score):

                self.assertEqual(
                    resolve_decision(
                        score,
                        self.review_threshold,
                        self.block_threshold,
                    ),
                    "REVIEW",
                )

    def test_block_decision(self):
        for score in (0.557, 0.600, 1.0):

            with self.subTest(score=score):

                self.assertEqual(
                    resolve_decision(
                        score,
                        self.review_threshold,
                        self.block_threshold,
                    ),
                    "BLOCK",
                )

    def test_invalid_thresholds(self):
        invalid_thresholds = [
            (0.7, 0.4),
            (0.5, 0.5),
            (-0.1, 0.7),
            (0.4, 1.1),
        ]

        for review, block in invalid_thresholds:

            with self.subTest(
                review=review,
                block=block,
            ):
                with self.assertRaises(ValueError):
                    resolve_decision(
                        0.5,
                        review,
                        block,
                    )

    def test_invalid_risk_score(self):
        for score in (
            -0.1,
            1.1,
            float("nan"),
        ):

            with self.subTest(score=score):
                with self.assertRaises(ValueError):
                    resolve_decision(
                        score,
                        self.review_threshold,
                        self.block_threshold,
                    )


if __name__ == "__main__":
    unittest.main()