package ch.emlidstock.noyau

import ch.emlidstock.noyau.coordonnees.PositionSuisse
import ch.emlidstock.noyau.leve.ModeLeve
import ch.emlidstock.noyau.leve.Stock
import ch.emlidstock.noyau.leve.autoriserPriseDePoint
import ch.emlidstock.noyau.leve.nomStockParDefaut
import ch.emlidstock.noyau.leve.preparerPoint
import ch.emlidstock.noyau.nmea.EtatCompensation
import ch.emlidstock.noyau.nmea.PositionGga
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeveTest {

    private fun gga(qualite: Int) = PositionGga(
        heureUtc = "120000.00", latitudeDeg = 46.38, longitudeDeg = 6.19, qualite = qualite, nbSatellites = 27,
        hdop = 0.6, altitudeGeoideRecepteurM = 462.0, separationGeoideM = 47.2, ageCorrectionsS = 1.0,
        stationReference = "3866",
    )

    private val pret = EtatCompensation(etat = 30, inclinaisonDeg = 5.0)
    private val position = PositionSuisse(2503979.890, 1137570.674, 459.605)

    /** Lève un point dans ce mode et renvoie son nom. */
    private fun lever(stock: Stock, mode: ModeLeve): String {
        val point = preparerPoint(stock, mode, gga(4), position, pret, horodatageMs = 0)
        stock.ajouterPoint(point)
        return point.nom
    }

    private fun nouveauStock() = Stock(id = "s1", chantierId = "c1", nom = "Test", creeLeMs = 0)

    @Test
    fun `numerotation des bas de talus et points caracteristiques`() {
        val stock = nouveauStock()
        assertEquals("B1", lever(stock, ModeLeve.BAS_DE_TALUS))
        assertEquals("B2", lever(stock, ModeLeve.BAS_DE_TALUS))
        assertEquals("C1", lever(stock, ModeLeve.POINT_CARACTERISTIQUE))
        assertEquals("B3", lever(stock, ModeLeve.BAS_DE_TALUS))
        assertEquals("C2", lever(stock, ModeLeve.POINT_CARACTERISTIQUE))
    }

    @Test
    fun `lignes de haut de talus`() {
        val stock = nouveauStock()
        assertEquals("H1-1", lever(stock, ModeLeve.HAUT_DE_TALUS))
        assertEquals("H1-2", lever(stock, ModeLeve.HAUT_DE_TALUS))
        assertEquals(2, stock.nouvelleLigneHaut())
        assertEquals("H2-1", lever(stock, ModeLeve.HAUT_DE_TALUS))
        // Une nouvelle ligne demandée deux fois de suite ne crée pas de ligne vide.
        assertEquals(3, stock.nouvelleLigneHaut())
        assertEquals(3, stock.nouvelleLigneHaut())
        assertEquals("H3-1", lever(stock, ModeLeve.HAUT_DE_TALUS))
    }

    @Test
    fun `reprise d'un stock sur une autre session`() {
        val premier = nouveauStock()
        lever(premier, ModeLeve.BAS_DE_TALUS)
        lever(premier, ModeLeve.HAUT_DE_TALUS)
        premier.nouvelleLigneHaut()
        lever(premier, ModeLeve.HAUT_DE_TALUS)
        // On recharge le stock avec ses points (comme à la réouverture de l'appli).
        val repris = Stock("s1", "c1", "Test", 0, points = premier.points)
        assertEquals("B2", repris.prochainNom(ModeLeve.BAS_DE_TALUS))
        assertEquals("H2-2", repris.prochainNom(ModeLeve.HAUT_DE_TALUS))
    }

    @Test
    fun `annuler le dernier point et supprimer un point`() {
        val stock = nouveauStock()
        lever(stock, ModeLeve.BAS_DE_TALUS)
        lever(stock, ModeLeve.BAS_DE_TALUS)
        lever(stock, ModeLeve.BAS_DE_TALUS)
        assertEquals("B3", stock.annulerDernierPoint()?.nom)
        assertEquals("B3", stock.prochainNom(ModeLeve.BAS_DE_TALUS))
        assertTrue(stock.supprimerPoint("B1"))
        assertFalse(stock.supprimerPoint("B1"))
        assertEquals(listOf("B2"), stock.points.map { it.nom })
        // Le numéro suivant reste « plus grand + 1 » : pas de doublon.
        assertEquals("B3", stock.prochainNom(ModeLeve.BAS_DE_TALUS))
        stock.annulerDernierPoint()
        assertNull(stock.annulerDernierPoint())
    }

    @Test
    fun `le point garde la position et la qualite`() {
        val point = preparerPoint(nouveauStock(), ModeLeve.BAS_DE_TALUS, gga(4), position, pret, horodatageMs = 42)
        assertEquals(2503979.890, point.est, 1e-9)
        assertEquals(459.605, point.altitude, 1e-9)
        assertEquals(4, point.qualiteGga)
        assertEquals(5.0, point.inclinaisonDeg)
        assertEquals(42, point.horodatageMs)
    }

    @Test
    fun `nom de stock par defaut`() {
        assertEquals("2026-10-21 Gravière 1", nomStockParDefaut("2026-10-21", "Gravière", emptyList()))
        assertEquals(
            "2026-10-21 Gravière 3",
            nomStockParDefaut("2026-10-21", "Gravière", listOf("2026-10-21 Gravière 1", "2026-10-21 Gravière 2")),
        )
    }

    @Test
    fun `prise de point autorisee seulement en RTK fixe avec inclinaison prete`() {
        assertTrue(autoriserPriseDePoint(gga(4), 200, pret, 200).autorise)
        assertEquals("RTK pas fixe", autoriserPriseDePoint(gga(5), 200, pret, 200).raison)
        assertFalse(autoriserPriseDePoint(gga(4), 200, EtatCompensation(20, null), 200).autorise)
        assertFalse(autoriserPriseDePoint(gga(4), 200, null, null).autorise)
        assertFalse(autoriserPriseDePoint(gga(4), 5_000, pret, 200).autorise)
        assertFalse(autoriserPriseDePoint(null, null, pret, 200).autorise)
        assertFalse(autoriserPriseDePoint(gga(4), 200, pret, 5_000).autorise)
    }
}
