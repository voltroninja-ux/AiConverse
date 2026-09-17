package com.aiconverse.app

import com.aiconverse.app.ai.AiParticipant
import com.aiconverse.app.ai.TranscriptEntry
import kotlin.random.Random

/**
 * Orchestrates the group conversation between the user and two AI participants.
 *
 * Design goals for a "natural" feel:
 *  - AIs see the full shared transcript, so they react to each other by name.
 *  - Turn order isn't rigid A/B/A/B — whoever didn't just speak goes next, with a
 *    small chance to let the same AI continue (like a real person finishing a thought).
 *  - The round has a hard cap (so a user isn't stuck waiting / paying forever) but also
 *    a randomized early-stop chance so it doesn't always run to the max — some rounds
 *    are a single reply, some are a short back-and-forth.
 *  - Barge-in: the caller polls `isInterrupted` between turns (wired to the mic) so the
 *    user can cut off the AIs mid-round just by speaking.
 */
class ConversationManager(
    private val participants: List<AiParticipant>,
    private val maxAiTurnsPerRound: Int = 4,
    private val minAiTurnsBeforeEarlyStop: Int = 1
) {
    private val transcript = mutableListOf<TranscriptEntry>()
    private var lastSpeakerIndex: Int = -1

    fun currentTranscript(): List<TranscriptEntry> = transcript.toList()

    fun reset() {
        transcript.clear()
        lastSpeakerIndex = -1
    }

    /**
     * Adds the user's utterance and streams AI replies one at a time via [onEntry],
     * so the caller (UI/TTS) can start speaking the first reply while later ones are
     * still being generated. Stops early if [isInterrupted] returns true, or if the
     * round naturally winds down.
     */
    suspend fun onUserSpeech(
        text: String,
        onEntry: suspend (TranscriptEntry) -> Unit,
        isInterrupted: () -> Boolean = { false }
    ) {
        val userEntry = TranscriptEntry("User", text)
        transcript.add(userEntry)

        var turns = 0
        while (turns < maxAiTurnsPerRound && !isInterrupted()) {
            val speakerIndex = pickNextSpeaker()
            val speaker = participants[speakerIndex]

            val reply = speaker.respond(transcript)
            if (isInterrupted()) return // user jumped in while this was generating; drop it

            val entry = TranscriptEntry(speaker.name, reply)
            transcript.add(entry)
            lastSpeakerIndex = speakerIndex
            onEntry(entry)
            turns++

            // Natural early-stop: after the minimum, there's a chance the "conversation"
            // settles rather than always maxing out. Also stop if the reply clearly hands
            // the floor back to the user (ends in a direct question to them).
            val handsBackToUser = reply.trimEnd().endsWith("?") &&
                    Regex("\\byou\\b", RegexOption.IGNORE_CASE).containsMatchIn(reply)

            if (turns >= minAiTurnsBeforeEarlyStop) {
                val stopChance = if (handsBackToUser) 0.7 else 0.35
                if (Random.nextDouble() < stopChance) break
            }
        }
    }

    private fun pickNextSpeaker(): Int {
        if (participants.size == 1) return 0
        // 80% of the time, alternate to whoever didn't just speak.
        // 20% of the time, let the same AI take a second consecutive turn
        // (mirrors a person adding "actually, one more thing").
        val other = (0 until participants.size).filter { it != lastSpeakerIndex }
        if (lastSpeakerIndex == -1) return other.random()
        return if (Random.nextDouble() < 0.8) other.random() else lastSpeakerIndex
    }
}
