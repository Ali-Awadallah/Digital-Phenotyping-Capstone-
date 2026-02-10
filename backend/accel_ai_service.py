import os, time, json
from datetime import datetime, timedelta, timezone
import mysql.connector
from mysql.connector import Error

from ai_model_super_advanced import process_features


def get_db_config():
    return {
        "host": os.getenv("DB_HOST", "mysql"),
        "port": int(os.getenv("DB_PORT", "3306")),
        "database": os.getenv("DB_NAME", "aware_db"),
        "user": os.getenv("DB_USER", "aware_user"),
        "password": os.getenv("DB_PASSWORD", "password"),
    }


def utc_ms_to_dt(ms: int) -> datetime:
    return datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc).replace(tzinfo=None)


def dt_to_utc_ms(dt: datetime) -> int:
    # treat naive dt as UTC
    return int(dt.replace(tzinfo=timezone.utc).timestamp() * 1000)


def read_state_ms(cur):
    cur.execute("SELECT last_end_ms FROM accel_ai_state WHERE state_id=1")
    r = cur.fetchone()
    return int(r[0]) if r and r[0] is not None else None


def write_state_ms(cur, end_ms: int):
    cur.execute(
        """
        INSERT INTO accel_ai_state (state_id, last_end_ms)
        VALUES (1, %s)
        ON DUPLICATE KEY UPDATE last_end_ms = VALUES(last_end_ms)
        """,
        (int(end_ms),),
    )


def get_db_min_max_ms(cur):
    # Prefer epoch-ms from accelerometer.timestamp (best for your merged CSV situation)
    cur.execute("SELECT MIN(`timestamp`), MAX(`timestamp`) FROM accelerometer")
    mn, mx = cur.fetchone()
    return (int(mn) if mn is not None else None, int(mx) if mx is not None else None)


def alert_exists(cur, participant_id: str, window_key: str) -> bool:
    cur.execute(
        """
        SELECT 1 FROM accel_alerts
        WHERE participant_id=%s AND anomaly_type='ACCEL_ANOMALY'
          AND metadata LIKE %s
        LIMIT 1
        """,
        (participant_id, f'%\"window_key\":\"{window_key}\"%'),
    )
    return cur.fetchone() is not None


def insert_alert(cur, participant_id: str, triggered_at: datetime, score: float, window_key: str, count: int):
    meta = {
        "source": "accel_ai",
        "window_key": window_key,
        "anomaly_count": int(count),
    }
    cur.execute(
        """
        INSERT INTO accel_alerts
          (participant_id, triggered_at, anomaly_type, anomaly_score, acknowledged, acknowledged_by, metadata)
        VALUES
          (%s, %s, %s, %s, 0, NULL, %s)
        """,
        (participant_id, triggered_at, "ACCEL_ANOMALY", float(score), json.dumps(meta)),
    )


def run_service(poll_seconds=5, window_minutes=30, baseline_hours=24):
    print("Starting accel_ai (DB-time windows)...", flush=True)
    db = get_db_config()
    participant_id = "unknown"
    window_ms = int(window_minutes * 60 * 1000)

    while True:
        try:
            conn = mysql.connector.connect(**db)
            cur = conn.cursor()

            db_min_ms, db_max_ms = get_db_min_max_ms(cur)
            if db_min_ms is None or db_max_ms is None:
                cur.close(); conn.close()
                print("No accelerometer data yet.", flush=True)
                time.sleep(poll_seconds)
                continue

            last_end_ms = read_state_ms(cur)

            # choose next start = last_end if exists else start at db_min
            start_ms = last_end_ms if last_end_ms is not None else db_min_ms
            end_ms = start_ms + window_ms

            # only process windows fully available in DB
            if end_ms > db_max_ms:
                cur.close(); conn.close()
                print(f"Waiting for more DB data (next_end_ms={end_ms}, db_max_ms={db_max_ms}).", flush=True)
                time.sleep(poll_seconds)
                continue

            start_dt = utc_ms_to_dt(start_ms)
            end_dt = utc_ms_to_dt(end_ms)
            window_key = f"{start_dt.isoformat()}__{end_dt.isoformat()}"

            cur.close(); conn.close()

            anomalies = process_features(
                db_config=db,
                start_dt=start_dt,
                end_dt=end_dt,
                baseline_hours=baseline_hours,
            )

            conn = mysql.connector.connect(**db)
            cur = conn.cursor()

            if not anomalies.empty:
                if not alert_exists(cur, participant_id, window_key):
                    insert_alert(
                        cur,
                        participant_id,
                        end_dt,
                        anomalies["anomaly_score"].max(),
                        window_key,
                        len(anomalies),
                    )
                    print(f"Inserted alert for window {window_key} (n={len(anomalies)}).", flush=True)
                else:
                    print(f"Alert already exists for window {window_key}, skipping.", flush=True)
            else:
                print(f"No anomalies in window {window_key}.", flush=True)

            # advance watermark to end_ms so each window runs once
            write_state_ms(cur, end_ms)
            conn.commit()

            cur.close(); conn.close()

        except Error as e:
            print("Database error:", e, flush=True)
        except Exception as ex:
            print("Error:", ex, flush=True)

        time.sleep(poll_seconds)


if __name__ == "__main__":
    run_service(poll_seconds=5, window_minutes=30, baseline_hours=24)
