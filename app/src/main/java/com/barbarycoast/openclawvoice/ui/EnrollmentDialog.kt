package com.barbarycoast.openclawvoice.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.barbarycoast.openclawvoice.SpeakerEngine
import kotlinx.coroutines.*
import android.content.Intent
import android.os.Bundle

/**
 * Full-screen enrollment dialog. Captures user's name via speech recognition,
 * then records 3 voice samples for MFCC profile creation.
 */
class EnrollmentDialog(
    private val context: Context,
    private val speakerEngine: SpeakerEngine,
    private val onComplete: (String) -> Unit,
    private val onCancel: () -> Unit
) {
    private var dialog: AlertDialog? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    companion object {
        private val SAMPLE_PHRASES = listOf(
            "Tell me about the weather today",
            "What time is it right now",
            "Read me the latest news"
        )
    }

    fun show() {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(64, 64, 64, 64)
        }

        val titleView = TextView(context).apply {
            text = "Voice Enrollment"
            setTextColor(Color.parseColor("#FFB300"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }

        val instructionView = TextView(context).apply {
            text = "Say your name"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 32)
        }

        val statusView = TextView(context).apply {
            text = "Listening…"
            setTextColor(Color.parseColor("#80FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }

        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 4
            progress = 0
            setPadding(0, 32, 0, 0)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 32, 0, 0) }
            layoutParams = lp
        }

        layout.addView(titleView)
        layout.addView(instructionView)
        layout.addView(statusView)
        layout.addView(progressBar)

        dialog = AlertDialog.Builder(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ ->
                cleanup()
                onCancel()
            }
            .create()

        dialog?.show()

        // Step 1: Listen for the user's name
        listenForName(instructionView, statusView, progressBar)
    }

    private fun listenForName(
        instructionView: TextView,
        statusView: TextView,
        progressBar: ProgressBar
    ) {
        statusView.text = "Listening…"

        listenOnce { result ->
            if (result.isNullOrBlank()) {
                statusView.text = "Didn't catch that. Try again."
                handler.postDelayed({ listenForName(instructionView, statusView, progressBar) }, 1000)
                return@listenOnce
            }

            val name = result.trim()
                .replaceFirstChar { it.uppercase() }
                .replace(Regex("[^a-zA-Z\\s]"), "")
                .trim()

            if (name.isBlank()) {
                statusView.text = "Didn't catch that. Try again."
                handler.postDelayed({ listenForName(instructionView, statusView, progressBar) }, 1000)
                return@listenOnce
            }

            progressBar.progress = 1
            instructionView.text = "Hi $name!"
            statusView.text = "Now record 3 voice samples"

            handler.postDelayed({
                recordSamples(name, instructionView, statusView, progressBar, 0, mutableListOf())
            }, 1500)
        }
    }

    private fun recordSamples(
        name: String,
        instructionView: TextView,
        statusView: TextView,
        progressBar: ProgressBar,
        index: Int,
        embeddings: MutableList<FloatArray>
    ) {
        if (index >= 3) {
            // All samples recorded, enroll
            progressBar.progress = 4
            instructionView.text = "Enrolling…"
            statusView.text = ""

            scope.launch {
                speakerEngine.enrollSpeaker(name, embeddings)
                instructionView.text = "Enrolled!"
                statusView.text = name
                delay(1200)
                dialog?.dismiss()
                cleanup()
                onComplete(name)
            }
            return
        }

        val phrase = SAMPLE_PHRASES[index]
        instructionView.text = "Say: \"$phrase\""
        statusView.text = "Recording… (${index + 1}/3)"

        scope.launch {
            val embedding = speakerEngine.recordEnrollmentSample(3000)
            embeddings.add(embedding)
            progressBar.progress = index + 2

            handler.post {
                recordSamples(name, instructionView, statusView, progressBar, index + 1, embeddings)
            }
        }
    }

    private fun listenOnce(callback: (String?) -> Unit) {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                handler.post { callback(null) }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                handler.post { callback(matches?.firstOrNull()) }
            }
        })

        speechRecognizer?.startListening(intent)
    }

    private fun cleanup() {
        scope.cancel()
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }
}
