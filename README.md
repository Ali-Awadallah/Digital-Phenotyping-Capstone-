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
