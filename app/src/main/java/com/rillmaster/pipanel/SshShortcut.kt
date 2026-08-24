package com.rillmaster.pipanel

import java.util.UUID

data class SshShortcut(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val icon: String = "Terminal", // Icon name like "Terminal", "Refresh", "Power", etc.
    val commands: List<String>,    // List of commands for macros
    val color: Long = 0xFF39FF14   // Default terminal green
) {
    val isMacro: Boolean get() = commands.size > 1
}
