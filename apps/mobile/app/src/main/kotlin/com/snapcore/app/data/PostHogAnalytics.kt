package com.snapcore.app.data

import android.content.Context
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.snapcore.app.domain.Analytics

/**
 * Adaptateur PostHog cloud (ADR-008). Les écrans sont envoyés à la main depuis la navigation Compose,
 * la capture automatique ne voyant qu'une seule activité. Pas d'enregistrement de session : la caméra
 * et les snaps ne doivent jamais quitter le téléphone par ce canal.
 */
class PostHogAnalytics(context: Context, apiKey: String, host: String) : Analytics {
    init {
        PostHogAndroid.setup(
            context,
            PostHogAndroidConfig(apiKey, host).apply {
                captureScreenViews = false
                captureApplicationLifecycleEvents = true
                sessionReplay = false
            },
        )
    }

    override fun identify(userId: String) = PostHog.identify(userId)

    override fun reset() = PostHog.reset()

    override fun screen(name: String) = PostHog.screen(name)

    override fun capture(event: String, properties: Map<String, Any>) = PostHog.capture(event, properties = properties)
}
