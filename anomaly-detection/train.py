import pandas as pd
from sklearn.ensemble import IsolationForest
import joblib
from sqlalchemy import create_engine
import os

engine = create_engine(os.environ["ANOMALY_DB_URL"])
df = pd.read_sql("SELECT * FROM login_attempts", engine)

# TODO: transformar df crudo en las mismas features de features.py, por usuario
# X = ...

model = IsolationForest(n_estimators=200, contamination=0.05, random_state=42)
model.fit(X)
joblib.dump(model, "models/isolation_forest.pkl")