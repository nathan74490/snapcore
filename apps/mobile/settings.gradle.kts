pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // SDK Mapbox de la Snap Map (ADR-031), téléchargeable sans jeton secret.
        maven { url = uri("https://api.mapbox.com/downloads/v2/releases/maven") }
        // SDK Live commun (ADR-035), construit par JitPack, et audioswitch qu'y prend LiveKit ;
        // limité à ces groupes pour ne pas y chercher le reste.
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.echimine.live-sdk")
                includeGroup("com.github.davidliu")
            }
        }
    }
}

rootProject.name = "snapcore-mobile"
include(":app")
