package ch.emlidstock.noyau

import ch.emlidstock.noyau.nmea.PositionGga
import ch.emlidstock.noyau.verification.EcartsAltitude
import ch.emlidstock.noyau.verification.ecartsAltitude
import ch.emlidstock.noyau.verification.fluxEnParallele
import ch.emlidstock.noyau.verification.interpreterAltitudeMock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerificationsTest {

    private fun gga(altitude: Double?, separation: Double?) = PositionGga(
        heureUtc = "", latitudeDeg = null, longitudeDeg = null, qualite = 4, nbSatellites = null,
        hdop = null, altitudeGeoideRecepteurM = altitude, separationGeoideM = separation,
        ageCorrectionsS = null, stationReference = "",
    )

    @Test
    fun `les deux flux sont actifs en meme temps`() {
        assertTrue(fluxEnParallele(dernierePositionMs = 9_000, derniereNmeaMs = 9_500, maintenantMs = 10_000))
    }

    @Test
    fun `un flux trop ancien ou absent fait echouer le test`() {
        assertFalse(fluxEnParallele(dernierePositionMs = 1_000, derniereNmeaMs = 9_500, maintenantMs = 10_000))
        assertFalse(fluxEnParallele(dernierePositionMs = null, derniereNmeaMs = 9_500, maintenantMs = 10_000))
    }

    @Test
    fun `ecarts entre l'altitude mock et le GGA`() {
        val ecarts = ecartsAltitude(475.0, gga(427.0, 48.0))
        assertEquals(48.0, ecarts.avecAltitudeGga!!, 1e-9)
        assertEquals(0.0, ecarts.avecHauteurEllipsoidale!!, 1e-9)
    }

    @Test
    fun `sans GGA il n'y a pas d'ecart`() {
        val ecarts = ecartsAltitude(475.0, null)
        assertNull(ecarts.avecAltitudeGga)
        assertNull(ecarts.avecHauteurEllipsoidale)
    }

    @Test
    fun `interpretation des ecarts d'altitude`() {
        assertTrue(interpreterAltitudeMock(EcartsAltitude(48.0, 0.01)).contains("ELLIPSOÏDALE"))
        assertTrue(interpreterAltitudeMock(EcartsAltitude(0.02, -48.0)).contains("GÉOÏDE"))
        assertTrue(interpreterAltitudeMock(EcartsAltitude(3.0, -45.0)).contains("à analyser"))
        assertTrue(interpreterAltitudeMock(EcartsAltitude(null, null)).contains("en attente"))
    }
}
