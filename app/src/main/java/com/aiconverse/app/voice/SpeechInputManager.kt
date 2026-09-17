package com.aiconverse.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Wraps Android's SpeechRecognizer for two jobs:
 *
 *  1. Normal "listen for the user's next line" mode, used when the AIs are silent
 *     and waiting for input.
 *  2. Lightweight "barge-in watch" mode, used while the AIs are speaking. Any speech
 *     picked up here is treated as an interruption: playback stops immediately and
 *     the recognized text starts a new round.
 *
 * Caveat (be upfront with the user about this): on-device barge-in has no true echo
 * cancellation, so on a phone's built-in speaker the mic can sometimes pick up the
 * AI's own TTS audio and misfire. It works reliably with headphones/earbuds. If that's
 * a problem, the simplest fix is to disable barge-in watch and require a tap-to-interrupt
 * button instead (see MainActivity's `bargeInEnabled` flag).
 */
class SpeechInputManager(
    private val context: Context,
    private val onFinalResult: (String) -> Unit,
    private val onListeningStateChanged: (Boolean) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var mode: Mode = Mode.IDLE

    enum class Mode { IDLE, NORMAL, BARGE_IN_WATCH }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition isn't available on this device.")
            return
        }
        mode = Mode.NORMAL
        startInternal()
    }

    fun startBargeInWatch() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        mode = Mode.BARGE_IN_WATCH
        startInternal()
    }

    private fun startInternal() {
        stop()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    onListeningStateChanged(true)
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    onListeningStateChanged(false)
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                    if (!text.isNullOrEmpty()) {
                        onFinalResult(text)
                    } else if (mode == Mode.NORMAL) {
                        // nothing heard — restart listening automatically
                        startInternal()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (mode == Mode.BARGE_IN_WATCH) {
                        val text = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                        // Require a few real words before treating it as a genuine interruption,
                        // to reduce false positives from stray noise.
                        if (!text.isNullOrEmpty() && text.split(" ").size >= 2) {
                            isListening = false
                            onListeningStateChanged(false)
                            onFinalResult(text)
                        }
                    }
                }

                override fun onError(error: Int) {
                    isListening = false
                    onListeningStateChanged(false)
                    if (mode == Mode.NORMAL) {
                        // auto-restart on timeout/no-match so listening feels continuous
                        startInternal()
                    }
                }

                override fun onEndOfSpeech() {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(buildIntent())
        }
    }

    fun stop() {
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
        isListening = false
        mode = Mode.IDLE
        onListeningStateChanged(false)
    }
}
