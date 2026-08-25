package com.rillmaster.pipanel.model

import com.rillmaster.pipanel.SettingsManager
import com.rillmaster.pipanel.SshClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ══════════════════════════════════════════════════════════════════════════════
//  Modèle de données pour les stats système
// ══════════════════════════════════════════════════════════════════════════════
data class SystemStats(
    val tempCelsius: Double,
    val cpuPercent : Int,
    val ramUsedMb  : Int,
    val ramTotalMb : Int,
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

idle1, total1 = read_cpu()
time.sleep(0.5)
idle2, total2 = read_cpu()
dt = total2 - total1
cpu_pct = round((1.0 - (idle2 - idle1) / dt) * 100.0, 1) if dt > 0 else 0.0

temp = int(open('/sys/class/thermal/thermal_zone0/temp').read()) / 1000.0

print(str(round(temp,1))+','+str(cpu_pct)+','+str(mem_used//1024)+','+str(mem_total//1024))
""".trimIndent()

suspend fun fetchSystemStats(settings: SettingsManager): SystemStats? =
    withContext(Dispatchers.IO) {
        try {
            val b64 = android.util.Base64.encodeToString(
                SYSTEM_STATS_SCRIPT.toByteArray(), android.util.Base64.NO_WRAP
            )
            val raw = SshClient.execute(
                settings.host, settings.port, settings.username, settings.password,
                "echo '$b64' | base64 -d | python3",
                settings.sshTimeoutMs
            )
            if (raw.startsWith("[err]")) return@withContext null
            val parts = raw.trim().split(",")
            if (parts.size < 4) return@withContext null
            SystemStats(
                tempCelsius = parts[0].toDouble(),
                cpuPercent  = parts[1].toDouble().toInt().coerceIn(0, 100),
                ramUsedMb   = parts[2].toInt(),
                ramTotalMb  = parts[3].toInt()
            )
        } catch (_: Exception) { null }
    }
