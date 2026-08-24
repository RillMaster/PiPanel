# Changelog

All notable changes to PiPanel are documented here.

---

## [Unreleased]

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

## [Previous versions]

> See Git history for earlier changes.