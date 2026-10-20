package ch.emlidstock.noyau

import ch.emlidstock.noyau.nmea.champsNumerotes
import ch.emlidstock.noyau.nmea.degresDepuisNmea
import ch.emlidstock.noyau.nmea.libelleQualite
import ch.emlidstock.noyau.nmea.lireGga
import ch.emlidstock.noyau.nmea.lirePhraseNmea
import ch.emlidstock.noyau.nmea.sommeDeControle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NmeaTest {

    // Exemple classique de la norme NMEA 0183 (somme de contrôle 47).
    private val ggaExemple = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"

    @Test
    fun `la somme de controle de l'exemple est juste`() {
        val phrase = lirePhraseNmea(ggaExemple)!!
        assertEquals(true, phrase.sommeOk)
        assertEquals("GPGGA", phrase.adresse)
        assertEquals("GGA", phrase.type)
    }

    @Test
    fun `une somme de controle fausse est signalee`() {
        val phrase = lirePhraseNmea(ggaExemple.replace("*47", "*48"))!!
        assertEquals(false, phrase.sommeOk)
    }

    @Test
    fun `une phrase sans somme de controle est acceptee`() {
        val phrase = lirePhraseNmea("\$GNETC,A,1.800")!!
        assertNull(phrase.sommeOk)
        assertEquals("ETC", phrase.type)
        assertEquals(listOf("A", "1.800"), phrase.champs)
    }

    @Test
    fun `une ligne qui n'est pas du NMEA est ignoree`() {
        assertNull(lirePhraseNmea(""))
        assertNull(lirePhraseNmea("bonjour"))
    }

    @Test
    fun `calcul de la somme de controle`() {
        assertEquals("47", sommeDeControle("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"))
    }

    @Test
    fun `lecture du GGA de l'exemple`() {
        val gga = lireGga(lirePhraseNmea(ggaExemple)!!)!!
        assertEquals("123519", gga.heureUtc)
        assertEquals(48.1173, gga.latitudeDeg!!, 1e-9)
        assertEquals(11 + 31.0 / 60.0, gga.longitudeDeg!!, 1e-9)
        assertEquals(1, gga.qualite)
        assertEquals(8, gga.nbSatellites)
        assertEquals(0.9, gga.hdop!!, 1e-9)
        assertEquals(545.4, gga.altitudeGeoideRecepteurM!!, 1e-9)
        assertEquals(46.9, gga.separationGeoideM!!, 1e-9)
        // Hauteur ellipsoïdale = 545.4 + 46.9
        assertEquals(592.3, gga.hauteurEllipsoidaleM!!, 1e-9)
        assertNull(gga.ageCorrectionsS)
        assertFalse(gga.estRtkFixe)
    }

    @Test
    fun `GGA en RTK fixe`() {
        val texte = "GNGGA,071530.00,4652.12345678,N,00655.12345678,E,4,24,0.6,427.123,M,48.456,M,1.0,0000"
        val gga = lireGga(lirePhraseNmea("\$$texte*${sommeDeControle(texte)}")!!)!!
        assertTrue(gga.estRtkFixe)
        assertEquals("RTK FIXE", libelleQualite(gga.qualite))
        assertEquals(1.0, gga.ageCorrectionsS!!, 1e-9)
        assertEquals("0000", gga.stationReference)
    }

    @Test
    fun `un message autre que GGA n'est pas lu comme GGA`() {
        assertNull(lireGga(lirePhraseNmea("\$GNETC,A,1.800")!!))
    }

    @Test
    fun `sud et ouest donnent des degres negatifs`() {
        assertEquals(-48.1173, degresDepuisNmea("4807.038", "S")!!, 1e-9)
        assertEquals(-(11 + 31.0 / 60.0), degresDepuisNmea("01131.000", "W")!!, 1e-9)
        assertNull(degresDepuisNmea("", "N"))
    }

    @Test
    fun `champs numerotes pour l'affichage`() {
        assertEquals("1: A | 2: (vide) | 3: 1.800", champsNumerotes(lirePhraseNmea("\$GNETC,A,,1.800")!!))
    }
}
