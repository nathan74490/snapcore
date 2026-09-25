plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

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
    }

    buildTypes {
        debug {
            // Backend local joint via "adb reverse tcp:3000 tcp:3000" : le port 3000 du téléphone (ou de
            // l'émulateur) est redirigé vers celui du PC par le câble USB, sans configuration réseau.
            buildConfigField("String", "API_BASE_URL", "\"http://127.0.0.1:3000/\"")
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

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Capture photo/vidéo (ADR-011) et lecture des vidéos capturées.
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)
    implementation(libs.camerax.view)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Redirige le port 3000 de l'appareil connecté vers le backend local (voir API_BASE_URL) à chaque
// build de debug, y compris depuis le bouton ▶ d'Android Studio. Nécessaire car un redémarrage
// d'adb (ouverture d'Android Studio, rebranchement du câble) efface cette redirection.
val adbReverse = tasks.register<Exec>("adbReverse") {
    val adb = androidComponents.sdkComponents.adb
    executable(adb.get().asFile)
    args("reverse", "tcp:3000", "tcp:3000")
    // Aucun appareil branché (build CI, émulateur éteint) : ne bloque pas le build.
    isIgnoreExitValue = true
}
tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(adbReverse) }
