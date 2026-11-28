package com.opendroid.ai.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialAccessPolicyTest {
    @Test
    fun blocksFinancialActionNamesAndAliases() {
        assertTrue(FinancialAccessPolicy.isBlockedAction("PAY_UPI"))
        assertTrue(FinancialAccessPolicy.isBlockedAction("CHECK_BALANCE"))
        assertTrue(FinancialAccessPolicy.isBlockedAction("BANK_TRANSFER"))
        assertFalse(FinancialAccessPolicy.isBlockedAction("OPEN_APP"))
        assertFalse(FinancialAccessPolicy.isBlockedAction("WEB_SEARCH"))
    }

    @Test
    fun blocksKnownFinancialAppNamesAndPackageIds() {
        assertTrue(FinancialAccessPolicy.isBlockedAppTarget("Google Pay"))
        assertTrue(FinancialAccessPolicy.isBlockedAppTarget("com.phonepe.app"))
        assertTrue(FinancialAccessPolicy.isBlockedAppTarget("MyBank Mobile"))
        assertTrue(FinancialAccessPolicy.isBlockedAppTarget("YONO SBI"))
        assertTrue(FinancialAccessPolicy.isBlockedAppTarget("CRED"))
        assertFalse(FinancialAccessPolicy.isBlockedAppTarget("CapCut"))
        assertFalse(FinancialAccessPolicy.isBlockedAppTarget("org.telegram.messenger"))
    }

    @Test
    fun blocksPaymentSchemesAndFinancialDestinations() {
        assertTrue(FinancialAccessPolicy.isBlockedUrl("upi://pay?pa=person@bank"))
        assertTrue(FinancialAccessPolicy.isBlockedUrl("https://example.com/netbanking/login"))
        assertTrue(FinancialAccessPolicy.isBlockedUrl("https://www.hdfcbank.com/"))
        assertFalse(FinancialAccessPolicy.isBlockedUrl("https://example.com/video-editor"))
    }

    @Test
    fun detectsFinancialScreensButAllowsOrdinaryScreens() {
        assertTrue(FinancialAccessPolicy.isFinancialScreen("Enter your UPI PIN"))
        assertTrue(FinancialAccessPolicy.isFinancialScreen("Account number and IFSC"))
        assertTrue(FinancialAccessPolicy.isFinancialScreen("Welcome to YONO SBI"))
        assertFalse(FinancialAccessPolicy.isFinancialScreen("CapCut timeline and transitions"))
    }
}
