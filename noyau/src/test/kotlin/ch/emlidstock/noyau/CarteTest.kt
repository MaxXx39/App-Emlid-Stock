package ch.emlidstock.noyau

import ch.emlidstock.noyau.carte.Cadrage
import ch.emlidstock.noyau.carte.Fond
import ch.emlidstock.noyau.carte.RESOLUTIONS
import ch.emlidstock.noyau.carte.barreEchelle
import ch.emlidstock.noyau.carte.niveauDeZoom
import ch.emlidstock.noyau.carte.tuilesPourZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests repris de l'appli topo (V1, tests/fond.test.js), plus le cadrage de la carte. */
class CarteTest {

    @Test
    fun `niveau de zoom au moins aussi fin que l'ecran`() {
        assertEquals(0.25, RESOLUTIONS[niveauDeZoom(0.3)])
        assertEquals(10.0, RESOLUTIONS[niveauDeZoom(10.0)])
        assertEquals(0.25, RESOLUTIONS[niveauDeZoom(0.6, 2.0)]) // écran haute définition
        assertEquals(28, niveauDeZoom(0.01)) // niveau le plus détaillé (0.1 m)
    }

    @Test
    fun `tuile qui contient un point (niveau 20, tuiles de 2560 m)`() {
        // Est 2 600 000 : (2 600 000 − 2 420 000) / 2560 = 70.3 → colonne 70
        // Nord 1 200 000 : (1 350 000 − 1 200 000) / 2560 = 58.6 → ligne 58
        val tuile = tuilesPourZone(2600000.0, 2600000.0, 1200000.0, 1200000.0, 20).single()
        assertEquals(70, tuile.colonne)
        assertEquals(58, tuile.ligne)
        assertEquals(2560.0, tuile.cote)
        assertEquals(2420000.0 + 70 * 2560, tuile.est)
        assertEquals(1350000.0 - 58 * 2560, tuile.nord)
        assertEquals(
            "https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.swissimage/default/current/2056/20/70/58.jpeg",
            Fond.PHOTO.adresse(tuile),
        )
    }

    @Test
    fun `les tuiles couvrent toute la zone`() {
        val tuiles = tuilesPourZone(2499320.0, 2499400.0, 1124560.0, 1124660.0, 27) // tuiles de 64 m
        tuiles.forEach { assertEquals(64.0, it.cote) }
        assertTrue(tuiles.minOf { it.est } <= 2499320.0 && tuiles.maxOf { it.est + it.cote } >= 2499400.0)
        assertTrue(tuiles.minOf { it.nord - it.cote } <= 1124560.0 && tuiles.maxOf { it.nord } >= 1124660.0)
    }

    @Test
    fun `aller-retour terrain ecran`() {
        val cadrage = Cadrage(2503979.89, 1137570.674, 10.0)
        val (x, y) = cadrage.versEcran(2503989.89, 1137580.674, 1000, 800)
        assertEquals(600.0, x, 1e-6) // 10 m à l'Est → 100 px à droite
        assertEquals(300.0, y, 1e-6) // 10 m au Nord → 100 px plus haut
        val (est, nord) = cadrage.versTerrain(x, y, 1000, 800)
        assertEquals(2503989.89, est, 1e-6)
        assertEquals(1137580.674, nord, 1e-6)
    }

    @Test
    fun `le zoom garde le point sous le doigt`() {
        val avant = Cadrage(2500000.0, 1100000.0, 10.0)
        val sousLeDoigt = avant.versTerrain(200.0, 150.0, 1000, 800)
        val apres = avant.zoomer(2.0, 200.0, 150.0, 1000, 800)
        assertEquals(20.0, apres.echelle)
        val toujours = apres.versTerrain(200.0, 150.0, 1000, 800)
        assertEquals(sousLeDoigt.first, toujours.first, 1e-6)
        assertEquals(sousLeDoigt.second, toujours.second, 1e-6)
    }

    @Test
    fun `cadrage sur les points`() {
        val points = listOf(Pair(2500000.0, 1100000.0), Pair(2500100.0, 1100050.0))
        val cadrage = Cadrage.surPoints(points, 1000, 800, margePixels = 50)!!
        assertEquals(2500050.0, cadrage.centreEst, 1e-6)
        assertEquals(1100025.0, cadrage.centreNord, 1e-6)
        assertEquals(9.0, cadrage.echelle, 1e-9) // 900 px pour 100 m
        assertEquals(25.0, Cadrage.surPoints(listOf(Pair(2500000.0, 1100000.0)), 1000, 800)!!.echelle, 1e-9)
    }

    @Test
    fun `barre d'echelle ronde`() {
        assertEquals(Pair(10.0, 100.0), barreEchelle(10.0)) // 12 m possibles → 10 m
        assertEquals(Pair(50.0, 100.0), barreEchelle(2.0)) // 60 m possibles → 50 m
        assertEquals(Pair(2.0, 80.0), barreEchelle(40.0)) // 3 m possibles → 2 m
    }
}
