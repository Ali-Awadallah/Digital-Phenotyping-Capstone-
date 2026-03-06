import json
import math
import os
from datetime import datetime
from typing import Tuple

import mysql.connector
import numpy as np
import pandas as pd

DB_CONFIG = {
    "host": os.getenv("DB_HOST", "localhost"),
    "port": int(os.getenv("DB_PORT", "3307")),
    "user": os.getenv("DB_USER", "aware_user"),
    "password": os.getenv("DB_PASSWORD", "password"),
    "database": os.getenv("DB_NAME", "aware_db"),
}


def connect():
    return mysql.connector.connect(
        host=DB_CONFIG["host"],
        port=DB_CONFIG["port"],
        user=DB_CONFIG["user"],
        password=DB_CONFIG["password"],
        database=DB_CONFIG["database"],
        autocommit=False,
    )


def _rows_to_df(cursor) -> pd.DataFrame:
    return pd.DataFrame(cursor.fetchall())


def _query_df(conn, query: str, params: Tuple = ()) -> pd.DataFrame:
    cursor = conn.cursor(dictionary=True)
    try:
        cursor.execute(query, params)
        return _rows_to_df(cursor)
    finally:
        cursor.close()


def _query_one(conn, query: str, params: Tuple = ()):
    cursor = conn.cursor(dictionary=True)
    try:
        cursor.execute(query, params)
        return cursor.fetchone()
    finally:
        cursor.close()


def _execute(conn, query: str, params: Tuple = ()):
    cursor = conn.cursor()
    try:
        cursor.execute(query, params)
    finally:
        cursor.close()


def _column_exists(conn, table_name: str, column_name: str) -> bool:
    row = _query_one(
        conn,
        """
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = %s AND column_name = %s
        LIMIT 1
        """,
        (table_name, column_name),
    )
    return row is not None


def _normalize_doc_value(value):
    if isinstance(value, pd.Timestamp):
        return value.to_pydatetime()
    if isinstance(value, np.generic):
        return value.item()
    if isinstance(value, float) and math.isnan(value):
        return None
    return value


def _json_default(value):
    if isinstance(value, np.generic):
        return value.item()
    if isinstance(value, pd.Timestamp):
        return value.to_pydatetime().isoformat()
    if isinstance(value, datetime):
        return value.isoformat()
    return str(value)


def json_dumps_safe(value) -> str:
    return json.dumps(value, default=_json_default)


def ensure_tables(conn):
    statements = [
        """
        CREATE TABLE IF NOT EXISTS engine_state (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            participant_id VARCHAR(128) NOT NULL,
            engine_name VARCHAR(128) NOT NULL,
            last_processed_hour_start DATETIME NULL,
            last_processed_day_start DATETIME NULL,
            last_trained_hour_start DATETIME NULL,
            threshold DOUBLE NULL,
            updated_at DATETIME NOT NULL,
            UNIQUE KEY uq_engine_state (participant_id, engine_name)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS hourly_features (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            participant_id VARCHAR(128) NOT NULL,
            hour_start DATETIME NOT NULL,
            accel_n INT NULL,
            gyro_n INT NULL,
            gps_n INT NULL,
            screen_event_n INT NULL,
            acc_mag_mean DOUBLE NULL,
            acc_mag_std DOUBLE NULL,
            acc_jerk_mean DOUBLE NULL,
            acc_mag_p95 DOUBLE NULL,
            acc_inactive_ratio DOUBLE NULL,
            gyro_mag_mean DOUBLE NULL,
            gyro_mag_std DOUBLE NULL,
            gyro_mag_p95 DOUBLE NULL,
            gps_points INT NULL,
            gps_mean_accuracy DOUBLE NULL,
            gps_distance_m DOUBLE NULL,
            gps_stationary_ratio DOUBLE NULL,
            screen_on_events INT NULL,
            screen_off_events INT NULL,
            screen_sessions INT NULL,
            screen_on_seconds DOUBLE NULL,
            screen_avg_session_seconds DOUBLE NULL,
            UNIQUE KEY uq_hourly_features (participant_id, hour_start),
            KEY idx_hourly_features_hour_start (hour_start)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS signature_alerts (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            participant_id VARCHAR(128) NOT NULL,
            hour_start DATETIME NOT NULL,
            hour_start_iso VARCHAR(32) NULL,
            hour_start_ts BIGINT NULL,
            alert_code VARCHAR(64) NOT NULL,
            alert_name VARCHAR(255) NULL,
            severity VARCHAR(32) NULL,
            score DOUBLE NULL,
            baseline_ref VARCHAR(255) NULL,
            top_features_json JSON NULL,
            explanation TEXT NULL,
            created_at DATETIME NOT NULL,
            status VARCHAR(32) NOT NULL,
            UNIQUE KEY uq_signature_alerts (participant_id, hour_start, alert_code),
            KEY idx_signature_alerts_participant (participant_id),
            KEY idx_signature_alerts_hour_start (hour_start),
            KEY idx_signature_alerts_created_at (created_at),
            KEY idx_signature_alerts_status (status)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS wearable_daily_features (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            participant_id VARCHAR(128) NOT NULL,
            day_start DATETIME NOT NULL,
            steps_total DOUBLE NULL,
            sleep_minutes DOUBLE NULL,
            sleep_episode_n INT NULL,
            sleep_start_hour DOUBLE NULL,
            sleep_end_hour DOUBLE NULL,
            sleep_midpoint_hour DOUBLE NULL,
            day_hr_mean DOUBLE NULL,
            night_hr_mean DOUBLE NULL,
            resting_hr_p10 DOUBLE NULL,
            rmssd_night DOUBLE NULL,
            sdnn_night DOUBLE NULL,
            UNIQUE KEY uq_wearable_daily_features (participant_id, day_start),
            KEY idx_wearable_daily_day_start (day_start)
        )
        """,
    ]
    for statement in statements:
        _execute(conn, statement)

    if not _column_exists(conn, "signature_alerts", "hour_start_iso"):
        _execute(conn, "ALTER TABLE signature_alerts ADD COLUMN hour_start_iso VARCHAR(32) NULL")
    if not _column_exists(conn, "signature_alerts", "hour_start_ts"):
        _execute(conn, "ALTER TABLE signature_alerts ADD COLUMN hour_start_ts BIGINT NULL")
    for column_name, ddl in [
        ("sleep_episode_n", "ALTER TABLE wearable_daily_features ADD COLUMN sleep_episode_n INT NULL"),
        ("sleep_midpoint_hour", "ALTER TABLE wearable_daily_features ADD COLUMN sleep_midpoint_hour DOUBLE NULL"),
        ("resting_hr_p10", "ALTER TABLE wearable_daily_features ADD COLUMN resting_hr_p10 DOUBLE NULL"),
        ("rmssd_night", "ALTER TABLE wearable_daily_features ADD COLUMN rmssd_night DOUBLE NULL"),
        ("sdnn_night", "ALTER TABLE wearable_daily_features ADD COLUMN sdnn_night DOUBLE NULL"),
    ]:
        if not _column_exists(conn, "wearable_daily_features", column_name):
            _execute(conn, ddl)
    conn.commit()


def fetch_participants(conn) -> list[str]:
    df = _query_df(
        conn,
        """
        SELECT DISTINCT device_id
        FROM (
            SELECT device_id FROM accelerometer
            UNION
            SELECT device_id FROM wearable_heart_rate
            UNION
            SELECT device_id FROM wearable_steps
            UNION
            SELECT device_id FROM wearable_sleep
        ) participants
        WHERE device_id IS NOT NULL
        ORDER BY device_id
        """,
    )
    if df.empty:
        return []
    return df["device_id"].astype(str).tolist()


def fetch_participant_devices(conn) -> list[dict]:
    try:
        df = _query_df(
            conn,
            """
            SELECT
                participant_id,
                MAX(CASE WHEN LOWER(COALESCE(device_type, '')) = 'phone' THEN device_id END) AS phone_device_id,
                MAX(CASE WHEN LOWER(COALESCE(device_type, '')) = 'watch' THEN device_id END) AS watch_device_id,
                MIN(name) AS name
            FROM participants
            WHERE participant_id IS NOT NULL
            GROUP BY participant_id
            ORDER BY participant_id
            """,
        )
        if not df.empty:
            rows = []
            for _, row in df.iterrows():
                participant_id = row.get("participant_id")
                if not participant_id:
                    continue
                phone_device_id = row.get("phone_device_id")
                watch_device_id = row.get("watch_device_id")
                fallback_device_id = phone_device_id or watch_device_id
                if fallback_device_id is None:
                    continue
                rows.append(
                    {
                        "participant_id": str(participant_id),
                        "phone_device_id": str(phone_device_id) if phone_device_id else None,
                        "watch_device_id": str(watch_device_id) if watch_device_id else None,
                        "name": row.get("name"),
                    }
                )
            if rows:
                return rows
    except Exception:
        pass

    return [
        {
            "participant_id": pid,
            "phone_device_id": pid,
            "watch_device_id": pid,
            "name": pid,
        }
        for pid in fetch_participants(conn)
    ]


def get_state(conn, pid: str, engine_name: str):
    return _query_one(
        conn,
        """
        SELECT participant_id, engine_name, last_processed_hour_start, last_processed_day_start,
               last_trained_hour_start, threshold
        FROM engine_state
        WHERE participant_id = %s AND engine_name = %s
        """,
        (pid, engine_name),
    )


def set_state(conn, pid: str, engine_name: str, payload: dict):
    values = {
        "last_processed_hour_start": None,
        "last_processed_day_start": None,
        "last_trained_hour_start": None,
        "threshold": None,
    }
    values.update({k: _normalize_doc_value(v) for k, v in payload.items()})
    _execute(
        conn,
        """
        INSERT INTO engine_state (
            participant_id, engine_name, last_processed_hour_start, last_processed_day_start,
            last_trained_hour_start, threshold, updated_at
        )
        VALUES (%s, %s, %s, %s, %s, %s, %s)
        ON DUPLICATE KEY UPDATE
            last_processed_hour_start = COALESCE(VALUES(last_processed_hour_start), last_processed_hour_start),
            last_processed_day_start = COALESCE(VALUES(last_processed_day_start), last_processed_day_start),
            last_trained_hour_start = COALESCE(VALUES(last_trained_hour_start), last_trained_hour_start),
            threshold = COALESCE(VALUES(threshold), threshold),
            updated_at = VALUES(updated_at)
        """,
        (
            pid,
            engine_name,
            values["last_processed_hour_start"],
            values["last_processed_day_start"],
            values["last_trained_hour_start"],
            values["threshold"],
            datetime.utcnow(),
        ),
    )
    conn.commit()


def get_time_range_ms(conn, pid: str):
    row = _query_one(
        conn,
        "SELECT MIN(timestamp) AS min_ts, MAX(timestamp) AS max_ts FROM accelerometer WHERE device_id = %s",
        (pid,),
    )
    if not row or row["min_ts"] is None:
        return None, None
    return row["min_ts"], row["max_ts"]


def load_accel(conn, pid, start_ms, end_ms):
    return _query_df(
        conn,
        """
        SELECT device_id, timestamp, x, y, z
        FROM accelerometer
        WHERE device_id = %s AND timestamp >= %s AND timestamp <= %s
        ORDER BY timestamp
        """,
        (pid, int(start_ms), int(end_ms)),
    )


def load_gyro(conn, pid, start_ms, end_ms):
    return _query_df(
        conn,
        """
        SELECT device_id, timestamp, x, y, z
        FROM gyroscope
        WHERE device_id = %s AND timestamp >= %s AND timestamp <= %s
        ORDER BY timestamp
        """,
        (pid, int(start_ms), int(end_ms)),
    )


def load_gps(conn, pid, start_ms, end_ms):
    return _query_df(
        conn,
        """
        SELECT device_id, timestamp, latitude, longitude, accuracy
        FROM location
        WHERE device_id = %s AND timestamp >= %s AND timestamp <= %s
        ORDER BY timestamp
        """,
        (pid, int(start_ms), int(end_ms)),
    )


def load_screen(conn, pid, start_ms, end_ms):
    return _query_df(
        conn,
        """
        SELECT device_id, timestamp, event
        FROM screen_events
        WHERE device_id = %s AND timestamp >= %s AND timestamp <= %s
        ORDER BY timestamp
        """,
        (pid, int(start_ms), int(end_ms)),
    )
