package com.opendroid.ai.actions

import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceActionsTest {
    @Test
    fun financialActionRegistryIsPermanentlyEmpty() {
        assertTrue(FinanceActions().getActions().isEmpty())
    }
}
