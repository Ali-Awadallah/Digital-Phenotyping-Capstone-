<div align="center">
  <h1>An IDS-Inspired Digital Phenotyping Framework for Early Behavioral Risk Detection</h1>
  <p><strong>Capstone Project &mdash; University of Doha for Science and Technology (UDST)</strong></p>
  <p>College of Computing and Information Technology &mdash; Cybersecurity Department</p>
</div>

---

## Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
- [System Architecture](#system-architecture)
- [Behavioral Risk Scenarios](#behavioral-risk-scenarios)
- [Technology Stack](#technology-stack)
- [Repository Structure](#repository-structure)
- [Prerequisites](#prerequisites)
- [Backend Setup](#backend-setup)
- [Mobile App Setup](#mobile-app-setup)
- [Synthetic Data Generation](#synthetic-data-generation)
- [Training the LSTM Anomaly Detection Model](#training-the-lstm-anomaly-detection-model)
- [Behavioral Signatures](#behavioral-signatures)
- [Security and Encryption](#security-and-encryption)
- [API Reference](#api-reference)
- [Troubleshooting](#troubleshooting)
- [Team](#team)
- [License](#license)

---

## Overview

This project applies **intrusion detection system (IDS) principles** to **digital phenotyping** -- the continuous measurement of human behavior through passive smartphone and wearable data. Instead of using fixed population thresholds, the system learns individualized behavioral baselines for each participant and treats significant deviations as intrusion-like events, enabling early detection of:

- **Mental health deterioration** (depression, withdrawal)
- **Screen addiction** (compulsive usage, late-night engagement)
- **Substance-use risk** (behavioral destabilization, high-risk location exposure)

The system collects multimodal sensor data (GPS, accelerometer, gyroscope, screen events, notifications, heart rate, sleep, steps, etc.), transforms it into engineered behavioral features, evaluates rule-based and AI-driven signatures, and surfaces alerts through a participant-centered monitoring dashboard -- all while preserving user privacy through encryption, pseudonymization, and role-based access control.

---

## Key Features

- **Passive multimodal data collection** -- phone sensors (GPS, accelerometer, gyroscope, pedometer, screen, battery, notifications) and wearable data via Android Health Connect (heart rate, steps, sleep, blood pressure, SpO2, respiratory rate, weight)
- **Participant-specific behavioral baselines** -- each person's "normal" is learned from their own history using robust statistics (median/MAD), not population averages
- **Rule-based behavioral signatures** -- 15+ interpretable alert types covering mobility withdrawal, sedentary engagement, night restlessness, location instability, phone interaction spikes, sleep disruption, physiological arousal, and more
- **LSTM autoencoder anomaly detection** -- per-participant deep learning model trained on 24-hour behavioral sequences to detect non-obvious multivariate temporal shifts
- **End-to-end encryption** -- AES-256-GCM application-layer encryption for sensitive fields, MySQL at-rest encryption, PBKDF2 password hashing (210,000 iterations)
- **Role-based access control** -- admin, doctor, and ingest roles with session-based and API key authentication
- **One-command Docker deployment** -- MySQL, Kotlin/Vert.x backend, and Python signature engine launch together with automatic schema creation and migration
- **Security audit logging** -- all sensitive actions tracked in `security_audit_log`
- **Geofence alerting** -- configurable red zones with automatic proximity alerts

---

## System Architecture

```
+-----------------------------+
|       Mobile Client         |
|  (Expo / React Native)      |
|-----------------------------|
|  Phone sensors (GPS, accel, |
|  gyro, pedometer, screen,   |
|  battery, notifications)    |
|  Wearable via Health Connect|
|  Background service         |
+-------------+---------------+
              |
              | HTTP REST (JSON)
              | X-API-Key auth
              v
+-----------------------------+
|     Kotlin/Vert.x Backend   |
|      (dp-backend:8080)      |
|-----------------------------|
|  REST API + WebSocket       |
|  Auth (RBAC + sessions)     |
|  Auto schema migration      |
|  AES-256-GCM encryption     |
|  Participant/device mgmt    |
|  Geofence detection         |
+-------------+---------------+
              |
              v
+-----------------------------+
|      MySQL 8.0 (dp-mysql)   |
|-----------------------------|
|  At-rest encryption (ON)    |
|  ~30 tables (raw sensor,    |
|  participants, analytics,   |
|  alerts, auth, geofence)    |
+-------------+---------------+
              |
              v
+-----------------------------+
|  Python Signature Engine    |
|  (signature-engine-service) |
|-----------------------------|
|  Hourly phone features      |
|  Daily wearable features    |
|  Rule-based signatures      |
|  LSTM autoencoder scoring   |
|  Alert generation           |
+-----------------------------+
```

---

## Behavioral Risk Scenarios

### Mental Health Deterioration
Early signals such as sleep disruption, mobility withdrawal, and reduced physical activity are captured through participant-relative signatures. The system detects within-person deterioration rather than comparing against population averages.

### Screen Addiction
The phone feature pipeline reconstructs screen sessions from on/off events, distinguishing long sustained use from repeated compulsive checking. Signatures detect late-night restlessness and high-engagement sedentary behavior.

### Substance-Use Risk
Geofence support detects visits to configured high-risk locations. Behavioral destabilization is identified through clusters of unusual movement, mobility, and interaction deviations, even without direct substance-specific physiological data.

---

## Technology Stack

| Layer | Technologies |
|-------|-------------|
| Mobile Client | React Native 0.81, Expo SDK 54, Android native modules (Kotlin) |
| Backend Server | Kotlin 2.0, Vert.x 4.5, Gradle 8.11, ShadowJar |
| Database | MySQL 8.0 with at-rest encryption |
| Signature Engine | Python 3, PyTorch (LSTM autoencoder), pandas, NumPy, scikit-learn |
| Containerization | Docker, Docker Compose |
| Security | AES-256-GCM, PBKDF2-SHA256, RBAC, audit logging |

---

## Repository Structure

```
.
|-- README.md                           # This file
|-- DigitalPhenotypingPaper.md          # Full research paper
|-- app/                                # Mobile client (Expo + React Native)
|   |-- App.js                          # App entry point
|   |-- awareAPI.js                     # API client (all backend calls)
|   |-- package.json
|   |-- app.json                        # Expo config (android.package = com.dp.capstone)
|   |-- android/                        # Android native project
|   |   +-- app/src/main/java/com/dp/demo/
|   |       |-- BackgroundServiceModule.kt    # Persistent sensor collection
|   |       |-- BackendAPIClient.kt           # Native HTTP sender
|   |       |-- SensorCollectorService.kt     # Foreground sensor service
|   |       |-- HealthConnectModule.kt        # Health Connect bridge
|   |       |-- AppUsageModule.kt             # UsageStats bridge
|   |       |-- NotificationLoggerService.kt  # Notification listener
|   |       +-- BootReceiver.kt               # Auto-start on boot
|   |-- plugins/                        # Expo config plugins
|   +-- src/
|       |-- screens/                    # UI screens
|       |   |-- HomeScreen.js           # Dashboard home
|       |   |-- SensorsScreen.js        # Real-time sensor display
|       |   |-- AlertsScreen.js         # Alerts viewer
|       |   |-- SettingsScreen.js       # Server connection config
|       |   |-- ProfileScreen.js        # Participant profile
|       |   |-- ConsentScreen.js        # Data consent management
|       |   +-- PermissionsScreen.js    # Permission grants
|       |-- services/                   # JS services
|       |   |-- SensorsService.js       # Sensor collection (5s intervals)
|       |   |-- BackgroundService.js    # Native bridge
|       |   +-- HealthConnectService.js # Wearable data bridge
|       |-- hooks/                      # Custom hooks
|       |-- components/                 # Reusable UI components
|       |-- context/                    # React context providers
|       +-- navigation/
|           +-- RootNavigator.js        # Tab navigation
|
+-- backend/                            # Backend + Signature Engine
    |-- docker-compose.yml              # Full stack (MySQL + Backend + Engine)
    |-- Dockerfile                      # Backend container (JDK 17)
    |-- start.bat                       # One-click Docker startup (Windows)
    |-- start-server.bat                # Local Gradle server startup
    |-- build.gradle                    # Kotlin/Vert.x build config
    |-- aware-config.json               # Local server config
    |-- aware-config-docker.json        # Docker server config
    |-- requirements-ai.txt             # Python dependencies for engine
    |-- generate_aware_mysql_v2.py      # Synthetic data generator
    |-- train_lstm_one_click.py         # LSTM model training script
    |-- train_lstm_one_click.bat        # Training launcher (Windows)
    |-- build_hourly_features_and_alerts.py  # Feature extraction + alerting
    |-- rotate_keys.py                  # Secret rotation utility
    |-- SECURITY_DB.md                  # Database security runbook
    |-- TEAMMATE_HANDOFF_CHECKLIST.md   # Operations reference
    |-- src/main/kotlin/com/awareframework/micro/
    |   |-- MainVerticle.kt             # HTTP routes, auth, API handlers
    |   |-- MySQLVerticle.kt            # Database operations, schema migration
    |   |-- SensitiveDataCipher.kt      # AES-256-GCM encryption
    |   |-- AuthSecurity.kt             # PBKDF2 password hashing, sessions
    |   +-- SecretResolver.kt           # Environment/file secret loading
    |-- signature_engine/               # Python detection modules
    |-- models/                         # Trained LSTM model files
    +-- mysql/                          # SQL scripts (encryption, privileges)
```

---

## Prerequisites

### For Backend (Docker -- Recommended)
- **Docker Desktop** -- https://www.docker.com/products/docker-desktop/

### For Backend (Local Development)
- **JDK 17** -- required by the Kotlin/Vert.x backend
- **Python 3.8+** -- for the signature engine and data generation
- **MySQL 8.0** -- local instance or Docker

### For Mobile Client
- **Node.js 18+** and npm
- **JDK 17** -- required by React Native / Android Gradle Plugin
- **Android SDK + Platform Tools (ADB)** -- via Android Studio
- **Android device or emulator** -- API 26+ (Android 8.0+)

Ensure `adb` is on your PATH:
```
%LOCALAPPDATA%\Android\Sdk\platform-tools\adb
```

---

## Backend Setup

### Docker Deployment (Recommended)

This starts MySQL, the Kotlin backend, and the Python signature engine together. The backend automatically creates and migrates all database tables on startup.

```bash
cd backend

# One-command startup (builds and starts all 3 services)
docker compose up -d --build
```

On Windows, you can use the convenience script:
```
start.bat
```

Verify all 3 containers are running:
```bash
docker compose ps
```

Expected services: `dp-mysql`, `dp-backend`, `signature-engine-service`

Verify the database schema was created:
```bash
docker exec dp-mysql mysql -uroot -prootpassword -D aware_db -e "SHOW TABLES;"
```

Test the backend is responding:
```bash
curl http://localhost:8080/api/testing
```

### Local Development (Without Docker)

If you want to run the backend directly for development:

1. Start a MySQL 8.0 instance (default port 3306)
2. Create a database named `aware_db`
3. Run the backend:

```bash
cd backend
.\gradlew.bat run          # Windows
./gradlew run              # macOS/Linux
```

Or use the convenience script:
```
start-server.bat
```

The backend listens on **port 8080** (HTTP) and **port 8081** (WebSocket).

### Default Credentials

| User | Password | Role | Scope |
|------|----------|------|-------|
| `admin` | `capstone` | admin | Full control (users, settings, security panel) |
| `doctor` | `Doctor@12345` | doctor | Read-only operational access (participants, alerts, dashboard) |

Change these via environment variables for non-demo deployments (see below).

### Environment Variables

Create a `.env` file in `backend/` to customize (all optional):

```env
# Database
DATABASE_USER=aware_user
DATABASE_PASSWORD=password

# API Keys (leave empty to disable key auth for local dev)
API_KEY_INGEST=your-ingest-key
API_KEY_ADMIN=your-admin-key

# Application-layer encryption (base64-encoded 32-byte AES-256 key)
DATA_ENCRYPTION_KEY_B64=your-base64-key

# Dashboard users
APP_ADMIN_USERNAME=admin
APP_ADMIN_PASSWORD=capstone
APP_DOCTOR_USERNAME=doctor
APP_DOCTOR_PASSWORD=Doctor@12345

# Auth settings
AUTH_SESSION_TTL_HOURS=12
AUTH_MAX_FAILED_ATTEMPTS=5
AUTH_LOCKOUT_MINUTES=15

# Password policy
PASSWORD_MIN_LENGTH=12
PASSWORD_REQUIRE_UPPERCASE=true
PASSWORD_REQUIRE_LOWERCASE=true
PASSWORD_REQUIRE_DIGIT=true
PASSWORD_REQUIRE_SPECIAL=true

# Signature engine
SIGNATURE_DB_USER=aware_user
SIGNATURE_DB_PASSWORD=password
LOOP_INTERVAL_SEC=300
LSTM_EPOCHS=4
```

Generate a new AES-256 encryption key:
```bash
python -c "import os, base64; print(base64.b64encode(os.urandom(32)).decode())"
```

---

## Mobile App Setup

### First-Time Setup

```bash
cd app
npm install
```

### Run on Emulator

1. Create and launch an Android emulator (AVD):
   - Android Studio -> Device Manager -> Create Device -> Start
   - Or via CLI:
     ```
     %LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -list-avds
     %LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -avd <AVD_NAME>
     ```

2. Build the APK:
   ```bash
   cd android
   .\gradlew.bat assembleDebug     # Windows
   ./gradlew assembleDebug         # macOS/Linux
   ```

3. Run:
   ```bash
   cd ..
   npx expo run:android
   ```

### Run on Physical Device

1. Enable **USB debugging** on the phone (Settings -> Developer options)
2. Verify ADB sees your device:
   ```bash
   adb devices
   ```
3. Build a debug APK:
   ```bash
   cd app/android
   .\gradlew.bat assembleDebug
   ```
4. Install:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
5. For a release build (no dev server dependency):
   ```bash
   .\gradlew.bat installRelease
   ```

### Connecting the App to the Backend

In the app's **Settings** screen, configure the server IP address to point to your backend:

- **Same machine (emulator):** `10.0.2.2:8080`
- **Physical device on same LAN:** `<your-pc-ip>:8080` (e.g., `192.168.10.8:8080`)

If you have set `API_KEY_INGEST` on the backend, enter the same key in the app's Settings screen under the API key field.

### Required Permissions

The app requires the following Android permissions (grant when prompted or via Settings):

| Permission | Purpose |
|-----------|---------|
| **Location** | GPS tracking for mobility analysis |
| **Usage Access** | App usage statistics (Settings -> Special access -> Usage access) |
| **Notification Access** | Notification metadata collection |
| **Activity Recognition** | Step counting |
| **Health Connect** | Wearable data (heart rate, sleep, steps, etc.) |

---

## Synthetic Data Generation

For testing, demo, or model training, generate realistic synthetic sensor data:

```bash
cd backend
python generate_aware_mysql_v2.py
```

Interactive prompts let you choose:
- **Mode:** `phone`, `watch`, or `both`
- **Signature focus:** `phone`, `watch`, or `both`
- **Live mode:** continuously inserts new data until stopped

Defaults: 10 participants, 60 days of history, with injected anomaly patterns (mobility withdrawal, night restlessness, interaction spikes, sleep disruption, etc.).

Device ID convention:
- Phone IDs: `phone_*`
- Watch IDs: `watch_*`
- Same participant can have both a phone and a watch.

---

## Training the LSTM Anomaly Detection Model

The LSTM autoencoder learns each participant's normal 24-hour behavioral pattern and detects anomalies via reconstruction error.

### One-Click Training

```bash
cd backend
python train_lstm_one_click.py
```

On Windows:
```
train_lstm_one_click.bat
```

### What It Does

1. Resets LSTM engine state (unless `--no-reset` is passed)
2. Rebuilds `hourly_features` from raw phone sensor tables
3. Trains a per-participant LSTM autoencoder model
4. Saves model artifacts to `backend/models/`:
   - `lstm_ae_<participant_id>.pt` -- trained model weights
   - `lstm_ae_<participant_id>_scaler.json` -- feature scaler parameters

### Requirements

- Sufficient hourly data per participant (at least several days of contiguous data)
- Python dependencies: `pip install -r requirements-ai.txt` and PyTorch

### Continuous Scoring

Once models are trained, the signature engine (running in Docker as `signature-engine-service`) automatically scores new hours against each participant's LSTM model. When reconstruction error exceeds the participant-specific threshold, alert code `LSTM_AE1` is generated alongside rule-based alerts.

---

## Behavioral Signatures

The system evaluates the following signature types per participant:

### Phone Signatures (Hourly)

| Code | Name | Description |
|------|------|-------------|
| DS1 | Mobility Withdrawal | Reduced travel distance + increased stationary behavior |
| DS2 | Sedentary Engagement | Low physical movement + elevated screen usage |
| AS1 | Night Restlessness | Elevated activity during biological rest hours |
| AS2 | Location Instability | Abrupt spatial context instability |
| SU1 | High Entropy + Movement Burst | Context variability + movement spikes |
| SU2 | High-Risk Location Exposure | Proximity to configured red zones (geofence) |
| SU3 | Phone Interaction Spike | Sudden surge in screen sessions or screen-on time |
| PS1 | Recent Anomaly Spike | Escalation in anomaly rate vs. prior week |
| BP1 | Activity + Sleep Variability | Instability in both movement and sleep timing rhythms |
| LSTM_AE1 | Sequence Anomaly | LSTM reconstruction error exceeds participant threshold |

### Watch Signatures (Daily)

| Code | Name | Description |
|------|------|-------------|
| WD1 | Reduced Activity (2 days) | Steps below baseline for 2 consecutive days |
| WD2 | Reduced Sleep + Elevated Night HR | Poor sleep combined with high nighttime heart rate |
| WA1 | Physiological Arousal | Elevated night HR or suppressed HRV proxy |
| WA2 | Sleep Fragmentation | Short sleep, fragmented sleep, or shifted timing |
| WB1 | Routine Instability | Increased variability in steps and/or sleep timing |
| WB2 | Elevated Activation Triad | Less sleep + more activity + elevated night HR |
| WSU3 | Multi-Stream Anomaly | 2+ wearable streams simultaneously anomalous |

---

## Security and Encryption

### Application-Layer Encryption
- **AES-256-GCM** with random 12-byte nonces and 128-bit authentication tags
- Encrypted fields: `participants.name`, `notifications.title`, `notifications.content`, `red_zones.name`, `accelerometer.{x,y,z}_enc`
- Encrypted values are prefixed with `enc:v1:` for identification
- Key loaded from `DATA_ENCRYPTION_KEY_B64` environment variable

### Database-Level Encryption (MySQL 8.0)
- Default table encryption: ON
- InnoDB redo/undo log encryption: ON
- Binary log encryption: ON
- Keyring-file-based key management

### Authentication
- **PBKDF2-SHA256** password hashing with 210,000 iterations, 16-byte salt
- **Session tokens**: 32 random bytes, SHA-256 hashed before storage
- **Login throttling**: configurable max failed attempts and lockout duration
- **API keys**: `API_KEY_INGEST` for sensor data endpoints, `API_KEY_ADMIN` for admin APIs

### RBAC Roles

| Role | Scope |
|------|-------|
| admin | Full control |
| doctor | Read-only participant monitoring |
| analyst | Read-only analytics |
| viewer | Read-only dashboard |
| ingest | Sensor data submission only |

### Key Rotation

```bash
cd backend
python rotate_keys.py
```

Rotates `API_KEY_INGEST`, `API_KEY_ADMIN`, and `DATA_ENCRYPTION_KEY_B64`. Restart containers after rotation.

### Verify Encryption

```bash
docker exec dp-mysql mysql -uroot -prootpassword aware_db \
  -e "SHOW VARIABLES LIKE 'default_table_encryption';"
```
Expected: `ON`

---

## API Reference

The backend exposes a REST API on port 8080. Base URL: `http://<host>:8080/api`

### Sensor Ingestion (Ingest Key)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/battery` | Submit battery reading |
| POST | `/screen` | Submit screen event |
| POST | `/accelerometer` | Submit accelerometer data |
| POST | `/gyroscope` | Submit gyroscope data |
| POST | `/pedometer` | Submit step count |
| POST | `/location` | Submit GPS coordinates |
| POST | `/notification` | Submit notification event |
| POST | `/wearable/heart-rate` | Submit heart rate |
| POST | `/wearable/steps` | Submit step data |
| POST | `/wearable/sleep` | Submit sleep session |

### Participant and Admin (Admin Key or Session)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/participants` | List all participants |
| GET | `/participants/:deviceId` | Get participant by device ID |
| POST | `/participants` | Create/update participant |
| PUT | `/participants/:participantId` | Update participant |
| GET | `/signature-alerts` | List behavioral alerts |
| GET | `/zones` | List red zones |
| POST | `/zones` | Create red zone |

### Auth

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/auth/login` | Login (returns session token) |
| POST | `/auth/logout` | Logout (revoke session) |
| GET | `/auth/me` | Get current user info |

### Connection Test

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/testing` | Health check (no auth required) |

---

## Troubleshooting

### Backend

| Problem | Solution |
|---------|----------|
| `docker compose up` fails | Ensure Docker Desktop is running. Check ports 3307/8080/8081 are free. |
| Backend can't connect to MySQL | Wait for MySQL health check to pass. Check `docker compose logs mysql`. |
| "Unauthorized" on API calls | Check your API key matches `API_KEY_INGEST` / `API_KEY_ADMIN` in `.env`. If no keys are set, ingest endpoints are open. |
| Schema tables missing | Backend auto-creates tables on startup. Check `docker compose logs backend` for migration errors. |
| Encryption not working | Ensure `DATA_ENCRYPTION_KEY_B64` is set and decodes to exactly 32 bytes. |
| Port 8080 already in use | On Windows: `netstat -ano \| findstr :8080` then `taskkill /F /PID <pid>` |

### Mobile App

| Problem | Solution |
|---------|----------|
| "Unsupported class file major version" | Ensure JDK 17. Set `org.gradle.java.home` in `app/android/gradle.properties`. |
| Red screen "Unable to load script" | Use Gradle-built APK (`assembleDebug`/`assembleRelease`), not Expo dev client. |
| Device not found by ADB | `adb kill-server && adb start-server`, replug USB, accept RSA prompt on device. |
| Profile shows "Unknown" / "N/A" | Ensure backend is running and app's server URL is correct in Settings. Check API key matches. |
| Sensors not collecting | Grant all required permissions. Ensure background service is enabled. |
| Usage stats show zero | Grant Usage Access permission, then restart the app. |
| App crashes on startup | Check `adb logcat` for errors. Ensure Android API 26+ (minSdk). |
| Health Connect data missing | Install Google Health Connect app. Grant all health permissions to the app. |

### Signature Engine

| Problem | Solution |
|---------|----------|
| No alerts generated | Ensure sufficient sensor data exists. Engine processes completed hours only. |
| LSTM alerts not appearing | Train models first with `train_lstm_one_click.py`. Need several days of contiguous hourly data per participant. |
| Engine container keeps restarting | Check `docker compose logs signature_engine`. Usually a DB connection issue. |

---

## Team

### Supervisors
- **Dr. Sami Zhioua** -- Cybersecurity Department, CCIT, UDST
- **Dr. Awad Mussa** -- Cybersecurity Department, CCIT, UDST

### Team Members
- **Ali Awadallah** (Data & Cyber Security)  -- Team Leader
- Abdalwahab Eltahir (Information Systems)
- Abdulrahman Babkir (Data & Cyber Security)
- Bara Al-Omari (Information Systems)
- Diya Alghuniem (Data & Cyber Security)
- Mumin Almaghrabi (Data & AI)
- Abdulaziz Al-Malki (Information Technology) 

---

## License

This project is for academic and research purposes at the University of Doha for Science and Technology. Reproduction or deployment must include credit to the original student team and supervisors.

---

**Contact:** 
60107679@udst.edu.qa (Dr. Sami Zhioua)
60301637@udst.edu.qa (Ali Awadallah)

<div align="center">
  <sub>&copy; 2025-2026 College of Computing and IT, University of Doha for Science and Technology</sub>
</div>
