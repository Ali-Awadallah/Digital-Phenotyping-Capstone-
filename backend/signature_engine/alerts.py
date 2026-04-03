import math
from datetime import datetime
from typing import Optional, Tuple

import numpy as np
import pandas as pd

from signature_engine.config import (
    BASELINE_HOURS,
    PHONE_BP1_VARIABILITY_RATIO,
    PHONE_DS2_SCREEN_ON_RATIO,
    PHONE_DS2_SCREEN_SESSION_RATIO,
    PHONE_ENTROPY_Z_ALERT,
    PHONE_GPS_LOW_DISTANCE_M,
    PHONE_GPS_RATIO_ALERT,
    PHONE_GPS_STATIONARY_MIN,
    PHONE_HIGH_MOTION_RATIO_ALERT,
    PHONE_LOW_MOTION_RATIO_MAX,
    PHONE_NIGHT_SCREEN_RATIO_ALERT,
    PHONE_PS1_GROWTH_RATIO,
    PHONE_PS1_HIGH_GROWTH_RATIO,
    PHONE_PS1_RECENT_ALERT_MIN,
    PHONE_SU3_SCREEN_ON_RATIO,
    PHONE_SU3_SCREEN_SESSION_RATIO,
    WEARABLE_BASELINE_DAYS,
    WEARABLE_ENGINE_NAME,
    WEARABLE_VARIABILITY_RATIO,
    WEARABLE_Z_ALERT,
)
from signature_engine.db import _execute, _query_df, _query_one, get_state, json_dumps_safe, set_state


def insert_alert(conn, alert_row: dict):
    doc = alert_row.copy()
    hs = doc.get("hour_start") or datetime.utcnow()
    if hasattr(hs, "to_pydatetime"):
        hs = hs.to_pydatetime()
    doc["hour_start"] = hs
    doc["hour_start_iso"] = hs.strftime("%Y-%m-%dT%H:%M:%SZ")
    doc["hour_start_ts"] = int(hs.timestamp() * 1000)
    doc["created_at"] = datetime.utcnow()
    doc["status"] = doc.get("status", "new")
    source_type = str(doc.get("source_type") or "").strip().lower()
    if source_type not in {"phone", "watch", "both"}:
        alert_code = str(doc.get("alert_code") or "").upper()
        source_type = "watch" if alert_code.startswith("W") else "phone"
    doc["source_type"] = source_type
    device_id = doc.get("device_id")
    if not device_id and doc.get("participant_id"):
        preferred = _query_one(
            conn,
            """
            SELECT device_id
            FROM participant_devices
            WHERE participant_id = %s
              AND LOWER(COALESCE(device_type, 'unknown')) = %s
            ORDER BY updated_at DESC, id DESC
            LIMIT 1
            """,
            (doc["participant_id"], source_type),
        )
        if preferred and preferred.get("device_id"):
            device_id = preferred["device_id"]
        else:
            fallback = _query_one(
                conn,
                """
                SELECT device_id
                FROM participant_devices
                WHERE participant_id = %s
                ORDER BY updated_at DESC, id DESC
                LIMIT 1
                """,
                (doc["participant_id"],),
            )
            if fallback and fallback.get("device_id"):
                device_id = fallback["device_id"]
    doc["device_id"] = device_id

    _execute(
        conn,
        """
        INSERT INTO signature_alerts (
            participant_id, device_id, source_type, hour_start, hour_start_iso, hour_start_ts, alert_code, alert_name,
            severity, score, baseline_ref, top_features_json, explanation, created_at, status
        )
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON DUPLICATE KEY UPDATE
            device_id = COALESCE(VALUES(device_id), device_id),
            source_type = COALESCE(VALUES(source_type), source_type),
            hour_start_iso = VALUES(hour_start_iso),
            hour_start_ts = VALUES(hour_start_ts),
            alert_name = VALUES(alert_name),
            severity = VALUES(severity),
            score = VALUES(score),
            baseline_ref = VALUES(baseline_ref),
            top_features_json = VALUES(top_features_json),
            explanation = VALUES(explanation),
            created_at = VALUES(created_at),
            status = VALUES(status)
        """,
        (
            doc["participant_id"], doc.get("device_id"), doc.get("source_type"),
            doc["hour_start"], doc["hour_start_iso"], doc["hour_start_ts"],
            doc["alert_code"], doc.get("alert_name"), doc.get("severity"), doc.get("score"),
            doc.get("baseline_ref"), doc.get("top_features_json"), doc.get("explanation"),
            doc["created_at"], doc["status"],
        ),
    )
    conn.commit()


def _baseline_window(conn, pid: str, hour_start: pd.Timestamp) -> pd.DataFrame:
    start_dt = hour_start - pd.Timedelta(hours=BASELINE_HOURS)
    df = _query_df(
        conn,
        """
        SELECT *
        FROM hourly_features
        WHERE participant_id = %s AND hour_start >= %s AND hour_start < %s
        ORDER BY hour_start
        """,
        (pid, start_dt.to_pydatetime(), hour_start.to_pydatetime()),
    )
    if df.empty:
        return df
    df["hour_start"] = pd.to_datetime(df["hour_start"])
    return df.sort_values("hour_start")


def _safe_num_series(df: pd.DataFrame, col: str) -> pd.Series:
    if col not in df.columns:
        return pd.Series(dtype=float)
    return pd.to_numeric(df[col], errors="coerce")


def _robust_ratio(curr: Optional[float], baseline: pd.Series) -> Optional[float]:
    if curr is None:
        return None
    b = baseline.dropna()
    if len(b) < 5:
        return None
    med = float(np.median(b.values)) or 1e-6
    return float(curr) / med


def _robust_z(value: Optional[float], base: np.ndarray) -> Optional[float]:
    if value is None:
        return None
    base = base.astype(np.float32)
    base = base[~np.isnan(base)]
    if base.size < 5:
        return None
    med = float(np.median(base))
    mad = float(np.median(np.abs(base - med))) or 1.0
    return 0.6745 * (float(value) - med) / mad


def _hour_bounds_ms(hour_start: pd.Timestamp) -> Tuple[int, int]:
    start_ms = int(hour_start.timestamp() * 1000)
    end_ms = int((hour_start + pd.Timedelta(hours=1)).timestamp() * 1000)
    return start_ms, end_ms


def _time_range_ms(start_dt: pd.Timestamp, end_dt: pd.Timestamp) -> Tuple[int, int]:
    return int(start_dt.timestamp() * 1000), int(end_dt.timestamp() * 1000)


def _load_hour_locations(conn, device_id: str, hour_start: pd.Timestamp) -> pd.DataFrame:
    start_ms, end_ms = _hour_bounds_ms(hour_start)
    return _query_df(
        conn,
        """
        SELECT latitude, longitude, accuracy
        FROM location
        WHERE device_id = %s AND timestamp >= %s AND timestamp < %s
        ORDER BY timestamp
        """,
        (device_id, start_ms, end_ms),
    )


def _load_screen_events(conn, device_id: str, start_dt: pd.Timestamp, end_dt: pd.Timestamp) -> pd.DataFrame:
    start_ms, end_ms = _time_range_ms(start_dt, end_dt)
    return _query_df(
        conn,
        """
        SELECT timestamp, event
        FROM screen_events
        WHERE device_id = %s AND timestamp >= %s AND timestamp < %s
        ORDER BY timestamp
        """,
        (device_id, start_ms, end_ms),
    )


def _hourly_window(conn, pid: str, start_dt: pd.Timestamp, end_dt: pd.Timestamp) -> pd.DataFrame:
    df = _query_df(
        conn,
        """
        SELECT *
        FROM hourly_features
        WHERE participant_id = %s AND hour_start >= %s AND hour_start < %s
        ORDER BY hour_start
        """,
        (pid, start_dt.to_pydatetime(), end_dt.to_pydatetime()),
    )
    if df.empty:
        return df
    df["hour_start"] = pd.to_datetime(df["hour_start"])
    return df


def _location_entropy(loc_df: pd.DataFrame) -> Optional[float]:
    if loc_df.empty or "latitude" not in loc_df.columns or "longitude" not in loc_df.columns:
        return None
    loc_df = loc_df.copy()
    if "accuracy" in loc_df.columns:
        loc_df["accuracy"] = pd.to_numeric(loc_df["accuracy"], errors="coerce")
        loc_df = loc_df[(loc_df["accuracy"].isna()) | (loc_df["accuracy"] <= 50.0)]
    if loc_df.empty:
        return None

    lat_bins = pd.to_numeric(loc_df["latitude"], errors="coerce").round(3)
    lon_bins = pd.to_numeric(loc_df["longitude"], errors="coerce").round(3)
    cells = (lat_bins.astype(str) + ":" + lon_bins.astype(str)).dropna()
    if cells.empty:
        return None

    probs = cells.value_counts(normalize=True).to_numpy(dtype=np.float32)
    return float(-np.sum(probs * np.log(probs + 1e-12)))


def _baseline_location_entropy(conn, device_id: str, hour_start: pd.Timestamp) -> np.ndarray:
    entropies = []
    for past_hour in pd.date_range(hour_start - pd.Timedelta(hours=BASELINE_HOURS), hour_start - pd.Timedelta(hours=1), freq="h"):
        entropy = _location_entropy(_load_hour_locations(conn, device_id, past_hour))
        if entropy is not None:
            entropies.append(entropy)
    return np.array(entropies, dtype=np.float32)


def _haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371000.0
    p1 = math.radians(lat1)
    p2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def _red_zone_exposure(conn, participant_id: str, phone_device_id: str, hour_start: pd.Timestamp) -> Optional[dict]:
    loc_df = _load_hour_locations(conn, phone_device_id, hour_start)
    if loc_df.empty:
        return None

    zones_df = _query_df(
        conn,
        """
        SELECT zone_id, name, latitude, longitude, radius, zone_type
        FROM red_zones
        WHERE participant_id IS NULL OR participant_id = %s
        """,
        (participant_id,),
    )
    if zones_df.empty:
        return None

    best = None
    for _, zone in zones_df.iterrows():
        for _, point in loc_df.iterrows():
            try:
                distance = _haversine_m(
                    float(point["latitude"]),
                    float(point["longitude"]),
                    float(zone["latitude"]),
                    float(zone["longitude"]),
                )
            except Exception:
                continue
            if distance <= float(zone["radius"]):
                candidate = {
                    "zone_id": zone["zone_id"],
                    "zone_name": zone["name"],
                    "zone_type": zone.get("zone_type"),
                    "distance_m": float(distance),
                }
                if best is None or candidate["distance_m"] < best["distance_m"]:
                    best = candidate
    return best


def _recent_alert_counts(conn, pid: str, hour_start: pd.Timestamp) -> Tuple[int, int]:
    recent = _query_one(
        conn,
        """
        SELECT COUNT(*) AS n
        FROM signature_alerts
        WHERE participant_id = %s
          AND alert_code <> 'PS1'
          AND hour_start >= %s
          AND hour_start < %s
        """,
        (
            pid,
            (hour_start - pd.Timedelta(days=7)).to_pydatetime(),
            hour_start.to_pydatetime(),
        ),
    )
    previous = _query_one(
        conn,
        """
        SELECT COUNT(*) AS n
        FROM signature_alerts
        WHERE participant_id = %s
          AND alert_code <> 'PS1'
          AND hour_start >= %s
          AND hour_start < %s
        """,
        (
            pid,
            (hour_start - pd.Timedelta(days=14)).to_pydatetime(),
            (hour_start - pd.Timedelta(days=7)).to_pydatetime(),
        ),
    )
    return int(recent["n"] if recent and recent["n"] is not None else 0), int(previous["n"] if previous and previous["n"] is not None else 0)


def _screen_sleep_proxy(conn, phone_device_id: str, day_start: pd.Timestamp) -> Optional[dict]:
    window_start = day_start - pd.Timedelta(hours=6)
    window_end = day_start + pd.Timedelta(hours=12)
    screen_df = _load_screen_events(conn, phone_device_id, window_start, window_end)
    if screen_df.empty or "timestamp" not in screen_df.columns:
        return None

    timestamps = pd.to_numeric(screen_df["timestamp"], errors="coerce").dropna().astype(np.int64).tolist()
    start_ms, end_ms = _time_range_ms(window_start, window_end)
    boundaries = [start_ms] + sorted(timestamps) + [end_ms]

    best_gap = None
    for gap_start, gap_end in zip(boundaries, boundaries[1:]):
        gap_hours = (gap_end - gap_start) / 1000.0 / 3600.0
        if gap_hours < 2.0 or gap_hours > 14.0:
            continue
        if best_gap is None or gap_hours > best_gap["duration_hours"]:
            midpoint_ms = gap_start + ((gap_end - gap_start) // 2)
            midpoint_dt = pd.to_datetime(midpoint_ms, unit="ms", utc=True).tz_convert(None)
            best_gap = {
                "duration_hours": float(gap_hours),
                "midpoint_hour": float(midpoint_dt.hour + midpoint_dt.minute / 60.0),
            }
    return best_gap


def _phone_sleep_proxy_series(conn, phone_device_id: str, anchor_day: pd.Timestamp, days: int) -> pd.DataFrame:
    rows = []
    for offset in range(days):
        day = anchor_day - pd.Timedelta(days=offset)
        proxy = _screen_sleep_proxy(conn, phone_device_id, day)
        if proxy is None:
            continue
        rows.append({
            "day_start": day.to_pydatetime(),
            "duration_hours": proxy["duration_hours"],
            "midpoint_hour": proxy["midpoint_hour"],
        })
    if not rows:
        return pd.DataFrame()
    return pd.DataFrame(rows).sort_values("day_start")


def _rmssd_sdnn_from_bpm_series(bpm: np.ndarray) -> Tuple[Optional[float], Optional[float]]:
    bpm = bpm.astype(np.float32)
    bpm = bpm[~np.isnan(bpm)]
    bpm = bpm[bpm > 0]
    if bpm.size < 10:
        return None, None
    rr = 60000.0 / bpm
    diffs = np.diff(rr)
    rmssd = float(np.sqrt(np.mean(diffs ** 2))) if diffs.size > 0 else None
    sdnn = float(np.std(rr)) if rr.size > 1 else None
    return rmssd, sdnn


def generate_rule_alerts_for_hour(conn, participant_id: str, phone_device_id: str, hour_row: dict):
    try:
        hour_start = pd.to_datetime(hour_row["hour_start"])
    except Exception:
        return

    base_df = _baseline_window(conn, participant_id, hour_start)
    if base_df.empty:
        return

    gps_dist = hour_row.get("gps_distance_m")
    gps_stat = hour_row.get("gps_stationary_ratio")
    screen_sessions = hour_row.get("screen_sessions")
    screen_on = hour_row.get("screen_on_seconds")
    acc_std = hour_row.get("acc_mag_std")
    gyro_std = hour_row.get("gyro_mag_std")
    gps_n = hour_row.get("gps_n")
    accel_n = hour_row.get("accel_n")
    gyro_n = hour_row.get("gyro_n")
    is_night = int(hour_start.hour) in [23, 0, 1, 2, 3, 4, 5]

    r_gps = _robust_ratio(gps_dist, _safe_num_series(base_df, "gps_distance_m"))
    r_screen_on = _robust_ratio(screen_on, _safe_num_series(base_df, "screen_on_seconds"))
    r_screen_sessions = _robust_ratio(screen_sessions, _safe_num_series(base_df, "screen_sessions"))
    r_acc_std = _robust_ratio(acc_std, _safe_num_series(base_df, "acc_mag_std"))
    r_gyro_std = _robust_ratio(gyro_std, _safe_num_series(base_df, "gyro_mag_std"))
    triggered_codes = []

    gps_low_mobility = (
        gps_n is not None and int(gps_n) >= 3 and
        gps_dist is not None and gps_stat is not None and
        float(gps_dist) < PHONE_GPS_LOW_DISTANCE_M and float(gps_stat) >= PHONE_GPS_STATIONARY_MIN
    )
    low_motion_signals = 0
    if accel_n is not None and int(accel_n) >= 10 and r_acc_std is not None and r_acc_std < PHONE_LOW_MOTION_RATIO_MAX:
        low_motion_signals += 1
    if gyro_n is not None and int(gyro_n) >= 10 and r_gyro_std is not None and r_gyro_std < PHONE_LOW_MOTION_RATIO_MAX:
        low_motion_signals += 1

    ds_score = 0.0
    if gps_low_mobility and low_motion_signals >= 1:
        ds_score = 1.0 + (0.5 * low_motion_signals)
    if gps_low_mobility and low_motion_signals >= 1:
        insert_alert(conn, {
            "participant_id": participant_id,
            "hour_start": hour_start.to_pydatetime(),
            "alert_code": "DS1",
            "alert_name": "Low mobility + low activity indicator",
            "severity": "LOW" if ds_score < 2 else "MEDIUM",
            "score": float(ds_score),
            "baseline_ref": f"last_{BASELINE_HOURS}h",
            "top_features_json": json_dumps_safe({
                "gps_distance_m": gps_dist,
                "gps_stationary_ratio": gps_stat,
                "acc_mag_std_ratio": r_acc_std,
                "gyro_mag_std_ratio": r_gyro_std,
            }),
            "explanation": "Hour shows very low mobility and low activity compared with baseline.",
        })
        triggered_codes.append("DS1")

    ds2_score = 0.0
    if low_motion_signals >= 1:
        ds2_score += 1.0
    if r_screen_on is not None and r_screen_on >= PHONE_DS2_SCREEN_ON_RATIO:
        ds2_score += 1.0
    if r_screen_sessions is not None and r_screen_sessions >= PHONE_DS2_SCREEN_SESSION_RATIO:
        ds2_score += 0.5
    if ds2_score >= 2.0:
        insert_alert(conn, {
            "participant_id": participant_id,
            "hour_start": hour_start.to_pydatetime(),
            "alert_code": "DS2",
            "alert_name": "Sedentary engagement indicator",
            "severity": "MEDIUM" if ds2_score < 2.5 else "HIGH",
            "score": float(ds2_score),
            "baseline_ref": f"last_{BASELINE_HOURS}h",
            "top_features_json": json_dumps_safe({
                "acc_mag_std_ratio": r_acc_std,
                "gyro_mag_std_ratio": r_gyro_std,
                "screen_on_ratio": r_screen_on,
                "screen_sessions_ratio": r_screen_sessions,
            }),
            "explanation": "Low movement with elevated device engagement relative to baseline.",
        })
        triggered_codes.append("DS2")

    current_entropy = _location_entropy(_load_hour_locations(conn, phone_device_id, hour_start))
    baseline_entropy = _baseline_location_entropy(conn, phone_device_id, hour_start)
    z_entropy = _robust_z(current_entropy, baseline_entropy) if current_entropy is not None else None

    as2_score = 0.0
    if z_entropy is not None and z_entropy >= PHONE_ENTROPY_Z_ALERT:
        as2_score += 1.0
    if r_gps is not None and r_gps >= PHONE_GPS_RATIO_ALERT:
        as2_score += 0.5
    if gps_stat is not None and float(gps_stat) <= 0.3:
        as2_score += 0.5
    if as2_score >= 1.5:
        insert_alert(conn, {
            "participant_id": participant_id,
            "hour_start": hour_start.to_pydatetime(),
            "alert_code": "AS2",
            "alert_name": "Location instability indicator",
            "severity": "HIGH" if as2_score >= 2.0 else "MEDIUM",
            "score": float(as2_score),
            "baseline_ref": f"last_{BASELINE_HOURS}h",
            "top_features_json": json_dumps_safe({
                "gps_distance_m": gps_dist,
                "gps_distance_ratio": r_gps,
                "gps_stationary_ratio": gps_stat,
                "location_entropy": current_entropy,
                "location_entropy_z": z_entropy,
            }),
            "explanation": "Hour shows frequent location/context switching relative to baseline.",
        })
        triggered_codes.append("AS2")

    as_score = (
        (1.0 if is_night and r_screen_on is not None and r_screen_on >= PHONE_NIGHT_SCREEN_RATIO_ALERT else 0.0)
        + (1.0 if is_night and r_acc_std is not None and r_acc_std >= PHONE_HIGH_MOTION_RATIO_ALERT else 0.0)
    )
    if as_score > 0:
        severity = "CRITICAL" if as_score >= 2.0 else "HIGH" if as_score >= 1.5 else "MEDIUM"
        insert_alert(conn, {
            "participant_id": participant_id,
            "hour_start": hour_start.to_pydatetime(),
            "alert_code": "AS1",
            "alert_name": "Night restlessness / sleep disruption indicator",
            "severity": severity,
            "score": float(min(2.0, as_score)),
            "baseline_ref": f"last_{BASELINE_HOURS}h",
            "top_features_json": json_dumps_safe({
                "screen_on_seconds": screen_on,
                "screen_on_ratio": r_screen_on,
                "acc_mag_std_ratio": r_acc_std,
            }),
            "explanation": "Night hour shows elevated screen-on time plus elevated movement versus baseline.",
        })
        triggered_codes.append("AS1")

    su1_score = 0.0
    if z_entropy is not None and z_entropy >= PHONE_ENTROPY_Z_ALERT:
        su1_score += 1.0
    if r_acc_std is not None and r_acc_std >= PHONE_HIGH_MOTION_RATIO_ALERT:
        su1_score += 0.5
    if r_gyro_std is not None and r_gyro_std >= PHONE_HIGH_MOTION_RATIO_ALERT:
        su1_score += 0.5
    if su1_score >= 1.5:
        insert_alert(conn, {
            "participant_id": participant_id,
            "hour_start": hour_start.to_pydatetime(),
            "alert_code": "SU1",
            "alert_name": "High mobility entropy + activity peaks indicator",
            "severity": "HIGH" if su1_score >= 2.0 else "MEDIUM",
            "score": float(su1_score),
            "baseline_ref": f"last_{BASELINE_HOURS}h",
            "top_features_json": json_dumps_safe({
                "location_entropy": current_entropy,
                "location_entropy_z": z_entropy,
                "acc_mag_std_ratio": r_acc_std,
                "gyro_mag_std_ratio": r_gyro_std,
            }),
            "explanation": "Mobility pattern is unusually dispersed and paired with elevated movement.",
        })
        triggered_codes.append("SU1")

    su2_zone = _red_zone_exposure(conn, participant_id, phone_device_id, hour_start)
    if su2_zone is not None:
        insert_alert(conn, {
            "participant_id": participant_id,
            "hour_start": hour_start.to_pydatetime(),
            "alert_code": "SU2",
            "alert_name": "High-risk location exposure indicator",
            "severity": "HIGH",
            "score": 1.0,
            "baseline_ref": "red_zone_match",
            "top_features_json": json_dumps_safe(su2_zone),
            "explanation": "Device entered a configured relapse-associated red zone.",
        })
        triggered_codes.append("SU2")

    su3_score = 0.0
    if r_screen_sessions is not None and r_screen_sessions >= PHONE_SU3_SCREEN_SESSION_RATIO:
        su3_score += 1.0
    if r_screen_on is not None and r_screen_on >= PHONE_SU3_SCREEN_ON_RATIO:
        su3_score += 1.0
    if su3_score >= 1.5:
        insert_alert(conn, {
            "participant_id": participant_id,
            "hour_start": hour_start.to_pydatetime(),
            "alert_code": "SU3",
            "alert_name": "Interaction spike indicator",
            "severity": "HIGH" if su3_score >= 2.0 else "MEDIUM",
            "score": float(su3_score),
            "baseline_ref": f"last_{BASELINE_HOURS}h",
            "top_features_json": json_dumps_safe({
                "screen_sessions_ratio": r_screen_sessions,
                "screen_on_ratio": r_screen_on,
            }),
            "explanation": "Phone engagement spiked sharply relative to personal baseline.",
        })
        triggered_codes.append("SU3")

    recent_alerts_7d, previous_alerts_7d = _recent_alert_counts(conn, participant_id, hour_start)
    if triggered_codes and recent_alerts_7d >= PHONE_PS1_RECENT_ALERT_MIN and recent_alerts_7d >= max(6, int(previous_alerts_7d * PHONE_PS1_GROWTH_RATIO)):
        insert_alert(conn, {
            "participant_id": participant_id,
            "hour_start": hour_start.to_pydatetime(),
            "alert_code": "PS1",
            "alert_name": "Pre-relapse anomaly spike indicator",
            "severity": "HIGH" if recent_alerts_7d >= max(15, previous_alerts_7d * PHONE_PS1_HIGH_GROWTH_RATIO) else "MEDIUM",
            "score": float(recent_alerts_7d),
            "baseline_ref": "alert_count_last_7d_vs_previous_7d",
            "top_features_json": json_dumps_safe({
                "current_triggered_codes": triggered_codes,
                "recent_alerts_7d": recent_alerts_7d,
                "previous_alerts_7d": previous_alerts_7d,
            }),
            "explanation": "Behavioral irregularities have increased substantially over the recent week versus the prior week.",
        })

    if hour_start.hour == 12:
        recent_phone_sleep = _phone_sleep_proxy_series(conn, phone_device_id, hour_start.floor("D"), 7)
        baseline_phone_sleep = _phone_sleep_proxy_series(conn, phone_device_id, hour_start.floor("D") - pd.Timedelta(days=7), 14)
        recent_hours = _hourly_window(conn, participant_id, hour_start - pd.Timedelta(hours=72), hour_start)
        baseline_hours = _hourly_window(conn, participant_id, hour_start - pd.Timedelta(days=14), hour_start - pd.Timedelta(hours=72))

        recent_sleep_mid = _safe_num_series(recent_phone_sleep, "midpoint_hour").dropna()
        baseline_sleep_mid = _safe_num_series(baseline_phone_sleep, "midpoint_hour").dropna()
        recent_acc_motion = _safe_num_series(recent_hours, "acc_mag_std").dropna()
        baseline_acc_motion = _safe_num_series(baseline_hours, "acc_mag_std").dropna()
        recent_gyro_motion = _safe_num_series(recent_hours, "gyro_mag_std").dropna()
        baseline_gyro_motion = _safe_num_series(baseline_hours, "gyro_mag_std").dropna()

        sleep_cv_ratio = None
        movement_var_ratio = None
        if len(recent_sleep_mid) >= 5 and len(baseline_sleep_mid) >= 7:
            baseline_sleep_std = float(baseline_sleep_mid.std())
            if baseline_sleep_std > 0:
                sleep_cv_ratio = float(recent_sleep_mid.std() / baseline_sleep_std)
        recent_motion_std = None
        if len(recent_acc_motion) >= 12 and len(baseline_acc_motion) >= 24:
            baseline_acc_std = float(baseline_acc_motion.std())
            if baseline_acc_std > 0:
                recent_motion_std = float(recent_acc_motion.std() / baseline_acc_std)
        if len(recent_gyro_motion) >= 12 and len(baseline_gyro_motion) >= 24:
            baseline_gyro_std = float(baseline_gyro_motion.std())
            if baseline_gyro_std > 0:
                gyro_ratio = float(recent_gyro_motion.std() / baseline_gyro_std)
                movement_var_ratio = gyro_ratio if recent_motion_std is None else max(recent_motion_std, gyro_ratio)
        elif recent_motion_std is not None:
            movement_var_ratio = recent_motion_std

        bp1_score = 0.0
        if movement_var_ratio is not None and movement_var_ratio >= PHONE_BP1_VARIABILITY_RATIO:
            bp1_score += 1.0
        if sleep_cv_ratio is not None and sleep_cv_ratio >= PHONE_BP1_VARIABILITY_RATIO:
            bp1_score += 1.0
        if bp1_score >= 2.0:
            insert_alert(conn, {
                "participant_id": participant_id,
                "hour_start": hour_start.to_pydatetime(),
                "alert_code": "BP1",
                "alert_name": "Activity variability + sleep timing irregularity indicator",
                "severity": "HIGH",
                "score": float(bp1_score),
                "baseline_ref": "recent_72h_vs_prior_14d_plus_screen_sleep_proxy",
                "top_features_json": json_dumps_safe({
                    "movement_variability_ratio": movement_var_ratio,
                    "sleep_midpoint_cv_ratio": sleep_cv_ratio,
                }),
                "explanation": "Recent movement variability and phone-derived overnight sleep timing variability both exceed baseline.",
            })


def wearable_time_range_ms(conn, watch_device_id: str) -> Tuple[Optional[int], Optional[int]]:
    row = _query_one(
        conn,
        """
        SELECT MIN(ts) AS min_ts, MAX(ts) AS max_ts
        FROM (
            SELECT timestamp AS ts FROM wearable_heart_rate WHERE device_id = %s
            UNION ALL
            SELECT start_time AS ts FROM wearable_steps WHERE device_id = %s
            UNION ALL
            SELECT start_time AS ts FROM wearable_sleep WHERE device_id = %s
            UNION ALL
            SELECT end_time AS ts FROM wearable_sleep WHERE device_id = %s
        ) wearable_points
        """,
        (watch_device_id, watch_device_id, watch_device_id, watch_device_id),
    )
    if not row or row["min_ts"] is None:
        return None, None
    return row["min_ts"], row["max_ts"]


def _clip_overlap_ms(start_series: pd.Series, end_series: pd.Series, start_ms: int, end_ms: int) -> pd.Series:
    start_vals = pd.to_numeric(start_series, errors="coerce")
    end_vals = pd.to_numeric(end_series, errors="coerce")
    clipped_start = start_vals.clip(lower=start_ms, upper=end_ms)
    clipped_end = end_vals.clip(lower=start_ms, upper=end_ms)
    return (clipped_end - clipped_start).clip(lower=0)


def _hour_of_day_from_ms(ts_ms: int) -> float:
    dt = pd.to_datetime(int(ts_ms), unit="ms", utc=True).tz_convert(None)
    return float(dt.hour + dt.minute / 60.0 + dt.second / 3600.0)


def _main_sleep_overlap_row(sleep_df: pd.DataFrame, start_ms: int, end_ms: int) -> Optional[pd.Series]:
    if sleep_df.empty:
        return None
    overlap_ms = _clip_overlap_ms(sleep_df["start_time"], sleep_df["end_time"], start_ms, end_ms)
    scored = sleep_df.assign(overlap_ms=overlap_ms)
    scored = scored[scored["overlap_ms"] > 0]
    if scored.empty:
        return None
    return scored.sort_values("overlap_ms", ascending=False).iloc[0]


def build_wearable_daily_features(conn, participant_id: str, watch_device_id: str, day_start: pd.Timestamp, day_end: pd.Timestamp) -> Optional[dict]:
    start_ms = int(day_start.timestamp() * 1000)
    end_ms = int(day_end.timestamp() * 1000)
    query_end_ms = end_ms - 1
    steps_df = _query_df(
        conn,
        "SELECT device_id, start_time, `count` AS count FROM wearable_steps WHERE device_id = %s AND start_time >= %s AND start_time < %s",
        (watch_device_id, start_ms, end_ms),
    )
    sleep_df = _query_df(
        conn,
        "SELECT device_id, start_time, end_time FROM wearable_sleep WHERE device_id = %s AND start_time < %s AND end_time > %s",
        (watch_device_id, end_ms, start_ms),
    )
    hr_df = _query_df(
        conn,
        "SELECT device_id, timestamp, bpm FROM wearable_heart_rate WHERE device_id = %s AND timestamp >= %s AND timestamp < %s",
        (watch_device_id, start_ms, end_ms),
    )
    if steps_df.empty and sleep_df.empty and hr_df.empty:
        return None

    steps_total = None if steps_df.empty or "count" not in steps_df.columns else float(pd.to_numeric(steps_df["count"], errors="coerce").fillna(0).sum())
    sleep_minutes = None
    sleep_episode_n = None
    sleep_start_hour = None
    sleep_end_hour = None
    sleep_midpoint_hour = None
    if not sleep_df.empty and "start_time" in sleep_df.columns and "end_time" in sleep_df.columns:
        overlap_ms = _clip_overlap_ms(sleep_df["start_time"], sleep_df["end_time"], start_ms, end_ms)
        overlap_min = overlap_ms / 1000.0 / 60.0
        valid_overlap = overlap_min[overlap_min > 0]
        if not valid_overlap.empty:
            sleep_minutes = float(valid_overlap.sum())
            sleep_episode_n = int(valid_overlap.shape[0])
            main = _main_sleep_overlap_row(sleep_df, start_ms, end_ms)
            if main is not None:
                clipped_start_ms = max(int(main["start_time"]), start_ms)
                clipped_end_ms = min(int(main["end_time"]), query_end_ms)
                sleep_start_hour = _hour_of_day_from_ms(clipped_start_ms)
                sleep_end_hour = _hour_of_day_from_ms(clipped_end_ms)
                sleep_midpoint_hour = _hour_of_day_from_ms(clipped_start_ms + ((clipped_end_ms - clipped_start_ms) // 2))

    day_hr_mean = None
    night_hr_mean = None
    resting_hr_p10 = None
    rmssd_night = None
    sdnn_night = None
    if not hr_df.empty and "bpm" in hr_df.columns and "timestamp" in hr_df.columns:
        hr_df = hr_df.copy()
        hr_df["dt"] = pd.to_datetime(hr_df["timestamp"], unit="ms", utc=True).dt.tz_convert(None)
        hr_df["bpm"] = pd.to_numeric(hr_df["bpm"], errors="coerce")
        bpm_all = hr_df["bpm"].dropna()
        if not bpm_all.empty:
            day_hr_mean = float(bpm_all.mean())
            resting_hr_p10 = float(np.percentile(bpm_all.to_numpy(dtype=np.float32), 10))
        night = hr_df[(hr_df["dt"].dt.hour >= 0) & (hr_df["dt"].dt.hour <= 5)]
        bpm_night = night["bpm"].dropna()
        if not bpm_night.empty:
            night_hr_mean = float(bpm_night.mean())
            rmssd_night, sdnn_night = _rmssd_sdnn_from_bpm_series(bpm_night.to_numpy(dtype=np.float32))

    return {
        "participant_id": participant_id,
        "day_start": day_start.to_pydatetime(),
        "steps_total": steps_total,
        "sleep_minutes": sleep_minutes,
        "sleep_episode_n": sleep_episode_n,
        "sleep_start_hour": sleep_start_hour,
        "sleep_end_hour": sleep_end_hour,
        "sleep_midpoint_hour": sleep_midpoint_hour,
        "day_hr_mean": day_hr_mean,
        "night_hr_mean": night_hr_mean,
        "resting_hr_p10": resting_hr_p10,
        "rmssd_night": rmssd_night,
        "sdnn_night": sdnn_night,
    }


def upsert_wearable_daily(conn, doc: dict):
    out = {k: (None if isinstance(v, float) and math.isnan(v) else v) for k, v in doc.items()}
    _execute(
        conn,
        """
        INSERT INTO wearable_daily_features (
            participant_id, day_start, steps_total, sleep_minutes, sleep_episode_n,
            sleep_start_hour, sleep_end_hour, sleep_midpoint_hour,
            day_hr_mean, night_hr_mean, resting_hr_p10, rmssd_night, sdnn_night
        )
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON DUPLICATE KEY UPDATE
            steps_total = VALUES(steps_total),
            sleep_minutes = VALUES(sleep_minutes),
            sleep_episode_n = VALUES(sleep_episode_n),
            sleep_start_hour = VALUES(sleep_start_hour),
            sleep_end_hour = VALUES(sleep_end_hour),
            sleep_midpoint_hour = VALUES(sleep_midpoint_hour),
            day_hr_mean = VALUES(day_hr_mean),
            night_hr_mean = VALUES(night_hr_mean),
            resting_hr_p10 = VALUES(resting_hr_p10),
            rmssd_night = VALUES(rmssd_night),
            sdnn_night = VALUES(sdnn_night)
        """,
        (
            out["participant_id"], out["day_start"], out["steps_total"], out["sleep_minutes"],
            out["sleep_episode_n"], out["sleep_start_hour"], out["sleep_end_hour"], out["sleep_midpoint_hour"],
            out["day_hr_mean"], out["night_hr_mean"], out["resting_hr_p10"], out["rmssd_night"], out["sdnn_night"],
        ),
    )
    conn.commit()


def wearable_signatures_for_day(conn, pid: str, day_start: pd.Timestamp):
    day_doc = _query_one(
        conn,
        "SELECT * FROM wearable_daily_features WHERE participant_id = %s AND day_start = %s",
        (pid, day_start.to_pydatetime()),
    )
    if not day_doc:
        return
    base_df = _query_df(
        conn,
        """
        SELECT *
        FROM wearable_daily_features
        WHERE participant_id = %s AND day_start >= %s AND day_start < %s
        ORDER BY day_start
        """,
        (pid, (day_start - pd.Timedelta(days=WEARABLE_BASELINE_DAYS)).to_pydatetime(), day_start.to_pydatetime()),
    )
    if base_df.empty:
        return

    def col_arr(name: str) -> np.ndarray:
        return pd.to_numeric(base_df.get(name), errors="coerce").to_numpy(dtype=np.float32)

    steps = day_doc.get("steps_total")
    sleep_min = day_doc.get("sleep_minutes")
    night_hr = day_doc.get("night_hr_mean")
    sleep_start_hr = day_doc.get("sleep_start_hour")
    sleep_mid_hr = day_doc.get("sleep_midpoint_hour")
    sleep_episode_n = day_doc.get("sleep_episode_n")
    rmssd_night = day_doc.get("rmssd_night")
    z_steps = _robust_z(steps, col_arr("steps_total"))
    z_sleep = _robust_z(sleep_min, col_arr("sleep_minutes"))
    z_night_hr = _robust_z(night_hr, col_arr("night_hr_mean"))
    z_sleep_start = _robust_z(sleep_start_hr, col_arr("sleep_start_hour"))
    z_sleep_mid = _robust_z(sleep_mid_hr, col_arr("sleep_midpoint_hour"))
    z_sleep_episode_n = _robust_z(sleep_episode_n, col_arr("sleep_episode_n"))
    z_rmssd = _robust_z(rmssd_night, col_arr("rmssd_night"))

    recent_df = _query_df(
        conn,
        """
        SELECT *
        FROM wearable_daily_features
        WHERE participant_id = %s AND day_start >= %s AND day_start <= %s
        ORDER BY day_start
        """,
        (pid, (day_start - pd.Timedelta(days=7)).to_pydatetime(), day_start.to_pydatetime()),
    )

    def safe_std(df: pd.DataFrame, name: str) -> Optional[float]:
        if df.empty or name not in df.columns:
            return None
        s = pd.to_numeric(df[name], errors="coerce").dropna()
        return None if len(s) < 5 else float(s.std())

    recent_steps_std = safe_std(recent_df, "steps_total")
    recent_sleep_start_std = safe_std(recent_df, "sleep_start_hour")
    base_steps_std = safe_std(base_df, "steps_total")
    base_sleep_start_std = safe_std(base_df, "sleep_start_hour")
    anomaly_count = 0
    anomaly_details = {}
    stream_flags = set()

    wa1_score = 0.0
    if z_night_hr is not None and z_night_hr >= WEARABLE_Z_ALERT:
        wa1_score += min(float(z_night_hr), 5.0)
        anomaly_details["night_hr_mean_z"] = z_night_hr
    if z_rmssd is not None and z_rmssd <= -WEARABLE_Z_ALERT:
        wa1_score += min(float(abs(z_rmssd)), 5.0)
        anomaly_details["rmssd_night_z"] = z_rmssd
    if wa1_score > 0:
        stream_flags.add("physiology")
        anomaly_count += 1
        insert_alert(conn, {
            "participant_id": pid, "hour_start": day_start.to_pydatetime(), "alert_code": "WA1",
            "alert_name": "Wearable physiological arousal proxy (night HR up and/or HRV down)",
            "severity": "MEDIUM" if wa1_score < 5 else "HIGH", "score": float(wa1_score),
            "baseline_ref": f"wearable_last_{WEARABLE_BASELINE_DAYS}d",
            "top_features_json": json_dumps_safe({
                "night_hr_mean": night_hr,
                "night_hr_z": z_night_hr,
                "rmssd_night": rmssd_night,
                "rmssd_night_z": z_rmssd,
            }),
            "explanation": "Night physiology deviated from baseline through elevated HR and/or reduced HRV.",
        })
    wa2_components = []
    if z_sleep is not None and z_sleep <= -WEARABLE_Z_ALERT:
        wa2_components.append(min(float(abs(z_sleep)), 5.0))
        anomaly_details["sleep_minutes_z"] = z_sleep
    if z_sleep_episode_n is not None and z_sleep_episode_n >= WEARABLE_Z_ALERT:
        wa2_components.append(min(float(z_sleep_episode_n), 5.0))
        anomaly_details["sleep_episode_n_z"] = z_sleep_episode_n
    if z_sleep_mid is not None and abs(z_sleep_mid) >= WEARABLE_Z_ALERT:
        wa2_components.append(min(float(abs(z_sleep_mid)), 5.0))
        anomaly_details["sleep_midpoint_hour_z"] = z_sleep_mid
    wa2_score = float(sum(wa2_components))
    if len(wa2_components) >= 2 or (wa2_components and max(wa2_components) >= 4.0):
        stream_flags.add("sleep")
        anomaly_count += 1
        insert_alert(conn, {
            "participant_id": pid, "hour_start": day_start.to_pydatetime(), "alert_code": "WA2",
            "alert_name": "Wearable night restlessness / sleep fragmentation proxy",
            "severity": "MEDIUM" if wa2_score < 5 else "HIGH", "score": float(wa2_score),
            "baseline_ref": f"wearable_last_{WEARABLE_BASELINE_DAYS}d",
            "top_features_json": json_dumps_safe({
                "sleep_minutes": sleep_min,
                "sleep_minutes_z": z_sleep,
                "sleep_episode_n": sleep_episode_n,
                "sleep_episode_n_z": z_sleep_episode_n,
                "sleep_midpoint_hour": sleep_mid_hr,
                "sleep_midpoint_hour_z": z_sleep_mid,
            }),
            "explanation": "Sleep duration, fragmentation, or midpoint timing deviated from baseline.",
        })
    if z_steps is not None and abs(z_steps) >= WEARABLE_Z_ALERT:
        stream_flags.add("steps")
        anomaly_details["steps_total_z"] = z_steps
    if z_sleep_start is not None and abs(z_sleep_start) >= WEARABLE_Z_ALERT:
        stream_flags.add("sleep_timing")
        anomaly_details["sleep_start_hour_z"] = z_sleep_start

    prev_day_doc = _query_one(
        conn,
        """
        SELECT steps_total, day_start
        FROM wearable_daily_features
        WHERE participant_id = %s AND day_start = %s
        """,
        (pid, (day_start - pd.Timedelta(days=1)).to_pydatetime()),
    )
    prev_z_steps = None
    if prev_day_doc and prev_day_doc.get("steps_total") is not None:
        prev_base_df = _query_df(
            conn,
            """
            SELECT steps_total
            FROM wearable_daily_features
            WHERE participant_id = %s AND day_start >= %s AND day_start < %s
            ORDER BY day_start
            """,
            (
                pid,
                (day_start - pd.Timedelta(days=WEARABLE_BASELINE_DAYS + 1)).to_pydatetime(),
                (day_start - pd.Timedelta(days=1)).to_pydatetime(),
            ),
        )
        if not prev_base_df.empty:
            prev_z_steps = _robust_z(prev_day_doc.get("steps_total"), pd.to_numeric(prev_base_df["steps_total"], errors="coerce").to_numpy(dtype=np.float32))
    if z_steps is not None and z_steps <= -WEARABLE_Z_ALERT and prev_z_steps is not None and prev_z_steps <= -WEARABLE_Z_ALERT:
        stream_flags.add("steps")
        anomaly_count += 1
        insert_alert(conn, {
            "participant_id": pid, "hour_start": day_start.to_pydatetime(), "alert_code": "WD1",
            "alert_name": "Reduced activity for two consecutive days",
            "severity": "HIGH" if min(z_steps, prev_z_steps) <= -3 else "MEDIUM",
            "score": float(abs(z_steps) + abs(prev_z_steps)),
            "baseline_ref": f"wearable_last_{WEARABLE_BASELINE_DAYS}d",
            "top_features_json": json_dumps_safe({"today_z_steps": z_steps, "previous_day_z_steps": prev_z_steps}),
            "explanation": "Step count remained at least two standard deviations below baseline for at least two days.",
        })

    if (z_sleep is not None and z_sleep <= -WEARABLE_Z_ALERT) and (z_night_hr is not None and z_night_hr >= WEARABLE_Z_ALERT):
        stream_flags.update({"sleep", "physiology"})
        anomaly_count = max(anomaly_count, len(stream_flags))
        insert_alert(conn, {
            "participant_id": pid, "hour_start": day_start.to_pydatetime(), "alert_code": "WD2",
            "alert_name": "Depression risk proxy: sleep disruption + elevated night HR trend",
            "severity": "HIGH" if (abs(z_sleep) >= 3 or z_night_hr >= 3) else "MEDIUM",
            "score": float(abs(z_sleep) + z_night_hr), "baseline_ref": f"wearable_last_{WEARABLE_BASELINE_DAYS}d",
            "top_features_json": json_dumps_safe({"sleep_minutes": sleep_min, "sleep_z": z_sleep, "night_hr_mean": night_hr, "night_hr_z": z_night_hr}),
            "explanation": "Reduced sleep plus elevated night HR vs baseline; interpret with context.",
        })

    steps_var_ratio = (recent_steps_std / base_steps_std) if (recent_steps_std is not None and base_steps_std not in (None, 0)) else None
    sleep_var_ratio = (recent_sleep_start_std / base_sleep_start_std) if (recent_sleep_start_std is not None and base_sleep_start_std not in (None, 0)) else None
    if (steps_var_ratio is not None and steps_var_ratio >= WEARABLE_VARIABILITY_RATIO) or (
        sleep_var_ratio is not None and sleep_var_ratio >= WEARABLE_VARIABILITY_RATIO
    ):
        stream_flags.add("variability")
        anomaly_count += 1
        insert_alert(conn, {
            "participant_id": pid, "hour_start": day_start.to_pydatetime(), "alert_code": "WB1",
            "alert_name": "Bipolar risk proxy: activity variability + sleep irregularity (7-day variability up)",
            "severity": "MEDIUM", "score": float((steps_var_ratio or 0) + (sleep_var_ratio or 0)),
            "baseline_ref": f"wearable_last_{WEARABLE_BASELINE_DAYS}d",
            "top_features_json": json_dumps_safe({
                "steps_variability_ratio": steps_var_ratio,
                "sleep_timing_variability_ratio": sleep_var_ratio,
            }),
            "explanation": "Increased variability in steps and/or sleep timing vs baseline.",
        })

    if (z_sleep is not None and z_sleep <= -WEARABLE_Z_ALERT) and (z_steps is not None and z_steps >= WEARABLE_Z_ALERT) and (
        z_night_hr is not None and z_night_hr >= WEARABLE_Z_ALERT
    ):
        stream_flags.update({"sleep", "steps", "physiology"})
        anomaly_count = max(anomaly_count, len(stream_flags))
        insert_alert(conn, {
            "participant_id": pid, "hour_start": day_start.to_pydatetime(), "alert_code": "WB2",
            "alert_name": "Bipolar risk proxy: elevated activation (sleep down + steps up + night HR up)",
            "severity": "HIGH", "score": float(abs(z_sleep) + z_steps + z_night_hr),
            "baseline_ref": f"wearable_last_{WEARABLE_BASELINE_DAYS}d",
            "top_features_json": json_dumps_safe({"sleep_minutes": sleep_min, "sleep_z": z_sleep, "steps_total": steps, "steps_z": z_steps, "night_hr_mean": night_hr, "night_hr_z": z_night_hr}),
            "explanation": "Pattern consistent with elevated activation proxy; requires context.",
        })

    stream_count = len(stream_flags)
    if stream_count >= 2:
        insert_alert(conn, {
            "participant_id": pid, "hour_start": day_start.to_pydatetime(), "alert_code": "WSU3",
            "alert_name": "Baseline-relative wearable anomaly rate (multi-stream)",
            "severity": "HIGH" if stream_count >= 4 else "MEDIUM" if stream_count >= 3 else "LOW",
            "score": float(stream_count), "baseline_ref": f"wearable_last_{WEARABLE_BASELINE_DAYS}d",
            "top_features_json": json_dumps_safe(anomaly_details),
            "explanation": "Multiple wearable streams deviated from baseline on the same day.",
        })

    # WSU2 requires TAC/EDA-like relapse physiology streams that are not present in this schema.


def process_new_days(conn, participant_id: str, watch_device_id: str) -> int:
    min_ms, max_ms = wearable_time_range_ms(conn, watch_device_id)
    if min_ms is None:
        return 0
    min_dt = pd.to_datetime(min_ms, unit="ms", utc=True).tz_convert(None)
    max_dt = pd.to_datetime(max_ms, unit="ms", utc=True).tz_convert(None)
    latest_complete_day = max_dt.floor("D") - pd.Timedelta(days=1)
    if latest_complete_day < min_dt.floor("D"):
        return 0

    state = get_state(conn, participant_id, WEARABLE_ENGINE_NAME)
    next_day = min_dt.floor("D") if not state or not state.get("last_processed_day_start") else pd.to_datetime(state["last_processed_day_start"]).floor("D") + pd.Timedelta(days=1)

    processed = 0
    day = next_day
    while day <= latest_complete_day:
        doc = build_wearable_daily_features(conn, participant_id, watch_device_id, day, day + pd.Timedelta(days=1))
        if doc is not None:
            upsert_wearable_daily(conn, doc)
            wearable_signatures_for_day(conn, participant_id, day)
        set_state(conn, participant_id, WEARABLE_ENGINE_NAME, {"last_processed_day_start": day.to_pydatetime()})
        processed += 1
        day = day + pd.Timedelta(days=1)
    return processed
