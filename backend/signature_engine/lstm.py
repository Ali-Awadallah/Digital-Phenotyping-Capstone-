import json
import os
from dataclasses import dataclass
from typing import Dict, Optional, Tuple

import numpy as np
import pandas as pd

from signature_engine.config import (
    ANOMALY_PERCENTILE,
    DataLoader,
    Dataset,
    LSTM_ENGINE_NAME,
    LSTM_MIN_TRAIN_ROWS,
    LSTM_MIN_TRAIN_SEQUENCES,
    MODEL_DIR,
    RETRAIN_EVERY_HOURS,
    SEQ_LEN,
    TORCH_AVAILABLE,
    TRAIN_BATCH_SIZE,
    TRAIN_EPOCHS,
    TRAIN_LOOKBACK_HOURS,
    TRAIN_LR,
    nn,
    torch,
)
from signature_engine.db import _query_df, get_state, json_dumps_safe, set_state

FEATURE_COLS = [
    "acc_mag_mean",
    "acc_mag_std",
    "acc_jerk_mean",
    "acc_mag_p95",
    "acc_inactive_ratio",
    "gyro_mag_mean",
    "gyro_mag_std",
    "gyro_mag_p95",
    "gps_distance_m",
    "gps_stationary_ratio",
    "screen_sessions",
    "screen_on_seconds",
    "screen_avg_session_seconds",
]


def _safe_float(x):
    if x is None:
        return np.nan
    try:
        return float(x)
    except Exception:
        return np.nan


def _robust_fit(x: np.ndarray) -> Dict[str, np.ndarray]:
    med = np.nanmedian(x, axis=0)
    q1 = np.nanpercentile(x, 25, axis=0)
    q3 = np.nanpercentile(x, 75, axis=0)
    iqr = q3 - q1
    iqr[iqr == 0] = 1.0
    return {"median": med.astype(np.float32), "iqr": iqr.astype(np.float32)}


def _robust_transform(x: np.ndarray, scaler: Dict[str, np.ndarray]) -> np.ndarray:
    med = scaler["median"]
    iqr = scaler["iqr"]
    x2 = x.copy().astype(np.float32)
    for j in range(x2.shape[1]):
        col = x2[:, j]
        col[np.isnan(col)] = med[j]
        x2[:, j] = col
    return (x2 - med) / iqr


if TORCH_AVAILABLE:
    class SeqDataset(Dataset):
        def __init__(self, x_seq: np.ndarray):
            self.x = torch.tensor(x_seq, dtype=torch.float32)

        def __len__(self):
            return self.x.shape[0]

        def __getitem__(self, idx):
            return self.x[idx]


    class LSTMAutoEncoder(nn.Module):
        def __init__(self, n_features: int, hidden_size: int = 64, latent_size: int = 32):
            super().__init__()
            self.encoder = nn.LSTM(input_size=n_features, hidden_size=hidden_size, batch_first=True)
            self.to_latent = nn.Linear(hidden_size, latent_size)
            self.from_latent = nn.Linear(latent_size, hidden_size)
            self.decoder = nn.LSTM(input_size=hidden_size, hidden_size=hidden_size, batch_first=True)
            self.out = nn.Linear(hidden_size, n_features)

        def forward(self, x):
            _, (h_n, _) = self.encoder(x)
            z = self.to_latent(h_n[-1])
            h_rep = self.from_latent(z).unsqueeze(1).repeat(1, x.size(1), 1)
            dec_h, _ = self.decoder(h_rep)
            return self.out(dec_h)
else:
    class SeqDataset:
        pass


    class LSTMAutoEncoder:
        pass


@dataclass
class ModelBundle:
    model: LSTMAutoEncoder
    scaler: Dict[str, np.ndarray]
    threshold: float


@dataclass
class LSTMWindowSummary:
    rows: int
    contiguous_sequences: int


def _model_paths(pid: str) -> Tuple[str, str]:
    os.makedirs(MODEL_DIR, exist_ok=True)
    safe_pid = pid.replace("/", "_").replace("\\", "_").replace(":", "_")
    return (
        os.path.join(MODEL_DIR, f"lstm_ae_{safe_pid}.pt"),
        os.path.join(MODEL_DIR, f"lstm_ae_{safe_pid}_scaler.json"),
    )


def _bundle_files_exist(pid: str) -> bool:
    model_path, scaler_path = _model_paths(pid)
    return os.path.exists(model_path) and os.path.exists(scaler_path)


def _save_bundle(pid: str, bundle: ModelBundle):
    model_path, scaler_path = _model_paths(pid)
    torch.save({"state_dict": bundle.model.state_dict(), "threshold": float(bundle.threshold)}, model_path)
    with open(scaler_path, "w", encoding="utf-8") as f:
        json.dump({"median": bundle.scaler["median"].tolist(), "iqr": bundle.scaler["iqr"].tolist(), "feature_cols": FEATURE_COLS}, f)


def _load_bundle(pid: str, n_features: int) -> Optional[ModelBundle]:
    if not TORCH_AVAILABLE:
        return None
    model_path, scaler_path = _model_paths(pid)
    if not (os.path.exists(model_path) and os.path.exists(scaler_path)):
        return None
    try:
        with open(scaler_path, "r", encoding="utf-8") as f:
            sc = json.load(f)
        if sc.get("feature_cols") != FEATURE_COLS:
            return None
        scaler = {"median": np.array(sc["median"], dtype=np.float32), "iqr": np.array(sc["iqr"], dtype=np.float32)}
        ckpt = torch.load(model_path, map_location="cpu")
        model = LSTMAutoEncoder(n_features=n_features)
        model.load_state_dict(ckpt["state_dict"])
        model.eval()
        return ModelBundle(model=model, scaler=scaler, threshold=float(ckpt.get("threshold", 0.0)))
    except Exception:
        return None


def fetch_hourly_feature_frame(conn, pid: str, start_dt: pd.Timestamp, end_dt: pd.Timestamp) -> pd.DataFrame:
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
    return df.sort_values("hour_start")


def _frame_to_matrix(df: pd.DataFrame) -> np.ndarray:
    return np.array([[_safe_float(r.get(c)) for c in FEATURE_COLS] for _, r in df.iterrows()], dtype=np.float32)


def _make_contiguous_sequences(df: pd.DataFrame, x: np.ndarray, seq_len: int) -> np.ndarray:
    if x.shape[0] < seq_len or df.empty:
        return np.zeros((0, seq_len, x.shape[1]), dtype=np.float32)

    hour_starts = pd.to_datetime(df["hour_start"]).reset_index(drop=True)
    sequences = []
    expected_step = pd.Timedelta(hours=1)

    for end_idx in range(seq_len - 1, x.shape[0]):
        start_idx = end_idx - seq_len + 1
        window_hours = hour_starts.iloc[start_idx:end_idx + 1]
        if (window_hours.diff().iloc[1:] == expected_step).all():
            sequences.append(x[start_idx:end_idx + 1, :])

    if not sequences:
        return np.zeros((0, seq_len, x.shape[1]), dtype=np.float32)
    return np.stack(sequences, axis=0).astype(np.float32)


def _split_train_eval_sequences(x_seq: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
    if x_seq.shape[0] < 2:
        return x_seq, np.zeros((0, x_seq.shape[1], x_seq.shape[2]), dtype=np.float32)

    eval_count = max(1, int(round(x_seq.shape[0] * 0.2)))
    eval_count = min(eval_count, max(1, x_seq.shape[0] - 1))
    train_count = x_seq.shape[0] - eval_count
    return x_seq[:train_count], x_seq[train_count:]


def _reconstruction_errors(model: LSTMAutoEncoder, x_seq: np.ndarray) -> list[float]:
    if x_seq.shape[0] == 0:
        return []

    dataset = SeqDataset(x_seq)
    errs = []
    with torch.no_grad():
        for xb in DataLoader(dataset, batch_size=TRAIN_BATCH_SIZE, shuffle=False):
            errs.extend(torch.mean((model(xb) - xb) ** 2, dim=(1, 2)).cpu().numpy().tolist())
    return errs


def summarize_lstm_window(conn, pid: str, now_hour: pd.Timestamp) -> LSTMWindowSummary:
    df = fetch_hourly_feature_frame(conn, pid, now_hour - pd.Timedelta(hours=TRAIN_LOOKBACK_HOURS), now_hour)
    if df.empty:
        return LSTMWindowSummary(rows=0, contiguous_sequences=0)
    x_raw = _frame_to_matrix(df)
    x_seq = _make_contiguous_sequences(df, _robust_transform(x_raw, _robust_fit(x_raw)), SEQ_LEN)
    return LSTMWindowSummary(rows=int(x_raw.shape[0]), contiguous_sequences=int(x_seq.shape[0]))


def train_or_update_lstm(conn, pid: str, now_hour: pd.Timestamp) -> Optional[ModelBundle]:
    if not TORCH_AVAILABLE:
        print(f"  [{pid}] LSTM skipped: torch not available")
        return None

    state = get_state(conn, pid, LSTM_ENGINE_NAME)
    force_retrain = False
    if state and state.get("last_trained_hour_start"):
        last_trained = pd.to_datetime(state["last_trained_hour_start"])
        bundle_files_exist = _bundle_files_exist(pid)
        if not bundle_files_exist:
            force_retrain = True
            print(f"  [{pid}] LSTM recovery: engine_state says trained at {last_trained} but model files are missing; retraining")
        elif (now_hour - last_trained) / pd.Timedelta(hours=1) < RETRAIN_EVERY_HOURS:
            bundle = _load_bundle(pid, n_features=len(FEATURE_COLS))
            if bundle is not None:
                print(f"  [{pid}] LSTM using saved bundle from {last_trained}")
                return bundle
            force_retrain = True
            print(f"  [{pid}] LSTM recovery: model files exist but bundle load failed; retraining")
        else:
            print(f"  [{pid}] LSTM retrain scheduled: last trained at {last_trained}")
    else:
        print(f"  [{pid}] LSTM first training attempt")

    df = fetch_hourly_feature_frame(conn, pid, now_hour - pd.Timedelta(hours=TRAIN_LOOKBACK_HOURS), now_hour)
    if df.empty:
        print(f"  [{pid}] LSTM skipped: no hourly_features in last {TRAIN_LOOKBACK_HOURS}h")
        return None
    x_raw = _frame_to_matrix(df)
    if x_raw.shape[0] < LSTM_MIN_TRAIN_ROWS:
        print(
            f"  [{pid}] LSTM skipped: only {x_raw.shape[0]} hourly rows in last {TRAIN_LOOKBACK_HOURS}h "
            f"(need >= {LSTM_MIN_TRAIN_ROWS})"
        )
        return None
    scaler = _robust_fit(x_raw)
    x_seq = _make_contiguous_sequences(df, _robust_transform(x_raw, scaler), SEQ_LEN)
    if x_seq.shape[0] < LSTM_MIN_TRAIN_SEQUENCES:
        print(
            f"  [{pid}] LSTM skipped: only {x_seq.shape[0]} contiguous sequences with seq_len={SEQ_LEN} "
            f"(need >= {LSTM_MIN_TRAIN_SEQUENCES})"
        )
        return None

    train_seq, eval_seq = _split_train_eval_sequences(x_seq)
    if train_seq.shape[0] < 1:
        print(f"  [{pid}] LSTM skipped: no train sequences after holdout split")
        return None

    dataset = SeqDataset(train_seq)
    print(
        f"  [{pid}] LSTM training: rows={x_raw.shape[0]} contiguous_sequences={x_seq.shape[0]} "
        f"train_sequences={train_seq.shape[0]} eval_sequences={eval_seq.shape[0]} "
        f"lookback={TRAIN_LOOKBACK_HOURS}h epochs={TRAIN_EPOCHS} recovery={force_retrain}"
    )
    loader = DataLoader(dataset, batch_size=TRAIN_BATCH_SIZE, shuffle=True, drop_last=False)
    model = LSTMAutoEncoder(n_features=len(FEATURE_COLS))
    model.train()
    optim = torch.optim.Adam(model.parameters(), lr=TRAIN_LR)
    loss_fn = nn.MSELoss()
    for _ in range(TRAIN_EPOCHS):
        for xb in loader:
            optim.zero_grad()
            loss = loss_fn(model(xb), xb)
            loss.backward()
            optim.step()

    model.eval()
    threshold_source = "holdout" if eval_seq.shape[0] > 0 else "train"
    errs = _reconstruction_errors(model, eval_seq if eval_seq.shape[0] > 0 else train_seq)
    if not errs:
        print(f"  [{pid}] LSTM skipped: no reconstruction errors produced")
        return None

    threshold = float(np.percentile(np.array(errs, dtype=np.float32), ANOMALY_PERCENTILE))
    bundle = ModelBundle(model=model, scaler=scaler, threshold=threshold)
    _save_bundle(pid, bundle)
    set_state(conn, pid, LSTM_ENGINE_NAME, {"last_trained_hour_start": now_hour.to_pydatetime(), "threshold": threshold})
    print(f"  [{pid}] LSTM trained and saved: threshold={threshold:.6f} source={threshold_source}")
    return bundle


def score_hour_with_lstm(conn, pid: str, hour_start: pd.Timestamp, bundle: ModelBundle) -> Optional[dict]:
    if not TORCH_AVAILABLE:
        return None
    df = fetch_hourly_feature_frame(conn, pid, hour_start + pd.Timedelta(hours=1) - pd.Timedelta(hours=SEQ_LEN), hour_start + pd.Timedelta(hours=1))
    if df.empty or df.shape[0] < SEQ_LEN:
        return None
    x_seq = _make_contiguous_sequences(df, _robust_transform(_frame_to_matrix(df), bundle.scaler), SEQ_LEN)
    if x_seq.shape[0] < 1:
        return None

    xb = torch.tensor(x_seq[-1:], dtype=torch.float32)
    with torch.no_grad():
        err = float(torch.mean((bundle.model(xb) - xb) ** 2).cpu().item())
    if err <= bundle.threshold:
        return None

    ratio = err / (bundle.threshold + 1e-8)
    severity = "CRITICAL" if ratio >= 2.5 else "HIGH" if ratio >= 1.8 else "MEDIUM" if ratio >= 1.3 else "LOW"
    return {
        "participant_id": pid,
        "hour_start": hour_start.to_pydatetime(),
        "alert_code": "LSTM_AE1",
        "alert_name": "LSTM autoencoder anomaly (multi-sensor hourly pattern)",
        "severity": severity,
        "score": float(err),
        "baseline_ref": f"train_last_{TRAIN_LOOKBACK_HOURS}h",
        "top_features_json": json_dumps_safe({"reconstruction_mse": err, "threshold": float(bundle.threshold), "ratio": ratio, "seq_len": SEQ_LEN}),
        "explanation": "Hour is part of a multi-hour pattern that deviates from the participant's learned baseline.",
    }
