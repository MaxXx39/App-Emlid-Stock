package ch.emlidstock.noyau.coordonnees

import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * Passage des coordonnées GNSS (ETRS89 / CHTRS95, celles des corrections swipos)
 * vers les coordonnées suisses MN95 (CH1903+ / LV95), selon la méthode officielle swisstopo :
 *   1. latitude, longitude, hauteur (ellipsoïde GRS80) → coordonnées 3D (X, Y, Z) ;
 *   2. trois translations fixes CHTRS95 → CH1903+ ;
 *   3. (X, Y, Z) → latitude, longitude sur l'ellipsoïde de Bessel ;
 *   4. projection suisse (cylindrique conforme à axe oblique, centre à Berne).
 * Pas de grille FINELTRA : elle ne sert qu'entre MN03 et MN95.
 *
 * Validé le 20.10.2026 sur deux points calculés par Emlid Flow (écart ≤ 2 mm),
 * voir exemples/test_bureau_pix4d.csv et le test Mn95Test.
 */

/** Coordonnées MN95 en mètres : Est (E = 2 xxx xxx) et Nord (N = 1 xxx xxx). */
data class PointMn95(val est: Double, val nord: Double)

/** Un ellipsoïde : demi-grand axe a (m) et carré de l'excentricité e². */
private class Ellipsoide(val demiGrandAxe: Double, val excentricite2: Double)

// GRS80 (ETRS89 / CHTRS95) : a = 6378137 m, aplatissement 1/298.257222101
private val GRS80 = Ellipsoide(6378137.0, (1 / 298.257222101) * (2 - 1 / 298.257222101))

// Bessel 1841 (CH1903+) : a = 6377397.155 m, e² = 0.006674372230614
private val BESSEL = Ellipsoide(6377397.155, 0.006674372230614)

// Translations CH1903+ → CHTRS95 (m). Pour aller de CHTRS95 vers CH1903+, on les soustrait.
private const val TRANSLATION_X = 674.374
private const val TRANSLATION_Y = 15.056
private const val TRANSLATION_Z = 405.346

// Centre de la projection (ancien observatoire de Berne) et décalage des coordonnées MN95.
private val LATITUDE_BERNE = Math.toRadians(46.0 + 57.0 / 60 + 8.66 / 3600)
private val LONGITUDE_BERNE = Math.toRadians(7.0 + 26.0 / 60 + 22.50 / 3600)
private const val EST_BERNE = 2_600_000.0
private const val NORD_BERNE = 1_200_000.0

/**
 * Convertit une position GNSS (degrés décimaux, hauteur ellipsoïdale en m)
 * en coordonnées MN95 Est, Nord (m).
 */
fun etrs89VersMn95(latitudeDeg: Double, longitudeDeg: Double, hauteurEllipsoidaleM: Double): PointMn95 {
    val (x, y, z) = geographiquesVersCartesiennes(
        Math.toRadians(latitudeDeg), Math.toRadians(longitudeDeg), hauteurEllipsoidaleM, GRS80,
    )
    val (latitudeBessel, longitudeBessel) = cartesiennesVersGeographiques(
        x - TRANSLATION_X, y - TRANSLATION_Y, z - TRANSLATION_Z, BESSEL,
    )
    return projectionSuisse(latitudeBessel, longitudeBessel)
}

/** Latitude, longitude (radians) et hauteur (m) → coordonnées 3D X, Y, Z (m). */
private fun geographiquesVersCartesiennes(
    latitude: Double, longitude: Double, hauteur: Double, ellipsoide: Ellipsoide,
): Triple<Double, Double, Double> {
    val e2 = ellipsoide.excentricite2
    val grandeNormale = ellipsoide.demiGrandAxe / sqrt(1 - e2 * sin(latitude) * sin(latitude))
    return Triple(
        (grandeNormale + hauteur) * cos(latitude) * cos(longitude),
        (grandeNormale + hauteur) * cos(latitude) * sin(longitude),
        (grandeNormale * (1 - e2) + hauteur) * sin(latitude),
    )
}

/** Coordonnées 3D X, Y, Z (m) → latitude, longitude (radians), par itérations. */
private fun cartesiennesVersGeographiques(
    x: Double, y: Double, z: Double, ellipsoide: Ellipsoide,
): Pair<Double, Double> {
    val e2 = ellipsoide.excentricite2
    val longitude = atan2(y, x)
    val distanceAxe = hypot(x, y)
    var latitude = atan2(z, distanceAxe * (1 - e2))
    repeat(10) {
        val grandeNormale = ellipsoide.demiGrandAxe / sqrt(1 - e2 * sin(latitude) * sin(latitude))
        val hauteur = distanceAxe / cos(latitude) - grandeNormale
        latitude = atan2(z, distanceAxe * (1 - e2 * grandeNormale / (grandeNormale + hauteur)))
    }
    return Pair(latitude, longitude)
}

/** Projection suisse : latitude, longitude sur Bessel (radians) → Est, Nord MN95 (m). */
private fun projectionSuisse(latitude: Double, longitude: Double): PointMn95 {
    val e2 = BESSEL.excentricite2
    val e = sqrt(e2)
    val sinBerne = sin(LATITUDE_BERNE)

    // Constantes de la projection (sphère de rayon R, coefficient alpha, latitude b0, constante K).
    val rayon = BESSEL.demiGrandAxe * sqrt(1 - e2) / (1 - e2 * sinBerne * sinBerne)
    val alpha = sqrt(1 + e2 / (1 - e2) * Math.pow(cos(LATITUDE_BERNE), 4.0))
    val b0 = asin(sinBerne / alpha)
    val k = ln(tan(Math.PI / 4 + b0 / 2)) - alpha * ln(tan(Math.PI / 4 + LATITUDE_BERNE / 2)) +
        alpha * e / 2 * ln((1 + e * sinBerne) / (1 - e * sinBerne))

    // 1. Ellipsoïde → sphère.
    val sinLatitude = sin(latitude)
    val s = alpha * ln(tan(Math.PI / 4 + latitude / 2)) -
        alpha * e / 2 * ln((1 + e * sinLatitude) / (1 - e * sinLatitude)) + k
    val b = 2 * (atan(exp(s)) - Math.PI / 4)
    val l = alpha * (longitude - LONGITUDE_BERNE)

    // 2. Rotation vers le système « pseudo-équatorial » centré sur Berne.
    val lBarre = atan(sin(l) / (sin(b0) * tan(b) + cos(b0) * cos(l)))
    val bBarre = asin(cos(b0) * sin(b) - sin(b0) * cos(b) * cos(l))

    // 3. Projection de Mercator sur la sphère.
    val est = EST_BERNE + rayon * lBarre
    val nord = NORD_BERNE + rayon / 2 * ln((1 + sin(bBarre)) / (1 - sin(bBarre)))
    return PointMn95(est, nord)
}
