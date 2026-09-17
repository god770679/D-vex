package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.remote.PendingConfirmation
import com.example.model.AiCoreState
import com.example.model.DvexAssistantState
import com.example.model.DvexSettings
import com.example.model.DvexUiState
import com.example.model.VoiceState
import com.example.repository.AssistantRepository
import com.example.service.DvexAssistantService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

  private val assistantRepo = AssistantRepository.getInstance(application)

  private val _uiState = MutableStateFlow(DvexUiState())
  val uiState: StateFlow<DvexUiState> = _uiState.asStateFlow()

  val assistantState: StateFlow<DvexAssistantState> = assistantRepo.assistantState
  val settings: StateFlow<DvexSettings> = assistantRepo.settings
  val pendingConfirmation: StateFlow<PendingConfirmation?> = assistantRepo.pendingConfirmation
  val latestTranscript: StateFlow<String> = assistantRepo.latestTranscript

  init {
    viewModelScope.launch {
      assistantRepo.assistantState.collectLatest { state ->
        val mappedAiState = state.toAiCoreState()
        val mappedVoiceState = state.toVoiceState()
        val response = assistantRepo.latestResponse.value
        val transcript = assistantRepo.latestTranscript.value

        val displayText = when (state) {
          is DvexAssistantState.Listening -> if (transcript.isNotBlank()) transcript else "Listening for speech..."
          is DvexAssistantState.Processing -> if (transcript.isNotBlank()) transcript else "Processing..."
          is DvexAssistantState.ExecutingAction -> "Executing ${state.toolName}..."
          is DvexAssistantState.Speaking -> state.text
          is DvexAssistantState.Error -> "ALERT: ${state.message}"
          is DvexAssistantState.Standby -> response
          is DvexAssistantState.Idle -> response
          is DvexAssistantState.WakeWordListening -> response
        }

        _uiState.value = _uiState.value.copy(
          aiState = mappedAiState,
          voiceState = mappedVoiceState,
          responseText = displayText
        )
      }
    }

    viewModelScope.launch {
      assistantRepo.latestResponse.collectLatest { resp ->
        val state = assistantRepo.assistantState.value
        if (state is DvexAssistantState.Speaking || state is DvexAssistantState.Standby || state is DvexAssistantState.Idle) {
          _uiState.value = _uiState.value.copy(responseText = resp)
        }
      }
    }

    viewModelScope.launch {
      assistantRepo.latestTranscript.collectLatest { tr ->
        val state = assistantRepo.assistantState.value
        if (state is DvexAssistantState.Listening && tr.isNotBlank()) {
          _uiState.value = _uiState.value.copy(responseText = tr)
        }
      }
    }
  }

  fun updateUiState(newState: DvexUiState) {
    _uiState.value = newState
  }

  fun updateSettings(newSettings: DvexSettings) {
    assistantRepo.updateSettings(newSettings)
    val context = getApplication<Application>()
    if (newSettings.alwaysReadyEnabled) {
      DvexAssistantService.start(context)
    } else {
      DvexAssistantService.stop(context)
    }
  }

  fun onCenterCoreTapped() {
    val currentState = assistantRepo.assistantState.value
    when (currentState) {
      is DvexAssistantState.Listening -> {
        assistantRepo.stopListening()
      }
      is DvexAssistantState.Speaking -> {
        // Interruption: Stop speaking immediately and listen for new command
        assistantRepo.startListeningForCommand()
      }
      else -> {
        assistantRepo.startListeningForCommand()
      }
    }
  }

  fun processVoiceCommand(command: String) {
    assistantRepo.processCommand(command)
  }

  fun confirmPendingAction() {
    assistantRepo.confirmPendingAction()
  }

  fun cancelPendingAction() {
    assistantRepo.cancelPendingAction()
  }
}
