# Digital Phenotyping Backend Paper (Full Technical, Implementation-Accurate)

Last validated against repository state: 2026-03-12

Scope:
- Smartphone and smartwatch ingestion backend
- Schema and data lifecycle
- Signature engine internals
- LSTM autoencoder internals
- Dashboard/API behavior
- Security controls

This document is intentionally deep and implementation-driven so it can be used directly for thesis/paper writing and handoff.

---

## 1) System Purpose and High-Level Architecture

The backend is a multi-service pipeline that:
1. Ingests raw phone/watch telemetry through HTTP APIs and event bus handlers.
2. Stores raw telemetry in MySQL tables.
3. Builds engineered behavioral features:
   - hourly phone features
   - daily watch features
4. Runs rule-based signatures on top of those features.
5. Runs a participant-specific LSTM autoencoder anomaly model for phone hourly sequences.
6. Writes alerts to `signature_alerts`.
7. Serves dashboard APIs with session auth, RBAC, and alert acknowledgment workflows.
8. Pushes live updates through WebSocket (`alerts.changed`, `battery.update`).

Primary runtime services:
- `mysql` (MySQL 8.0)
- `backend` (Kotlin + Vert.x)
- `signature_engine` (Python loop: hourly/daily processing + rules + LSTM)

Primary files:
- API + RBAC: `backend/src/main/kotlin/com/awareframework/micro/MainVerticle.kt`
- DB schema + migrations + DB operations: `backend/src/main/kotlin/com/awareframework/micro/MySQLVerticle.kt`
- Signature engine orchestrator: `backend/build_hourly_features_and_alerts.py`
- Rule signatures: `backend/signature_engine/alerts.py`
- LSTM model: `backend/signature_engine/lstm.py`
- Signature DB helpers: `backend/signature_engine/db.py`
- Dashboard client: `backend/src/main/resources/static/dashboard/app.js`
- Reproducible one-click LSTM runner: `backend/train_lstm_one_click.py`

---

## 2) End-to-End Data Flow

### 2.1 Ingestion Path

Phone endpoints:
- `POST /api/accelerometer`
- `POST /api/gyroscope`
- `POST /api/location`
- `POST /api/screen`
- `POST /api/battery`
- `POST /api/notification`
- `POST /api/pedometer`

Watch endpoints:
- `POST /api/wearable/heart-rate`
- `POST /api/wearable/steps`
- `POST /api/wearable/sleep`
- `POST /api/wearable/blood-pressure`
- `POST /api/wearable/weight`
- `POST /api/wearable/oxygen`
- `POST /api/wearable/respiratory`

For each ingestion call:
1. `MainVerticle` parses and normalizes payload.
2. It ensures participant-device mapping via `upsertParticipant` with device_type (`phone` or `watch`).
3. It sends DB insert request over Vert.x event bus to `MySQLVerticle`.
4. `MySQLVerticle` writes row(s) into target table.

### 2.2 Feature and Alert Path

`signature_engine` runs `build_hourly_features_and_alerts.py --loop`:
1. Loads participant mapping (`participant_id`, `phone_device_id`, `watch_device_id`).
2. For each participant:
   - Watch path: `process_new_days(...)`
   - Phone path: `process_new_hours(...)`
3. Feature rows are upserted:
   - `hourly_features`
   - `wearable_daily_features`
4. Rule alerts inserted into `signature_alerts`.
5. LSTM bundle trained/loaded and latest hour scored for `LSTM_AE1`.

### 2.3 Dashboard Path

Dashboard requests:
- `/api/participants`, `/api/alerts`, `/api/signature-alerts`, etc.

Acknowledgment path:
- geofence: `/api/alerts/:alertId/acknowledge`
- signature: `/api/signature-alerts/:id/acknowledge`

On acknowledge, backend publishes `alerts.changed` on event bus; WebSocket service broadcasts to connected UI clients.

---

## 3) Schema Ownership and Automatic Table Creation

### 3.1 Source of truth

Tables are created/migrated at backend startup in `MySQLVerticle`.  
No external bootstrap step is required for schema.

### 3.2 Auto-created table groups

Raw phone telemetry:
- `accelerometer`
- `gyroscope`
- `location`
- `battery_readings`
- `screen_events`
- `notifications`
- `pedometer` (legacy generic insert path)

Raw watch telemetry:
- `wearable_heart_rate`
- `wearable_steps`
- `wearable_sleep`
- `wearable_blood_pressure`
- `wearable_weight`
- `wearable_oxygen`
- `wearable_respiratory`

Participant/geofence:
- `participants`
- `participant_devices`
- `red_zones`
- `geofence_alerts`

Analytics/signatures:
- `hourly_features`
- `wearable_daily_features`
- `watch_day_profiles` (generator support)
- `anomaly_hours` (generator support/training labels)
- `engine_state`
- `signature_alerts`
- `signature_alerts_archive`
- `signature_alerts_legacy`
- `aware_db_geofence_alerts`

Security/auth:
- `app_users`
- `auth_sessions`
- `auth_login_attempts`
- `security_audit_log`

### 3.3 Important schema migrations already encoded

Examples in startup migration logic:
- participant/device ids widened to `VARCHAR(128)` where needed.
- `participants.device_type` ensured.
- `participant_devices` backfilled from existing `participants`.
- `signature_alerts` adds/normalizes:
  - `hour_start_iso`
  - `hour_start_ts`
  - `device_id`
  - `source_type`
- `location.coordinates` ensured as JSON.
- `accelerometer` ensures encrypted mirror columns:
  - `x_enc`, `y_enc`, `z_enc`

---

## 4) Participant and Device Identity Model

### 4.1 Key model

Participant identity is represented by `participant_id`.  
Devices are linked in `participant_devices` with explicit `device_type`:
- `phone`
- `watch`
- `unknown`

### 4.2 Why this matters

This enables:
- same participant having separate phone and watch IDs,
- alert source filtering (`phone` vs `watch`),
- participant-centric dashboard grouping.

### 4.3 Participant query behavior

`getAllParticipants()` builds a normalized participant object:
- `phone_device_id`
- `watch_device_id`
- merged `source_type` (`phone`, `watch`, `both`, `unknown`)
- `devices` array from `participant_devices`

This is what dashboard uses for participant cards and device breakdown.

---

## 5) Phone Hourly Feature Engineering

Implemented in `build_hourly_features_and_alerts.py`.

Given one hour `[hour_start, hour_end)` and a participant's phone device:

### 5.1 Accelerometer features

Raw: `(x, y, z)` samples.

Computed:
- `mag = sqrt(x^2 + y^2 + z^2)`
- `jerk = abs(diff(mag))`
- `accel_n`
- `acc_mag_mean`
- `acc_mag_std`
- `acc_mag_p95`
- `acc_jerk_mean`
- `acc_inactive_ratio = fraction(mag < p10(mag))`

### 5.2 Gyroscope features

Raw: `(x, y, z)` samples.

Computed:
- `gyro_mag = sqrt(x^2 + y^2 + z^2)`
- `gyro_n`
- `gyro_mag_mean`
- `gyro_mag_std`
- `gyro_mag_p95`

### 5.3 GPS features

Uses only samples with acceptable accuracy (`<= GPS_MAX_ACCURACY_M`, default 50m).

Computed:
- `gps_n`
- `gps_points`
- `gps_mean_accuracy`
- pairwise Haversine segment distances
- `gps_distance_m = sum(segment_distances)`
- `gps_stationary_ratio = fraction(segment_distance < 5m)`

### 5.4 Screen features

Raw events normalized to:
- `Screen turned on`
- `Screen turned off`

Computed:
- `screen_event_n`
- `screen_on_events`
- `screen_off_events`
- reconstructed sessions (on->off pairs)
- `screen_sessions`
- `screen_on_seconds`
- `screen_avg_session_seconds`

All features are upserted by `(participant_id, hour_start)`.

---

## 6) Watch Daily Feature Engineering

Implemented in `signature_engine/alerts.py` (`build_wearable_daily_features`).

Day window uses `[day_start, day_end)`.

### 6.1 Step features

- `steps_total = sum(wearable_steps.count over day)`

### 6.2 Sleep features

Sleep intervals can overlap day boundaries. The code clips each episode to day window:
- overlap duration via clipped start/end
- `sleep_minutes = sum(overlap_minutes)`
- `sleep_episode_n = number of overlapping episodes`
- main sleep episode selected by maximum overlap
- from main episode:
  - `sleep_start_hour`
  - `sleep_end_hour`
  - `sleep_midpoint_hour`

### 6.3 Heart-rate and HRV proxy features

From `wearable_heart_rate`:
- `day_hr_mean`
- `resting_hr_p10` (10th percentile BPM)

Night subset: hours `[0..5]`:
- `night_hr_mean`
- `rmssd_night`
- `sdnn_night`

HRV approximation method:
- convert BPM to RR interval: `RR(ms) = 60000 / BPM`
- `RMSSD = sqrt(mean(diff(RR)^2))`
- `SDNN = std(RR)`

This is a PPG-based approximation, not ECG-grade interbeat interval HRV.

---

## 7) Rule Signatures: Full Logic, Meaning, and Examples

All rule signatures are in `signature_engine/alerts.py`.

Notation:
- `r_x` = robust ratio = current / median(baseline)
- robust baseline window (phone): last `BASELINE_HOURS` (default 24h)
- robust z: `z = 0.6745 * (x - median) / MAD`
- wearable baseline window: last `WEARABLE_BASELINE_DAYS` (default 14d)

## 7.1 Phone Signatures

### DS1 - Low mobility + low activity indicator

Intent:
- detect a strongly reduced movement/mobility hour.

Trigger conditions:
1. GPS low mobility:
   - `gps_n >= 3`
   - `gps_distance_m < PHONE_GPS_LOW_DISTANCE_M` (default 20m)
   - `gps_stationary_ratio >= PHONE_GPS_STATIONARY_MIN` (default 0.9)
2. At least one low-motion signal:
   - accel path: `accel_n >= 10` and `r_acc_std < PHONE_LOW_MOTION_RATIO_MAX` (default 0.4)
   - gyro path: `gyro_n >= 10` and `r_gyro_std < PHONE_LOW_MOTION_RATIO_MAX`

Score:
- `1.0 + 0.5 * low_motion_signals`

Severity:
- LOW if score < 2
- MEDIUM otherwise

Example:
- `gps_distance_m = 8m`, `gps_stationary_ratio = 0.95`
- `r_acc_std = 0.28`, `r_gyro_std = 0.35`
- low_motion_signals = 2 => score = 2.0 => MEDIUM DS1

### DS2 - Sedentary engagement indicator

Intent:
- low movement while phone engagement rises.

Score components:
- `+1.0` if low_motion_signals >= 1 (same low motion criteria as DS1)
- `+1.0` if `r_screen_on >= PHONE_DS2_SCREEN_ON_RATIO` (default 2.0)
- `+0.5` if `r_screen_sessions >= PHONE_DS2_SCREEN_SESSION_RATIO` (default 1.8)

Trigger:
- score >= 2.0

Severity:
- MEDIUM if score < 2.5
- HIGH if score >= 2.5

Example:
- low motion true (+1), screen_on ratio=2.4 (+1), sessions ratio=1.2 (+0)
- score 2.0 => DS2 MEDIUM

### AS2 - Location instability indicator

Intent:
- detect unusual context switching and mobility dispersion.

Core metric:
- location entropy computed from 0.001-degree binned lat/lon cells.

Score components:
- `+1.0` if `z_entropy >= PHONE_ENTROPY_Z_ALERT` (default 2.0)
- `+0.5` if `r_gps_distance >= PHONE_GPS_RATIO_ALERT` (default 2.5)
- `+0.5` if `gps_stationary_ratio <= 0.3`

Trigger:
- score >= 1.5

Severity:
- HIGH if score >= 2.0
- MEDIUM otherwise

Example:
- entropy z=2.4 (+1), gps ratio=3.1 (+0.5), stationary ratio=0.22 (+0.5)
- score=2.0 => AS2 HIGH

### AS1 - Night restlessness / sleep disruption indicator

Intent:
- capture restless high-engagement nights.

Night hours:
- hour in {23,0,1,2,3,4,5}

Score components:
- `+1.0` if night and `r_screen_on >= PHONE_NIGHT_SCREEN_RATIO_ALERT` (default 3.0)
- `+1.0` if night and `r_acc_std >= PHONE_HIGH_MOTION_RATIO_ALERT` (default 2.0)

Trigger:
- score > 0

Severity mapping:
- CRITICAL if score >= 2.0
- HIGH if score >= 1.5
- MEDIUM otherwise

Example:
- at 02:00, screen_on ratio 3.8 and acc_std ratio 2.4
- score=2 => AS1 CRITICAL

### SU1 - High mobility entropy + activity peaks indicator

Intent:
- detect highly dispersed mobility plus elevated motion activity.

Score components:
- `+1.0` if entropy z >= 2.0
- `+0.5` if accel std ratio >= 2.0
- `+0.5` if gyro std ratio >= 2.0

Trigger:
- score >= 1.5

Severity:
- HIGH if score >= 2.0
- MEDIUM otherwise

Example:
- entropy z=2.3 (+1), acc ratio=1.9 (+0), gyro ratio=2.2 (+0.5)
- score=1.5 => SU1 MEDIUM

### SU2 - High-risk location exposure indicator

Intent:
- geofence-like relapse risk indicator from configured red zones.

Trigger:
- any location sample in hour falls within any matching red-zone radius
  (`participant-specific` or global `participant_id IS NULL` zones).

Severity:
- fixed HIGH

Score:
- fixed 1.0

Details:
- stores nearest zone hit details in `top_features_json`:
  - `zone_id`, `zone_name`, `zone_type`, `distance_m`

Example:
- participant enters a zone radius 220m with nearest point at 45m => SU2 fires.

### SU3 - Interaction spike indicator

Intent:
- detect abrupt phone usage spikes relative to personal baseline.

Score components:
- `+1.0` if `r_screen_sessions >= PHONE_SU3_SCREEN_SESSION_RATIO` (default 2.5)
- `+1.0` if `r_screen_on >= PHONE_SU3_SCREEN_ON_RATIO` (default 2.5)

Trigger:
- score >= 1.5

Severity:
- HIGH if score >= 2.0
- MEDIUM otherwise

Example:
- sessions ratio=2.8 (+1), screen_on ratio=2.6 (+1)
- score=2 => SU3 HIGH

### PS1 - Pre-relapse anomaly spike indicator

Intent:
- meta-signature on alert rate acceleration.

Requirements:
1. current hour already triggered at least one phone code (`triggered_codes` not empty),
2. recent non-PS1 alerts in last 7d >= `PHONE_PS1_RECENT_ALERT_MIN` (default 12),
3. recent >= `max(6, previous_7d * PHONE_PS1_GROWTH_RATIO)` (default growth ratio 1.5).

Severity:
- HIGH if recent >= `max(15, previous_7d * PHONE_PS1_HIGH_GROWTH_RATIO)` (default 2.0)
- MEDIUM otherwise

Score:
- recent alert count over last 7d

Example:
- previous 7d = 8, recent 7d = 17, current hour has DS2+SU3
- threshold = max(6,12) = 12, passes
- high threshold = max(15,16)=16, recent 17 => PS1 HIGH

### BP1 - Activity variability + sleep timing irregularity indicator

Intent:
- bipolar-spectrum style variability pattern proxy.

Evaluation time:
- runs only when `hour_start.hour == 12` (daily noon check).

Sleep proxy extraction:
- from screen event inactivity gaps in window `[day_start - 6h, day_start + 12h]`
- valid gap duration between 2h and 14h
- choose longest gap
- derive:
  - `duration_hours`
  - `midpoint_hour`

Variability ratios:
1. Sleep midpoint variability ratio:
   - `std(recent 7d midpoint_hour) / std(baseline 14d midpoint_hour)`
   - requires recent >= 5 samples, baseline >= 7
2. Movement variability ratio:
   - compares recent vs baseline std of `acc_mag_std` and `gyro_mag_std`
   - recent windows: last 72h
   - baseline windows: prior 14d excluding recent 72h
   - uses max(acc_ratio, gyro_ratio) where available

Threshold:
- `PHONE_BP1_VARIABILITY_RATIO` (default 1.7)

Score:
- +1 if movement_var_ratio >= 1.7
- +1 if sleep_cv_ratio >= 1.7

Trigger:
- score >= 2.0

Severity:
- fixed HIGH

Example:
- movement_var_ratio=2.1 (+1), sleep_cv_ratio=1.9 (+1) => BP1 HIGH

## 7.2 Watch Signatures

### WA1 - Physiological arousal proxy

Intent:
- elevated night autonomic arousal pattern.

Components:
- night HR high: `z_night_hr >= WEARABLE_Z_ALERT` (default 2.0)
- HRV down: `z_rmssd <= -WEARABLE_Z_ALERT`

Score:
- add capped contributions:
  - `min(z_night_hr, 5.0)` when positive trigger
  - `min(abs(z_rmssd), 5.0)` when negative HRV trigger

Trigger:
- score > 0 (any component)

Severity:
- MEDIUM if score < 5
- HIGH if score >= 5

Example:
- z_night_hr = 3.2 (+3.2), z_rmssd = -2.4 (+2.4), total 5.6 => WA1 HIGH

### WA2 - Night restlessness / sleep fragmentation proxy

Intent:
- detect abnormal sleep amount + fragmentation + timing.

Components:
- sleep reduction: `z_sleep <= -2.0`
- fragmentation: `z_sleep_episode_n >= 2.0`
- midpoint shift: `abs(z_sleep_mid) >= 2.0`

Each component contributes `min(abs(z), 5.0)`.

Trigger:
- at least 2 components, OR
- single very strong component (`max(component) >= 4.0`)

Severity:
- MEDIUM if score < 5
- HIGH if score >= 5

Example:
- z_sleep=-3.1, z_episode=2.5, z_mid=1.2
- components {3.1,2.5}, score 5.6 => WA2 HIGH

### WD1 - Reduced activity for two consecutive days

Intent:
- sustained low step activity.

Conditions:
- today `z_steps <= -2.0`
- previous day `z_steps <= -2.0` (previous day z computed against its own baseline window)

Score:
- `abs(today_z) + abs(prev_day_z)`

Severity:
- HIGH if min(today_z, prev_z) <= -3
- MEDIUM otherwise

Example:
- today z=-2.4, prev z=-2.2 => score 4.6 => WD1 MEDIUM

### WD2 - Sleep disruption + elevated night HR trend

Intent:
- depressive trend proxy coupling sleep and physiology.

Conditions:
- `z_sleep <= -2.0`
- `z_night_hr >= 2.0`

Score:
- `abs(z_sleep) + z_night_hr`

Severity:
- HIGH if `abs(z_sleep) >= 3` OR `z_night_hr >= 3`
- MEDIUM otherwise

Example:
- z_sleep=-3.2, z_night_hr=2.6 => WD2 HIGH

### WB1 - Variability increase in activity/sleep timing

Intent:
- week-level instability increase.

Ratios:
- `steps_var_ratio = std(recent 7d steps) / std(baseline steps)`
- `sleep_var_ratio = std(recent 7d sleep_start_hour) / std(baseline sleep_start_hour)`

Trigger:
- either ratio >= `WEARABLE_VARIABILITY_RATIO` (default 1.7)

Severity:
- MEDIUM

Score:
- `(steps_var_ratio or 0) + (sleep_var_ratio or 0)`

Example:
- steps ratio 1.9, sleep ratio 1.2 => WB1 MEDIUM

### WB2 - Elevated activation triad

Intent:
- activated state proxy from mixed streams.

Conditions:
- `z_sleep <= -2.0`
- `z_steps >= 2.0`
- `z_night_hr >= 2.0`

Severity:
- HIGH

Score:
- `abs(z_sleep) + z_steps + z_night_hr`

Example:
- z_sleep=-2.3, z_steps=2.8, z_night_hr=2.1 => score 7.2 => WB2 HIGH

### WSU3 - Multi-stream wearable anomaly rate

Intent:
- triage meta-indicator for same-day multi-stream deviation.

Streams tracked by flags:
- physiology
- sleep
- steps
- sleep_timing
- variability

Trigger:
- `stream_count >= 2`

Severity:
- LOW for 2 streams
- MEDIUM for 3 streams
- HIGH for 4+ streams

Score:
- stream_count

Example:
- day has sleep + physiology + variability anomalies => stream_count=3 => WSU3 MEDIUM

### WSU2 status

Not implemented by design in this codebase.

Reason:
- requires TAC/EDA-like streams not present in current schema/sensors.

---

## 8) LSTM Autoencoder: Full Technical Description

Implemented in `signature_engine/lstm.py`.

## 8.1 Goal

Learn participant-specific "normal" hourly phone behavior from multivariate sequences, then flag unusual sequence-level reconstruction errors.

It complements rules:
- rules catch interpretable known patterns,
- LSTM catches unknown multivariate pattern shifts.

## 8.2 Input feature vector (13 dimensions)

`FEATURE_COLS`:
1. `acc_mag_mean`
2. `acc_mag_std`
3. `acc_jerk_mean`
4. `acc_mag_p95`
5. `acc_inactive_ratio`
6. `gyro_mag_mean`
7. `gyro_mag_std`
8. `gyro_mag_p95`
9. `gps_distance_m`
10. `gps_stationary_ratio`
11. `screen_sessions`
12. `screen_on_seconds`
13. `screen_avg_session_seconds`

Rows come from `hourly_features`.

## 8.3 Scaling and missing value handling

Robust scaler per participant over train window:
- `median_j = median(feature_j)`
- `iqr_j = q3_j - q1_j` (if 0 then set to 1)
- missing values in each feature replaced by that feature's median
- transformed value:
  - `x'_j = (x_j - median_j) / iqr_j`

Rationale:
- stable under heavy-tailed behavioral data and outliers.

## 8.4 Sequence construction and continuity constraint

`SEQ_LEN` default: 24 hours.

Important:
- sequences are only built when all consecutive rows are exactly 1 hour apart.
- this avoids learning from broken gaps as if they were continuous behavior.

Given ordered hours `t`:
- window `[t-23, ..., t]` is valid iff each adjacent diff == 1h.

## 8.5 Model architecture

Class: `LSTMAutoEncoder`

Parameters:
- input features: 13
- encoder hidden size: 64
- latent size: 32

Forward path:
1. Encoder LSTM over input sequence.
2. Take final hidden state `h_T`.
3. Project to latent: `z = Linear(h_T)`.
4. Expand back: `h_rep = Linear(z)` and repeat across sequence length.
5. Decoder LSTM consumes repeated hidden vectors.
6. Output linear layer maps decoder hidden back to 13 features each step.

This is sequence-to-sequence reconstruction.

## 8.6 Train/eval split and thresholding

Contiguous sequences split:
- ~80% train
- ~20% holdout eval (at least 1 sequence when possible)

Loss:
- MSE reconstruction loss.

Threshold:
- reconstruction errors computed on holdout sequences if available,
- else fallback to train errors.
- threshold = percentile `ANOMALY_PERCENTILE` (default 95th).

Meaning:
- by default, top 5% highest reconstruction error windows in reference data define anomaly cut.

## 8.7 Training requirements and skip reasons

Training can be skipped if data is insufficient.

Checks:
- `LSTM_MIN_TRAIN_ROWS` default `max(SEQ_LEN + 12, 36)` -> typically 36
- `LSTM_MIN_TRAIN_SEQUENCES` default 6 contiguous sequences

Common skip causes:
- sparse data or missing hours,
- recent ingestion with insufficient completed hours.

## 8.8 Saved artifacts and recovery

Per participant files:
- `models/lstm_ae_<participant>.pt`
- `models/lstm_ae_<participant>_scaler.json`

Checkpoint includes:
- model weights
- threshold

Scaler JSON includes:
- `median`
- `iqr`
- `feature_cols` guard list

Recovery logic:
- if `engine_state` says trained but files missing/corrupt, retrain.
- if files exist but feature list mismatch, reject load and retrain.

## 8.9 Inference and alert emission (`LSTM_AE1`)

For scored hour:
1. fetch latest `SEQ_LEN` rows ending at current hour.
2. build contiguous sequence.
3. transform with saved scaler.
4. compute reconstruction MSE.
5. if `err > threshold`, emit alert.

Alert:
- `alert_code = LSTM_AE1`
- score = reconstruction MSE
- `top_features_json` includes:
  - `reconstruction_mse`
  - `threshold`
  - `ratio = err / threshold`
  - `seq_len`

Severity by ratio:
- CRITICAL: ratio >= 2.5
- HIGH: ratio >= 1.8
- MEDIUM: ratio >= 1.3
- LOW: ratio > 1.0 and below 1.3

## 8.10 Leakage prevention detail

In `process_new_hours`:
- when processing latest hour `h`, model train/load is done before inserting/scoring that hour's features.
- this avoids contaminating model with the exact hour being tested.

Also:
- during backlog catch-up mode, LSTM alerts are deferred and only rule alerts emitted.
- once caught up, model is retrained and used for real-time latest-hour scoring.

---

## 9) Engine State and Scheduling Semantics

State table: `engine_state`
- keyed by `(participant_id, engine_name)`
- tracks:
  - `last_processed_hour_start`
  - `last_processed_day_start`
  - `last_trained_hour_start`
  - `threshold`

Engines:
- `signature_engine_v1` (phone hourly path)
- `wearable_signature_engine_v1` (watch daily path)
- `lstm_ae_v1` (model state)

Loop timing:
- controlled by `LOOP_INTERVAL_SEC` (default 300s).

The engine is incremental:
- processes only completed hour/day windows after checkpoints.

---

## 10) Alert Storage Model and Semantics

Primary alert table: `signature_alerts`

Important fields:
- `participant_id`
- `device_id`
- `source_type` (`phone` / `watch` / `both` fallback)
- `hour_start`, `hour_start_iso`, `hour_start_ts`
- `alert_code`, `alert_name`
- `severity`, `score`
- `baseline_ref`
- `top_features_json`
- `explanation`
- `status`
- `created_at`
- ack metadata:
  - `acknowledged_at`
  - `acknowledged_by`

De-duplication:
- unique key on `(participant_id, hour_start, alert_code)`.

Acknowledge operation:
- sets `acknowledged_at`, `acknowledged_by`, `status='acknowledged'`.
- writes security audit event.
- emits `alerts.changed` event for real-time UI refresh.

---

## 11) Dashboard Logic and Filtering Behavior

Dashboard frontend file: `static/dashboard/app.js`.

Key behavior:
- groups alerts by participant for display.
- supports status filter:
  - active
  - acknowledged
  - all
- supports source filter:
  - phone
  - watch
  - all
- supports participant filter:
  - exact participant selection
- supports limit filter (including large values).

Alert source inference:
- prefers `source_type` from row,
- if missing, falls back:
  - geofence => phone
  - alert_code starts with `W` => watch
  - else phone

Device identity in participants view:
- shows `phone_device_id`, `watch_device_id`, and merged source badge (`phone/watch/both`).

Role-aware UI:
- `users` and `settings` nav hidden for non-admin.
- admin security widgets hidden for non-admin.
- read-only roles cannot perform write actions (ack, user edit, zone edit).

---

## 12) RBAC and Authentication Model

Auth store tables:
- `app_users`
- `auth_sessions`
- `auth_login_attempts`
- `security_audit_log`

Roles:
- `admin`
- `analyst`
- `viewer`
- `doctor`
- `ingest`

Session auth:
- `POST /api/auth/login`
- `Authorization: Bearer <token>` for protected routes

API-key auth:
- admin key and ingest key supported by headers/query/body fallback.

Role routing model in `MainVerticle.requiredRolesForPath(...)`:
- `/api/users*` -> admin only
- `/api/admin*` -> admin only
- `/api/participants*`:
  - GET: admin, analyst, viewer, doctor
  - write: admin, analyst
- other admin API prefixes (`/participants`, `/zones`, `/alerts`, `/signature-alerts`, `/users`, `/admin`):
  - GET: admin, analyst, viewer, doctor
  - write: admin, analyst
- ingestion endpoints:
  - ingest/admin key or valid ingest/admin session role

Doctor behavior (current):
- can view participants, zones, alerts, signature alerts, device data.
- cannot access settings/users/admin-security operations.

---

## 13) Security Controls (Current)

## 13.1 At-rest DB encryption (MySQL)

`docker-compose.yml` enables:
- `default_table_encryption=ON`
- `innodb_redo_log_encrypt=ON`
- `innodb_undo_log_encrypt=ON`
- `binlog_encryption=ON`
- keyring plugin:
  - `--early-plugin-load=keyring_file.so`
  - `--keyring_file_data=/var/lib/mysql-keyring/keyring`

Implication:
- InnoDB table pages and logs are encrypted on disk.

## 13.2 App-level field encryption (AES-256-GCM)

`SensitiveDataCipher`:
- algorithm: `AES/GCM/NoPadding`
- nonce: 12 bytes random
- tag: 128 bits
- key source: `DATA_ENCRYPTION_KEY_B64` (must decode to 32 bytes)
- ciphertext prefix: `enc:v1:`

Currently used in code for:
- `accelerometer.x_enc`, `y_enc`, `z_enc`
- `notifications.title`, `notifications.content`

Important transparency note:
- plain `accelerometer.x/y/z` are still stored for analytics compatibility.
- encrypted mirrors are additional columns, not replacements.

## 13.3 Password and session security

`AuthSecurity`:
- password hash: PBKDF2-HMAC-SHA256
- iterations: 210,000
- salt: 16 bytes random
- hash length: 256 bits

Session tokens:
- generated random 32 bytes (URL-safe base64)
- only SHA-256 token hash stored in DB

Lockout/rate limiting:
- `auth_login_attempts` with configurable window/max attempts/lockout.

## 13.4 Secret loading

`SecretResolver` load order:
1. `<NAME>_FILE` path content
2. `<NAME>` env var
3. code fallback

This allows:
- direct env in local dev
- file-backed secrets in secured deployments.

---

## 14) API Surface Summary (Operational)

Auth:
- `POST /api/auth/login`
- `GET /api/auth/me`
- `POST /api/auth/logout`

Users/admin:
- `GET /api/users`
- `POST /api/users`
- `GET /api/admin/security-status`
- `GET /api/admin/sessions`
- `POST /api/admin/sessions/:id/revoke`
- `GET /api/admin/audit`
- `GET /api/admin/login-lockouts`

Participants/zones:
- `GET /api/participants`
- `GET /api/participants/:deviceId`
- `POST /api/participants`
- `PUT /api/participants/:participantId`
- `GET /api/zones`
- `POST /api/zones`
- `DELETE /api/zones/:zoneId`

Alerts:
- `GET /api/alerts`
- `POST /api/alerts/:alertId/acknowledge`
- `GET /api/signature-alerts`
- `POST /api/signature-alerts/:id/acknowledge`

Phone ingest:
- `POST /api/accelerometer`
- `POST /api/gyroscope`
- `POST /api/location`
- `POST /api/screen`
- `POST /api/battery`
- `POST /api/notification`
- `POST /api/pedometer`

Watch ingest:
- `POST /api/wearable/heart-rate`
- `POST /api/wearable/steps`
- `POST /api/wearable/sleep`
- `POST /api/wearable/blood-pressure`
- `POST /api/wearable/weight`
- `POST /api/wearable/oxygen`
- `POST /api/wearable/respiratory`

---

## 15) Real-Time and Event Bus Mechanics

WebSocket service (`WebsocketVerticle`) subscribes to:
- `battery.update`
- `alerts.changed`

Broadcast payloads:
- `type = battery_update` with battery object
- `type = alert_update` with alert action metadata

Result:
- dashboard can update without manual full-page refresh when events are emitted.

---

## 16) Synthetic Data and Training Support Tooling

Generator:
- `generate_aware_mysql_v2.py`
- modes: phone/watch/both
- live mode (continuous) and historical bulk mode
- signature focus bias: phone/watch/both
- maintains participant-device mappings
- supports anomaly labels in `anomaly_hours`

One-click LSTM:
- `train_lstm_one_click.py`
- default behavior:
  1. clear `hourly_features`, `engine_state`, existing model artifacts
  2. rebuild hourly features from raw phone data
  3. train per-participant LSTM bundles

This script is ideal for deterministic "fresh model rebuild" demos.

---

## 17) Known Limitations and Design Tradeoffs

1. Field encryption partial by design:
- only selected sensitive fields are app-encrypted.
- accelerometer keeps plaintext and encrypted mirror columns simultaneously.

2. Watch signal coverage:
- WSU2 intentionally unavailable due missing TAC/EDA schema streams.

3. LSTM dependence on contiguous data:
- sparse hour continuity reduces trainable sequences.

4. Rule signatures are high-sensitivity:
- useful for detection coverage, but require downstream triage context.

5. `getSignatureAlerts(limit)` upper bound:
- enforced safe cap max 10,000 per query.

---

## 18) Practical Validation Checklist for Teammate Handoff

Use this exact sequence after clone:

1. Start stack:
- `docker compose up -d --build`

2. Confirm tables auto-created:
- open MySQL and verify key tables:
  - `participants`, `participant_devices`, `signature_alerts`, `hourly_features`,
  - `wearable_daily_features`, `app_users`, `auth_sessions`.

3. Generate data:
- run generator and choose `both`.

4. Run engine loop:
- verify `signature_engine` container logs processing hours/days.

5. Check model artifacts:
- `backend/models/lstm_ae_*.pt`
- `backend/models/lstm_ae_*_scaler.json`

6. Dashboard verification:
- login as admin and doctor
- confirm doctor sees participants + alerts + zones but no users/settings.
- apply alert filters:
  - source `phone` only
  - source `watch` only
  - participant-specific

7. Acknowledge flow:
- acknowledge one signature alert and confirm:
  - `acknowledged_at` set in DB
  - row status updated
  - dashboard reflects change.

---

## 19) Signature Quick Reference Table

Phone:
- DS1: low mobility + low movement
- DS2: low movement + high screen engagement
- AS1: night restlessness
- AS2: location instability
- SU1: high entropy + movement peaks
- SU2: red-zone exposure
- SU3: interaction spike
- PS1: recent-week anomaly growth spike
- BP1: movement variability + sleep timing variability

Watch:
- WA1: night HR up / HRV down
- WA2: sleep reduction/fragmentation/timing shift
- WD1: two-day low steps
- WD2: sleep down + night HR up
- WB1: 7-day variability increase
- WB2: sleep down + steps up + night HR up
- WSU3: multi-stream same-day anomaly count

Model:
- LSTM_AE1: participant-specific sequence anomaly

Not implemented:
- WSU2 (missing TAC/EDA-like stream data)

---

## 20) Paper-Ready Wording Blocks (You can copy directly)

### 20.1 Method paragraph for signatures

"The system computes participant-relative digital behavioral signatures by comparing current engineered features against robust personal baselines (median/MAD and median-ratio). Smartphone signatures are evaluated hourly from movement, mobility, and interaction features, while smartwatch signatures are evaluated daily from sleep, activity, and physiological recovery proxies. Each signature emits severity-scored alerts with structured feature evidence and baseline references."

### 20.2 Method paragraph for LSTM

"In parallel with rule signatures, we deploy a participant-specific LSTM autoencoder that learns contiguous hourly multivariate behavior patterns under robust median-IQR normalization. The model is trained on rolling historical windows and flags anomalous sequences when reconstruction error exceeds a participant-specific percentile threshold. This complements interpretable rule alerts by detecting previously unseen multivariate deviations."

### 20.3 Security paragraph

"Data protection combines storage-layer encryption and application-level controls. MySQL is configured with InnoDB table/log encryption and binary-log encryption, while selected high-sensitivity fields are encrypted with AES-256-GCM at write time. Authentication uses PBKDF2-hashed credentials, token-hash session storage, login lockout policies, and security audit logging for privileged actions."

---

## 21) Final Notes for Publication Accuracy

1. Keep distinction explicit between:
- proxy indicators
- clinical diagnosis

2. Report all threshold values as "current implementation defaults" and mention env configurability.

3. For LSTM results, report:
- trainable participant count
- skipped participant count and reasons
- threshold distribution
- alert rate before and after threshold tuning.

4. Include limitation statement:
- no TAC/EDA means WSU2 is intentionally excluded.

5. If reviewers ask "why both rule-based and learned model":
- emphasize interpretability + generalization complementarity.

---

## Appendix A) Complete Backend Startup Table-Creation Inventory

The backend startup (`MySQLVerticle.start`) invokes these table creation calls:

Participants and geofence:
- `createParticipantsTable()`
- `createParticipantDevicesTable()`
- `createRedZonesTable()`
- `createGeofenceAlertsTable()`

Signature/legacy alert storage:
- `createSignatureAlertsTable()`
- `createSignatureAlertsArchiveTable()`
- `createSignatureAlertsLegacyTable()`
- `createAwareDbGeofenceAlertsTable()`

Phone sensors:
- `createAccelerometerTable()`
- `createGyroscopeTable()`
- `createLocationTable()`
- `createBatteryReadingsTable()`
- `createScreenEventsTable()`
- `createNotificationsTable()`

Watch sensors:
- `createWearableHeartRateTable()`
- `createWearableStepsTable()`
- `createWearableSleepTable()`
- `createWearableBloodPressureTable()`
- `createWearableWeightTable()`
- `createWearableOxygenTable()`
- `createWearableRespiratoryTable()`

Analytics:
- `createAnomalyHoursTable()`
- `createWatchDayProfilesTable()`
- `createEngineStateTable()`
- `createHourlyFeaturesTable()`
- `createWearableDailyFeaturesTable()`

Security/auth:
- `createSecurityAuditLogTable()`
- `createAuthUsersTable()`
- `createAuthSessionsTable()`
- `createAuthLoginAttemptsTable()`

After `createAuthUsersTable()`, backend calls `ensureBootstrapUsers()` to guarantee admin and doctor accounts exist.

---

## Appendix B) Detailed LSTM Training Timeline Example

Assume defaults:
- `SEQ_LEN = 24`
- `TRAIN_LOOKBACK_HOURS = 168` (7 days)
- `RETRAIN_EVERY_HOURS = 24`
- `ANOMALY_PERCENTILE = 95`

Timeline:
1. Engine loop reaches participant P and latest complete hour H.
2. If first run:
   - fetch rows in `[H-168h, H)`
   - if rows >= 36 and contiguous sequences >= 6, train model.
3. During backlog (many old hours to process):
   - process each hour rule alerts first.
   - defer LSTM alerting until caught up.
4. Once caught up:
   - train/update model around current latest hour.
   - score hour `H` with latest sequence ending at `H`.
5. If `err > threshold`:
   - emit `LSTM_AE1`.
6. Save checkpoint and scaler.
7. Next loops within 24h:
   - load existing bundle without retraining unless recovery needed.

---

## Appendix C) Exact Severity Mapping Summary

Rule signatures:
- each code has custom logic shown in Section 7.

LSTM_AE1 severity by ratio `err/threshold`:
- LOW: >1.0 and <1.3
- MEDIUM: >=1.3 and <1.8
- HIGH: >=1.8 and <2.5
- CRITICAL: >=2.5

WSU3 by stream count:
- LOW: 2 streams
- MEDIUM: 3 streams
- HIGH: 4+ streams

---

## Appendix D) Full Scoring Matrix (Synced With Publication Draft)

This appendix mirrors the scoring chapter in `PAPER_PUBLICATION_DRAFT.md` so both reports stay aligned.

### D.1 Common Baseline and Comparison Functions

Phone baseline window:
- last `BASELINE_HOURS` (default 24h)

Watch baseline window:
- last `WEARABLE_BASELINE_DAYS` (default 14d)

Robust ratio:
- `ratio = current / median(baseline)`

Robust z-score:
- `z = 0.6745 * (x - median) / MAD`

Location entropy:
- bin GPS coordinates to 0.001-degree cells
- `H = -Σ p_i * log(p_i)`
- compare current `H` to baseline entropy history with robust z

### D.2 Smartphone Signatures: Exact Trigger, Score, Severity

#### DS1
Trigger:
- `gps_n >= 3`
- `gps_distance_m < PHONE_GPS_LOW_DISTANCE_M` (20.0)
- `gps_stationary_ratio >= PHONE_GPS_STATIONARY_MIN` (0.9)
- and at least one low-motion signal:
  - `accel_n >= 10` and `r_acc_std < PHONE_LOW_MOTION_RATIO_MAX` (0.4)
  - or `gyro_n >= 10` and `r_gyro_std < PHONE_LOW_MOTION_RATIO_MAX`

Score:
- `1.0 + 0.5 * low_motion_signals`

Severity:
- LOW if score < 2
- MEDIUM if score >= 2

#### DS2
Score components:
- +1.0 if low-motion signal exists
- +1.0 if `r_screen_on >= PHONE_DS2_SCREEN_ON_RATIO` (2.0)
- +0.5 if `r_screen_sessions >= PHONE_DS2_SCREEN_SESSION_RATIO` (1.8)

Trigger:
- score >= 2.0

Severity:
- MEDIUM if score < 2.5
- HIGH if score >= 2.5

#### AS2
Score components:
- +1.0 if `z_entropy >= PHONE_ENTROPY_Z_ALERT` (2.0)
- +0.5 if `r_gps >= PHONE_GPS_RATIO_ALERT` (2.5)
- +0.5 if `gps_stationary_ratio <= 0.3`

Trigger:
- score >= 1.5

Severity:
- MEDIUM if score < 2.0
- HIGH if score >= 2.0

#### AS1
Night hours:
- `{23, 0, 1, 2, 3, 4, 5}`

Score components:
- +1.0 if night and `r_screen_on >= PHONE_NIGHT_SCREEN_RATIO_ALERT` (3.0)
- +1.0 if night and `r_acc_std >= PHONE_HIGH_MOTION_RATIO_ALERT` (2.0)

Trigger:
- score > 0

Severity:
- MEDIUM if 0 < score < 1.5
- HIGH if score >= 1.5
- CRITICAL if score >= 2.0

#### SU1
Score components:
- +1.0 if `z_entropy >= 2.0`
- +0.5 if `r_acc_std >= 2.0`
- +0.5 if `r_gyro_std >= 2.0`

Trigger:
- score >= 1.5

Severity:
- MEDIUM if score < 2.0
- HIGH if score >= 2.0

#### SU2
Trigger:
- any location point inside any matching red zone radius (participant-specific or global zone)

Score:
- 1.0 (fixed)

Severity:
- HIGH (fixed)

#### SU3
Score components:
- +1.0 if `r_screen_sessions >= PHONE_SU3_SCREEN_SESSION_RATIO` (2.5)
- +1.0 if `r_screen_on >= PHONE_SU3_SCREEN_ON_RATIO` (2.5)

Trigger:
- score >= 1.5

Severity:
- MEDIUM if score < 2.0
- HIGH if score >= 2.0

#### PS1
Definitions:
- `recent_alerts_7d`: non-PS1 alerts in `[now-7d, now)`
- `previous_alerts_7d`: non-PS1 alerts in `[now-14d, now-7d)`

Extra guard:
- current hour must already have at least one triggered phone code

Trigger:
- `recent_alerts_7d >= PHONE_PS1_RECENT_ALERT_MIN` (12)
- `recent_alerts_7d >= max(6, previous_alerts_7d * PHONE_PS1_GROWTH_RATIO)` (1.5)

Score:
- `recent_alerts_7d`

Severity:
- MEDIUM by default
- HIGH if `recent_alerts_7d >= max(15, previous_alerts_7d * PHONE_PS1_HIGH_GROWTH_RATIO)` (2.0)

#### BP1
Evaluation schedule:
- only when `hour_start.hour == 12`

Sleep proxy:
- longest valid screen inactivity gap in `[day_start-6h, day_start+12h]`
- valid gap duration in `[2h, 14h]`

Ratios:
- `sleep_cv_ratio = std(recent_7d_midpoint) / std(baseline_14d_midpoint)`
- `movement_var_ratio` from recent vs baseline std of `acc_mag_std` / `gyro_mag_std`

Threshold:
- `PHONE_BP1_VARIABILITY_RATIO` (1.7)

Score:
- +1 if movement ratio >= 1.7
- +1 if sleep ratio >= 1.7

Trigger:
- score >= 2.0

Severity:
- HIGH (fixed)

### D.3 Smartwatch Signatures: Exact Trigger, Score, Severity

#### WA1
Components:
- add `min(z_night_hr, 5.0)` when `z_night_hr >= WEARABLE_Z_ALERT` (2.0)
- add `min(abs(z_rmssd), 5.0)` when `z_rmssd <= -WEARABLE_Z_ALERT`

Trigger:
- score > 0

Severity:
- MEDIUM if score < 5
- HIGH if score >= 5

#### WA2
Components:
- sleep reduction: `z_sleep <= -2.0` -> add `min(abs(z_sleep), 5.0)`
- fragmentation: `z_sleep_episode_n >= 2.0` -> add `min(z_sleep_episode_n, 5.0)`
- midpoint shift: `abs(z_sleep_mid) >= 2.0` -> add `min(abs(z_sleep_mid), 5.0)`

Trigger:
- at least two components OR one component >= 4.0

Severity:
- MEDIUM if score < 5
- HIGH if score >= 5

#### WD1
Trigger:
- today `z_steps <= -2.0`
- previous day `prev_z_steps <= -2.0`

Score:
- `abs(z_steps) + abs(prev_z_steps)`

Severity:
- MEDIUM by default
- HIGH if `min(z_steps, prev_z_steps) <= -3`

#### WD2
Trigger:
- `z_sleep <= -2.0`
- `z_night_hr >= 2.0`

Score:
- `abs(z_sleep) + z_night_hr`

Severity:
- MEDIUM by default
- HIGH if `abs(z_sleep) >= 3` OR `z_night_hr >= 3`

#### WB1
Ratios:
- `steps_var_ratio = std(recent_7d_steps) / std(baseline_steps)`
- `sleep_var_ratio = std(recent_7d_sleep_start) / std(baseline_sleep_start)`

Trigger:
- either ratio >= `WEARABLE_VARIABILITY_RATIO` (1.7)

Score:
- `(steps_var_ratio or 0) + (sleep_var_ratio or 0)`

Severity:
- MEDIUM (fixed)

#### WB2
Trigger (all required):
- `z_sleep <= -2.0`
- `z_steps >= 2.0`
- `z_night_hr >= 2.0`

Score:
- `abs(z_sleep) + z_steps + z_night_hr`

Severity:
- HIGH (fixed)

#### WSU3
Distinct stream flags:
- physiology, sleep, steps, sleep_timing, variability

Let:
- `stream_count = |stream_flags|`

Trigger:
- `stream_count >= 2`

Score:
- `stream_count`

Severity:
- LOW for 2
- MEDIUM for 3
- HIGH for 4+

### D.4 LSTM (`LSTM_AE1`) Scoring and Severity

Reconstruction error:
- `err = mean((X_hat - X)^2)` over all timesteps and features

Threshold:
- `threshold = percentile(E, ANOMALY_PERCENTILE)` with default percentile 95
- `E` is holdout sequence errors if holdout exists, else train sequence errors

Trigger:
- `err > threshold`

Ratio:
- `r = err / threshold`

Severity:
- LOW if `1.0 < r < 1.3`
- MEDIUM if `1.3 <= r < 1.8`
- HIGH if `1.8 <= r < 2.5`
- CRITICAL if `r >= 2.5`

### D.5 Parameter Defaults Used by Scoring

General:
- `BASELINE_HOURS = 24`
- `WEARABLE_BASELINE_DAYS = 14`
- `WEARABLE_Z_ALERT = 2.0`
- `WEARABLE_VARIABILITY_RATIO = 1.7`

Phone:
- `PHONE_GPS_LOW_DISTANCE_M = 20.0`
- `PHONE_GPS_STATIONARY_MIN = 0.9`
- `PHONE_LOW_MOTION_RATIO_MAX = 0.4`
- `PHONE_DS2_SCREEN_ON_RATIO = 2.0`
- `PHONE_DS2_SCREEN_SESSION_RATIO = 1.8`
- `PHONE_ENTROPY_Z_ALERT = 2.0`
- `PHONE_GPS_RATIO_ALERT = 2.5`
- `PHONE_NIGHT_SCREEN_RATIO_ALERT = 3.0`
- `PHONE_HIGH_MOTION_RATIO_ALERT = 2.0`
- `PHONE_SU3_SCREEN_SESSION_RATIO = 2.5`
- `PHONE_SU3_SCREEN_ON_RATIO = 2.5`
- `PHONE_PS1_RECENT_ALERT_MIN = 12`
- `PHONE_PS1_GROWTH_RATIO = 1.5`
- `PHONE_PS1_HIGH_GROWTH_RATIO = 2.0`
- `PHONE_BP1_VARIABILITY_RATIO = 1.7`

LSTM:
- `SEQ_LEN = 24`
- `TRAIN_LOOKBACK_HOURS = 168`
- `ANOMALY_PERCENTILE = 95`
- `LSTM_MIN_TRAIN_ROWS = max(SEQ_LEN + 12, 36)`
- `LSTM_MIN_TRAIN_SEQUENCES = 6`
