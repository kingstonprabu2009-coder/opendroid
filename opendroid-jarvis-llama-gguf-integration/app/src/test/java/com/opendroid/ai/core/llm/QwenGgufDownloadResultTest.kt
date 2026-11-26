package com.opendroid.ai.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenGgufDownloadResultTest {

    @Test
    fun `completed download stores durable verified size and 100 percent`() {
        val bytes = 4_680_000_000L
        val data = QwenGgufDownloadResult.completedData(bytes)

        assertEquals(bytes, data.getLong("downloaded", -1L))
        assertEquals(bytes, data.getLong("total", -1L))
        assertEquals(100, data.getInt("percent", -1))
        assertTrue(data.getBoolean("verified", false))
    }

    @Test
    fun `completed download never stores negative sizes`() {
        val data = QwenGgufDownloadResult.completedData(-1L)

        assertEquals(0L, data.getLong("downloaded", -1L))
        assertEquals(0L, data.getLong("total", -1L))
        assertEquals(100, data.getInt("percent", -1))
    }
}
