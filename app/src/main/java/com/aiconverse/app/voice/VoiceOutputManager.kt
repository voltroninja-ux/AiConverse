package com.aiconverse.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID

/**
 * Wraps Android's built-in TextToSpeech (free, on-device, no API cost) and gives each
 * named speaker a distinct voice so the user can tell the two AIs apart by ear.
 *
 * Strategy for distinctness:
 *  1. If the device has multiple installed TTS voices, assign each speaker a different
 *     actual Voice object (different engine voice = different timbre).
 *  2. Always also offset pitch/rate per speaker as a fallback, so even on devices with
 *     only one voice installed, the two AIs still sound different.
 */
class VoiceOutputManager(
    context: Context,
    private val onSpeakingStateChanged: (isSpeaking: Boolean) -> Unit = {}
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pendingQueue = mutableListOf<Pair<String, String>>() // speaker to text

    // speaker name -> voice profile
    private val profiles = mutableMapOf<String, VoiceProfile>()
    private var speakerOrder = 0

    data class VoiceProfile(val voice: Voice?, val pitch: Float, val rate: Float)

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        onSpeakingStateChanged(true)
                    }
                    override fun onDone(utteranceId: String?) {
                        onSpeakingStateChanged(false)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onSpeakingStateChanged(false)
                    }
                })
                drainQueue()
            }
        }
    }

    /** Call once per AI participant name before speaking, so voices stay stable across the session. */
    fun registerSpeaker(name: String) {
        if (profiles.containsKey(name)) return
        val engine = tts
        val availableVoices: List<Voice> = try {
            engine?.voices?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val chosenVoice = if (availableVoices.isNotEmpty()) {
            availableVoices[speakerOrder % availableVoices.size]
        } else null

        // Fallback differentiation even with a single voice: alternate pitch/rate bands.
        val pitch = if (speakerOrder % 2 == 0) 1.05f else 0.88f
        val rate = if (speakerOrder % 2 == 0) 1.02f else 0.95f

        profiles[name] = VoiceProfile(chosenVoice, pitch, rate)
        speakerOrder++
    }

    fun speak(speaker: String, text: String) {
        if (!ready) {
            pendingQueue.add(speaker to text)
            return
        }
        registerSpeaker(speaker)
        val profile = profiles[speaker] ?: VoiceProfile(null, 1.0f, 1.0f)
        profile.voice?.let { tts?.voice = it }
        tts?.setPitch(profile.pitch)
        tts?.setSpeechRate(profile.rate)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }

    /** Barge-in: user started talking, so cut the AIs off immediately. */
    fun stopSpeaking() {
        tts?.stop()
        onSpeakingStateChanged(false)
    }

    private fun drainQueue() {
        val copy = pendingQueue.toList()
        pendingQueue.clear()
        copy.forEach { (speaker, text) -> speak(speaker, text) }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
