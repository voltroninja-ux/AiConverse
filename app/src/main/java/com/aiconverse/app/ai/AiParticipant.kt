package com.aiconverse.app.ai

/**
 * One entry in the shared conversation transcript.
 * speaker is "User" or the AI's display name (e.g. "Nova", "Echo").
 */
data class TranscriptEntry(
    val speaker: String,
    val text: String
)

/**
 * Common contract for any AI participant in the group conversation.
 * Every participant sees the full shared transcript, so they can react
 * to the user AND to each other.
 */
interface AiParticipant {
    val name: String

    /** A short persona description used to keep replies feeling distinct and human. */
    val personaHint: String

    suspend fun respond(transcript: List<TranscriptEntry>): String
}
