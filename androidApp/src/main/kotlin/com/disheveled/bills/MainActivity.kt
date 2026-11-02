package com.disheveled.bills

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.disheveled.bills.ai.AiProvider
import com.disheveled.bills.ui.AndroidReceiptCapture

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        // enableEdgeToEdge() leaves the system free to paint its own translucent scrim behind
        // the navigation bar — 90% white in light mode — so the strip under three-button nav
        // reads as #FFFEFE against a #FFF8F3 page. Nothing needs the scrim here: what sits
        // behind the bar is a flat app background, and enableEdgeToEdge already flips the
        // button tint with the theme.
        window.isNavigationBarContrastEnforced = false
        super.onCreate(savedInstanceState)

        setContent {
            App(
                // BuildConfig is generated in this module, so :sharedLogic can never read it —
                // the keys are handed inward instead. Order is fallback order.
                aiProviders = listOf(
                    AiProvider.gemini(BuildConfig.GEMINI_API_KEY),
                    // Same key: Gemini's free quota is per model, so this is extra, not shared.
                    AiProvider.geminiLite(BuildConfig.GEMINI_API_KEY),
                    AiProvider.mistral(BuildConfig.MISTRAL_API_KEY),
                    AiProvider.groq(BuildConfig.GROQ_API_KEY),
                    AiProvider.nvidia(BuildConfig.NVIDIA_API_KEY),
                ),
                store = (application as BillsApplication).store,
                capture = AndroidReceiptCapture(),
                onShareText = ::shareText,
                // "Remove animations" in Accessibility sets this scale to 0.
                reducedMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f,
            )
        }
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share split"))
    }
}
