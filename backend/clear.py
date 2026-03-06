#!/usr/bin/env python3
"""
Delete rows from MySQL tables in the configured database.

By default this script clears all app tables. It prompts before deleting.
"""

import os
import sys
from pathlib import Path

try:
    import mysql.connector
except ImportError:
    print("ERROR: mysql-connector-python not installed.")
    print("Run: pip install mysql-connector-python")
    sys.exit(1)


DB_CONFIG = {
    "host": os.getenv("DB_HOST", "localhost"),
    "port": int(os.getenv("DB_PORT", "3307")),
    "user": os.getenv("DB_USER", "aware_user"),
    "password": os.getenv("DB_PASSWORD", "password"),
    "database": os.getenv("DB_NAME", "aware_db"),
}

DEFAULT_TABLES = [
    "signature_alerts",
    "signature_alerts_archive",
    "signature_alerts_legacy",
    "engine_state",
    "hourly_features",
    "wearable_daily_features",
    "anomaly_hours",
    "participants",
    "accelerometer",
    "gyroscope",
    "location",
    "battery_readings",
    "screen_events",
    "notifications",
    "wearable_heart_rate",
    "wearable_steps",
    "wearable_sleep",
    "wearable_blood_pressure",
    "wearable_weight",
    "wearable_oxygen",
    "wearable_respiratory",
    "watch_day_profiles",
    "red_zones",
    "geofence_alerts",
    "aware_db_geofence_alerts",
]

FRESH_LEARNING_TABLES = [
    "engine_state",
    "hourly_features",
    "wearable_daily_features",
    "signature_alerts",
    "signature_alerts_archive",
    "signature_alerts_legacy",
    "anomaly_hours",
    "watch_day_profiles",
]

MODELS_DIR = Path(__file__).resolve().parent / "models"


def prompt_yes_no(question: str, default: bool = False) -> bool:
    suffix = "[Y/n]" if default else "[y/N]"
    answer = input(f"{question} {suffix} ").strip().lower()
    if not answer:
        return default
    return answer in {"y", "yes"}


def fetch_existing_tables(cursor) -> set[str]:
    cursor.execute("SHOW TABLES")
    return {row[0] for row in cursor.fetchall()}


def prompt_mode() -> str:
    print("Select clear mode:")
    print("  1. Clear all app tables")
    print("  2. Fresh learning reset (derived tables + model files)")
    while True:
        answer = input("Choose 1 or 2 [1]: ").strip()
        if answer in {"", "1"}:
            return "all"
        if answer == "2":
            return "fresh_learning"
        print("Please enter 1 or 2.")


def truncate_tables(cursor, conn, tables_to_clear: list[str]) -> int:
    cursor.execute("SET FOREIGN_KEY_CHECKS = 0")
    deleted_tables = []
    try:
        for table_name in tables_to_clear:
            cursor.execute(f"TRUNCATE TABLE `{table_name}`")
            deleted_tables.append(table_name)
            print(f"  cleared {table_name}")
        conn.commit()
    finally:
        cursor.execute("SET FOREIGN_KEY_CHECKS = 1")
        conn.commit()
    return len(deleted_tables)


def delete_model_files() -> int:
    if not MODELS_DIR.exists():
        return 0
    deleted = 0
    for path in MODELS_DIR.iterdir():
        if path.name == ".gitkeep":
            continue
        if path.suffix == ".pt" or path.name.endswith("_scaler.json"):
            path.unlink(missing_ok=True)
            deleted += 1
            print(f"  deleted model file {path.name}")
    return deleted


def clear_tables():
    mode = prompt_mode()
    conn = mysql.connector.connect(**DB_CONFIG)
    cursor = conn.cursor()

    print(f"Connected to MySQL database: {DB_CONFIG['database']} @ {DB_CONFIG['host']}:{DB_CONFIG['port']}")

    existing_tables = fetch_existing_tables(cursor)
    requested_tables = DEFAULT_TABLES if mode == "all" else FRESH_LEARNING_TABLES
    tables_to_clear = [table for table in requested_tables if table in existing_tables]

    if not tables_to_clear:
        print("No known tables found to clear.")
        cursor.close()
        conn.close()
        return

    print("Tables that will be cleared:")
    for table_name in tables_to_clear:
        print(f"  - {table_name}")
    if mode == "fresh_learning":
        print("Model files that will be deleted:")
        print(f"  - {MODELS_DIR}")

    confirm_text = "Run fresh learning reset?" if mode == "fresh_learning" else "Delete all rows from these tables?"
    if not prompt_yes_no(confirm_text, default=False):
        print("Aborted.")
        cursor.close()
        conn.close()
        return

    try:
        truncated_count = truncate_tables(cursor, conn, tables_to_clear)
    finally:
        cursor.close()
        conn.close()

    deleted_models = delete_model_files() if mode == "fresh_learning" else 0

    if mode == "fresh_learning":
        print(f"Fresh learning reset complete. Tables truncated: {truncated_count}, model files deleted: {deleted_models}")
    else:
        print(f"Database cleared. Tables truncated: {truncated_count}")


if __name__ == "__main__":
    clear_tables()
