package com.snapcore.app

import com.snapcore.app.domain.screenNameOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenNameTest {
    @Test
    fun `route typee, nom court sans parametres ni suffixe Route`() {
        assertEquals("Preview", screenNameOf("com.snapcore.app.ui.PreviewRoute/{path}/{kind}"))
        assertEquals("Paywall", screenNameOf("com.snapcore.app.ui.PaywallRoute?returnedFromCheckout={returnedFromCheckout}"))
        assertEquals("Profile", screenNameOf("com.snapcore.app.ui.ProfileRoute"))
    }

    @Test
    fun `accueil et route inconnue, pas d'ecran (l'onglet affiche est envoye a part)`() {
        assertNull(screenNameOf("com.snapcore.app.ui.HomeRoute"))
        assertNull(screenNameOf(null))
    }
}
