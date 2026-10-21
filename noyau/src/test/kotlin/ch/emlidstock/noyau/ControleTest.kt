package ch.emlidstock.noyau

import ch.emlidstock.noyau.controle.Plage
import ch.emlidstock.noyau.controle.Projection3D
import ch.emlidstock.noyau.controle.bandesDeNiveau
import ch.emlidstock.noyau.controle.choisirPalier
import ch.emlidstock.noyau.controle.couleurAltitude
import ch.emlidstock.noyau.controle.courbesDeNiveau
import ch.emlidstock.noyau.controle.ecartsExtremes
import ch.emlidstock.noyau.controle.equidistanceAuto
import ch.emlidstock.noyau.controle.plageAltitudes
import ch.emlidstock.noyau.controle.zonesRemblaiDeblai
import ch.emlidstock.noyau.leve.ModeLeve
import ch.emlidstock.noyau.leve.PointLeve
import ch.emlidstock.noyau.volume.PointPlan
import ch.emlidstock.noyau.volume.Sommet
import ch.emlidstock.noyau.volume.Triangle
import ch.emlidstock.noyau.volume.airePolygone
import ch.emlidstock.noyau.volume.calculerVolumeStock
import ch.emlidstock.noyau.volume.volumeEntreSurfaces
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun s(est: Double, nord: Double, altitude: Double) = Sommet("", est, nord, altitude)
private fun aire(polygone: List<PointPlan>) = abs(airePolygone(polygone))

// Pyramide : base carrée 10 m × 10 m à 0 m, sommet au centre à 3 m (4 triangles).
private val sommet = s(5.0, 5.0, 3.0)
private val pyramide = listOf(
    Triangle(s(0.0, 0.0, 0.0), s(10.0, 0.0, 0.0), sommet),
    Triangle(s(10.0, 0.0, 0.0), s(10.0, 10.0, 0.0), sommet),
    Triangle(s(10.0, 10.0, 0.0), s(0.0, 10.0, 0.0), sommet),
    Triangle(s(0.0, 10.0, 0.0), s(0.0, 0.0, 0.0), sommet),
)

/** Tests repris de l'appli topo V1 (tests/courbes.test.js et tests/ecarts.test.js). */
class CourbesTest {

    @Test
    fun `courbe a mi-hauteur d'une pyramide - un carre de 5 m de cote (20 m de long)`() {
        val segments = courbesDeNiveau(pyramide, 1.5).filter { it.altitude == 1.5 }
        assertEquals(4, segments.size)
        assertEquals(20.0, segments.sumOf { hypot(it.b.est - it.a.est, it.b.nord - it.a.nord) }, 1e-9)
    }

    @Test
    fun `ne dessine pas deux fois un cote pose pile sur une courbe`() {
        val triangles = listOf(
            Triangle(s(0.0, 0.0, 0.0), s(10.0, 0.0, 0.0), s(5.0, 5.0, 1.0)),
            Triangle(s(10.0, 0.0, 0.0), s(0.0, 0.0, 0.0), s(5.0, -5.0, -1.0)),
        )
        val aZero = courbesDeNiveau(triangles, 1.0).filter { it.altitude == 0.0 }
        assertEquals(1, aZero.size)
        assertEquals(10.0, hypot(aZero[0].b.est - aZero[0].a.est, aZero[0].b.nord - aZero[0].a.nord), 1e-9)
    }

    @Test
    fun `altitudes rondes`() {
        assertTrue(courbesDeNiveau(pyramide, 0.1).map { it.altitude }.contains(0.3))
    }

    @Test
    fun `les bandes recouvrent exactement la pyramide (100 m2) et la bande du bas fait 75 m2`() {
        assertEquals(100.0, bandesDeNiveau(pyramide, 1.0).sumOf { aire(it.polygone) }, 1e-9)
        assertEquals(75.0, bandesDeNiveau(pyramide, 1.5).filter { it.altitude == 0.0 }.sumOf { aire(it.polygone) }, 1e-9)
    }

    @Test
    fun `equidistance ronde et altitudes extremes`() {
        assertEquals(0.25, equidistanceAuto(426.8, 429.6)) // 2.8 m → environ 11 courbes
        assertEquals(10.0, equidistanceAuto(0.0, 100.0))
        assertEquals(Plage(0.0, 3.0), plageAltitudes(pyramide))
    }

    @Test
    fun `couleurs d'altitude - bleu en bas, rouge en haut`() {
        val bas = couleurAltitude(0.0, Plage(0.0, 3.0))
        val haut = couleurAltitude(3.0, Plage(0.0, 3.0))
        assertEquals(44, bas.rouge)
        assertTrue(bas.bleu > bas.rouge)
        assertEquals(215, haut.rouge)
        assertTrue(haut.rouge > haut.bleu)
    }
}

class RemblaiDeblaiTest {

    // Plan incliné de −1 m à +2 m sur 30 m : déblai jusqu'à 1 m, remblai jusqu'à 2 m.
    private val incline = listOf(
        Triangle(s(0.0, 0.0, -1.0), s(30.0, 0.0, 2.0), s(30.0, 10.0, 2.0)),
        Triangle(s(0.0, 0.0, -1.0), s(30.0, 10.0, 2.0), s(0.0, 10.0, -1.0)),
    )

    @Test
    fun `triangles d'ecart - toute la surface, avec l'ecart dessus moins base`() {
        val dessus = listOf(Triangle(s(0.0, 0.0, 5.0), s(10.0, 0.0, 5.0), s(0.0, 10.0, 5.0)))
        val base = listOf(Triangle(s(0.0, 0.0, 7.0), s(10.0, 0.0, 7.0), s(0.0, 10.0, 7.0)))
        val ecart = volumeEntreSurfaces(dessus, base).trianglesEcart
        assertEquals(0.0, ecartsExtremes(ecart).remblai)
        assertEquals(2.0, ecartsExtremes(ecart).deblai, 1e-9)
        assertEquals(50.0, ecart.sumOf { abs(ch.emlidstock.noyau.volume.aireTriangle(it)) }, 1e-9)
    }

    @Test
    fun `palier automatique - au plus 6 classes de chaque cote`() {
        assertEquals(0.5, choisirPalier(incline))
        assertEquals(0.25, choisirPalier(incline, 0.25))
    }

    @Test
    fun `les zones couvrent toute la surface et la legende va du clair au fonce`() {
        val r = zonesRemblaiDeblai(incline, 0.5)
        assertEquals(300.0, r.zones.sumOf { aire(it.polygone) }, 1e-6)
        assertEquals(listOf(0.0 to 0.5, 0.5 to 1.0, 1.0 to 1.5, 1.5 to 2.0), r.remblai.map { it.de to it.a })
        assertEquals(listOf(0.0 to 0.5, 0.5 to 1.0), r.deblai.map { it.de to it.a })
        val premier = r.remblai.first().couleur
        val dernier = r.remblai.last().couleur
        assertTrue(premier.rouge > premier.bleu) // rouge
        assertTrue(dernier.rouge + dernier.vert + dernier.bleu < premier.rouge + premier.vert + premier.bleu) // plus foncé
        assertTrue(r.deblai.first().couleur.bleu < r.deblai.first().couleur.vert) // jaune
        assertEquals(
            (r.remblai + r.deblai).map { it.couleur }.toSet(),
            r.zones.map { it.couleur }.toSet(),
        )
    }

    @Test
    fun `cas de reference Stock 1 - remblai seulement, zones sur toute la surface`() {
        val points = File("../exemples/stock_export_2026-05-20.txt").readLines().filter { it.isNotBlank() }.mapNotNull { ligne ->
            val (nom, est, nord, alt) = ligne.split('\t')
            val numero = nom.drop(1).toInt()
            val mode = when {
                nom[0] == 'b' && numero in 1..17 -> ModeLeve.BAS_DE_TALUS
                nom[0] == 'h' && numero in 1..31 -> ModeLeve.HAUT_DE_TALUS
                else -> return@mapNotNull null
            }
            PointLeve(nom, mode, if (mode == ModeLeve.HAUT_DE_TALUS) 1 else null, numero, est.toDouble(), nord.toDouble(), alt.toDouble(),
                0.0, 0.0, 0.0, 0, 4, null, null, null, "", null)
        }
        val volume = calculerVolumeStock(points)
        val r = zonesRemblaiDeblai(volume.trianglesEcart)
        assertTrue(r.deblai.isEmpty())
        // Aire calculée autour du premier sommet de chaque zone (précision en MN95).
        val total = r.zones.sumOf { z ->
            val o = z.polygone[0]
            aire(z.polygone.map { PointPlan(it.est - o.est, it.nord - o.nord) })
        }
        assertEquals(volume.surfacePlan, total, 1e-3)
    }
}

class Vue3DTest {

    @Test
    fun `vue de dessus - le Nord est en haut, l'Est a droite`() {
        val p = Projection3D(0.0, 0.0, 0.0, azimutDeg = 0.0, elevationDeg = 90.0)
        val (x, y, _) = p.projeter(10.0, 20.0, 5.0)
        assertEquals(10.0, x, 1e-9)
        assertEquals(20.0, y, 1e-9)
    }

    @Test
    fun `vue de cote - la hauteur est en haut, exageree si demande`() {
        val p = Projection3D(0.0, 0.0, 100.0, azimutDeg = 0.0, elevationDeg = 0.0, exageration = 2.0)
        val (x, y, profondeur) = p.projeter(3.0, 7.0, 105.0)
        assertEquals(3.0, x, 1e-9)
        assertEquals(10.0, y, 1e-9) // 5 m × 2
        assertEquals(7.0, profondeur, 1e-9) // le Nord est au fond
    }

    @Test
    fun `les triangles du fond sont dessines en premier et l'eclairage reste entre 0_35 et 1`() {
        val p = Projection3D(0.0, 0.0, 0.0, azimutDeg = 0.0, elevationDeg = 30.0)
        val pres = Triangle(s(0.0, -10.0, 0.0), s(1.0, -10.0, 0.0), s(0.0, -9.0, 0.0))
        val loin = Triangle(s(0.0, 10.0, 0.0), s(1.0, 10.0, 0.0), s(0.0, 11.0, 0.0))
        assertEquals(listOf(loin, pres), p.trierPourDessin(listOf(pres, loin)))
        pyramide.forEach { assertTrue(p.eclairage(it) in 0.35..1.0) }
    }
}
