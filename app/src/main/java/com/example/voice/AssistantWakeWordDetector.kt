// REMOVED — replaced by SherpaOnnxWakeWordDetector + WakePhraseTokenizer.
//
// Kept as the documented rollback reference for the legacy SpeechRecognizer-based
// standby path, but it is NOT compiled into the app and must never be instantiated
// by production code. If you need the legacy standby detector at all, it is the
// AndroidSpeechWakeWordDetector class inside WakeWordManager.kt (disabled by default
// behind the WakeWordDetector/WakeWordCapability abstraction).
package com.example.voice

/** Placeholder so this file does not represent dead production code. */
internal object RemovedAssistantWakeWordDetector {
    val note: String get() =
        "This file does not contain a compiled wake-word implementation. " +
        "Production standby wake-word detection is SherpaOnnxWakeWordDetector."
}
