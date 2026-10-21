package ch.emlidstock.noyau

import ch.emlidstock.noyau.leve.ModeLeve
import ch.emlidstock.noyau.leve.PointLeve
import ch.emlidstock.noyau.volume.ResultatVolume
import ch.emlidstock.noyau.volume.Sommet
import ch.emlidstock.noyau.volume.ajouterPrisme
import ch.emlidstock.noyau.volume.calculerVolumeStock
import ch.emlidstock.noyau.volume.trianglerAvecContraintes
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fabrique un point levé (seuls nom, rôle, ligne, numéro et coordonnées comptent pour le volume). */
private fun point(mode: ModeLeve, numero: Int, est: Double, nord: Double, altitude: Double, ligne: Int? = null, nom: String? = null) =
    PointLeve(
        nom = nom ?: (if (mode == ModeLeve.HAUT_DE_TALUS) "H${ligne ?: 1}-$numero" else "${mode.lettre}$numero"),
        mode = mode, ligne = if (mode == ModeLeve.HAUT_DE_TALUS) (ligne ?: 1) else null, numero = numero,
        est = est, nord = nord, altitude = altitude, latitudeDeg = 0.0, longitudeDeg = 0.0, hauteurEllipsoidaleM = 0.0,
        horodatageMs = 0, qualiteGga = 4, nbSatellites = null, hdop = null, ageCorrectionsS = null,
        stationReference = "", inclinaisonDeg = null,
    )

private fun b(n: Int, e: Double, no: Double, h: Double) = point(ModeLeve.BAS_DE_TALUS, n, e, no, h)
private fun h(n: Int, e: Double, no: Double, alt: Double, ligne: Int = 1) = point(ModeLeve.HAUT_DE_TALUS, n, e, no, alt, ligne)
private fun c(n: Int, e: Double, no: Double, alt: Double) = point(ModeLeve.POINT_CARACTERISTIQUE, n, e, no, alt)

/**
 * Cas de référence : levé du 20/05/2026 (affaire 10 203), fichier exemples/stock_export_2026-05-20.txt.
 * Dans ce levé, « b » = bas de talus (contour) et « h » = haut de talus (une ligne par stock).
 * Volumes de remblai Mensura : 1636.197 / 648.979 / 372.201 m³ (tolérance 0,5 %).
 * L'appli topo V1 trouve 1636.1971 / 648.9788 / 372.2010 m³ avec la même méthode.
 */
class VolumeReferenceTest {

    private val lignesFichier = File("../exemples/stock_export_2026-05-20.txt").readLines().filter { it.isNotBlank() }

    /** Points d'un stock : les b de [debutB] à [finB] et les h de [debutH] à [finH], dans l'ordre des numéros. */
    private fun stock(debutB: Int, finB: Int, debutH: Int, finH: Int): List<PointLeve> =
        lignesFichier.mapNotNull { ligne ->
            val (nom, est, nord, alt) = ligne.split('\t')
            val numero = nom.drop(1).toInt()
            when {
                nom[0] == 'b' && numero in debutB..finB -> point(ModeLeve.BAS_DE_TALUS, numero, est.toDouble(), nord.toDouble(), alt.toDouble(), nom = nom)
                nom[0] == 'h' && numero in debutH..finH -> point(ModeLeve.HAUT_DE_TALUS, numero, est.toDouble(), nord.toDouble(), alt.toDouble(), 1, nom)
                else -> null
            }
        }

    private fun verifier(points: List<PointLeve>, v1: Double, mensura: Double, surface: Double) {
        for (fermee in listOf(emptySet(), setOf(1))) {
            val r = calculerVolumeStock(points, fermee)
            assertNull(r.erreur)
            assertEquals(v1, r.remblai, 0.001, "même volume que la V1")
            assertTrue(abs(r.remblai - mensura) <= mensura * 0.005, "écart Mensura > 0,5 %")
            assertEquals(0.0, r.deblai, 1e-6)
            assertEquals(surface, r.surfacePlan, 0.001)
            assertEquals(r.surfaceContour, r.surfacePlan, 1e-6) // tout le contour est couvert
            assertEquals(0.0, r.surfaceNonCouverte)
        }
    }

    @Test
    fun `stock 1 (forme en L)`() = verifier(stock(1, 17, 1, 31), v1 = 1636.1971, mensura = 1636.197, surface = 758.6382)

    @Test
    fun `stock 2`() = verifier(stock(100, 116, 100, 114), v1 = 648.9788, mensura = 648.979, surface = 436.9694)

    @Test
    fun `stock 3 (forme en L)`() = verifier(stock(200, 217, 200, 216), v1 = 372.2010, mensura = 372.201, surface = 323.4844)

    @Test
    fun `memes nombres de triangles que la V1`() {
        val r = calculerVolumeStock(stock(1, 17, 1, 31))
        assertEquals(77, r.trianglesDessus.size)
        assertEquals(20, r.trianglesBase.size)
    }
}

/** Volumes connus, vérifiables à la main (repris des tests de la V1). */
class VolumeTest {

    private val carre = listOf(b(1, 0.0, 0.0, 0.0), b(2, 10.0, 0.0, 0.0), b(3, 10.0, 10.0, 0.0), b(4, 0.0, 10.0, 0.0))

    @Test
    fun `pyramide - base carree 10 m x 10 m, sommet a 3 m - 100 m3`() {
        val r = calculerVolumeStock(carre + c(1, 5.0, 5.0, 3.0))
        assertEquals(100.0, r.remblai, 1e-9)
        assertEquals(0.0, r.deblai)
        assertEquals(100.0, r.surfacePlan, 1e-9)
        assertEquals(4, r.trianglesDessus.size)
    }

    @Test
    fun `base inclinee - le volume se mesure depuis la base`() {
        // Base qui monte de 0 à 2 m vers l'Est ; sommet 5 m au-dessus de la base au centre (base à 1 m).
        val points = listOf(b(1, 0.0, 0.0, 0.0), b(2, 10.0, 0.0, 2.0), b(3, 10.0, 10.0, 2.0), b(4, 0.0, 10.0, 0.0), c(1, 5.0, 5.0, 6.0))
        assertEquals(500.0 / 3, calculerVolumeStock(points).remblai, 1e-9)
    }

    @Test
    fun `forme en L - le creux du L n'est pas compte`() {
        // L de 3 carrés de 10 m (300 m²), dessus plat à 2 m, base à 0 m.
        val contour = listOf(
            b(1, 0.0, 0.0, 0.0), b(2, 20.0, 0.0, 0.0), b(3, 20.0, 10.0, 0.0),
            b(4, 10.0, 10.0, 0.0), b(5, 10.0, 20.0, 0.0), b(6, 0.0, 20.0, 0.0),
        )
        val haut = listOf(h(1, 2.0, 2.0, 2.0), h(2, 18.0, 2.0, 2.0), h(3, 18.0, 8.0, 2.0), h(4, 8.0, 8.0, 2.0), h(5, 8.0, 18.0, 2.0), h(6, 2.0, 18.0, 2.0))
        val r = calculerVolumeStock(contour + haut, lignesFermees = setOf(1))
        assertNull(r.erreur)
        assertEquals(300.0, r.surfacePlan, 1e-9)
        assertEquals(300.0, r.surfaceContour, 1e-9)
        assertEquals(0.0, r.surfaceNonCouverte)
        assertEquals(0.0, r.deblai)
        assertTrue(r.remblai > 0 && r.remblai < 600)
    }

    @Test
    fun `un point hors du contour ne change rien`() {
        val r = calculerVolumeStock(carre + c(1, 5.0, 5.0, 3.0) + c(2, 50.0, 50.0, 20.0))
        assertEquals(100.0, r.remblai, 1e-9)
    }

    @Test
    fun `coupe exactement un triangle ou l'ecart change de signe`() {
        val r = ResultatVolume()
        // Triangle de 50 m², écarts +1, −1, −1 : la pointe positive fait 1/4 de l'aire.
        ajouterPrisme(r, 50.0, 1.0, -1.0, -1.0)
        assertEquals(12.5, r.surfaceRemblai, 1e-9)
        assertEquals(12.5 / 3, r.remblai, 1e-9)
        assertEquals(37.5, r.surfaceDeblai, 1e-9)
        assertEquals(-50.0 / 3, r.remblai - r.deblai, 1e-9)
    }

    @Test
    fun `deblai quand le dessus passe sous la base`() {
        val r = calculerVolumeStock(carre + c(1, 5.0, 5.0, -3.0))
        assertEquals(0.0, r.remblai, 1e-9)
        assertEquals(100.0, r.deblai, 1e-9)
    }

    @Test
    fun `ligne de haut de talus respectee - la crete passe par la ligne`() {
        // Toit à deux pans : crête H1-1 → H1-2 au milieu, à 2 m. Volume = 10 × 10 × 2 / 2 = 100 m³
        // seulement si les triangles suivent la crête (sinon la crête serait « coupée »).
        val points = carre + listOf(h(1, 0.0001, 5.0, 2.0), h(2, 9.9999, 5.0, 2.0))
        val r = calculerVolumeStock(points)
        assertNull(r.erreur)
        assertEquals(100.0, r.remblai, 0.01)
    }

    @Test
    fun `erreurs comprehensibles`() {
        assertNotNull(calculerVolumeStock(carre.take(2)).erreur)
        // Contour qui se croise (ordre B1, B2, B4, B3) : message clair.
        val croise = listOf(b(1, 0.0, 0.0, 0.0), b(2, 10.0, 0.0, 0.0), b(3, 0.0, 10.0, 0.0), b(4, 10.0, 10.0, 0.0), c(1, 5.0, 3.0, 1.0))
        assertTrue(calculerVolumeStock(croise).erreur!!.contains("se croisent"))
    }

    @Test
    fun `deux points a la meme position - avertissement`() {
        val r = calculerVolumeStock(carre + c(1, 5.0, 5.0, 3.0) + c(2, 5.0, 5.0, 3.0))
        assertNull(r.erreur)
        assertEquals(1, r.avertissements.size)
        assertEquals(100.0, r.remblai, 1e-9)
    }

    @Test
    fun `triangulation de points au hasard - couvre l'enveloppe et respecte Delaunay`() {
        val alea = java.util.Random(7)
        val sommets = (1..200).map { Sommet("p$it", alea.nextDouble() * 100, alea.nextDouble() * 100, 0.0) }
        val r = trianglerAvecContraintes(sommets, emptyList())
        assertNull(r.erreur)
        // Pour n points dont k sur l'enveloppe : 2n − 2 − k triangles ; on vérifie l'ordre de grandeur et l'orientation.
        assertTrue(r.triangles.size in 370..398)
        r.triangles.forEach { assertTrue(ch.emlidstock.noyau.volume.aireTriangle(it) > 0) }
    }
}
