#!/usr/bin/env python3
"""
Display MySQL schema details for all tables in the configured database.
"""

import json
import os
import sys
from datetime import date, datetime
from decimal import Decimal

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


def json_default(value):
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, Decimal):
        return float(value)
    return str(value)


def print_schema():
    conn = mysql.connector.connect(**DB_CONFIG)
    cursor = conn.cursor()
    dict_cursor = conn.cursor(dictionary=True)

    print(f"\nDatabase: {DB_CONFIG['database']}\n{'-' * 50}")

    cursor.execute("SHOW TABLES")
    tables = [row[0] for row in cursor.fetchall()]

    if not tables:
        print("No tables found.")
        return

    for table_name in sorted(tables):
        print(f"\nTable: {table_name}")
        print("=" * 50)

        cursor.execute(f"DESCRIBE `{table_name}`")
        columns = cursor.fetchall()
        if columns:
            print("Columns:")
            for field, col_type, nullable, key, default, extra in columns:
                null_text = "NULL" if nullable == "YES" else "NOT NULL"
                default_text = f", default={default}" if default is not None else ""
                extra_text = f", extra={extra}" if extra else ""
                key_text = f", key={key}" if key else ""
                print(
                    f"  - {field}: {col_type}, {null_text}{key_text}{default_text}{extra_text}"
                )

        cursor.execute(f"SHOW INDEX FROM `{table_name}`")
        indexes = cursor.fetchall()
        if indexes:
            print("\nIndexes:")
            for _, _, index_name, non_unique, _, column_name, *_ in indexes:
                unique_text = "UNIQUE" if non_unique == 0 else "NON-UNIQUE"
                print(f"  - {index_name}: {column_name} ({unique_text})")
        else:
            print("\nNo indexes.")

        dict_cursor.execute(f"SELECT * FROM `{table_name}` LIMIT 1")
        sample = dict_cursor.fetchone()
        if sample:
            print("\nSample row:")
            print(json.dumps(sample, indent=2, default=json_default))
        else:
            print("\nNo rows in this table.")

    dict_cursor.close()
    cursor.close()
    conn.close()


if __name__ == "__main__":
    print_schema()
