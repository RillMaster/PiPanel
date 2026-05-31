package com.rillmaster.pipanel

import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileManagerScreenTest {

    @Test
    fun testRemoteFileDataClass() {
        val file = RemoteFile(
            name = "test.txt",
            path = "/home/pi/test.txt",
            isDirectory = false,
            size = "1.2K",
            date = "Jan 1 10:00",
            permissions = "-rw-r--r--"
        )
        assertEquals("test.txt", file.name)
        assertEquals("/home/pi/test.txt", file.path)
        assertTrue(!file.isDirectory)
        assertEquals("1.2K", file.size)
    }

    @Test
    fun testRemoteFileHelperReadFile() = runTest {
        val settings = mockk<SettingsManager>()
        every { settings.host } returns "192.168.1.1"
        every { settings.port } returns 22
        every { settings.username } returns "pi"
        every { settings.password } returns "raspberry"

        mockkObject(SshClient)
        coEvery { 
            SshClient.execute(any(), any(), any(), any(), "cat \"/test.txt\" 2>/dev/null || true") 
        } returns "Hello World"

        val content = RemoteFileHelper.readFile(settings, "/test.txt")
        assertEquals("Hello World", content)

        unmockkAll()
    }

    @Test
    fun testRemoteFileHelperWriteFileSuccess() = runTest {
        val settings = mockk<SettingsManager>()
        every { settings.host } returns "192.168.1.1"
        every { settings.port } returns 22
        every { settings.username } returns "pi"
        every { settings.password } returns "raspberry"

        mockkObject(SshClient)
        coEvery { 
            SshClient.execute(any(), any(), any(), any(), any()) 
        } returns "Success"

        val result = RemoteFileHelper.writeFile(settings, "/test.txt", "New Content")
        assertTrue(result)

        unmockkAll()
    }

    @Test
    fun testLsOutputParsingLogic() {
        // Simulating the logic inside refresh()
        val rawOutput = """
            total 4.0K
            drwxr-xr-x 2 pi pi 4.0K Jan 1 10:00 folder
            -rw-r--r-- 1 pi pi 1.2K Jan 1 10:05 file.txt
        """.trimIndent()

        val lines = rawOutput.lines().drop(1).filter { it.isNotBlank() }
        val currentPath = "/home/pi"
        
        val files = lines.mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 9) {
                val perms = parts[0]
                val isDir = perms.startsWith("d")
                val name = parts.drop(8).joinToString(" ")
                RemoteFile(
                    name = name,
                    path = if (currentPath.endsWith("/")) "$currentPath$name" else "$currentPath/$name",
                    isDirectory = isDir,
                    size = parts[4],
                    date = "${parts[5]} ${parts[6]} ${parts[7]}",
                    permissions = perms
                )
            } else null
        }

        assertEquals(2, files.size)
        assertEquals("folder", files[0].name)
        assertTrue(files[0].isDirectory)
        assertEquals("/home/pi/folder", files[0].path)
        
        assertEquals("file.txt", files[1].name)
        assertTrue(!files[1].isDirectory)
        assertEquals("1.2K", files[1].size)
    }
}
