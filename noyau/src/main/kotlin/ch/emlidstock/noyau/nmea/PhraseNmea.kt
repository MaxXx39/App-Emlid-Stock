package ch.emlidstock.noyau.nmea

/**
 * Une phrase NMEA déjà découpée.
 *
 * Exemple : « $GNGGA,071530.00,4652.1234,N,...*5C »
 * - adresse : ce qui suit le « $ » jusqu'à la première virgule (« GNGGA »).
 * - type : les 3 dernières lettres de l'adresse (« GGA », « ETC »...).
 * - champs : les valeurs entre les virgules, sans l'adresse ni la somme de contrôle.
 * - sommeOk : true si la somme de contrôle est juste, false si elle est fausse,
 *   null si la phrase n'en contient pas.
 */
data class PhraseNmea(
    val texte: String,
    val adresse: String,
    val champs: List<String>,
    val sommeOk: Boolean?,
) {
    val type: String
        get() = if (adresse.length >= 3) adresse.takeLast(3) else adresse

    /** Renvoie le champ n° [numero] (en comptant à partir de 1), ou "" s'il n'existe pas. */
    fun champ(numero: Int): String = champs.getOrNull(numero - 1) ?: ""
}

/**
 * Lit une ligne de texte reçue du récepteur et la découpe en [PhraseNmea].
 * Renvoie null si la ligne n'est pas une phrase NMEA (elle doit commencer par « $ »).
 */
fun lirePhraseNmea(ligne: String): PhraseNmea? {
    val texte = ligne.trim()
    if (!texte.startsWith("$") || texte.length < 2) return null

    // Séparer le contenu et la somme de contrôle (après l'étoile).
    val positionEtoile = texte.lastIndexOf('*')
    val contenu = if (positionEtoile > 0) texte.substring(1, positionEtoile) else texte.substring(1)
    val sommeLue = if (positionEtoile > 0) texte.substring(positionEtoile + 1) else null

    val sommeOk = sommeLue?.let { it.equals(sommeDeControle(contenu), ignoreCase = true) }

    val morceaux = contenu.split(',')
    return PhraseNmea(
        texte = texte,
        adresse = morceaux.first(),
        champs = morceaux.drop(1),
        sommeOk = sommeOk,
    )
}

/**
 * Somme de contrôle NMEA : « ou exclusif » de tous les caractères entre « $ » et « * »,
 * écrite en 2 chiffres hexadécimaux (ex. « 5C »).
 */
fun sommeDeControle(contenu: String): String {
    var somme = 0
    for (caractere in contenu) somme = somme xor caractere.code
    return "%02X".format(somme)
}

/**
 * Présente les champs d'une phrase avec leur numéro, pour les lire facilement à l'écran :
 * « 1: A | 2: 1.800 | 3: ... ». Sert au message ETC, dont on ne connaît pas encore le détail.
 */
fun champsNumerotes(phrase: PhraseNmea): String =
    phrase.champs.mapIndexed { index, valeur -> "${index + 1}: ${valeur.ifEmpty { "(vide)" }}" }
        .joinToString(" | ")
