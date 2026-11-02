package com.disheveled.bills.ai

import com.disheveled.bills.model.Category
import com.disheveled.bills.model.Receipt
import com.disheveled.bills.split.SplitResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Why a scan didn't produce a receipt. Each case maps to something the UI can actually say. */
enum class FailureReason {
    MissingApiKey,
    Network,
    RateLimited,
    ApiError,
    Refused,
    Truncated,
    Unreadable,
}

/** One photo of a receipt: JPEG or PNG bytes, base64 with no line breaks, already downscaled. */
data class ReceiptPhoto(val base64: String, val mediaType: String = "image/jpeg")

sealed interface ScanOutcome {
    data class Success(val receipt: Receipt) : ScanOutcome
    data class Failure(val reason: FailureReason, val detail: String? = null) : ScanOutcome
}

/**
 * Reads receipts with a vision model and writes the closing recap.
 *
 * It does not do arithmetic. Extraction and prose are what a model is good at; dividing
 * money is what [com.disheveled.bills.split.SplitCalculator] is for.
 *
 * [providers] are tried in order; one without a key is skipped. The next one only gets the
 * call when the previous one couldn't take it (see [call]).
 */
class ReceiptScanner(
    providers: List<AiProvider>,
    private val http: HttpClient = defaultHttpClient(),
    private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) {

    private val providers = providers.filter { it.apiKey.isNotBlank() }

    /** Most photos one scan can take: what the most capable provider with a key reads at once. */
    val maxPhotos: Int = this.providers.maxOfOrNull { it.maxImages } ?: 1

    /**
     * Reads one receipt from [photos]: a single photo, or a long receipt in up to [maxPhotos]
     * overlapping parts, top first. All of them go to the model in one request, so it is the
     * model that lines the parts up and drops the lines two photos share — that can't be done
     * by merging separate reads, since two identical lines can also be two real orders.
     */
    suspend fun scan(photos: List<ReceiptPhoto>): ScanOutcome {
        if (providers.isEmpty()) return ScanOutcome.Failure(FailureReason.MissingApiKey)
        val able = providers.filter { it.maxImages >= photos.size }
        if (photos.isEmpty() || able.isEmpty()) {
            return ScanOutcome.Failure(FailureReason.ApiError, "no provider reads ${photos.size} photos at once")
        }

        val response = when (val result = call(able) { scanRequest(it, photos) }) {
            is Call.Ok -> result.response
            is Call.Failed -> return ScanOutcome.Failure(result.reason, result.detail)
        }

        val choice = response.first
            ?: return ScanOutcome.Failure(FailureReason.Unreadable, "no choices in response")

        when (choice.finishReason) {
            "content_filter" -> return ScanOutcome.Failure(FailureReason.Refused)
            // The schema only guarantees valid JSON if generation actually finished.
            "length" -> return ScanOutcome.Failure(FailureReason.Truncated)
        }

        // Deliberately not reasoning_content: only `content` is schema-constrained.
        val text = choice.message?.content?.withoutThinking()?.takeIf { it.isNotBlank() }
            ?: return ScanOutcome.Failure(FailureReason.Unreadable, "empty content")

        val receipt = runCatching { json.decodeFromString<ReceiptDto>(text).toDomain(today()) }
            .getOrElse { return ScanOutcome.Failure(FailureReason.Unreadable, it.message) }

        if (receipt.items.isEmpty()) {
            return ScanOutcome.Failure(FailureReason.Unreadable, "no line items found")
        }
        return ScanOutcome.Success(receipt)
    }

    /**
     * A short spoken-Indonesian recap of an already-computed split.
     *
     * The numbers arrive as finished text and the model is told not to redo them — it is
     * writing, not calculating. Returns null on failure, or when the recap quotes a number the
     * facts don't have; a missing recap is cosmetic, not a reason to fail the whole split.
     */
    suspend fun summarize(split: SplitResult): String? {
        if (providers.isEmpty()) return null

        val facts = splitFacts(split)
        val recap = (call { noteRequest(it, SUMMARY_PROMPT, facts) } as? Call.Ok)
            ?.response?.let(::noteFrom) ?: return null
        // Without this the model once explained a one-rupiah rounding gap as "Rp 1.000".
        return recap.takeIf { noteIsGrounded(it, facts) }
    }

    /**
     * One or two sentences on what moved in a month, written from [facts] the app computed
     * ([com.disheveled.bills.spend.MonthFacts]). Figures it quotes come wrapped in `**`.
     *
     * Returns null on failure, and also when the note quotes a number the facts don't have:
     * the model is writing, not calculating, and a wrong figure is worse than no note.
     */
    suspend fun monthNote(facts: String): String? {
        if (providers.isEmpty()) return null

        // Picking what moved and why is the same relational reading as the split recap, so it
        // gets the same tuning.
        val note = (call { noteRequest(it, MONTH_NOTE_PROMPT, facts) } as? Call.Ok)
            ?.response?.let(::noteFrom) ?: return null
        return note.takeIf { noteIsGrounded(it, facts) }
    }

    private fun noteRequest(provider: AiProvider, prompt: String, facts: String) = ChatRequest(
        model = provider.model,
        maxTokens = 4_000,
        temperature = provider.note.temperature,
        topP = provider.note.topP,
        reasoningBudget = provider.note.reasoningBudget,
        reasoningEffort = provider.note.reasoningEffort,
        reasoningFormat = provider.note.reasoningFormat,
        responseFormat = noteFormat,
        messages = listOf(
            ChatMessage(role = "system", content = listOf(ContentPart.text(prompt))),
            ChatMessage(role = "user", content = listOf(ContentPart.text(facts))),
        ),
    )

    fun close() = http.close()

    private sealed interface Call {
        data class Ok(val response: ChatResponse) : Call
        data class Failed(val reason: FailureReason, val detail: String?) : Call
    }

    /**
     * Sends [request] to each provider in turn until one answers. Only a provider that couldn't
     * take the call — offline, out of quota, erroring, or a key it rejects — hands over to the
     * next. A reply it did give stands, even an unusable one: a photo one model can't read,
     * another rarely can, and the user would wait through both to find out.
     *
     * When every provider fails, the first failure is the one reported: it is about the
     * provider the user set up first.
     */
    private suspend fun call(to: List<AiProvider> = providers, request: (AiProvider) -> ChatRequest): Call {
        var firstFailure: Call.Failed? = null
        for (provider in to) {
            when (val result = post(provider, request(provider))) {
                is Call.Ok -> return result
                is Call.Failed -> {
                    if (firstFailure == null) firstFailure = result
                    if (result.reason !in HANDS_OVER) return firstFailure
                }
            }
        }
        return firstFailure ?: Call.Failed(FailureReason.MissingApiKey, null)
    }

    private suspend fun post(provider: AiProvider, request: ChatRequest): Call {
        val response = try {
            http.post(provider.endpoint) {
                header("Authorization", "Bearer ${provider.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        } catch (t: Throwable) {
            return Call.Failed(FailureReason.Network, "${provider.name}: ${t.message}")
        }

        if (!response.status.isSuccess()) {
            val body = runCatching { response.bodyAsText() }.getOrNull()
            val message = body
                ?.let { runCatching { json.decodeFromString<ApiErrorEnvelope>(it).message }.getOrNull() }
                ?: body
            val reason = when (response.status.value) {
                401, 403 -> FailureReason.MissingApiKey
                429 -> FailureReason.RateLimited
                else -> FailureReason.ApiError
            }
            return Call.Failed(reason, "${provider.name}: $message")
        }

        // A 200 that isn't a chat completion — a proxy's HTML error page, a cut-off body — is the
        // provider failing, not the receipt being unreadable, so it hands over like a 5xx.
        return runCatching { Call.Ok(response.body<ChatResponse>()) }
            .getOrElse { Call.Failed(FailureReason.ApiError, "${provider.name}: ${it.message}") }
    }

    private companion object {
        /** Failures that say nothing about the receipt, only about the provider that had it. */
        val HANDS_OVER = setOf(
            FailureReason.Network,
            FailureReason.RateLimited,
            FailureReason.ApiError,
            FailureReason.MissingApiKey,
        )
    }
}

internal fun scanRequest(provider: AiProvider, photos: List<ReceiptPhoto>) = ChatRequest(
    model = provider.model,
    maxTokens = 16_384,
    temperature = provider.extraction.temperature,
    topP = provider.extraction.topP,
    reasoningBudget = provider.extraction.reasoningBudget,
    reasoningEffort = provider.extraction.reasoningEffort,
    reasoningFormat = provider.extraction.reasoningFormat,
    responseFormat = ResponseFormat(
        jsonSchema = JsonSchemaSpec(name = "receipt", schema = receiptSchema),
    ),
    messages = listOf(
        ChatMessage(role = "system", content = listOf(ContentPart.text(EXTRACTION_PROMPT))),
        ChatMessage(
            role = "user",
            // Images before text — vision models read the instruction better once the
            // pictures are already in context. One photo gets exactly the request it always
            // has; the parts of a long receipt get told how they fit together.
            content = photos.map { ContentPart.image(it.base64, it.mediaType) } +
                ContentPart.text(if (photos.size == 1) "Extract this receipt." else partsPrompt(photos.size)),
        ),
    ),
)

/**
 * What read a 22-item receipt in three parts exactly (see [AiProvider.gemini]). The "same
 * photo" sentence is for two real orders of the same thing, which dropping repeats would also
 * drop; the last one is for gallery picks, which can arrive in any order.
 */
private fun partsPrompt(count: Int) =
    "These $count photos are parts of one long receipt, in order from the top. Neighbouring photos " +
        "overlap: the last lines of one photo are printed again at the top of the next. List each of " +
        "those repeated lines once. Two identical lines inside the same photo are two separate items; " +
        "keep both. If the photos are out of order, use the repeated lines, the header and the totals " +
        "to put them in order. Extract the whole receipt as one."

/**
 * Strips a leaked chain of thought.
 *
 * The reasoning channel is supposed to arrive in `reasoning_content`, but under load the
 * closing tag and the text before it spill into `content` as "<answer></think><answer>".
 * Only what follows the last tag is the answer; a response without one is returned as is.
 */
private fun String.withoutThinking(): String = substringAfterLast("</think>").trim()

/**
 * Both prose notes come back as `{"note": "..."}`. Free text wasn't safe: the reasoning budget
 * cuts the model off mid-thought, and it carried on thinking in `content` with no `</think>` to
 * strip — "? Actually format "Rp 21.500". Use that. Thus final answer: ..." reached the screen.
 * Constrained to this schema, content can only be the note; measured, the thinking also wrapped
 * up inside the budget instead of being cut.
 */
private val noteFormat = ResponseFormat(
    jsonSchema = JsonSchemaSpec(
        name = "note",
        schema = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") { putJsonObject("note") { put("type", "string") } }
            putJsonArray("required") { add("note") }
        },
    ),
)

@Serializable
private data class NoteDto(val note: String = "")

/** The note out of a reply, or null when it was filtered, cut short or isn't the schema's JSON. */
internal fun noteFrom(response: ChatResponse): String? {
    val choice = response.first ?: return null
    // "length" means the JSON never closed; "content_filter" means there's nothing to show.
    if (choice.finishReason == "length" || choice.finishReason == "content_filter") return null
    val text = choice.message?.content?.withoutThinking() ?: return null
    return runCatching { json.decodeFromString<NoteDto>(text).note.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/** What the split recap is written from: every share and line, already computed. */
internal fun splitFacts(split: SplitResult): String = buildString {
    appendLine("Merchant: ${split.receipt.merchant}")
    appendLine(
        "Bill total: ${split.total} " +
            "(tax ${split.receipt.tax}, service ${split.receipt.serviceCharge})"
    )
    for (share in split.shares) {
        appendLine()
        appendLine("${share.person.name} pays ${share.total}")
        for (line in share.lines) {
            // Naming the owner of every line, instead of letting "had it to himself"
            // be inferred from a missing marker, is what stops the model reporting a
            // shared dish as somebody's personal order.
            val who = when {
                line.sharedBetween <= 1 -> "${share.person.name} alone"
                line.sharedBetween == split.shares.size ->
                    "shared by all ${line.sharedBetween}"
                else -> "shared by ${line.sharedBetween} of them"
            }
            appendLine("  ${line.itemName} - ${line.amount} - $who")
        }
    }
}

/**
 * True when every number in a note the model wrote also appears in the [facts] it was given.
 * Thousands separators are ignored, so "Rp 486.000" in a note matches 486000 in the facts.
 * Both AI notes go through this: the model may pick and phrase figures, never make one up.
 */
internal fun noteIsGrounded(note: String, facts: String): Boolean =
    numbersIn(note).all { it in numbersIn(facts) }

private val number = Regex("""\d+(?:\.\d{3})*""")

private fun numbersIn(text: String): Set<String> =
    number.findAll(text).map { it.value.replace(".", "").trimStart('0').ifEmpty { "0" } }.toSet()

internal val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

internal fun defaultHttpClient(): HttpClient = HttpClient {
    install(ContentNegotiation) { json(json) }
    // Shared NIM workers hand out 503 "ResourceExhausted" when they are full — roughly one
    // call in six while benchmarking. It clears on its own in a second, so one quick retry
    // before ReceiptScanner moves on to the next provider. A 429 is not retried: on a free
    // tier it means the minute's or the day's quota is spent, which no short wait fixes, and
    // the next provider is the faster way out. Retry-After is ignored on purpose: the header
    // can ask for minutes, and someone is staring at a spinner.
    install(HttpRequestRetry) {
        retryIf(maxRetries = 1) { _, response -> response.status.value == 503 }
        exponentialDelay(respectRetryAfterHeader = false)
    }
    // Installed after the retry plugin so the timeout applies per attempt, not to all four.
    // The engine default is far too tight either way: OkHttp reads time out after 10s, and a
    // scan takes ~5s plus however long the photo needs to upload on mobile data.
    install(HttpTimeout) {
        connectTimeoutMillis = 30_000
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
        socketTimeoutMillis = REQUEST_TIMEOUT_MS
    }
}

private const val REQUEST_TIMEOUT_MS = 90L * 1000

/**
 * JSON Schema for the extraction.
 *
 * `strict: true` requires every object to set `additionalProperties: false` and list all of
 * its properties in `required`, so this stays deliberately flat and complete.
 *
 * Constrained decoding writes properties in this order, so `category` comes last: by then
 * the model has already written out the items it is judging.
 */
internal val receiptSchema: JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    putJsonObject("properties") {
        putJsonObject("merchant") { put("type", "string") }
        putJsonObject("date") { put("type", "string") }
        putJsonObject("currency") { put("type", "string") }
        putJsonObject("items") {
            put("type", "array")
            putJsonObject("items") {
                put("type", "object")
                put("additionalProperties", false)
                putJsonObject("properties") {
                    putJsonObject("name") { put("type", "string") }
                    putJsonObject("qty") { put("type", "integer") }
                    putJsonObject("unit_price") { put("type", "integer") }
                    putJsonObject("total_price") { put("type", "integer") }
                }
                putJsonArray("required") {
                    add("name"); add("qty"); add("unit_price"); add("total_price")
                }
            }
        }
        putJsonObject("subtotal") { put("type", "integer") }
        putJsonObject("tax") { put("type", "integer") }
        putJsonObject("service_charge") { put("type", "integer") }
        putJsonObject("discount") { put("type", "integer") }
        putJsonObject("total") { put("type", "integer") }
        putJsonObject("category") {
            put("type", "string")
            putJsonArray("enum") { Category.entries.forEach { add(it.key) } }
        }
    }
    putJsonArray("required") {
        add("merchant"); add("date"); add("currency"); add("items")
        add("subtotal"); add("tax"); add("service_charge"); add("discount"); add("total")
        add("category")
    }
}

private val EXTRACTION_PROMPT = """
    You read restaurant and shop receipts, most of them Indonesian, and return their contents
    as structured data.

    Amounts
    - Every amount is a whole number in the smallest unit of the receipt's own currency.
    - Indonesian rupiah has no subunit and uses "." as a thousands separator, so "Rp 56.000"
      is 56000 and "Rp 1.250.500" is 1250500. Never return 56 or 56000.00.
    - "total_price" is the line total for the whole quantity, not the unit price.

    Items
    - Only actual ordered items belong in "items". Subtotal, tax, service, discount, rounding,
      change and payment lines do not.
    - If a line's quantity is not printed, use 1.

    Charges
    - "tax" covers PB1, PPN, PJK and anything labelled Pajak.
    - "service_charge" covers Service, Servis, SC and similar.
    - "discount" is a positive number representing the amount taken off.

    Missing values
    - Return 0 for any charge the receipt does not show. Do not derive it, do not estimate it,
      and never invent a percentage the receipt does not print. A receipt whose prices already
      include tax and shows no tax line has tax 0.
    - If the merchant name is unreadable, return an empty string.

    Date
    - "date" is the transaction date printed on the receipt, as YYYY-MM-DD.
    - Indonesian receipts put the day first: "05/10/26" and "05-10-2026" are both 2026-10-05.
    - If no date is printed or it is unreadable, return an empty string. Do not guess one.

    Category
    - "category" is what the money was spent on, judged mainly by the kind of merchant and
      then by the items:
      makan: meals, restaurants, warung, food delivery
      kopi: coffee shops, tea and boba drinks, snacks, bakeries, desserts
      belanja: supermarkets, minimarkets, groceries, household goods, online shops
      transport: ride-hailing, fuel, parking, tolls, trains and buses
      tagihan: electricity, water, phone credit, internet, rent, insurance
      hiburan: cinema, games, streaming, concerts, karaoke
      kesehatan: pharmacies, clinics, doctors, gyms
      lainnya: anything that fits none of the above

    Accuracy matters more than completeness: a wrong number is worse than a zero, because
    someone is going to pay it.
""".trimIndent()

private val SUMMARY_PROMPT = """
    You write a short closing note for a bill-splitting app, in casual Indonesian
    (gaya ngobrol, bukan formal). Always answer in Indonesian.

    You are given a split that has already been calculated. Do not recalculate anything and
    do not contradict the numbers you are given.

    Every line says who paid for it. "shared by all N" means everyone chipped in for it;
    "<name> alone" means only that person did. Never describe a shared item as something one
    person ordered.

    Totals that differ by only a few rupiah are rounding left over from dividing, not a real
    difference. Treat those people as paying the same and do not explain the gap.

    Write one or two sentences on why the amounts differ, naming people. If everyone pays
    about the same, say that plainly instead of inventing a reason. Amounts are in rupiah;
    write them like "Rp 62.400". Open with the explanation itself, no greeting, no sign-off,
    no bullet points, no markdown.
""".trimIndent()

private val MONTH_NOTE_PROMPT = """
    You write a short note for the monthly recap of a personal spending log, in casual
    Indonesian (gaya ngobrol, bukan formal), speaking to the reader as "kamu". Always answer
    in Indonesian.

    You are given facts the app has already calculated. Do not calculate anything yourself:
    no new totals, differences, averages or percentages. Copy amounts and percentages exactly
    as the facts write them ("Rp 38.000", "40%"). An amount is how much was spent, never how
    much something rose; only a percentage marked "naik" or "turun" is a change. When the facts
    say there is nothing to compare with, don't call anything a rise or a drop.

    Write one or two sentences on what stands out most this month, naming categories and, when
    the facts name one, the place. Only explain a change with something the facts show, such
    as more of it coming from splits; never guess at reasons. If nothing stands out, say so
    plainly. Never mention anything the facts don't contain, like times of day or weekdays.

    Open with the observation itself: no greeting, no filler like "Kamu lihat", no sign-off.
    Wrap at most two of the figures you quote in double asterisks, like **naik 40%**. No other
    formatting, no bullet points.
""".trimIndent()
