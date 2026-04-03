#!/usr/bin/env python3
"""
AWARE synthetic data generator -> MySQL (DBeaver-friendly)

What this version improves:
- Generates MUCH more data (defaults: 10 devices, 60 days).
- Produces realistic daily rhythms (sleep vs day vs evening) for better LSTM learning.
- Injects controlled anomalies (default: 5% of hours) WITHOUT overwhelming training.
- Stores anomaly labels in a dedicated table: anomaly_hours
  -> so you can train LSTM on normal-only hours, then evaluate on mixed data.
- Uses existing backend-created tables and inserts in large batches.

Requirements:
  pip install sqlalchemy pymysql numpy haversine

Run:
  python generate_aware_mysql_v2.py

Environment overrides (optional):
  DEVICES=20 DAYS=90 ANOMALY_HOUR_RATE=0.03 MYSQL_HOST=127.0.0.1 MYSQL_PORT=3307 ...
"""

import argparse
import math
import os
import random
import platform
import time
from datetime import datetime, timedelta, timezone

import numpy as np
import haversine  # noqa: F401 (kept for compatibility if you used it elsewhere)

from sqlalchemy import (
    create_engine, MetaData, Table, Column, BigInteger, Integer, Float, String,
    Text, DateTime, text
)
from sqlalchemy.exc import SQLAlchemyError


# ------------------ CONFIGURATION (env overridable) ------------------

MYSQL_HOST = os.getenv("DB_HOST", os.getenv("MYSQL_HOST", "127.0.0.1"))
MYSQL_PORT = int(os.getenv("DB_PORT", os.getenv("MYSQL_PORT", "3307")))   # docker published port
MYSQL_DB = os.getenv("DB_NAME", os.getenv("MYSQL_DB", "aware_db"))
MYSQL_USER = os.getenv("DB_USER", os.getenv("MYSQL_USER", "aware_user"))
MYSQL_PASSWORD = os.getenv("DB_PASSWORD", os.getenv("MYSQL_PASSWORD", "password"))

DEVICES = int(os.getenv("DEVICES", "10"))           # ↑ increase for more volume
DAYS = int(os.getenv("DAYS", "60"))                 # ↑ increase for more history
START_DATE = datetime.now(timezone.utc).replace(tzinfo=None) - timedelta(days=DAYS)
HISTORICAL_DAYS_DEFAULT = int(os.getenv("HISTORICAL_DAYS_DEFAULT", "180"))

# Sampling rates (kept moderate so volume is big but not insane)
ACCEL_FREQ_SEC = int(os.getenv("ACCEL_FREQ_SEC", "60"))       # 1/min
GYRO_FREQ_SEC = int(os.getenv("GYRO_FREQ_SEC", "60"))         # 1/min
LOC_FREQ_SEC = int(os.getenv("LOC_FREQ_SEC", "300"))          # 1/5 min
HR_FREQ_SEC = int(os.getenv("HR_FREQ_SEC", "60"))             # 1/min
STEPS_FREQ_SEC = int(os.getenv("STEPS_FREQ_SEC", "300"))      # 1/5 min

# "Hourly" signals (use deterministic on-the-hour inserts)
BATTERY_ON_THE_HOUR = True
OXYGEN_ON_THE_HOUR = True
RESPIRATORY_ON_THE_HOUR = True

NOTIFICATIONS_PER_DAY_MIN = int(os.getenv("NOTIFS_MIN", "5"))
NOTIFICATIONS_PER_DAY_MAX = int(os.getenv("NOTIFS_MAX", "30"))

SLEEP_PER_DAY = os.getenv("SLEEP_PER_DAY", "true").lower() == "true"
BP_FREQ_DAY_MIN = int(os.getenv("BP_MIN", "1"))
BP_FREQ_DAY_MAX = int(os.getenv("BP_MAX", "3"))
WEIGHT_FREQ_DAY_MIN = int(os.getenv("WEIGHT_MIN", "0"))
WEIGHT_FREQ_DAY_MAX = int(os.getenv("WEIGHT_MAX", "1"))

# Mobility random walk
EARTH_RADIUS = 6371000
NORMAL_STEP_SIZE_M = float(os.getenv("NORMAL_STEP_SIZE_M", "10"))
HIGH_MOBILITY_STEP_SIZE_M = float(os.getenv("HIGH_MOBILITY_STEP_SIZE_M", "500"))
LOW_MOBILITY_STEP_SIZE_M = float(os.getenv("LOW_MOBILITY_STEP_SIZE_M", "0.1"))

# Anomaly injection (percentage of hours per device)
ANOMALY_HOUR_RATE = float(os.getenv("ANOMALY_HOUR_RATE", "0.08"))
ANOMALY_TYPES = ["AS1", "DS1", "DS2", "AS2", "SU1", "SU2", "SU3"]

# Batch insert sizes
CHUNK_SIZE = int(os.getenv("CHUNK_SIZE", "5000"))
LIVE_SLEEP_SEC = int(os.getenv("LIVE_SLEEP_SEC", "30"))
LIVE_LAG_MINUTES = int(os.getenv("LIVE_LAG_MINUTES", "5"))
LIVE_BOOTSTRAP_HOURS = int(os.getenv("LIVE_BOOTSTRAP_HOURS", "6"))

PHONE_TABLE_NAMES = {
    "accelerometer",
    "gyroscope",
    "location",
    "battery_readings",
    "screen_events",
    "notifications",
}
WATCH_TABLE_NAMES = {
    "wearable_heart_rate",
    "wearable_steps",
    "wearable_sleep",
    "wearable_blood_pressure",
    "wearable_weight",
    "wearable_oxygen",
    "wearable_respiratory",
    "watch_day_profiles",
}
CORE_TABLE_NAMES = {"participants", "participant_devices", "anomaly_hours"}
DERIVED_TABLE_NAMES = {
    "engine_state",
    "hourly_features",
    "wearable_daily_features",
    "signature_alerts",
    "signature_alerts_archive",
    "signature_alerts_legacy",
    "geofence_alerts",
    "aware_db_geofence_alerts",
}


# ------------------ HELPERS ------------------

def now_iso():
    return datetime.now(timezone.utc).replace(tzinfo=None).isoformat()


def now_dt():
    return datetime.now(timezone.utc).replace(tzinfo=None)

def random_bearing():
    return random.uniform(0, 360)

def haversine_step(lat, lon, d, bearing):
    """Move (lat, lon) by distance d (meters) in direction bearing (degrees)."""
    lat1 = np.radians(lat)
    lon1 = np.radians(lon)
    ang_dist = d / EARTH_RADIUS
    bearing = np.radians(bearing)
    lat2 = np.arcsin(np.sin(lat1) * np.cos(ang_dist) +
                     np.cos(lat1) * np.sin(ang_dist) * np.cos(bearing))
    lon2 = lon1 + np.arctan2(np.sin(bearing) * np.sin(ang_dist) * np.cos(lat1),
                             np.cos(ang_dist) - np.sin(lat1) * np.sin(lat2))
    return float(np.degrees(lat2)), float(np.degrees(lon2))


def bearing_towards(lat1, lon1, lat2, lon2):
    lat1 = math.radians(lat1)
    lon1 = math.radians(lon1)
    lat2 = math.radians(lat2)
    lon2 = math.radians(lon2)
    dlon = lon2 - lon1
    y = math.sin(dlon) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dlon)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def device_geo_profile(device_id: str):
    seed = sum((idx + 1) * ord(ch) for idx, ch in enumerate(device_id))
    rng = random.Random(seed)
    home_lat = 25.15 + rng.random() * 0.8
    home_lon = 51.0 + rng.random() * 1.0
    risk_distance_m = 450.0 + rng.random() * 350.0
    risk_bearing = rng.random() * 360.0
    risk_lat, risk_lon = haversine_step(home_lat, home_lon, risk_distance_m, risk_bearing)
    return {
        "home_lat": home_lat,
        "home_lon": home_lon,
        "risk_lat": risk_lat,
        "risk_lon": risk_lon,
        "risk_radius": 220,
    }

def format_utc_time(ts_ms):
    """Convert timestamp ms to string like '2/16/2026 15:07' (no leading zeros)."""
    dt = datetime.fromtimestamp(ts_ms / 1000.0, timezone.utc).replace(tzinfo=None)
    if platform.system() == "Windows":
        fmt = "%#m/%#d/%Y %H:%M"
    else:
        fmt = "%-m/%-d/%Y %H:%M"
    return dt.strftime(fmt)


def utc_dt(ts_ms):
    return datetime.fromtimestamp(ts_ms / 1000.0, timezone.utc).replace(tzinfo=None)

def make_engine():
    url = (
        f"mysql+pymysql://{MYSQL_USER}:{MYSQL_PASSWORD}"
        f"@{MYSQL_HOST}:{MYSQL_PORT}/{MYSQL_DB}?charset=utf8mb4"
    )
    return create_engine(url, pool_pre_ping=True, future=True)


# ------------------ SCHEMA ------------------

def define_tables(metadata: MetaData):
    participants = Table(
        "participants", metadata,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("participant_id", String(128), nullable=False),
        Column("device_id", String(128), nullable=False, index=True),
        Column("device_type", String(32), nullable=False),
        Column("name", String(255), nullable=False),
        Column("red_zone_radius", Integer, nullable=False),
        Column("status", String(50), nullable=False),
        Column("risk_level", String(50), nullable=False),
        Column("created_at", Text, nullable=False),
        Column("updated_at", Text, nullable=False),
    )

    participant_devices = Table(
        "participant_devices", metadata,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("participant_id", String(128), nullable=False),
        Column("device_id", String(128), nullable=False),
        Column("device_type", String(32), nullable=False),
        Column("is_primary", Integer, nullable=False),
        Column("created_at", DateTime, nullable=True, server_default=text("CURRENT_TIMESTAMP")),
        Column("updated_at", DateTime, nullable=True, server_default=text("CURRENT_TIMESTAMP")),
    )

    anomaly_hours = Table(
        "anomaly_hours", metadata,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("device_id", String(128), index=True, nullable=False),
        Column("hour_start_ts", BigInteger, index=True, nullable=False),
        Column("hour_start_utc", String(32), nullable=False),
        Column("anomaly_type", String(32), nullable=False),
        Column("created_at", Text, nullable=False),
    )

    accelerometer = Table(
        "accelerometer", metadata,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("device_id", String(128), index=True, nullable=False),
        Column("timestamp", BigInteger, index=True, nullable=False),
        Column("utc_time", DateTime, nullable=True),
        Column("x", Float, nullable=True),
        Column("y", Float, nullable=True),
        Column("z", Float, nullable=True),
        Column("created_at", DateTime, nullable=True, server_default=text("CURRENT_TIMESTAMP")),
    )

    gyroscope = Table(
        "gyroscope", metadata,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("device_id", String(128), index=True, nullable=False),
        Column("timestamp", BigInteger, index=True, nullable=False),
        Column("utc_time", DateTime, nullable=True),
        Column("x", Float, nullable=True),
        Column("y", Float, nullable=True),
        Column("z", Float, nullable=True),
        Column("created_at", DateTime, nullable=True, server_default=text("CURRENT_TIMESTAMP")),
    )

    location = Table(
        "location", metadata,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("device_id", String(128), index=True, nullable=False),
        Column("timestamp", BigInteger, index=True, nullable=False),
        Column("utc_time", DateTime, nullable=True),
        Column("latitude", Float, nullable=True),
        Column("longitude", Float, nullable=True),
        Column("altitude", Float, nullable=True),
        Column("accuracy", Float, nullable=True),
        Column("created_at", DateTime, nullable=True, server_default=text("CURRENT_TIMESTAMP")),
    )

    battery_readings = Table(
        "battery_readings", metadata,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("device_id", String(128), index=True, nullable=False),
        Column("timestamp", BigInteger, index=True, nullable=False),
        Column("percentage", Float, nullable=False),
        Column("charging_status", String(20), nullable=True, server_default=text("'unknown'")),
        Column("created_at", DateTime, nullable=True, server_default=text("CURRENT_TIMESTAMP")),
    )

    screen_events = Table(
        "screen_events", metadata,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("device_id", String(128), index=True, nullable=False),
        Column("timestamp", BigInteger, index=True, nullable=False),
        Column("utc_time", DateTime, nullable=True),
        Column("event", String(50), nullable=False),
        Column("created_at", DateTime, nullable=True, server_default=text("CURRENT_TIMESTAMP")),
    )

    notifications = Table(
        "notifications", metadata,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("device_id", String(128), index=True, nullable=False),
        Column("app_name", String(256), nullable=True),
        Column("title", Text, nullable=True),
        Column("content", Text, nullable=True),
        Column("category", String(50), nullable=True),
        Column("kind", String(20), nullable=True, server_default=text("'posted'")),
        Column("timestamp", BigInteger, index=True, nullable=False),
        Column("dismissed_at", BigInteger, nullable=True),
        Column("created_at", DateTime, nullable=True, server_default=text("CURRENT_TIMESTAMP")),
    )

    wearable_heart_rate = Table(
        "wearable_heart_rate", metadata,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("device_id", String(128), index=True, nullable=False),
        Column("timestamp", BigInteger, index=True, nullable=False),
        Column("bpm", Integer, nullable=False),
        Column("created_at", DateTime, nullable=True, server_default=text("CURRENT_TIMESTAMP")),
    )

    wearable_steps = Table(
        "wearable_steps", metadata,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("device_id", String(128), index=True, nullable=False),
        Column("start_time", BigInteger, index=True, nullable=False),
        Column("end_time", BigInteger, index=True, nullable=False),
        Column("count", Integer, nullable=False),
        Column("created_at", DateTime, nullable=True, server_default=text("CURRENT_TIMESTAMP")),
    )

    wearable_sleep = Table(
        "wearable_sleep", metadata,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("device_id", String(128), index=True, nullable=False),
        Column("start_time", BigInteger, index=True, nullable=False),
        Column("end_time", BigInteger, index=True, nullable=False),
        Column("title", String(256), nullable=True, server_default=text("'Sleep'")),
        Column("notes", Text, nullable=True),
        Column("created_at", DateTime, nullable=True, server_default=text("CURRENT_TIMESTAMP")),
    )

    wearable_blood_pressure = Table(
        "wearable_blood_pressure", metadata,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("device_id", String(128), index=True, nullable=False),
        Column("timestamp", BigInteger, index=True, nullable=False),
        Column("systolic", Integer, nullable=False),
        Column("diastolic", Integer, nullable=False),
        Column("created_at", DateTime, nullable=True, server_default=text("CURRENT_TIMESTAMP")),
    )

    wearable_weight = Table(
        "wearable_weight", metadata,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("device_id", String(128), index=True, nullable=False),
        Column("timestamp", BigInteger, index=True, nullable=False),
        Column("weight_kg", Float, nullable=False),
        Column("created_at", DateTime, nullable=True, server_default=text("CURRENT_TIMESTAMP")),
    )

    wearable_oxygen = Table(
        "wearable_oxygen", metadata,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("device_id", String(128), index=True, nullable=False),
        Column("timestamp", BigInteger, index=True, nullable=False),
        Column("percentage", Float, nullable=False),
        Column("created_at", DateTime, nullable=True, server_default=text("CURRENT_TIMESTAMP")),
    )

    wearable_respiratory = Table(
        "wearable_respiratory", metadata,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("device_id", String(128), index=True, nullable=False),
        Column("timestamp", BigInteger, index=True, nullable=False),
        Column("rate", Float, nullable=False),
        Column("created_at", DateTime, nullable=True, server_default=text("CURRENT_TIMESTAMP")),
    )

    watch_day_profiles = Table(
        "watch_day_profiles", metadata,
        Column("id", BigInteger, primary_key=True, autoincrement=True),
        Column("device_id", String(128), index=True, nullable=False),
        Column("day_start", BigInteger, index=True, nullable=False),
        Column("profile", String(32), nullable=False),
        Column("created_at", Text, nullable=False),
    )

    return {
        "participants": participants,
        "participant_devices": participant_devices,
        "anomaly_hours": anomaly_hours,
        "accelerometer": accelerometer,
        "gyroscope": gyroscope,
        "location": location,
        "battery_readings": battery_readings,
        "screen_events": screen_events,
        "notifications": notifications,
        "wearable_heart_rate": wearable_heart_rate,
        "wearable_steps": wearable_steps,
        "wearable_sleep": wearable_sleep,
        "wearable_blood_pressure": wearable_blood_pressure,
        "wearable_weight": wearable_weight,
        "wearable_oxygen": wearable_oxygen,
        "wearable_respiratory": wearable_respiratory,
        "watch_day_profiles": watch_day_profiles,
    }


def truncate_all(conn, table_names):
    existing_tables = {
        row[0]
        for row in conn.execute(text("SHOW TABLES")).fetchall()
    }
    conn.execute(text("SET FOREIGN_KEY_CHECKS=0;"))
    for t in table_names:
        if t in existing_tables:
            conn.execute(text(f"TRUNCATE TABLE `{t}`;"))
            print(f"Cleared {t}")
    conn.execute(text("SET FOREIGN_KEY_CHECKS=1;"))


def require_tables_exist(conn, table_names: set[str]):
    existing_tables = {row[0] for row in conn.execute(text("SHOW TABLES")).fetchall()}
    missing = sorted(name for name in table_names if name not in existing_tables)
    if missing:
        raise RuntimeError(
            "Missing required tables: "
            + ", ".join(missing)
            + ". Start the Kotlin backend first so it creates schema on startup."
        )


def read_table_columns(conn, table_names: set[str]) -> dict[str, set[str]]:
    schema: dict[str, set[str]] = {}
    existing_tables = {row[0] for row in conn.execute(text("SHOW TABLES")).fetchall()}
    for table_name in table_names:
        if table_name not in existing_tables:
            continue
        rows = conn.execute(text(f"SHOW COLUMNS FROM `{table_name}`")).fetchall()
        schema[table_name] = {str(row[0]) for row in rows}
    return schema


def reset_tables_for_mode(selected_tables: set[str]) -> list[str]:
    table_names = set(CORE_TABLE_NAMES | DERIVED_TABLE_NAMES | selected_tables)
    if selected_tables & PHONE_TABLE_NAMES:
        table_names.add("red_zones")
    return sorted(table_names)


def resolve_mode_tables(mode: str):
    if mode == "phone":
        return set(PHONE_TABLE_NAMES)
    if mode == "watch":
        return set(WATCH_TABLE_NAMES)
    return set(PHONE_TABLE_NAMES | WATCH_TABLE_NAMES)


def prompt_yes_no(question: str, default: bool = False) -> bool:
    suffix = "[Y/n]" if default else "[y/N]"
    answer = input(f"{question} {suffix} ").strip().lower()
    if not answer:
        return default
    return answer in {"y", "yes"}


def prompt_mode() -> str:
    while True:
        answer = input("Generate data for phone, watch, or both? [phone/watch/both] ").strip().lower()
        if answer in {"phone", "watch", "both"}:
            return answer
        print("Please enter phone, watch, or both.")


def prompt_signature_focus() -> str:
    while True:
        answer = input("Bias generated signatures toward phone, watch, or both? [phone/watch/both] ").strip().lower()
        if answer in {"phone", "watch", "both"}:
            return answer
        print("Please enter phone, watch, or both.")


def prompt_live_mode() -> bool:
    return prompt_yes_no("Run in live continuous mode until you stop it?", default=False)


def prompt_historical_days(default_days: int) -> int:
    while True:
        answer = input(f"How many historical days should be generated? [{default_days}] ").strip()
        if not answer:
            return default_days
        try:
            days = int(answer)
        except ValueError:
            print("Please enter a whole number of days.")
            continue
        if days <= 0:
            print("Please enter a positive number of days.")
            continue
        return days


def bulk_insert(conn, table: Table, rows, chunk_size: int, allowed_columns: set[str] | None = None):
    if not rows:
        return 0
    total = 0
    for i in range(0, len(rows), chunk_size):
        chunk = rows[i:i + chunk_size]
        if allowed_columns is not None:
            chunk = [{k: v for k, v in row.items() if k in allowed_columns} for row in chunk]
        if not chunk:
            continue
        conn.execute(table.insert(), chunk)
        total += len(chunk)
    return total


# ------------------ ANOMALY PLAN ------------------

def choose_anomaly_for_hour(hour_start_dt, signature_focus="both"):
    if random.random() >= ANOMALY_HOUR_RATE:
        return None
    if signature_focus == "watch":
        return None
    is_night = hour_start_dt.hour in [23, 0, 1, 2, 3, 4, 5]
    if is_night:
        return random.choices(
            ["AS1", "SU3", "DS2"],
            weights=[0.55, 0.25, 0.20] if signature_focus == "both" else [0.60, 0.15, 0.25],
            k=1,
        )[0]
    return random.choices(
        ["DS1", "DS2", "AS2", "SU1", "SU2", "SU3"],
        weights=[0.15, 0.18, 0.17, 0.18, 0.12, 0.20] if signature_focus == "both" else [0.14, 0.18, 0.18, 0.18, 0.17, 0.15],
        k=1,
    )[0]

def build_anomaly_dict(start_dt, end_dt, signature_focus="both"):
    """
    Returns:
      anom_dict: {hour_start_datetime: anomaly_type}
      anom_rows: list of rows for anomaly_hours table
    """
    anom_dict = {}
    anom_rows = []

    hour_slots = []
    cur = start_dt.replace(minute=0, second=0, microsecond=0)
    while cur < end_dt:
        hour_slots.append(cur)
        cur += timedelta(hours=1)

    if signature_focus == "watch" or not hour_slots:
        return anom_dict, anom_rows

    target_count = max(1, int(round(len(hour_slots) * ANOMALY_HOUR_RATE)))
    target_count = min(target_count, max(1, len(hour_slots) // 3))
    chosen_hours = set(random.sample(hour_slots, target_count))

    for hour_start in hour_slots:
        if hour_start not in chosen_hours:
            continue
        atype = choose_anomaly_for_hour(hour_start, signature_focus=signature_focus)
        if atype is None:
            atype = "DS2" if hour_start.hour in [23, 0, 1, 2, 3, 4, 5] else "SU3"
        anom_dict[hour_start] = atype

        ts = int(hour_start.timestamp() * 1000)
        anom_rows.append({
            "device_id": None,
            "hour_start_ts": ts,
            "hour_start_utc": format_utc_time(ts),
            "anomaly_type": atype,
            "created_at": now_iso(),
        })

    return anom_dict, anom_rows


def get_day_profile(device_id: str, day_start: datetime, signature_focus="both") -> str:
    if not hasattr(get_day_profile, "_state"):
        get_day_profile._state = {}
    key = (device_id, day_start.date(), signature_focus)
    if key in get_day_profile._state:
        return get_day_profile._state[key]

    prev_key = (device_id, (day_start - timedelta(days=1)).date(), signature_focus)
    prev_profile = get_day_profile._state.get(prev_key)
    if prev_profile == "wd1" and random.random() < 0.80:
        profile = "wd1"
    elif prev_profile == "wb2" and random.random() < 0.50:
        profile = "wb2"
    elif prev_profile == "wb1" and random.random() < 0.45:
        profile = "wb1"
    else:
        if signature_focus == "phone":
            profile = "normal"
        else:
            profile = random.choices(
                ["normal", "wa1", "wa2", "wd1", "wd2", "wb1", "wb2"],
                weights=[0.40, 0.10, 0.12, 0.12, 0.08, 0.09, 0.09] if signature_focus == "watch" else [0.58, 0.08, 0.09, 0.09, 0.06, 0.05, 0.05],
                k=1,
            )[0]
    get_day_profile._state[key] = profile
    return profile


# ------------------ DATA GENERATION ------------------

def generate_hour_data(device_id, device_type, hour_start_dt, anomaly_type=None, signature_focus="both"):
    """
    Produces realistic rhythms:
      - Sleep hours: very low movement/rotation, low steps, stable HR.
      - Work hours: moderate movement, moderate HR.
      - Evening: light movement, some screen time.
    Then anomaly types override:
      AS1: high movement+rotation at night + long screen on
      DS1: extremely low motion (near-stationary)
      SU1/SU3: high mobility or strong phone engagement
    """
    hour_end_dt = hour_start_dt + timedelta(hours=1)
    is_sleep_hours = hour_start_dt.hour in [23, 0, 1, 2, 3, 4, 5]
    is_work_hours = 8 <= hour_start_dt.hour <= 18
    day_profile = get_day_profile(
        device_id,
        hour_start_dt.replace(hour=0, minute=0, second=0, microsecond=0),
        signature_focus=signature_focus,
    )

    # base normal parameters (will be adjusted)
    screen_mode = "normal"
    gyro_variance = 0.05
    acc_variance = 0.003
    step_size = NORMAL_STEP_SIZE_M
    hr_low, hr_high = 60, 100

    if anomaly_type is None:
        if is_sleep_hours:
            screen_mode = "rare"
            gyro_variance = 0.01
            acc_variance = 0.001
            step_size = 1.0
            hr_low, hr_high = 52, 72
        elif is_work_hours:
            screen_mode = "normal"
            gyro_variance = 0.06
            acc_variance = 0.004
            step_size = 20.0
            hr_low, hr_high = 65, 105
        else:
            screen_mode = "normal"
            gyro_variance = 0.04
            acc_variance = 0.003
            step_size = 10.0
            hr_low, hr_high = 60, 95

    if day_profile == "wa1" and is_sleep_hours:
        hr_low, hr_high = 78, 125
    elif day_profile == "wa2":
        if is_sleep_hours and random.random() < 0.35:
            screen_mode = "long_on"
        hr_low, hr_high = max(hr_low, 62), max(hr_high, 98)
    elif day_profile == "wd1":
        gyro_variance = min(gyro_variance, 0.01)
        acc_variance = min(acc_variance, 0.001)
        step_size = min(step_size, 1.0)
        hr_low, hr_high = 54, 76
    elif day_profile == "wd2":
        if is_sleep_hours:
            hr_low, hr_high = 76, 120
        gyro_variance = min(gyro_variance, 0.02)
        acc_variance = min(acc_variance, 0.002)
        step_size = min(step_size, 4.0)
    elif day_profile == "wb1":
        step_size = random.choice([2.0, 15.0, 80.0, 150.0])
        gyro_variance = random.choice([0.01, 0.04, 0.12])
        acc_variance = random.choice([0.001, 0.003, 0.02])
    elif day_profile == "wb2":
        screen_mode = "many_sessions" if not is_sleep_hours else "long_on"
        gyro_variance = max(gyro_variance, 0.12)
        acc_variance = max(acc_variance, 0.015)
        step_size = max(step_size, 120.0)
        hr_low, hr_high = 82, 138

    # anomaly overrides
    if anomaly_type == "AS1" and is_sleep_hours:
        screen_mode = "long_on"
        gyro_variance = 0.30
        acc_variance = 0.05
        step_size = NORMAL_STEP_SIZE_M
        hr_low, hr_high = 75, 130
    elif anomaly_type == "DS1":
        screen_mode = "normal"
        gyro_variance = 0.001
        acc_variance = 0.0001
        step_size = LOW_MOBILITY_STEP_SIZE_M
        hr_low, hr_high = 55, 80
    elif anomaly_type == "DS2":
        screen_mode = "long_on"
        gyro_variance = 0.004
        acc_variance = 0.0005
        step_size = 0.5
        hr_low, hr_high = 58, 82
    elif anomaly_type == "AS2":
        screen_mode = "normal"
        gyro_variance = 0.08
        acc_variance = 0.006
        step_size = 300.0
        hr_low, hr_high = 72, 112
    elif anomaly_type == "SU1":
        screen_mode = "many_sessions"
        gyro_variance = 0.10
        acc_variance = 0.012
        step_size = HIGH_MOBILITY_STEP_SIZE_M
        hr_low, hr_high = 70, 140
    elif anomaly_type == "SU2":
        screen_mode = "normal"
        gyro_variance = 0.02
        acc_variance = 0.003
        step_size = 140.0
        hr_low, hr_high = 66, 108
    elif anomaly_type == "SU3":
        screen_mode = "many_sessions"
        gyro_variance = 0.03
        acc_variance = 0.002
        step_size = 12.0
        hr_low, hr_high = 65, 105

    # per-device persistent state keys
    if not hasattr(generate_hour_data, "_state"):
        generate_hour_data._state = {}

    geo = device_geo_profile(device_id)
    state = generate_hour_data._state.setdefault(device_id, {
        "lat": geo["home_lat"],
        "lon": geo["home_lon"],
        "bearing": random_bearing(),
        "steps_accum": 0,
        "steps_last_time": hour_start_dt,
        "battery_level": random.uniform(25, 90),
        "charging": random.choice([True, False]),
        "home_lat": geo["home_lat"],
        "home_lon": geo["home_lon"],
        "risk_lat": geo["risk_lat"],
        "risk_lon": geo["risk_lon"],
        "risk_radius": geo["risk_radius"],
    })

    lat, lon = state["lat"], state["lon"]
    bearing = state["bearing"]
    steps_accum = state["steps_accum"]
    steps_last_time = state["steps_last_time"]
    battery_level = state["battery_level"]
    charging = state["charging"]

    if device_type == "phone":
        rows = {
            "accelerometer": [],
            "gyroscope": [],
            "location": [],
            "battery_readings": [],
            "screen_events": [],
        }
    elif device_type == "watch":
        rows = {
            "wearable_heart_rate": [],
            "wearable_steps": [],
            "wearable_oxygen": [],
            "wearable_respiratory": [],
        }
    else:
        raise ValueError(f"Unsupported device_type: {device_type}")

    # screen sessions in this hour
    screen_events_this_hour = []
    if screen_mode == "long_on":
        on_time = hour_start_dt + timedelta(seconds=random.randint(0, 300))
        off_time = on_time + timedelta(seconds=random.randint(3000, 3500))
        if off_time > hour_end_dt:
            off_time = hour_end_dt - timedelta(seconds=1)
        screen_events_this_hour += [("on", on_time), ("off", off_time)]
    elif screen_mode == "many_sessions":
        for _ in range(random.randint(8, 15)):
            on_time = hour_start_dt + timedelta(seconds=random.randint(0, 3600))
            off_time = on_time + timedelta(seconds=random.randint(30, 120))
            if off_time > hour_end_dt:
                off_time = hour_end_dt - timedelta(seconds=1)
            screen_events_this_hour += [("on", on_time), ("off", off_time)]
    elif screen_mode == "rare":
        # maybe 0-1 short sessions
        if random.random() < 0.3:
            on_time = hour_start_dt + timedelta(seconds=random.randint(0, 3600))
            off_time = on_time + timedelta(seconds=random.randint(15, 60))
            if off_time > hour_end_dt:
                off_time = hour_end_dt - timedelta(seconds=1)
            screen_events_this_hour += [("on", on_time), ("off", off_time)]
    else:
        for _ in range(random.randint(1, 3)):
            on_time = hour_start_dt + timedelta(seconds=random.randint(0, 3600))
            off_time = on_time + timedelta(seconds=random.randint(60, 600))
            if off_time > hour_end_dt:
                off_time = hour_end_dt - timedelta(seconds=1)
            screen_events_this_hour += [("on", on_time), ("off", off_time)]
    screen_events_this_hour.sort(key=lambda x: x[1])

    def iter_times(start_dt, end_dt, interval_sec):
        current = start_dt
        while current < end_dt:
            yield current
            current += timedelta(seconds=max(1, interval_sec))

    def steps_for_window():
        if anomaly_type == "SU1":
            return random.randint(80, 400)
        if anomaly_type == "SU3":
            return random.randint(15, 80)
        if anomaly_type == "DS2":
            return random.randint(0, 10)
        if day_profile == "wd1":
            return random.randint(0, 8)
        if day_profile == "wb2":
            return random.randint(120, 320)
        if is_work_hours and anomaly_type is None:
            return random.randint(20, 180)
        if is_sleep_hours and anomaly_type is None:
            return random.randint(0, 15)
        return random.randint(0, 90)

    hour_ts_ms = int(hour_start_dt.timestamp() * 1000)
    if device_type == "phone" and BATTERY_ON_THE_HOUR:
        if random.random() < 0.03:
            charging = not charging
        battery_level = min(100.0, battery_level + random.uniform(0.5, 2.0)) if charging else max(0.0, battery_level - random.uniform(0.2, 1.2))
        status = "charging" if charging else "unplugged"
        rows["battery_readings"].append({
            "device_id": device_id,
            "timestamp": hour_ts_ms,
            "percentage": float(battery_level),
            "charging_status": status,
            "created_at": now_dt(),
        })

    if device_type == "watch" and OXYGEN_ON_THE_HOUR:
        rows["wearable_oxygen"].append({
            "device_id": device_id,
            "timestamp": hour_ts_ms,
            "percentage": float(random.uniform(94.0, 100.0)),
            "created_at": now_dt(),
        })

    if device_type == "watch" and RESPIRATORY_ON_THE_HOUR:
        rows["wearable_respiratory"].append({
            "device_id": device_id,
            "timestamp": hour_ts_ms,
            "rate": float(random.uniform(10.0, 22.0)),
            "created_at": now_dt(),
        })

    if device_type == "phone":
        for current in iter_times(hour_start_dt, hour_end_dt, ACCEL_FREQ_SEC):
            ts_ms = int(current.timestamp() * 1000)
            rows["accelerometer"].append({
                "device_id": device_id,
                "timestamp": ts_ms,
                "utc_time": utc_dt(ts_ms),
                "x": random.gauss(0, acc_variance),
                "y": random.gauss(0, acc_variance),
                "z": random.gauss(0.99, acc_variance),
                "created_at": now_dt(),
            })

        for current in iter_times(hour_start_dt, hour_end_dt, GYRO_FREQ_SEC):
            ts_ms = int(current.timestamp() * 1000)
            rows["gyroscope"].append({
                "device_id": device_id,
                "timestamp": ts_ms,
                "utc_time": utc_dt(ts_ms),
                "x": random.gauss(0, gyro_variance),
                "y": random.gauss(0, gyro_variance),
                "z": random.gauss(0, gyro_variance),
                "created_at": now_dt(),
            })

        for current in iter_times(hour_start_dt, hour_end_dt, LOC_FREQ_SEC):
            ts_ms = int(current.timestamp() * 1000)
            if anomaly_type == "SU2":
                target_bearing = bearing_towards(lat, lon, state["risk_lat"], state["risk_lon"])
                bearing = target_bearing if random.random() < 0.85 else random_bearing()
                distance_to_target = haversine.haversine(
                    (lat, lon),
                    (state["risk_lat"], state["risk_lon"]),
                    unit=haversine.Unit.METERS,
                )
                current_step = 35.0 if distance_to_target <= state["risk_radius"] else step_size
            else:
                if random.random() < 0.1:
                    bearing = random_bearing()
                current_step = step_size
            lat, lon = haversine_step(lat, lon, current_step, bearing)
            rows["location"].append({
                "device_id": device_id,
                "timestamp": ts_ms,
                "utc_time": utc_dt(ts_ms),
                "latitude": lat,
                "longitude": lon,
                "altitude": -16.8,
                "accuracy": float(random.uniform(5, 25)),
                "created_at": now_dt(),
            })

        for ev_type, ev_time in screen_events_this_hour:
            ts_ms = int(ev_time.timestamp() * 1000)
            rows["screen_events"].append({
                "device_id": device_id,
                "timestamp": ts_ms,
                "utc_time": utc_dt(ts_ms),
                "event": f"Screen turned {ev_type}",
                "created_at": now_dt(),
            })

    if device_type == "watch":
        for current in iter_times(hour_start_dt, hour_end_dt, HR_FREQ_SEC):
            rows["wearable_heart_rate"].append({
                "device_id": device_id,
                "timestamp": int(current.timestamp() * 1000),
                "bpm": int(random.randint(hr_low, hr_high)),
                "created_at": now_dt(),
            })

        step_window_start = max(steps_last_time, hour_start_dt)
        while step_window_start < hour_end_dt:
            step_window_end = min(step_window_start + timedelta(seconds=max(1, STEPS_FREQ_SEC)), hour_end_dt)
            steps_accum += steps_for_window()
            rows["wearable_steps"].append({
                "device_id": device_id,
                "start_time": int(step_window_start.timestamp() * 1000),
                "end_time": int(step_window_end.timestamp() * 1000),
                "count": int(steps_accum),
                "created_at": now_dt(),
            })
            steps_accum = 0
            steps_last_time = step_window_end
            step_window_start = step_window_end

    # persist state
    state["lat"], state["lon"], state["bearing"] = lat, lon, bearing
    state["steps_accum"], state["steps_last_time"] = steps_accum, steps_last_time
    state["battery_level"], state["charging"] = battery_level, charging

    return rows


def generate_device_data(device_id, device_type, start_date, end_date, anom_dict, signature_focus="both"):
    bulk = empty_bulk()

    cur = start_date.replace(minute=0, second=0, microsecond=0)
    while cur < end_date:
        anomaly_type = anom_dict.get(cur)
        hour_rows = generate_hour_data(device_id, device_type, cur, anomaly_type=anomaly_type, signature_focus=signature_focus)
        for k, v in hour_rows.items():
            bulk[k].extend(v)

        # daily aggregates at midnight
        if cur.hour == 0:
            append_daily_rows(device_id, device_type, cur, end_date, bulk, signature_focus=signature_focus)

        cur += timedelta(hours=1)

    return bulk


def get_or_create_participant_specs(conn, participant_count: int, base_start_date: datetime):
    existing_rows = []
    if table_exists(conn, "participant_devices"):
        existing_rows = conn.execute(
            text(
                """
                SELECT
                    pd.participant_id,
                    pd.device_id,
                    CASE
                        WHEN LOWER(pd.device_id) LIKE 'phone_%' THEN 'phone'
                        WHEN LOWER(pd.device_id) LIKE 'watch_%' THEN 'watch'
                        ELSE LOWER(COALESCE(pd.device_type, 'unknown'))
                    END AS device_type,
                    p.name
                FROM participant_devices pd
                LEFT JOIN participants p
                  ON p.participant_id = pd.participant_id
                WHERE pd.device_id IS NOT NULL
                ORDER BY pd.participant_id, pd.device_type, pd.device_id
                """
            )
        ).mappings().all()
    if (not existing_rows) and table_exists(conn, "participants"):
        has_device_type = column_exists(conn, "participants", "device_type")
        if has_device_type:
            device_type_expr = """
                CASE
                    WHEN LOWER(device_id) LIKE 'phone_%' THEN 'phone'
                    WHEN LOWER(device_id) LIKE 'watch_%' THEN 'watch'
                    ELSE LOWER(COALESCE(device_type, 'unknown'))
                END AS device_type
            """
        else:
            device_type_expr = """
                CASE
                    WHEN LOWER(device_id) LIKE 'phone_%' THEN 'phone'
                    WHEN LOWER(device_id) LIKE 'watch_%' THEN 'watch'
                    ELSE 'unknown'
                END AS device_type
            """
        existing_rows = conn.execute(
            text(
                f"""
                SELECT participant_id, device_id, {device_type_expr}, name
                FROM participants
                WHERE device_id IS NOT NULL
                ORDER BY participant_id, device_type, device_id
                """
            )
        ).mappings().all()

    specs_by_participant = {}
    for row in existing_rows:
        participant_id = row["participant_id"]
        spec = specs_by_participant.setdefault(
            participant_id,
            {
                "participant_id": participant_id,
                "name": row["name"] or f"Participant {participant_id}",
                "phone_device_id": None,
                "watch_device_id": None,
            },
        )
        device_type = (row["device_type"] or "unknown").lower()
        if device_type == "phone":
            spec["phone_device_id"] = row["device_id"]
        elif device_type == "watch":
            spec["watch_device_id"] = row["device_id"]
        elif spec["phone_device_id"] is None:
            # Legacy schemas may not carry device_type; default unknown devices to phone.
            spec["phone_device_id"] = row["device_id"]

    specs = list(specs_by_participant.values())
    if len(specs) >= participant_count:
        return specs[:participant_count]

    base_ts = int(base_start_date.timestamp() * 1000)
    seen_participants = {spec["participant_id"] for spec in specs}
    dev_num = 0
    while len(specs) < participant_count:
        suffix = str(base_ts + dev_num * 1000000)
        participant_id = f"participant_{suffix}"
        if participant_id not in seen_participants:
            specs.append(
                {
                    "participant_id": participant_id,
                    "name": f"Participant {len(specs) + 1:02d}",
                    "phone_device_id": f"phone_SM-S938B_{suffix}",
                    "watch_device_id": f"watch_GW7_{suffix}",
                }
            )
            seen_participants.add(participant_id)
        dev_num += 1
    return specs


def table_exists(conn, table_name: str) -> bool:
    result = conn.execute(
        text(
            """
            SELECT COUNT(*)
            FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name = :table_name
            """
        ),
        {"table_name": table_name},
    ).scalar()
    return bool(result)


def column_exists(conn, table_name: str, column_name: str) -> bool:
    result = conn.execute(
        text(
            """
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = :table_name
              AND column_name = :column_name
            """
        ),
        {"table_name": table_name, "column_name": column_name},
    ).scalar()
    return bool(result)


def ensure_red_zones_for_participants(conn, participant_specs: list[dict]):
    if not table_exists(conn, "red_zones"):
        return
    for spec in participant_specs:
        device_id = spec["phone_device_id"] or spec["participant_id"]
        geo = device_geo_profile(device_id)
        conn.execute(
            text(
                """
                INSERT INTO red_zones (zone_id, participant_id, name, latitude, longitude, radius, zone_type)
                VALUES (:zone_id, :participant_id, :name, :latitude, :longitude, :radius, :zone_type)
                ON DUPLICATE KEY UPDATE
                    participant_id = VALUES(participant_id),
                    name = VALUES(name),
                    latitude = VALUES(latitude),
                    longitude = VALUES(longitude),
                    radius = VALUES(radius),
                    zone_type = VALUES(zone_type)
                """
            ),
            {
                "zone_id": f"rz_{spec['participant_id']}",
                "participant_id": spec["participant_id"],
                "name": f"Risk Zone {spec['participant_id'][-4:]}",
                "latitude": geo["risk_lat"],
                "longitude": geo["risk_lon"],
                "radius": geo["risk_radius"],
                "zone_type": "relapse",
            },
        )


def prime_device_state_from_db(conn, device_id: str, anchor_dt: datetime):
    if not hasattr(generate_hour_data, "_state"):
        generate_hour_data._state = {}
    if device_id in generate_hour_data._state:
        return

    geo = device_geo_profile(device_id)
    row = conn.execute(
        text(
            """
            SELECT latitude, longitude
            FROM location
            WHERE device_id = :device_id
            ORDER BY timestamp DESC
            LIMIT 1
            """
        ),
        {"device_id": device_id},
    ).mappings().first()

    generate_hour_data._state[device_id] = {
        "lat": float(row["latitude"]) if row else geo["home_lat"],
        "lon": float(row["longitude"]) if row else geo["home_lon"],
        "bearing": random_bearing(),
        "steps_accum": 0,
        "steps_last_time": anchor_dt,
        "battery_level": random.uniform(25, 90),
        "charging": random.choice([True, False]),
        "home_lat": geo["home_lat"],
        "home_lon": geo["home_lon"],
        "risk_lat": geo["risk_lat"],
        "risk_lon": geo["risk_lon"],
        "risk_radius": geo["risk_radius"],
    }


def latest_complete_hour(now_dt: datetime, lag_minutes: int) -> datetime:
    effective_now = now_dt - timedelta(minutes=lag_minutes)
    return effective_now.replace(minute=0, second=0, microsecond=0) - timedelta(hours=1)


def next_hour_for_device(conn, device_id: str, latest_hour: datetime, bootstrap_hours: int, selected_tables: set[str]) -> datetime:
    timestamp_queries = []
    if "accelerometer" in selected_tables:
        timestamp_queries.append("SELECT MAX(timestamp) AS ts FROM accelerometer WHERE device_id = :device_id")
    if "gyroscope" in selected_tables:
        timestamp_queries.append("SELECT MAX(timestamp) AS ts FROM gyroscope WHERE device_id = :device_id")
    if "location" in selected_tables:
        timestamp_queries.append("SELECT MAX(timestamp) AS ts FROM location WHERE device_id = :device_id")
    if "screen_events" in selected_tables:
        timestamp_queries.append("SELECT MAX(timestamp) AS ts FROM screen_events WHERE device_id = :device_id")
    if "notifications" in selected_tables:
        timestamp_queries.append("SELECT MAX(timestamp) AS ts FROM notifications WHERE device_id = :device_id")
    if "battery_readings" in selected_tables:
        timestamp_queries.append("SELECT MAX(timestamp) AS ts FROM battery_readings WHERE device_id = :device_id")
    if "wearable_heart_rate" in selected_tables:
        timestamp_queries.append("SELECT MAX(timestamp) AS ts FROM wearable_heart_rate WHERE device_id = :device_id")
    if "wearable_steps" in selected_tables:
        timestamp_queries.append("SELECT MAX(end_time) AS ts FROM wearable_steps WHERE device_id = :device_id")
    if "wearable_sleep" in selected_tables:
        timestamp_queries.append("SELECT MAX(end_time) AS ts FROM wearable_sleep WHERE device_id = :device_id")
    if "wearable_blood_pressure" in selected_tables:
        timestamp_queries.append("SELECT MAX(timestamp) AS ts FROM wearable_blood_pressure WHERE device_id = :device_id")
    if "wearable_weight" in selected_tables:
        timestamp_queries.append("SELECT MAX(timestamp) AS ts FROM wearable_weight WHERE device_id = :device_id")
    if "wearable_oxygen" in selected_tables:
        timestamp_queries.append("SELECT MAX(timestamp) AS ts FROM wearable_oxygen WHERE device_id = :device_id")
    if "wearable_respiratory" in selected_tables:
        timestamp_queries.append("SELECT MAX(timestamp) AS ts FROM wearable_respiratory WHERE device_id = :device_id")

    max_ts = None
    for query in timestamp_queries:
        value = conn.execute(text(query), {"device_id": device_id}).scalar()
        if value is not None:
            max_ts = value if max_ts is None else max(max_ts, value)

    if max_ts is None:
        return latest_hour - timedelta(hours=max(bootstrap_hours - 1, 0))
    last_hour = datetime.fromtimestamp(int(max_ts) / 1000.0, timezone.utc).replace(tzinfo=None).replace(minute=0, second=0, microsecond=0)
    return last_hour + timedelta(hours=1)


def insert_hour_batch(
    conn,
    tables,
    participant_id: str,
    participant_name: str,
    device_id: str,
    device_type: str,
    hour_start: datetime,
    selected_tables: set[str],
    signature_focus: str,
    db_columns: dict[str, set[str]],
):
    bulk = empty_bulk()
    anomaly_type = choose_anomaly_for_hour(hour_start, signature_focus=signature_focus) if device_type == "phone" else None
    prime_device_state_from_db(conn, device_id, hour_start)
    hour_rows = generate_hour_data(device_id, device_type, hour_start, anomaly_type=anomaly_type, signature_focus=signature_focus)
    for key, rows in hour_rows.items():
        bulk[key].extend(rows)
    if hour_start.hour == 0:
        append_daily_rows(device_id, device_type, hour_start, hour_start + timedelta(days=1), bulk, signature_focus=signature_focus)
    bulk = prune_bulk_for_mode(bulk, selected_tables)

    upsert_participant_and_device(conn, db_columns, participant_id, participant_name, device_id, device_type)

    if device_type == "phone" and anomaly_type is not None:
        ts = int(hour_start.timestamp() * 1000)
        anomaly_doc = {
            "device_id": device_id,
            "hour_start_ts": ts,
            "hour_start_utc": format_utc_time(ts),
            "anomaly_type": anomaly_type,
            "created_at": now_iso(),
        }
        anomaly_doc = {k: v for k, v in anomaly_doc.items() if k in db_columns["anomaly_hours"]}
        conn.execute(
            tables["anomaly_hours"].insert(),
            [anomaly_doc],
        )

    inserted = {}
    for name, rows in bulk.items():
        count = bulk_insert(conn, tables[name], rows, CHUNK_SIZE, db_columns.get(name))
        if count:
            inserted[name] = count
    return inserted, anomaly_type


def run_live_stream(engine, tables, sleep_sec: int, lag_minutes: int, bootstrap_hours: int, selected_tables: set[str], signature_focus: str):
    print(
        f"Starting live mode: sleep={sleep_sec}s lag={lag_minutes}m bootstrap_hours={bootstrap_hours} devices={DEVICES}"
    )
    try:
        while True:
            now_dt = datetime.now(timezone.utc).replace(tzinfo=None)
            latest_hour = latest_complete_hour(now_dt, lag_minutes)
            total_hours = 0
            with engine.begin() as conn:
                db_columns = read_table_columns(conn, set(CORE_TABLE_NAMES | selected_tables))
                participant_specs = get_or_create_participant_specs(conn, DEVICES, latest_hour)
                if selected_tables & PHONE_TABLE_NAMES:
                    ensure_red_zones_for_participants(conn, participant_specs)
                for spec in participant_specs:
                    device_runs = []
                    if selected_tables & PHONE_TABLE_NAMES and spec.get("phone_device_id"):
                        device_runs.append((spec["phone_device_id"], "phone"))
                    if selected_tables & WATCH_TABLE_NAMES and spec.get("watch_device_id"):
                        device_runs.append((spec["watch_device_id"], "watch"))
                    for device_id, device_type in device_runs:
                        hour = next_hour_for_device(conn, device_id, latest_hour, bootstrap_hours, selected_tables)
                        while hour <= latest_hour:
                            inserted, anomaly_type = insert_hour_batch(
                                conn,
                                tables,
                                spec["participant_id"],
                                spec["name"],
                                device_id,
                                device_type,
                                hour,
                                selected_tables,
                                signature_focus,
                                db_columns,
                            )
                            total_hours += 1
                            if inserted:
                                print(f"[live][{spec['participant_id']}][{device_type}] hour={hour} anomaly={anomaly_type or 'normal'} inserts={inserted}")
                            hour += timedelta(hours=1)
            if total_hours == 0:
                print(f"[live] caught up through {latest_hour}; sleeping {sleep_sec}s")
            else:
                print(f"[live] inserted {total_hours} hour(s); sleeping {sleep_sec}s")
            time.sleep(sleep_sec)
    except KeyboardInterrupt:
        print("\nStopped live generation.")


def build_participant_row(participant_id, device_id, device_type, name):
    lowered_device_id = (device_id or "").lower()
    normalized_type = (device_type or "unknown").lower()
    if lowered_device_id.startswith("phone_"):
        normalized_type = "phone"
    elif lowered_device_id.startswith("watch_"):
        normalized_type = "watch"

    return {
        "participant_id": participant_id,
        "name": name,
        "red_zone_radius": 300,
        "status": "active",
        "risk_level": "low",
        "device_id": device_id,
        "device_type": normalized_type,
        "created_at": now_dt(),
        "updated_at": now_dt(),
    }


def build_participant_device_row(participant_id, device_id, device_type):
    lowered_device_id = (device_id or "").lower()
    normalized_type = (device_type or "unknown").lower()
    if lowered_device_id.startswith("phone_"):
        normalized_type = "phone"
    elif lowered_device_id.startswith("watch_"):
        normalized_type = "watch"

    return {
        "participant_id": participant_id,
        "device_id": device_id,
        "device_type": normalized_type,
        "is_primary": 1,
        "created_at": now_dt(),
        "updated_at": now_dt(),
    }


def upsert_participant_and_device(
    conn,
    db_columns: dict[str, set[str]],
    participant_id: str,
    participant_name: str,
    device_id: str | None,
    device_type: str,
):
    participant_doc = build_participant_row(participant_id, device_id, device_type, participant_name)
    participant_allowed = db_columns.get("participants", set())
    participant_doc = {k: v for k, v in participant_doc.items() if k in participant_allowed}

    if participant_doc:
        insert_cols = [c for c in ["participant_id", "name", "red_zone_radius", "status", "risk_level", "device_id", "device_type", "created_at", "updated_at"] if c in participant_doc]
        if insert_cols:
            col_sql = ", ".join(f"`{c}`" for c in insert_cols)
            val_sql = ", ".join(f":{c}" for c in insert_cols)
            update_sql = []
            for col in insert_cols:
                if col in {"participant_id", "created_at"}:
                    continue
                if col == "name":
                    update_sql.append("`name` = IF(VALUES(`name`) = 'Unknown', `name`, VALUES(`name`))")
                elif col == "device_id":
                    update_sql.append("`device_id` = COALESCE(`device_id`, VALUES(`device_id`))")
                elif col == "device_type":
                    update_sql.append("`device_type` = IF(VALUES(`device_type`) = 'unknown', `device_type`, VALUES(`device_type`))")
                else:
                    update_sql.append(f"`{col}` = VALUES(`{col}`)")

            sql = f"INSERT INTO participants ({col_sql}) VALUES ({val_sql})"
            if update_sql:
                sql += " ON DUPLICATE KEY UPDATE " + ", ".join(update_sql)
            conn.execute(text(sql), {c: participant_doc[c] for c in insert_cols})

    if not device_id:
        return
    if "participant_devices" not in db_columns:
        return

    device_doc = build_participant_device_row(participant_id, device_id, device_type)
    device_doc = {k: v for k, v in device_doc.items() if k in db_columns["participant_devices"]}
    insert_cols = [c for c in ["participant_id", "device_id", "device_type", "is_primary", "created_at", "updated_at"] if c in device_doc]
    if not insert_cols:
        return

    col_sql = ", ".join(f"`{c}`" for c in insert_cols)
    val_sql = ", ".join(f":{c}" for c in insert_cols)
    update_sql = []
    for col in insert_cols:
        if col in {"created_at"}:
            continue
        if col == "participant_id":
            update_sql.append("`participant_id` = VALUES(`participant_id`)")
        elif col == "device_type":
            update_sql.append("`device_type` = IF(VALUES(`device_type`) = 'unknown', `device_type`, VALUES(`device_type`))")
        elif col == "updated_at":
            update_sql.append("`updated_at` = VALUES(`updated_at`)")
        elif col == "is_primary":
            update_sql.append("`is_primary` = VALUES(`is_primary`)")
        elif col != "device_id":
            update_sql.append(f"`{col}` = VALUES(`{col}`)")

    sql = f"INSERT INTO participant_devices ({col_sql}) VALUES ({val_sql})"
    if update_sql:
        sql += " ON DUPLICATE KEY UPDATE " + ", ".join(update_sql)
    conn.execute(text(sql), {c: device_doc[c] for c in insert_cols})


def append_daily_rows(device_id, device_type, day, end_date, bulk, signature_focus="both"):
    day_profile = get_day_profile(device_id, day, signature_focus=signature_focus)
    if device_type == "phone":
        n_min, n_max = NOTIFICATIONS_PER_DAY_MIN, NOTIFICATIONS_PER_DAY_MAX
        for _ in range(random.randint(n_min, n_max)):
            notif_time = day + timedelta(seconds=random.randint(0, 86399))
            if notif_time >= end_date:
                continue
            ts = int(notif_time.timestamp() * 1000)
            bulk["notifications"].append({
                "device_id": device_id,
                "app_name": random.choice(["WhatsApp", "Google Play Store", "Instagram", "Gmail"]),
                "title": "",
                "content": "",
                "category": "other",
                "kind": random.choice(["posted", "msg"]),
                "timestamp": ts,
                "dismissed_at": None,
                "created_at": now_dt(),
            })
        return

    bulk["watch_day_profiles"].append({
        "device_id": device_id,
        "day_start": int(day.timestamp() * 1000),
        "profile": day_profile,
        "created_at": now_dt(),
    })

    if SLEEP_PER_DAY:
        sleep_rows = []
        if day_profile == "wa2":
            episode_count = random.randint(2, 4)
            cursor = day + timedelta(hours=22, minutes=random.randint(0, 45))
            for _ in range(episode_count):
                duration_h = random.uniform(1.2, 2.4)
                sleep_start = cursor
                sleep_end = min(sleep_start + timedelta(hours=duration_h), end_date)
                sleep_rows.append((sleep_start, sleep_end))
                cursor = sleep_end + timedelta(minutes=random.randint(20, 80))
        elif day_profile == "wd2":
            sleep_start = day + timedelta(hours=23, minutes=random.randint(30, 110))
            sleep_end = min(sleep_start + timedelta(hours=random.uniform(4.8, 6.2)), end_date)
            sleep_rows.append((sleep_start, sleep_end))
        elif day_profile == "wb1":
            sleep_start = day + timedelta(hours=random.randint(21, 26), minutes=random.randint(0, 59))
            sleep_end = min(sleep_start + timedelta(hours=random.uniform(5.5, 9.0)), end_date)
            sleep_rows.append((sleep_start, sleep_end))
        elif day_profile == "wb2":
            sleep_start = day + timedelta(hours=24, minutes=random.randint(0, 120))
            sleep_end = min(sleep_start + timedelta(hours=random.uniform(4.5, 5.8)), end_date)
            sleep_rows.append((sleep_start, sleep_end))
        else:
            sleep_start = day + timedelta(hours=23, minutes=random.randint(0, 35))
            sleep_end = min(sleep_start + timedelta(hours=random.uniform(7.0, 8.2)), end_date)
            sleep_rows.append((sleep_start, sleep_end))

        for sleep_start, sleep_end in sleep_rows:
            bulk["wearable_sleep"].append({
                "device_id": device_id,
                "start_time": int(sleep_start.timestamp() * 1000),
                "end_time": int(sleep_end.timestamp() * 1000),
                "title": "Sleep",
                "notes": day_profile,
                "created_at": now_dt(),
            })

    for _ in range(random.randint(BP_FREQ_DAY_MIN, BP_FREQ_DAY_MAX)):
        bp_time = day + timedelta(seconds=random.randint(0, 86399))
        if bp_time >= end_date:
            continue
        ts = int(bp_time.timestamp() * 1000)
        bulk["wearable_blood_pressure"].append({
            "device_id": device_id,
            "timestamp": ts,
            "systolic": random.randint(110, 140),
            "diastolic": random.randint(70, 90),
            "created_at": now_dt(),
        })

    for _ in range(random.randint(WEIGHT_FREQ_DAY_MIN, WEIGHT_FREQ_DAY_MAX)):
        w_time = day + timedelta(seconds=random.randint(0, 86399))
        if w_time >= end_date:
            continue
        ts = int(w_time.timestamp() * 1000)
        bulk["wearable_weight"].append({
            "device_id": device_id,
            "timestamp": ts,
            "weight_kg": float(random.uniform(50, 90)),
            "created_at": now_dt(),
        })


def empty_bulk():
    return {
        "accelerometer": [],
        "gyroscope": [],
        "location": [],
        "battery_readings": [],
        "screen_events": [],
        "notifications": [],
        "wearable_heart_rate": [],
        "wearable_steps": [],
        "wearable_sleep": [],
        "wearable_blood_pressure": [],
        "wearable_weight": [],
        "wearable_oxygen": [],
        "wearable_respiratory": [],
        "watch_day_profiles": [],
    }


def prune_bulk_for_mode(bulk: dict, selected_tables: set[str]) -> dict:
    return {name: rows for name, rows in bulk.items() if name in selected_tables}


# ------------------ MAIN ------------------

def run(mode_override=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--live", action="store_true", help="Continuously generate recent-hour data until stopped.")
    parser.add_argument("--mode", choices=["phone", "watch", "both"], help="Generate phone data, watch data, or both.")
    parser.add_argument("--signature-focus", choices=["phone", "watch", "both"], help="Bias generated signatures toward phone, watch, or both.")
    parser.add_argument("--sleep-sec", type=int, default=LIVE_SLEEP_SEC, help="Delay between live generation loops.")
    parser.add_argument(
        "--lag-minutes",
        type=int,
        default=LIVE_LAG_MINUTES,
        help="Generate slightly behind real time so completed hours exist for the engine.",
    )
    parser.add_argument(
        "--bootstrap-hours",
        type=int,
        default=LIVE_BOOTSTRAP_HOURS,
        help="If a device has no data yet, seed this many recent hours first in live mode.",
    )
    parser.add_argument("--truncate", action="store_true", help="Delete generator-managed tables before one-shot historical seeding.")
    args = parser.parse_args()

    engine = make_engine()
    metadata = MetaData()
    tables = define_tables(metadata)

    selected_mode = mode_override or args.mode or prompt_mode()
    selected_tables = resolve_mode_tables(selected_mode)
    required_tables = set(CORE_TABLE_NAMES | selected_tables)
    if selected_tables & PHONE_TABLE_NAMES:
        required_tables.add("red_zones")
    with engine.begin() as conn:
        require_tables_exist(conn, required_tables)

    signature_focus = args.signature_focus or prompt_signature_focus()
    should_truncate = prompt_yes_no("Delete current generated data from the selected tables first?", default=False)

    run_live = args.live or prompt_live_mode()

    if run_live:
        if should_truncate:
            with engine.begin() as conn:
                truncate_all(conn, reset_tables_for_mode(selected_tables))
        run_live_stream(engine, tables, args.sleep_sec, args.lag_minutes, args.bootstrap_hours, selected_tables, signature_focus)
        return

    historical_days = prompt_historical_days(HISTORICAL_DAYS_DEFAULT)
    start_date = datetime.now(timezone.utc).replace(tzinfo=None) - timedelta(days=historical_days)
    end_date = datetime.now(timezone.utc).replace(tzinfo=None)
    try:
        with engine.begin() as conn:
            if args.truncate or should_truncate:
                truncate_all(conn, reset_tables_for_mode(selected_tables))

            db_columns = read_table_columns(conn, set(CORE_TABLE_NAMES | selected_tables))
            participant_specs = get_or_create_participant_specs(conn, DEVICES, start_date)
            if selected_tables & PHONE_TABLE_NAMES:
                ensure_red_zones_for_participants(conn, participant_specs)

            for spec in participant_specs:
                device_runs = []
                if selected_tables & PHONE_TABLE_NAMES and spec.get("phone_device_id"):
                    device_runs.append((spec["phone_device_id"], "phone"))
                if selected_tables & WATCH_TABLE_NAMES and spec.get("watch_device_id"):
                    device_runs.append((spec["watch_device_id"], "watch"))

                for device_id, device_type in device_runs:
                    if device_type == "phone":
                        anom_dict, anom_rows = build_anomaly_dict(start_date, end_date, signature_focus=signature_focus)
                        for row in anom_rows:
                            row["device_id"] = device_id
                    else:
                        anom_dict, anom_rows = {}, []

                    bulk = generate_device_data(device_id, device_type, start_date, end_date, anom_dict, signature_focus=signature_focus)
                    bulk = prune_bulk_for_mode(bulk, selected_tables)

                    upsert_participant_and_device(
                        conn,
                        db_columns,
                        spec["participant_id"],
                        spec["name"],
                        device_id,
                        device_type,
                    )

                    inserted_anom = (
                        bulk_insert(
                            conn,
                            tables["anomaly_hours"],
                            anom_rows,
                            CHUNK_SIZE,
                            db_columns.get("anomaly_hours"),
                        )
                        if device_type == "phone"
                        else 0
                    )
                    if inserted_anom:
                        print(f"[{spec['participant_id']}][{device_type}] anomaly_hours: {inserted_anom}")

                    for name, rows in bulk.items():
                        inserted = bulk_insert(conn, tables[name], rows, CHUNK_SIZE, db_columns.get(name))
                        if inserted:
                            print(f"[{spec['participant_id']}][{device_type}] {name}: {inserted}")

        print("\nMySQL data generation complete.")
        print("Tip for LSTM training: exclude hours in anomaly_hours when building your training set.")
        return

        print("\n✅ MySQL data generation complete!")
        print("Tip for LSTM training: exclude hours in anomaly_hours when building your training set.")

    except SQLAlchemyError as e:
        print("DB error:", str(e))
        return
        print("❌ DB error:", str(e))


def main():
    run()


if __name__ == "__main__":
    main()
