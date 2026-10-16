import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

// Identifiants Snap Camera Kit (ADR-024) : lus dans local.properties, ignoré par git, jamais commités.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun localProperty(name: String): String = localProperties.getProperty(name).orEmpty().trim()

android {
    // Nom de package enregistré dans le projet Firebase (google-services.json).
    namespace = "com.snapcore.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.snapcore.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        manifestPlaceholders["cameraKitApiToken"] = localProperty("cameraKitApiToken")
        buildConfigField("String", "CAMERA_KIT_LENS_GROUP_ID", "\"${localProperty("cameraKitLensGroupId")}\"")
        // Analytics PostHog (ADR-008) : clé de projet publique ; vide = aucun envoi.
        buildConfigField("String", "POSTHOG_API_KEY", "\"${localProperty("posthogApiKey")}\"")
        buildConfigField("String", "POSTHOG_HOST", "\"${localProperty("posthogHost").ifBlank { "https://eu.i.posthog.com" }}\"")
    }

    buildTypes {
        debug {
            // Backend local. Par défaut via "adb reverse tcp:3000 tcp:3000" : le port 3000 du téléphone (ou de
            // l'émulateur) est redirigé vers celui du PC par le câble USB, sans configuration réseau.
            // Sans câble : apiBaseUrl=http://<IP du PC sur le Wi-Fi>:3000/ dans local.properties.
            val apiBaseUrl = localProperty("apiBaseUrl").ifBlank { "http://127.0.0.1:3000/" }
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "API_BASE_URL", "\"https://api.snapcore.example/\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.messaging)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.coil.compose)
    implementation("io.coil-kt.coil3:coil-svg:${libs.versions.coil.get()}")
    implementation(libs.coil.network.okhttp)

    // Capture photo/vidéo (ADR-011) et lecture des vidéos capturées.
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)
    implementation(libs.camerax.view)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    // Filtres couleur choisis après la capture, incrustés dans la vidéo avant l'envoi (ADR-022).
    implementation(libs.media3.effect)
    implementation(libs.media3.transformer)
    // Lenses visage en direct ; Camera Kit pilote toute la caméra (ADR-024).
    implementation(libs.camerakit)
    implementation(libs.camerakit.support.camerax)
    // Analytics d'usage, avec le même identifiant que le backend (ADR-008).
    implementation(libs.posthog.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Redirige le port 3000 de chaque appareil connecté vers le backend local (voir API_BASE_URL) à chaque
// build de debug, y compris depuis le bouton ▶ d'Android Studio. Nécessaire car un redémarrage
// d'adb (ouverture d'Android Studio, rebranchement du câble) efface cette redirection. Sans `-s`,
// `adb reverse` refuse d'agir dès que deux appareils sont branchés (BUG-004 d'ADR-023).
abstract class AdbReverseTask : DefaultTask() {
    @get:Internal abstract val adb: RegularFileProperty
    @get:Inject abstract val execOperations: ExecOperations

    @TaskAction
    fun reverse() {
        val adbPath = adb.get().asFile.absolutePath
        val devices = ByteArrayOutputStream()
        execOperations.exec {
            commandLine(adbPath, "devices")
            standardOutput = devices
            isIgnoreExitValue = true
        }
        // Lignes « <série>	device » ; aucun appareil (build CI, émulateur éteint) : rien à faire.
        val serials = devices.toString().lines().drop(1).mapNotNull { line ->
            line.split("	").takeIf { it.size == 2 && it[1].trim() == "device" }?.get(0)
        }
        serials.forEach { serial ->
            execOperations.exec {
                commandLine(adbPath, "-s", serial, "reverse", "tcp:3000", "tcp:3000")
                isIgnoreExitValue = true
            }
        }
        logger.lifecycle("adb reverse tcp:3000 sur ${serials.size} appareil(s)")
    }
}

val adbReverse = tasks.register<AdbReverseTask>("adbReverse") {
    adb.set(androidComponents.sdkComponents.adb)
}
tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(adbReverse) }
