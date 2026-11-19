"""Export real login attempts from PostgreSQL for offline ML training.

RawAuthenticationEvent is an in-memory publication contract and has no local
repository. ``login_attempts`` is the persisted source of real login behavior
and additionally retains the failures required by ``recent_failures_15min``.
"""

import argparse
import os
from datetime import datetime, timezone
from pathlib import Path
from tempfile import NamedTemporaryFile

import pandas as pd
from sqlalchemy import create_engine, text

from ML.config import REAL_DATASET_PATH


EXPORT_COLUMNS = [
    "eventId",
    "eventType",
    "occurredAt",
    "username",
    "ipAddress",
    "userAgent",
    "successful",
]


def parse_timestamp(value: str | None) -> datetime | None:
    if value is None:
        return None
    normalized = value.strip().replace("Z", "+00:00")
    parsed = datetime.fromisoformat(normalized)
    if parsed.tzinfo is not None:
        parsed = parsed.astimezone(timezone.utc).replace(tzinfo=None)
    return parsed


def load_real_events(
    database_url: str,
    from_timestamp: datetime | None = None,
    to_timestamp: datetime | None = None,
) -> pd.DataFrame:
    conditions = []
    parameters = {}
    if from_timestamp is not None:
        conditions.append("attempted_at >= :from_timestamp")
        parameters["from_timestamp"] = from_timestamp
    if to_timestamp is not None:
        conditions.append("attempted_at < :to_timestamp")
        parameters["to_timestamp"] = to_timestamp

    where_clause = "WHERE " + " AND ".join(conditions) if conditions else ""
    query = text(
        f"""
        SELECT
            id::text AS "eventId",
            CASE
                WHEN successful THEN 'identidad.login'
                ELSE 'identidad.login.failed'
            END AS "eventType",
            attempted_at AS "occurredAt",
            username,
            ip_address AS "ipAddress",
            user_agent AS "userAgent",
            successful
        FROM login_attempts
        {where_clause}
        ORDER BY attempted_at ASC, id ASC
        """
    )

    engine = create_engine(database_url)
    try:
        with engine.connect() as connection:
            dataframe = pd.read_sql_query(query, connection, params=parameters)
    finally:
        engine.dispose()
    return dataframe.reindex(columns=EXPORT_COLUMNS)


def write_csv_atomically(dataframe: pd.DataFrame, output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = None
    try:
        with NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="",
            suffix=".csv.tmp",
            dir=output_path.parent,
            delete=False,
        ) as temporary_file:
            temporary_path = Path(temporary_file.name)
            dataframe.to_csv(temporary_file, index=False)
        temporary_path.replace(output_path)
    finally:
        if temporary_path is not None and temporary_path.exists():
            temporary_path.unlink()


def export_real_events(
    database_url: str,
    output_path: Path,
    from_timestamp: datetime | None = None,
    to_timestamp: datetime | None = None,
    minimum_records: int = 1,
) -> pd.DataFrame:
    dataframe = load_real_events(database_url, from_timestamp, to_timestamp)
    if len(dataframe) < minimum_records:
        raise ValueError(
            "No hay suficientes intentos reales para exportar: "
            f"se encontraron {len(dataframe)} y se requieren {minimum_records}."
        )
    write_csv_atomically(dataframe, output_path)
    return dataframe


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Exporta intentos reales de login para entrenamiento offline."
    )
    parser.add_argument(
        "--database-url",
        default=os.getenv("ANOMALY_DB_URL"),
        help="URL PostgreSQL. Por defecto usa ANOMALY_DB_URL.",
    )
    parser.add_argument("--from", dest="from_value", help="Inicio ISO 8601 inclusivo.")
    parser.add_argument("--to", dest="to_value", help="Fin ISO 8601 exclusivo.")
    parser.add_argument(
        "--output",
        type=Path,
        default=REAL_DATASET_PATH,
        help=f"CSV de salida (default: {REAL_DATASET_PATH}).",
    )
    parser.add_argument("--min-records", type=int, default=1)
    return parser.parse_args()


def main() -> None:
    args = parse_arguments()
    if not args.database_url:
        raise SystemExit("Falta --database-url o la variable ANOMALY_DB_URL.")
    if args.min_records < 1:
        raise SystemExit("--min-records debe ser mayor o igual a 1.")

    dataframe = export_real_events(
        database_url=args.database_url,
        output_path=args.output,
        from_timestamp=parse_timestamp(args.from_value),
        to_timestamp=parse_timestamp(args.to_value),
        minimum_records=args.min_records,
    )
    print("Exportacion de eventos reales completada")
    print(f"Registros: {len(dataframe)}")
    print(f"Salida: {args.output.resolve()}")
    print("El archivo contiene datos sensibles y no debe versionarse.")


if __name__ == "__main__":
    main()

