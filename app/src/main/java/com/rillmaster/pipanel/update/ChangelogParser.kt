package com.rillmaster.pipanel.update

/**
 * Parsing de changelog — pure Kotlin, sans dépendance Android,
 * pour être testable en JVM unit test.
 */
object ChangelogParser {

    /**
     * Extrait uniquement la section du changelog correspondant à [version]
     * (ex: "## [1.5.3]" … jusqu'au prochain titre "## [...]").
     * Retourne le changelog complet si aucune section ne correspond.
     */
    fun extractVersionChangelog(fullChangelog: String, version: String): String {
        val headerRegex = Regex("""^##+\s*\[?v?([0-9][0-9A-Za-z.\-]*)\]?.*""")
        val result = StringBuilder()
        var inside = false
        for (line in fullChangelog.lines()) {
            val match = headerRegex.find(line.trimStart())
            if (match != null) {
                if (inside) break                          // prochaine version → fin
                if (match.groupValues[1] == version) { inside = true; continue }
            } else if (inside) {
                result.appendLine(line)
            }
        }
        return result.toString().trim().ifEmpty { fullChangelog }
    }
}
