package com.evan.lazlo.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers [LocalModelDownloader.isCompleteDownload] — the guard that
 * decides whether a file on disk is a real, ready-to-use model versus a
 * missing, truncated, or stray partial download. Everything else on
 * [LocalModelDownloader] needs a live network call and Android's
 * EncryptedSharedPreferences, so it isn't exercised here; this is the one
 * piece of it that's pure I/O against a real [File], the same "pull the
 * pure logic out and test that" discipline as [UrlBarInput] and
 * [com.evan.lazlo.ui.chat.AiBackendCopy].
 */
class LocalModelDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `a file that doesn't exist is never a complete download`() {
        val missing = File(tempFolder.root, "does-not-exist.task")
        assertFalse(LocalModelDownloader.isCompleteDownload(missing, expectedSizeBytes = 100))
    }

    @Test
    fun `a file exactly the expected size counts as complete`() {
        val file = tempFolder.newFile("model.task")
        file.writeBytes(ByteArray(100))
        assertTrue(LocalModelDownloader.isCompleteDownload(file, expectedSizeBytes = 100))
    }

    @Test
    fun `a truncated file short of the expected size is not complete`() {
        val file = tempFolder.newFile("model.task.part")
        file.writeBytes(ByteArray(40))
        assertFalse(LocalModelDownloader.isCompleteDownload(file, expectedSizeBytes = 100))
    }

    @Test
    fun `a file larger than expected is also rejected, not just short ones`() {
        val file = tempFolder.newFile("model.task")
        file.writeBytes(ByteArray(150))
        assertFalse(LocalModelDownloader.isCompleteDownload(file, expectedSizeBytes = 100))
    }
}
