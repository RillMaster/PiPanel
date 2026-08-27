package com.rillmaster.pipanel.model

import android.content.Context
import android.util.Base64
import android.util.Log
import com.rillmaster.pipanel.SshClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════════════════════════════════════════════
//  Modèle de données pour les stats système
// ═══════════════════════════════════════════════════════════════════════════════
data class SystemStats(
    val tempCelsius: Double,
    val cpuPercent : Int,
    val ramUsedMb  : Int,
    val ramTotalMb : Int,
    val diskReadBytes: Long = 0L,
    val diskWriteBytes: Long = 0L,
    val netRxBytes: Long = 0L,
    val netTxBytes: Long = 0L
)

private val SYSTEM_STATS_SCRIPT = """
import re, time

meminfo = open('/proc/meminfo').read()
def mi(k):
    m = re.search(r'^' + k + r':\s+(\d+)', meminfo, re.MULTILINE)
    return int(m.group(1)) if m else 0

mem_total = mi('MemTotal')
mem_avail = mi('MemAvailable')
mem_used  = mem_total - mem_avail

def read_cpu():
    vals = list(map(int, open('/proc/stat').readline().split()[1:]))
    return vals[3], sum(vals)

def read_net():
    rx, tx = 0, 0
    try:
        for line in open('/proc/net/dev').readlines():
            if ':' in line and 'lo:' not in line:
                parts = line.split(':')[1].split()
                rx += int(parts[0])
                tx += int(parts[8])
    catch: pass
    return rx, tx

def read_disk():
    r_sect, w_sect = 0, 0
    try:
        for line in open('/proc/diskstats').readlines():
            parts = line.split()
            if len(parts) >= 10:
                dev = parts[2]
                if 'loop' in dev or 'ram' in dev: continue
                r_sect += int(parts[5])
                w_sect += int(parts[9])
    catch: pass
    return r_sect * 512, w_sect * 512

idle1, total1 = read_cpu()
time.sleep(0.5)
idle2, total2 = read_cpu()
dt = total2 - total1
cpu_pct = round((1.0 - (idle2 - idle1) / dt) * 100.0, 1) if dt > 0 else 0.0

temp = int(open('/sys/class/thermal/thermal_zone0/temp').read()) / 1000.0
rx, tx = read_net()
dr, dw = read_disk()

print(str(round(temp,1))+','+str(cpu_pct)+','+str(mem_used//1024)+','+str(mem_total//1024)+','+str(dr)+','+str(dw)+','+str(rx)+','+str(tx))
""".trimIndent()

/**
 * Convenience overload to fetch system statistics directly using SettingsManager.
 */
suspend fun fetchSystemStats(
    settings: com.rillmaster.pipanel.SettingsManager,
    context: Context? = null
): SystemStats? = fetchSystemStats(
    settings.host,
    settings.port,
    settings.username,
    settings.password,
    settings.sshTimeoutMs,
    context
)

/**
 * Fetches system statistics from the remote device via SSH.
 *
 * @param host The hostname or IP address of the target device
 * @param port The SSH port (typically 22)
 * @param username The SSH username
 * @param password The SSH password (for password-based auth)
 * @param sshTimeoutMs SSH connection timeout in milliseconds
 * @param context Android context for error reporting (can be null)
 * @return SystemStats object containing the metrics, or null if failed
 */
suspend fun fetchSystemStats(
    host: String,
    port: Int,
    username: String,
    password: String,
    sshTimeoutMs: Int,
    context: Context? = null
): SystemStats? =
    withContext(Dispatchers.IO) {
        try {
            val b64 = Base64.encodeToString(
                SYSTEM_STATS_SCRIPT.toByteArray(), Base64.NO_WRAP
            )
            val raw = SshClient.execute(
                host, port, username, password,
                "echo '$b64' | base64 -d | python3",
                sshTimeoutMs,
                context
            )
            if (raw.startsWith("[err]")) return@withContext null
            val parts = raw.trim().split(",")
            if (parts.size < 4) return@withContext null
            SystemStats(
                tempCelsius = parts[0].toDouble(),
                cpuPercent  = parts[1].toDouble().toInt().coerceIn(0, 100),
                ramUsedMb   = parts[2].toInt(),
                ramTotalMb  = parts[3].toInt(),
                diskReadBytes = parts.getOrNull(4)?.toLongOrNull() ?: 0L,
                diskWriteBytes = parts.getOrNull(5)?.toLongOrNull() ?: 0L,
                netRxBytes = parts.getOrNull(6)?.toLongOrNull() ?: 0L,
                netTxBytes = parts.getOrNull(7)?.toLongOrNull() ?: 0L
            )
        } catch (_: Exception) { null }
    }