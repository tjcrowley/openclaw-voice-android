package com.barbarycoast.openclawvoice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * TextToSpeech wrapper that speaks text sentence-by-sentence,
 * supporting queuing from streaming API responses.
 */
class TtsEngine(context: Context) {

    private val utteranceId = AtomicInteger(0)
    private var tts: TextToSpeech? = null
    private var initialized = false
    private val pendingCallbacks = mutableMapOf<String, () -> Unit>()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.05f)
                initialized = true
                Log.d(TAG, "TTS initialized")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                utteranceId?.let { id ->
                    pendingCallbacks.remove(id)?.invoke()
                }
                // Check if queue is empty
                if (pendingCallbacks.isEmpty()) {
                    _isSpeaking.value = false
                }
            }

            @Deprecated("Deprecated in API")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { id ->
                    pendingCallbacks.remove(id)?.invoke()
                }
                if (pendingCallbacks.isEmpty()) {
                    _isSpeaking.value = false
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error $errorCode for utterance $utteranceId")
                utteranceId?.let { id ->
                    pendingCallbacks.remove(id)?.invoke()
                }
                if (pendingCallbacks.isEmpty()) {
                    _isSpeaking.value = false
                }
            }
        })
    }

    /**
     * Speak a single sentence, suspending until it finishes.
     */
    suspend fun speakAndWait(text: String) {
        if (!initialized || text.isBlank()) return

        val id = "utt_${utteranceId.getAndIncrement()}"

        suspendCancellableCoroutine { cont ->
            pendingCallbacks[id] = {
                if (cont.isActive) cont.resume(Unit)
            }
            cont.invokeOnCancellation {
                pendingCallbacks.remove(id)
            }

            _isSpeaking.value = true
            val result = tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
            if (result != TextToSpeech.SUCCESS) {
                pendingCallbacks.remove(id)
                _isSpeaking.value = false
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    /**
     * Speak text immediately (queue flush).
     */
    fun speakNow(text: String) {
        if (!initialized || text.isBlank()) return
        val id = "utt_${utteranceId.getAndIncrement()}"
        _isSpeaking.value = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /**
     * Split text into sentences and speak each one sequentially.
     */
    suspend fun speakSentences(text: String) {
        val sentences = splitIntoSentences(text)
        for (sentence in sentences) {
            speakAndWait(sentence.trim())
        }
    }

    fun stop() {
        tts?.stop()
        pendingCallbacks.clear()
        _isSpeaking.value = false
    }

    fun destroy() {
        stop()
        tts?.shutdown()
        tts = null
        initialized = false
    }

    companion object {
        private const val TAG = "TtsEngine"

        fun splitIntoSentences(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            return text.split(Regex("(?<=[.!?])\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }
}
