package com.disheveled.bills.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.disheveled.bills.ui.DashedDivider
import com.disheveled.bills.ui.Leader
import com.disheveled.bills.ui.LocalReducedMotion
import com.disheveled.bills.ui.PlainButton
import com.disheveled.bills.ui.SurfaceCard
import com.disheveled.bills.ui.bottomBarInsets
import com.disheveled.bills.ui.theme.BillsTheme
import com.disheveled.bills.ui.topBarInsets

/**
 * The model reading the photo: the photo, faded, behind a receipt that prints itself while
 * we wait. Always dark, like the camera before it, so the hop from viewfinder to here doesn't
 * flash a light page.
 */
@Composable
internal fun ScanningScreen(
    preview: ImageBitmap?,
    /** How many photos went with this scan; the frame shows the first. */
    photoCount: Int,
    onCancel: () -> Unit,
) = BillsTheme(darkTheme = true) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(scheme.background).topBarInsets().bottomBarInsets(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PhotoFrame(
                preview = preview,
                label = if (photoCount > 1) "$photoCount foto struk kamu" else "foto struk kamu",
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight(0.82f)
                    .padding(start = 40.dp, end = 40.dp, top = 32.dp),
            )
            PrintingReceipt(Modifier.align(Alignment.BottomCenter).width(260.dp))
        }
        Text(
            // Gemini's free tier measured 2.9–23.7s for the same receipt (see AiProvider.gemini):
            // promising five seconds would make every busy hour look broken.
            "biasanya beberapa detik, pas rame bisa 20 detik",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 28.dp),
        )
        Box(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            PlainButton("Batal", onClick = onCancel)
        }
    }
}

/** Where the photo sits: a dashed outline with the photo faded inside it. */
@Composable
private fun PhotoFrame(preview: ImageBitmap?, label: String, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val outline = scheme.outline
    Box(
        modifier.drawWithCache {
            val dashed = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
            )
            val corner = CornerRadius(4.dp.toPx())
            onDrawBehind { drawRoundRect(color = outline, style = dashed, cornerRadius = corner) }
        },
        contentAlignment = Alignment.TopCenter,
    ) {
        if (preview != null) {
            // A receipt photo is mostly white paper: at 22% it still outshone the receipt in
            // front and drowned the label, so it's barely there — a hint of which photo it was.
            Image(
                bitmap = preview,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().padding(1.dp).clip(RoundedCornerShape(4.dp)).alpha(0.12f),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

// Nota's print: unrolls from the top in 420ms, settling down from 8dp above.
private val PrintEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * A receipt of skeleton lines that prints itself once. It promises nothing about progress;
 * the real phases are too few and too uneven to show honestly.
 */
@Composable
private fun PrintingReceipt(modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val printed = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { printed.animateTo(1f, tween(420, easing = PrintEasing)) }

    Box(
        modifier
            .graphicsLayer { translationY = (1f - printed.value) * -8.dp.toPx() }
            .drawWithContent { clipRect(bottom = size.height * printed.value) { this@drawWithContent.drawContent() } },
    ) {
        SurfaceCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("lagi baca struknya…", style = MaterialTheme.typography.labelMedium, color = scheme.onSurface)
                DashedDivider()
                SkeletonLine(120.dp, 44.dp)
                SkeletonLine(96.dp, 44.dp)
                SkeletonLine(136.dp, 36.dp)
                DashedDivider()
                SkeletonLine(48.dp, 64.dp)
            }
        }
    }
}

@Composable
private fun SkeletonLine(name: Dp, amount: Dp) {
    Row(verticalAlignment = Alignment.Bottom) {
        Skeleton(name)
        Leader()
        Skeleton(amount)
    }
}

@Composable
private fun Skeleton(width: Dp) {
    Box(
        Modifier.width(width).height(10.dp).clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}
