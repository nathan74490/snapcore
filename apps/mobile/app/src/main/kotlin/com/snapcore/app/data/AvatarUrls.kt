package com.snapcore.app.data

import com.snapcore.app.BuildConfig
import java.net.URLEncoder

/**
 * URL du rendu PNG d'un avatar (ADR-028). Le backend dessine l'avatar à partir de ses seules options :
 * mêmes options, même URL, donc une image mise en cache une fois pour toutes. Clés triées pour cela.
 */
fun avatarImageUrl(options: Map<String, String>, size: Int = 256): String {
    val query = options.toSortedMap().entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
    return "${BuildConfig.API_BASE_URL.trimEnd('/')}/avatars/avataaars.png?$query&size=$size"
}
