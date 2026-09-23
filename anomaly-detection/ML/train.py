import joblib
import pandas as pd

from sklearn.ensemble import IsolationForest

from config import (
    PROCESSED_DATASET_PATH,
    MODEL_PATH,
    MODELS_DIR,
    RANDOM_SEED,
    ANOMALY_RATIO,
)


FEATURE_COLUMNS = [
    "hour_of_day",
    "is_new_ip",
    "is_new_device",
    "recent_failures_15min",
    "history_size",
]


def load_training_data():
    """
    Loads the processed dataset and returns only the features
    used by the Isolation Forest model.
    """
    dataframe = pd.read_csv(PROCESSED_DATASET_PATH)

    missing_features = [
        feature
        for feature in FEATURE_COLUMNS
        if feature not in dataframe.columns
    ]

    if missing_features:
        raise ValueError(
            f"Missing features in dataset: {missing_features}"
        )

    X = dataframe[FEATURE_COLUMNS].copy()

    if X.isnull().any().any():
        raise ValueError(
            "Training features contain null values."
        )

    return dataframe, X


def train_model(X):
    """
    Trains an Isolation Forest model.

    contamination indicates the expected proportion of anomalies
    in the dataset.
    """
    model = IsolationForest(
        n_estimators=200,
        contamination=ANOMALY_RATIO,
        random_state=RANDOM_SEED,
        n_jobs=-1,
    )

    model.fit(X)

    return model


def save_model(model):
    """
    Saves the trained model to disk.
    """
    MODELS_DIR.mkdir(
        parents=True,
        exist_ok=True,
    )

    joblib.dump(
        model,
        MODEL_PATH,
    )


def print_training_summary(dataframe, X, model):
    """
    Prints useful information about the training process.
    """

    predictions = model.predict(X)

    predicted_normal = (predictions == 1).sum()
    predicted_anomalies = (predictions == -1).sum()

    print()
    print("Isolation Forest training completed")
    print("-----------------------------------")
    print(f"Training records:     {len(X)}")
    print(f"Features used:        {len(FEATURE_COLUMNS)}")
    print(f"Expected contamination: {ANOMALY_RATIO:.2%}")

    print()
    print("Feature columns:")
    for feature in FEATURE_COLUMNS:
        print(f"- {feature}")

    print()
    print("Model predictions on training dataset:")
    print(f"Predicted normal:     {predicted_normal}")
    print(f"Predicted anomalies:  {predicted_anomalies}")

    print()
    print("Synthetic labels in dataset:")
    print(
        dataframe["synthetic_label"]
        .value_counts()
        .sort_index()
    )

    print()
    print(f"Model saved at:")
    print(MODEL_PATH)


def main():
    dataframe, X = load_training_data()

    model = train_model(X)

    save_model(model)

    print_training_summary(
        dataframe,
        X,
        model,
    )


if __name__ == "__main__":
    main()