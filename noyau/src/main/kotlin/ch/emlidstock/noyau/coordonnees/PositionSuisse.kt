package ch.emlidstock.noyau.coordonnees

import ch.emlidstock.noyau.nmea.PositionGga

/** Position en coordonnées suisses : Est, Nord MN95 (m) et altitude RAN95 / LHN95 (m). */
data class PositionSuisse(val est: Double, val nord: Double, val altitude: Double)

/**
 * Convertit une position GNSS (degrés, hauteur ellipsoïdale en m) en MN95 + altitude RAN95.
 * Renvoie null si la position est hors de la grille du géoïde (hors de Suisse).
 */
fun versPositionSuisse(
    latitudeDeg: Double,
    longitudeDeg: Double,
    hauteurEllipsoidaleM: Double,
    grille: GrilleGeoide,
): PositionSuisse? {
    val hauteurGeoide = grille.hauteurGeoide(latitudeDeg, longitudeDeg) ?: return null
    val point = etrs89VersMn95(latitudeDeg, longitudeDeg, hauteurEllipsoidaleM)
    return PositionSuisse(point.est, point.nord, hauteurEllipsoidaleM - hauteurGeoide)
}

/**
 * Même conversion à partir d'un message GGA du récepteur.
 * Hauteur ellipsoïdale = altitude GGA + séparation (on n'utilise pas le géoïde du récepteur).
 * Renvoie null s'il manque une valeur dans le GGA ou si la position est hors de Suisse.
 */
fun versPositionSuisse(gga: PositionGga, grille: GrilleGeoide): PositionSuisse? {
    val latitude = gga.latitudeDeg ?: return null
    val longitude = gga.longitudeDeg ?: return null
    val hauteur = gga.hauteurEllipsoidaleM ?: return null
    return versPositionSuisse(latitude, longitude, hauteur, grille)
}
