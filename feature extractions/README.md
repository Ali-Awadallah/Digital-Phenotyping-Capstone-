# Digital Phenotyping – Sensor-to-Mental-Health Pipeline

This repository contains a complete data-processing pipeline for turning raw smartphone sensor data (from the Beiwe / AWARE framework) into daily behavioral features and mental-health–related risk indicators.

## 📌 Overview

Smartphones continuously collect sensor data such as accelerometer, gyroscope, GPS, magnetometer, lock/unlock events, and proximity.  
This project converts these raw data streams into **daily behavioral summaries**, then computes simple **risk flags** that may indicate stress, poor sleep, anxiety-like patterns, or depressive-like patterns.

**Important:**  
This project **does not diagnose mental illness**. It only identifies behavioral *patterns* based on digital phenotyping research.

---

## 🚀 Features Extracted

The pipeline processes all available datasets:

- **Accelerometer** → movement level, inactivity ratio, total motion  
- **Gyroscope** → jitter/instability, phone handling  
- **Magnetometer** → environmental changes  
- **GPS** → mobility, location range, unique places  
- **Power state** → screen usage, unlock count, session durations  
- **Proximity** → phone usage near user  
- **Reachability** → WiFi/cellular switching, connectivity stability  
- **iOS logs** → battery & memory patterns  
- **Survey answers** → self-reported digital behavior

---

## 🧠 Mental-Health Risk Indicators

From the daily features, the system computes:

- `flag_low_activity`  
- `flag_high_unlocks`  
- `flag_sleep_disturbance`  
- `flag_low_mobility`  
- `flag_high_self_report`  

Then produces:

- `risk_score` (0–5)  
- `risk_level` (`low`, `moderate`, `high`)

These indicate how much the user’s behavior deviates from their own baseline.

---

## 📂 Folder Structure (Expected)

