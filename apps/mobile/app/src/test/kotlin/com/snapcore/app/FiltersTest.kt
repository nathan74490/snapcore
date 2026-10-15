package com.snapcore.app

import com.snapcore.app.domain.CameraFilters
import com.snapcore.app.domain.ColorMatrix
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMatrixTest {
    private val eps = 1e-5f

    @Test
    fun `l'identite ne change aucune couleur`() {
        assertArrayEquals(floatArrayOf(0.2f, 0.5f, 0.9f), ColorMatrix.IDENTITY.transform(0.2f, 0.5f, 0.9f), eps)
    }

    @Test
    fun `noir et blanc, les trois canaux egaux a la luminance BT709`() {
        val (r, g, b) = ColorMatrix.grayscale().transform(1f, 0f, 0f).toList()
        assertEquals(0.2126f, r, eps)
        assertEquals(r, g, eps)
        assertEquals(r, b, eps)
        // Le blanc reste blanc.
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), ColorMatrix.grayscale().transform(1f, 1f, 1f), eps)
    }

    @Test
    fun `contraste autour du gris moyen, qui ne bouge pas`() {
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f), ColorMatrix.contrast(1.5f).transform(0.5f, 0.5f, 0.5f), eps)
        assertEquals(0.8f, ColorMatrix.contrast(1.5f).transform(0.7f, 0f, 0f)[0], eps) // 0.7·1.5 - 0.25
    }

    @Test
    fun `then applique d'abord la premiere matrice, puis la seconde`() {
        val gainThenOffset = ColorMatrix.gains(2f, 1f, 1f).then(ColorMatrix.offset(0.1f, 0f, 0f))
        val offsetThenGain = ColorMatrix.offset(0.1f, 0f, 0f).then(ColorMatrix.gains(2f, 1f, 1f))
        assertEquals(0.5f, gainThenOffset.transform(0.2f, 0f, 0f)[0], eps) // 0.2·2 + 0.1
        assertEquals(0.6f, offsetThenGain.transform(0.2f, 0f, 0f)[0], eps) // (0.2 + 0.1)·2
    }

    @Test
    fun `les couleurs restent bornees entre 0 et 1, comme dans le shader`() {
        val vivid = ColorMatrix.saturation(1.7f)
        vivid.transform(1f, 0f, 0f).forEach { assertTrue(it in 0f..1f) }
    }

    @Test
    fun `matrice OpenGL en colonnes, decalages en derniere colonne, alpha inchange`() {
        val m = ColorMatrix.of(
            1f, 2f, 3f, 4f,
            5f, 6f, 7f, 8f,
            9f, 10f, 11f, 12f,
        )
        assertArrayEquals(
            floatArrayOf(
                1f, 5f, 9f, 0f, // colonne R
                2f, 6f, 10f, 0f, // colonne G
                3f, 7f, 11f, 0f, // colonne B
                4f, 8f, 12f, 1f, // décalages, alpha
            ),
            m.toGlMatrix(),
            eps,
        )
    }

    @Test
    fun `matrice Android en lignes 4x5, decalages ramenes sur 0-255, alpha inchange`() {
        val m = ColorMatrix.of(
            1f, 2f, 3f, 0.1f,
            5f, 6f, 7f, 0.2f,
            9f, 10f, 11f, 0.5f,
        )
        assertArrayEquals(
            floatArrayOf(
                1f, 2f, 3f, 0f, 25.5f,
                5f, 6f, 7f, 0f, 51f,
                9f, 10f, 11f, 0f, 127.5f,
                0f, 0f, 0f, 1f, 0f,
            ),
            m.toAndroidMatrix(),
            eps,
        )
    }
}

class CameraFiltersTest {
    @Test
    fun `le premier filtre est Normal, sans effet`() {
        assertEquals(CameraFilters.NORMAL, CameraFilters.ALL.first())
        assertNull(CameraFilters.NORMAL.matrix)
    }

    @Test
    fun `identifiants uniques`() {
        assertEquals(CameraFilters.ALL.size, CameraFilters.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun `navigation en boucle dans les deux sens`() {
        val last = CameraFilters.ALL.lastIndex
        assertEquals(1, CameraFilters.next(0, 1))
        assertEquals(0, CameraFilters.next(last, 1))
        assertEquals(last, CameraFilters.next(0, -1))
    }
}
