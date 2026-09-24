# apps/mobile

Application Android native (Kotlin + Jetpack Compose). Projet Gradle indépendant,
hors du périmètre npm/Turborepo — voir la racine du dossier pour le projet Android
(build.gradle.kts, settings.gradle.kts, etc.) une fois généré depuis Android Studio.

## Analytics

Le SDK `posthog-android` sera ajouté une fois le projet Android généré, pour envoyer les événements côté client vers PostHog Cloud (voir ADR-008).
