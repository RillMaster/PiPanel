package com.rillmaster.pipanel.model

// ══════════════════════════════════════════════════════════════════════════════
//  MainApp — gestion de la navigation entre écrans
// ══════════════════════════════════════════════════════════════════════════════
enum class Screen {
    CONTROL, SETTINGS, TERMINAL, DOCKER, MONITORING,
    PIHOLE, PIHOLE_CONFIG, WIREGUARD, NOTIFS, PWM, GPIO_PLANNER, SENSORS, ABOUT,
    EASTER_EGG_OCTOPUS, LOGS_VIEWER, FAIL2BAN, UFW, FILE_MANAGER, SERVICES, PROFILES, NETWORK_SCANNER, CRON_SCHEDULER, CHARTS
}
