package com.rillmaster.pipanel

import android.content.Context
import com.rillmaster.pipanel.PiProfile
import com.rillmaster.pipanel.model.Screen
import com.rillmaster.pipanel.SshShortcut
import com.rillmaster.pipanel.ssh.SshKey
import com.rillmaster.pipanel.managers.AdvancedSettingsManager
import com.rillmaster.pipanel.managers.AppearanceManager
import com.rillmaster.pipanel.managers.NotificationSettingsManager
import com.rillmaster.pipanel.managers.ProfileManager
import com.rillmaster.pipanel.managers.SshKeyManager
import com.rillmaster.pipanel.managers.TerminalSettingsManager
import com.rillmaster.pipanel.managers.VpnManager

class SettingsManager(context: Context) {
    // Initialize all managers
    private val profileManager = ProfileManager(context)
    private val sshKeyManager = SshKeyManager(context)
    private val vpnManager = VpnManager(context)
    private val notificationSettingsManager = NotificationSettingsManager(context, profileManager)
    private val appearanceManager = AppearanceManager(context)
    private val terminalSettingsManager = TerminalSettingsManager(context, profileManager)
    private val advancedSettingsManager = AdvancedSettingsManager(context, profileManager)

    // ── Premier lancement ─────────────────────────────────────────────────────
    var isFirstLaunch: Boolean
        get() = profileManager.isFirstLaunch
        set(value) { profileManager.isFirstLaunch = value }

    // ── Profils de connexion ──────────────────────────────────────────────────
    var currentProfileId: String?
        get() = profileManager.currentProfileId
        set(value) { profileManager.currentProfileId = value }

    var profiles: List<PiProfile>
        get() = profileManager.profiles
        set(value) { profileManager.profiles = value }

    fun getCurrentProfile(): PiProfile? {
        return profileManager.getCurrentProfile()
    }

    // Proxy properties for backward compatibility and easy access to current profile
    var host: String
        get() = profileManager.host
        set(value) { profileManager.host = value }

    var port: Int
        get() = profileManager.port
        set(value) { profileManager.port = value }

    var username: String
        get() = profileManager.username
        set(value) { profileManager.username = value }

    var password: String
        get() = profileManager.password
        set(value) { profileManager.password = value }

    var piHolePassword: String
        get() = profileManager.piHolePassword
        set(value) { profileManager.piHolePassword = value }

    /** Clé privée SSH du profil courant (résolue via sshKeyId ou legacy). */
    var privateKey: String
        get() {
            val profile = profileManager.getCurrentProfile() ?: return ""
            profile.sshKeyId?.let { id ->
                sshKeyManager.findKeyById(id)?.let { return it.privateKey }
            }
            return profile.privateKey
        }
        set(value) { profileManager.updateCurrentProfile { it.copy(privateKey = value, sshKeyId = null) } }

    /** Passphrase de la clé privée du profil courant. */
    var keyPassphrase: String
        get() = profileManager.keyPassphrase
        set(value) { profileManager.keyPassphrase = value }

    /** True si le profil courant s'authentifie par clé SSH. */
    val useSshKey: Boolean
        get() = profileManager.useSshKey

    fun addProfile(profile: PiProfile) {
        profileManager.addProfile(profile)
    }

    fun deleteProfile(id: String) {
        profileManager.deleteProfile(id)
    }

    // ── Clés SSH ─────────────────────────────────────────────────────────────
    var sshKeys: List<SshKey>
        get() = sshKeyManager.keys
        set(value) { sshKeyManager.keys = value }

    // ── WireGuard Local ──────────────────────────────────────────────────────
    var vpnPrivateKey: String
        get() = vpnManager.vpnPrivateKey
        set(value) { vpnManager.vpnPrivateKey = value }

    var vpnPublicKey: String
        get() = vpnManager.vpnPublicKey
        set(value) { vpnManager.vpnPublicKey = value }

    var vpnConfig: String
        get() = vpnManager.vpnConfig
        set(value) { vpnManager.vpnConfig = value }

    var vpnInterfaceAddresses: String
        get() = vpnManager.vpnInterfaceAddresses
        set(value) { vpnManager.vpnInterfaceAddresses = value }

    var vpnInterfaceListenPort: Int
        get() = vpnManager.vpnInterfaceListenPort
        set(value) { vpnManager.vpnInterfaceListenPort = value }

    var vpnInterfaceDns: String
        get() = vpnManager.vpnInterfaceDns
        set(value) { vpnManager.vpnInterfaceDns = value }

    var vpnInterfaceMtu: Int
        get() = vpnManager.vpnInterfaceMtu
        set(value) { vpnManager.vpnInterfaceMtu = value }

    var vpnPeerPublicKey: String
        get() = vpnManager.vpnPeerPublicKey
        set(value) { vpnManager.vpnPeerPublicKey = value }

    var vpnPeerPresharedKey: String
        get() = vpnManager.vpnPeerPresharedKey
        set(value) { vpnManager.vpnPeerPresharedKey = value }

    var vpnPeerPersistentKeepalive: Int
        get() = vpnManager.vpnPeerPersistentKeepalive
        set(value) { vpnManager.vpnPeerPersistentKeepalive = value }

    var vpnPeerEndpoint: String
        get() = vpnManager.vpnPeerEndpoint
        set(value) { vpnManager.vpnPeerEndpoint = value }

    var vpnPeerAllowedIPs: String
        get() = vpnManager.vpnPeerAllowedIPs
        set(value) { vpnManager.vpnPeerAllowedIPs = value }

    // ── Pi-hole (Global settings) ───────────────────────────────────────────
    var piHoleAutoRefresh: Boolean
        get() = advancedSettingsManager.piHoleAutoRefresh
        set(value) { advancedSettingsManager.piHoleAutoRefresh = value }

    var piHoleRefreshDelaySec: Int
        get() = advancedSettingsManager.piHoleRefreshDelaySec
        set(value) { advancedSettingsManager.piHoleRefreshDelaySec = value }

    // ── Timeouts & rafraîchissement ───────────────────────────────────────────
    var sshTimeoutMs: Int
        get() = advancedSettingsManager.sshTimeoutMs
        set(value) { advancedSettingsManager.sshTimeoutMs = value }

    var tempRefreshMs: Int
        get() = advancedSettingsManager.tempRefreshMs
        set(value) { advancedSettingsManager.tempRefreshMs = value }

    // ── Thème & sécurité ──────────────────────────────────────────────────────
    var theme: String
        get() = appearanceManager.theme
        set(value) { appearanceManager.theme = value }

    var biometricEnabled: Boolean
        get() = appearanceManager.biometricEnabled
        set(value) { appearanceManager.biometricEnabled = value }

    var backgroundActivityEnabled: Boolean
        get() = appearanceManager.backgroundActivityEnabled
        set(value) { appearanceManager.backgroundActivityEnabled = value }

    var monitoringServiceEnabled: Boolean
        get() = appearanceManager.monitoringServiceEnabled
        set(value) { appearanceManager.monitoringServiceEnabled = value }

    // ── Raccourcis terminal ───────────────────────────────────────────────────
    var sshShortcuts: List<SshShortcut>
        get() = terminalSettingsManager.sshShortcuts
        set(value) { terminalSettingsManager.sshShortcuts = value }

    fun defaultSshShortcuts() = terminalSettingsManager.defaultSshShortcuts()

    // Keep legacy property for compatibility if needed, but it will now use sshShortcuts
    var shortcuts: List<Pair<String, String>>
        get() = terminalSettingsManager.shortcuts
        set(value) { terminalSettingsManager.shortcuts = value }

    // ── Tuiles Quick Settings (3 slots) ────────────────────────────────────
    /** ID du raccourci assigné au slot de tuile [slot] (0..2), null = position. */
    fun getTileShortcutId(slot: Int): String? {
        return null
    }

    fun setTileShortcutId(slot: Int, id: String?) {
    }

    /** Raccourci résolu pour un slot : ID explicitement assigné, sinon le Nième raccourci. */
    fun getTileShortcut(slot: Int): SshShortcut? {
        return null
    }

    // ── Thème Terminal ────────────────────────────────────────────────────────
    var terminalThemeName: String
        get() = terminalSettingsManager.terminalThemeName
        set(value) { terminalSettingsManager.terminalThemeName = value }

    fun loadTerminalTheme() {
        terminalSettingsManager.loadTerminalTheme()
    }

    // ── Favoris gestionnaire de fichiers ─────────────────────────────────────
    var fileManagerBookmarks: List<String>
        get() = advancedSettingsManager.fileManagerBookmarks
        set(value) { advancedSettingsManager.fileManagerBookmarks = value }

    // ── Planifications GPIO ───────────────────────────────────────────────────
    var gpioSchedules: List<GpioSchedule>
        get() = advancedSettingsManager.gpioSchedules
        set(value) { advancedSettingsManager.gpioSchedules = value }

    // ── Couleurs des graphiques ────────────────────────────────────────────────
    fun getChartColor(key: String, defaultHex: String): String {
        return advancedSettingsManager.getChartColor(key, defaultHex)
    }

    fun setChartColor(key: String, hex: String) {
        advancedSettingsManager.setChartColor(key, hex)
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  Notifications push
    // ════════════════════════════════════════════════════════════════════════════

    /** Active ou désactive toutes les notifications. */
    var notificationsEnabled: Boolean
        get() = notificationSettingsManager.notificationsEnabled
        set(value) { notificationSettingsManager.notificationsEnabled = value }

    // ── CPU ───────────────────────────────────────────────────────────────────
    /** Active les alertes CPU. */
    var cpuAlertsEnabled: Boolean
        get() = notificationSettingsManager.cpuAlertsEnabled
        set(value) { notificationSettingsManager.cpuAlertsEnabled = value }

    /** Seuil CPU en pourcentage (0-100) déclenchant une alerte. */
    var cpuThreshold: Int
        get() = notificationSettingsManager.cpuThreshold
        set(value) { notificationSettingsManager.cpuThreshold = value }

    // ── RAM ───────────────────────────────────────────────────────────────────
    /** Active les alertes RAM. */
    var ramAlertsEnabled: Boolean
        get() = notificationSettingsManager.ramAlertsEnabled
        set(value) { notificationSettingsManager.ramAlertsEnabled = value }

    /** Seuil RAM en pourcentage (0-100) déclenchant une alerte. */
    var ramThreshold: Int
        get() = notificationSettingsManager.ramThreshold
        set(value) { notificationSettingsManager.ramThreshold = value }

    // ── Watchdog ──────────────────────────────────────────────────────────────
    /** Active l'alerte si le Pi devient injoignable. */
    var watchdogEnabled: Boolean
        get() = notificationSettingsManager.watchdogEnabled
        set(value) { notificationSettingsManager.watchdogEnabled = value }

    /** Intervalle en secondes entre chaque tentative de contact du Pi. */
    var watchdogIntervalSeconds: Int
        get() = notificationSettingsManager.watchdogIntervalSeconds
        set(value) { notificationSettingsManager.watchdogIntervalSeconds = value }

    // ── Docker ────────────────────────────────────────────────────────────────
    /** Active les alertes quand un conteneur Docker s'arrête. */
    var dockerAlertsEnabled: Boolean
        get() = notificationSettingsManager.dockerAlertsEnabled
        set(value) { notificationSettingsManager.dockerAlertsEnabled = value }

    // ── Disque ────────────────────────────────────────────────────────────────
    /** Active les alertes d'espace disque sur la partition racine. */
    var diskAlertsEnabled: Boolean
        get() = notificationSettingsManager.diskAlertsEnabled
        set(value) { notificationSettingsManager.diskAlertsEnabled = value }

    /** Seuil d'utilisation disque en pourcentage (0-100) déclenchant une alerte. */
    var diskThreshold: Int
        get() = notificationSettingsManager.diskThreshold
        set(value) { notificationSettingsManager.diskThreshold = value }

    // ── Services critiques ────────────────────────────────────────────────
    /** Active la surveillance des services systemd critiques. */
    var serviceAlertsEnabled: Boolean
        get() = notificationSettingsManager.serviceAlertsEnabled
        set(value) { notificationSettingsManager.serviceAlertsEnabled = value }

    /** Liste CSV des services systemd à surveiller (ex : "ssh,docker,pihole-FTL"). */
    var criticalServices: String
        get() = notificationSettingsManager.criticalServices
        set(value) { notificationSettingsManager.criticalServices = value }

    /** Liste normalisée des services critiques (CSV → List, sans espaces vides). */
    val criticalServicesList: List<String>
        get() = notificationSettingsManager.criticalServicesList

    // ── Détection des services ────────────────────────────────────────────
    fun isServiceInstalled(screen: Screen): Boolean {
        return notificationSettingsManager.isServiceInstalled(screen)
    }

    fun setServiceInstalled(screen: Screen, installed: Boolean) {
        notificationSettingsManager.setServiceInstalled(screen, installed)
    }

    var lastServiceScan: Long
        get() = notificationSettingsManager.lastServiceScan
        set(value) { notificationSettingsManager.lastServiceScan = value }

    // ── Historique des commandes ──────────────────────────────────────────────
    fun getCommandHistory(profileId: String): List<String> {
        return profileManager.getCommandHistory(profileId)
    }

    fun saveCommandHistory(profileId: String, history: List<String>) {
        profileManager.saveCommandHistory(profileId, history)
    }

    fun isConfigured(): Boolean = host.isNotEmpty() && username.isNotEmpty()
}