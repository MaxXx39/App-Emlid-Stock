package ch.emlidstock.noyau

import ch.emlidstock.noyau.coordonnees.GrilleGeoide
import ch.emlidstock.noyau.coordonnees.versPositionSuisse
import ch.emlidstock.noyau.nmea.lireGga
import ch.emlidstock.noyau.nmea.lirePhraseNmea
import ch.emlidstock.noyau.nmea.sommeDeControle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Cas de référence : deux points calculés par Emlid Flow (exemples/test_bureau_pix4d.csv),
 * système « CH1903+ / LV95 + LHN95 height », corrections swipos.
 * Emlid arrondit au millimètre : on accepte 3 mm en plan et 1 mm en altitude.
 */
class CoordonneesTest {

    private val grille = GrilleGeoide.chgeo2004Lhn95()

    @Test
    fun `point 1 du 17 septembre 2026`() {
        val position = versPositionSuisse(46.41530610, 6.26153669, 464.985, grille)!!
        assertEquals(2509500.921, position.est, 0.003)
        assertEquals(1141119.690, position.nord, 0.003)
        assertEquals(414.856, position.altitude, 0.001)
    }

    @Test
    fun `base swipos du meme leve`() {
        val position = versPositionSuisse(46.42611403, 6.27007312, 402.281, grille)!!
        assertEquals(2510175.098, position.est, 0.003)
        assertEquals(1142311.197, position.nord, 0.003)
        assertEquals(352.129, position.altitude, 0.001)
    }

    @Test
    fun `hors de Suisse la grille ne repond pas`() {
        assertNull(grille.hauteurGeoide(48.85, 2.35)) // Paris
        assertNull(versPositionSuisse(48.85, 2.35, 100.0, grille))
    }

    @Test
    fun `valeur de la grille a Gland`() {
        // Valeur lue dans la grille officielle à cet endroit : 50.129 m.
        assertEquals(50.129, grille.hauteurGeoide(46.41530610, 6.26153669)!!, 0.001)
    }

    @Test
    fun `conversion depuis un message GGA`() {
        // Point 1 écrit comme le RX2 l'enverrait : latitude 46°24.918366', longitude 6°15.6922014',
        // altitude 417.751 m + séparation 47.234 m = hauteur ellipsoïdale 464.985 m.
        val texte = "GNGGA,134400.60,4624.9183660,N,00615.6922014,E,4,25,0.6,417.751,M,47.234,M,0.8,1935"
        val gga = lireGga(lirePhraseNmea("\$$texte*${sommeDeControle(texte)}")!!)!!
        val position = assertNotNull(versPositionSuisse(gga, grille))
        assertEquals(2509500.921, position.est, 0.003)
        assertEquals(1141119.690, position.nord, 0.003)
        assertEquals(414.856, position.altitude, 0.001)
    }
}
