package com.disheveled.bills.ai

/**
 * One OpenAI-compatible chat-completions backend, and how each kind of call is tuned on it.
 *
 * The providers disagree on how thinking is switched down — NIM takes a token budget, Gemini
 * and Groq an effort level, and Gemini 3 can't turn it off at all — so every provider carries
 * its own measured values instead of [ReceiptScanner] sending one set to all of them.
 */
class AiProvider internal constructor(
    val name: String,
    internal val endpoint: String,
    internal val apiKey: String,
    internal val model: String,
    /** Reading a photo: perception, so as little thinking as the provider allows. */
    internal val extraction: Tuning,
    /** The split recap and the month note: relational, so a little thinking. */
    internal val note: Tuning,
    /**
     * Most photos of one long receipt this provider reads together in one request. A scan with
     * more skips it; the bar is reading the overlap between photos once, not accepting them.
     */
    internal val maxImages: Int,
) {
    companion object {
        /**
         * First choice, on the free tier. A test receipt came back exactly right on 4 of 4
         * runs in 2.9–23.7s (the spread is the free tier queueing, not the model), and on
         * real receipts it dropped the non-item noise by itself. Recaps: 3 of 3 grounded,
         * 1.7–2.5s. The free tier allows only 5 requests a minute and 20 a day (AI Studio,
         * 2026-11-02), and a split spends two of them; [geminiLite] is behind it for that.
         *
         * Free-tier prompts may be used by Google to improve its products.
         */
        fun gemini(apiKey: String) = AiProvider(
            name = "Gemini",
            endpoint = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            apiKey = apiKey,
            // Not 3.8 Flash: on the free tier (2026-10-25) it answered 503 "high demand" or
            // hung past 45s on most calls, and it rejects "minimal" thinking with a 400.
            model = "gemini-3.6-flash",
            // Gemini 3 can't stop thinking; "minimal" is the floor. Sampling stays at the
            // model's defaults.
            extraction = Tuning(reasoningEffort = "minimal"),
            note = Tuning(reasoningEffort = "low"),
            // A 22-item receipt in three overlapping photos came back exact: the three overlap
            // lines once, two identical lines inside one photo kept as two. 8.2s, 3,876 tokens.
            maxImages = 3,
        )

        /**
         * Gemini 3.5 Flash Lite on the same key: the free tier counts each model separately and
         * gives this one 15 requests a minute and 500 a day, against [gemini]'s 5 and 20. On
         * the 22-item test receipt it was exact as one photo (4.9s) and in three overlapping
         * photos on 2 of 2 runs (4.3–4.7s); notes 6 of 6 grounded in 1.1–1.9s. Behind [gemini]
         * rather than first only because it hasn't been tried on real, crumpled receipts yet.
         *
         * Free-tier prompts may be used by Google to improve its products.
         */
        fun geminiLite(apiKey: String) = AiProvider(
            name = "Gemini Lite",
            endpoint = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            apiKey = apiKey,
            model = "gemini-3.5-flash-lite",
            extraction = Tuning(reasoningEffort = "minimal"),
            note = Tuning(reasoningEffort = "low"),
            maxImages = 3,
        )

        /**
         * Third, on the free Experiment plan, which allows Ministral 14B 30 requests and
         * 937,500 tokens a minute (Small and Medium are 0 on it). The last one that reads long
         * receipts once both Geminis are spent: three overlapping photos of a 22-item receipt came back exact on 2 of
         * 2 runs in 18–29s (7,780 tokens). As one photo the total was right but two identical
         * lines came back merged as one with qty 2. No thinking, so `content` is a plain string;
         * Large 4 reads as well but always thinks and answers in chunks this wire can't read.
         *
         * Notes at 0.2: 6 of 6 grounded, ~2s, chattier than Gemini ("Wah, …"). At the default
         * temperature one split recap of three worked out a difference itself and was dropped.
         *
         * Experiment-plan requests may be used by Mistral to train its models.
         */
        fun mistral(apiKey: String) = AiProvider(
            name = "Mistral",
            endpoint = "https://api.mistral.ai/v1/chat/completions",
            apiKey = apiKey,
            model = "ministral-14b-2512",
            extraction = Tuning(),
            note = Tuning(temperature = 0.2),
            maxImages = 3,
        )

        /**
         * Fallback, on the free tier. The same test receipt came back exactly right in
         * 0.8–1.9s and recaps took ~1s, 3 of 3 grounded — but the free plan allows 7,000
         * input tokens a minute and an image alone counts as 2,048, so only about two scans
         * fit in a minute. Not yet checked against real receipts.
         */
        fun groq(apiKey: String) = AiProvider(
            name = "Groq",
            endpoint = "https://api.groq.com/openai/v1/chat/completions",
            apiKey = apiKey,
            model = "qwen/qwen3.8-27b",
            // "none" is Qwen's instruct mode. Sampling is what Groq publishes for each mode,
            // minus presence_penalty: a penalty on repeated tokens is the last thing a column
            // of prices needs.
            extraction = Tuning(temperature = 0.7, topP = 0.8, reasoningEffort = "none"),
            note = Tuning(temperature = 1.0, topP = 0.95, reasoningEffort = "low", reasoningFormat = "hidden"),
            // The 22-item receipt as one photo came back exact in 2.8s, but in three parts (6,838
            // input tokens, nearly the minute's 7,000) the model degenerated on both runs
            // ("parse-received-received-…") and Groq answered 400 json_validate_failed.
            maxImages = 1,
        )

        /**
         * Nemotron 3 Nano Omni: a 30B mixture-of-experts with ~3B active parameters, so it
         * reads an image in seconds rather than minutes. Reading a receipt is perception, not
         * reasoning: with `reasoning_budget` at 0 this returned the benchmark receipt exactly
         * right on 6 of 6 runs in 4.1–4.9s, against 25s with thinking on and over 10 minutes
         * for Kimi K3. Sampling is what NVIDIA publishes for the model, and what that was
         * measured at.
         *
         * The notes get 512 tokens of thinking. Working out *why* two people owe different
         * amounts is relational: with no budget at all the model read shared dishes as one
         * person's order and explained a one-rupiah rounding remainder as someone eating less.
         * 512 fixed both across repeated runs, for about 3 extra seconds.
         */
        fun nvidia(apiKey: String) = AiProvider(
            name = "NVIDIA",
            endpoint = "https://integrate.api.nvidia.com/v1/chat/completions",
            apiKey = apiKey,
            model = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
            extraction = Tuning(temperature = 0.6, topP = 0.95, reasoningBudget = 0),
            note = Tuning(temperature = 0.6, topP = 0.95, reasoningBudget = 512),
            // It takes three photos, but listed the overlap lines twice on 2 of 2 runs (25
            // items for 22, Rp 53–65 ribu over) and dropped the PB1 line.
            maxImages = 1,
        )
    }
}

/** Per-call request knobs; null leaves the field out so a provider that rejects it never sees it. */
internal data class Tuning(
    val temperature: Double? = null,
    val topP: Double? = null,
    val reasoningBudget: Int? = null,
    val reasoningEffort: String? = null,
    val reasoningFormat: String? = null,
)
