package com.opendroid.ai.core.agent

import com.opendroid.ai.data.models.PlanStep
import org.junit.Assert.assertEquals
import org.junit.Test

class PlanValidatorDependenciesTest {

    private fun step(id: String, action: String, dependsOn: List<String> = emptyList()) = PlanStep(
        stepId = id,
        order = 1,
        description = id,
        action = action,
        params = emptyMap(),
        fallback = "",
        dependsOn = dependsOn
    )

    @Test
    fun `a dependency on a confirmation step is kept`() {
        listOf("CONFIRM_ACTION", "ASK_CONFIRMATION", "REQUEST_CONFIRMATION", "CONFIRM_USER", "CONFIRM").forEach { name ->
            val cleaned = PlanValidator.removeBadDependencies(
                listOf(step("s1", name), step("s2", "PAY_UPI", listOf("s1")))
            )
            assertEquals(name, listOf("s1"), cleaned.last().dependsOn)
        }
    }

    @Test
    fun `a dependency on a step that produces nothing is still dropped`() {
        val cleaned = PlanValidator.removeBadDependencies(
            listOf(step("s1", "OPEN_APP"), step("s2", "PAY_UPI", listOf("s1", "gone")))
        )
        assertEquals(emptyList<String>(), cleaned.last().dependsOn)
    }

    @Test
    fun `browser search launch actions are not treated as returned research data`() {
        val cleaned = PlanValidator.removeBadDependencies(
            listOf(
                step("s1", "WEB_SEARCH"),
                step("s2", "GET_NEWS"),
                step("s3", "SEND_SMS", listOf("s1", "s2"))
            )
        )
        assertEquals(emptyList<String>(), cleaned.last().dependsOn)
    }

    @Test
    fun `ten steps are within the safe plan limit`() {
        PlanValidator.requirePlanStepLimit(10)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `plans above the safe step limit are rejected`() {
        PlanValidator.requirePlanStepLimit(11)
    }
}
