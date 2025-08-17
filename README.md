# BLE Serial (Android, Jetpack Compose)

Android app that communicates with an **ESP32** over Bluetooth Low Energy using the **Nordic UART Service (NUS)**.

- Scans for device name: **`Zobo`**
- Subscribes to **TX** (Notify) and writes to **RX**
- Uses new **API 33+** GATT overloads (`writeCharacteristic(..., byte[], ...)`, `writeDescriptor(descriptor, value)`) with a safe legacy fallback for Android 12 and older
- Simple UI in **Jetpack Compose**, log autoscrolls to the latest line

> 🇨🇿 Stručně: Aplikace pro komunikaci s ESP32 přes BLE (NUS). Hledá zařízení `Zobo`, zapisuje do RX, čte notifikace z TX, funguje na API 33+ i starších (fallback), log se automaticky posouvá dolů.

---

## Features
- **Scan & Connect** to device named `Zobo`
- **Text Send** – send a line to RX characteristic
- **Control Buttons** – send bytes to ESP32:
  - `10 (0x0A)` → **Green**
  - `20 (0x14)` → **Red**
  - `30 (0x1E)` → **Blue**
  - `40 (0x28)` → **All lights**
- **Live Log** with timestamps and auto-scroll
- **Disconnect** + **Clear log** actions

---

## BLE UUIDs (NUS)
- **Service**: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- **RX (Write)**: `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
- **TX (Notify)**: `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`
- **CCCD**: `00002902-0000-1000-8000-00805F9B34FB`

---

## Requirements
- **Android Studio** (Giraffe+) and **JDK 17** (Temurin recommended)
- Android device with BLE
- For Android **8–11**: Location must be enabled for BLE scanning
- ESP32 firmware advertising as **`Zobo`** and exposing NUS

---

## Build & Run

### Android Studio
1. Open this project folder.
2. Let Gradle sync.
3. Click **Run** and select your device.

### Command line
```bash
./gradlew assembleDebug
# APK will be in app/build/outputs/apk/debug/
```

---

## Permissions
Runtime permissions are requested in-app (depending on your targetSdk / Android version):

- Android 12+: `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`
- Legacy: `BLUETOOTH`, `BLUETOOTH_ADMIN`
- Android 8–11 also require **Location** to be ON for BLE scanning

---

## Usage
1. Turn on Bluetooth (and Location on Android 8–11).
2. Tap **Start scan** → the app connects to device named **Zobo**.
3. Use **Send** to send a text line, or press **Green/Red/Blue/Light** to send control bytes.
4. Observe incoming messages in **Log** (auto-scroll enabled).

---

## Notes for ESP32 Firmware
- After **disconnect**, make sure ESP32 restarts **advertising** so the app can reconnect:
```cpp
void onDisconnect(BLEServer* pServer) {
  pServer->getAdvertising()->start();
}
```
- When the Blue LED (command `30`) turns on, ESP32 may notify e.g. `LED_BLUE_ON` to confirm state.

---

## Troubleshooting
- **No notifications**: ensure CCCD is written. The app supports both new (API 33+) and legacy (≤ 32) descriptor writes.
- **No scan results on Android 8–11**: enable **Location**.
- **Cannot reconnect**: verify the ESP32 restarts advertising on disconnect.
- **SSH passphrase on each push**: add your key to the agent (`ssh-add ~/.ssh/id_ed25519`).

---

## License
MIT (you can replace with another if desired). Add a `LICENSE` file to specify the terms.
