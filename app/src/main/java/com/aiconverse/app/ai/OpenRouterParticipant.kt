package com.aiconverse.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenRouterParticipant(
    override val name: String,
    override val personaHint: String,
    private val model: String,
    private val apiKey: String
) : AiParticipant {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun respond(transcript: List<TranscriptEntry>): String = withContext(Dispatchers.IO) {
        val systemPrompt = buildString {
            append("You are $name, one voice in a live spoken group conversation with a human user ")
            append("and one other AI. $personaHint\n")
            append("Speak like a real person on a voice call: 1-3 short sentences, contractions, no bullet points, no markdown. ")
            append("You can address the other AI by name and react to what it just said. ")
            append("Don't repeat what was already said, don't re-introduce yourself, don't say 'As an AI'. ")
            append("If the user asked a direct question, answer it directly first.")
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            transcript.takeLast(16).forEach { entry ->
                put(JSONObject().apply {
                    put("role", if (entry.speaker == "User") "user" else "assistant")
                    put("content", if (entry.speaker == "User") entry.text else "${entry.speaker}: ${entry.text}")
                })
            }
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.9)
            put("max_tokens", 150)
        }

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://aiconverse.app")
            .addHeader("X-Title", "AiConverse")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        // Free OpenRouter models are aggressively rate-limited; retry once on 429.
        repeat(2) { attempt ->
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (resp.code == 429 && attempt == 0) {
                    return@use
                }
                if (!resp.isSuccessful) {
                    return@withContext "(${name} couldn't respond right now.)"
                }
                return@withContext try {
                    val json = JSONObject(raw)
                    json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                } catch (e: Exception) {
                    "(${name} had trouble forming a reply.)"
                }
            }
            delay(1500)
        }
        "(${name} is rate-limited right now — try again in a moment.)"
    }
}
