package com.opendroid.ai.core.llm.prompts

import com.opendroid.ai.core.agent.ActionSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanningPromptsTest {

    @Test
    fun `on-device prompt gives CREATE_CALENDAR_EVENT the date and time params its handler reads`() {
        val line = PlanningPrompts.buildOnDevicePlanningPrompt().lines()
            .single { it.contains("CREATE_CALENDAR_EVENT") }
        val promptParams = Regex("""(\w+):""").findAll(line).map { it.groupValues[1] }.toList()

        assertEquals(listOf("title", "date", "time"), promptParams)
        val schemaParams = ActionSchema.getAction("CREATE_CALENDAR_EVENT")!!.params.map { it.name }
        assertEquals(emptyList<String>(), promptParams - schemaParams.toSet())
    }

    @Test
    fun `cloud planner includes truthful reasoning and approval boundaries`() {
        val prompt = PlanningPrompts.buildPlanningPrompt()
        assertTrue(prompt.contains("REASONING CHECKLIST"))
        assertTrue(prompt.contains("runtime approval gates"))
        assertTrue(prompt.contains("Never claim an action has completed"))
        assertTrue(prompt.contains("Never place private personal memory in a team-scoped plan"))
        assertTrue(prompt.contains("VIDEO-EDITING WORKFLOW"))
        assertTrue(prompt.contains("SWIPE_COORDINATES"))
        assertTrue(prompt.contains("OPEN_APP {appName: \"CapCut\"}"))
        assertTrue(prompt.contains("Never silently substitute Google Flow"))
        assertTrue(prompt.contains("Preserve the original source"))
        assertTrue(prompt.contains("Never promise a one-second finished video"))
        assertTrue(prompt.contains("Never invent button names, menu items, timeline coordinates"))
    }

    @Test
    fun `on-device planner keeps the same core safety boundaries`() {
        val prompt = PlanningPrompts.buildOnDevicePlanningPrompt()
        assertTrue(prompt.contains("JARVIS's on-device planning engine"))
        assertTrue(prompt.contains("Follow approvals, risk checks, permissions and confirmations"))
        assertTrue(prompt.contains("keep private memory out of team plans"))
    }
}
