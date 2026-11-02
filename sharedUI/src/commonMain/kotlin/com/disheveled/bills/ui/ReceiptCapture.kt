package com.disheveled.bills.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Fires off a capture. The result comes back through the callback given to [ReceiptCapture]. */
interface CaptureLauncher {
    fun pickFromGallery()
}

/** What the camera can do right now, so the scanner screen knows what to draw. */
enum class CameraStatus {
    /** Opening the camera, or reopening it after permission was granted. */
    Starting,

    /** The preview is live and [CameraSession.capture] will produce a photo. */
    Ready,

    /** Camera access was refused. [CameraSession.requestPermission] asks again. */
    NeedsPermission,

    /** No usable camera, or it failed to open. The gallery is still a way in. */
    Unavailable,
}

/**
 * A live camera, bound to the composition that shows it.
 *
 * The viewfinder is a composable rather than a stream of frames because every platform has a
 * hardware-accelerated surface for exactly this, and copying frames through shared code to
 * draw them ourselves would throw that away for nothing.
 */
interface CameraSession {
    val status: CameraStatus

    /** True between the shutter press and the photo arriving, so the shutter can lock. */
    val capturing: Boolean

    /** Fills [modifier]'s bounds with the live preview. */
    @Composable
    fun Viewfinder(modifier: Modifier)

    /** Takes a photo. It arrives on the callback given to [ReceiptCapture.rememberCameraSession]. */
    fun capture()

    fun requestPermission()
}

/**
 * How this platform gets a receipt photo.
 *
 * Injected rather than declared `expect`/`actual` because the only implementation today is
 * Android's, and passing it in keeps `commonMain` free of platform stubs. iOS adds a second
 * implementation here without touching a line of shared code.
 */
interface ReceiptCapture {
    /**
     * The gallery: up to [maxPhotos] photos at once, for a long receipt photographed in parts.
     * They arrive in the order the platform picker returns them, which need not be top first.
     */
    @Composable
    fun rememberLauncher(maxPhotos: Int, onPicked: (List<CapturedReceipt>) -> Unit): CaptureLauncher

    @Composable
    fun rememberCameraSession(onCaptured: (CapturedReceipt) -> Unit): CameraSession
}
