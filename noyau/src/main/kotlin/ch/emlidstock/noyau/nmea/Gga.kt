package ch.emlidstock.noyau.nmea

/**
 * Contenu d'un message GGA (position et qualité du fix), tel que le récepteur l'envoie.
 *
 * Unités : latitude et longitude en degrés décimaux (WGS84 / système des corrections),
 * altitudes en mètres, âge des corrections en secondes.
 */
data class PositionGga(
    val heureUtc: String,
    val latitudeDeg: Double?,
    val longitudeDeg: Double?,
    val qualite: Int?,
    val nbSatellites: Int?,
    val hdop: Double?,
    /** Altitude au-dessus du géoïde utilisé par le récepteur (m). */
    val altitudeGeoideRecepteurM: Double?,
    /** Écart entre le géoïde du récepteur et l'ellipsoïde (m). */
    val separationGeoideM: Double?,
    val ageCorrectionsS: Double?,
    val stationReference: String,
) {
    /**
     * Hauteur au-dessus de l'ellipsoïde (m) = altitude GGA + séparation du géoïde.
     * C'est la définition du message GGA ; null s'il manque une des deux valeurs.
     */
    val hauteurEllipsoidaleM: Double?
        get() = if (altitudeGeoideRecepteurM != null && separationGeoideM != null) {
            altitudeGeoideRecepteurM + separationGeoideM
        } else {
            null
        }

    /** Vrai seulement si le récepteur annonce un RTK « fixe » (code 4). */
    val estRtkFixe: Boolean
        get() = qualite == 4
}

/** Lit un message GGA. Renvoie null si la phrase n'est pas un GGA. */
fun lireGga(phrase: PhraseNmea): PositionGga? {
    if (phrase.type != "GGA") return null
    return PositionGga(
        heureUtc = phrase.champ(1),
        latitudeDeg = degresDepuisNmea(phrase.champ(2), phrase.champ(3)),
        longitudeDeg = degresDepuisNmea(phrase.champ(4), phrase.champ(5)),
        qualite = phrase.champ(6).toIntOrNull(),
        nbSatellites = phrase.champ(7).toIntOrNull(),
        hdop = phrase.champ(8).toDoubleOrNull(),
        altitudeGeoideRecepteurM = phrase.champ(9).toDoubleOrNull(),
        separationGeoideM = phrase.champ(11).toDoubleOrNull(),
        ageCorrectionsS = phrase.champ(13).toDoubleOrNull(),
        stationReference = phrase.champ(14),
    )
}

/**
 * Convertit une latitude ou longitude NMEA (« ddmm.mmmm » ou « dddmm.mmmm »)
 * en degrés décimaux. Le sud et l'ouest donnent une valeur négative.
 */
fun degresDepuisNmea(valeur: String, hemisphere: String): Double? {
    val nombre = valeur.toDoubleOrNull() ?: return null
    val degres = (nombre / 100).toInt()
    val minutes = nombre - degres * 100
    val resultat = degres + minutes / 60.0
    return if (hemisphere == "S" || hemisphere == "W") -resultat else resultat
}

/** Texte en français pour le code de qualité du GGA. */
fun libelleQualite(code: Int?): String = when (code) {
    null -> "inconnu"
    0 -> "pas de position"
    1 -> "autonome (sans corrections)"
    2 -> "DGNSS"
    4 -> "RTK FIXE"
    5 -> "RTK flottant"
    6 -> "estimée"
    else -> "code $code"
}
