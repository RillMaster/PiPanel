<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.webp" width="240" alt="PiPanel Logo"/>

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
- **Customizable layout** — reorder sections via drag-and-drop, hide/show individually
- **"At a Glance" card** — Pi-hole status, Docker container count, one-tap Pi reboot
- Smooth **animated theme transitions** (light/dark)

### 📊 History & Charts
- **24h charts** for CPU, temperature and RAM (powered by local Room DB)
- Metrics collected every 15 minutes, 7-day retention

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

### 📱 Home Screen Widgets
- **Pi-hole** quick toggle
- **CPU temperature** with alert above 70°C
- **Docker** running/total container counter
- **Stats** and **Sensor** widgets

### 💻 Terminal
- Full **VT100 / xterm-256color** terminal emulator
- PTY support with sticky modifier keys (Ctrl, Alt, etc.)
- SSH session directly in the app

### 🔔 Notifications & Monitoring
- CPU & RAM threshold alerts via **WorkManager**
- **Disk space alerts** with configurable threshold
- **Systemd services monitoring** (customizable list)
- **Background metrics collection** every 15 minutes
- **Quick Settings Tiles** to trigger SSH shortcuts instantly

### 🛠️ System Tools
- **Cron Job Manager**: Add, edit and delete scheduled tasks
- **Wi-Fi Management**: Scan and connect your Pi to local networks
- **UFW Firewall**: Manage rules and status
- **Fail2Ban**: Monitor security logs and bans
- **Remote File Transfer**: Background SFTP uploads/downloads

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
| SSH / SFTP | JSch |
| Background tasks | WorkManager |
| Widgets | Glance |
| Charts | Vico |
| Local database | Room |
| Dependency Injection | Manual (Singleton providers) |

---

## 📁 Project Structure

```
app/src/main/java/com/rillmaster/pipanel/
├── ui/                              # UI Layer
│   ├── screens/                     # App settings & secondary screens
│   ├── viewmodels/                  # ViewModels for complex states
│   ├── components/                  # Reusable UI components
│   ├── theme/                       # Material 3 & dynamic colors
│   └── terminal/                    # VT100 terminal engine
│
├── ssh/                             # SSH Key management
├── data/db/                         # Room metrics database
├── model/                           # Domain models
├── update/                          # Update system (OTA)
│
├── MainActivity.kt                  # Root entry & Navigation
├── OnboardingActivity.kt            # Setup flow
│
├── SshClient.kt                     # SSH/SFTP core implementation
├── SettingsManager.kt               # Secure credentials storage
│
├── MonitoringScreen.kt              # Resource monitoring (CPU/RAM/Temp)
├── DockerScreen.kt                  # Docker container management
├── TerminalScreen.kt                # Full SSH terminal
├── FileManagerScreen.kt             # Remote file browser & editor
├── NetworkScannerScreen.kt          # SSH + nmap discovery
│
├── ServicesScreen.kt                # systemd service manager
├── UfwScreen.kt                     # Firewall control
├── Piholescreen.kt                  # Pi-hole integration
├── Wireguardscreen.kt               # VPN management
│
├── CronSchedulerScreen.kt           # Task scheduling (Crontab)
├── GpioScheduleScreen.kt            # GPIO & PWM control
├── WifiManagementScreen.kt          # Wi-Fi configuration
│
├── MonitoringWorker.kt              # Background stats collection
├── FileTransferWorker.kt            # Background SFTP transfers
├── WidgetUpdateService.kt           # Home screen sync service
│
└── *Widget.kt                       # Android Home Screen Widgets (Glance)
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
