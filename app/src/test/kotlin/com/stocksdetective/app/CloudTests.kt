package com.stocksdetective.app

import com.stocksdetective.app.ai.AIEngine
import com.stocksdetective.app.ai.CloudConfig
import com.stocksdetective.app.ai.CloudException
import com.stocksdetective.app.ai.CloudModel
import com.stocksdetective.app.ai.DetectiveAI
import com.stocksdetective.app.ai.GeminiCloudModel
import com.stocksdetective.app.ai.JsonPost
import com.stocksdetective.app.data.MemoryStore
import com.stocksdetective.app.data.Settings
import com.stocksdetective.kit.CaseBuilder
import com.stocksdetective.kit.DemoMarketData
import com.stocksdetective.kit.HoldingInput
import com.stocksdetective.kit.ReportAuthor
import com.stocksdetective.kit.Sector
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NVDA_JSON = """{"headline":"Nvidia jumps on AI capex","whatHappened":"Nvidia rose 12.6% on hyperscaler spending.","verdict":"justified","verdictReasoning":"Strong news.","watchNext":["Capex"],"confidence":80}"""

class GeminiCloudModelTests {
    private val config = CloudConfig("secret-key", "gemini-3.6-flash")

    @Test fun sendsKeyInHeaderAndJsonMode() = runBlocking {
        var url = ""; var headers = emptyMap<String, String>(); var body = ""
        val http = JsonPost { u, h, b -> url = u; headers = h; body = b; 200 to """{"candidates":[{"content":{"parts":[{"text":"OK"}]}}]}""" }
        assertEquals("OK", GeminiCloudModel(http).generate(config, "sys", "hi", 0.3f, 700, json = true))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent", url)
        assertEquals("secret-key", headers["x-goog-api-key"])
        assertFalse("key never goes in the URL", url.contains("secret"))
        val req = Json.parseToJsonElement(body).jsonObject
        assertEquals("sys", req["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val gen = req["generationConfig"]!!.jsonObject
        assertEquals("application/json", gen["responseMimeType"]!!.jsonPrimitive.content)
        assertEquals(2800, gen["maxOutputTokens"]!!.jsonPrimitive.int)
        assertEquals(0.3f, gen["temperature"]!!.jsonPrimitive.float, 1e-6f)
    }

    @Test fun skipsThoughtsAndJoinsParts() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"thinking...","thought":true},{"text":"{\"a\":"},{"text":"1}"}]}}]}"""
        assertEquals("{\"a\":1}", GeminiCloudModel.parseText(body))
    }

    @Test fun friendlyErrors() {
        assertTrue(GeminiCloudModel.errorMessage(400, """{"error":{"message":"API key not valid. Please pass a valid API key."}}""", "m").contains("isn't valid"))
        assertTrue(GeminiCloudModel.errorMessage(404, "", "gemini-9").contains("\"gemini-9\""))
        assertTrue(GeminiCloudModel.errorMessage(429, "", "m").contains("quota"))
        val blocked = runCatching { GeminiCloudModel.parseText("""{"promptFeedback":{"blockReason":"SAFETY"}}""") }.exceptionOrNull()
        assertTrue(blocked is CloudException && blocked.message!!.contains("SAFETY"))
        val failing = runCatching { runBlocking { GeminiCloudModel { _, _, _ -> 429 to "" }.generate(config, "s", "p", 0f, 10, false) } }.exceptionOrNull()
        assertTrue(failing is CloudException)
    }
}

class ModelRoutingTests {
    private val file = runBlocking { CaseBuilder(DemoMarketData()).buildCase(HoldingInput("NVDA", Sector.TECHNOLOGY, 1.0)) }
    private val config = CloudConfig("k", "gemini-3.6-flash")

    private fun ai(onDevice: FakeModel, cloud: CloudModel, engine: AIEngine = AIEngine.CLOUD, key: CloudConfig? = config) =
        DetectiveAI(onDevice, cloud).also { runBlocking { it.refreshStatus() }; it.engine = engine; it.cloudConfig = key }

    @Test fun cloudAnswersWithTheFullEvidence() = runBlocking {
        val prompts = mutableListOf<String>()
        val r = ai(FakeModel { error("on-device not used") }, CloudModel { _, _, p, _, _, json -> assertTrue(json); prompts += p; NVDA_JSON }).investigate(file)
        assertEquals(ReportAuthor.CLOUD_AI, r.author)
        assertTrue("cloud gets all 10 headline slots", prompts.single().contains("MARKET HEADLINES"))
    }

    @Test fun cloudFailureFallsBackToOnDevice() = runBlocking {
        val detective = ai(FakeModel { NVDA_JSON }, CloudModel { _, _, _, _, _, _ -> throw CloudException("quota") })
        assertEquals(ReportAuthor.ON_DEVICE_AI, detective.investigate(file).author)
        assertEquals("quota", detective.lastCloudError)
    }

    @Test fun noModelsMeansRules() = runBlocking {
        val detective = ai(FakeModel(available = false), CloudModel { _, _, _, _, _, _ -> throw CloudException("offline") })
        assertTrue("cloud is chosen with a key, so AI counts as available", detective.isAvailable)
        assertEquals(ReportAuthor.RULE_BASED, detective.investigate(file).author)
    }

    @Test fun cloudOnlyWhenChosenAndKeyed() = runBlocking {
        val cloud = CloudModel { _, _, _, _, _, _ -> error("cloud must not be called") }
        assertEquals(ReportAuthor.ON_DEVICE_AI, ai(FakeModel { NVDA_JSON }, cloud, engine = AIEngine.ON_DEVICE).investigate(file).author)
        val noKey = ai(FakeModel(available = false), cloud, key = null)
        assertFalse(noKey.isAvailable)
        assertEquals(ReportAuthor.RULE_BASED, noKey.investigate(file).author)
    }

    @Test fun testCloudReportsProblems() = runBlocking {
        assertNull(ai(FakeModel(), CloudModel { _, _, _, _, _, _ -> "OK" }).testCloud(config))
        assertEquals("bad key", ai(FakeModel(), CloudModel { _, _, _, _, _, _ -> throw CloudException("bad key") }).testCloud(config))
    }
}

class CloudSettingsTests {
    @Test fun keyIsStoredSeparatelyAndApplied() {
        val main = MemoryStore(); val secrets = MemoryStore()
        val s = Settings(main, secrets)
        assertEquals(AIEngine.ON_DEVICE, s.aiEngine)
        assertNull(s.cloudConfig)
        assertEquals(Settings.DEFAULT_GEMINI_MODEL, s.geminiModel)
        s.aiEngine = AIEngine.CLOUD
        s.geminiApiKey = "  abc  "
        s.geminiModel = ""
        assertEquals("abc", secrets.getString(Settings.API_KEY))
        assertNull("the key is never in the main (backed-up) file", main.getString(Settings.API_KEY))
        assertEquals(CloudConfig("abc", Settings.DEFAULT_GEMINI_MODEL), s.cloudConfig)
        val ai = DetectiveAI(FakeModel(available = false))
        s.applyTo(ai)
        assertEquals(AIEngine.CLOUD, ai.engine)
        assertNotNull(ai.cloudConfig)
        assertTrue(ai.cloudReady)
    }
}
