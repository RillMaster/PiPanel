# Changelog

All notable changes to PiPanel are documented here.

---

## [1.5.2]

### ✨ Added

#### 👤 Connection Profiles
- New **Connection Profiles** system — save and manage multiple Pi configurations
- Each profile stores: name, IP/hostname, SSH port, username, password or key
- Switch between profiles from a dedicated menu
- Profiles stored locally with DataStore

#### ⚡ Customizable Shortcuts
- Create custom shortcuts with a name, icon and SSH command
- **Macros** support — chain multiple commands in a single tap
- Drag-and-drop reordering (same as existing shortcuts)

#### 📐 Tablet / Landscape Mode
- Adaptive layout using `WindowSizeClass`
- Navigation rail + side panel in landscape and tablet mode
- Terminal and all screens properly adapt to wide layouts

#### 🔒 WireGuard Detailed Status
- New detailed WireGuard view — parsed output of `sudo wg show`
- Displays: connected peers, last handshake, transferred data (rx/tx), allowed IPs
- Auto-refresh every 30 seconds

#### 📡 Network Scanner
- New **Network Scanner** screen — scans the local network via SSH + `nmap`
- Auto-detects the active subnet from `eth0` (no manual config needed)
- Displays hostname, IP address, MAC address and vendor for each device
- Smart device icons based on hostname/vendor (smartphone, laptop, router, Raspberry Pi...)
- Auto-installs `nmap` on the Pi if not already present
- Shows device count in the top bar subtitle
- Error message displayed if SSH command fails (instead of silent empty list)

#### 🕐 Cron Scheduler
- New **Cron Scheduler** screen — read, add and delete cron jobs via SSH
- Separate input fields for minute, hour, day, month, weekday and command
- Human-readable preview (e.g. *"Every day at 3:00 AM"*)

#### ⚙️ Service Manager
- New **Service Manager** screen — manage `systemd` services directly from the app
- View all active services with their current status
- **Start / Stop / Restart** any service with one tap
- Real-time status refresh
- Color-coded indicators (active, inactive, failed)

#### 📁 File Manager
- **Upload files** from your Android device to the Pi via SFTP
- **Download files** from the Pi to your device
- **Integrated file editor** — edit text files (configs, scripts...) directly in the app
- Syntax-aware editing for common file types (`.conf`, `.yml`, `.sh`, `.py`...)

---

## [1.5.3]

### ✨ Added

#### 🗄️ Local Metrics Database (Room)
- System metrics (CPU, RAM, temperature) are now **stored locally in a Room database**
- Collected every 15 minutes by the monitoring worker
- 7-day retention with automatic cleanup

#### 📊 24h Charts
- New **Charts** screen — 24h history graphs powered by the local database
- CPU % + temperature curves (dual series) and RAM usage graph
- Accessible from the navigation drawer and the dashboard Services section

#### 🎨 Animated Theme Transition
- Theme changes (light/dark) are now **smoothly animated** instead of instant

#### 🧩 Customizable Dashboard
- **Reorder dashboard sections** (Stats, GPIO, Services, Terminal) via drag-and-drop
- **Hide/show sections** individually — layout persisted across restarts
- New edit mode (pencil icon in the top bar)

#### 🏠 "At a Glance" Card
- New summary card on the dashboard: Pi-hole status (blocked domains), Docker container count
- **Quick action button** — reboot the Pi with confirmation dialog

#### 🌡️ CPU Temperature Widget
- New home screen widget showing live CPU temperature
- Turns red and sends an alert notification above **70°C**

#### 🐳 Docker Widget
- New home screen widget showing **running/total containers**
- Tap to open the Docker screen directly

### 🔔 Notifications
- **Disk space alerts** — configurable threshold (default 85%), checks `df` usage
- **Critical services alerts** — monitors systemd services (`systemctl is-active`), customizable service list (default: ssh, docker, pihole-FTL)
- New dedicated notification channel for service alerts
- Pi-hole widget now shows a "Switching…" pending state during toggles

---

## [1.5.4]

### ✨ Added

#### 📈 Real-Time Charts (Live Dashboard)
- Stat cards (CPU, Temperature) now feature live-scrolling mini-charts powered by Vico
- Much easier to spot a load spike or overheating at a glance than a static number

#### ⚡ Quick Action Tiles (Android Quick Settings)
- Add custom Quick Settings tiles to trigger SSH commands without opening the app
- Example: a "Restart Web Server" or "Shutdown Pi" tile accessible from anywhere

#### 📶 Raspberry Pi Wi-Fi Management
- New tool to scan nearby Wi-Fi networks from the Pi and connect to a new one
- Handy when relocating the Pi without needing a monitor to configure Wi-Fi manually

#### 🖼️ Media Gallery & Video Player (SFTP)
- Built-in lightweight image viewer and video player in the File Manager
- Thumbnail previews for image folders for easier navigation

#### 🖥️ Terminal Themes & Customization
- New color themes: Dracula, Solarized, Monokai
- Advanced auto-completion (bash-completion style)
- Reverse search history (mobile Ctrl+R)

#### 📦 Configuration Export/Import
- Export all profiles, shortcuts and SSH keys to an encrypted JSON file
- Import configuration on a new device, or share your setup across devices

## [Previous versions]

> See Git history for earlier changes.