import argparse

import pandas as pd

from ML.config import DATA_DIR
from ML.preprocess import coerce_boolean


def prepare_dataset(split):

    input_path = (
        DATA_DIR
        / f"processed_features_v3_{split}.csv"
    )

    dataframe = pd.read_csv(input_path)

    dataframe["ml_eligible"] = dataframe[
        "ml_eligible"
    ].map(coerce_boolean)

    dataframe["evaluation_target"] = dataframe[
        "evaluation_target"
    ].map(coerce_boolean)

    dataframe["successful"] = dataframe[
        "successful"
    ].map(coerce_boolean)

    # Solo conservar intentos que llegan a ML.
    targets = dataframe[
        dataframe["evaluation_target"]
        & dataframe["ml_eligible"]
        & dataframe["successful"]
    ].copy()

    # Entrenamiento exclusivamente legítimo.
    if split == "train":

        targets = targets[
            targets["synthetic_label"] == 0
        ].copy()

        assert targets[
            "synthetic_label"
        ].eq(0).all()

    else:

        assert set(
            targets["synthetic_label"]
        ) == {0, 1}

    targets["history_group"] = pd.cut(
        targets["history_size"],
        bins=[-1, 0, 10, 50, 100, 150, 200],
    )

    print(f"\nPREPARED V3 - {split.upper()}")
    print("-" * 40)

    print("Records:", len(targets))

    print("\nLabel distribution:")
    print(targets["synthetic_label"].value_counts())

    print("\nHistory size distribution:")
    print(targets["history_group"].value_counts())

    print("\nCold start:")
    print(targets["history_size"].eq(0).sum())

    print("\nRecent failures:")
    print(
        targets["recent_failures_15min"]
        .value_counts()
        .sort_index()
    )

    print("\nScenarios:")
    print(targets["scenario"].value_counts())

    output_path = DATA_DIR / f"{split}_v3.csv"

    targets.to_csv(
        output_path,
        index=False,
    )

    print("\nSaved at:")
    print(output_path)


if __name__ == "__main__":

    parser = argparse.ArgumentParser()

    parser.add_argument(
        "--split",
        choices=["train", "validation", "test"],
        required=True,
    )

    args = parser.parse_args()

    prepare_dataset(args.split)