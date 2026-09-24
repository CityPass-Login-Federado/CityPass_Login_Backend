from pathlib import Path

# Reproducibility
RANDOM_SEED = 42

# Dataset configuration
TOTAL_RECORDS = 10_000
ANOMALY_RATIO = 0.05

NORMAL_RECORDS = int(TOTAL_RECORDS * (1 - ANOMALY_RATIO))
ANOMALY_RECORDS = TOTAL_RECORDS - NORMAL_RECORDS

# Project paths
BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / "data"
MODELS_DIR = BASE_DIR / "models"

DATASET_PATH = DATA_DIR / "synthetic_events.csv"
PROCESSED_DATASET_PATH = DATA_DIR / "processed_features.csv"
MODEL_PATH = MODELS_DIR / "isolation_forest.pkl"

# Synthetic CityPass configuration
DEPARTMENTS = [
    "eda",
    "movilidad",
    "residuos",
    "reclamos",
    "emergencias",
    "espacios",
    "analitica",
]

CLIENT_IDS = [
    "citypass-admin-web",
    "citypass-mobile",
]

USER_AGENTS = [
    "Chrome-Windows",
    "Edge-Windows",
    "Firefox-Windows",
    "Chrome-Android",
]

EVENT_TYPES = [
    "identidad.login",
    "identidad.refresh",
    "identidad.logout",
]