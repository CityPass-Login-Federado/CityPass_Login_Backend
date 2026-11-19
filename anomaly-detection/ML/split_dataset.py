import pandas as pd

from ML.config import DATA_DIR
from ML.preprocess import coerce_boolean


INPUT_PATH = DATA_DIR / "processed_features_v2.csv"

TRAIN_PATH = DATA_DIR / "train_v2.csv"
VALIDATION_PATH = DATA_DIR / "validation_v2.csv"
TEST_PATH = DATA_DIR / "test_v2.csv"

TRAIN_RATIO = 0.60
VALIDATION_RATIO = 0.20


def print_summary(name, dataframe):
    print(f"\n{name}")
    print("-" * 35)

    print("Total records:", len(dataframe))
    print("From:", dataframe["occurred_at"].min())
    print("To:", dataframe["occurred_at"].max())

    print("\nLabel distribution:")
    print(
        dataframe["synthetic_label"]
        .value_counts()
        .sort_index()
    )

    print("\nAnomaly types:")
    print(dataframe["anomaly_type"].value_counts())


def split_dataset():

    dataframe = pd.read_csv(INPUT_PATH)

    dataframe["occurred_at"] = pd.to_datetime(
        dataframe["occurred_at"],
        utc=True,
        errors="raise",
    )

    dataframe["successful"] = dataframe[
        "successful"
    ].map(coerce_boolean)

    dataframe["ml_eligible"] = dataframe[
        "ml_eligible"
    ].map(coerce_boolean)

    dataframe = dataframe.sort_values(
        "occurred_at",
        kind="mergesort",
    ).reset_index(drop=True)

    total = len(dataframe)

    # Calcular límites temporales.
    train_end = int(total * TRAIN_RATIO)

    validation_end = int(
        total * (TRAIN_RATIO + VALIDATION_RATIO)
    )

    validation_start_time = dataframe.iloc[
        train_end
    ]["occurred_at"]

    test_start_time = dataframe.iloc[
        validation_end
    ]["occurred_at"]

    # No separar eventos del mismo instante.
    train_raw = dataframe[
        dataframe["occurred_at"] < validation_start_time
    ].copy()

    validation_raw = dataframe[
        (dataframe["occurred_at"] >= validation_start_time)
        & (dataframe["occurred_at"] < test_start_time)
    ].copy()

    test_raw = dataframe[
        dataframe["occurred_at"] >= test_start_time
    ].copy()

    print("Temporal split before filtering")
    print("--------------------------------")

    print("Train:", len(train_raw))
    print("Validation:", len(validation_raw))
    print("Test:", len(test_raw))

    # Entrenamiento:
    # solamente autenticaciones legítimas.
    train = train_raw[
        (train_raw["ml_eligible"])
        & (train_raw["successful"])
        & (train_raw["synthetic_label"] == 0)
    ].copy()

    # Validación:
    # autenticaciones legítimas y anómalas.
    validation = validation_raw[
        validation_raw["ml_eligible"]
    ].copy()

    # Test:
    # autenticaciones legítimas y anómalas.
    test = test_raw[
        test_raw["ml_eligible"]
    ].copy()

    # Validaciones.
    assert (
        len(train_raw)
        + len(validation_raw)
        + len(test_raw)
        == total
    )

    assert len(train) >= 100
    assert len(validation) > 0
    assert len(test) > 0

    assert train["synthetic_label"].eq(0).all()

    assert validation["synthetic_label"].nunique() == 2
    assert test["synthetic_label"].nunique() == 2

    assert train["ml_eligible"].all()
    assert validation["ml_eligible"].all()
    assert test["ml_eligible"].all()

    # Verificar que los períodos no se superpongan.
    assert (
        train["occurred_at"].max()
        < validation["occurred_at"].min()
    )

    assert (
        validation["occurred_at"].max()
        < test["occurred_at"].min()
    )

    DATA_DIR.mkdir(
        parents=True,
        exist_ok=True,
    )

    train.to_csv(TRAIN_PATH, index=False)
    validation.to_csv(VALIDATION_PATH, index=False)
    test.to_csv(TEST_PATH, index=False)

    print_summary("TRAIN", train)
    print_summary("VALIDATION", validation)
    print_summary("TEST", test)

    print("\nDatasets saved:")
    print(TRAIN_PATH)
    print(VALIDATION_PATH)
    print(TEST_PATH)

    return train, validation, test


if __name__ == "__main__":
    split_dataset()