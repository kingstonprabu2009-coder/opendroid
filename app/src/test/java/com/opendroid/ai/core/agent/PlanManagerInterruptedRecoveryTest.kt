package com.opendroid.ai.core.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.core.memory.WorkingMemory
import com.opendroid.ai.data.db.dao.PlanDao
import com.opendroid.ai.data.db.entities.PlanEntity
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStatus
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import com.opendroid.ai.data.repository.PlanRepository
import dagger.Lazy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlanManagerRewriteStepsTestApplication::class)
class PlanManagerInterruptedRecoveryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `startup fails interrupted tasks without replaying uncertain actions`() = runBlocking {
        val dao = FakePlanDao()
        val repository = PlanRepository(dao)
        val manager = PlanManager(
            repository,
            WorkingMemory(context, DeviceStateProvider(context)),
            Lazy { error("Recovery does not need the planner") }
        )
        repository.savePlan(
            Plan(
                planId = "interrupted",
                goal = "Send a message and set a reminder",
                estimatedDuration = "1m",
                estimatedSteps = 3,
                status = PlanStatus.RUNNING,
                steps = listOf(
                    step("done", StepStatus.COMPLETED),
                    step("uncertain", StepStatus.RUNNING),
                    step("not-started", StepStatus.PENDING)
                )
            )
        )
        repository.savePlan(
            Plan("proposal", "Await approval", "1m", 1, listOf(step("p1", StepStatus.PENDING)), PlanStatus.PROPOSED)
        )

        assertEquals(1, manager.recoverInterruptedPlans())
        val recovered = repository.getPlanById("interrupted")!!
        assertEquals(PlanStatus.FAILED, recovered.status)
        assertEquals(StepStatus.COMPLETED, recovered.steps[0].status)
        assertEquals(StepStatus.FAILED, recovered.steps[1].status)
        assertTrue(recovered.steps[1].error.orEmpty().contains("not retried"))
        assertEquals(StepStatus.FAILED, recovered.steps[2].status)
        assertEquals(PlanStatus.PROPOSED, repository.getPlanById("proposal")?.status)
        assertEquals(0, manager.recoverInterruptedPlans())
    }

    private fun step(id: String, status: StepStatus) = PlanStep(
        stepId = id, order = 1, description = id, action = "SEND_SMS", status = status
    )

    private class FakePlanDao : PlanDao {
        private val plans = mutableMapOf<String, PlanEntity>()
        override fun getAllPlansFlow(): Flow<List<PlanEntity>> = emptyFlow()
        override suspend fun getAllPlans(): List<PlanEntity> = plans.values.toList()
        override suspend fun getPlanById(planId: String): PlanEntity? = plans[planId]
        override suspend fun insertOrUpdatePlan(plan: PlanEntity) { plans[plan.planId] = plan }
        override suspend fun deletePlan(planId: String) { plans.remove(planId) }
    }
}
