package com.stocksdetective.app.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Feature
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Whether on-device AI can answer right now. */
sealed interface AiStatus {
    data object Checking : AiStatus
    data object Available : AiStatus
    /** Supported phone; the model must be downloaded first (Settings offers the download). */
    data object Downloadable : AiStatus
    data class Downloading(val bytes: Long) : AiStatus
    data class Unavailable(val reason: String) : AiStatus
}

/** The language model the detective talks to. Swappable for tests. */
interface OnDeviceModel {
    suspend fun status(): AiStatus
    /** Starts (or resumes) the model download; emits progress and finishes with Available or Unavailable. */
    fun download(): Flow<AiStatus>
    suspend fun warmup()
    suspend fun generate(instructions: String, prompt: String, temperature: Float, maxOutputTokens: Int = 512): String
}

/**
 * Gemini Nano through ML Kit's GenAI Prompt API (Android AICore). Runs entirely on the phone,
 * like Apple's on-device Foundation Models. Supported on recent flagship phones (e.g. Pixel 9+,
 * Galaxy S25); everywhere else the app uses the rule-based analyst.
 */
class GeminiNanoModel : OnDeviceModel {
    private val client: GenerativeModel by lazy { Generation.getClient() }
    private var systemPromptSupported: Boolean? = null

    override suspend fun status(): AiStatus = try {
        when (client.checkStatus()) {
            FeatureStatus.AVAILABLE -> AiStatus.Available
            FeatureStatus.DOWNLOADABLE -> AiStatus.Downloadable
            FeatureStatus.DOWNLOADING -> AiStatus.Downloading(0)
            else -> AiStatus.Unavailable("This phone doesn't support on-device Gemini Nano. Using the built-in rule-based analyst.")
        }
    } catch (e: Exception) {
        // AICore missing or outdated, or the device isn't eligible.
        AiStatus.Unavailable("On-device AI isn't available on this phone (${e.message ?: "AICore unavailable"}). Using the rule-based analyst.")
    }

    override fun download(): Flow<AiStatus> = client.download().map { s ->
        when (s) {
            is DownloadStatus.DownloadProgress -> AiStatus.Downloading(s.totalBytesDownloaded)
            is DownloadStatus.DownloadCompleted -> AiStatus.Available
            is DownloadStatus.DownloadFailed -> AiStatus.Unavailable("The Gemini Nano download failed: ${s.e.message}. Try again on Wi-Fi.")
            else -> AiStatus.Downloading(0)
        }
    }

    override suspend fun warmup() {
        runCatching { client.warmup() }
    }

    override suspend fun generate(instructions: String, prompt: String, temperature: Float, maxOutputTokens: Int): String {
        val useSystem = systemPromptSupported ?: runCatching { Feature.SYSTEM_PROMPT in client.getAvailableFeatures() }.getOrDefault(false)
            .also { systemPromptSupported = it }
        val request = if (useSystem) {
            generateContentRequest(SystemInstruction(instructions), TextPart(prompt)) {
                this.temperature = temperature
                this.maxOutputTokens = maxOutputTokens
            }
        } else {
            generateContentRequest(TextPart("$instructions\n\n$prompt")) {
                this.temperature = temperature
                this.maxOutputTokens = maxOutputTokens
            }
        }
        return client.generateContent(request).candidates.firstOrNull()?.text.orEmpty()
    }
}
