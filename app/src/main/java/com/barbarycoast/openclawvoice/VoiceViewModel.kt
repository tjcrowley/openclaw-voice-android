package com.barbarycoast.openclawvoice

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.barbarycoast.openclawvoice.db.SpeakerProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException

/**
 * Core ViewModel orchestrating the voice loop:
 * listen → transcribe → identify speaker → API → stream TTS → listen again
 */
class VoiceViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "VoiceViewModel"
    }

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    val speechEngine = SpeechEngine(application)
    val speakerEngine = SpeakerEngine(application)
    val ttsEngine = TtsEngine(application)
    private val apiClient = OpenClawClient()

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _speakerName = MutableStateFlow<String?>(null)
    val speakerName: StateFlow<String?> = _speakerName

    private val _profiles = MutableStateFlow<List<SpeakerProfile>>(emptyList())
    val profiles: StateFlow<List<SpeakerProfile>> = _profiles

    private val _needsEnrollment = MutableStateFlow(false)
    val needsEnrollment: StateFlow<Boolean> = _needsEnrollment

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude

    /** Per-speaker conversation history */
    private val conversationHistories = mutableMapOf<String, MutableList<OpenClawClient.Message>>()

    private var voiceLoopJob: Job? = null
    private var amplitudeJob: Job? = null

    init {
        viewModelScope.launch {
            loadProfiles()
            if (_profiles.value.isEmpty()) {
                _needsEnrollment.value = true
            }
        }

        // Forward SpeechEngine RMS to our amplitude state
        viewModelScope.launch {
            speechEngine.rmsLevel.collect { rms ->
                if (_state.value == State.LISTENING) {
                    _amplitude.value = rms
                }
            }
        }
    }

    fun startVoiceLoop() {
        if (voiceLoopJob?.isActive == true) return

        voiceLoopJob = viewModelScope.launch {
            _state.value = State.LISTENING
            speakerEngine.startCapture()
            speechEngine.start()

            speechEngine.transcripts.collect { transcript ->
                handleTranscript(transcript)
            }
        }
    }

    fun stopVoiceLoop() {
        voiceLoopJob?.cancel()
        voiceLoopJob = null
        speechEngine.stop()
        speakerEngine.stopCapture()
        ttsEngine.stop()
        _state.value = State.IDLE
        _amplitude.value = 0f
    }

    private suspend fun handleTranscript(transcript: String) {
        Log.d(TAG, "Transcript: $transcript")

        // Pause listening while processing
        speechEngine.pause()
        speakerEngine.stopCapture()

        // Identify speaker
        _state.value = State.THINKING
        _amplitude.value = 0f

        val speakerResult = speakerEngine.identifySpeaker()
        val speakerLabel = speakerResult?.first ?: "Unknown"
        _speakerName.value = if (speakerResult != null) speakerLabel else null

        Log.d(TAG, "Speaker: $speakerLabel (similarity: ${speakerResult?.second ?: 0f})")

        // Build message with speaker prefix
        val userContent = "Speaker: $speakerLabel: $transcript"
        val userField = if (speakerResult != null) speakerLabel else null

        // Get or create conversation history for this speaker
        val history = conversationHistories.getOrPut(speakerLabel) { mutableListOf() }
        history.add(OpenClawClient.Message("user", userContent))

        // Keep history manageable (last 20 messages)
        while (history.size > 20) history.removeAt(0)

        // Stream API response
        try {
            val sentenceBuffer = StringBuilder()
            var fullResponse = ""

            apiClient.streamChat(history, user = userField)
                .catch { e ->
                    Log.e(TAG, "API stream error", e)
                    _state.value = State.SPEAKING
                    ttsEngine.speakNow("Cannot reach assistant, check your connection")
                    delay(3000)
                    resumeListening()
                }
                .collect { delta ->
                    if (_state.value != State.SPEAKING) {
                        _state.value = State.SPEAKING
                    }

                    fullResponse += delta
                    sentenceBuffer.append(delta)

                    // Check if we have a complete sentence to speak
                    val text = sentenceBuffer.toString()
                    val sentenceEnd = findSentenceEnd(text)
                    if (sentenceEnd >= 0) {
                        val sentence = text.substring(0, sentenceEnd + 1).trim()
                        sentenceBuffer.clear()
                        sentenceBuffer.append(text.substring(sentenceEnd + 1))

                        if (sentence.isNotBlank()) {
                            ttsEngine.speakAndWait(sentence)
                        }
                    }
                }

            // Speak any remaining text
            val remaining = sentenceBuffer.toString().trim()
            if (remaining.isNotBlank()) {
                ttsEngine.speakAndWait(remaining)
            }

            // Add assistant response to history
            if (fullResponse.isNotBlank()) {
                history.add(OpenClawClient.Message("assistant", fullResponse))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error in voice loop", e)
            _state.value = State.SPEAKING
            ttsEngine.speakNow("Cannot reach assistant, check your connection")
            delay(3000)
        }

        // Resume listening
        resumeListening()
    }

    private fun resumeListening() {
        _state.value = State.LISTENING
        _speakerName.value = null
        speakerEngine.startCapture()
        speechEngine.resume()
    }

    private fun findSentenceEnd(text: String): Int {
        // Find the last sentence-ending punctuation followed by a space or at end
        var lastEnd = -1
        for (i in text.indices) {
            if (text[i] in ".!?" && (i == text.lastIndex || text.getOrNull(i + 1) == ' ')) {
                lastEnd = i
            }
        }
        return lastEnd
    }

    suspend fun loadProfiles() {
        _profiles.value = speakerEngine.getProfiles()
    }

    fun onEnrollmentComplete() {
        _needsEnrollment.value = false
        viewModelScope.launch {
            loadProfiles()
        }
    }

    suspend fun deleteProfile(id: Long) {
        speakerEngine.deleteProfile(id)
        loadProfiles()
    }

    override fun onCleared() {
        super.onCleared()
        stopVoiceLoop()
        speechEngine.destroy()
        ttsEngine.destroy()
        apiClient.shutdown()
    }
}
