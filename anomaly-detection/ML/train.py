import argparse
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from tempfile import NamedTemporaryFile

import joblib
import pandas as pd
from sklearn import __version__ as sklearn_version
from sklearn.ensemble import IsolationForest

from app.feature_contract import FEATURE_ORDER
from ML.config import (
    ANOMALY_RATIO,
    MODEL_METADATA_PATH,
    MODEL_PATH,
    PROCESSED_DATASET_PATH,
    RANDOM_SEED,
)
from ML.preprocess import coerce_boolean


FEATURE_COLUMNS = FEATURE_ORDER


def parse_contamination(value: str | float) -> str | float:
    if value == "auto":
        return "auto"
    parsed = float(value)
    if not 0 < parsed <= 0.5:
        raise ValueError("contamination debe estar entre 0 (exclusivo) y 0.5.")
    return parsed


def load_training_data(
    input_path: Path = PROCESSED_DATASET_PATH,
    successful_only: bool = True,
    minimum_records: int = 100,
) -> tuple[pd.DataFrame, pd.DataFrame]:
    dataframe = pd.read_csv(input_path)
    missing_features = [
        feature for feature in FEATURE_COLUMNS if feature not in dataframe.columns
    ]
    if missing_features:
        raise ValueError(f"Missing features in dataset: {missing_features}")

    if successful_only:
        if "successful" not in dataframe.columns:
            raise ValueError(
                "El dataset no incluye successful; use --include-failures solo si es intencional."
            )
        success_mask = dataframe["successful"].map(coerce_boolean)
        dataframe = dataframe.loc[success_mask].copy()

    if len(dataframe) < minimum_records:
        raise ValueError(
            "No hay suficientes filas para entrenar: "
            f"se encontraron {len(dataframe)} y se requieren {minimum_records}."
        )

    features = dataframe[FEATURE_COLUMNS].copy()
    if features.isnull().any().any():
        raise ValueError("Training features contain null values.")
    return dataframe, features


def train_model(
    features: pd.DataFrame,
    contamination: str | float = ANOMALY_RATIO,
) -> IsolationForest:
    model = IsolationForest(
        n_estimators=200,
        contamination=contamination,
        random_state=RANDOM_SEED,
        n_jobs=-1,
    )
    model.fit(features)
    return model


def save_model(model: IsolationForest, output_path: Path = MODEL_PATH) -> str:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = None
    try:
        with NamedTemporaryFile(
            suffix=".pkl.tmp", dir=output_path.parent, delete=False
        ) as temporary_file:
            temporary_path = Path(temporary_file.name)
        joblib.dump(model, temporary_path)
        persisted_model = joblib.load(temporary_path)
        if list(getattr(persisted_model, "feature_names_in_", [])) != FEATURE_COLUMNS:
            raise ValueError("El artefacto generado no conserva el contrato de features.")
        os.replace(temporary_path, output_path)
    finally:
        if temporary_path is not None and temporary_path.exists():
            temporary_path.unlink()

    digest = hashlib.sha256()
    with output_path.open("rb") as model_file:
        for chunk in iter(lambda: model_file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def save_metadata(
    dataframe: pd.DataFrame,
    input_path: Path,
    output_path: Path,
    model_path: Path,
    model_sha256: str,
    contamination: str | float,
    successful_only: bool,
) -> None:
    occurred_at = (
        pd.to_datetime(dataframe["occurred_at"], utc=True)
        if "occurred_at" in dataframe.columns
        else None
    )
    metadata = {
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "training_records": len(dataframe),
        "features": FEATURE_COLUMNS,
        "contamination": contamination,
        "successful_only": successful_only,
        "input_path": str(input_path.resolve()),
        "model_path": str(model_path.resolve()),
        "model_sha256": model_sha256,
        "scikit_learn_version": sklearn_version,
        "data_from": occurred_at.min().isoformat() if occurred_at is not None else None,
        "data_to": occurred_at.max().isoformat() if occurred_at is not None else None,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = None
    try:
        with NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            suffix=".json.tmp",
            dir=output_path.parent,
            delete=False,
        ) as temporary_file:
            temporary_path = Path(temporary_file.name)
            json.dump(metadata, temporary_file, indent=2, ensure_ascii=False)
            temporary_file.write("\n")
        os.replace(temporary_path, output_path)
    finally:
        if temporary_path is not None and temporary_path.exists():
            temporary_path.unlink()


def print_training_summary(
    dataframe: pd.DataFrame,
    features: pd.DataFrame,
    model: IsolationForest,
    contamination: str | float,
    output_path: Path,
) -> None:
    predictions = model.predict(features)
    scores = model.decision_function(features)
    print("\nIsolation Forest training completed")
    print("-----------------------------------")
    print(f"Training records:       {len(features)}")
    print(f"Features used:          {len(FEATURE_COLUMNS)}")
    print(f"Contamination:          {contamination}")
    print(f"Predicted normal:       {(predictions == 1).sum()}")
    print(f"Predicted anomalies:    {(predictions == -1).sum()}")
    print(f"Decision score minimum: {scores.min():.6f}")
    print(f"Decision score median:  {pd.Series(scores).median():.6f}")
    print(f"Decision score maximum: {scores.max():.6f}")
    if "synthetic_label" in dataframe.columns:
        print("\nSynthetic labels in dataset:")
        print(dataframe["synthetic_label"].value_counts().sort_index())
    print(f"\nModel saved at:\n{output_path.resolve()}")


def train_from_file(
    input_path: Path,
    model_path: Path = MODEL_PATH,
    metadata_path: Path = MODEL_METADATA_PATH,
    contamination: str | float = ANOMALY_RATIO,
    successful_only: bool = True,
    minimum_records: int = 100,
) -> IsolationForest:
    contamination = parse_contamination(contamination)
    dataframe, features = load_training_data(
        input_path, successful_only, minimum_records
    )
    model = train_model(features, contamination)
    model_sha256 = save_model(model, model_path)
    save_metadata(
        dataframe,
        input_path,
        metadata_path,
        model_path,
        model_sha256,
        contamination,
        successful_only,
    )
    print_training_summary(dataframe, features, model, contamination, model_path)
    return model


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Entrena Isolation Forest con features sinteticas o reales."
    )
    parser.add_argument("--input", type=Path, default=PROCESSED_DATASET_PATH)
    parser.add_argument("--output", type=Path, default=MODEL_PATH)
    parser.add_argument("--metadata-output", type=Path, default=MODEL_METADATA_PATH)
    parser.add_argument("--contamination", default=str(ANOMALY_RATIO))
    parser.add_argument("--min-records", type=int, default=100)
    parser.add_argument(
        "--include-failures",
        action="store_true",
        help="Incluye fallos en el fit. Por defecto solo se usan logins exitosos.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_arguments()
    train_from_file(
        input_path=args.input,
        model_path=args.output,
        metadata_path=args.metadata_output,
        contamination=args.contamination,
        successful_only=not args.include_failures,
        minimum_records=args.min_records,
    )


if __name__ == "__main__":
    main()
