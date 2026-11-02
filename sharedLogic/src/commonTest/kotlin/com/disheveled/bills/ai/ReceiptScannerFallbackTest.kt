package com.disheveled.bills.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** How a fake provider answers. */
private sealed interface Answer {
    data class Status(val code: Int) : Answer
    data class Reply(val content: String) : Answer
    /** A 200 whose body isn't a chat completion at all, like a proxy's error page. */
    data object Garbled : Answer
    data object Offline : Answer
}

private const val RECEIPT = """{"merchant":"Warung Bu Tini","date":"","currency":"IDR","items":[{"name":"Nasi Rames","qty":1,"unit_price":25000,"total_price":25000}],"subtotal":25000,"tax":0,"service_charge":0,"discount":0,"total":25000,"category":"makan"}"""

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

private fun chatReply(content: String) = buildJsonObject {
    putJsonArray("choices") {
        addJsonObject {
            put("finish_reason", "stop")
            putJsonObject("message") { put("content", content) }
        }
    }
}.toString()

/**
 * Scans [photos] photos across fake providers, each named by its host and answering as given,
 * in that order. Returns the outcome and the hosts that were actually called.
 */
private fun scanWith(
    vararg answers: Pair<String, Answer>,
    withoutKey: Set<String> = emptySet(),
    maxImages: Map<String, Int> = emptyMap(),
    photos: Int = 1,
): Pair<ScanOutcome, List<String>> = runBlocking {
    val called = mutableListOf<String>()
    val engine = MockEngine { request ->
        val host = request.url.host
        called += host
        when (val answer = answers.toMap().getValue(host)) {
            is Answer.Status ->
                respond("""{"error":{"message":"nope"}}""", HttpStatusCode.fromValue(answer.code), jsonHeaders)
            is Answer.Reply -> respond(chatReply(answer.content), HttpStatusCode.OK, jsonHeaders)
            Answer.Garbled -> respond("<html>Bad Gateway</html>", HttpStatusCode.OK, jsonHeaders)
            Answer.Offline -> throw IllegalStateException("connection reset")
        }
    }
    val scanner = ReceiptScanner(fakeProviders(answers.map { it.first }, withoutKey, maxImages), HttpClient(engine) { install(ContentNegotiation) { json(json) } })
    scanner.scan(List(photos) { ReceiptPhoto("aW1n") }) to called
}

private fun fakeProviders(hosts: List<String>, withoutKey: Set<String> = emptySet(), maxImages: Map<String, Int> = emptyMap()) =
    hosts.map { host ->
        AiProvider(
            name = host,
            endpoint = "https://$host/v1/chat/completions",
            apiKey = if (host in withoutKey) "" else "key",
            model = "model",
            extraction = Tuning(),
            note = Tuning(),
            maxImages = maxImages[host] ?: 3,
        )
    }

class ReceiptScannerFallbackTest {

    @Test
    fun aRateLimitHandsTheScanToTheNextProvider() {
        val (outcome, called) = scanWith("a" to Answer.Status(429), "b" to Answer.Reply(RECEIPT))
        assertIs<ScanOutcome.Success>(outcome)
        assertEquals(listOf("a", "b"), called)
    }

    @Test
    fun aServerErrorHandsOver() {
        val (outcome, called) = scanWith("a" to Answer.Status(500), "b" to Answer.Reply(RECEIPT))
        assertIs<ScanOutcome.Success>(outcome)
        assertEquals(listOf("a", "b"), called)
    }

    @Test
    fun aRejectedKeyHandsOver() {
        val (outcome, called) = scanWith("a" to Answer.Status(401), "b" to Answer.Reply(RECEIPT))
        assertIs<ScanOutcome.Success>(outcome)
        assertEquals(listOf("a", "b"), called)
    }

    @Test
    fun beingUnreachableHandsOver() {
        val (outcome, called) = scanWith("a" to Answer.Offline, "b" to Answer.Reply(RECEIPT))
        assertIs<ScanOutcome.Success>(outcome)
        assertEquals(listOf("a", "b"), called)
    }

    @Test
    fun aGarbledReplyHandsOver() {
        val (outcome, called) = scanWith("a" to Answer.Garbled, "b" to Answer.Reply(RECEIPT))
        assertIs<ScanOutcome.Success>(outcome)
        assertEquals(listOf("a", "b"), called)
    }

    @Test
    fun aGarbledReplyIsReportedAsTheServerFailingNotThePhoto() {
        val (outcome, _) = scanWith("a" to Answer.Garbled)
        assertEquals(FailureReason.ApiError, assertIs<ScanOutcome.Failure>(outcome).reason)
    }

    @Test
    fun aReceiptTheModelCouldNotReadStaysUnread() {
        // The second provider would have read it, but a photo one model can't read, another
        // rarely can — the user shouldn't wait through both to find out.
        val (outcome, called) = scanWith("a" to Answer.Reply("struknya buram"), "b" to Answer.Reply(RECEIPT))
        assertEquals(FailureReason.Unreadable, assertIs<ScanOutcome.Failure>(outcome).reason)
        assertEquals(listOf("a"), called)
    }

    @Test
    fun whenEveryProviderFailsTheFirstFailureIsReported() {
        val (outcome, called) = scanWith("a" to Answer.Status(429), "b" to Answer.Status(500))
        val failure = assertIs<ScanOutcome.Failure>(outcome)
        assertEquals(FailureReason.RateLimited, failure.reason)
        assertEquals("a: nope", failure.detail)
        assertEquals(listOf("a", "b"), called)
    }

    @Test
    fun aProviderWithoutAKeyIsNeverCalled() {
        val (outcome, called) = scanWith("a" to Answer.Status(500), "b" to Answer.Reply(RECEIPT), withoutKey = setOf("a"))
        assertIs<ScanOutcome.Success>(outcome)
        assertEquals(listOf("b"), called)
    }

    @Test
    fun aLongReceiptSkipsAProviderThatCantReadItsPartsTogether() {
        val (outcome, called) = scanWith(
            "a" to Answer.Reply(RECEIPT), "b" to Answer.Reply(RECEIPT),
            maxImages = mapOf("a" to 1), photos = 3,
        )
        assertIs<ScanOutcome.Success>(outcome)
        assertEquals(listOf("b"), called)
    }

    @Test
    fun oneScanTakesAsManyPhotosAsTheBestProviderWithAKeyReads() {
        val scanner = { withoutKey: Set<String> ->
            ReceiptScanner(fakeProviders(listOf("a", "b"), withoutKey, mapOf("a" to 3, "b" to 1)), HttpClient(MockEngine { error("no calls") }))
        }
        assertEquals(3, scanner(emptySet()).maxPhotos)
        assertEquals(1, scanner(setOf("a")).maxPhotos)
    }
}
