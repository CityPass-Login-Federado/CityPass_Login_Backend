"""Administrative end-to-end retraining command using real login attempts."""

import argparse
import os
from pathlib import Path

from export_real_events import export_real_events, parse_timestamp
from ML.config import (
    ANOMALY_RATIO,
    MODEL_METADATA_PATH,
    MODEL_PATH,
    REAL_DATASET_PATH,
    REAL_PROCESSED_DATASET_PATH,
)
from ML.preprocess import preprocess_file, print_summary
from ML.train import train_from_file


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Exporta intentos reales, construye features y reentrena "
            "isolation_forest.pkl en un unico comando."
        )
    )
    parser.add_argument(
        "--database-url",
        default=os.getenv("ANOMALY_DB_URL"),
        help="URL PostgreSQL. Por defecto usa ANOMALY_DB_URL.",
    )
    parser.add_argument("--from", dest="from_value", help="Inicio ISO 8601 inclusivo.")
    parser.add_argument("--to", dest="to_value", help="Fin ISO 8601 exclusivo.")
    parser.add_argument("--raw-output", type=Path, default=REAL_DATASET_PATH)
    parser.add_argument(
        "--processed-output", type=Path, default=REAL_PROCESSED_DATASET_PATH
    )
    parser.add_argument("--model-output", type=Path, default=MODEL_PATH)
    parser.add_argument("--metadata-output", type=Path, default=MODEL_METADATA_PATH)
    parser.add_argument("--contamination", default=str(ANOMALY_RATIO))
    parser.add_argument("--min-records", type=int, default=100)
    parser.add_argument(
        "--include-failures",
        action="store_true",
        help="Incluye intentos fallidos en el fit; no recomendado por defecto.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_arguments()
    if not args.database_url:
        raise SystemExit("Falta --database-url o la variable ANOMALY_DB_URL.")
    if args.min_records < 1:
        raise SystemExit("--min-records debe ser mayor o igual a 1.")

    events = export_real_events(
        database_url=args.database_url,
        output_path=args.raw_output,
        from_timestamp=parse_timestamp(args.from_value),
        to_timestamp=parse_timestamp(args.to_value),
        minimum_records=1,
    )
    print(f"Eventos reales exportados: {len(events)}")
    print(f"CSV sensible: {args.raw_output.resolve()}")

    processed = preprocess_file(args.raw_output, args.processed_output)
    print_summary(processed)
    print(f"Features reales: {args.processed_output.resolve()}")

    train_from_file(
        input_path=args.processed_output,
        model_path=args.model_output,
        metadata_path=args.metadata_output,
        contamination=args.contamination,
        successful_only=not args.include_failures,
        minimum_records=args.min_records,
    )


if __name__ == "__main__":
    main()
