import joblib
import pandas as pd

from sklearn.metrics import (
    confusion_matrix,
    classification_report,
)

from app.feature_contract import FEATURE_ORDER
from ML.config import (
    PROCESSED_DATASET_PATH,
    MODEL_PATH,
)


def main():
    dataframe = pd.read_csv(PROCESSED_DATASET_PATH)
    model = joblib.load(MODEL_PATH)

    X = dataframe[FEATURE_ORDER]

    decision_scores = model.decision_function(X)

    # Reproduce la transformación actual de app/model.py
    dataframe["risk_score"] = (
        pd.Series(0.5 - decision_scores)
        .clip(lower=0, upper=1)
    )

    dataframe["decision"] = dataframe["risk_score"].apply(
        lambda score: (
            "BLOCK" if score >= 0.7
            else "REVIEW" if score >= 0.4
            else "ALLOW"
        )
    )

    dataframe["blocked"] = (
        dataframe["decision"] == "BLOCK"
    ).astype(int)

    print("\nDecisiones productivas:")
    print(dataframe["decision"].value_counts())

    print("\nDistribución del risk score:")
    print(dataframe["risk_score"].describe())

    print("\nMatriz de decisiones por tipo de evento:")
    print(pd.crosstab(
        dataframe["synthetic_label"],
        dataframe["decision"]
    ))

    print("\nMatriz de confusión del bloqueo:")
    print(confusion_matrix(
        dataframe["synthetic_label"],
        dataframe["blocked"],
        labels=[0, 1]
    ))

    print("\nMétricas del bloqueo:")
    print(classification_report(
        dataframe["synthetic_label"],
        dataframe["blocked"],
        zero_division=0
    ))


if __name__ == "__main__":
    main()