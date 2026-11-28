package com.opendroid.ai.core.security

import java.net.URI
import java.util.Locale

/**
 * Fail-closed policy for the owner's no-banking/no-payment requirement.
 * This policy is intentionally independent of LLM prompts and approval settings.
 */
object FinancialAccessPolicy {
    private val blockedActionNames = setOf(
        "PAY_UPI", "CHECK_BALANCE", "SPLIT_BILL", "PAYMENT", "MAKE_PAYMENT",
        "TRANSFER_MONEY", "BANK_TRANSFER", "CHECK_BANK_BALANCE"
    )

    private val blockedTargetTokens = listOf(
        "bank", "banking", "upi", "payment", "pay", "paytm", "phonepe", "gpay",
        "googlepay", "bhim", "paisa", "wallet", "finance", "credit", "debit",
        "loan", "invest", "trading", "broker", "card", "sbi", "hdfc", "icici",
        "axis", "kotak", "canara", "baroda", "idfc", "federal", "unionbank",
        "indusind", "yesbank", "pnb", "indianbank", "rblbank", "yono", "bobworld",
        "mobikwik", "freecharge", "amazonpay", "payzapp", "fampay", "hdfcbank",
        "icicibank", "axisbank", "canarabank", "bankofbaroda", "idfcfirstbank",
        "federalbank", "unionbankofindia", "indusindbank", "cred"
    )

    private val blockedScreenPhrases = listOf(
        "upi pin", "enter your pin", "enter pin", "bank account",
        "account number", "account balance", "net banking", "internet banking",
        "ifsc", "cvv", "card number", "debit card", "credit card",
        "send money", "transfer money", "pay now", "make payment",
        "payment successful", "payment pending", "wallet balance",
        "bank login", "banking login", "bank statement", "fund transfer",
        "beneficiary", "account summary", "available balance", "transaction history",
        "upi", "netbanking"
    )

    fun isBlockedAction(actionName: String): Boolean {
        val normalized = normalize(actionName)
        if (normalized in blockedActionNames) return true
        return blockedTargetTokens.any { token ->
            normalized.split('_').any { part -> part == normalize(token) }
        }
    }

    fun isBlockedAppTarget(target: String?): Boolean {
        if (target.isNullOrBlank()) return false
        val normalized = target.lowercase(Locale.ROOT)
        val tokens = normalized.split(Regex("[^a-z0-9]+")).filter(String::isNotBlank)
        return tokens.any { token -> blockedTargetTokens.any { token == it } } ||
            normalized.contains("com.google.android.apps.nbu.paisa.user") ||
            normalized.contains("com.phonepe.app") ||
            normalized.contains("net.one97.paytm")
    }

    fun isBlockedUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val candidate = rawUrl.trim()
        if (candidate.startsWith("upi:", ignoreCase = true)) return true
        val uri = runCatching { URI(candidate) }.getOrNull()
        val searchable = listOfNotNull(uri?.host, uri?.rawPath, uri?.rawQuery, candidate)
            .joinToString(" ").lowercase(Locale.ROOT)
        return isBlockedAppTarget(searchable) ||
            listOf("payment", "banking", "netbanking", "upi", "wallet", "transfer-money")
                .any(searchable::contains)
    }

    fun isFinancialScreen(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val normalized = text.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        return isBlockedAppTarget(normalized) || blockedScreenPhrases.any(normalized::contains)
    }

    private fun normalize(value: String): String =
        value.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]+"), "_").trim('_')
}
