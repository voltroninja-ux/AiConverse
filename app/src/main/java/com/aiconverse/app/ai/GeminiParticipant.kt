package com.aiconverse.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiParticipant(
    override val name: String,
    override val personaHint: String,
    private val apiKey: String,
    private val model: String = "gemini-1.5-flash"
) : AiParticipant {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun respond(transcript: List<TranscriptEntry>): String = withContext(Dispatchers.IO) {
        val historyText = transcript.takeLast(16).joinToString("\n") { "${it.speaker}: ${it.text}" }

        val prompt = buildString {
            append("You are $name, one voice in a live spoken group conversation with a human user ")
            append("and one other AI. $personaHint\n")
            append("Ground rules for how you talk:\n")
            append("- Speak like a real person on a voice call: 1-3 short sentences, contractions, no bullet points, no markdown.\n")
            append("- You can address the other AI by name and react to what it just said, agree, disagree, or build on it.\n")
            append("- Don't repeat what was already said. Don't re-introduce yourself. Don't say 'As an AI'.\n")
            append("- If the user asked a direct question, prioritize actually answering it.\n")
            append("- Occasionally it's fine to keep it very short, like a real reaction (\"Ha, fair point.\" / \"Wait, really?\").\n\n")
            append("Conversation so far:\n")
            append(historyText)
            append("\n\n$name:")
        }

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.9)
                put("maxOutputTokens", 200)
            })
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                return@withContext "(${name} couldn't respond right now.)"
            }
            try {
                val json = JSONObject(raw)
                json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()
            } catch (e: Exception) {
                "(${name} had trouble forming a reply.)"
            }
        }
    }
}
