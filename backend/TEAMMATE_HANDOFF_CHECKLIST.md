# Teammate Handoff Checklist (Backend + DB + Signatures)

Last validated: 2026-03-12 (local Docker setup)

## 1) Prerequisites

- Docker Desktop running.
- Python installed (for synthetic generator / helper scripts).
- Open terminal in `backend/`.

## 2) One-time startup (fresh machine)

1. Build and start all services:
   ```bash
   docker compose up -d --build
   ```
2. Verify containers:
   ```bash
   docker compose ps
   ```
   Expected services: `dp-mysql`, `dp-backend`, `signature-engine-service`.

## 3) Auto schema creation check

- Backend is the schema source of truth. It auto-creates/migrates tables at startup.
- Verify:
  ```bash
  docker exec dp-mysql mysql -uroot -prootpassword -D aware_db -e "SHOW TABLES;"
  ```
- You should see phone, watch, analytics, alert, and auth tables (around 30 tables total).

## 4) Default login users (auto-created)

- `admin` / `capstone`
- `doctor` / `Doctor@12345`

Role scope:

- `admin`: full control, including Users + Settings + Admin Security panel.
- `doctor`: read-only operational access (participants, alerts, zones, devices, dashboard), no Users/Settings/Admin Security actions.

Change these in `.env` for non-demo use:

- `APP_ADMIN_USERNAME`, `APP_ADMIN_PASSWORD`
- `APP_DOCTOR_USERNAME`, `APP_DOCTOR_PASSWORD`

## 5) Generate synthetic data (optional, for testing/demo/training)

```bash
python generate_aware_mysql_v2.py
```

Prompt options:
- Mode: `phone`, `watch`, or `both`.
- Signature focus: `phone`, `watch`, or `both`.
- Live mode: continuously inserts new-hour data until stopped.

Device ID convention:
- Phone IDs: `phone_*`
- Watch IDs: `watch_*`
- Same participant can own both.

## 6) Signature engine behavior

- Runs continuously inside `signature-engine-service`.
- Reads new data from MySQL.
- Produces:
  - `hourly_features` (phone),
  - `wearable_daily_features` (watch),
  - `signature_alerts` (phone rules, watch rules, and LSTM alerts when trained).

No manual restart is needed when new data is inserted.

## 7) LSTM one-click training

Use:
```bash
python train_lstm_one_click.py
```

What it does:
- Resets LSTM state/tables (unless `--no-reset`),
- Rebuilds hourly features from raw phone sensor tables,
- Trains/saves per-participant model files into `backend/models`.

Model artifacts:
- `lstm_ae_<participant>.pt`
- `lstm_ae_<participant>_scaler.json`

## 8) Security quick check

1. At-rest DB encryption:
   ```bash
   docker exec dp-mysql mysql -uroot -prootpassword -D aware_db -e "SHOW VARIABLES LIKE 'default_table_encryption';"
   ```
   Expected: `ON`.
2. App users:
   ```bash
   docker exec dp-mysql mysql -uroot -prootpassword -D aware_db -e "SELECT username,role,status FROM app_users;"
   ```

## 9) Common issues and fixes

- Generator error: `Unknown column 'coordinates' in 'field list'`:
  - Start backend first so migrations run:
    ```bash
    docker compose up -d backend
    ```
  - Then rerun generator.
- No alerts in dashboard:
  - Ensure generator inserted recent data.
  - Ensure signature engine container is running.
  - Check `signature_alerts` table row count.
- Models folder empty:
  - Means LSTM training conditions not met yet, or training not run.
  - Run one-click trainer above.
