package com.snapcore.app.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.snap.camerakit.UnauthorizedApplicationException
import com.snap.camerakit.lenses.LensesComponent
import com.snapcore.app.domain.CapturedMedia
import com.snapcore.app.domain.MediaKind
import com.snapcore.app.ui.appContainer
import com.snapcore.app.ui.components.FigmaAsset
import com.snapcore.app.ui.components.PrimaryButton
import com.snapcore.app.ui.components.StatusBarIcons
import com.snapcore.app.ui.theme.SnapColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.File
import java.util.UUID

private const val MAX_VIDEO_MS = 60_000L // limite d'un snap vidéo (ADR-011)
private val LENS_LOADING = Color(0xFF3CC8AE)

private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

@Composable
fun CameraScreen(
    onCaptured: (CapturedMedia) -> Unit,
    modifier: Modifier = Modifier,
    lensMode: Boolean = false,
    onLensModeChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    fun granted() = REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var hasPermissions by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hasPermissions = granted() }
    // Permissions caméra et micro demandées au premier accès à l'écran caméra (ADR-011).
    LaunchedEffect(Unit) { if (!hasPermissions) launcher.launch(REQUIRED_PERMISSIONS) }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermissions) {
            CameraContent(onCaptured, lensMode, onLensModeChange)
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

// Caméra pilotée par Snap Camera Kit : la Lens choisie s'affiche en direct et se retrouve dans la
// photo ou la vidéo capturée (ADR-024). Les filtres couleur se choisissent ensuite sur l'aperçu (ADR-022).
@Composable
// `lensMode` : une Lens est active ; l'accueil remplace alors sa barre de navigation par la barre
// « ✕ | Pour vous », dont le ✕ remet `lensMode` à false (ADR-024).
private fun CameraContent(onCaptured: (CapturedMedia) -> Unit, lensMode: Boolean, onLensModeChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val capturesDir = appContainer().capturesDir
    val analytics = appContainer().analytics
    val scope = rememberCoroutineScope()

    var flash by remember { mutableStateOf(false) }
    var front by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Closeable?>(null) }
    var recordProgress by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Token refusé : Camera Kit ferme sa session, la caméra reste noire. Message affiché en permanence.
    var cameraKitBlocked by remember { mutableStateOf<String?>(null) }
    var lenses by remember { mutableStateOf<List<Lens>>(emptyList()) }
    var selectedLens by remember { mutableStateOf<Lens?>(null) }
    var lensLoading by remember { mutableStateOf(false) }
    val lensList = rememberLazyListState()

    val camera = remember { LensCamera(context, lifecycleOwner, capturesDir) }
    DisposableEffect(camera) { onDispose { camera.close() } }
    LaunchedEffect(front) { camera.startPreview(front) }
    LaunchedEffect(flash) { camera.setFlash(flash) }
    // L'arc de chargement tourne autour du bouton de capture jusqu'à ce que la Lens soit prête.
    LaunchedEffect(selectedLens) {
        val lens = selectedLens
        lensLoading = lens != null
        camera.show(lens) { if (selectedLens == lens) lensLoading = false }
    }
    // La Lens active est celle qui s'arrête au centre du carrousel, comme dans Snapchat (ADR-024).
    LaunchedEffect(lensList, lenses) {
        snapshotFlow { if (lensList.isScrollInProgress) null else lensList.centeredIndex() }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { index ->
                val lens = lenses.getOrNull(index - 1)
                selectedLens = lens
                lens?.let { analytics.capture("lens_selected", mapOf("lens" to it.name.orEmpty())) }
                // Une Lens visage se joue sur la caméra frontale.
                if (lens?.facingPreference == LensesComponent.Lens.Facing.FRONT) front = true
                onLensModeChange(index > 0)
            }
    }
    // ✕ de la barre du bas : retour à la première position, sans Lens.
    LaunchedEffect(lensMode) { if (!lensMode && lensList.centeredIndex() != 0) lensList.animateScrollToItem(0) }
    DisposableEffect(Unit) { onDispose { onLensModeChange(false) } }

    fun newFile(extension: String) = File(capturesDir, "${UUID.randomUUID()}.$extension")

    fun takePhoto() {
        busy = true
        camera.takePhoto { bitmap ->
            scope.launch {
                val file = newFile("jpg")
                val saved = withContext(Dispatchers.IO) {
                    runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } }.isSuccess
                }
                bitmap.recycle()
                busy = false
                if (saved) {
                    analytics.capture("snap_captured", mapOf("media" to "photo", "lens" to (selectedLens != null)))
                    onCaptured(CapturedMedia(file, MediaKind.PHOTO))
                } else {
                    file.delete()
                    error = "Photo impossible"
                }
            }
        }
    }

    fun startRecording() {
        recording = camera.startVideo { file ->
            recording = null
            recordProgress = 0f
            if (file.length() > 0) {
                analytics.capture("snap_captured", mapOf("media" to "video", "lens" to (selectedLens != null)))
                onCaptured(CapturedMedia(file, MediaKind.VIDEO))
            } else {
                file.delete()
                error = "Vidéo impossible"
            }
        }
    }

    // Progression de l'enregistrement, arrêt automatique à 60 s (ADR-011).
    LaunchedEffect(recording) {
        val current = recording ?: return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - start
            recordProgress = (elapsed.toFloat() / MAX_VIDEO_MS).coerceAtMost(1f)
            if (elapsed >= MAX_VIDEO_MS) {
                current.close()
                break
            }
            delay(100)
        }
    }

    AndroidView(
        factory = { ctx ->
            FrameLayout(ctx).also { frame ->
                val stub = ViewStub(ctx)
                frame.addView(stub, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                camera.attach(
                    stub,
                    onLenses = { lenses = it },
                    onError = {
                        if (it is UnauthorizedApplicationException) cameraKitBlocked = cameraKitErrorMessage(it)
                        else error = cameraKitErrorMessage(it)
                    },
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    )

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { front = !front }, enabled = recording == null) {
                FigmaAsset("14-588_imgIconFlip", "Changer de caméra", tint = Color.White)
            }
            IconButton(onClick = { flash = !flash }) {
                // Mêmes icônes Figma de 30 que la recherche et l'ajout d'amis : la taille ne change pas au clic.
                if(flash) FigmaAsset("14-588_imgIconFlashOn", "Désactiver le flash", tint = Color.White)
                else FigmaAsset("14-588_imgIconFlashOff", "Activer le flash", tint = Color.White)
            }
        }

        cameraKitBlocked?.let {
            Text(it, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }

        error?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color(0x99000000)).padding(12.dp))
            LaunchedEffect(it) {
                delay(3000)
                error = null
            }
        }

        // Nom de la Lens active, en haut à gauche sous l'avatar, comme dans Snapchat.
        AnimatedVisibility(selectedLens != null, Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 60.dp), enter = fadeIn(), exit = fadeOut()) {
            Text(
                selectedLens?.name.orEmpty(),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .6f), blurRadius = 8f)),
            )
        }


        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                if (lenses.isNotEmpty()) {
                    LensCarousel(
                        lenses,
                        lensList,
                        scrollEnabled = recording == null,
                        onTap = { index -> scope.launch { lensList.animateScrollToItem(index) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Au-dessus du carrousel : la Lens au centre apparaît dans l'anneau de capture.
                CaptureButton(recording != null, recordProgress, busy, lensLoading, ::takePhoto, ::startRecording, { recording?.close() })
            }
            // En mode Lens, les libellés s'effacent sans changer la mise en page : le carrousel ne bouge pas.
            val labelsAlpha by animateFloatAsState(if (lensMode) 0f else 1f, label = "labels")
            Column(Modifier.graphicsLayer { alpha = labelsAlpha }, horizontalAlignment = Alignment.CenterHorizontally) {
                // Le mode en cours est en gras avec un point dessous : Snap au repos, Vidéo pendant l'enregistrement.
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                    CaptureModeLabel("Snap", active = recording == null, dot = Color.White)
                    CaptureModeLabel("Vidéo", active = recording != null, dot = SnapColors.SnapRed)
                }
                Text(if (recording != null) "Relâche pour arrêter" else "Appuie pour une photo, maintiens pour filmer",
                    color = Color.White.copy(alpha = .8f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }

    }
}

@Composable
private fun CaptureModeLabel(label: String, active: Boolean, dot: Color) {
    val shadow = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .5f), blurRadius = 6f))
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = if (active) Color.White else Color.White.copy(alpha = .75f), fontSize = 17.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold, style = shadow)
        Box(Modifier.padding(top = 3.dp).size(5.dp).background(if (active) dot else Color.Transparent, CircleShape))
    }
}

// Tap = photo ; appui maintenu = vidéo jusqu'au relâchement (ADR-011).
@Composable
private fun CaptureButton(
    recording: Boolean,
    recordProgress: Float,
    busy: Boolean,
    lensLoading: Boolean,
    onPhoto: () -> Unit,
    onStartVideo: () -> Unit,
    onStopVideo: () -> Unit,
) {
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(86.dp)
            .border(6.dp, if (recording) SnapColors.SnapRed else Color.White, CircleShape)
            .semantics { contentDescription = "Prendre un snap ; maintenir pour filmer" }
            .pointerInput(busy) {
                if (busy) return@pointerInput
                detectTapGestures(onPress = {
                    val releasedQuickly = withTimeoutOrNull(longPressTimeout) { tryAwaitRelease() }
                    if (releasedQuickly != null) {
                        if (releasedQuickly) onPhoto()
                    } else {
                        onStartVideo()
                        tryAwaitRelease()
                        onStopVideo()
                    }
                })
            },
    ) {
        if (recording) {
            CircularProgressIndicator(progress = { recordProgress }, color = SnapColors.SnapRed, strokeWidth = 6.dp, modifier = Modifier.size(86.dp))
        }
        // Lens en cours de chargement : arc qui tourne autour de l'anneau, comme dans Snapchat.
        if (lensLoading && !recording) {
            CircularProgressIndicator(color = LENS_LOADING, strokeWidth = 5.dp, modifier = Modifier.size(86.dp))
        }
        if (busy) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
    }
}
