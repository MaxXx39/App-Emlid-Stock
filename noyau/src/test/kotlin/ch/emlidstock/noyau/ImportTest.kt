package ch.emlidstock.noyau

import ch.emlidstock.noyau.importation.creerStockImporte
import ch.emlidstock.noyau.importation.detecterSeries
import ch.emlidstock.noyau.importation.ecrireListeDeNoms
import ch.emlidstock.noyau.importation.lireListeDeNoms
import ch.emlidstock.noyau.importation.lirePoints
import ch.emlidstock.noyau.leve.ModeLeve
import ch.emlidstock.noyau.leve.TypeLeve
import ch.emlidstock.noyau.volume.calculerVolumeStock
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests repris de l'appli topo V1 (tests/import-points.test.js et tests/noms.test.js). */
class LecturePointsTest {

    private val csv = lirePoints(File("../exemples/stock_2026-05-20.csv").readText())
    private val txt = lirePoints(File("../exemples/stock_export_2026-05-20.txt").readText())

    @Test
    fun `reconnait le format et lit les 115 points sans erreur`() {
        assertEquals("CSV Emlid", csv.format)
        assertEquals("TXT", txt.format)
        assertEquals(115, csv.points.size)
        assertEquals(115, txt.points.size)
        assertTrue(csv.erreurs.isEmpty() && txt.erreurs.isEmpty() && csv.avertissements.isEmpty())
    }

    @Test
    fun `lit le point b1 dans l'ordre Est, Nord, altitude, et le CSV donne les memes points que le TXT`() {
        val b1 = csv.points[0]
        assertEquals("b1", b1.nom)
        assertEquals(2499349.955, b1.est, 1e-9)
        assertEquals(1124567.41, b1.nord, 1e-9)
        assertEquals(427.654, b1.altitude, 1e-9)
        assertEquals(csv.points.map { Triple(it.nom, it.est, it.nord) }, txt.points.map { Triple(it.nom, it.est, it.nord) })
    }

    @Test
    fun `lignes illisibles signalees avec leur numero`() {
        val r = lirePoints("p1\t100.000\t200.000\t10.000\np2\t100.000\tabc\t10.000\np3\t101.000\t201.000\t11.000")
        assertEquals(listOf("p1", "p3"), r.points.map { it.nom })
        assertEquals(2, r.erreurs.single().ligne)
        assertEquals("point « p2 » : Nord illisible(s)", r.erreurs.single().message)
        assertEquals(1, lirePoints("p1\t100.000\t200.000").erreurs.single().ligne)
        assertTrue(lirePoints("p1\t100,5\t200.000\t10.000").erreurs[0].message.contains("Est")) // virgule refusée
        assertEquals(1, lirePoints("\np1\t1\t2\t3\n\n").points.size)
        assertTrue(lirePoints("Name,Easting,Northing\np1,1,2").erreurs[0].message.contains("Elevation"))
    }

    @Test
    fun `CSV avec description entre guillemets et noms en double`() {
        val r = lirePoints("Name,Description,Easting,Northing,Elevation\np1,\"tas, côté nord\",100.5,200.25,10.125")
        assertEquals(100.5, r.points[0].est)
        val doubles = lirePoints("p1\t1\t2\t3\np1\t4\t5\t6")
        assertEquals(2, doubles.points.size)
        assertTrue(doubles.avertissements[0].contains("lignes 1, 2"))
    }
}

class NomsTest {

    private val noms = (1..17).map { "b$it" } + (100..116).map { "b$it" } + listOf("h7", "h8", "h9")

    @Test
    fun `plages et morceaux`() {
        val r = lireListeDeNoms("b100 à b116", noms)
        assertEquals(17, r.noms.size)
        assertEquals("b100", r.noms.first())
        assertEquals("b116", r.noms.last())
        assertEquals(listOf("b3", "b2", "b1"), lireListeDeNoms("b3 à b1", noms).noms)
        assertEquals(listOf("b1", "b2", "b3", "h7", "b10"), lireListeDeNoms("b1 a b3, h7; b10", noms).noms)
        assertEquals(listOf("p008", "p009", "p010"), lireListeDeNoms("p008 à p010", listOf("p008", "p009", "p010")).noms)
    }

    @Test
    fun `points introuvables et plage mal ecrite`() {
        val r = lireListeDeNoms("b15 à b19, x1", noms)
        assertEquals(listOf("b15", "b16", "b17"), r.noms)
        assertEquals(2, r.erreurs.size)
        assertTrue(r.erreurs[0].contains("b18, b19"))
        assertTrue(r.erreurs[1].contains("x1"))
        assertTrue(lireListeDeNoms("b1 à h5", noms).erreurs[0].contains("plage"))
    }

    @Test
    fun `ecriture courte et series`() {
        assertEquals("b1 à b3, h7, h9, h8", ecrireListeDeNoms(listOf("b1", "b2", "b3", "h7", "h9", "h8")))
        assertEquals("b5 à b3", ecrireListeDeNoms(listOf("b5", "b4", "b3")))
        assertEquals("x, y", ecrireListeDeNoms(listOf("x", "y")))
        val liste = listOf("b1", "b2", "b3", "h7", "h9", "h8")
        assertEquals(liste, lireListeDeNoms(ecrireListeDeNoms(liste), noms).noms)
        assertEquals(listOf(listOf("b1", "b2", "b3"), listOf("h1", "h2")), detecterSeries(listOf("b1", "b2", "b3", "x", "h1", "h2")))
    }
}

class StockImporteTest {

    private val points = lirePoints(File("../exemples/stock_export_2026-05-20.txt").readText()).points
    private val noms = points.map { it.nom }

    @Test
    fun `le levé du 20-05 importé en 3 stocks redonne les volumes Mensura`() {
        val series = detecterSeries(noms).map { ecrireListeDeNoms(it) }
        assertTrue(series.containsAll(listOf("b1 à b17", "h1 à h31", "b100 à b116", "h100 à h114", "b200 à b217", "h200 à h216")))
        val attendus = listOf(Triple("b1 à b17", "h1 à h31", 1636.197), Triple("b100 à b116", "h100 à h114", 648.979), Triple("b200 à b217", "h200 à h216", 372.201))
        for ((contour, ligne, mensura) in attendus) {
            val r = creerStockImporte(
                "s", "c", "Stock", 0, points, lireListeDeNoms(contour, noms).noms,
                listOf(lireListeDeNoms(ligne, noms).noms to true), "stock_export.txt",
            )
            assertNull(r.erreur)
            val stock = r.stock!!
            assertEquals(contour.substringBefore(" "), stock.points.first { it.mode == ModeLeve.BAS_DE_TALUS }.nom) // noms d'origine
            assertTrue(stock.points.none { it.nom.startsWith("b") && it.mode != ModeLeve.BAS_DE_TALUS })
            val volume = calculerVolumeStock(stock.points, stock.lignesFermees)
            assertEquals(mensura, volume.remblai, 0.001)
        }
    }

    @Test
    fun `une surface importee sans contour garde tous les points du fichier`() {
        val r = creerStockImporte("t", "c", "Terrain", 0, points, emptyList(), emptyList(), "f.txt", TypeLeve.SURFACE)
        assertNull(r.erreur)
        assertEquals(TypeLeve.SURFACE, r.stock!!.type)
        assertEquals(points.size, r.stock!!.points.size)
        // Un stock, lui, demande toujours un contour.
        assertNotNull(creerStockImporte("s", "c", "Stock", 0, points, emptyList(), emptyList(), "f.txt").erreur)
    }

    @Test
    fun `seuls les points dans le contour sont gardes, les noms d'origine aussi`() {
        val r = creerStockImporte("s", "c", "Stock 2", 0, points, lireListeDeNoms("b100 à b116", noms).noms, emptyList(), "f.txt")
        val stock = r.stock!!
        // Les h100… sont dans le contour : ils deviennent des points caractéristiques ; les autres stocks sont exclus.
        assertTrue(stock.points.any { it.nom == "h105" && it.mode == ModeLeve.POINT_CARACTERISTIQUE })
        assertTrue(stock.points.none { it.nom == "b1" || it.nom == "h1" })
        // Un point ajouté ensuite sur le terrain prend le prochain nom libre.
        assertEquals("B18", stock.prochainNom(ModeLeve.BAS_DE_TALUS))
    }

    @Test
    fun `erreurs comprehensibles`() {
        assertNotNull(creerStockImporte("s", "c", "S", 0, points, listOf("b1", "b2"), emptyList(), "f").erreur)
        assertTrue(creerStockImporte("s", "c", "S", 0, points, listOf("b1", "b2", "b3", "b1"), emptyList(), "f").erreur!!.contains("deux fois"))
        assertTrue(creerStockImporte("s", "c", "S", 0, points, listOf("b1", "b2", "zz"), emptyList(), "f").erreur!!.contains("zz"))
    }
}
