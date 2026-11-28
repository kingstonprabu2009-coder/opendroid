package com.opendroid.ai.core.cloud

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Better Auth session cookie encrypted at rest by Android Keystore. */
@Singleton
class JarvisCloudSession @Inject constructor(
    private val credentials: ProviderCredentialStore,
    private val baseClient: OkHttpClient
) {
    private val gson = Gson()
    private val cookieJar = EncryptedSessionCookieJar(credentials, gson)
    private val client = baseClient.newBuilder().cookieJar(cookieJar).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun authenticatedClient(): OkHttpClient = client

    suspend fun signUp(baseUrl: String, email: String, password: String) = withContext(Dispatchers.IO) {
        val root = normalizeBaseUrl(baseUrl)
        require(email.isNotBlank() && password.length >= 12) {
            "Enter a valid email and a password with at least 12 characters."
        }
        require(password.length <= 128) { "Password must be 128 characters or fewer." }
        val body = gson.toJson(
            mapOf("name" to "JARVIS User", "email" to email.trim(), "password" to password)
        ).toRequestBody(jsonType)
        val request = Request.Builder().url(root + "api/auth/sign-up/email").post(body).build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                val detail = responseBody.take(240)
                throw IOException("JARVIS account creation failed (HTTP ${response.code}): ${detail.ifBlank { response.message }}")
            }
            // Email verification is required: Better Auth intentionally returns a null
            // session token until the user verifies the address. Do not treat that as failure.
            val token = runCatching {
                com.google.gson.JsonParser.parseString(responseBody).asJsonObject.get("token")
            }.getOrNull()
            if (token == null || token.isJsonNull) return@withContext
        }
        if (!verifySession(root)) throw IOException("Account created, but no active JARVIS session was returned. Verify your email, then sign in.")
    }

    suspend fun signIn(baseUrl: String, email: String, password: String) = withContext(Dispatchers.IO) {
        val root = normalizeBaseUrl(baseUrl)
        require(email.isNotBlank() && password.isNotEmpty()) { "Email and password are required." }
        val body = gson.toJson(mapOf("email" to email.trim(), "password" to password)).toRequestBody(jsonType)
        val request = Request.Builder().url(root + "api/auth/sign-in/email").post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body.string().take(240)
                val errorCode = runCatching {
                    com.google.gson.JsonParser.parseString(detail).asJsonObject.get("code")?.asString
                }.getOrNull()
                if (errorCode == "EMAIL_NOT_VERIFIED") {
                    throw IOException("Email not verified. We sent a fresh verification link; check your inbox, then sign in again.")
                }
                throw IOException("JARVIS sign-in failed (HTTP ${response.code}): ${detail.ifBlank { response.message }}")
            }
        }
        if (!verifySession(root)) throw IOException("Sign-in response received, but no valid JARVIS session was created.")
    }

    suspend fun requestPasswordReset(baseUrl: String, email: String) = withContext(Dispatchers.IO) {
        val root = normalizeBaseUrl(baseUrl)
        require(email.isNotBlank()) { "Enter your JARVIS account email first." }
        val redirect = root + "reset-password-page"
        val body = gson.toJson(mapOf("email" to email.trim(), "redirectTo" to redirect))
            .toRequestBody(jsonType)
        val request = Request.Builder().url(root + "api/auth/request-password-reset").post(body).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body.string()
            if (!response.isSuccessful) {
                val detail = runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject.get("message")?.asString }.getOrNull()
                throw IOException(detail ?: "Password recovery request failed (HTTP ${response.code}).")
            }
        }
    }

    suspend fun verifySession(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(normalizeBaseUrl(baseUrl) + "api/auth/get-session")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            response.isSuccessful && response.body.string().trim().let { it.isNotBlank() && it != "null" }
        }
    }

    suspend fun signOut(baseUrl: String) = withContext(Dispatchers.IO) {
        if (baseUrl.startsWith("https://")) {
            val request = Request.Builder()
                .url(normalizeBaseUrl(baseUrl) + "api/auth/sign-out")
                .post("{}".toRequestBody(jsonType))
                .build()
            runCatching { client.newCall(request).execute().close() }
        }
        cookieJar.clear()
    }

    private fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim()
        require(trimmed.startsWith("https://")) { "JARVIS Cloud URL is not configured. HTTPS is required." }
        return trimmed.trimEnd('/') + "/"
    }
}

private class EncryptedSessionCookieJar(
    private val credentials: ProviderCredentialStore,
    private val gson: Gson
) : CookieJar {
    private val lock = Any()
    private var cached: List<Cookie>? = null
    private val listType = object : TypeToken<List<StoredCookie>>() {}.type

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(lock) {
        val existing = load().filter { it.expiresAt > System.currentTimeMillis() }
        val received = cookies.filter { it.name.contains("session_token", ignoreCase = true) }
        val merged = (existing.filterNot { old ->
            received.any { it.name == old.name && it.domain == old.domain && it.path == old.path }
        } + received).filter { it.expiresAt > System.currentTimeMillis() }
        persist(merged)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        load().filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
    }

    fun clear() = synchronized(lock) {
        val result = credentials.remove(ProviderCredentialId.JarvisCloudSession)
        if (result !is CredentialStoreResult.Success) {
            throw IOException("Could not securely clear the JARVIS Cloud session.")
        }
        cached = emptyList()
    }

    private fun load(): List<Cookie> {
        cached?.let { return it }
        val raw = (credentials.read(ProviderCredentialId.JarvisCloudSession) as? CredentialStoreResult.Success)
            ?.value.orEmpty()
        val stored: List<StoredCookie> = runCatching {
            gson.fromJson<List<StoredCookie>>(raw, listType)
        }.getOrNull().orEmpty()
        cached = stored.filter { it.expiresAt > System.currentTimeMillis() }.mapNotNull { item ->
            runCatching {
                val builder = Cookie.Builder().name(item.name).value(item.value)
                    .path(item.path).expiresAt(item.expiresAt)
                if (item.hostOnly) builder.hostOnlyDomain(item.domain) else builder.domain(item.domain)
                if (item.secure) builder.secure()
                if (item.httpOnly) builder.httpOnly()
                builder.build()
            }.getOrNull()
        }
        return cached.orEmpty()
    }

    private fun persist(cookies: List<Cookie>) {
        val records = cookies.map {
            StoredCookie(it.name, it.value, it.domain, it.path, it.expiresAt, it.hostOnly, it.secure, it.httpOnly)
        }
        if (credentials.write(ProviderCredentialId.JarvisCloudSession, gson.toJson(records)) !is CredentialStoreResult.Success) {
            throw IOException("Could not securely save JARVIS Cloud session.")
        }
        cached = cookies
    }

    private data class StoredCookie(
        val name: String, val value: String, val domain: String, val path: String,
        val expiresAt: Long, val hostOnly: Boolean, val secure: Boolean, val httpOnly: Boolean
    )
}
