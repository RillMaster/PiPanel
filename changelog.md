🚀 WireGuard Management Update
This update provides a complete management interface for your WireGuard tunnels on Raspberry Pi.

✨ New Features:
• Full Peer Management: You can now add, rename, and delete WireGuard clients directly from the application.

• Name Identification: Custom client names are now persistent. They are stored and read via comments (# Name) directly in the /etc/wireguard/wg0.conf file.

Smart Configuration: When creating a client, the application automatically generates the private key, public key, and assigns the next available IP address.

• Custom Port Support: Improved endpoint management to support specific ports (useful for NAT/port forwarding).

🛠 Technical Improvements:

Optimized Python Scripts: Use remote Python scripts to manipulate configuration files without risk of corruption.

• SSH Robustness: Fixed variable interpolation errors in remote commands.

• Hot Restart: After each modification (addition/deletion), the WireGuard service is cleanly restarted to apply the changes without losing the configuration.

🎨 Interface & UX:

• Loading Overlay: Added a progress screen when restarting WireGuard to prevent simultaneous actions.

• Visual Feedback: Notifications (Snackbar) to confirm successful operations (renaming, deletion, etc.).

• Real-Time Statistics: Improved monitoring of traffic (upload/download) and client connection status.