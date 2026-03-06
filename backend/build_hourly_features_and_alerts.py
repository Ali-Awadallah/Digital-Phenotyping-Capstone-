import argparse
import math
import time
from datetime import datetime

import numpy as np
import pandas as pd

from signature_engine.alerts import generate_rule_alerts_for_hour, insert_alert, process_new_days
from signature_engine.config import ENGINE_NAME, GPS_MAX_ACCURACY_M, LOOP_INTERVAL_SEC, TORCH_AVAILABLE
from signature_engine.db import (
    _execute,
    _normalize_doc_value,
    connect,
    ensure_tables,
    fetch_participant_devices,
    get_state,
    get_time_range_ms,
    load_accel,
    load_gps,
    load_gyro,
    load_screen,
    set_state,
)
from signature_engine.lstm import score_hour_with_lstm, summarize_lstm_window, train_or_update_lstm


def haversine_m(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1 = math.radians(lat1)
    p2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def ms_to_dt(ts_ms):
    return pd.to_datetime(ts_ms, unit="ms", utc=True).dt.tz_convert(None)


def build_hourly_features(db, participant_id: str, phone_device_id: str, hour_start: pd.Timestamp, hour_end: pd.Timestamp) -> pd.DataFrame:
    start_ms = int(hour_start.timestamp() * 1000)
    end_ms = int((hour_end - pd.Timedelta(milliseconds=1)).timestamp() * 1000)

    accel = load_accel(db, phone_device_id, start_ms, end_ms)
    gyro = load_gyro(db, phone_device_id, start_ms, end_ms)
    gps = load_gps(db, phone_device_id, start_ms, end_ms)
    screen = load_screen(db, phone_device_id, start_ms, end_ms)

    row = {
        "participant_id": participant_id,
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

    if not accel.empty and "x" in accel.columns:
        accel["mag"] = np.sqrt(accel["x"] ** 2 + accel["y"] ** 2 + accel["z"] ** 2)
        accel["jerk"] = accel["mag"].diff().abs().fillna(0)
        row["accel_n"] = int(len(accel))
        row["acc_mag_mean"] = float(accel["mag"].mean())
        row["acc_mag_std"] = float(accel["mag"].std()) if len(accel) > 1 else 0.0
        row["acc_mag_p95"] = float(np.nanpercentile(accel["mag"].values, 95))
        row["acc_jerk_mean"] = float(accel["jerk"].mean())
        thr_inactive = float(np.nanpercentile(accel["mag"].values, 10))
        row["acc_inactive_ratio"] = float((accel["mag"] < thr_inactive).mean())

    if not gyro.empty and "x" in gyro.columns:
        gyro["mag"] = np.sqrt(gyro["x"] ** 2 + gyro["y"] ** 2 + gyro["z"] ** 2)
        row["gyro_n"] = int(len(gyro))
        row["gyro_mag_mean"] = float(gyro["mag"].mean())
        row["gyro_mag_std"] = float(gyro["mag"].std()) if len(gyro) > 1 else 0.0
        row["gyro_mag_p95"] = float(np.nanpercentile(gyro["mag"].values, 95))

    if not gps.empty and "latitude" in gps.columns:
        gps = gps.copy()
        if "accuracy" in gps.columns:
            gps["accuracy"] = pd.to_numeric(gps["accuracy"], errors="coerce")
            gps = gps[(gps["accuracy"].isna()) | (gps["accuracy"] <= GPS_MAX_ACCURACY_M)]
        if not gps.empty:
            gps = gps.sort_values("timestamp")
            row["gps_n"] = int(len(gps))
            row["gps_points"] = int(len(gps))
            if "accuracy" in gps.columns and gps["accuracy"].notna().any():
                row["gps_mean_accuracy"] = float(gps["accuracy"].mean())
            lat = gps["latitude"].values
            lon = gps["longitude"].values
            dists = [0.0]
            for i in range(1, len(gps)):
                dists.append(haversine_m(lat[i - 1], lon[i - 1], lat[i], lon[i]))
            dists = np.array(dists, dtype=float)
            row["gps_distance_m"] = float(dists.sum())
            row["gps_stationary_ratio"] = float((dists < 5.0).mean())

    if not screen.empty and "event" in screen.columns:
        screen = screen.copy()
        screen["event_time"] = ms_to_dt(screen["timestamp"])
        screen = screen.sort_values("event_time")
        row["screen_event_n"] = int(len(screen))
        row["screen_on_events"] = int((screen["event"] == "Screen turned on").sum())
        row["screen_off_events"] = int((screen["event"] == "Screen turned off").sum())
        sessions = []
        current_on = None
        for _, r in screen.iterrows():
            ev = str(r["event"])
            t = r["event_time"]
            if ev == "Screen turned on":
                current_on = t
            elif ev == "Screen turned off" and current_on is not None and t >= current_on:
                sessions.append((current_on, t, (t - current_on).total_seconds()))
                current_on = None
        if sessions:
            s_df = pd.DataFrame(sessions, columns=["on_time", "off_time", "duration_s"])
            row["screen_sessions"] = int(len(s_df))
            row["screen_on_seconds"] = float(s_df["duration_s"].sum())
            row["screen_avg_session_seconds"] = float(s_df["duration_s"].mean())
        else:
            row["screen_sessions"] = 0
            row["screen_on_seconds"] = 0.0
            row["screen_avg_session_seconds"] = None

    return pd.DataFrame([row])


def upsert_hourly_features(conn, df: pd.DataFrame):
    for _, row in df.iterrows():
        doc = {k: _normalize_doc_value(v) for k, v in row.to_dict().items()}
        _execute(
            conn,
            """
            INSERT INTO hourly_features (
                participant_id, hour_start, accel_n, gyro_n, gps_n, screen_event_n,
                acc_mag_mean, acc_mag_std, acc_jerk_mean, acc_mag_p95, acc_inactive_ratio,
                gyro_mag_mean, gyro_mag_std, gyro_mag_p95, gps_points, gps_mean_accuracy,
                gps_distance_m, gps_stationary_ratio, screen_on_events, screen_off_events,
                screen_sessions, screen_on_seconds, screen_avg_session_seconds
            )
            VALUES (
                %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
            )
            ON DUPLICATE KEY UPDATE
                accel_n = VALUES(accel_n),
                gyro_n = VALUES(gyro_n),
                gps_n = VALUES(gps_n),
                screen_event_n = VALUES(screen_event_n),
                acc_mag_mean = VALUES(acc_mag_mean),
                acc_mag_std = VALUES(acc_mag_std),
                acc_jerk_mean = VALUES(acc_jerk_mean),
                acc_mag_p95 = VALUES(acc_mag_p95),
                acc_inactive_ratio = VALUES(acc_inactive_ratio),
                gyro_mag_mean = VALUES(gyro_mag_mean),
                gyro_mag_std = VALUES(gyro_mag_std),
                gyro_mag_p95 = VALUES(gyro_mag_p95),
                gps_points = VALUES(gps_points),
                gps_mean_accuracy = VALUES(gps_mean_accuracy),
                gps_distance_m = VALUES(gps_distance_m),
                gps_stationary_ratio = VALUES(gps_stationary_ratio),
                screen_on_events = VALUES(screen_on_events),
                screen_off_events = VALUES(screen_off_events),
                screen_sessions = VALUES(screen_sessions),
                screen_on_seconds = VALUES(screen_on_seconds),
                screen_avg_session_seconds = VALUES(screen_avg_session_seconds)
            """,
            (
                doc["participant_id"], doc["hour_start"], doc["accel_n"], doc["gyro_n"], doc["gps_n"],
                doc["screen_event_n"], doc["acc_mag_mean"], doc["acc_mag_std"], doc["acc_jerk_mean"],
                doc["acc_mag_p95"], doc["acc_inactive_ratio"], doc["gyro_mag_mean"], doc["gyro_mag_std"],
                doc["gyro_mag_p95"], doc["gps_points"], doc["gps_mean_accuracy"], doc["gps_distance_m"],
                doc["gps_stationary_ratio"], doc["screen_on_events"], doc["screen_off_events"],
                doc["screen_sessions"], doc["screen_on_seconds"], doc["screen_avg_session_seconds"],
            ),
        )
    conn.commit()


def process_new_hours(conn, participant_id: str, phone_device_id: str):
    min_ms, max_ms = get_time_range_ms(conn, phone_device_id)
    if min_ms is None:
        print(f"  [{participant_id}] No phone data in accelerometer table")
        return 0

    max_dt = pd.to_datetime(max_ms, unit="ms", utc=True).tz_convert(None)
    min_dt = pd.to_datetime(min_ms, unit="ms", utc=True).tz_convert(None)
    latest_complete_hour = max_dt.floor("h") - pd.Timedelta(hours=1)

    state = get_state(conn, participant_id, ENGINE_NAME)
    if not state or not state.get("last_processed_hour_start"):
        next_hour = pd.to_datetime(min_ms, unit="ms", utc=True).tz_convert(None).floor("h")
        last_done = None
    else:
        last_done = state["last_processed_hour_start"]
        next_hour = pd.to_datetime(last_done).floor("h") + pd.Timedelta(hours=1)

    lstm_summary = summarize_lstm_window(conn, participant_id, latest_complete_hour)
    print(
        f"  [{participant_id}] window next={next_hour} latest_complete={latest_complete_hour} "
        f"rows={lstm_summary.rows} contiguous_sequences={lstm_summary.contiguous_sequences}"
    )

    if next_hour > latest_complete_hour:
        # Keep the latest bundle warm even when there is no newly completed hour.
        lstm_bundle = train_or_update_lstm(conn, participant_id, latest_complete_hour)
        if lstm_bundle is None:
            print(f"  [{participant_id}] Skipping hourly processing: next_hour > latest_complete_hour and no LSTM bundle available yet")
        else:
            print(f"  [{participant_id}] Skipping hourly processing: next_hour > latest_complete_hour (LSTM bundle already available)")
        return 0

    backlog_mode = next_hour < latest_complete_hour
    if backlog_mode:
        print(f"  [{participant_id}] Backfill mode: rule alerts enabled, LSTM alerts deferred until backlog is caught up")

    processed = 0
    hour = next_hour
    while hour <= latest_complete_hour:
        hour_end = hour + pd.Timedelta(hours=1)
        lstm_bundle = None
        # Train or reload only for the hour being scored, before its features are
        # inserted, so the model never sees the current hour or future hours.
        if hour == latest_complete_hour:
            lstm_bundle = train_or_update_lstm(conn, participant_id, hour)
        hf_row = build_hourly_features(conn, participant_id, phone_device_id, hour, hour_end)
        upsert_hourly_features(conn, hf_row)
        generate_rule_alerts_for_hour(conn, participant_id, phone_device_id, hf_row.iloc[0])
        if lstm_bundle is not None and not backlog_mode:
            alert = score_hour_with_lstm(conn, participant_id, hour, lstm_bundle)
            if alert is not None:
                insert_alert(conn, alert)
        set_state(conn, participant_id, ENGINE_NAME, {"last_processed_hour_start": hour.to_pydatetime()})
        processed += 1
        hour = hour + pd.Timedelta(hours=1)

    if backlog_mode:
        train_or_update_lstm(conn, participant_id, latest_complete_hour)
    return processed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--loop", action="store_true", help="Run forever; process new completed hours periodically.")
    ap.add_argument("--once", action="store_true", help="Run once and exit (default).")
    args = ap.parse_args()

    if args.loop and args.once:
        raise SystemExit("Choose only one: --loop or --once")

    run_forever = args.loop or (not args.once)
    print(f"[startup] torch_available={TORCH_AVAILABLE}")

    while True:
        conn = None
        try:
            conn = connect()
            ensure_tables(conn)
            participants = fetch_participant_devices(conn)
            print(f"  Found {len(participants)} participants: {[p['participant_id'] for p in participants]}")
            total_h = 0
            total_d = 0
            for participant in participants:
                participant_id = participant["participant_id"]
                phone_device_id = participant.get("phone_device_id")
                watch_device_id = participant.get("watch_device_id")
                if watch_device_id:
                    total_d += process_new_days(conn, participant_id, watch_device_id)
                if phone_device_id:
                    total_h += process_new_hours(conn, participant_id, phone_device_id)
            print(f"[{datetime.utcnow().isoformat()}] processed_hours={total_h} processed_days={total_d}")
        except Exception as e:
            print(f"[{datetime.utcnow().isoformat()}] Error: {e}")
        finally:
            if conn is not None and conn.is_connected():
                conn.close()

        if not run_forever:
            break
        time.sleep(LOOP_INTERVAL_SEC)


if __name__ == "__main__":
    main()
