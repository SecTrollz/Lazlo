package com.evan.lazlo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRecordCodecTest {

    @Test
    fun `records round-trip through encode and decode`() {
        val records = listOf(
            BrowserRecord("https://example.com", "Example", 1000L),
            BrowserRecord("https://a.test", "A Test \"quoted\" & special", 2000L),
        )
        val decoded = BrowserRecordCodec.decodeRecords(BrowserRecordCodec.encodeRecords(records))
        assertEquals(records, decoded)
    }

    @Test
    fun `decoding blank or null input yields an empty list, not a crash`() {
        assertTrue(BrowserRecordCodec.decodeRecords(null).isEmpty())
        assertTrue(BrowserRecordCodec.decodeRecords("").isEmpty())
        assertTrue(BrowserRecordCodec.decodeRecords("not json").isEmpty())
    }

    @Test
    fun `an entry missing its url is skipped rather than crashing the whole decode`() {
        val json = """[{"title":"no url","ts":5},{"url":"https://ok.test","title":"ok","ts":6}]"""
        val decoded = BrowserRecordCodec.decodeRecords(json)
        assertEquals(1, decoded.size)
        assertEquals("https://ok.test", decoded[0].url)
    }

    @Test
    fun `downloads round-trip through encode and decode`() {
        val downloads = listOf(
            DownloadRecord(1L, "https://example.com/file.pdf", "file.pdf", 1234L),
            DownloadRecord(2L, "https://example.com/img.png", "img.png", 5678L),
        )
        val decoded = BrowserRecordCodec.decodeDownloads(BrowserRecordCodec.encodeDownloads(downloads))
        assertEquals(downloads, decoded)
    }

    @Test
    fun `download entries missing their id are skipped, so they cannot collide as list keys`() {
        // The downloads list is keyed by downloadManagerId; two id-less entries
        // would both decode to -1 and crash the LazyColumn on the duplicate key.
        val json = """[{"url":"https://a.test/1","fileName":"a"},{"url":"https://b.test/2","fileName":"b"},""" +
            """{"id":7,"url":"https://ok.test/f.pdf","fileName":"f.pdf","ts":9}]"""
        val decoded = BrowserRecordCodec.decodeDownloads(json)
        assertEquals(1, decoded.size)
        assertEquals(7L, decoded[0].downloadManagerId)
        assertEquals(decoded.map { it.downloadManagerId }.distinct().size, decoded.size)
    }

    @Test
    fun `decoding blank or malformed download input yields an empty list, not a crash`() {
        assertTrue(BrowserRecordCodec.decodeDownloads(null).isEmpty())
        assertTrue(BrowserRecordCodec.decodeDownloads("").isEmpty())
        assertTrue(BrowserRecordCodec.decodeDownloads("{not json").isEmpty())
    }
}
