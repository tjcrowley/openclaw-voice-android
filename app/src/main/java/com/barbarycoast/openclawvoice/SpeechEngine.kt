package com.barbarycoast.openclawvoice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Wraps Android SpeechRecognizer for continuous listening with auto-restart.
 * Emits final transcripts and RMS amplitude for waveform visualization.
 */
class SpeechEngine(private val context: Context) {

    companion object {
        private const val TAG = "SpeechEngine"
    }

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var shouldListen = false

    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val transcripts: SharedFlow<String> = _transcripts

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive

    private val recognitionIntent: Intent
        get() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _isActive.value = true
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech started")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Normalize RMS: SpeechRecognizer gives -2 to 10 typically
            _rmsLevel.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _isActive.value = false
            Log.d(TAG, "End of speech")
        }

        override fun onError(error: Int) {
            _isActive.value = false
            _rmsLevel.value = 0f
            Log.w(TAG, "Recognition error: $error")
            // Auto-restart on recoverable errors
            if (shouldListen) {
                restartListening()
            }
        }

        override fun onResults(results: Bundle?) {
            _isActive.value = false
            _rmsLevel.value = 0f
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim()
            if (!text.isNullOrEmpty()) {
                Log.d(TAG, "Final result: $text")
                _transcripts.tryEmit(text)
            }
            // Auto-restart for continuous loop
            if (shouldListen) {
                restartListening()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // We only use final results for submission
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun start() {
        shouldListen = true
        startListening()
    }

    fun stop() {
        shouldListen = false
        stopListening()
    }

    fun pause() {
        stopListening()
    }

    fun resume() {
        if (shouldListen) {
            startListening()
        }
    }

    private fun startListening() {
        if (isListening) return
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(listener)
                }
            }
            recognizer?.startListening(recognitionIntent)
            isListening = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            isListening = false
        }
    }

    private fun stopListening() {
        isListening = false
        _isActive.value = false
        _rmsLevel.value = 0f
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {}
    }

    private fun restartListening() {
        isListening = false
        try {
            recognizer?.cancel()
        } catch (_: Exception) {}
        // Brief delay to avoid rapid restart loops
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (shouldListen) {
                startListening()
            }
        }, 150)
    }

    fun destroy() {
        shouldListen = false
        isListening = false
        try {
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
    }
}
