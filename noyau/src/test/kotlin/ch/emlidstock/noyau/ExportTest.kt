package ch.emlidstock.noyau

import ch.emlidstock.noyau.export.StockExporte
import ch.emlidstock.noyau.export.barreEchellePapier
import ch.emlidstock.noyau.export.choisirEchelle
import ch.emlidstock.noyau.export.ecrireLandXml
import ch.emlidstock.noyau.export.ecrireProjetV1
import ch.emlidstock.noyau.export.genererCsv
import ch.emlidstock.noyau.export.genererDxf
import ch.emlidstock.noyau.export.nomDeCalque
import ch.emlidstock.noyau.export.texteJson
import ch.emlidstock.noyau.leve.ModeLeve
import ch.emlidstock.noyau.leve.PointLeve
import ch.emlidstock.noyau.leve.Stock
import ch.emlidstock.noyau.volume.calculerVolumeStock
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Les 3 stocks du levé de référence du 20/05/2026, avec leur volume calculé. */
internal fun stocksDeReference(): List<StockExporte> {
    val lignes = File("../exemples/stock_export_2026-05-20.txt").readLines().filter { it.isNotBlank() }
    return listOf(Triple("Stock 1", 1..17, 1..31), Triple("Stock 2", 100..116, 100..114), Triple("Stock 3", 200..217, 200..216))
        .map { (nom, plageB, plageH) ->
            val points = lignes.mapNotNull { ligne ->
                val (n, e, no, h) = ligne.split('\t')
                val numero = n.drop(1).toInt()
                val mode = when {
                    n[0] == 'b' && numero in plageB -> ModeLeve.BAS_DE_TALUS
                    n[0] == 'h' && numero in plageH -> ModeLeve.HAUT_DE_TALUS
                    else -> return@mapNotNull null
                }
                PointLeve(n, mode, if (mode == ModeLeve.HAUT_DE_TALUS) 1 else null, numero, e.toDouble(), no.toDouble(), h.toDouble(),
                    0.0, 0.0, 0.0, 0, 4, null, null, null, "", null)
            }
            val stock = Stock(nom, "c", nom, 0, points = points, lignesFermees = setOf(1))
            StockExporte(stock, calculerVolumeStock(points, stock.lignesFermees))
        }
}

class ExportTest {

    @Test
    fun `echelle du plan imprime (repris de la V1)`() {
        assertEquals(Pair(20.0, 40.0), barreEchellePapier(500, 50.0)) // 50 mm = 25 m → 20 m
        assertEquals(Pair(50.0, 50.0), barreEchellePapier(1000, 50.0))
        assertEquals(500, choisirEchelle(80.0, 40.0, 173.0, 160.0)) // 80 m → 160 mm à 1:500
        assertEquals(250, choisirEchelle(40.0, 30.0, 173.0, 160.0))
    }

    @Test
    fun `CSV - volumes comme la V1 puis les points`() {
        val csv = genererCsv(stocksDeReference())
        val lignes = csv.split("\r\n")
        assertEquals("Nom;Base;Surface (m²);Remblai (m³);Déblai (m³)", lignes[0])
        assertEquals("Stock 1;A;758.638;1636.197;0.000", lignes[1])
        assertEquals("Stock 2;A;436.969;648.979;0.000", lignes[2])
        assertEquals("Stock 3;A;323.484;372.201;0.000", lignes[3])
        assertEquals("", lignes[4])
        assertEquals("Stock;Point;Rôle;Est;Nord;Altitude", lignes[5])
        assertEquals("Stock 1;b1;Bas de talus;2499349.955;1124567.410;427.654", lignes[6])
    }

    @Test
    fun `DXF R12 - calques, faces 3D et polylignes`() {
        val dxf = genererDxf(stocksDeReference(), equidistance = 0.5)
        assertTrue(dxf.startsWith("0\r\nSECTION\r\n2\r\nHEADER\r\n9\r\n\$ACADVER\r\n1\r\nAC1009"))
        assertTrue(dxf.endsWith("0\r\nEOF\r\n"))
        assertTrue(dxf.contains("TRIANGLES_STOCK_1"))
        assertEquals(77 + 45 + 50, Regex("\r\n3DFACE\r\n").findAll(dxf).count())
        assertEquals(3 + 3, Regex("\r\nPOLYLINE\r\n").findAll(dxf).count()) // 3 contours + 3 lignes de haut
        assertTrue(dxf.contains("\r\nCOURBES\r\n"))
        assertEquals("GRAVIERE_GLAND_1", nomDeCalque("Gravière Gland 1"))
    }

    @Test
    fun `LandXML - Nord avant Est, surfaces TIN`() {
        val s = stocksDeReference().first()
        val xml = ecrireLandXml(listOf(s.nom to s.resultat!!.trianglesDessus), "Essai", LocalDateTime.of(2026, 10, 21, 15, 0, 0))
        assertTrue(xml.contains("""date="2026-10-21" time="15:00:00""""))
        assertTrue(xml.contains("""<Surface name="Stock 1">"""))
        assertTrue(xml.contains("""area2DSurf="758.638""""))
        assertTrue(Regex("""<P id="\d+" name="b1">1124567.410 2499349.955 427.654</P>""").containsMatchIn(xml))
        assertEquals(77, Regex("<F>").findAll(xml).count())
    }

    @Test
    fun `fichier projet pour la V1`() {
        var compteur = 0
        val texte = ecrireProjetV1("Chantier \"essai\"", stocksDeReference(), "10 203", "MGO", Instant.parse("2026-10-21T13:00:00Z")) { "id-${++compteur}" }
        assertTrue(texte.startsWith("""{"format":"topo-projet","version":1,"""))
        assertTrue(texte.contains(""""nom":"Chantier \"essai\"""""))
        assertTrue(texte.contains(""""contour":["b1","b2","b3""""))
        assertTrue(texte.contains(""""lignesRupture":[{"noms":["h1","h2""""))
        assertTrue(texte.contains(""""cartouche":{"affaire":"10 203","dessinePar":"MGO"}"""))
        assertEquals("\"a\\\\b\\n\"", texteJson("a\\b\n"))
        // Fichier gardé pour le contrôle avec le vrai code de la V1 (voir CLAUDE.md).
        File("build/projet-v1-reference.topo.json").writeText(texte)
    }
}
