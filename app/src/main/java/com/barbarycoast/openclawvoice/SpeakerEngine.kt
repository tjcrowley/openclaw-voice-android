package com.barbarycoast.openclawvoice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.barbarycoast.openclawvoice.db.AppDatabase
import com.barbarycoast.openclawvoice.db.SpeakerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

/**
 * On-device speaker recognition using MFCC embeddings and cosine similarity.
 * Records audio via AudioRecord, extracts 13-coefficient MFCCs, averages into
 * a fixed-length embedding vector.
 */
class SpeakerEngine(private val context: Context) {

    companion object {
        private const val TAG = "SpeakerEngine"
        private const val SAMPLE_RATE = 16000
        private const val NUM_MFCC = 13
        private const val FRAME_SIZE_MS = 25
        private const val FRAME_STEP_MS = 10
        private const val NUM_MEL_FILTERS = 26
        private const val FFT_SIZE = 512
        private const val SIMILARITY_THRESHOLD = 0.82f
    }

    private val dao = AppDatabase.getInstance(context).speakerDao()

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var capturedAudio: ShortArray? = null
    private val captureBuffer = mutableListOf<Short>()

    // --- Public API ---

    /** Start background audio capture in parallel with SpeechRecognizer */
    fun startCapture() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return

        stopCapture()
        captureBuffer.clear()

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE * 2) // at least 1 second buffer

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "No RECORD_AUDIO permission", e)
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return
        }

        isRecording = true
        audioRecord?.startRecording()

        Thread {
            val readBuf = ShortArray(1024)
            while (isRecording) {
                val read = audioRecord?.read(readBuf, 0, readBuf.size) ?: -1
                if (read > 0) {
                    synchronized(captureBuffer) {
                        for (i in 0 until read) captureBuffer.add(readBuf[i])
                        // Keep last 5 seconds max
                        val maxSamples = SAMPLE_RATE * 5
                        while (captureBuffer.size > maxSamples) {
                            captureBuffer.removeAt(0)
                        }
                    }
                }
            }
        }.start()
    }

    /** Stop capture and freeze the buffer */
    fun stopCapture() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        synchronized(captureBuffer) {
            capturedAudio = captureBuffer.toShortArray()
        }
    }

    /** Get current amplitude (0-1) from capture buffer for waveform */
    fun getCurrentAmplitude(): Float {
        if (!isRecording) return 0f
        synchronized(captureBuffer) {
            if (captureBuffer.size < 160) return 0f
            val recent = captureBuffer.takeLast(160)
            val rms = sqrt(recent.sumOf { it.toDouble() * it.toDouble() } / recent.size)
            return (rms / 32768.0).coerceIn(0.0, 1.0).toFloat()
        }
    }

    /**
     * Record a fixed-duration sample for enrollment.
     * Returns the MFCC embedding vector.
     */
    suspend fun recordEnrollmentSample(durationMs: Long = 3000): FloatArray =
        withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) return@withContext FloatArray(NUM_MFCC)

            val totalSamples = (SAMPLE_RATE * durationMs / 1000).toInt()
            val samples = ShortArray(totalSamples)
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "No permission for enrollment recording", e)
                return@withContext FloatArray(NUM_MFCC)
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return@withContext FloatArray(NUM_MFCC)
            }

            recorder.startRecording()
            var offset = 0
            while (offset < totalSamples) {
                val read = recorder.read(samples, offset, minOf(1024, totalSamples - offset))
                if (read > 0) offset += read
                else break
            }
            recorder.stop()
            recorder.release()

            extractEmbedding(samples)
        }

    /**
     * Identify speaker from the last captured audio buffer.
     * Returns (name, similarity) or null if no profiles exist.
     */
    suspend fun identifySpeaker(): Pair<String, Float>? = withContext(Dispatchers.IO) {
        val audio = capturedAudio
        if (audio == null || audio.size < SAMPLE_RATE / 2) return@withContext null

        val profiles = dao.getAll()
        if (profiles.isEmpty()) return@withContext null

        val embedding = extractEmbedding(audio)

        var bestName: String? = null
        var bestSimilarity = -1f

        for (profile in profiles) {
            val profileVec = profile.embeddingVector()
            if (profileVec.size != embedding.size) continue
            val sim = cosineSimilarity(embedding, profileVec)
            if (sim > bestSimilarity) {
                bestSimilarity = sim
                bestName = profile.name
            }
        }

        if (bestName != null && bestSimilarity >= SIMILARITY_THRESHOLD) {
            Pair(bestName, bestSimilarity)
        } else {
            null
        }
    }

    /** Enroll a speaker with name and list of embedding samples */
    suspend fun enrollSpeaker(name: String, samples: List<FloatArray>) {
        if (samples.isEmpty()) return
        val averaged = averageEmbeddings(samples)
        val profile = SpeakerProfile.fromVector(name, averaged)
        dao.insert(profile)
    }

    suspend fun getProfiles(): List<SpeakerProfile> = dao.getAll()
    suspend fun profileCount(): Int = dao.count()
    suspend fun deleteProfile(id: Long) = dao.deleteById(id)

    // --- MFCC Extraction ---

    private fun extractEmbedding(samples: ShortArray): FloatArray {
        if (samples.isEmpty()) return FloatArray(NUM_MFCC)

        val floatSamples = FloatArray(samples.size) { samples[it].toFloat() / 32768f }
        val frameSamples = (SAMPLE_RATE * FRAME_SIZE_MS) / 1000
        val stepSamples = (SAMPLE_RATE * FRAME_STEP_MS) / 1000
        val numFrames = (floatSamples.size - frameSamples) / stepSamples

        if (numFrames <= 0) return FloatArray(NUM_MFCC)

        val allMfcc = Array(numFrames) { FloatArray(NUM_MFCC) }

        for (i in 0 until numFrames) {
            val start = i * stepSamples
            val frame = FloatArray(frameSamples) { idx ->
                if (start + idx < floatSamples.size) floatSamples[start + idx] else 0f
            }
            applyHammingWindow(frame)
            val powerSpectrum = computePowerSpectrum(frame)
            val melEnergies = applyMelFilterbank(powerSpectrum)
            allMfcc[i] = dct(melEnergies)
        }

        // Average across all frames to get fixed-length embedding
        val embedding = FloatArray(NUM_MFCC)
        for (frame in allMfcc) {
            for (j in 0 until NUM_MFCC) {
                embedding[j] += frame[j]
            }
        }
        for (j in 0 until NUM_MFCC) {
            embedding[j] /= numFrames
        }

        // L2 normalize
        val norm = sqrt(embedding.sumOf { it.toDouble() * it.toDouble() }).toFloat()
        if (norm > 1e-8f) {
            for (j in embedding.indices) embedding[j] /= norm
        }

        return embedding
    }

    private fun applyHammingWindow(frame: FloatArray) {
        val n = frame.size
        for (i in frame.indices) {
            frame[i] *= (0.54f - 0.46f * cos(2.0 * PI * i / (n - 1))).toFloat()
        }
    }

    private fun computePowerSpectrum(frame: FloatArray): FloatArray {
        val padded = FloatArray(FFT_SIZE)
        frame.copyInto(padded, 0, 0, minOf(frame.size, FFT_SIZE))

        // Real FFT using Cooley-Tukey
        val real = padded.copyOf()
        val imag = FloatArray(FFT_SIZE)
        fft(real, imag)

        val halfSize = FFT_SIZE / 2 + 1
        return FloatArray(halfSize) { i ->
            (real[i] * real[i] + imag[i] * imag[i]) / FFT_SIZE
        }
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        // Bit reversal
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var temp = real[j]; real[j] = real[i]; real[i] = temp
                temp = imag[j]; imag[j] = imag[i]; imag[i] = temp
            }
            var m = n / 2
            while (m >= 1 && j >= m) {
                j -= m
                m /= 2
            }
            j += m
        }
        // Butterfly
        var mLen = 1
        while (mLen < n) {
            val mLen2 = mLen * 2
            val theta = -PI / mLen
            val wReal = cos(theta).toFloat()
            val wImag = sin(theta).toFloat()
            var wr = 1f
            var wi = 0f
            for (k in 0 until mLen) {
                var i = k
                while (i < n) {
                    val jIdx = i + mLen
                    val tr = wr * real[jIdx] - wi * imag[jIdx]
                    val ti = wr * imag[jIdx] + wi * real[jIdx]
                    real[jIdx] = real[i] - tr
                    imag[jIdx] = imag[i] - ti
                    real[i] += tr
                    imag[i] += ti
                    i += mLen2
                }
                val newWr = wr * wReal - wi * wImag
                wi = wr * wImag + wi * wReal
                wr = newWr
            }
            mLen = mLen2
        }
    }

    private fun applyMelFilterbank(powerSpectrum: FloatArray): FloatArray {
        val lowFreq = 0.0
        val highFreq = SAMPLE_RATE / 2.0
        val lowMel = hzToMel(lowFreq)
        val highMel = hzToMel(highFreq)

        val melPoints = DoubleArray(NUM_MEL_FILTERS + 2) { i ->
            lowMel + i * (highMel - lowMel) / (NUM_MEL_FILTERS + 1)
        }
        val binPoints = IntArray(melPoints.size) { i ->
            ((melToHz(melPoints[i]) * FFT_SIZE / SAMPLE_RATE).toInt()).coerceIn(0, powerSpectrum.size - 1)
        }

        val filterEnergies = FloatArray(NUM_MEL_FILTERS)
        for (m in 0 until NUM_MEL_FILTERS) {
            val startBin = binPoints[m]
            val centerBin = binPoints[m + 1]
            val endBin = binPoints[m + 2]

            for (k in startBin until centerBin) {
                if (centerBin > startBin && k < powerSpectrum.size) {
                    filterEnergies[m] += powerSpectrum[k] * (k - startBin).toFloat() / (centerBin - startBin)
                }
            }
            for (k in centerBin until endBin) {
                if (endBin > centerBin && k < powerSpectrum.size) {
                    filterEnergies[m] += powerSpectrum[k] * (endBin - k).toFloat() / (endBin - centerBin)
                }
            }
            // Log compression
            filterEnergies[m] = ln(filterEnergies[m].coerceAtLeast(1e-10f))
        }

        return filterEnergies
    }

    private fun dct(input: FloatArray): FloatArray {
        val output = FloatArray(NUM_MFCC)
        val n = input.size
        for (k in 0 until NUM_MFCC) {
            var sum = 0.0
            for (i in input.indices) {
                sum += input[i] * cos(PI * k * (2 * i + 1) / (2.0 * n))
            }
            output[k] = sum.toFloat()
        }
        return output
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 1e-8) (dot / denom).toFloat() else 0f
    }

    private fun averageEmbeddings(embeddings: List<FloatArray>): FloatArray {
        if (embeddings.isEmpty()) return FloatArray(NUM_MFCC)
        val size = embeddings[0].size
        val result = FloatArray(size)
        for (emb in embeddings) {
            for (i in 0 until minOf(size, emb.size)) {
                result[i] += emb[i]
            }
        }
        for (i in result.indices) result[i] /= embeddings.size
        // L2 normalize
        val norm = sqrt(result.sumOf { it.toDouble() * it.toDouble() }).toFloat()
        if (norm > 1e-8f) {
            for (i in result.indices) result[i] /= norm
        }
        return result
    }
}
