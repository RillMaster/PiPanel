package com.rillmaster.pipanel.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ChangelogParserTest {

    private val sampleChangelog = """
        # Changelog
        
        ## [1.5.3] - 2026-08-25
        ### Added
        - New feature A
        - New feature B
        
        ## [1.5.2] - 2026-08-20
        ### Fixed
        - Bug fix C
        
        ## v1.5.1
        - Initial release
    """.trimIndent()

    @Test
    fun `extract existing version with brackets`() {
        val result = ChangelogParser.extractVersionChangelog(sampleChangelog, "1.5.3")
        val expected = """
            ### Added
            - New feature A
            - New feature B
        """.trimIndent()
        assertEquals(expected, result)
    }

    @Test
    fun `extract intermediate version`() {
        val result = ChangelogParser.extractVersionChangelog(sampleChangelog, "1.5.2")
        val expected = """
            ### Fixed
            - Bug fix C
        """.trimIndent()
        assertEquals(expected, result)
    }

    @Test
    fun `extract version with v prefix`() {
        val result = ChangelogParser.extractVersionChangelog(sampleChangelog, "1.5.1")
        assertEquals("- Initial release", result)
    }

    @Test
    fun `return full changelog if version not found`() {
        val result = ChangelogParser.extractVersionChangelog(sampleChangelog, "2.0.0")
        assertEquals(sampleChangelog.trim(), result)
    }

    @Test
    fun `handle empty changelog`() {
        val result = ChangelogParser.extractVersionChangelog("", "1.0.0")
        assertEquals("", result)
    }
}
