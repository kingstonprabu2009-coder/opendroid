package com.opendroid.ai.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.agent.ChatAgent
import com.opendroid.ai.core.cloud.JarvisCloudSession
import com.opendroid.ai.BuildConfig
import com.opendroid.ai.core.llm.ProviderCatalog
import android.widget.Toast
import com.google.gson.JsonParser
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.opendroid.ai.core.agent.ChatErrorPrimaryAction
import com.opendroid.ai.core.agent.ChatErrorUiState
import com.opendroid.ai.core.agent.primaryAction
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.repository.ChatSession
import com.opendroid.ai.data.repository.ConversationRepository
import com.opendroid.ai.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentLoop: ChatAgent,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val jarvisCloudSession: JarvisCloudSession
) : ViewModel() {

    data class TeamChoice(val id: String, val name: String)
    private val _teams = MutableStateFlow<List<TeamChoice>>(emptyList())
    val teams: StateFlow<List<TeamChoice>> = _teams.asStateFlow()
    private val _selectedTeamId = MutableStateFlow<String?>(null)
    val selectedTeamId: StateFlow<String?> = _selectedTeamId.asStateFlow()
    private val _teamLoadStatus = MutableStateFlow("")
    val teamLoadStatus: StateFlow<String> = _teamLoadStatus.asStateFlow()
    @Volatile private var userSelectedWorkspace = false

    init {
        refreshChatTeams()
        viewModelScope.launch {
            val currentId = conversationRepository.ensureCurrentSessionId()
            if (!userSelectedWorkspace) {
                _selectedTeamId.value = conversationRepository.getSession(currentId)?.teamId
            }
        }
    }

    fun selectChatTeam(teamId: String?) {
        if (teamId != null && _teams.value.none { it.id == teamId }) return
        userSelectedWorkspace = true
        _selectedTeamId.value = teamId
        viewModelScope.launch {
            val currentId = conversationRepository.ensureCurrentSessionId()
            val current = conversationRepository.getSession(currentId)
            if (current?.teamId != teamId) {
                // Workspace changes always start a fresh local session; the DB stores
                // its scope so reopening an older chat restores the correct workspace.
                conversationRepository.createSession(teamId = teamId)
            }
        }
    }

    fun refreshChatTeams() {
        val baseUrl = BuildConfig.JARVIS_CLOUD_BASE_URL.trim()
        if (!baseUrl.startsWith("https://")) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(baseUrl.trimEnd('/') + "/v1/teams").get().build()
                jarvisCloudSession.authenticatedClient().newCall(request).execute().use { response ->
                    if (response.code == 401) throw IllegalStateException("Sign in to JARVIS Cloud to use teams.")
                    if (!response.isSuccessful) throw IllegalStateException("Team list unavailable (HTTP ${response.code}).")
                    val array = JsonParser.parseString(response.body.string()).asJsonObject.getAsJsonArray("teams")
                    array.map { item -> item.asJsonObject.let { TeamChoice(it.get("id").asString, it.get("name").asString) } }
                }
            }.onSuccess { loaded ->
                _teams.value = loaded
                if (_selectedTeamId.value != null && loaded.none { it.id == _selectedTeamId.value }) _selectedTeamId.value = null
                _teamLoadStatus.value = if (loaded.isEmpty()) "No teams joined" else ""
            }.onFailure { error ->
                _teamLoadStatus.value = error.message?.take(120) ?: "Unable to load teams."
                if (_teamLoadStatus.value.contains("Sign in", ignoreCase = true)) {
                    _teams.value = emptyList(); _selectedTeamId.value = null
                }
            }
        }
    }

    val llmConfig: StateFlow<LLMConfig> = settingsRepository.llmConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LLMConfig())

    val conversationHistory: StateFlow<List<ChatMessage>> = conversationRepository.conversations
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** Every chat session, most recently active first. */
    val sessions: StateFlow<List<ChatSession>> = conversationRepository.sessions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val currentSessionId: StateFlow<String?> = conversationRepository.currentSessionId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val agentState: StateFlow<AgentState> = agentLoop.agentState

    /** chatError scoped to the chat on screen. */
    val chatError: StateFlow<ChatErrorUiState?> = combine(
        agentLoop.chatError, sessions
    ) { error, sessionList ->
        val current = sessionList.firstOrNull { it.isCurrent }?.id
        error?.takeIf { it.sessionId == current }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    fun dismissChatError() {
        agentLoop.dismissChatError()
    }

    fun retryAfterChatError(context: Context) {
        val error = agentLoop.chatError.value ?: return
        when (error.primaryAction()) {
            ChatErrorPrimaryAction.RETRY -> {
                val pausedPlanId = error.resumePlanId
                if (pausedPlanId != null) {
                    agentLoop.resumePlan(pausedPlanId, error.sessionId, context)
                    return
                }
                agentLoop.dismissChatError()
                viewModelScope.launch {
                    val teamId = conversationRepository.getSession(error.sessionId)?.teamId
                    agentLoop.retryRequest(error.requestId, error.sessionId, context, teamId)
                }
            }
            else -> agentLoop.dismissChatError()
        }
    }

    private fun sessionOf(state: AgentState, activeTaskSessionId: String?, proposedPlanSessionId: String?): String? =
        if (state is AgentState.PlanProposed) proposedPlanSessionId ?: activeTaskSessionId else activeTaskSessionId

    /** agentState, forced to Idle when it describes a task of a different chat. */
    val visibleAgentState: StateFlow<AgentState> = combine(
        agentState, agentLoop.activeTaskSessionId, agentLoop.proposedPlanSessionId, sessions
    ) { state, activeTaskSessionId, proposedPlanSessionId, sessionList ->
        val taskSessionId = sessionOf(state, activeTaskSessionId, proposedPlanSessionId)
        val current = sessionList.firstOrNull { it.isCurrent }?.id
        if (taskSessionId == null || taskSessionId == current) state else AgentState.Idle
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AgentState.Idle
    )

    /** Chat with an in-flight task, or null. Not scoped to the viewed chat. */
    val runningSessionId: StateFlow<String?> = combine(
        agentState, agentLoop.activeTaskSessionId, agentLoop.proposedPlanSessionId
    ) { state, activeTaskSessionId, proposedPlanSessionId ->
        if (state is AgentState.Thinking || state is AgentState.ExecutingPlan) {
            sessionOf(state, activeTaskSessionId, proposedPlanSessionId)
        } else {
            null
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    fun sendMessage(query: String, context: Context) {
        if (query.isBlank()) return
        if (_selectedTeamId.value != null &&
            ProviderCatalog.canonicalName(llmConfig.value.activeProvider) != "JARVIS Cloud"
        ) {
            Toast.makeText(context, "Team chat requires JARVIS Cloud as the active AI provider.", Toast.LENGTH_LONG).show()
            return
        }
        val selectedTeam = _selectedTeamId.value
        agentLoop.speakReplies = false
        viewModelScope.launch {
            val currentId = conversationRepository.ensureCurrentSessionId()
            val currentSession = conversationRepository.getSession(currentId)
            val sessionId = if (currentSession?.teamId == selectedTeam) currentId
                else conversationRepository.createSession(teamId = selectedTeam)
            agentLoop.processQuery(query, context, sessionId, selectedTeam)
        }
    }

    val pendingContactPickerMessageId: StateFlow<String?> = agentLoop.pendingContactPickerMessageId

    fun selectContact(pickerMessageId: String, contact: Map<String, String>, context: Context) {
        val sessionId = sessions.value.firstOrNull { it.isCurrent }?.id
        agentLoop.speakReplies = false
        agentLoop.answerContactPicker(pickerMessageId, contact, context, sessionId)
    }

    fun editAndResend(messageId: String, newText: String, context: Context) {
        if (newText.isBlank()) return
        agentLoop.cancelCurrentTask()
        agentLoop.speakReplies = false
        viewModelScope.launch {
            val sessionId = conversationRepository.ensureCurrentSessionId()
            conversationRepository.deleteMessageAndAfter(sessionId, messageId)
            agentLoop.processQuery(newText, context, sessionId, _selectedTeamId.value)
        }
    }

    fun approvePlan(context: Context, planId: String, grants: Set<String> = emptySet()) {
        agentLoop.approveProposedPlan(context, planId, grants)
    }

    fun rejectPlan() {
        agentLoop.rejectProposedPlan()
    }

    /** Clears the messages of the CURRENT chat only. */
    fun clearChat() {
        val current = sessions.value.firstOrNull { it.isCurrent }?.id
        if (current != null) cancelTaskIn(current)
        viewModelScope.launch {
            conversationRepository.clearCurrentSession()
        }
    }

    /** New empty chat; does NOT cancel the running task. */
    fun newChat() {
        viewModelScope.launch {
            conversationRepository.createSession(teamId = _selectedTeamId.value)
        }
    }

    /** Switches the active chat; does NOT cancel the running task. */
    fun switchToSession(sessionId: String) {
        if (isCurrentSession(sessionId)) return
        viewModelScope.launch {
            conversationRepository.switchToSession(sessionId)
            _selectedTeamId.value = conversationRepository.getSession(sessionId)?.teamId
        }
    }

    fun renameSession(sessionId: String, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            conversationRepository.renameSession(sessionId, title.trim())
        }
    }

    /** Deletes a chat; cancels a task pinned to it first. */
    fun deleteChat(sessionId: String) {
        cancelTaskIn(sessionId)
        agentLoop.forgetSession(sessionId)
        viewModelScope.launch {
            conversationRepository.deleteSession(sessionId)
            val currentId = conversationRepository.ensureCurrentSessionId()
            _selectedTeamId.value = conversationRepository.getSession(currentId)?.teamId
        }
    }

    private fun isCurrentSession(sessionId: String): Boolean =
        sessions.value.any { it.id == sessionId && it.isCurrent }

    private fun cancelTaskIn(sessionId: String) {
        val owner = sessionOf(
            agentLoop.agentState.value,
            agentLoop.activeTaskSessionId.value,
            agentLoop.proposedPlanSessionId.value
        )
        if (owner == sessionId) {
            agentLoop.cancelCurrentTask()
        }
    }

    fun stopTask() {
        agentLoop.cancelCurrentTask()
    }
}
