# ai_model_super_advanced_v2.py
#
# Advanced anomaly detection for accelerometer data in MySQL.
# Version WITHOUT rolling skew/kurtosis (removes precision-loss warnings).
#
# Improvements kept:
#   - Adaptive thresholding
#   - Severity labeling
#   - Night-time weighting
#   - Rolling feature engineering
#   - Optional smoothing (not enabled here)


import pandas as pd
import numpy as np
import mysql.connector
from sklearn.ensemble import IsolationForest
from sklearn.preprocessing import StandardScaler


# ------------------------------
# Database loading
# ------------------------------
def _load_by_timestamp_ms(db_config, start_ms: int, end_ms: int) -> pd.DataFrame:
    conn = mysql.connector.connect(**db_config)
    cur = conn.cursor(dictionary=True)
    cur.execute(
        """
        SELECT id, `timestamp`, `UTC time` AS utc_time_str, x, y, z
        FROM accelerometer
        WHERE `timestamp` BETWEEN %s AND %s
        ORDER BY `timestamp` ASC
        """,
        (int(start_ms), int(end_ms)),
    )
    rows = cur.fetchall()
    cur.close()
    conn.close()
    return pd.DataFrame(rows) if rows else pd.DataFrame()


# ------------------------------
# Data preparation
# ------------------------------
def _prepare(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()

    if "utc_time_str" in df.columns:
        df["event_time"] = pd.to_datetime(df["utc_time_str"], errors="coerce")
    else:
        df["event_time"] = pd.NaT

    miss = df["event_time"].isna()
    if miss.any():
        ts = pd.to_numeric(df.loc[miss, "timestamp"], errors="coerce")
        df.loc[miss, "event_time"] = pd.to_datetime(ts, unit="ms", utc=True).dt.tz_convert(None)

    for c in ["x", "y", "z"]:
        df[c] = pd.to_numeric(df[c], errors="coerce")

    df.bfill(inplace=True)
    df.ffill(inplace=True)
    df = df.sort_values("event_time").reset_index(drop=True)
    return df


# ------------------------------
# Feature engineering
# ------------------------------
def _add_features(df: pd.DataFrame):
    df = df.copy()

    # Magnitude and derivatives
    df["magnitude"] = np.sqrt(df["x"] ** 2 + df["y"] ** 2 + df["z"] ** 2)
    df["dx"] = df["x"].diff().bfill()
    df["dy"] = df["y"].diff().bfill()
    df["dz"] = df["z"].diff().bfill()
    df["ddx"] = df["dx"].diff().bfill()
    df["ddy"] = df["dy"].diff().bfill()
    df["ddz"] = df["dz"].diff().bfill()
    df["mag_diff"] = df["magnitude"].diff().bfill()
    df["mag_change"] = df["mag_diff"].abs()

    # Spike flag (computed within the current dataframe)
    q95 = df["mag_change"].quantile(0.95) if len(df) else 0.0
    df["spike_flag"] = (df["mag_change"] > q95).astype(int)

    SHORT_WINDOW = 5
    LONG_WINDOW = 20

    # Rolling stats (NO skew/kurtosis)
    for col in ["x", "y", "z", "magnitude"]:
        df[f"{col}_roll_mean_short"] = df[col].rolling(SHORT_WINDOW).mean().bfill()
        df[f"{col}_roll_mean_long"] = df[col].rolling(LONG_WINDOW).mean().bfill()
        df[f"{col}_roll_std_short"] = df[col].rolling(SHORT_WINDOW).std().bfill()
        df[f"{col}_roll_std_long"] = df[col].rolling(LONG_WINDOW).std().bfill()
        df[f"{col}_roll_min_short"] = df[col].rolling(SHORT_WINDOW).min().bfill()
        df[f"{col}_roll_min_long"] = df[col].rolling(LONG_WINDOW).min().bfill()
        df[f"{col}_roll_max_short"] = df[col].rolling(SHORT_WINDOW).max().bfill()
        df[f"{col}_roll_max_long"] = df[col].rolling(LONG_WINDOW).max().bfill()
        df[f"{col}_roll_median_short"] = df[col].rolling(SHORT_WINDOW).median().bfill()
        df[f"{col}_roll_median_long"] = df[col].rolling(LONG_WINDOW).median().bfill()

    # Movement inactivity features
    df["zero_movement"] = (df["magnitude"] < 0.05).astype(int)
    df["zero_pct_short"] = df["zero_movement"].rolling(SHORT_WINDOW).mean().bfill()
    df["zero_pct_long"] = df["zero_movement"].rolling(LONG_WINDOW).mean().bfill()

    # Time-of-day features
    df["hour_of_day"] = df["event_time"].dt.hour
    df["is_night"] = df["hour_of_day"].isin([23, 0, 1, 2, 3, 4, 5]).astype(int)

    feature_cols = [
        "x", "y", "z", "magnitude",
        "dx", "dy", "dz",
        "ddx", "ddy", "ddz",
        "mag_diff", "mag_change",
        "spike_flag",
        "zero_pct_short", "zero_pct_long",
        "hour_of_day", "is_night",
    ]

    for c in ["x", "y", "z", "magnitude"]:
        for stat in [
            "roll_mean_short", "roll_mean_long",
            "roll_std_short", "roll_std_long",
            "roll_min_short", "roll_min_long",
            "roll_max_short", "roll_max_long",
            "roll_median_short", "roll_median_long",
        ]:
            feature_cols.append(f"{c}_{stat}")

    return df, feature_cols


# ------------------------------
# Adaptive threshold
# ------------------------------
def compute_adaptive_threshold(base_scores, multiplier=3.0):
    mean = np.mean(base_scores)
    std = np.std(base_scores)
    return mean + multiplier * std


# ------------------------------
# Severity assignment
# ------------------------------
def assign_severity(anomaly_scores, threshold):
    severity = []
    for s in anomaly_scores:
        if s > threshold * 3:
            severity.append("CRITICAL")
        elif s > threshold * 2:
            severity.append("HIGH")
        elif s > threshold * 1.5:
            severity.append("MEDIUM")
        elif s > threshold:
            severity.append("LOW")
        else:
            severity.append("NORMAL")
    return severity


# ------------------------------
# Main processing function
# ------------------------------
def process_features(
    db_config=None,
    start_dt=None,
    end_dt=None,
    baseline_hours=24,
    contamination=0.01,
    baseline_min_rows=200,
    chunk_min_rows=50,
):
    if db_config is None or start_dt is None or end_dt is None:
        raise ValueError("db_config, start_dt, end_dt are required")

    start_dt = pd.to_datetime(start_dt)
    end_dt = pd.to_datetime(end_dt)
    baseline_start = start_dt - pd.Timedelta(hours=baseline_hours)

    baseline_start_ms = int(baseline_start.timestamp() * 1000)
    start_ms = int(start_dt.timestamp() * 1000)
    end_ms = int(end_dt.timestamp() * 1000)

    base = _load_by_timestamp_ms(db_config, baseline_start_ms, start_ms)
    chunk = _load_by_timestamp_ms(db_config, start_ms, end_ms)

    if base.empty or chunk.empty:
        return pd.DataFrame()

    base = _prepare(base)
    chunk = _prepare(chunk)

    if len(base) < baseline_min_rows or len(chunk) < chunk_min_rows:
        return pd.DataFrame()

    base, feature_cols = _add_features(base)
    chunk, _ = _add_features(chunk)

    scaler = StandardScaler()
    X_base = scaler.fit_transform(base[feature_cols].values)
    X_chunk = scaler.transform(chunk[feature_cols].values)

    model = IsolationForest(
        n_estimators=300,
        contamination=float(contamination),
        random_state=42,
    )
    model.fit(X_base)

    # Anomaly scores (IMPORTANT: store them back to the dataframe)
    chunk_scores = -model.decision_function(X_chunk)
    base_scores = -model.decision_function(X_base)
    chunk["anomaly_score"] = chunk_scores

    # Adaptive threshold based on baseline distribution
    threshold = compute_adaptive_threshold(base_scores, multiplier=3.0)

    # Night-time weighting
    chunk["adjusted_score"] = chunk["anomaly_score"] * np.where(chunk["is_night"].astype(bool), 1.2, 1.0)

    # Severity based on adjusted score
    chunk["severity"] = assign_severity(chunk["adjusted_score"].values, threshold)

    # Only anomalies above NORMAL
    anomalies = chunk[chunk["severity"] != "NORMAL"].copy()
    if anomalies.empty:
        return pd.DataFrame()

    anomalies["window_start"] = start_dt
    anomalies["window_end"] = end_dt
    anomalies["baseline_start"] = baseline_start
    anomalies["baseline_end"] = start_dt

    return anomalies[
        [
            "id", "event_time",
            "anomaly_score", "adjusted_score", "severity",
            "x", "y", "z",
            "window_start", "window_end", "baseline_start", "baseline_end",
        ]
    ]
