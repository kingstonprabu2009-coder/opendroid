package com.opendroid.ai.actions

import com.opendroid.ai.actions.base.Action
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Financial automation is intentionally disabled by the JARVIS owner policy.
 * Keep an empty registry so legacy dependency-injection wiring cannot expose
 * payment, balance, or bill-splitting handlers.
 */
@Singleton
class FinanceActions @Inject constructor() {
    fun getActions(): List<Action> = emptyList()
}
