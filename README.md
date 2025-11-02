<div align="center">
  <h1>🛡️ Privacy-Preserving Cybersecurity & Digital Phenotyping System</h1>
  <p><em>A Capstone Project at the University of Doha for Science and Technology (UDST)</em></p>
</div>

---

### 🏛️ **University**
**College of Computing & Information Technology – UDST**

### 👨‍🏫 **Supervisor**
**Dr. Sami Zhioua**

### 👥 **Team Members**
- **Ali Awadallah** (Group Leader)  
- Abdalwahab Eltahir  
- Abdulrahman Babkir  
- Bara Al Omari  
- Diya Alghaniem  
- Abdulaziz Al Malki  
- Mumin Almaghrabi  

---

## 📘 Overview

This project combines **cybersecurity intrusion detection** and **digital phenotyping** to identify behavioral risk indicators such as screen addiction, stress, or substance-use tendencies — **while preserving user privacy**.

Smartphones reveal behavioral patterns via passive data (screen time, app usage, etc.). However, such data is sensitive. Therefore, our system prioritizes **privacy-preserving collection and analysis** using modern cryptographic and AI techniques.

The solution integrates:
- 📱 **Mobile App** for privacy-aware data collection  
- ☁️ **Secure Cloud Backend** for encrypted data aggregation  
- 🧠 **AI Intrusion Detection Engine (IDS)** for anomaly detection and risk visualization  

---

## 🔐 Privacy-Preserving Technologies (PETs)

We employ multiple PETs to ensure sensitive behavioral data remains protected:

### 1️⃣ On-Device Feature Extraction
Raw data (e.g., screen logs, usage sessions) is processed **locally**. Only summary statistics (e.g., total screen time, app category distribution) are uploaded.  
This minimizes exposure and prevents re-identification.

### 2️⃣ Federated Learning (FL)
Each phone trains a **local model** on its own data. Only **model updates** are sent to the server, which aggregates them into a global model — without ever seeing any user's raw data.

### 3️⃣ Secure Aggregation & Multi-Party Computation (MPC)
Updates are **masked or encrypted** before aggregation, ensuring the server can only see **combined totals** and never individual contributions.

### 4️⃣ Differential Privacy (DP)
Adds controlled random noise to uploaded data, protecting individuals while preserving overall statistical trends.

### 5️⃣ Dynamic Consent & Transparency
Users can manage privacy settings:
- View what data is collected and why  
- Opt in/out of specific metrics  
- Request deletion or export  

### 6️⃣ Governance & Encryption
- **TLS (HTTPS)** for data in transit  
- **AES-256** for encryption at rest  
- Role-based access and audit logs  

---

## 🧱 System Architecture

```text
+-------------------+
|   Mobile Client   |
|-------------------|
| - On-device data  |
| - Feature extraction |
| - Consent UI       |
| - Local FL training|
+---------+---------+
          |
     TLS / OAuth2
          |
+---------v---------+
|   Secure Backend  |
|-------------------|
| - Pseudonymization |
| - Secure Aggregation |
| - Differential Privacy |
| - Encrypted Storage |
+---------+---------+
          |
    Aggregated Outputs
          |
+---------v---------+
|  Detection Engine |
|-------------------|
| - IDS / Anomaly ML |
| - Splunk SIEM Dash |
| - Explainability   |
+-------------------+
```

---

## 🚀 Key Features

✅ Privacy-preserving behavioral data collection  
✅ Secure API (TLS + OAuth/JWT)  
✅ Federated model training and aggregation  
✅ Explainable anomaly detection (Splunk Dashboard)  
✅ Granular consent and data control  

---

## 🧪 Current Progress (as of Fall 2025)

| Status | Task |
|:------:|------|
| ✅ | Literature Review on PETs and Digital Phenotyping |
| ✅ | System Architecture Design |
| 🧩 | Mobile Client Prototype (Feature Extraction) |
| 🧩 | Backend API with Secure Data Handling |
| 🔄 | Next: Implement Federated Learning + Secure Aggregation |

---

## ⚙️ Tools & Technologies

| Layer | Technologies |
|-------|---------------|
| Mobile | Kotlin (Android), Swift (iOS), TensorFlow Lite |
| Cloud Backend | Flask / FastAPI, PostgreSQL, JWT Auth, TLS |
| ML / IDS | TensorFlow Federated, PyTorch, Splunk |
| Privacy | Differential Privacy, Secure Aggregation Protocols |
| DevOps | Docker, GitHub Actions, CI/CD |

---

## 📊 Privacy vs Utility Trade-off

| PET | Privacy Strength | Model Utility Impact |
|-----|------------------|----------------------|
| On-Device Extraction | High | Minimal |
| Differential Privacy | Very High | Moderate noise |
| Federated Learning | Very High | Higher communication cost |
| Secure Aggregation | High | Slight latency |
| Dynamic Consent | Ethical/Legal | None |

---

## 🧭 Next Steps

1. Finalize privacy-preserving feature set  
2. Implement federated learning aggregation  
3. Build consent management dashboard  
4. Integrate Splunk explainable anomaly visualizations  
5. Pilot test with synthetic data  


---

## Demo App (Android - iOS Partially) — Setup, Run, Build

This repository includes a working Android demo under `DP_demo/demo` built with Expo + React Native and a few native hooks. The demo collects and displays:

- App usage for “Today” (Total screen time + Top apps with minutes and icons)
- Realtime “Screen Events” (SCREEN_ON / SCREEN_OFF)
- Location Tracking
- Accelerometer Tracking
- Gyroscope Tracking


It uses Android’s UsageStats API (via a small native module) and a manifest, BroadcastReceiver API for screen power events.

### Prerequisites

- Node.js 18+ and npm
- JDK 17 (required by React Native/AGP). Verify with `java -version`
- Android SDK + Platform Tools (ADB) via Android Studio
- Ensure `adb` is on your PATH or use the full path (e.g. `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb`)

### First‑time setup

```
cd DP_demo/demo
npm install

```

### Run on a emulator

1) Create and launch an emulator (AVD)

- Android Studio → Device Manager → Create Device → Start (▶)
- Or CLI:
  - List: `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -list-avds`
  - Start: `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -avd <AVD_NAME>`

2) Build a debug APK with embedded bundle

```
cd DP_demo/demo/android
./gradlew assembleDebug    # Windows: .\gradlew.bat assembleDebug
```

3) Run the app

```
cd ..
npx expo run:android
```

### Run on a physical device

1) Enable USB debugging on the phone (Developer options)

2) Verify ADB sees your device

```
adb devices
# if multiple devices, note your serial (e.g., R5CR704WGMK)
```

3) Build a debug APK with embedded JS bundle (runs offline)

```
cd android
./gradlew assembleDebug     # Windows: .\gradlew.bat assembleDebug
```

4) Install the APK

```
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

5) Open the app (“DP demo”).

### Grant usage access (for app usage)

On first run, the Home screen shows a banner if the permission is missing. Tap “Open Usage Access Settings” and enable for “DP demo”, or navigate manually:

Settings → Apps → Special access → Usage access → DP demo → Allow

### Troubleshooting

- Java error “Unsupported class file major version …” → Ensure JDK 17 and/or set `org.gradle.java.home` in `android/gradle.properties` to your JDK 17 path.
- Red screen “Unable to load script” → Ensure you installed the Gradle‑built APK (`assembleDebug`/`assembleRelease`), not a dev client.
- Device not found → `adb kill-server && adb start-server`, replug cable, accept RSA prompt, or use `--device` selection in Expo.
- Usage shows zero → After granting access, close the app and open it again and refresh usage data.

### Project structure (relevant parts)

```
DP_demo/
  demo/
    App.js                 # UI + usage/sensors wiring
    app.json               # expo config (android.package = com.dp.demo)
    android/
      app/
        src/main/AndroidManifest.xml    # includes PACKAGE_USAGE_STATS + screen receiver
        src/main/java/com/dp/demo/
          AppUsageModule.kt             # UsageStats bridge
          AppUsagePackage.kt
          ScreenEventsReceiver.java     # Power events receiver
          MainApplication.kt            # Registers packages/receivers
```

---

## 🧑‍💻 License

This project is for academic and research purposes at UDST.  
Reproduction or deployment must include credit to the original student team and supervisor.

---

## 📬 Contact

📧 **60301637@udst.edu.qa** (Ali Awadallah)  
📧 **60107679@udst.edu.qa** (Dr. Sami Zhioua)

<div align="center">
  <sub>© 2025 College of Computing & IT, University of Doha for Science and Technology</sub>
</div>
