#!/usr/bin/env python3
"""
One-click LSTM training runner.

What it does (default behavior):
1) Clears old LSTM artifacts/state (`engine_state`, `hourly_features`, model files).
2) Rebuilds hourly features from raw phone sensor tables for all participants.
3) Trains and saves one LSTM bundle per participant when enough data exists.

Usage:
  python train_lstm_one_click.py
  python train_lstm_one_click.py --no-reset
  python train_lstm_one_click.py --include-rule-alerts
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path

import pandas as pd

import build_hourly_features_and_alerts as engine_runner
from signature_engine.config import MODEL_DIR
from signature_engine.db import _execute, connect, ensure_tables, fetch_participant_devices, get_time_range_ms
from signature_engine.lstm import train_or_update_lstm


def _delete_model_files() -> int:
    model_dir = Path(MODEL_DIR)
    if not model_dir.exists():
        return 0
    deleted = 0
    for path in model_dir.iterdir():
        if not path.is_file():
            continue
        if path.name == ".gitkeep":
            continue
        if path.suffix == ".pt" or path.name.endswith("_scaler.json"):
            path.unlink(missing_ok=True)
            deleted += 1
    return deleted


def _truncate_training_state(conn) -> None:
    _execute(conn, "TRUNCATE TABLE hourly_features")
    _execute(conn, "TRUNCATE TABLE engine_state")
    conn.commit()


def _latest_complete_hour_for_device(conn, phone_device_id: str):
    min_ms, max_ms = get_time_range_ms(conn, phone_device_id)
    if min_ms is None or max_ms is None:
        return None
    max_dt = pd.to_datetime(max_ms, unit="ms", utc=True).tz_convert(None)
    return max_dt.floor("h") - pd.Timedelta(hours=1)


def run(reset: bool, include_rule_alerts: bool) -> int:
    conn = None
    try:
        conn = connect()
        ensure_tables(conn)

        if reset:
            _truncate_training_state(conn)
            deleted = _delete_model_files()
            print(f"[reset] cleared hourly_features + engine_state; deleted model files={deleted}")

        if not include_rule_alerts:
            # Speed-focused training path: skip rule alert writes while rebuilding hourly features.
            engine_runner.generate_rule_alerts_for_hour = lambda *args, **kwargs: None
            engine_runner.insert_alert = lambda *args, **kwargs: None

        participants = fetch_participant_devices(conn)
        phone_participants = [p for p in participants if p.get("phone_device_id")]
        if not phone_participants:
            print("[error] No phone participants found in participants table.")
            return 2

        print(f"[info] phone participants={len(phone_participants)}")

        rebuilt_hours = 0
        trained = 0
        skipped = 0

        for participant in phone_participants:
            pid = participant["participant_id"]
            phone_device_id = participant["phone_device_id"]

            processed = engine_runner.process_new_hours(conn, pid, phone_device_id)
            rebuilt_hours += int(processed)

            latest_hour = _latest_complete_hour_for_device(conn, phone_device_id)
            if latest_hour is None:
                print(f"  [{pid}] skipped training: no accelerometer range")
                skipped += 1
                continue

            bundle = train_or_update_lstm(conn, pid, latest_hour)
            if bundle is None:
                skipped += 1
            else:
                trained += 1

        model_dir = Path(MODEL_DIR)
        model_count = len(list(model_dir.glob("lstm_ae_*.pt"))) if model_dir.exists() else 0
        scaler_count = len(list(model_dir.glob("lstm_ae_*_scaler.json"))) if model_dir.exists() else 0

        print(
            f"[done] rebuilt_hours={rebuilt_hours} trained_participants={trained} "
            f"skipped_participants={skipped} model_files={model_count} scaler_files={scaler_count}"
        )

        return 0 if trained > 0 else 1
    finally:
        if conn is not None and conn.is_connected():
            conn.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--no-reset",
        action="store_true",
        help="Do not clear hourly_features/engine_state/model files before rebuilding/training.",
    )
    parser.add_argument(
        "--include-rule-alerts",
        action="store_true",
        help="Keep rule alert generation enabled while rebuilding hourly features (slower).",
    )
    args = parser.parse_args()

    exit_code = run(reset=not args.no_reset, include_rule_alerts=args.include_rule_alerts)
    raise SystemExit(exit_code)


if __name__ == "__main__":
    main()

