import os
import time
import math
import json
import argparse
import numpy as np
import pandas as pd
import mysql.connector


ENGINE_NAME = "signature_engine_v1"

BASELINE_HOURS = int(os.getenv("BASELINE_HOURS", "24"))
LOOP_INTERVAL_SEC = int(os.getenv("LOOP_INTERVAL_SEC", "300"))

GPS_MAX_ACCURACY_M = float(os.getenv("GPS_MAX_ACCURACY_M", "50"))

DB_CONFIG = {
    "host": os.getenv("DB_HOST", "mysql"),
    "port": int(os.getenv("DB_PORT", "3306")),
    "user": os.getenv("DB_USER", "aware_user"),
    "password": os.getenv("DB_PASSWORD", "password"),
    "database": os.getenv("DB_NAME", "aware_db"),
    "autocommit": False,
}


def haversine_m(lat1, lon1, lat2, lon2):
    R = 6371000.0
    p1 = math.radians(lat1)
    p2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R * math.asin(math.sqrt(a))


def ms_to_dt(ts_ms: pd.Series) -> pd.Series:
    return pd.to_datetime(ts_ms, unit="ms", utc=True).dt.tz_convert(None)


def floor_hour(dt_series: pd.Series) -> pd.Series:
    return dt_series.dt.floor("h")


def connect():
    return mysql.connector.connect(**DB_CONFIG)


def ensure_tables(conn):
    """Create required tables if they don't exist."""
    cur = conn.cursor()
    cur.execute("""
        CREATE TABLE IF NOT EXISTS `engine_state` (
            `participant_id` VARCHAR(128) NOT NULL,
            `engine_name` VARCHAR(64) NOT NULL,
            `last_processed_hour_start` DATETIME NOT NULL,
            PRIMARY KEY (`participant_id`, `engine_name`)
        )
    """)
    cur.execute("""
        CREATE TABLE IF NOT EXISTS `hourly_features` (
            `participant_id` VARCHAR(128) NOT NULL,
            `hour_start` DATETIME NOT NULL,
            `accel_n` INT NULL,
            `acc_mag_mean` DOUBLE NULL,
            `acc_mag_std` DOUBLE NULL,
            `acc_jerk_mean` DOUBLE NULL,
            `acc_mag_p95` DOUBLE NULL,
            `acc_inactive_ratio` DOUBLE NULL,
            `gyro_n` INT NULL,
            `gyro_mag_mean` DOUBLE NULL,
            `gyro_mag_std` DOUBLE NULL,
            `gyro_mag_p95` DOUBLE NULL,
            `gps_n` INT NULL,
            `gps_points` INT NULL,
            `gps_mean_accuracy` DOUBLE NULL,
            `gps_distance_m` DOUBLE NULL,
            `gps_stationary_ratio` DOUBLE NULL,
            `screen_event_n` INT NULL,
            `screen_on_events` INT NULL,
            `screen_off_events` INT NULL,
            `screen_sessions` INT NULL,
            `screen_on_seconds` DOUBLE NULL,
            `screen_avg_session_seconds` DOUBLE NULL,
            PRIMARY KEY (`participant_id`, `hour_start`)
        )
    """)
    cur.execute("""
        CREATE TABLE IF NOT EXISTS `signature_alerts` (
            `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
            `participant_id` VARCHAR(128) NOT NULL,
            `hour_start` DATETIME NOT NULL,
            `alert_code` VARCHAR(32) NOT NULL,
            `alert_name` VARCHAR(255) NULL,
            `severity` VARCHAR(16) NULL,
            `score` DOUBLE NULL,
            `baseline_ref` VARCHAR(64) NULL,
            `top_features_json` TEXT NULL,
            `explanation` TEXT NULL,
            `status` VARCHAR(32) DEFAULT 'new',
            `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            UNIQUE KEY `uq_alert` (`participant_id`, `hour_start`, `alert_code`),
            INDEX `idx_participant` (`participant_id`),
            INDEX `idx_hour` (`hour_start`)
        )
    """)
    conn.commit()
    cur.close()
    print("Ensured engine_state, hourly_features, signature_alerts tables exist.")


def fetch_participants(conn) -> list[str]:
    cur = conn.cursor()
    cur.execute("SELECT DISTINCT device_id FROM accelerometer")
    rows = cur.fetchall()
    cur.close()
    return [r[0] for r in rows]


def get_state(conn, pid: str):
    cur = conn.cursor()
    cur.execute(
        """
        SELECT last_processed_hour_start
        FROM engine_state
        WHERE participant_id=%s AND engine_name=%s
        """,
        (pid, ENGINE_NAME),
    )
    row = cur.fetchone()
    cur.close()
    return row[0] if row else None


def set_state(conn, pid: str, last_hour_start):
    cur = conn.cursor()
    cur.execute(
        """
        INSERT INTO engine_state (participant_id, engine_name, last_processed_hour_start)
        VALUES (%s, %s, %s)
        ON DUPLICATE KEY UPDATE last_processed_hour_start=VALUES(last_processed_hour_start)
        """,
        (pid, ENGINE_NAME, last_hour_start),
    )
    conn.commit()
    cur.close()


def get_time_range_ms(conn, pid: str):
    cur = conn.cursor()
    cur.execute(
        """
        SELECT MIN(timestamp), MAX(timestamp)
        FROM accelerometer
        WHERE device_id=%s
        """,
        (pid,),
    )
    row = cur.fetchone()
    cur.close()
    if not row or row[0] is None or row[1] is None:
        return None, None
    return int(row[0]), int(row[1])


def read_sql_df(conn, sql: str, params: tuple):
    return pd.read_sql(sql, conn, params=params)


def load_accel(conn, pid, start_ms, end_ms):
    return read_sql_df(
        conn,
        """
        SELECT timestamp, x, y, z
        FROM accelerometer
        WHERE device_id=%s AND timestamp BETWEEN %s AND %s
        ORDER BY timestamp
        """,
        (pid, int(start_ms), int(end_ms)),
    )


def load_gyro(conn, pid, start_ms, end_ms):
    return read_sql_df(
        conn,
        """
        SELECT timestamp, x, y, z
        FROM gyroscope
        WHERE device_id=%s AND timestamp BETWEEN %s AND %s
        ORDER BY timestamp
        """,
        (pid, int(start_ms), int(end_ms)),
    )


def load_gps(conn, pid, start_ms, end_ms):
    return read_sql_df(
        conn,
        """
        SELECT timestamp, latitude, longitude, accuracy
        FROM location
        WHERE device_id=%s AND timestamp BETWEEN %s AND %s
        ORDER BY timestamp
        """,
        (pid, int(start_ms), int(end_ms)),
    )


def load_screen(conn, pid, start_ms, end_ms):
    return read_sql_df(
        conn,
        """
        SELECT timestamp, event
        FROM screen_events
        WHERE device_id=%s AND timestamp BETWEEN %s AND %s
        ORDER BY timestamp
        """,
        (pid, int(start_ms), int(end_ms)),
    )


def build_hourly_features(conn, pid: str, hour_start: pd.Timestamp, hour_end: pd.Timestamp) -> pd.DataFrame:
    # Process exactly one hour: [hour_start, hour_end)
    start_ms = int(hour_start.timestamp() * 1000)
    end_ms = int((hour_end - pd.Timedelta(milliseconds=1)).timestamp() * 1000)

    accel = load_accel(conn, pid, start_ms, end_ms)
    gyro = load_gyro(conn, pid, start_ms, end_ms)
    gps = load_gps(conn, pid, start_ms, end_ms)
    screen = load_screen(conn, pid, start_ms, end_ms)

    row = {
        "participant_id": pid,
        "hour_start": hour_start.to_pydatetime(),
        "accel_n": 0,
        "gyro_n": 0,
        "gps_n": 0,
        "screen_event_n": 0,
        "acc_mag_mean": None,
        "acc_mag_std": None,
        "acc_jerk_mean": None,
        "acc_mag_p95": None,
        "acc_inactive_ratio": None,
        "gyro_mag_mean": None,
        "gyro_mag_std": None,
        "gyro_mag_p95": None,
        "gps_points": None,
        "gps_mean_accuracy": None,
        "gps_distance_m": None,
        "gps_stationary_ratio": None,
        "screen_on_events": None,
        "screen_off_events": None,
        "screen_sessions": None,
        "screen_on_seconds": None,
        "screen_avg_session_seconds": None,
    }

    # Accel features
    if not accel.empty:
        accel["mag"] = np.sqrt(accel["x"] ** 2 + accel["y"] ** 2 + accel["z"] ** 2)
        accel["jerk"] = accel["mag"].diff().abs().fillna(0)
        row["accel_n"] = int(len(accel))
        row["acc_mag_mean"] = float(accel["mag"].mean())
        row["acc_mag_std"] = float(accel["mag"].std()) if len(accel) > 1 else 0.0
        row["acc_mag_p95"] = float(np.nanpercentile(accel["mag"].values, 95))
        row["acc_jerk_mean"] = float(accel["jerk"].mean())
        thr_inactive = float(np.nanpercentile(accel["mag"].values, 10))
        row["acc_inactive_ratio"] = float((accel["mag"] < thr_inactive).mean())

    # Gyro features
    if not gyro.empty:
        gyro["mag"] = np.sqrt(gyro["x"] ** 2 + gyro["y"] ** 2 + gyro["z"] ** 2)
        row["gyro_n"] = int(len(gyro))
        row["gyro_mag_mean"] = float(gyro["mag"].mean())
        row["gyro_mag_std"] = float(gyro["mag"].std()) if len(gyro) > 1 else 0.0
        row["gyro_mag_p95"] = float(np.nanpercentile(gyro["mag"].values, 95))

    # GPS features
    if not gps.empty:
        gps = gps.copy()
        gps["accuracy"] = pd.to_numeric(gps["accuracy"], errors="coerce")
        gps = gps[(gps["accuracy"].isna()) | (gps["accuracy"] <= GPS_MAX_ACCURACY_M)]
        if not gps.empty:
            gps = gps.sort_values("timestamp")
            row["gps_n"] = int(len(gps))
            row["gps_points"] = int(len(gps))
            row["gps_mean_accuracy"] = float(gps["accuracy"].mean()) if gps["accuracy"].notna().any() else None

            lat = gps["latitude"].values
            lon = gps["longitude"].values
            dists = [0.0]
            for i in range(1, len(gps)):
                dists.append(haversine_m(lat[i - 1], lon[i - 1], lat[i], lon[i]))
            dists = np.array(dists, dtype=float)

            row["gps_distance_m"] = float(dists.sum())
            row["gps_stationary_ratio"] = float((dists < 5.0).mean())

    # Screen features (events + sessions within the hour)
    if not screen.empty:
        screen = screen.copy()
        screen["event_time"] = ms_to_dt(screen["timestamp"])
        screen = screen.sort_values("event_time")
        row["screen_event_n"] = int(len(screen))
        row["screen_on_events"] = int((screen["event"] == "Screen turned on").sum())
        row["screen_off_events"] = int((screen["event"] == "Screen turned off").sum())

        # Reconstruct sessions; keep only on->off pairs fully inside this hour
        sessions = []
        current_on = None
        for _, r in screen.iterrows():
            ev = str(r["event"])
            t = r["event_time"]
            if ev == "Screen turned on":
                current_on = t
            elif ev == "Screen turned off":
                if current_on is not None and t >= current_on:
                    sessions.append((current_on, t, (t - current_on).total_seconds()))
                    current_on = None

        if sessions:
            s = pd.DataFrame(sessions, columns=["on_time", "off_time", "duration_s"])
            row["screen_sessions"] = int(len(s))
            row["screen_on_seconds"] = float(s["duration_s"].sum())
            row["screen_avg_session_seconds"] = float(s["duration_s"].mean())
        else:
            row["screen_sessions"] = 0
            row["screen_on_seconds"] = 0.0
            row["screen_avg_session_seconds"] = None

    return pd.DataFrame([row])


def upsert_hourly_features(conn, df: pd.DataFrame):
    cols = list(df.columns)
    df2 = df.astype(object).where(pd.notnull(df), None)

    placeholders = ",".join(["%s"] * len(cols))
    col_sql = ",".join([f"`{c}`" for c in cols])
    update_sql = ",".join([f"`{c}`=VALUES(`{c}`)" for c in cols if c not in ("participant_id", "hour_start")])

    sql = f"""
    INSERT INTO hourly_features ({col_sql})
    VALUES ({placeholders})
    ON DUPLICATE KEY UPDATE {update_sql}
    """

    cur = conn.cursor()
    cur.executemany(sql, df2.values.tolist())
    conn.commit()
    cur.close()


def severity_from_value(value, pct_dict):
    if value is None or pct_dict is None:
        return None
    p90 = pct_dict.get("p90"); p95 = pct_dict.get("p95"); p98 = pct_dict.get("p98"); p995 = pct_dict.get("p995")
    if p90 is None:
        return None
    if value >= p995:
        return "CRITICAL"
    if value >= p98:
        return "HIGH"
    if value >= p95:
        return "MEDIUM"
    if value >= p90:
        return "LOW"
    return None


def baseline_thresholds(conn, pid: str, now_hour_start: pd.Timestamp):
    # Baseline features are computed from hourly_features table (not raw) for speed.
    # Use last BASELINE_HOURS that are strictly before now_hour_start.
    q = """
    SELECT hour_start, acc_jerk_mean, gyro_mag_std, screen_on_seconds, screen_sessions, gps_distance_m, acc_mag_std
    FROM hourly_features
    WHERE participant_id=%s AND hour_start < %s
    ORDER BY hour_start ASC
    """
    hf = pd.read_sql(q, conn, params=(pid, now_hour_start.to_pydatetime()))
    if hf.empty:
        return None

    hf["hour_start"] = pd.to_datetime(hf["hour_start"])
    hf = hf.sort_values("hour_start")
    base_all = hf.tail(BASELINE_HOURS).copy()

    night_hours = set([23, 0, 1, 2, 3, 4, 5])
    base_night = base_all[base_all["hour_start"].dt.hour.isin(night_hours)].copy()

    def pct(df, col):
        s = pd.to_numeric(df[col], errors="coerce").dropna()
        if len(s) < 10:
            return {"p90": None, "p95": None, "p98": None, "p995": None}
        return {
            "p90": float(np.nanpercentile(s, 90)),
            "p95": float(np.nanpercentile(s, 95)),
            "p98": float(np.nanpercentile(s, 98)),
            "p995": float(np.nanpercentile(s, 99.5)),
        }

    thr_all = {
        "gps_distance_m": pct(base_all, "gps_distance_m"),
        "screen_sessions": pct(base_all, "screen_sessions"),
        "acc_mag_std": pct(base_all, "acc_mag_std"),
    }
    thr_night = {
        "screen_on_seconds": pct(base_night, "screen_on_seconds") if not base_night.empty else {"p90": None, "p95": None, "p98": None, "p995": None},
        "gyro_mag_std": pct(base_night, "gyro_mag_std") if not base_night.empty else {"p90": None, "p95": None, "p98": None, "p995": None},
        "acc_jerk_mean": pct(base_night, "acc_jerk_mean") if not base_night.empty else {"p90": None, "p95": None, "p98": None, "p995": None},
    }
    return {"all": thr_all, "night": thr_night}


def insert_alert(conn, alert_row: dict):
    """
    Insert an alert, but if it already exists (same participant_id, hour_start, alert_code),
    update only the computed fields.

    We intentionally do NOT overwrite workflow fields like status/ack fields, and we do NOT
    touch created_at (so "first seen" stays accurate). [web:268]
    """
    insert_cols = [
        "participant_id", "hour_start",
        "alert_code",
        "alert_name", "severity", "score",
        "baseline_ref", "top_features_json", "explanation",
    ]
    values = [alert_row.get(c) for c in insert_cols]

    insert_cols_sql = ",".join([f"`{c}`" for c in insert_cols])
    placeholders = ",".join(["%s"] * len(insert_cols))

    update_cols = [
        "alert_name", "severity", "score",
        "baseline_ref", "top_features_json", "explanation",
    ]
    update_sql = ",".join([f"`{c}`=VALUES(`{c}`)" for c in update_cols])

    sql = f"""
    INSERT INTO signature_alerts ({insert_cols_sql})
    VALUES ({placeholders})
    ON DUPLICATE KEY UPDATE {update_sql}
    """

    cur = conn.cursor()
    cur.execute(sql, values)
    conn.commit()
    cur.close()


def generate_alerts_for_hour(conn, pid: str, hour_row: pd.Series):
    hour_start = pd.to_datetime(hour_row["hour_start"])
    hr = hour_start.hour
    is_night = hr in [23, 0, 1, 2, 3, 4, 5]

    # need baseline
    thr = baseline_thresholds(conn, pid, hour_start)
    if thr is None:
        return

    acc_jerk = hour_row.get("acc_jerk_mean")
    gyro_std = hour_row.get("gyro_mag_std")
    scr_secs = hour_row.get("screen_on_seconds")
    scr_sess = hour_row.get("screen_sessions")
    gps_dist = hour_row.get("gps_distance_m")
    acc_std = hour_row.get("acc_mag_std")

    # AS1 (night baseline)
    if is_night:
        sev_screen = severity_from_value(float(scr_secs) if scr_secs is not None else None, thr["night"]["screen_on_seconds"])
        sev_gyro = severity_from_value(float(gyro_std) if gyro_std is not None else None, thr["night"]["gyro_mag_std"])
        sev_acc = severity_from_value(float(acc_jerk) if acc_jerk is not None else None, thr["night"]["acc_jerk_mean"])

        if sev_screen is not None and (sev_gyro is not None or sev_acc is not None):
            elevated = [x for x in [sev_screen, sev_gyro, sev_acc] if x is not None]
            severity = "LOW"
            if "CRITICAL" in elevated:
                severity = "CRITICAL"
            elif "HIGH" in elevated:
                severity = "HIGH"
            elif "MEDIUM" in elevated:
                severity = "MEDIUM"

            top = {"screen_on_seconds": float(scr_secs) if scr_secs is not None else None}
            if sev_gyro:
                top["gyro_mag_std"] = float(gyro_std) if gyro_std is not None else None
            if sev_acc:
                top["acc_jerk_mean"] = float(acc_jerk) if acc_jerk is not None else None

            insert_alert(conn, {
                "participant_id": pid,
                "hour_start": hour_start.to_pydatetime(),
                "alert_code": "AS1",
                "alert_name": "Night restlessness / sleep disruption indicator",
                "severity": severity,
                "score": float(len(elevated)),
                "baseline_ref": f"last_{BASELINE_HOURS}h_night_only",
                "top_features_json": json.dumps(top),
                "explanation": "Night hour shows elevated screen-on time plus elevated movement/restlessness versus baseline; flagged as a sleep-disruption/restlessness indicator.",
            })

    # DS1 (all baseline) - conservative
    if thr["all"]["gps_distance_m"]["p90"] is not None and thr["all"]["acc_mag_std"]["p90"] is not None:
        if gps_dist is not None and acc_std is not None:
            if float(gps_dist) <= thr["all"]["gps_distance_m"]["p90"] * 0.10 and float(acc_std) <= thr["all"]["acc_mag_std"]["p90"] * 0.25:
                insert_alert(conn, {
                    "participant_id": pid,
                    "hour_start": hour_start.to_pydatetime(),
                    "alert_code": "DS1",
                    "alert_name": "Low mobility + low activity indicator",
                    "severity": "LOW",
                    "score": 1.0,
                    "baseline_ref": f"last_{BASELINE_HOURS}h",
                    "top_features_json": json.dumps({"gps_distance_m": float(gps_dist), "acc_mag_std": float(acc_std)}),
                    "explanation": "Hour shows very low mobility and low activity compared with baseline; flagged as an inactivity/withdrawal-style indicator.",
                })

    # SU_CTX1 (all baseline)
    sev_g = severity_from_value(float(gps_dist) if gps_dist is not None else None, thr["all"]["gps_distance_m"])
    sev_s = severity_from_value(float(scr_sess) if scr_sess is not None else None, thr["all"]["screen_sessions"])
    if sev_g is not None and sev_s is not None:
        severity = "LOW"
        if "CRITICAL" in [sev_g, sev_s]:
            severity = "CRITICAL"
        elif "HIGH" in [sev_g, sev_s]:
            severity = "HIGH"
        elif "MEDIUM" in [sev_g, sev_s]:
            severity = "MEDIUM"

        insert_alert(conn, {
            "participant_id": pid,
            "hour_start": hour_start.to_pydatetime(),
            "alert_code": "SU_CTX1",
            "alert_name": "High context-change + high phone engagement indicator",
            "severity": severity,
            "score": 2.0,
            "baseline_ref": f"last_{BASELINE_HOURS}h",
            "top_features_json": json.dumps({
                "gps_distance_m": float(gps_dist) if gps_dist is not None else None,
                "screen_sessions": int(scr_sess) if scr_sess is not None else None,
            }),
            "explanation": "Hour shows high mobility/context change and frequent screen sessions versus baseline; flagged as a context-change/engagement indicator (not a confirmed craving event).",
        })


def process_new_hours(conn, pid: str):
    min_ms, max_ms = get_time_range_ms(conn, pid)
    if min_ms is None:
        print(f"  [{pid}] No data in accelerometer table")
        return 0

    # Only process completed hours: stop at current max hour_start (floor) but exclude that hour (it may still be receiving data)
    max_dt = pd.to_datetime(max_ms, unit="ms", utc=True).tz_convert(None)
    min_dt = pd.to_datetime(min_ms, unit="ms", utc=True).tz_convert(None)
    latest_complete_hour = (max_dt.floor("h") - pd.Timedelta(hours=1))

    last_done = get_state(conn, pid)
    if last_done is None:
        # Start from the first hour we can compute
        first_hour = pd.to_datetime(min_ms, unit="ms", utc=True).tz_convert(None).floor("h")
        next_hour = first_hour
    else:
        next_hour = pd.to_datetime(last_done).floor("h") + pd.Timedelta(hours=1)

    print(f"  [{pid}] min_ts={min_ms} ({min_dt}) | max_ts={max_ms} ({max_dt})")
    print(f"  [{pid}] next_hour={next_hour} | latest_complete={latest_complete_hour} | last_done={last_done}")

    if next_hour > latest_complete_hour:
        print(f"  [{pid}] Skipping: next_hour > latest_complete_hour (need more data)")
        return 0

    processed = 0
    hour = next_hour
    while hour <= latest_complete_hour:
        hour_end = hour + pd.Timedelta(hours=1)

        hf_row = build_hourly_features(conn, pid, hour, hour_end)
        upsert_hourly_features(conn, hf_row)

        # Generate alerts for this hour
        generate_alerts_for_hour(conn, pid, hf_row.iloc[0])

        set_state(conn, pid, hour.to_pydatetime())
        processed += 1
        hour = hour + pd.Timedelta(hours=1)

    return processed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--loop", action="store_true", help="Run forever; process new completed hours periodically.")
    ap.add_argument("--once", action="store_true", help="Run once and exit (default).")
    args = ap.parse_args()

    if args.loop and args.once:
        raise SystemExit("Choose only one: --loop or --once")

    run_forever = args.loop or (not args.once)

    # Create required tables on startup
    init_conn = connect()
    ensure_tables(init_conn)
    init_conn.close()

    while True:
        conn = connect()
        try:
            pids = fetch_participants(conn)
            print(f"  Found {len(pids)} participants: {pids}")
            total = 0
            for pid in pids:
                total += process_new_hours(conn, pid)
            print(f"[{datetime.utcnow().isoformat()}] processed_hours={total}")
        except mysql.connector.errors.ProgrammingError as e:
            if "doesn't exist" in str(e):
                print(f"[{datetime.utcnow().isoformat()}] Waiting for tables to be created by backend... ({e})")
            else:
                raise
        finally:
            conn.close()

        if not run_forever:
            break

        time.sleep(LOOP_INTERVAL_SEC)


if __name__ == "__main__":
    from datetime import datetime
    main()
