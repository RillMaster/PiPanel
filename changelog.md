**🚀 Release Notes - Raspberry Controller**



*✨ New Features*

•Pi-hole v6 Support: Full integration of the new Pi-hole v6 API (secure management of SID/CSRF sessions).



• New "About" Screen: Added a dedicated section with developer information (RillMaster), a link to the GitHub repository, and the current application version.



*🎨 Interface \& Widgets*

• Widget Visual Redesign:

◦ Modernized design with rounded corners (16dp) and translucent backgrounds.



◦ Status indicators are now perfect circles for improved aesthetics.



• Improved Previews: The widget selector now displays a true preview of how the widgets will look (Stats, Pi-hole, WireGuard, Sensors).



• Toggle Optimization: Redesigned the button system for widgets to ensure immediate responsiveness with each click.



*🛠️ Technical Improvements \& Fixes*

• SSH Stability: Improved script execution on the Raspberry Pi using Heredocs Python, preventing errors related to special characters in passwords.



• Widget Reliability: Added a temporary lock after a manual action to prevent background updates from causing visual conflicts.



• Code Cleanup: Updated system icons to AutoMirrored versions and fixed various import bugs.



• Advanced Logs: Improved the diagnostic system to facilitate troubleshooting SSH connection issues.

