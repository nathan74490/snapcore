package com.snapcore.app.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.view.ViewStub
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.snap.camerakit.Session
import com.snap.camerakit.UnauthorizedApplicationException
import com.snap.camerakit.invoke
import com.snap.camerakit.lenses.LensesComponent
import com.snap.camerakit.lenses.apply
import com.snap.camerakit.lenses.clear
import com.snap.camerakit.lenses.observe
import com.snap.camerakit.lenses.whenHasSome
import com.snap.camerakit.support.camera.AllowsCameraFlash
import com.snap.camerakit.support.camerax.CameraXImageProcessorSource
import com.snapcore.app.BuildConfig
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

typealias Lens = LensesComponent.Lens

/**
 * Message affichable pour une erreur Camera Kit. Le message brut du SDK n'est jamais affiché : il
 * contient le token d'API (ADR-024).
 */
fun cameraKitErrorMessage(error: Throwable): String = when (error) {
    is UnauthorizedApplicationException ->
        "Camera Kit refuse cette app : vérifiez le token et le package ${BuildConfig.APPLICATION_ID} dans le portail Snap."
    else -> "Lenses indisponibles pour le moment."
}

/**
 * Caméra pilotée par Snap Camera Kit (ADR-024) : aperçu, Lenses visage en direct, photo et vidéo
 * rendues avec la Lens active. Les callbacks du SDK sont ramenés sur le thread principal.
 */
class LensCamera(context: Context, lifecycleOwner: LifecycleOwner, videosDir: File) : Closeable {
    private val main = ContextCompat.getMainExecutor(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val source = CameraXImageProcessorSource(context, lifecycleOwner, executor, videosDir)
    private var session: Session? = null
    private var lensesSubscription: Closeable? = null

    /** Crée la session dans [stub] et charge le groupe de Lenses configuré dans local.properties. */
    fun attach(stub: ViewStub, onLenses: (List<Lens>) -> Unit, onError: (Throwable) -> Unit) {
        val created = Session(stub.context) {
            imageProcessorSource(source)
            attachTo(stub)
            handleErrorsWith { error -> main.execute { onError(error) } }
        }
        session = created
        val groupId = BuildConfig.CAMERA_KIT_LENS_GROUP_ID
        if (groupId.isNotBlank()) {
            lensesSubscription = created.lenses.repository.observe(
                LensesComponent.Repository.QueryCriteria.Available(groupId),
            ) { result -> result.whenHasSome { lenses -> main.execute { onLenses(lenses) } } }
        }
    }

    fun startPreview(front: Boolean) = source.startPreview(front)

    /** Active [lens], ou retire la Lens courante si null ; [onApplied] est appelé une fois la Lens prête. */
    fun show(lens: Lens?, onApplied: (Boolean) -> Unit = {}) {
        val processor = session?.lenses?.processor ?: return onApplied(false)
        if (lens == null) processor.clear { ok -> main.execute { onApplied(ok) } }
        else processor.apply(lens) { ok -> main.execute { onApplied(ok) } }
    }

    fun setFlash(on: Boolean) = source.useFlashConfiguration(
        if (on) AllowsCameraFlash.FlashConfiguration.Enabled(FLASH_DELAY_MS, TimeUnit.MILLISECONDS)
        else AllowsCameraFlash.FlashConfiguration.Disabled,
    )

    fun takePhoto(onPhoto: (Bitmap) -> Unit) = source.takePhoto { bitmap -> main.execute { onPhoto(bitmap) } }

    /** Démarre l'enregistrement ; le fermer arrête la vidéo, livrée ensuite à [onVideo]. */
    fun startVideo(onVideo: (File) -> Unit): Closeable = source.takeVideo { file -> main.execute { onVideo(file) } }

    override fun close() {
        lensesSubscription?.close()
        session?.close()
        executor.shutdown()
    }

    private companion object {
        // Laisse au flash le temps d'éclairer la scène avant la prise.
        const val FLASH_DELAY_MS = 500L
    }
}
