package com.disheveled.bills.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import androidx.core.graphics.scale

/**
 * Long edge we downscale to before sending.
 *
 * Vision input is billed per 28px patch. A raw phone photo can run ~3900 visual tokens; at
 * 1568px it is closer to 1560 for the same readable text, so this roughly halves the
 * per-scan cost without hurting extraction.
 */
private const val MAX_LONG_EDGE = 1568

private const val JPEG_QUALITY = 85

class AndroidReceiptCapture : ReceiptCapture {

    @Composable
    override fun rememberLauncher(maxPhotos: Int, onPicked: (List<CapturedReceipt>) -> Unit): CaptureLauncher {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val prepare = { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                scope.launch {
                    val photos = uris.take(maxPhotos).mapNotNull { prepareFromUri(context, it) }
                    if (photos.isNotEmpty()) onPicked(photos)
                }
            }
        }
        val single = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            prepare(listOfNotNull(uri))
        }
        // PickMultipleVisualMedia refuses a limit under 2, so a one-photo scan keeps the plain picker.
        val multiple = rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(maxPhotos.coerceAtLeast(2)),
        ) { uris -> prepare(uris) }

        return remember(context, maxPhotos) {
            object : CaptureLauncher {
                override fun pickFromGallery() {
                    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    if (maxPhotos > 1) multiple.launch(request) else single.launch(request)
                }
            }
        }
    }

    @Composable
    override fun rememberCameraSession(onCaptured: (CapturedReceipt) -> Unit): CameraSession {
        val context = LocalContext.current
        val activity = LocalActivity.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val scope = rememberCoroutineScope()
        val session = remember { CameraXSession() }

        // Whether the system dialog has been put in front of the user yet. Without this, the
        // very first "no" looks identical to a permanent refusal.
        var asked by remember { mutableStateOf(false) }

        // Bumped, rather than keying off status, so that the bind effect setting the status
        // to Ready doesn't immediately tear down the camera it just opened.
        var attempt by remember { mutableIntStateOf(0) }

        val ask = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                session.status = CameraStatus.Starting
                attempt++
            } else {
                session.status = CameraStatus.NeedsPermission
            }
        }

        SideEffect {
            session.onRequest = {
                // Once the user has picked "don't allow" twice, Android stops showing the
                // dialog and the launcher returns denied without anything appearing on
                // screen. Its own settings page is then the only route left, and
                // shouldShowRequestPermissionRationale is how that state is detected.
                val silentlyRefused = asked && activity?.shouldShowRequestPermissionRationale(
                    Manifest.permission.CAMERA,
                ) == false
                if (silentlyRefused) {
                    context.openAppSettings()
                } else {
                    asked = true
                    ask.launch(Manifest.permission.CAMERA)
                }
            }
            session.onCapture = { session.takePicture(context, scope, onCaptured) }
        }

        DisposableEffect(attempt, lifecycleOwner) {
            var provider: ProcessCameraProvider? = null
            val job = scope.launch {
                if (!context.hasCameraPermission()) {
                    session.status = CameraStatus.NeedsPermission
                    // Asking straight away: the user got here by tapping "Scan Struk", so the
                    // prompt is expected. Only the first arrival, though — after that the
                    // screen's own button is the way to ask again.
                    if (!asked) {
                        asked = true
                        ask.launch(Manifest.permission.CAMERA)
                    }
                    return@launch
                }
                session.status = CameraStatus.Starting
                runCatching {
                    val cameraProvider = context.awaitCameraProvider()
                    provider = cameraProvider

                    val preview = Preview.Builder().build().apply {
                        setSurfaceProvider { request -> session.surfaceRequest = request }
                    }
                    // Latency over quality on purpose: the photo is downscaled to
                    // MAX_LONG_EDGE before it is ever sent, so the extra detail a slower
                    // capture buys is thrown away a moment later.
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                    )
                    session.imageCapture = capture
                    session.status = CameraStatus.Ready
                }.onFailure {
                    session.status = CameraStatus.Unavailable
                }
            }
            onDispose {
                job.cancel()
                provider?.unbindAll()
                session.imageCapture = null
                // The viewfinder would otherwise keep drawing against a surface nobody feeds.
                session.surfaceRequest = null
            }
        }

        return session
    }
}

private class CameraXSession : CameraSession {
    override var status by mutableStateOf(CameraStatus.Starting)
    override var capturing by mutableStateOf(false)

    var surfaceRequest by mutableStateOf<SurfaceRequest?>(null)
    var imageCapture: ImageCapture? = null

    var onCapture: () -> Unit = {}
    var onRequest: () -> Unit = {}

    @Composable
    override fun Viewfinder(modifier: Modifier) {
        surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = modifier) }
    }

    override fun capture() = onCapture()

    override fun requestPermission() = onRequest()

    fun takePicture(
        context: Context,
        scope: CoroutineScope,
        onCaptured: (CapturedReceipt) -> Unit,
    ) {
        val capture = imageCapture ?: return
        if (capturing) return
        capturing = true

        capture.takePicture(
            context.mainExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    // ImageCapture hands back JPEG in a single plane unless the output format
                    // is reconfigured, which it isn't. Taking the bytes rather than
                    // ImageProxy.toBitmap() keeps a 50MP sensor from ever materialising a
                    // 200MB ARGB bitmap just to be shrunk to 1568px.
                    val jpeg = image.planes[0].buffer.let { buffer ->
                        ByteArray(buffer.remaining()).also(buffer::get)
                    }
                    val degrees = image.imageInfo.rotationDegrees
                    image.close()

                    scope.launch {
                        val receipt = prepareFromJpeg(jpeg, degrees.toFloat())
                        capturing = false
                        receipt?.let(onCaptured)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                }
            },
        )
    }
}

private fun Context.hasCameraPermission(): Boolean =
    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    continuation.resume(future.get()) { _, _, _ -> }
                } catch (throwable: Throwable) {
                    continuation.resumeWith(Result.failure(throwable))
                }
            },
            mainExecutor,
        )
    }

/** Gallery path: the file on disk records its orientation in EXIF rather than in its pixels. */
private suspend fun prepareFromUri(context: Context, uri: Uri): CapturedReceipt? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null

            val rotation = runCatching {
                when (
                    ExifInterface(bytes.inputStream())
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }.getOrDefault(0f)

            encode(decodeSampled(bytes) ?: return@runCatching null, rotation)
        }.getOrNull()
    }

/**
 * Camera path: CameraX reports the rotation separately in [ImageProxy.getImageInfo] rather
 * than leaving it in EXIF, and BitmapFactory ignores EXIF anyway, so this rotates once.
 */
private suspend fun prepareFromJpeg(jpeg: ByteArray, rotation: Float): CapturedReceipt? =
    withContext(Dispatchers.IO) {
        runCatching { encode(decodeSampled(jpeg) ?: return@runCatching null, rotation) }.getOrNull()
    }

private fun decodeSampled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun encode(decoded: Bitmap, rotation: Float): CapturedReceipt {
    val upright = if (rotation == 0f) decoded else {
        Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height,
            Matrix().apply { postRotate(rotation) }, true,
        ).also { if (it != decoded) decoded.recycle() }
    }

    val scaled = scaleToLongEdge(upright)
    val jpeg = ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        out.toByteArray()
    }

    return CapturedReceipt(
        base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP),
        mediaType = "image/jpeg",
        preview = scaled.asImageBitmap(),
    )
}

/** Largest power-of-two downsample that still leaves us above the target long edge. */
private fun sampleSizeFor(width: Int, height: Int): Int {
    var sample = 1
    var longEdge = maxOf(width, height)
    while (longEdge / 2 >= MAX_LONG_EDGE) {
        longEdge /= 2
        sample *= 2
    }
    return sample
}

private fun scaleToLongEdge(source: Bitmap): Bitmap {
    val longEdge = maxOf(source.width, source.height)
    if (longEdge <= MAX_LONG_EDGE) return source
    val ratio = MAX_LONG_EDGE.toFloat() / longEdge
    val scaled = source.scale(
        (source.width * ratio).toInt().coerceAtLeast(1),
        (source.height * ratio).toInt().coerceAtLeast(1),
    )
    if (scaled != source) source.recycle()
    return scaled
}
