package com.snapcore.app

import com.snapcore.app.domain.avatarDescription
import com.snapcore.app.domain.avatarLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Noms lisibles des options de l'avatar, lus par TalkBack (ADR-028, ADR-033). */
class AvatarLabelsTest {
    // Copie du catalogue du backend (apps/backend/src/modules/avatars/domain/avatar-catalog.ts).
    private val catalog = mapOf(
        "top" to "none hat hijab turban winterHat1 winterHat02 winterHat03 winterHat04 bob bun curly curvy dreads frida fro froBand " +
            "longButNotTooLong miaWallace shavedSides straight02 straight01 straightAndStrand dreads01 dreads02 frizzle shaggy " +
            "shaggyMullet shortCurly shortFlat shortRound shortWaved sides theCaesar theCaesarAndSidePart bigHair",
        "hairColor" to "a55728 2c1b18 b58143 d6b370 724133 4a312c f59797 ecdcbf c93305 e8e1e1",
        "skinColor" to "614335 d08b5b ae5d29 edb98a ffdbb4 fd9841 f8d25c",
        "eyes" to "closed cry default eyeRoll happy hearts side squint surprised winkWacky wink xDizzy",
        "eyebrows" to "angryNatural defaultNatural flatNatural frownNatural raisedExcitedNatural sadConcernedNatural unibrowNatural " +
            "upDownNatural angry default raisedExcited sadConcerned upDown",
        "mouth" to "concerned default disbelief eating grimace sad screamOpen serious smile tongue twinkle",
        "facialHair" to "none beardLight beardMajestic beardMedium moustacheFancy moustacheMagnum",
        "accessories" to "none kurt prescription01 prescription02 round sunglasses wayfarers eyepatch",
        "clothing" to "blazerAndShirt blazerAndSweater collarAndSweater graphicShirt hoodie overall shirtCrewNeck shirtScoopNeck shirtVNeck",
        "clothesColor" to "262e33 65c9ff 5199e4 25557c e6e6e6 929598 3c4f5c b1e2ff a7ffc4 ffafb9 ffffb1 ff488e ff5c5c ffffff",
        "backgroundColor" to "b6e3f4 c0aede d1d4f9 ffd5dc ffdfbf fffaa1 a7ffc4 ffffff",
    ).mapValues { (_, values) -> values.split(" ") }

    @Test
    fun `chaque option du catalogue a un nom en francais, jamais l'identifiant technique`() {
        catalog.forEach { (key, values) ->
            values.forEach { value ->
                val label = avatarLabel(key, value)
                // « Hijab » ou « Turban » se disent pareil en français ; un identifiant technique, lui, a une majuscule
                // interne ou des chiffres (« shortFlat », « 2c1b18 »).
                val technical = value.any { it.isUpperCase() || it.isDigit() }
                assertFalse("$key/$value lu « $label »", technical && label.replace(" ", "").equals(value, ignoreCase = true))
                assertFalse("$key/$value lu « $label »", key.endsWith("Color") && label.any(Char::isDigit))
            }
            assertEquals("deux options de $key ne doivent pas se confondre", values.size, values.map { avatarLabel(key, it) }.toSet().size)
        }
    }

    @Test
    fun `une option inconnue reste lisible`() {
        assertEquals("big hair bun", avatarLabel("top", "bigHairBun"))
        assertEquals("noirs", avatarLabel("hairColor", "#2C1B18"))
    }

    @Test
    fun `l'avatar decrit en une phrase, sans les options absentes`() {
        val options = mapOf("top" to "shortFlat", "hairColor" to "2c1b18", "skinColor" to "edb98a", "eyes" to "default",
            "eyebrows" to "defaultNatural", "mouth" to "smile", "facialHair" to "none", "accessories" to "round",
            "clothing" to "hoodie", "clothesColor" to "65c9ff", "backgroundColor" to "b6e3f4")
        assertEquals("Cheveux courts plats, noirs, peau claire, yeux normaux, sourcils normaux, naturels, bouche sourire, " +
            "lunettes rondes, sweat à capuche bleu ciel, fond bleu ciel", avatarDescription(options))
    }
}
