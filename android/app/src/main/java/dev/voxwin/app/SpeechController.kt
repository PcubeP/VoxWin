package dev.voxwin.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Thin wrapper around [android.speech.SpeechRecognizer].
 *
 * Prefers on-device recognition where available (API 31+), falling back to the
 * default network recognizer. Exposes simple start()/stop() and reports results
 * through [Callbacks]. All callbacks are delivered on the main thread (the
 * SpeechRecognizer contract).
 */
class SpeechController(
    private val context: Context,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        /** Interim hypothesis as the user speaks. */
        fun onPartial(text: String) {}
        /** Final recognized utterance. */
        fun onResult(text: String)
        /** Non-fatal error; [message] is human-readable. */
        fun onError(message: String)
        /** Listening state changed (mic open/closed). */
        fun onListeningChanged(listening: Boolean) {}
    }

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (listening) return
        if (!isAvailable) {
            callbacks.onError("Speech recognition is not available on this device")
            return
        }

        val r = recognizer ?: createRecognizer().also { recognizer = it }
        val intent = buildIntent()
        listening = true
        callbacks.onListeningChanged(true)
        r.startListening(intent)
    }

    fun stop() {
        recognizer?.stopListening()
        setNotListening()
    }

    fun cancel() {
        recognizer?.cancel()
        setNotListening()
    }

    /** Release the underlying recognizer. Call from onDestroy/onStop. */
    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        setNotListening()
    }

    private fun setNotListening() {
        if (listening) {
            listening = false
            callbacks.onListeningChanged(false)
        }
    }

    private fun createRecognizer(): SpeechRecognizer {
        val r = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        r.setRecognitionListener(listener)
        return r
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Prefer on-device when the platform honors this hint.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
        }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { callbacks.onPartial(it) }
        }

        override fun onResults(results: Bundle?) {
            val text = firstResult(results)
            setNotListening()
            if (text != null) callbacks.onResult(text)
            else callbacks.onError("No speech recognized")
        }

        override fun onError(error: Int) {
            setNotListening()
            callbacks.onError(describeError(error))
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}

        private fun firstResult(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
    }

    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client side error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
        else -> "Recognition error ($code)"
    }
}
