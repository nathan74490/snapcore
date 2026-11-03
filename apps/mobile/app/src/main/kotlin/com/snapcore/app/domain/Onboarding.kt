package com.snapcore.app.domain

import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random

// Inscription et premiers pas façon Snapchat (ADR-029).

/** Réponse du backend sur un pseudo, avant même que le compte existe. */
data class UsernameAvailability(val available: Boolean, val reason: String? = null)

/**
 * Pseudo proposé d'office, comme Snapchat (« saml20244814 ») : le prénom sans accents, l'initiale du nom,
 * puis des chiffres. Toujours conforme à la règle du backend (une lettre d'abord, 15 caractères au plus).
 */
fun suggestUsername(firstName: String, lastName: String?, random: Random = Random.Default): String {
    fun letters(raw: String?) = Normalizer.normalize(raw.orEmpty(), Normalizer.Form.NFD)
        .lowercase(Locale.ROOT).filter { it in 'a'..'z' }
    val base = (letters(firstName).take(8) + letters(lastName).take(1)).ifEmpty { "snap" }
    val digits = (1..(15 - base.length).coerceAtMost(6)).joinToString("") { random.nextInt(10).toString() }
    return base + digits
}

// --- Pays et indicatifs -------------------------------------------------------------------------

data class Country(val code: String, val name: String, val dialCode: String) {
    /** Drapeau en emoji, à partir des deux lettres du code ISO. */
    val flag get() = code.uppercase(Locale.ROOT).map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")

    /** Numéro complet tel qu'enregistré : indicatif, puis le numéro national sans son 0 initial. */
    fun international(national: String): String = "+$dialCode" + national.filter(Char::isDigit).trimStart('0')
}

object Countries {
    // Indicatifs internationaux par code ISO 3166-1 (UIT-T E.164).
    private const val DIAL_CODES =
        "AF93 AX358 AL355 DZ213 AS1 AD376 AO244 AI1 AG1 AR54 AM374 AW297 AU61 AT43 AZ994 BS1 BH973 BD880 BB1 BY375 " +
            "BE32 BZ501 BJ229 BM1 BT975 BO591 BA387 BW267 BR55 IO246 VG1 BN673 BG359 BF226 BI257 KH855 CM237 CA1 CV238 " +
            "KY1 CF236 TD235 CL56 CN86 CX61 CC61 CO57 KM269 CG242 CD243 CK682 CR506 CI225 HR385 CU53 CW599 CY357 CZ420 " +
            "DK45 DJ253 DM1 DO1 EC593 EG20 SV503 GQ240 ER291 EE372 SZ268 ET251 FK500 FO298 FJ679 FI358 FR33 GF594 PF689 " +
            "GA241 GM220 GE995 DE49 GH233 GI350 GR30 GL299 GD1 GP590 GU1 GT502 GG44 GN224 GW245 GY592 HT509 HN504 HK852 " +
            "HU36 IS354 IN91 ID62 IR98 IQ964 IE353 IM44 IL972 IT39 JM1 JP81 JE44 JO962 KZ7 KE254 KI686 XK383 KW965 KG996 " +
            "LA856 LV371 LB961 LS266 LR231 LY218 LI423 LT370 LU352 MO853 MG261 MW265 MY60 MV960 ML223 MT356 MH692 MQ596 " +
            "MR222 MU230 YT262 MX52 FM691 MD373 MC377 MN976 ME382 MS1 MA212 MZ258 MM95 NA264 NR674 NP977 NL31 NC687 NZ64 " +
            "NI505 NE227 NG234 NU683 NF672 KP850 MK389 MP1 NO47 OM968 PK92 PW680 PS970 PA507 PG675 PY595 PE51 PH63 PL48 " +
            "PT351 PR1 QA974 RE262 RO40 RU7 RW250 BL590 SH290 KN1 LC1 MF590 PM508 VC1 WS685 SM378 ST239 SA966 SN221 RS381 " +
            "SC248 SL232 SG65 SX1 SK421 SI386 SB677 SO252 ZA27 KR82 SS211 ES34 LK94 SD249 SR597 SJ47 SE46 CH41 SY963 TW886 " +
            "TJ992 TZ255 TH66 TL670 TG228 TK690 TO676 TT1 TN216 TR90 TM993 TC1 TV688 UG256 UA380 AE971 GB44 US1 UY598 " +
            "VI1 UZ998 VU678 VA39 VE58 VN84 WF681 EH212 YE967 ZM260 ZW263"

    /** Tous les pays, triés par nom dans la langue de l'appareil. */
    fun all(locale: Locale = Locale.FRENCH): List<Country> = DIAL_CODES.split(" ").map { entry ->
        val code = entry.take(2)
        Country(code, Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifEmpty { code }, entry.drop(2))
    }.sortedWith(compareBy(java.text.Collator.getInstance(locale)) { it.name })

    fun byCode(code: String?, locale: Locale = Locale.FRENCH): Country =
        all(locale).firstOrNull { it.code.equals(code, ignoreCase = true) } ?: all(locale).first { it.code == "FR" }
}

// --- Contacts du téléphone ----------------------------------------------------------------------

/** Un contact du carnet d'adresses, lu sur le téléphone uniquement après accord de l'utilisateur. */
data class PhoneContact(val name: String, val phoneNumbers: List<String>)

/** Compte SnapCore retrouvé grâce au numéro d'un contact (ADR-029). */
data class ContactMatch(val phoneNumber: String, val user: Friend, val relation: Relation)

interface ContactsReader {
    suspend fun read(): List<PhoneContact>
}
