package com.opendroid.ai.core.util

import android.util.Log
import com.opendroid.ai.core.agent.ChatErrorUiState
import com.opendroid.ai.core.agent.guidance
import com.opendroid.ai.core.agent.title
import com.opendroid.ai.core.llm.error.LLMException

object NetworkErrorFormatter {

    private const val TAG = "NetworkErrorFormatter"

    fun toUserMessage(error: Throwable?): String {
        if (error is LLMException) {
            val state = ChatErrorUiState.fromException(
                sessionId = "network",
                requestId = "network",
                runId = "network",
                failure = error
            )
            return "${state.title()} ${state.guidance()}"
        }
        val message = error?.localizedMessage ?: error?.message
            ?: return "Something went wrong. Please try again."
        return toUserMessage(message)
    }

    fun toUserMessage(message: String): String {
        val lower = message.lowercase()
        return when {
            lower.contains("unable to resolve host") ||
                lower.contains("no address associated with hostname") ||
                lower.contains("network is unreachable") ||
                lower.contains("enetunreach") || lower.contains("failed to connect") ||
                lower.contains("connection reset") || lower.contains("socketexception") ->
                "JARVIS Cloud network connection failed. Check your internet and try again."

            lower.contains("timeout") || lower.contains("timed out") ->
                "JARVIS Cloud took too long to respond. Check your connection and retry."

            lower.contains("http 401") || lower.contains("unauthorized") || lower.contains("sign in") ->
                "JARVIS Cloud sign-in required. Sign in to your JARVIS account and try again."

            lower.contains("http 403") || lower.contains("forbidden") ->
                "JARVIS Cloud access was denied. Check your account access."

            lower.contains("http 429") || lower.contains("rate limit") || lower.contains("too many requests") ->
                "JARVIS Cloud is busy right now. Wait a moment and retry."

            lower.contains("http 5") || lower.contains("server error") || lower.contains("service unavailable") ->
                "JARVIS Cloud server is temporarily unavailable. Try again shortly."

            lower.contains("empty response") || lower.contains("malformed") || lower.contains("unreadable") ->
                "JARVIS Cloud returned an invalid response. Please retry."

            lower.contains("10.0.2.2") || lower.contains("connection refused") ->
                "Can't reach the configured server. Check your provider settings."

            else -> {
                Log.e(TAG, "Unhandled network/provider error: $message")
                "Something went wrong. Please try again."
            }
        }
    }
}
