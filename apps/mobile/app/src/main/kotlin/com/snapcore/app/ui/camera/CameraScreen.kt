package com.snapcore.app.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.MediaKind
import com.snapcore.app.ui.appContainer
import com.snapcore.app.ui.components.PrimaryButton
import com.snapcore.app.ui.components.StatusBarIcons
import com.snapcore.app.ui.theme.SnapColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

private const val MAX_VIDEO_MS = 60_000L // limite d'un snap vidéo (ADR-011)

private enum class Flash(val imageMode: Int) {
    OFF(ImageCapture.FLASH_MODE_OFF),
    ON(ImageCapture.FLASH_MODE_ON),
    AUTO(ImageCapture.FLASH_MODE_AUTO);

    fun next() = entries[(ordinal + 1) % entries.size]
}

private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

@Composable
fun CameraScreen(onCaptured: (CapturedMedia) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    fun granted() = REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var hasPermissions by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hasPermissions = granted() }
    // Permissions caméra et micro demandées au premier accès à l'écran caméra (ADR-011).
    LaunchedEffect(Unit) { if (!hasPermissions) launcher.launch(REQUIRED_PERMISSIONS) }

    StatusBarIcons(dark = false)
    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermissions) {
            CameraContent(onCaptured)
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("SnapCore a besoin de la caméra et du micro pour prendre des snaps.", color = Color.White, textAlign = TextAlign.Center)
                PrimaryButton("Autoriser", { launcher.launch(REQUIRED_PERMISSIONS) }, container = SnapColors.Yellow, content = SnapColors.Black)
            }
        }
    }
}

@Composable
private fun CameraContent(onCaptured: (CapturedMedia) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val capturesDir = appContainer().capturesDir

    var flash by remember { mutableStateOf(Flash.OFF) }
    var front by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordProgress by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE)
            // 720p : une vidéo de 60 s reste nettement sous la limite de taille du backend (ADR-016).
            videoCaptureQualitySelector = QualitySelector.from(Quality.HD)
        }
    }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }
    LaunchedEffect(flash) { controller.imageCaptureFlashMode = flash.imageMode }
    LaunchedEffect(front) {
        controller.cameraSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }

    fun newFile(extension: String) = File(capturesDir, "${UUID.randomUUID()}.$extension")

    fun takePhoto() {
        busy = true
        val file = newFile("jpg")
        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    busy = false
                    onCaptured(CapturedMedia(file, MediaKind.PHOTO))
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    file.delete()
                    error = "Photo impossible : ${exception.message}"
                }
            },
        )
    }

    fun startRecording() {
        val file = newFile("mp4")
        if (flash == Flash.ON) controller.enableTorch(true)
        val options = FileOutputOptions.Builder(file).setDurationLimitMillis(MAX_VIDEO_MS).build()
        recording = controller.startRecording(options, AudioConfig.create(true), ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Status -> recordProgress = event.recordingStats.recordedDurationNanos / 1e6f / MAX_VIDEO_MS
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    recordProgress = 0f
                    controller.enableTorch(false)
                    // La limite de 60 s arrête l'enregistrement proprement : le fichier reste valide.
                    val ok = !event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                    if (ok && file.length() > 0) {
                        onCaptured(CapturedMedia(file, MediaKind.VIDEO))
                    } else {
                        file.delete()
                        error = "Vidéo impossible (${event.error})"
                    }
                }
            }
        }
    }

    AndroidView(
        factory = { PreviewView(it).apply { this.controller = controller } },
        modifier = Modifier.fillMaxSize(),
    )

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { front = !front }, enabled = recording == null) {
                Icon(Icons.Filled.Cameraswitch, "Changer de caméra", tint = Color.White)
            }
            IconButton(onClick = { flash = flash.next() }) {
                val icon = when (flash) {
                    Flash.OFF -> Icons.Filled.FlashOff
                    Flash.ON -> Icons.Filled.FlashOn
                    Flash.AUTO -> Icons.Filled.FlashAuto
                }
                Icon(icon, "Flash", tint = Color.White)
            }
        }

        error?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color(0x99000000)).padding(12.dp))
            LaunchedEffect(it) {
                delay(3000)
                error = null
            }
        }

        val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .size(84.dp)
                .border(6.dp, if (recording != null) SnapColors.SnapRed else Color.White, CircleShape)
                // Tap = photo ; appui maintenu = vidéo jusqu'au relâchement (ADR-011).
                .pointerInput(busy) {
                    if (busy) return@pointerInput
                    detectTapGestures(onPress = {
                        val releasedQuickly = withTimeoutOrNull(longPressTimeout) { tryAwaitRelease() }
                        if (releasedQuickly != null) {
                            if (releasedQuickly) takePhoto()
                        } else {
                            startRecording()
                            tryAwaitRelease()
                            recording?.stop()
                        }
                    })
                },
        ) {
            if (recording != null) {
                CircularProgressIndicator(progress = { recordProgress }, color = SnapColors.SnapRed, strokeWidth = 6.dp, modifier = Modifier.size(84.dp))
            }
            if (busy) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}
