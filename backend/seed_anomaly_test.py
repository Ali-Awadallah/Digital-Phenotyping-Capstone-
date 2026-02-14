"""
Seed anomaly test data for the signature engine.

Inserts ~30 hours of synthetic sensor data into the LOCAL MySQL database:
  * 26 hours of NORMAL baseline data
  * Then 4 anomalous hours designed to trigger each alert signature:
      - AS1    Night restlessness  (02:00)
      - DS1    Low mobility        (10:00)
      - SU_CTX1 High context-change (14:00)
      - AS1    Critical restlessness (03:00)

Usage:
    python seed_anomaly_test.py            # seed data
    python seed_anomaly_test.py --clean    # delete test data first, then seed
"""

print("[seed_anomaly_test] Script starting...")

import os
import sys
import random
import math
import argparse
from datetime import datetime, timedelta

try:
    import mysql.connector
except ImportError:
    print("ERROR: mysql-connector-python not installed.")
    print("Run: pip install mysql-connector-python")
    sys.exit(1)

# ---------------------------------------------------------------------------
# CONFIG
# ---------------------------------------------------------------------------
DEVICE_ID = "test_anomaly_device_001"

DB_CONFIG = {
    "host": os.getenv("DB_HOST", "localhost"),
    "port": int(os.getenv("DB_PORT", "3307")),  # Docker MySQL is mapped to 3307
    "user": os.getenv("DB_USER", "aware_user"),
    "password": os.getenv("DB_PASSWORD", "password"),
    "database": os.getenv("DB_NAME", "aware_db"),
    "autocommit": False,
    "connection_timeout": 10,
    "use_pure": True,
}


def ts_ms(dt: datetime) -> int:
    return int(dt.timestamp() * 1000)


# ---------------------------------------------------------------------------
# DATA GENERATORS
# ---------------------------------------------------------------------------

def gen_accel_normal(device_id, hour_start, n=120):
    rows = []
    for i in range(n):
        t = hour_start + timedelta(seconds=i * 30 + random.uniform(0, 5))
        x = random.gauss(0.1, 0.3)
        y = random.gauss(0.2, 0.3)
        z = random.gauss(9.7, 0.3)
        rows.append((device_id, ts_ms(t), x, y, z))
    return rows


def gen_accel_very_still(device_id, hour_start, n=120):
    """DS1: extremely low variation."""
    rows = []
    for i in range(n):
        t = hour_start + timedelta(seconds=i * 30 + random.uniform(0, 2))
        x = 0.0 + random.gauss(0, 0.001)
        y = 0.0 + random.gauss(0, 0.001)
        z = 9.81 + random.gauss(0, 0.001)
        rows.append((device_id, ts_ms(t), x, y, z))
    return rows


def gen_accel_restless(device_id, hour_start, n=200):
    """AS1: high jerk / erratic movement."""
    rows = []
    for i in range(n):
        t = hour_start + timedelta(seconds=i * 18 + random.uniform(0, 5))
        x = random.gauss(0, 5.0) + random.choice([-8, 0, 8])
        y = random.gauss(0, 5.0) + random.choice([-8, 0, 8])
        z = random.gauss(9.8, 5.0) + random.choice([-5, 0, 5])
        rows.append((device_id, ts_ms(t), x, y, z))
    return rows


def gen_gyro_normal(device_id, hour_start, n=120):
    rows = []
    for i in range(n):
        t = hour_start + timedelta(seconds=i * 30 + random.uniform(0, 5))
        x = random.gauss(0, 0.05)
        y = random.gauss(0, 0.05)
        z = random.gauss(0, 0.05)
        rows.append((device_id, ts_ms(t), x, y, z))
    return rows


def gen_gyro_restless(device_id, hour_start, n=200):
    """AS1: very high rotation."""
    rows = []
    for i in range(n):
        t = hour_start + timedelta(seconds=i * 18 + random.uniform(0, 5))
        x = random.gauss(0, 3.0)
        y = random.gauss(0, 3.0)
        z = random.gauss(0, 3.0)
        rows.append((device_id, ts_ms(t), x, y, z))
    return rows


def gen_gps_normal(device_id, hour_start, n=12, base_lat=25.286, base_lon=51.534):
    rows = []
    lat, lon = base_lat, base_lon
    for i in range(n):
        t = hour_start + timedelta(seconds=i * 300 + random.uniform(0, 30))
        lat += random.gauss(0, 0.0003)
        lon += random.gauss(0, 0.0003)
        acc = random.uniform(3, 25)
        rows.append((device_id, ts_ms(t), lat, lon, acc))
    return rows


def gen_gps_stationary(device_id, hour_start, n=12, base_lat=25.286, base_lon=51.534):
    """DS1: basically no movement."""
    rows = []
    for i in range(n):
        t = hour_start + timedelta(seconds=i * 300 + random.uniform(0, 10))
        lat = base_lat + random.gauss(0, 0.000001)
        lon = base_lon + random.gauss(0, 0.000001)
        acc = random.uniform(3, 10)
        rows.append((device_id, ts_ms(t), lat, lon, acc))
    return rows


def gen_gps_wide_roaming(device_id, hour_start, n=30, base_lat=25.286, base_lon=51.534):
    """SU_CTX1: very high distance traveled."""
    rows = []
    lat, lon = base_lat, base_lon
    for i in range(n):
        t = hour_start + timedelta(seconds=i * 120 + random.uniform(0, 10))
        lat += random.gauss(0, 0.005)
        lon += random.gauss(0, 0.005)
        acc = random.uniform(5, 20)
        rows.append((device_id, ts_ms(t), lat, lon, acc))
    return rows


def gen_screen_normal(device_id, hour_start, sessions=3):
    rows = []
    for s in range(sessions):
        on_time = hour_start + timedelta(minutes=random.randint(5, 55), seconds=random.randint(0, 59))
        off_time = on_time + timedelta(seconds=random.randint(30, 180))
        rows.append((device_id, ts_ms(on_time), "Screen turned on"))
        rows.append((device_id, ts_ms(off_time), "Screen turned off"))
    return rows


def gen_screen_heavy_night(device_id, hour_start, sessions=15):
    """AS1: lots of screen time during night."""
    rows = []
    for s in range(sessions):
        on_time = hour_start + timedelta(minutes=s * 4, seconds=random.randint(0, 30))
        off_time = on_time + timedelta(seconds=random.randint(120, 200))
        rows.append((device_id, ts_ms(on_time), "Screen turned on"))
        rows.append((device_id, ts_ms(off_time), "Screen turned off"))
    return rows


def gen_screen_many_sessions(device_id, hour_start, sessions=25):
    """SU_CTX1: very many screen sessions."""
    rows = []
    for s in range(sessions):
        on_time = hour_start + timedelta(minutes=s * 2, seconds=random.randint(0, 30))
        off_time = on_time + timedelta(seconds=random.randint(15, 60))
        rows.append((device_id, ts_ms(on_time), "Screen turned on"))
        rows.append((device_id, ts_ms(off_time), "Screen turned off"))
    return rows


# ---------------------------------------------------------------------------
# BULK INSERT
# ---------------------------------------------------------------------------

def bulk_insert(conn, table, columns, rows):
    if not rows:
        return
    cur = conn.cursor()
    placeholders = ",".join(["%s"] * len(columns))
    col_sql = ",".join([f"`{c}`" for c in columns])
    sql = f"INSERT INTO `{table}` ({col_sql}) VALUES ({placeholders})"
    cur.executemany(sql, rows)
    conn.commit()
    cur.close()
    print(f"  Inserted {len(rows)} rows into {table}")


# ---------------------------------------------------------------------------
# CLEAN
# ---------------------------------------------------------------------------

def clean_test_data(conn):
    cur = conn.cursor()
    for table in ["accelerometer", "gyroscope", "location", "screen_events",
                   "hourly_features", "engine_state", "signature_alerts"]:
        try:
            cur.execute(f"DELETE FROM `{table}` WHERE device_id=%s", (DEVICE_ID,))
            deleted = cur.rowcount
            if deleted:
                print(f"  Cleaned {deleted} rows from {table}")
        except Exception:
            pass
        try:
            cur.execute(f"DELETE FROM `{table}` WHERE participant_id=%s", (DEVICE_ID,))
            deleted = cur.rowcount
            if deleted:
                print(f"  Cleaned {deleted} rows from {table} (participant_id)")
        except Exception:
            pass
    conn.commit()
    cur.close()
    print(f"Cleaned all test data for device: {DEVICE_ID}")


# ---------------------------------------------------------------------------
# SEED
# ---------------------------------------------------------------------------

def seed(conn):
    """
    Timeline (all sequential, no gaps or overlaps):
      Hours 0..25  : normal baseline (starting 32h ago)
      Hour  26     : AS1 anomaly — forced to 02:00 night
      Hour  27     : DS1 anomaly — forced to 10:00 day
      Hour  28     : SU_CTX1 anomaly — forced to 14:00 day
      Hour  29     : AS1 critical — forced to 03:00 night
      Hour  30     : trailing data so engine sees hour 29 as complete
    """
    now = datetime.utcnow()

    # Build a timeline that places anomaly hours at the right clock times
    # Start the baseline 3 days ago at 00:00 UTC so we have room
    day_base = (now - timedelta(days=2)).replace(hour=0, minute=0, second=0, microsecond=0)

    print(f"Timeline base: {day_base} UTC")
    print(f"Now:           {now} UTC")
    print()

    accel_rows = []
    gyro_rows = []
    gps_rows = []
    screen_rows = []

    # --- 26 hours of NORMAL baseline: day_base+0h through day_base+25h ---
    print("Generating 26 hours of normal baseline data...")
    for i in range(26):
        h = day_base + timedelta(hours=i)
        accel_rows.extend(gen_accel_normal(DEVICE_ID, h))
        gyro_rows.extend(gen_gyro_normal(DEVICE_ID, h))
        gps_rows.extend(gen_gps_normal(DEVICE_ID, h))
        screen_rows.extend(gen_screen_normal(DEVICE_ID, h))

    # After baseline: day_base + 26h = day_base's date + 02:00 next day
    # We need night hours (23,0,1,2,3,4,5) for AS1
    # Let's place anomaly hours explicitly on the NEXT day

    next_day = day_base + timedelta(days=1)

    # --- AS1: night restlessness at 02:00 ---
    h_as1 = next_day.replace(hour=2)
    print(f"AS1 anomaly hour (night):   {h_as1}")
    accel_rows.extend(gen_accel_restless(DEVICE_ID, h_as1))
    gyro_rows.extend(gen_gyro_restless(DEVICE_ID, h_as1))
    gps_rows.extend(gen_gps_normal(DEVICE_ID, h_as1))
    screen_rows.extend(gen_screen_heavy_night(DEVICE_ID, h_as1))

    # --- DS1: low mobility at 10:00 ---
    h_ds1 = next_day.replace(hour=10)
    print(f"DS1 anomaly hour (day):     {h_ds1}")
    accel_rows.extend(gen_accel_very_still(DEVICE_ID, h_ds1))
    gyro_rows.extend(gen_gyro_normal(DEVICE_ID, h_ds1))
    gps_rows.extend(gen_gps_stationary(DEVICE_ID, h_ds1))
    screen_rows.extend(gen_screen_normal(DEVICE_ID, h_ds1, sessions=1))

    # --- SU_CTX1: high context-change at 14:00 ---
    h_su = next_day.replace(hour=14)
    print(f"SU_CTX1 anomaly hour (day): {h_su}")
    accel_rows.extend(gen_accel_normal(DEVICE_ID, h_su))
    gyro_rows.extend(gen_gyro_normal(DEVICE_ID, h_su))
    gps_rows.extend(gen_gps_wide_roaming(DEVICE_ID, h_su))
    screen_rows.extend(gen_screen_many_sessions(DEVICE_ID, h_su))

    # --- AS1 critical: extreme restlessness at 03:00 next next day ---
    day_after = next_day + timedelta(days=1)
    h_as1c = day_after.replace(hour=3)
    print(f"AS1 critical hour (night):  {h_as1c}")
    accel_rows.extend(gen_accel_restless(DEVICE_ID, h_as1c, n=300))
    gyro_rows.extend(gen_gyro_restless(DEVICE_ID, h_as1c, n=300))
    gps_rows.extend(gen_gps_normal(DEVICE_ID, h_as1c))
    screen_rows.extend(gen_screen_heavy_night(DEVICE_ID, h_as1c, sessions=20))

    # --- Trailing hour so engine sees previous hour as complete ---
    h_tail = h_as1c + timedelta(hours=1)
    accel_rows.extend(gen_accel_normal(DEVICE_ID, h_tail, n=10))
    gyro_rows.extend(gen_gyro_normal(DEVICE_ID, h_tail, n=5))

    # --- Insert everything ---
    print()
    print("Connecting to database...")
    print(f"  Host: {DB_CONFIG['host']}:{DB_CONFIG['port']}")
    print(f"  Database: {DB_CONFIG['database']}")
    print()

    print("Inserting sensor data...")
    bulk_insert(conn, "accelerometer", ["device_id", "timestamp", "x", "y", "z"], accel_rows)
    bulk_insert(conn, "gyroscope", ["device_id", "timestamp", "x", "y", "z"], gyro_rows)
    bulk_insert(conn, "location", ["device_id", "timestamp", "latitude", "longitude", "accuracy"], gps_rows)
    bulk_insert(conn, "screen_events", ["device_id", "timestamp", "event"], screen_rows)

    # Verify insertion
    cur = conn.cursor()
    cur.execute("SELECT COUNT(*) FROM accelerometer WHERE device_id=%s", (DEVICE_ID,))
    count = cur.fetchone()[0]
    cur.close()

    print()
    print("=" * 60)
    print("SEED COMPLETE")
    print("=" * 60)
    print(f"Device ID:           {DEVICE_ID}")
    print(f"Accel rows inserted: {count}")
    print(f"Baseline hours:      26 (normal)")
    print(f"Anomaly hours:       4 (AS1 x2, DS1, SU_CTX1)")
    print()
    print("Expected alerts after signature engine processes:")
    print(f"  AS1     at {h_as1}  — Night restlessness (HIGH)")
    print(f"  DS1     at {h_ds1}  — Low mobility (LOW)")
    print(f"  SU_CTX1 at {h_su}  — High context-change (MEDIUM+)")
    print(f"  AS1     at {h_as1c} — Night restlessness (CRITICAL)")
    print()
    print("Now restart the signature engine:")
    print("  docker compose restart signature_engine")
    print("  docker logs -f signature-engine-service")


def main():
    parser = argparse.ArgumentParser(description="Seed anomaly test data")
    parser.add_argument("--clean", action="store_true", help="Delete test data before seeding")
    args = parser.parse_args()

    print(f"Connecting to MySQL at {DB_CONFIG['host']}:{DB_CONFIG['port']}...")
    try:
        conn = mysql.connector.connect(**DB_CONFIG)
        print("Connected successfully!")
    except Exception as e:
        print(f"ERROR connecting to MySQL: {e}")
        sys.exit(1)

    try:
        if args.clean:
            print("Cleaning old test data...")
            clean_test_data(conn)
            print()
        seed(conn)
    except Exception as e:
        print(f"ERROR during seeding: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
