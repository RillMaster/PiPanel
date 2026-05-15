### Summary: Localization improvements and initialization bug fixes
This update focuses on internationalizing the application and fixing premature background alerts.

#### 1. Full Localization (i18n)
- **Onboarding & Configuration:** Moved all hardcoded strings from `OnboardingActivity.kt` and `Piholeconfigscreen.kt` to resource files. The setup process now fully supports English and French based on system settings.
- **Localized SSH Errors:** Refactored `SshClient.kt` to use string resources for connection errors (timeout, auth failure, host unreachable). This fixes the issue where error messages remained in French even when the system language was English.
- **Background Services:** Localized notification channel names, descriptions, and alert messages in `MonitoringWorker`, `NotificationHelper`, and `WidgetUpdateService`.
- **General:** Fixed various UI components where labels and buttons had mixed languages.

#### 2. Features & Improvements
- **WireGuard:** Added QR code client generator.
- **UFW Firewall:** New firewall management page.
- **Logs Viewer:** New page for viewing real-time system logs.
- **File Manager:** New file management page.
- **Network Speed:** New tab in Advanced Monitoring.

#### 3. Bug Fixes
- **Premature Monitoring Alerts:** Fixed a bug where the `MonitoringWorker` would trigger a "Raspberry Pi unreachable" notification immediately after installation. It now checks if the SSH configuration is completed before attempting any connection.
- **Fixed several other bugs** across the application.

#### 4. Files Modified
- `strings.xml` & `values-fr/strings.xml`: Added comprehensive localized strings.
- `SshClient.kt`: Integrated `Context` for localized error parsing.
- `OnboardingActivity.kt` & `Piholeconfigscreen.kt`: UI updated to use `stringResource`.
- `MonitoringWorker.kt`: Added configuration check and localized alerts.
- `WidgetUpdateService.kt` & `TerminalScreen.kt`: Updated for multi-language support.
