package ch.emlidstock.noyau.verification

import ch.emlidstock.noyau.nmea.PositionGga
import kotlin.math.abs

/**
 * Point à vérifier n° 1 : la position d'Emlid Flow (mock location) et le flux NMEA
 * Bluetooth arrivent-ils en même temps ?
 * Vrai si les deux ont donné signe de vie depuis moins de [delaiMs] millisecondes.
 */
fun fluxEnParallele(
    dernierePositionMs: Long?,
    derniereNmeaMs: Long?,
    maintenantMs: Long,
    delaiMs: Long = 5_000,
): Boolean {
    if (dernierePositionMs == null || derniereNmeaMs == null) return false
    return maintenantMs - dernierePositionMs <= delaiMs && maintenantMs - derniereNmeaMs <= delaiMs
}

/** Écarts (en mètres) entre l'altitude de la mock location et celles du GGA. */
data class EcartsAltitude(
    /** Altitude mock − altitude GGA (au-dessus du géoïde du récepteur). */
    val avecAltitudeGga: Double?,
    /** Altitude mock − hauteur ellipsoïdale (GGA altitude + séparation). */
    val avecHauteurEllipsoidale: Double?,
)

/** Point à vérifier n° 4 : compare l'altitude reçue par la mock location à celles du GGA (m). */
fun ecartsAltitude(altitudeMockM: Double?, gga: PositionGga?): EcartsAltitude {
    if (altitudeMockM == null || gga == null) return EcartsAltitude(null, null)
    return EcartsAltitude(
        avecAltitudeGga = gga.altitudeGeoideRecepteurM?.let { altitudeMockM - it },
        avecHauteurEllipsoidale = gga.hauteurEllipsoidaleM?.let { altitudeMockM - it },
    )
}

/**
 * Donne une première lecture des écarts d'altitude, à confirmer par l'utilisateur.
 * [toleranceM] : écart en dessous duquel on considère les deux altitudes comme égales (m).
 */
fun interpreterAltitudeMock(ecarts: EcartsAltitude, toleranceM: Double = 0.05): String {
    val avecEllipsoide = ecarts.avecHauteurEllipsoidale
    val avecGga = ecarts.avecAltitudeGga
    return when {
        avecEllipsoide == null && avecGga == null -> "en attente (il faut la mock location ET un GGA)"
        avecEllipsoide != null && abs(avecEllipsoide) <= toleranceM ->
            "semble ELLIPSOÏDALE (égale à la hauteur ellipsoïdale du GGA)"
        avecGga != null && abs(avecGga) <= toleranceM ->
            "semble liée au GÉOÏDE du récepteur (égale à l'altitude du GGA)"
        else -> "ni l'une ni l'autre : à analyser avec le journal"
    }
}
