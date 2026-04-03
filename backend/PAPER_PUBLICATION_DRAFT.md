# Multimodal Digital Phenotyping Backend for Behavioral Risk Signatures:
# Publication-Style Technical Draft

Version: 2026-03-12  
Codebase alignment: `Digital-Phenotyping-Capstone--dev/backend`

---

## Abstract

This work presents a production-oriented backend for multimodal digital phenotyping using smartphone and smartwatch telemetry to detect behavioral risk signatures in near real-time. The system ingests heterogeneous sensor streams (motion, mobility, screen interaction, and wearable physiological/sleep signals), performs incremental feature engineering, applies interpretable rule-based signatures, and augments detection with a participant-specific LSTM autoencoder for multivariate sequence anomalies. The pipeline is designed for operational deployment: automatic schema creation/migration, role-based access control (RBAC), session security, alert acknowledgment workflows, and real-time dashboard synchronization.

The implemented signature set includes nine smartphone indicators (`DS1`, `DS2`, `AS1`, `AS2`, `SU1`, `SU2`, `SU3`, `PS1`, `BP1`), seven smartwatch indicators (`WA1`, `WA2`, `WD1`, `WD2`, `WB1`, `WB2`, `WSU3`), and one learned anomaly channel (`LSTM_AE1`). Baselines are participant-specific and robustly estimated (median/MAD and median-ratio) to reduce sensitivity to outliers. The LSTM model uses robust scaling, contiguous-hour sequence construction, and percentile-based reconstruction-error thresholding.

The backend further incorporates data-protection controls, including encrypted InnoDB storage and application-layer AES-256-GCM protection for selected sensitive fields, plus PBKDF2 password hashing, token-hash sessions, and lockout policies. This architecture offers an end-to-end, reproducible foundation for research and translational deployment of digital behavioral risk monitoring systems.

---

## 1. Introduction

Digital phenotyping systems convert passively collected mobile and wearable telemetry into behavior-level indicators that can support risk monitoring and early intervention workflows. In applied settings, model performance alone is insufficient; a usable system must also satisfy operational constraints: robust ingestion, schema evolution, incremental processing, explainable alerts, secure storage, role-constrained access, and low-friction deployment for collaborators.

This backend addresses these constraints through a multimodal architecture that:
1. Collects phone and watch data through API endpoints.
2. Links device streams to participant identity.
3. Builds hourly (phone) and daily (watch) features.
4. Computes interpretable signatures against personal baselines.
5. Runs participant-specific sequence modeling (LSTM autoencoder).
6. Surfaces evidence-rich alerts in a live dashboard with RBAC controls.

The design principle is **hybrid detection**:
- rules for transparent and clinically interpretable logic,
- learned sequence anomaly modeling for non-obvious multivariate shifts.

---

## 2. System Architecture

### 2.1 Service Topology

The deployment consists of three coordinated services:
- `mysql`: primary relational store.
- `backend` (Kotlin/Vert.x): API server, schema owner, auth/RBAC layer, dashboard backend.
- `signature_engine` (Python): feature extraction loop, signature rules, LSTM training/inference.

### 2.2 Runtime Data Path

1. Client sends telemetry (`/api/accelerometer`, `/api/location`, `/api/wearable/heart-rate`, etc.).
2. Backend normalizes payloads and ensures participant-device linkage (`participant_devices`).
3. Sensor rows are inserted into dedicated raw tables.
4. Signature engine polls for newly completed windows:
   - hourly phone windows,
   - daily watch windows.
5. Derived features are upserted (`hourly_features`, `wearable_daily_features`).
6. Rule signatures and LSTM alerts are inserted into `signature_alerts`.
7. Dashboard reads participant and alert APIs; acknowledgment updates are broadcast via WebSocket.

### 2.3 Incremental Processing Semantics

The system is checkpoint-driven using `engine_state`, with per-participant/per-engine progress markers:
- `last_processed_hour_start`
- `last_processed_day_start`
- `last_trained_hour_start`
- model threshold metadata

This enables restart-safe incremental operation rather than full recomputation.

---

## 3. Data Model and Schema Strategy

### 3.1 Schema Ownership

Schema creation/migration is centralized in backend startup (`MySQLVerticle`), allowing teammates to start from an empty database and obtain required tables automatically.

### 3.2 Core Table Families

Raw smartphone:
- `accelerometer`, `gyroscope`, `location`, `screen_events`, `battery_readings`, `notifications`, `pedometer`.

Raw smartwatch:
- `wearable_heart_rate`, `wearable_steps`, `wearable_sleep`,
- `wearable_blood_pressure`, `wearable_weight`, `wearable_oxygen`, `wearable_respiratory`.

Identity and geofence:
- `participants`, `participant_devices`, `red_zones`, `geofence_alerts`.

Analytics:
- `hourly_features`, `wearable_daily_features`, `engine_state`,
- `signature_alerts` (+ legacy/archive compatibility tables),
- `anomaly_hours`, `watch_day_profiles` (generator support).

Security/auth:
- `app_users`, `auth_sessions`, `auth_login_attempts`, `security_audit_log`.

### 3.3 Identity Linking Model

A participant may own multiple devices. `participant_devices` stores explicit `device_type` (`phone`/`watch`) mappings, solving ambiguity and enabling source-specific alert filtering.

---

## 4. Feature Engineering

## 4.1 Smartphone Hourly Features

For each completed hour:
- Accelerometer magnitude and dynamics:
  - `acc_mag_mean`, `acc_mag_std`, `acc_mag_p95`, `acc_jerk_mean`, `acc_inactive_ratio`.
- Gyroscope intensity features:
  - `gyro_mag_mean`, `gyro_mag_std`, `gyro_mag_p95`.
- Mobility context:
  - `gps_distance_m`, `gps_stationary_ratio`, `gps_mean_accuracy`, `gps_points`.
- Phone engagement:
  - `screen_sessions`, `screen_on_seconds`, `screen_avg_session_seconds`, event counts.

GPS features are accuracy-filtered and distance-integrated using Haversine segments.

## 4.2 Smartwatch Daily Features

For each completed day:
- Activity:
  - `steps_total`.
- Sleep:
  - `sleep_minutes`, `sleep_episode_n`, `sleep_start_hour`, `sleep_end_hour`, `sleep_midpoint_hour`.
- Cardiovascular/autonomic proxies:
  - `day_hr_mean`, `night_hr_mean`, `resting_hr_p10`.
- HRV approximations from BPM-derived RR:
  - `rmssd_night`, `sdnn_night`.

Sleep episodes crossing day boundaries are clipped to the day window before aggregation.

---

## 5. Rule-Based Behavioral Signatures

All signatures compare current behavior to participant-specific baselines and output:
- alert code/name,
- severity,
- score,
- evidence payload (`top_features_json`),
- explanation string.

### 5.1 Baseline Math

Two robust comparison families are used:

1. **Robust ratio**  
`ratio = current / median(baseline)`

2. **Robust z-score (MAD)**  
`z = 0.6745 * (x - median) / MAD`

Phone default baseline window: last 24 hours.  
Watch default baseline window: last 14 days.

### 5.2 Smartphone Signature Set

- `DS1`: low mobility + low activity.
- `DS2`: low movement + elevated engagement.
- `AS1`: night restlessness (late-night screen/motion elevation).
- `AS2`: location instability (entropy/context switching).
- `SU1`: high mobility entropy + activity peaks.
- `SU2`: red-zone exposure (geofence-like high-risk location).
- `SU3`: abrupt interaction spike.
- `PS1`: recent-week anomaly-growth meta-indicator.
- `BP1`: movement variability + sleep timing irregularity.

### 5.3 Smartwatch Signature Set

- `WA1`: physiological arousal proxy (night HR up and/or HRV down).
- `WA2`: sleep reduction/fragmentation/timing disruption.
- `WD1`: sustained low steps over consecutive days.
- `WD2`: sleep reduction plus elevated night HR trend.
- `WB1`: elevated weekly variability in activity/sleep timing.
- `WB2`: elevated activation triad (sleep down + steps up + night HR up).
- `WSU3`: same-day multi-stream wearable anomaly accumulation.

### 5.4 Implementability Boundary

`WSU2` is intentionally excluded because required TAC/EDA-like streams are not present in the current schema/sensor pipeline.

### 5.5 Full Smartphone Scoring and Severity Logic (Code-Exact)

This subsection specifies exactly how score and severity are computed in the implemented engine (`signature_engine/alerts.py`).

Common symbols:
- `r_acc_std = acc_mag_std_current / median(acc_mag_std_baseline)`
- `r_gyro_std = gyro_mag_std_current / median(gyro_mag_std_baseline)`
- `r_screen_on = screen_on_seconds_current / median(screen_on_seconds_baseline)`
- `r_screen_sessions = screen_sessions_current / median(screen_sessions_baseline)`
- `r_gps = gps_distance_current / median(gps_distance_baseline)`
- `z_entropy = robust_z(location_entropy_current, entropy_baseline)`
- robust z uses MAD:
  - `z = 0.6745 * (x - median) / MAD`

Phone baseline window:
- last `BASELINE_HOURS` (default 24h).

#### DS1 (`Low mobility + low activity indicator`)

Conditions:
1. GPS low mobility gate:
- `gps_n >= 3`
- `gps_distance_m < PHONE_GPS_LOW_DISTANCE_M` (default 20.0)
- `gps_stationary_ratio >= PHONE_GPS_STATIONARY_MIN` (default 0.9)

2. Low-motion evidence count:
- +1 if `accel_n >= 10` and `r_acc_std < PHONE_LOW_MOTION_RATIO_MAX` (default 0.4)
- +1 if `gyro_n >= 10` and `r_gyro_std < PHONE_LOW_MOTION_RATIO_MAX`

Trigger:
- GPS low mobility AND low-motion count >= 1.

Score:
- `score = 1.0 + 0.5 * low_motion_signals`

Severity:
- LOW if `score < 2`
- MEDIUM otherwise

Worked example:
- `gps_distance_m=9`, `gps_stationary_ratio=0.94`, `gps_n=8`
- `r_acc_std=0.31` (low), `r_gyro_std=0.52` (not low)
- low-motion count=1 => score=1.5 => DS1 LOW

#### DS2 (`Sedentary engagement indicator`)

Score components:
- +1.0 if low-motion count >= 1
- +1.0 if `r_screen_on >= PHONE_DS2_SCREEN_ON_RATIO` (default 2.0)
- +0.5 if `r_screen_sessions >= PHONE_DS2_SCREEN_SESSION_RATIO` (default 1.8)

Trigger:
- `score >= 2.0`

Severity:
- MEDIUM if `score < 2.5`
- HIGH if `score >= 2.5`

Worked example:
- low motion true (+1)
- `r_screen_on=2.3` (+1)
- `r_screen_sessions=1.6` (+0)
- total=2.0 => DS2 MEDIUM

#### AS2 (`Location instability indicator`)

Location entropy:
- GPS points are binned into 0.001-degree grid cells.
- cell probability vector `p_i` yields entropy `H = -Σ p_i log(p_i)`.

Score components:
- +1.0 if `z_entropy >= PHONE_ENTROPY_Z_ALERT` (default 2.0)
- +0.5 if `r_gps >= PHONE_GPS_RATIO_ALERT` (default 2.5)
- +0.5 if `gps_stationary_ratio <= 0.3`

Trigger:
- `score >= 1.5`

Severity:
- HIGH if `score >= 2.0`
- MEDIUM otherwise

Worked example:
- `z_entropy=2.2` (+1.0)
- `r_gps=2.8` (+0.5)
- `gps_stationary_ratio=0.38` (+0)
- total=1.5 => AS2 MEDIUM

#### AS1 (`Night restlessness / sleep disruption indicator`)

Night-hour set:
- `{23, 0, 1, 2, 3, 4, 5}`

Score:
- +1.0 if night and `r_screen_on >= PHONE_NIGHT_SCREEN_RATIO_ALERT` (default 3.0)
- +1.0 if night and `r_acc_std >= PHONE_HIGH_MOTION_RATIO_ALERT` (default 2.0)

Trigger:
- `score > 0`

Severity mapping:
- CRITICAL if `score >= 2.0`
- HIGH if `score >= 1.5`
- MEDIUM if `0 < score < 1.5`

Worked example:
- 01:00 hour, `r_screen_on=3.4`, `r_acc_std=1.8`
- score=1.0 => AS1 MEDIUM

#### SU1 (`High mobility entropy + activity peaks indicator`)

Score components:
- +1.0 if `z_entropy >= 2.0`
- +0.5 if `r_acc_std >= 2.0`
- +0.5 if `r_gyro_std >= 2.0`

Trigger:
- `score >= 1.5`

Severity:
- HIGH if `score >= 2.0`
- MEDIUM otherwise

Worked example:
- `z_entropy=2.6`, `r_acc_std=2.3`, `r_gyro_std=1.7`
- score=1.5 => SU1 MEDIUM

#### SU2 (`High-risk location exposure indicator`)

Red-zone logic:
- iterate hourly location points.
- for each configured zone (`participant-specific` or global), compute Haversine distance.
- trigger if any point is within zone radius.

Score:
- fixed `1.0`

Severity:
- fixed `HIGH`

Evidence:
- nearest hit (`zone_id`, `zone_name`, `zone_type`, `distance_m`).

Worked example:
- minimum point-zone distance = 47m in radius-220m zone => SU2 HIGH.

#### SU3 (`Interaction spike indicator`)

Score components:
- +1.0 if `r_screen_sessions >= PHONE_SU3_SCREEN_SESSION_RATIO` (default 2.5)
- +1.0 if `r_screen_on >= PHONE_SU3_SCREEN_ON_RATIO` (default 2.5)

Trigger:
- `score >= 1.5`

Severity:
- HIGH if `score >= 2.0`
- MEDIUM otherwise

Worked example:
- `r_screen_sessions=2.7` (+1)
- `r_screen_on=2.4` (+0)
- total=1.0 => no SU3 alert

#### PS1 (`Pre-relapse anomaly spike indicator`)

This is a second-order signature (alert-rate acceleration).

Definitions:
- `recent_alerts_7d`: count of non-PS1 alerts in `[now-7d, now)`.
- `previous_alerts_7d`: count in `[now-14d, now-7d)`.

Additional guard:
- the current hour must already have at least one triggered phone signature.

Trigger conditions:
- `recent_alerts_7d >= PHONE_PS1_RECENT_ALERT_MIN` (default 12)
- `recent_alerts_7d >= max(6, previous_alerts_7d * PHONE_PS1_GROWTH_RATIO)` (default growth ratio 1.5)

Score:
- `score = recent_alerts_7d`

Severity:
- HIGH if `recent_alerts_7d >= max(15, previous_alerts_7d * PHONE_PS1_HIGH_GROWTH_RATIO)` (default 2.0)
- MEDIUM otherwise

Worked example:
- previous=9, recent=16, triggered_codes this hour non-empty
- growth gate `max(6,13.5)=13.5` => pass
- high gate `max(15,18)=18` => fail
- PS1 MEDIUM

#### BP1 (`Activity variability + sleep timing irregularity indicator`)

Evaluation schedule:
- only evaluated when `hour_start.hour == 12` (noon checkpoint).

Sleep proxy construction:
- use screen-event inactivity gaps in window `[day_start-6h, day_start+12h]`.
- valid gap duration range: `[2h,14h]`.
- choose longest valid gap:
  - `duration_hours`
  - `midpoint_hour`

Variability metrics:
1. `sleep_cv_ratio = std(recent_7d_midpoint) / std(baseline_14d_midpoint)`
   - requires recent >= 5 and baseline >= 7 proxy days.
2. `movement_var_ratio` from `acc_mag_std` and `gyro_mag_std`:
   - recent window: last 72h
   - baseline window: prior 14d excluding recent 72h
   - use max(acc_std_ratio, gyro_std_ratio) when both available.

Threshold:
- `PHONE_BP1_VARIABILITY_RATIO` (default 1.7)

Score:
- +1 if `movement_var_ratio >= 1.7`
- +1 if `sleep_cv_ratio >= 1.7`

Trigger:
- `score >= 2.0`

Severity:
- fixed `HIGH`

Worked example:
- movement ratio=1.95 (+1)
- sleep ratio=1.82 (+1)
- score=2.0 => BP1 HIGH

### 5.6 Full Smartwatch Scoring and Severity Logic (Code-Exact)

Watch baseline window:
- last `WEARABLE_BASELINE_DAYS` (default 14 days).

Watch robust z variables:
- `z_steps`, `z_sleep`, `z_night_hr`, `z_sleep_start`, `z_sleep_mid`, `z_sleep_episode_n`, `z_rmssd`.

#### WA1 (`Physiological arousal proxy`)

Components:
- if `z_night_hr >= WEARABLE_Z_ALERT` (default 2.0): add `min(z_night_hr, 5.0)`
- if `z_rmssd <= -WEARABLE_Z_ALERT`: add `min(abs(z_rmssd), 5.0)`

Trigger:
- `wa1_score > 0`

Severity:
- MEDIUM if `< 5`
- HIGH if `>= 5`

Worked example:
- `z_night_hr=2.6` adds 2.6
- `z_rmssd=-2.2` adds 2.2
- score=4.8 => WA1 MEDIUM

#### WA2 (`Sleep disruption / fragmentation proxy`)

Components:
- sleep reduction: if `z_sleep <= -2.0`, add `min(abs(z_sleep), 5.0)`
- fragmentation: if `z_sleep_episode_n >= 2.0`, add `min(z_sleep_episode_n, 5.0)`
- midpoint disruption: if `abs(z_sleep_mid) >= 2.0`, add `min(abs(z_sleep_mid), 5.0)`

Trigger:
- at least two components, OR
- one very strong component (`max(component) >= 4.0`)

Severity:
- MEDIUM if score `< 5`
- HIGH if score `>= 5`

Worked example:
- `z_sleep=-2.7` (2.7)
- `z_sleep_episode_n=2.3` (2.3)
- `z_sleep_mid=1.4` (0)
- score=5.0 => WA2 HIGH

#### WD1 (`Reduced activity for two consecutive days`)

Requires:
- today `z_steps <= -2.0`
- previous day `prev_z_steps <= -2.0` (previous day z computed against its own baseline)

Score:
- `abs(z_steps) + abs(prev_z_steps)`

Severity:
- HIGH if `min(z_steps, prev_z_steps) <= -3`
- MEDIUM otherwise

Worked example:
- today=-2.1, prev=-3.2 => score=5.3 => WD1 HIGH

#### WD2 (`Sleep disruption + elevated night HR trend`)

Requires:
- `z_sleep <= -2.0`
- `z_night_hr >= 2.0`

Score:
- `abs(z_sleep) + z_night_hr`

Severity:
- HIGH if `abs(z_sleep) >= 3` OR `z_night_hr >= 3`
- MEDIUM otherwise

Worked example:
- `z_sleep=-2.4`, `z_night_hr=2.2` => score=4.6 => WD2 MEDIUM

#### WB1 (`Weekly variability increase`)

Ratios:
- `steps_var_ratio = std(recent_7d_steps) / std(baseline_steps)`
- `sleep_var_ratio = std(recent_7d_sleep_start) / std(baseline_sleep_start)`

Trigger:
- `steps_var_ratio >= WEARABLE_VARIABILITY_RATIO` OR
- `sleep_var_ratio >= WEARABLE_VARIABILITY_RATIO`
- default ratio threshold: 1.7

Score:
- `(steps_var_ratio or 0) + (sleep_var_ratio or 0)`

Severity:
- fixed MEDIUM

Worked example:
- steps ratio=1.85, sleep ratio=1.31 => trigger true => WB1 MEDIUM

#### WB2 (`Elevated activation triad`)

Requires all:
- `z_sleep <= -2.0`
- `z_steps >= 2.0`
- `z_night_hr >= 2.0`

Score:
- `abs(z_sleep) + z_steps + z_night_hr`

Severity:
- fixed HIGH

Worked example:
- -2.5, +2.2, +2.4 => score=7.1 => WB2 HIGH

#### WSU3 (`Multi-stream wearable anomaly rate`)

The engine accumulates distinct stream flags in a set:
- physiology
- sleep
- steps
- sleep_timing
- variability

Let `stream_count = |stream_flags|`.

Trigger:
- `stream_count >= 2`

Score:
- `stream_count`

Severity:
- LOW for 2
- MEDIUM for 3
- HIGH for 4+

Worked example:
- flags={sleep,physiology,variability} => stream_count=3 => WSU3 MEDIUM

### 5.7 Score Interpretation Notes

1. Scores are **signature-specific**, not globally normalized across all codes.
2. Severity thresholds are code-level heuristics, not probabilistic confidence values.
3. `top_features_json` stores the evidence used for human review and auditing.
4. Baseline windows differ by modality (hourly phone vs daily watch) and influence score behavior.

### 5.8 Parameter Defaults (Exact Runtime Controls)

Phone-related defaults:
- `BASELINE_HOURS = 24`
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

Watch-related defaults:
- `WEARABLE_BASELINE_DAYS = 14`
- `WEARABLE_Z_ALERT = 2.0`
- `WEARABLE_VARIABILITY_RATIO = 1.7`

---

## 6. LSTM Autoencoder Channel (`LSTM_AE1`)

## 6.1 Modeling Objective

Model participant-specific normal hourly behavior sequences and detect anomalous sequence reconstructions.

## 6.2 Input Space

Thirteen hourly engineered features are used:
`acc_mag_mean`, `acc_mag_std`, `acc_jerk_mean`, `acc_mag_p95`, `acc_inactive_ratio`, `gyro_mag_mean`, `gyro_mag_std`, `gyro_mag_p95`, `gps_distance_m`, `gps_stationary_ratio`, `screen_sessions`, `screen_on_seconds`, `screen_avg_session_seconds`.

## 6.3 Normalization

Robust per-feature scaling:
- center by median,
- scale by IQR (`q3-q1`, floored at 1 when zero),
- impute missing with median.

This reduces outlier distortion relative to mean/std normalization.

## 6.4 Sequence Construction

Sequence length `SEQ_LEN` (default 24 hours).  
Only contiguous windows are accepted (strict 1-hour adjacency), preventing broken timelines from contaminating temporal learning.

## 6.5 Architecture

Encoder-decoder LSTM autoencoder:
- encoder LSTM (hidden=64),
- latent projection (32),
- latent expansion and repeated hidden representation,
- decoder LSTM,
- linear output head back to 13-dimensional sequence outputs.

## 6.6 Training Protocol

Window: last `TRAIN_LOOKBACK_HOURS` (default 168h).  
Data quality gates:
- minimum hourly rows,
- minimum contiguous sequences.

Holdout-aware thresholding:
- split sequences into train/eval.
- train with MSE reconstruction.
- compute reconstruction errors on holdout if available.
- threshold = chosen percentile (default 95th).

## 6.7 Inference and Severity Mapping

At scoring hour:
1. build latest contiguous sequence,
2. reconstruct and compute MSE,
3. trigger if `error > threshold`.

Severity uses ratio `error/threshold`:
- LOW: (1.0, 1.3)
- MEDIUM: [1.3, 1.8)
- HIGH: [1.8, 2.5)
- CRITICAL: >= 2.5

## 6.8 Artifacts and Reproducibility

Per-participant model bundle:
- `models/lstm_ae_<participant>.pt`
- `models/lstm_ae_<participant>_scaler.json`

One-click rebuild script:
- `train_lstm_one_click.py`
- clears state/artifacts (unless disabled), rebuilds features, retrains participant bundles.

## 6.9 Exact Reconstruction Error Definition

For one sequence tensor `X` and model reconstruction `X_hat`, error is:

`err = mean((X_hat - X)^2)` over all timesteps and all features.

This is the MSE used both for training loss and for anomaly scoring.

## 6.10 Exact Threshold Derivation

Let `E = {err_i}` from:
- holdout eval sequences if present,
- otherwise train sequences.

Threshold is:
- `threshold = percentile(E, ANOMALY_PERCENTILE)`
- default percentile = 95.

Interpretation:
- approximately top 5% highest reconstruction windows in reference set are above threshold.

## 6.11 Training Gate Criteria (Code Defaults)

LSTM training is skipped when either fails:
- `rows < LSTM_MIN_TRAIN_ROWS` (default `max(SEQ_LEN+12, 36)`)
- `contiguous_sequences < LSTM_MIN_TRAIN_SEQUENCES` (default 6)

This avoids unstable model fitting on sparse histories.

## 6.12 LSTM Worked Example

Assume:
- participant has 220 hourly rows in lookback window,
- 120 contiguous 24h sequences after continuity filtering,
- threshold percentile = 95.

After training:
- holdout reconstruction errors produce `threshold = 0.043`.

At runtime for latest hour:
- current sequence error `err = 0.081`
- ratio `r = err/threshold = 0.081/0.043 = 1.88`

Alert result:
- `LSTM_AE1` triggered (`err > threshold`),
- severity = HIGH (ratio in `[1.8, 2.5)`),
- score stored as `0.081`,
- evidence includes threshold and ratio in JSON.

---

## 7. Security and Privacy Controls

## 7.1 Storage-Layer Protection

MySQL is configured with encryption-at-rest controls:
- default table encryption ON,
- InnoDB redo/undo log encryption ON,
- binlog encryption ON,
- keyring plugin enabled.

This protects on-disk database files against direct file theft scenarios.

## 7.2 Application-Layer Encryption

Sensitive field encryption utility uses AES-256-GCM:
- algorithm: `AES/GCM/NoPadding`,
- 32-byte key from `DATA_ENCRYPTION_KEY_B64`,
- random nonce per value,
- versioned ciphertext prefix (`enc:v1:`).

Current code usage:
- encrypted mirrors for accelerometer axes (`x_enc`, `y_enc`, `z_enc`),
- encrypted notification text fields (`title`, `content`).

## 7.3 Credential and Session Security

- Password hashing: PBKDF2-HMAC-SHA256 (210,000 iterations + random salt).
- Session tokens: random, DB stores SHA-256 token hashes.
- Login throttling and lockout via `auth_login_attempts`.
- Security actions audited in `security_audit_log`.

## 7.4 Secret Management Interface

Secrets resolved by priority:
1. `*_FILE` path contents,
2. direct environment variable,
3. fallback default.

This supports local development and production secret injection workflows.

---

## 8. Access Control and Operational UX

### 8.1 RBAC Roles

Roles:
- `admin`
- `analyst`
- `viewer`
- `doctor`
- `ingest`

Current policy:
- Doctor has operational read visibility (participants/alerts/zones/devices).
- Users/settings/admin-security actions remain admin-only.

### 8.2 Alert Operations

Alert acknowledgment is role-gated and audit-logged.  
Updates are broadcast live (`alerts.changed`) to keep dashboards synchronized.

### 8.3 Participant-Centric Filtering

Dashboard supports:
- participant selection,
- source filtering (phone/watch),
- status filtering (active/acknowledged/all),
- configurable limits.

This addresses high-alert-volume triage by reducing cross-participant noise.

---

## 9. Deployment and Teammate Reproducibility

Minimal handoff workflow:
1. Clone repository.
2. `docker compose up -d --build`.
3. Backend startup auto-creates/migrates schema.
4. Run synthetic generator if needed.
5. Verify engine loop and dashboard.

This removes separate bootstrap dependencies and reduces onboarding friction.

---

## 10. Suggested Results Section Structure (for Paper)

For a publishable evaluation section, report:
1. Data volume:
   - raw rows per sensor modality,
   - participants with both phone+watch mapping.
2. Feature coverage:
   - completed hourly/day windows,
   - missingness profile.
3. Signature yield:
   - per-code alert counts,
   - severity distributions,
   - participant distribution.
4. LSTM readiness:
   - trained vs skipped participant counts,
   - skip reasons (insufficient contiguous history, etc.).
5. Online behavior:
   - end-to-end latency from ingest to visible alert.
6. Security posture:
   - encrypted tables on disk,
   - auth lockout statistics,
   - audit log activity.

---

## 11. Threats to Validity and Limitations

1. Proxy semantics:
- Signatures are behavioral/physiological proxies, not clinical diagnoses.

2. Sensor dependency:
- Missing streams (e.g., TAC/EDA) constrain signature completeness (`WSU2` absent).

3. Synthetic-vs-real distribution gap:
- Simulated anomalies may not fully represent real-world behavioral transitions.

4. LSTM dependence on continuity:
- sparse or irregular time series can reduce trainability and detection coverage.

5. Operational threshold sensitivity:
- robust thresholds reduce noise but still require tuning for deployment population.

---

## 12. Conclusion

The backend provides a complete operational stack for multimodal digital phenotyping: ingestion, identity linkage, robust feature engineering, interpretable rule signatures, sequence-model anomaly detection, secure access, and real-time dashboard delivery. Its automatic schema management and one-command deployment make it suitable for collaborative capstone continuation, while the hybrid rule+LSTM design supports both interpretability and pattern generalization in behavioral risk monitoring.

---

## Appendix A: Implemented Signature Inventory

Smartphone:
- `DS1`, `DS2`, `AS1`, `AS2`, `SU1`, `SU2`, `SU3`, `PS1`, `BP1`

Smartwatch:
- `WA1`, `WA2`, `WD1`, `WD2`, `WB1`, `WB2`, `WSU3`

Model-based:
- `LSTM_AE1`

Not implemented due missing sensors:
- `WSU2` (requires TAC/EDA-like stream support)

---

## Appendix B: Ready-to-Use Method Statement (Short)

"We implemented a hybrid anomaly-detection backend that combines participant-relative robust rule signatures with participant-specific LSTM autoencoder sequence modeling. Smartphone signals are aggregated hourly, smartwatch signals daily, and all detections are written as evidence-rich alerts with severity and baseline references. The system operates incrementally through engine checkpoints and is secured with RBAC, session controls, audit logging, and layered encryption controls."

---

## Appendix C: Configuration Defaults Used for Scoring

From current signature engine config:

General:
- `BASELINE_HOURS = 24`
- `WEARABLE_BASELINE_DAYS = 14`
- `WEARABLE_Z_ALERT = 2.0`
- `WEARABLE_VARIABILITY_RATIO = 1.7`

Phone thresholds:
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

LSTM defaults:
- `SEQ_LEN = 24`
- `TRAIN_LOOKBACK_HOURS = 168`
- `TRAIN_EPOCHS = 8`
- `TRAIN_BATCH_SIZE = 32`
- `TRAIN_LR = 0.001`
- `ANOMALY_PERCENTILE = 95`
- `RETRAIN_EVERY_HOURS = 24`
- `LSTM_MIN_TRAIN_ROWS = max(SEQ_LEN + 12, 36)`
- `LSTM_MIN_TRAIN_SEQUENCES = 6`
