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

---

## [1.5.5]

### ✨ Added

#### 📊 New Tracked Metrics
- **Disk I/O** chart — read/write speeds in MB/s with adaptive precision (3 decimals) so even small speeds stay visible
- **Network speed** chart — download/upload rates in kB/s
- Storage and network throughput (RX/TX) are now tracked over 24h
- The Room database now also stores network and disk measurements, collected every 15 minutes by the background worker
- The SSH Python script on the Pi now parses `/proc/net/dev` and `/proc/diskstats`

#### 🎨 Chart Customization (Edit Mode)
- New **palette icon** in the top bar to open color settings
- Custom **HSV/RGB color picker** — circular color wheel with a brightness slider, draggable square cursor, live preview, and OK / Cancel buttons
- **Per-curve colors** (e.g. CPU vs Temperature), persisted across restarts
- Drag-and-drop reordering of charts

#### 🔒 Built-in WireGuard VPN
- Official **WireGuard SDK** integrated directly in PiPanel — no external app needed
- **Auto-configuration** — one button generates the keys and adds you as a client on the Pi via SSH
- Smart connection: connects when the app opens, disconnects when it closes to save battery

#### 🌐 SSH Connection Multiplexing
- New `SshSessionManager` — a single shared SSH session used by widgets, terminal and stats screens (instead of ~10 simultaneous connections)
- Fixes Raspberry Pi crashes caused by too many concurrent connections

#### 💻 SSH Terminal
- Floating **D-Pad** for easy navigation in nano or htop
- **Suggestion bar** based on history and common Linux commands
- **Haptic feedback** on special keys
- **Landscape mode**: compact interface that keeps the terminal visible with the keyboard open
- Better **ANSI color** support (files, folders) for a more authentic Linux look

#### ✨ Premium UI & Animations
- Staggered slide-up entrance animations
- Tiles bounce slightly on touch
- Progress bars (CPU, RAM, Disk) glide instead of jumping; the "Online" dot pulses subtly

### 🔄 Changed

- **Charts look**: gradients under curves and interactive touch tooltips showing exact values with units (%, °C, MB/s, kB/s…), without truncation
- **Smooth scrolling**: charts no longer "jump" on each new point — linear right-to-left windowing
- **Smart collection**: monitoring now records data even when notifications are disabled
- **Cleaner changelog parser**: only the latest version's notes are displayed instead of the whole file
- Heavy terminal computations moved to the background — no more freezes with large outputs
- Cleaned up notifications: removed spurious alerts during the setup phase
- Removed duplicate "Last login" messages at terminal startup

### 🐛 Fixed

- Critical crash when opening charts with no data yet (`Series can't be empty`)
- X-axis showing scientific notation (e.g. `1.7E12`) — now displays readable `HH:mm` times (e.g. 14:30)
- Terminal keyboard bug preventing commands from being typed (input field fully rewritten)
- **Room**: database bumped to **version 3** to prevent migration crashes
- Crash when upgrading from an older version (empty fields in profiles)

## [Previous versions]

> See Git history for earlier changes.
