package com.chandan.localassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Continuous wake-word monitor backed exclusively by Android's on-device
 * SpeechRecognizer. No network recognizer fallback is used.
 *
 * Android 12 (API 31)+ is required for the public on-device recognizer API.
 * The device must also have an on-device English recognition service/model.
 */
class WakeWordListener(
    context: Context,
    private val onWakeWordDetected: () -> Unit,
    private val onUnavailable: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "BixbyWakeWord"
        private const val RESTART_DELAY_MS = 450L
        private const val BUSY_RESTART_DELAY_MS = 1_200L
        private const val LISTENING_TIMEOUT_MS = 15_000L
        private const val WAKE_WORD_COOLDOWN_MS = 5_000L
        private const val PREF_LANGUAGE = "en-US"
        private val WAKE_WORD_REGEX =
            Regex("\\b(?:hey\\s+)?bixby\\b", RegexOption.IGNORE_CASE)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var enabled = false
    private var recognitionInProgress = false
    private var lastWakeWordAt = 0L

    private val restartRunnable = Runnable {
        if (enabled && !recognitionInProgress) {
            startRecognition()
        }
    }

    private val timeoutRunnable = Runnable {
        if (enabled && recognitionInProgress) {
            Log.w(TAG, "Recognition timed out; restarting on-device listener.")
            try {
                speechRecognizer?.cancel()
            } catch (_: RuntimeException) {
                // The recognizer may already have stopped.
            }
            recognitionInProgress = false
            scheduleRestart(RESTART_DELAY_MS)
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "On-device wake-word listener is ready.")
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            recognitionInProgress = false
            mainHandler.removeCallbacks(timeoutRunnable)
            Log.w(TAG, "Speech recognition error: $error")
            scheduleRestart(
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    BUSY_RESTART_DELAY_MS
                } else {
                    RESTART_DELAY_MS
                }
            )
        }

        override fun onResults(results: Bundle?) {
            recognitionInProgress = false
            mainHandler.removeCallbacks(timeoutRunnable)
            inspectRecognitionResults(results)
            scheduleRestart(RESTART_DELAY_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            inspectRecognitionResults(partialResults)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /**
     * Starts continuous local recognition. Call from the main thread after
     * RECORD_AUDIO has been granted and the microphone foreground service
     * has been started while an Activity is visible.
     */
    fun start() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { start() }
            return
        }

        if (enabled) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            reportUnavailable("On-device wake-word recognition requires Android 12 or newer.")
            return
        }

        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            reportUnavailable("Microphone permission has not been granted.")
            return
        }

        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
            reportUnavailable(
                "This device does not provide an on-device speech recognition service. " +
                    "No cloud fallback will be used."
            )
            return
        }

        try {
            speechRecognizer = SpeechRecognizer
                .createOnDeviceSpeechRecognizer(appContext)
                .also { it.setRecognitionListener(recognitionListener) }

            enabled = true
            startRecognition()
        } catch (error: UnsupportedOperationException) {
            reportUnavailable("On-device speech recognition is not supported on this device.")
        } catch (error: SecurityException) {
            reportUnavailable("Microphone access was denied by Android.")
        } catch (error: RuntimeException) {
            reportUnavailable("Could not initialize on-device speech recognition: ${error.message}")
        }
    }

    /** Stops recognition and releases the SpeechRecognizer instance. */
    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stop() }
            return
        }

        enabled = false
        recognitionInProgress = false
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.removeCallbacks(timeoutRunnable)

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: RuntimeException) {
            // Safe during service shutdown.
        } finally {
            speechRecognizer = null
        }
    }

    private fun startRecognition() {
        if (!enabled || recognitionInProgress) return

        val recognizer = speechRecognizer ?: return
        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, PREF_LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }

        try {
            recognitionInProgress = true
            recognizer.startListening(request)
            mainHandler.removeCallbacks(timeoutRunnable)
            mainHandler.postDelayed(timeoutRunnable, LISTENING_TIMEOUT_MS)
        } catch (error: SecurityException) {
            recognitionInProgress = false
            reportUnavailable("Android denied microphone access.")
        } catch (error: RuntimeException) {
            recognitionInProgress = false
            Log.e(TAG, "Unable to start on-device recognition.", error)
            scheduleRestart(BUSY_RESTART_DELAY_MS)
        }
    }

    private fun inspectRecognitionResults(bundle: Bundle?) {
        if (!enabled || bundle == null) return

        val candidates = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return

        val wakeWordFound = candidates.any { candidate ->
            WAKE_WORD_REGEX.containsMatchIn(candidate.trim())
        }
        if (!wakeWordFound) return

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastWakeWordAt < WAKE_WORD_COOLDOWN_MS) return

        lastWakeWordAt = now
        Log.i(TAG, "Wake word detected.")
        onWakeWordDetected()
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!enabled) return
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMs)
    }

    private fun reportUnavailable(message: String) {
        Log.e(TAG, message)
        stop()
        onUnavailable(message)
    }
}
