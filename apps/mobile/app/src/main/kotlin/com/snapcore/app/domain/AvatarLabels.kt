package com.snapcore.app.domain

// Noms lisibles des options de l'avatar (ADR-028), pour TalkBack (ADR-033) : le catalogue du backend
// n'expose que les identifiants techniques d'avataaars (« shortFlat », « 2c1b18 »).

private val LABELS: Map<String, Map<String, String>> = mapOf(
    "top" to mapOf(
        "none" to "Sans cheveux", "hat" to "Chapeau", "hijab" to "Hijab", "turban" to "Turban",
        "winterHat1" to "Bonnet d'hiver 1", "winterHat02" to "Bonnet d'hiver 2", "winterHat03" to "Bonnet d'hiver 3",
        "winterHat04" to "Bonnet d'hiver 4", "bob" to "Carré", "bun" to "Chignon", "curly" to "Cheveux bouclés",
        "curvy" to "Cheveux longs ondulés", "dreads" to "Dreadlocks", "frida" to "Tresses avec des fleurs", "fro" to "Afro",
        "froBand" to "Afro avec bandeau", "longButNotTooLong" to "Cheveux mi-longs", "miaWallace" to "Carré long à frange",
        "shavedSides" to "Côtés rasés", "straight01" to "Cheveux longs raides 1", "straight02" to "Cheveux longs raides 2",
        "straightAndStrand" to "Cheveux longs avec une mèche", "dreads01" to "Dreadlocks courtes 1",
        "dreads02" to "Dreadlocks courtes 2", "frizzle" to "Cheveux courts frisés", "shaggy" to "Cheveux en bataille",
        "shaggyMullet" to "Coupe mulet", "shortCurly" to "Cheveux courts bouclés", "shortFlat" to "Cheveux courts plats",
        "shortRound" to "Cheveux courts arrondis", "shortWaved" to "Cheveux courts ondulés", "sides" to "Chauve sur le dessus",
        "theCaesar" to "Coupe César", "theCaesarAndSidePart" to "Coupe César avec une raie", "bigHair" to "Cheveux volumineux",
    ),
    "hairColor" to mapOf(
        "a55728" to "auburn", "2c1b18" to "noirs", "b58143" to "châtain clair", "d6b370" to "blonds", "724133" to "châtains",
        "4a312c" to "bruns", "f59797" to "roses", "ecdcbf" to "blond platine", "c93305" to "roux", "e8e1e1" to "gris",
    ),
    "skinColor" to mapOf(
        "614335" to "très foncée", "d08b5b" to "mate", "ae5d29" to "foncée", "edb98a" to "claire", "ffdbb4" to "très claire",
        "fd9841" to "orange", "f8d25c" to "jaune",
    ),
    "eyes" to mapOf(
        "closed" to "fermés", "cry" to "en larmes", "default" to "normaux", "eyeRoll" to "levés au ciel", "happy" to "joyeux",
        "hearts" to "en cœur", "side" to "regard de côté", "squint" to "plissés", "surprised" to "surpris",
        "winkWacky" to "clin d'œil farceur", "wink" to "clin d'œil", "xDizzy" to "étourdis",
    ),
    "eyebrows" to mapOf(
        "angryNatural" to "en colère, naturels", "defaultNatural" to "normaux, naturels", "flatNatural" to "plats, naturels",
        "frownNatural" to "froncés, naturels", "raisedExcitedNatural" to "levés, enthousiastes, naturels",
        "sadConcernedNatural" to "tristes, naturels", "unibrowNatural" to "monosourcil", "upDownNatural" to "un levé, un baissé, naturels",
        "angry" to "en colère", "default" to "normaux", "raisedExcited" to "levés, enthousiastes", "sadConcerned" to "tristes",
        "upDown" to "un levé, un baissé",
    ),
    "mouth" to mapOf(
        "concerned" to "inquiète", "default" to "neutre", "disbelief" to "incrédule", "eating" to "en train de manger",
        "grimace" to "grimace", "sad" to "triste", "screamOpen" to "qui crie", "serious" to "sérieuse", "smile" to "sourire",
        "tongue" to "langue tirée", "twinkle" to "petit sourire", "vomit" to "écœurée",
    ),
    "facialHair" to mapOf(
        "none" to "Sans barbe", "beardLight" to "Barbe légère", "beardMajestic" to "Grande barbe", "beardMedium" to "Barbe moyenne",
        "moustacheFancy" to "Moustache élégante", "moustacheMagnum" to "Grosse moustache",
    ),
    "accessories" to mapOf(
        "none" to "Sans lunettes", "kurt" to "Lunettes rondes teintées", "prescription01" to "Lunettes de vue 1",
        "prescription02" to "Lunettes de vue 2", "round" to "Lunettes rondes", "sunglasses" to "Lunettes de soleil",
        "wayfarers" to "Lunettes Wayfarer", "eyepatch" to "Cache-œil",
    ),
    "clothing" to mapOf(
        "blazerAndShirt" to "Blazer et chemise", "blazerAndSweater" to "Blazer et pull", "collarAndSweater" to "Pull à col",
        "graphicShirt" to "T-shirt imprimé", "hoodie" to "Sweat à capuche", "overall" to "Salopette",
        "shirtCrewNeck" to "T-shirt col rond", "shirtScoopNeck" to "T-shirt col large", "shirtVNeck" to "T-shirt col en V",
    ),
    "clothesColor" to mapOf(
        "262e33" to "noir", "65c9ff" to "bleu ciel", "5199e4" to "bleu", "25557c" to "bleu marine", "e6e6e6" to "gris clair",
        "929598" to "gris", "3c4f5c" to "gris anthracite", "b1e2ff" to "bleu pastel", "a7ffc4" to "vert menthe",
        "ffafb9" to "rose pâle", "ffffb1" to "jaune pâle", "ff488e" to "rose vif", "ff5c5c" to "rouge", "ffffff" to "blanc",
    ),
    "backgroundColor" to mapOf(
        "b6e3f4" to "bleu ciel", "c0aede" to "lavande", "d1d4f9" to "pervenche", "ffd5dc" to "rose pâle", "ffdfbf" to "pêche",
        "fffaa1" to "jaune pâle", "a7ffc4" to "vert menthe", "ffffff" to "blanc",
    ),
)

/**
 * Nom lisible d'une option (« Cheveux courts plats », « châtains »). Une valeur inconnue (nouvelle version
 * d'avataaars) reste lisible : « bigHairBun » devient « big hair bun ».
 */
fun avatarLabel(key: String, value: String): String = LABELS[key]?.get(value.removePrefix("#").let { if (key.endsWith("Color")) it.lowercase() else it })
    ?: value.replace(Regex("([a-z])([A-Z0-9])"), "$1 $2").lowercase()

/** L'avatar décrit en une phrase, lue par TalkBack sur l'aperçu. */
fun avatarDescription(options: Map<String, String>): String {
    fun label(key: String) = options[key]?.let { avatarLabel(key, it) }
    val parts = listOfNotNull(
        label("top")?.replaceFirstChar(Char::lowercase)?.let { top ->
            val color = label("hairColor")
            if (options["top"] == "none" || color == null) top else "$top, $color"
        },
        label("skinColor")?.let { "peau $it" },
        label("eyes")?.let { "yeux $it" },
        label("eyebrows")?.let { "sourcils $it" },
        label("mouth")?.let { "bouche $it" },
        label("facialHair")?.takeIf { options["facialHair"] != "none" }?.replaceFirstChar(Char::lowercase),
        label("accessories")?.takeIf { options["accessories"] != "none" }?.replaceFirstChar(Char::lowercase),
        label("clothing")?.replaceFirstChar(Char::lowercase)?.let { clothing -> label("clothesColor")?.let { "$clothing $it" } ?: clothing },
        label("backgroundColor")?.let { "fond $it" },
    )
    return parts.joinToString(", ").replaceFirstChar(Char::uppercase)
}
