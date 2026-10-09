package com.dvex

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

enum class DVexState { IDLE, LISTENING, THINKING, SPEAKING }

class DVexEngine(private val context: Context) {
    val currentState = MutableStateFlow(DVexState.IDLE)
    val audioAmplitude = MutableStateFlow(0f)
    
    fun startListening() {
        currentState.value = DVexState.LISTENING
    }
}
