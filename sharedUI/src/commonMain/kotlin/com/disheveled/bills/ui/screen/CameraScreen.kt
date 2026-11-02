package com.disheveled.bills.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.disheveled.bills.ui.NotaGlyph
import com.disheveled.bills.ui.Glyph
import com.disheveled.bills.ui.pressable
import com.disheveled.bills.ui.CameraSession
import com.disheveled.bills.ui.CameraStatus
import com.disheveled.bills.ui.CapturedReceipt
import com.disheveled.bills.ui.FrameCorners
import com.disheveled.bills.ui.PrimaryButton
import com.disheveled.bills.ui.ScannerOverlay
import com.disheveled.bills.ui.ScannerPaper
import com.disheveled.bills.ui.ScreenTopBar
import com.disheveled.bills.ui.SecondaryButton
import com.disheveled.bills.ui.bottomBarInsets
import com.disheveled.bills.ui.topBarInsets

/**
 * The live viewfinder.
 *
 * Ours rather than the system camera app's: handing off to `ACTION_IMAGE_CAPTURE` means a
 * different screen on every phone, no way to show where the receipt should sit, and a
 * full-resolution file written to disk that we immediately shrink and throw away.
 */
@Composable
internal fun CameraScreen(
    session: CameraSession,
    /** Photos taken so far of a long receipt, waiting for the next part or for [onRead]. */
    pages: List<CapturedReceipt>,
    maxPhotos: Int,
    onClose: () -> Unit,
    onPickFromGallery: () -> Unit,
    onRemovePage: (Int) -> Unit,
    onRead: () -> Unit,
) {
    when (session.status) {
        CameraStatus.NeedsPermission -> CameraBlocked(
            title = "Butuh izin kamera",
            body = "Buat scan struk, app-nya perlu akses kamera. Atau pilih foto yang udah ada " +
                "di galeri.",
            actionLabel = "Kasih izin",
            onAction = session::requestPermission,
            onClose = onClose,
            onPickFromGallery = onPickFromGallery,
        )

        CameraStatus.Unavailable -> CameraBlocked(
            title = "Kameranya ga bisa dibuka",
            body = "Mungkin lagi dipakai app lain. Coba tutup app kamera yang lain, atau ambil " +
                "dari galeri aja.",
            actionLabel = null,
            onAction = {},
            onClose = onClose,
            onPickFromGallery = onPickFromGallery,
        )

        CameraStatus.Starting, CameraStatus.Ready -> Viewfinder(
            session = session,
            pages = pages,
            maxPhotos = maxPhotos,
            onClose = onClose,
            onPickFromGallery = onPickFromGallery,
            onRemovePage = onRemovePage,
            onRead = onRead,
        )
    }
}

@Composable
private fun Viewfinder(
    session: CameraSession,
    pages: List<CapturedReceipt>,
    maxPhotos: Int,
    onClose: () -> Unit,
    onPickFromGallery: () -> Unit,
    onRemovePage: (Int) -> Unit,
    onRead: () -> Unit,
) {
    val ready = session.status == CameraStatus.Ready
    val full = pages.size >= maxPhotos
    // Black, not the app background: until the first frame arrives this is what shows, and a
    // cream flash before a camera opens looks like a bug.
    Box(Modifier.fillMaxSize().background(Color.Black)) {

        session.Viewfinder(Modifier.fillMaxSize())

        // The lens can be pointed at anything, so the chrome brings its own contrast rather
        // than hoping the scene is dark at the top and bottom.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to ScannerOverlay.copy(alpha = 0.70f),
                    0.22f to Color.Transparent,
                    0.62f to Color.Transparent,
                    1f to ScannerOverlay.copy(alpha = 0.85f),
                ),
            ),
        )

        if (ready) {
            FrameCorners(
                Modifier.fillMaxSize()
                    .padding(horizontal = 40.dp)
                    .padding(top = 140.dp, bottom = 250.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().topBarInsets()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.pressable(onClick = onClose, onClickLabel = "Tutup").size(48.dp).clip(CircleShape)
                    .background(ScannerOverlay.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) { Glyph(NotaGlyph.Close, ScannerPaper) }

            Text(
                text = "Scan struk",
                style = MaterialTheme.typography.bodyLarge,
                color = ScannerPaper,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .background(ScannerOverlay.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().bottomBarInsets(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = when {
                    !ready -> "Bentar, lagi buka kamera..."
                    pages.isEmpty() -> "Pas-in struknya di dalam bingkai"
                    full -> "Udah $maxPhotos foto, tinggal dibaca"
                    // The overlap is what lets the model line the parts up and list each line once.
                    else -> "Masih panjang? Foto lanjutannya, sisain 1–2 baris dari foto tadi"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = ScannerPaper.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )

            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Shutter(
                    enabled = ready && !session.capturing && !full,
                    capturing = session.capturing,
                    onClick = session::capture,
                )

                if (pages.isEmpty()) {
                    Box(
                        modifier = Modifier.align(Alignment.CenterStart)
                            .pressable(onClick = onPickFromGallery, onClickLabel = "Pilih dari galeri")
                            .size(48.dp).clip(CircleShape).background(ScannerOverlay.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) { Glyph(NotaGlyph.Gallery, ScannerPaper) }
                } else {
                    PageStack(
                        pages = pages,
                        onRemoveLast = { onRemovePage(pages.lastIndex) },
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    ReadButton(
                        count = pages.size,
                        onClick = onRead,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
    }
}

/**
 * The parts taken so far: the latest on top, the earlier ones peeking out behind it. Tapping
 * it takes the latest back, for a blurred or cut-off shot.
 */
@Composable
private fun PageStack(pages: List<CapturedReceipt>, onRemoveLast: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier.pressable(onClick = onRemoveLast, onClickLabel = "Hapus foto terakhir").size(56.dp),
        contentAlignment = Alignment.Center,
    ) {
        pages.forEachIndexed { index, page ->
            val behind = pages.lastIndex - index
            Box(
                Modifier.offset(x = (-4).dp * behind, y = (-4).dp * behind).size(width = 36.dp, height = 48.dp)
                    .clip(shape).background(ScannerPaper).border(1.dp, ScannerPaper, shape),
            ) {
                page.preview?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(ScannerOverlay)
                .border(1.dp, ScannerPaper, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Glyph(NotaGlyph.Close, ScannerPaper, size = 12.dp, heavy = true) }
    }
}

@Composable
private fun ReadButton(count: Int, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.pressable(onClick = onClick, onClickLabel = "Baca $count foto")
            .height(48.dp).clip(RoundedCornerShape(8.dp)).background(scheme.primary)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Baca ($count)", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary)
    }
}

@Composable
private fun Shutter(enabled: Boolean, capturing: Boolean, onClick: () -> Unit) {
    val tint = ScannerPaper.copy(alpha = if (enabled || capturing) 1f else 0.35f)
    Box(
        modifier = Modifier.pressable(onClick = onClick, enabled = enabled, onClickLabel = "Foto struk")
            .size(74.dp).border(3.dp, tint, CircleShape).padding(6.dp)
            .clip(CircleShape).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        if (capturing) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = ScannerOverlay,
                strokeWidth = 2.dp,
            )
        }
    }
}

/**
 * Shown instead of the viewfinder when there is no camera to show.
 *
 * Drawn on the app's own background rather than over black: with no preview behind it this
 * is an ordinary message screen, and the shared buttons are built for that surface.
 */
@Composable
private fun CameraBlocked(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
    onClose: () -> Unit,
    onPickFromGallery: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(scheme.background)) {
        ScreenTopBar(title = "", onBack = onClose)

        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = scheme.onBackground)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Column(
            modifier = Modifier.bottomBarInsets().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (actionLabel != null) PrimaryButton(label = actionLabel, onClick = onAction)
            SecondaryButton(
                label = "Pilih dari galeri",
                icon = { Glyph(NotaGlyph.Gallery, scheme.onSurface) },
                onClick = onPickFromGallery,
            )
        }
    }
}
