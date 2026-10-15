package com.snapcore.app.domain

/**
 * Transformation affine des couleurs, ligne par ligne : r' = m0·r + m1·g + m2·b + m3 (puis g', b'),
 * sur des couleurs sRGB dans [0, 1]. La même matrice sert à l'aperçu et à l'incrustation dans le
 * média, photo (Android) comme vidéo (Media3), pour un rendu identique (ADR-022).
 */
class ColorMatrix private constructor(private val rows: FloatArray) {

    /** Applique d'abord cette matrice, puis [next]. */
    fun then(next: ColorMatrix): ColorMatrix {
        val out = FloatArray(12)
        for (i in 0..2) {
            for (j in 0..3) {
                var sum = if (j == 3) next.at(i, 3) else 0f
                for (k in 0..2) sum += next.at(i, k) * at(k, j)
                out[i * 4 + j] = sum
            }
        }
        return ColorMatrix(out)
    }

    /** Couleur transformée, bornée à [0, 1] comme à l'affichage. */
    fun transform(r: Float, g: Float, b: Float): FloatArray =
        FloatArray(3) { i -> (at(i, 0) * r + at(i, 1) * g + at(i, 2) * b + at(i, 3)).coerceIn(0f, 1f) }

    /** Matrice 4×4 RGBA en ordre colonne par colonne, format attendu par OpenGL et Media3 (RgbMatrix). */
    fun toGlMatrix(): FloatArray {
        val gl = FloatArray(16)
        for (i in 0..2) for (j in 0..3) gl[j * 4 + i] = at(i, j)
        gl[15] = 1f // alpha inchangé ; les décalages (colonne 3) sont multipliés par alpha = 1
        return gl
    }

    /** Matrice 4×5 ligne par ligne, format d'Android et de Compose : décalages exprimés sur 0..255. */
    fun toAndroidMatrix(): FloatArray {
        val android = FloatArray(20)
        for (i in 0..2) {
            for (j in 0..2) android[i * 5 + j] = at(i, j)
            android[i * 5 + 4] = at(i, 3) * 255f
        }
        android[18] = 1f // alpha inchangé
        return android
    }

    private fun at(row: Int, column: Int) = rows[row * 4 + column]

    companion object {
        // Coefficients de luminance BT.709.
        private const val LR = 0.2126f
        private const val LG = 0.7152f
        private const val LB = 0.0722f

        // Gris moyen en sRGB : pivot du contraste.
        private const val MID_GREY = 0.5f

        fun of(vararg values: Float): ColorMatrix {
            require(values.size == 12) { "Une matrice de couleur a 3 lignes de 4 valeurs" }
            return ColorMatrix(values.copyOf())
        }

        val IDENTITY = of(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)

        fun grayscale() = saturation(0f)

        /** 0 = gris, 1 = inchangé, > 1 = couleurs plus vives. */
        fun saturation(s: Float): ColorMatrix {
            val t = 1f - s
            return of(
                t * LR + s, t * LG, t * LB, 0f,
                t * LR, t * LG + s, t * LB, 0f,
                t * LR, t * LG, t * LB + s, 0f,
            )
        }

        /** < 1 = image plus douce, > 1 = plus contrastée, autour du gris moyen. */
        fun contrast(c: Float): ColorMatrix {
            val shift = MID_GREY * (1f - c)
            return of(c, 0f, 0f, shift, 0f, c, 0f, shift, 0f, 0f, c, shift)
        }

        fun gains(r: Float, g: Float, b: Float) = of(r, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, b, 0f)

        fun offset(r: Float, g: Float, b: Float) = of(1f, 0f, 0f, r, 0f, 1f, 0f, g, 0f, 0f, 1f, b)
    }
}

/** Filtre couleur choisi après la capture ; [matrix] nul = aucun effet (ADR-022). */
data class CameraFilter(val id: String, val label: String, val matrix: ColorMatrix?)

object CameraFilters {
    val NORMAL = CameraFilter("normal", "Normal", null)

    val ALL: List<CameraFilter> = listOf(
        NORMAL,
        CameraFilter("bw", "Noir & blanc", ColorMatrix.grayscale()),
        CameraFilter(
            "sepia", "Sépia",
            ColorMatrix.of(
                0.393f, 0.769f, 0.189f, 0f,
                0.349f, 0.686f, 0.168f, 0f,
                0.272f, 0.534f, 0.131f, 0f,
            ),
        ),
        CameraFilter(
            "vintage", "Vintage",
            ColorMatrix.saturation(0.6f)
                .then(ColorMatrix.contrast(0.85f))
                .then(ColorMatrix.gains(1.06f, 1f, 0.88f))
                .then(ColorMatrix.offset(0.04f, 0.02f, 0f)),
        ),
        CameraFilter("vivid", "Vif", ColorMatrix.saturation(1.6f).then(ColorMatrix.contrast(1.1f))),
        CameraFilter("cold", "Froid", ColorMatrix.gains(0.9f, 0.98f, 1.1f).then(ColorMatrix.offset(0f, 0.01f, 0.04f))),
        CameraFilter("warm", "Chaud", ColorMatrix.gains(1.1f, 1.01f, 0.88f).then(ColorMatrix.offset(0.03f, 0.01f, 0f))),
        CameraFilter("noir", "Noir", ColorMatrix.grayscale().then(ColorMatrix.contrast(1.45f))),
    )

    /** Filtre voisin dans le catalogue, en boucle : [step] = +1 (suivant) ou -1 (précédent). */
    fun next(current: Int, step: Int): Int = Math.floorMod(current + step, ALL.size)
}

/** Incruste un filtre dans un média capturé, avant l'envoi (ADR-022). */
interface FilterRenderer {
    /**
     * @return un nouveau fichier filtré dans le cache privé, ou [media] tel quel pour une photo sans
     * filtre (une vidéo est toujours réencodée, pour tenir sous la limite de taille du backend). Le
     * fichier d'origine n'est jamais modifié : l'utilisateur peut revenir à l'aperçu et choisir un
     * autre filtre.
     */
    suspend fun render(media: CapturedMedia, filter: CameraFilter): CapturedMedia
}
