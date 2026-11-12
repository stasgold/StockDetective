package com.stocksdetective.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** The user's Gemini API settings. Only used when they choose the cloud model and enter a key. */
data class CloudConfig(val apiKey: String, val model: String)

/** A model in the cloud. Separate from [OnDeviceModel] because it has no download and can fail per request. */
fun interface CloudModel {
    suspend fun generate(config: CloudConfig, instructions: String, prompt: String, temperature: Float, maxOutputTokens: Int, json: Boolean): String
}

/** A failed cloud request, with a message fit to show in Settings. */
class CloudException(message: String) : IOException(message)

/** POSTs JSON and returns (status code, body). Swappable for tests. */
fun interface JsonPost {
    suspend fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String>
}

object DefaultJsonPost : JsonPost {
    override suspend fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.encodeToByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.use { it.readBytes().decodeToString() } ?: "")
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Gemini through the Gemini API (`generateContent`), billed to the user's own API key from
 * Google AI Studio. Google doesn't let apps use a consumer Gemini subscription, so a key is the
 * supported way to use the larger cloud models. Portfolio data leaves the phone on this path.
 */
class GeminiCloudModel(private val http: JsonPost = DefaultJsonPost) : CloudModel {
    override suspend fun generate(config: CloudConfig, instructions: String, prompt: String, temperature: Float, maxOutputTokens: Int, json: Boolean): String {
        val c = config
        if (c.apiKey.isBlank()) throw CloudException("Add a Gemini API key in Settings.")
        val model = URLEncoder.encode(c.model.trim().removePrefix("models/"), "UTF-8")
        val (code, body) = try {
            http.post(
                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                mapOf("x-goog-api-key" to c.apiKey.trim()),
                requestBody(instructions, prompt, temperature, maxOutputTokens, json),
            )
        } catch (e: IOException) {
            throw CloudException("Couldn't reach Gemini (${e.message ?: "no connection"}).")
        }
        if (code !in 200..299) throw CloudException(errorMessage(code, body, c.model))
        return parseText(body)
    }

    companion object {
        /** Thinking models spend output tokens on reasoning, so give the cloud more room than Nano. */
        internal fun cloudTokens(requested: Int) = maxOf(requested * 4, 2048)

        internal fun requestBody(instructions: String, prompt: String, temperature: Float, maxOutputTokens: Int, json: Boolean): String =
            buildJsonObject {
                putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", instructions) }) } }
                putJsonArray("contents") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) }
                    })
                }
                putJsonObject("generationConfig") {
                    put("temperature", temperature)
                    put("maxOutputTokens", cloudTokens(maxOutputTokens))
                    if (json) put("responseMimeType", "application/json")
                }
            }.toString()

        private val json = Json { ignoreUnknownKeys = true }

        /** The answer text: every non-thought part of the first candidate. */
        internal fun parseText(body: String): String {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw CloudException("Gemini sent a reply the app couldn't read.")
            (root["promptFeedback"] as? JsonObject)?.get("blockReason")?.jsonPrimitive?.contentOrNull?.let {
                throw CloudException("Gemini declined the request ($it).")
            }
            val parts = runCatching {
                root["candidates"]!!.jsonArray[0].jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
            }.getOrNull() ?: throw CloudException("Gemini returned no answer.")
            val text = parts.mapNotNull { p ->
                val o = p.jsonObject
                if (o["thought"]?.jsonPrimitive?.booleanOrNull == true) null else o["text"]?.jsonPrimitive?.contentOrNull
            }.joinToString("")
            if (text.isBlank()) throw CloudException("Gemini returned an empty answer.")
            return text
        }

        internal fun errorMessage(code: Int, body: String, model: String): String {
            val detail = runCatching {
                json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
            }.getOrNull().orEmpty()
            return when {
                code == 400 && detail.contains("API key", ignoreCase = true) -> "The Gemini API key isn't valid. Check it in Google AI Studio."
                code == 401 || code == 403 -> "The Gemini API key was refused. Check it in Google AI Studio."
                code == 404 -> "Gemini doesn't know the model \"$model\". Check the model name in Settings."
                code == 429 -> "Your Gemini API quota is used up for now. Using on-device AI until it resets."
                code >= 500 -> "Gemini is having trouble right now ($code). Try again later."
                else -> "Gemini request failed ($code)${if (detail.isNotEmpty()) ": $detail" else "."}"
            }
        }
    }
}

