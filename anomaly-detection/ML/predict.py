import argparse

import joblib
import pandas as pd

from config import MODEL_PATH


FEATURE_COLUMNS = [
    "hour_of_day",
    "is_new_ip",
    "is_new_device",
    "recent_failures_15min",
    "history_size",
]


def load_model():
    """
    Loads the trained Isolation Forest model.
    """
    return joblib.load(MODEL_PATH)


def predict_event(
    model,
    hour_of_day: int,
    is_new_ip: int,
    is_new_device: int,
    recent_failures_15min: int,
    history_size: int,
):
    """
    Predicts whether a single authentication event is anomalous.

    IsolationForest returns:
    1  -> normal
    -1 -> anomaly
    """

    event = pd.DataFrame(
        [
            {
                "hour_of_day": hour_of_day,
                "is_new_ip": is_new_ip,
                "is_new_device": is_new_device,
                "recent_failures_15min": recent_failures_15min,
                "history_size": history_size,
            }
        ],
        columns=FEATURE_COLUMNS,
    )

    prediction = model.predict(event)[0]
    decision_score = model.decision_function(event)[0]

    return {
        "prediction": "ANOMALY" if prediction == -1 else "NORMAL",
        "decision_score": float(decision_score),
    }


def parse_arguments():
    """
    Reads feature values from command-line arguments.
    """

    parser = argparse.ArgumentParser(
        description=(
            "Evaluate an authentication event "
            "using the trained Isolation Forest model."
        )
    )

    parser.add_argument(
        "--hour",
        type=int,
        required=True,
        help="Hour of day from 0 to 23.",
    )

    parser.add_argument(
        "--new-ip",
        type=int,
        choices=[0, 1],
        required=True,
        help="1 if the IP is new for the user, otherwise 0.",
    )

    parser.add_argument(
        "--new-device",
        type=int,
        choices=[0, 1],
        required=True,
        help="1 if the device is new for the user, otherwise 0.",
    )

    parser.add_argument(
        "--failures",
        type=int,
        required=True,
        help="Number of failed login attempts in the last 15 minutes.",
    )

    parser.add_argument(
        "--history",
        type=int,
        required=True,
        help="Number of historical authentication records available.",
    )

    return parser.parse_args()


def validate_arguments(args):
    """
    Performs additional validation of command-line values.
    """

    if not 0 <= args.hour <= 23:
        raise ValueError(
            "--hour must be between 0 and 23."
        )

    if args.failures < 0:
        raise ValueError(
            "--failures cannot be negative."
        )

    if args.history < 0:
        raise ValueError(
            "--history cannot be negative."
        )


def print_result(features, result):
    """
    Prints the input features and prediction result.
    """

    print()
    print("Authentication Event Analysis")
    print("-----------------------------")

    print("Features:")
    for key, value in features.items():
        print(f"{key}: {value}")

    print()
    print(f"Prediction:     {result['prediction']}")
    print(f"Decision score: {result['decision_score']:.6f}")

    print()

    if result["prediction"] == "ANOMALY":
        print(
            "The event differs from the behavioral patterns "
            "learned by the Isolation Forest."
        )
    else:
        print(
            "The event is consistent with the behavioral patterns "
            "learned by the Isolation Forest."
        )


def main():
    args = parse_arguments()
    validate_arguments(args)

    features = {
        "hour_of_day": args.hour,
        "is_new_ip": args.new_ip,
        "is_new_device": args.new_device,
        "recent_failures_15min": args.failures,
        "history_size": args.history,
    }

    model = load_model()

    result = predict_event(
        model=model,
        hour_of_day=features["hour_of_day"],
        is_new_ip=features["is_new_ip"],
        is_new_device=features["is_new_device"],
        recent_failures_15min=features["recent_failures_15min"],
        history_size=features["history_size"],
    )

    print_result(
        features,
        result,
    )


if __name__ == "__main__":
    main()