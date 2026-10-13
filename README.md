# Smart Road Damage

Smart Road Damage is an advanced intelligent mobility safety system prototype that leverages an Android application, custom ESP32 hardware, on-device Machine Learning (PyTorch Lite), and Firebase to detect, classify, and map road anomalies (potholes, speedbreakers, etc.) in real-time.

## 🚀 Product Vision

The system is designed to provide a continuous loop of hazard intelligence:
1. A rider connects their phone to the sensing hardware via Bluetooth.
2. The user validates the device and calibration state through a pre-drive diagnostic cockpit.
3. The rider selects a destination and receives route choices ranked against known hazard intelligence.
4. During the ride, the phone and hardware continuously detect anomalies using a localized IMU-based AI model.
5. Post-ride, raw hazard reports are uploaded to Firebase, where a backend function clusters them into a canonical hazard map.
6. Future riders benefit from this growing crowdsourced hazard intelligence map.

## 🏗️ Architecture Stack

### 1. Android Application Layer
- **Language & UI:** Kotlin, Jetpack Compose (Neo-Brutalist design language).
- **On-Device ML:** PyTorch Lite (`anomaly_detector.ptl`) running a localized model on 30-sample IMU windows.
- **Maps & Routing:** Mappls SDK.
- **Transport:** Classic Bluetooth SPP for parsing ESP32 telemetry (Calibration, Inference, GPS, Sync packets).
- **Core Components:** 
  - `BluetoothService`: hardware bridge.
  - `CockpitViewModel`: live diagnostic and anomaly routing.
  - `InferenceEngine`: PyTorch runtime.
  - `AnomalyDetector`: GPS interpolation and anomaly enrichment.

### 2. Hardware Layer
- **Device:** ESP32 with IMU and GPS sensors.
- **Protocol:** Newline-delimited CSV packets over Classic Bluetooth SPP (`C`, `I`, `G`, `SYNCED`).

### 3. Backend & Cloud Layer
- **Authentication:** Firebase Auth (Google Sign-in via Credential Manager).
- **Database:** Cloud Firestore (Separated into `pothole_reports` for raw data and `potholes` for canonical clustered data).
- **Cloud Functions:** Backend logic for clustering rider reports and optional snap-to-road enrichment.

## 🛠️ Key Features
- **Hardware Bridge:** Robust BLE discovery, connection state management, and real-time sensor calibration.
- **Live Cockpit:** Pre-drive diagnostics (battery, voltage, temperature, GPS, IMU noise, calibration).
- **Route Risk Analysis:** Evaluates route safety by matching paths against canonical Firestore hazard data.
- **Offline-First AI:** Inference is performed completely locally on the device without requiring an active internet connection.
- **Resilient Uploads:** Background workers (via WorkManager) ensure raw hazard reports are reliably uploaded even after network interruptions.

## ⚠️ Important Notes & Current Status

This project is currently in a **serious controlled-testing stage**. It is an architecture-ready field system, not a final public release.

* **API Keys:** Mappls SDK keys (REST, map SDK, Atlas client ID/secret) are currently hardcoded in `SmartRoadApplication.kt`. These **must** be extracted and secured (e.g., via `local.properties` or a secrets manager) before production deployment.
* **Bluetooth Requirements:** The app requires runtime permissions for Bluetooth Scan/Connect (Android 12+) and Fine/Coarse Location (Android 11 and below).
* **Experimental Features:** Lane-side detection logic and camera-assisted vision fusion are present in the data models but are considered experimental and pending further field calibration.
* **Firmware Contract:** The Android app expects very specific CSV packet formats from the ESP32 (e.g., exactly 20 fields for `C` packets). Any changes to the ESP32 firmware output format will require corresponding updates to `CPacketParser.kt` and `IPacketParser.kt`.

