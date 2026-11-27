package com.opendroid.ai.di

import org.junit.Test
import org.junit.Assert.assertEquals
import okhttp3.OkHttpClient

class AppModuleTest {
    @Test
    fun `shared HTTP client uses the configured network timeouts`() {
        val client: OkHttpClient = AppModule.provideOkHttpClient()
        assertEquals("connect timeout", 20000, client.connectTimeoutMillis)
        assertEquals("read timeout", 120000, client.readTimeoutMillis)
        assertEquals("write timeout", 30000, client.writeTimeoutMillis)
    }
}
