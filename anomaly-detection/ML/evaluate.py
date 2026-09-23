import joblib
import pandas as pd

from sklearn.metrics import (
    confusion_matrix,
    classification_report,
    precision_score,
    recall_score,
    f1_score,
)

from config import (
    PROCESSED_DATASET_PATH,
    MODEL_PATH,
)


FEATURE_COLUMNS = [
    "hour_of_day",
    "is_new_ip",
    "is_new_device",
    "recent_failures_15min",
    "history_size",
]


def load_data_and_model():
    """
    Loads the processed dataset and the trained Isolation Forest model.
    """
    dataframe = pd.read_csv(
        PROCESSED_DATASET_PATH
    )

    model = joblib.load(
        MODEL_PATH
    )

    return dataframe, model


def predict(dataframe, model):
    """
    Generates Isolation Forest predictions.

    IsolationForest returns:
    1  -> normal
    -1 -> anomaly

    For easier comparison with synthetic_label:
    0 -> normal
    1 -> anomaly
    """
    X = dataframe[FEATURE_COLUMNS]

    raw_predictions = model.predict(X)

    dataframe = dataframe.copy()

    dataframe["predicted_anomaly"] = (
        raw_predictions == -1
    ).astype(int)

    # decision_function:
    # higher values -> more normal
    # lower values -> more anomalous
    dataframe["decision_score"] = model.decision_function(X)

    return dataframe


def print_overall_metrics(dataframe):
    """
    Prints global evaluation metrics.
    """
    y_true = dataframe["synthetic_label"]
    y_pred = dataframe["predicted_anomaly"]

    precision = precision_score(
        y_true,
        y_pred,
        zero_division=0,
    )

    recall = recall_score(
        y_true,
        y_pred,
        zero_division=0,
    )

    f1 = f1_score(
        y_true,
        y_pred,
        zero_division=0,
    )

    tn, fp, fn, tp = confusion_matrix(
        y_true,
        y_pred,
        labels=[0, 1],
    ).ravel()

    print()
    print("Isolation Forest Evaluation")
    print("---------------------------")

    print(f"Total records:        {len(dataframe)}")
    print(f"Real anomalies:       {int(y_true.sum())}")
    print(f"Predicted anomalies:  {int(y_pred.sum())}")

    print()
    print("Confusion matrix values:")
    print(f"True negatives:       {tn}")
    print(f"False positives:      {fp}")
    print(f"False negatives:      {fn}")
    print(f"True positives:       {tp}")

    print()
    print("Metrics:")
    print(f"Precision:            {precision:.4f}")
    print(f"Recall:               {recall:.4f}")
    print(f"F1-score:             {f1:.4f}")

    print()
    print("Classification report:")
    print(
        classification_report(
            y_true,
            y_pred,
            target_names=[
                "normal",
                "anomaly",
            ],
            zero_division=0,
        )
    )


def print_detection_by_anomaly_type(dataframe):
    """
    Shows how well the model detects each synthetic anomaly type.
    """
    anomalies = dataframe[
        dataframe["synthetic_label"] == 1
    ].copy()

    summary = (
        anomalies
        .groupby("anomaly_type")
        .agg(
            total=("anomaly_type", "size"),
            detected=("predicted_anomaly", "sum"),
        )
    )

    summary["missed"] = (
        summary["total"]
        - summary["detected"]
    )

    summary["detection_rate"] = (
        summary["detected"]
        / summary["total"]
    )

    print()
    print("Detection by anomaly type")
    print("-------------------------")
    print(summary)


def print_false_positives(dataframe):
    """
    Shows examples of normal events incorrectly classified as anomalies.
    """
    false_positives = dataframe[
        (dataframe["synthetic_label"] == 0)
        & (dataframe["predicted_anomaly"] == 1)
    ].copy()

    false_positives = false_positives.sort_values(
        by="decision_score"
    )

    print()
    print("False positive examples")
    print("-----------------------")

    if false_positives.empty:
        print("No false positives detected.")
        return

    columns = FEATURE_COLUMNS + [
        "decision_score",
    ]

    print(
        false_positives[
            columns
        ].head(10).to_string(index=False)
    )


def print_false_negatives(dataframe):
    """
    Shows synthetic anomalies missed by the model.
    """
    false_negatives = dataframe[
        (dataframe["synthetic_label"] == 1)
        & (dataframe["predicted_anomaly"] == 0)
    ].copy()

    false_negatives = false_negatives.sort_values(
        by="decision_score",
        ascending=False,
    )

    print()
    print("False negative examples")
    print("-----------------------")

    if false_negatives.empty:
        print("No false negatives detected.")
        return

    columns = FEATURE_COLUMNS + [
        "anomaly_type",
        "decision_score",
    ]

    print(
        false_negatives[
            columns
        ].head(10).to_string(index=False)
    )


def save_evaluation_results(dataframe):
    """
    Saves the dataset with model predictions and decision scores.
    """
    output_path = (
        PROCESSED_DATASET_PATH.parent
        / "evaluation_results.csv"
    )

    dataframe.to_csv(
        output_path,
        index=False,
    )

    print()
    print("Evaluation results saved at:")
    print(output_path)


def main():
    dataframe, model = load_data_and_model()

    evaluated = predict(
        dataframe,
        model,
    )

    print_overall_metrics(
        evaluated
    )

    print_detection_by_anomaly_type(
        evaluated
    )

    print_false_positives(
        evaluated
    )

    print_false_negatives(
        evaluated
    )

    save_evaluation_results(
        evaluated
    )


if __name__ == "__main__":
    main()