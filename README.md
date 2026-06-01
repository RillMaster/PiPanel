<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="120" alt="PiPanel Logo"/>

# PiPanel

**Remote Raspberry Pi manager for Android**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-1.6-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Min SDK](https://img.shields.io/badge/Min_SDK-26-green?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)

</div>

---

## ✨ Features

### 🖥️ Dashboard
- Live **CPU temperature** with color-coded indicator
- Real-time **CPU & RAM** usage
- Quick status overview at a glance

### 🐳 Docker
- List all containers with status
- Start / Stop / Restart containers
- View container logs in real time

### 📡 Network Scanner
- Scan the local network via SSH + `nmap`
- Detects hostnames, IPs, MAC addresses and vendors
- Smart device icons (smartphone, laptop, router, Pi...)
- Auto-installs `nmap` if not present on the Pi

### 🌐 WireGuard
- Toggle WireGuard VPN directly from the app
- Peer status and connection info

### ⚡ GPIO
- Control GPIO pins remotely
- LED on/off with real-time feedback

### 🔌 Pi-hole
- Enable / Disable Pi-hole with one tap
- Home screen widget for quick toggle

### 💻 Terminal
- Full **VT100 / xterm-256color** terminal emulator
- PTY support with sticky modifier keys (Ctrl, Alt, etc.)
- SSH session directly in the app

### 🔔 Notifications
- CPU & RAM threshold alerts via **WorkManager**
- Background monitoring with configurable thresholds

### 🔐 Security
- **Biometric authentication** (fingerprint / face unlock)
- SSH credentials stored securely
- WireGuard VPN integration for remote access

### 🎨 UI / UX
- Material You design with **teal/cyan** palette
- Full **dark & light** theme support
- Drag-and-drop shortcut reordering
- Automatic update system with changelog

---

## 📋 Requirements

| Requirement | Details |
|---|---|
| Android | 8.0+ (API 26) |
| Raspberry Pi | Any model with SSH enabled |
| Network | WireGuard VPN recommended for remote access |
| Pi packages | `nmap` (auto-installed by the app if missing) |

---

## 🚀 Getting Started

### 1. Enable SSH on your Raspberry Pi

```bash
sudo systemctl enable ssh
sudo systemctl start ssh
```

### 2. (Optional) Set up WireGuard for remote access

Install WireGuard on your Pi and configure a peer for your phone. The app connects over VPN when you're away from home.

### 3. Install PiPanel

Download the latest APK from the [Releases](../../releases) page and install it on your Android device.

### 4. Configure the connection

On first launch, enter your Pi's:
- **IP address** (local or WireGuard)
- **SSH port** (default: `22`)
- **Username**
- **Password**

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| SSH | JSch |
| Background tasks | WorkManager |
| Auth | AndroidX Biometric |
| Terminal | Custom VT100/xterm-256color renderer |
| Drag & drop | `sh.calvin.reorderable` |

---

## 📁 Project Structure

```
app/src/main/java/com/rillmaster/pipanel/
├── MainActivity.kt              # Entry point, navigation
├── SshClient.kt                 # SSH connection manager
├── SettingsManager.kt           # Credentials & config storage
├── DashboardScreen.kt           # CPU / RAM / temp overview
├── DockerScreen.kt              # Docker container management
├── NetworkScannerScreen.kt      # Local network scanner
├── TerminalScreen.kt            # VT100 terminal emulator
├── GpioScreen.kt                # GPIO controls
├── WireGuardScreen.kt           # WireGuard toggle
├── PiholeScreen.kt              # Pi-hole toggle
└── ui/theme/                    # Material You theming
```

---

## 🔧 Building from Source

```bash
git clone https://github.com/RillMaster/PiPanel.git
cd PiPanel
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 License

```
MIT License — see LICENSE for details.
```

---

<div align="center">
Made with ❤️ by <a href="https://github.com/RillMaster">RillMaster</a>
</div>
