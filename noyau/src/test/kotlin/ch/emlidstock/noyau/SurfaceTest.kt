package ch.emlidstock.noyau

import ch.emlidstock.noyau.export.genererDxf
import ch.emlidstock.noyau.leve.ModeLeve
import ch.emlidstock.noyau.leve.PointLeve
import ch.emlidstock.noyau.leve.Stock
import ch.emlidstock.noyau.leve.TypeLeve
import ch.emlidstock.noyau.surface.SurfaceImportee
import ch.emlidstock.noyau.surface.SurfacesDuChantier
import ch.emlidstock.noyau.surface.calculerSurfaceLevee
import ch.emlidstock.noyau.surface.cleTriangle
import ch.emlidstock.noyau.surface.lireDxf
import ch.emlidstock.noyau.surface.lireLandXml
import ch.emlidstock.noyau.surface.resumerSurface
import ch.emlidstock.noyau.volume.BaseDeVolume
import ch.emlidstock.noyau.volume.Triangle
import ch.emlidstock.noyau.volume.aireTriangle
import ch.emlidstock.noyau.volume.calculerVolumeStock
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun aire(triangles: List<Triangle>) = triangles.sumOf { abs(aireTriangle(it)) }

private fun pt(mode: ModeLeve, nom: String, numero: Int, est: Double, nord: Double, altitude: Double, ligne: Int? = null) =
    PointLeve(nom, mode, ligne, numero, est, nord, altitude, 0.0, 0.0, 0.0, 0, 4, null, null, null, "", null)

private fun c(nom: String, numero: Int, est: Double, nord: Double, altitude: Double) =
    pt(ModeLeve.POINT_CARACTERISTIQUE, nom, numero, est, nord, altitude)

// Carré de 10 m avec un point au centre : 4 triangles, 100 m² (comme les tests de la V1).
private val CARRE = listOf(c("a", 1, 0.0, 0.0, 0.0), c("b", 2, 10.0, 0.0, 0.0), c("c", 3, 10.0, 10.0, 0.0), c("d", 4, 0.0, 10.0, 0.0), c("m", 5, 5.0, 5.0, 2.0))

/** Tests de la lecture des LandXML (repris de la V1, tests/landxml.test.js). */
class LectureLandXmlTest {

    private val tapis = lireLandXml(File("../exemples/3D SUR Tapis AC11.xml").readText())

    @Test
    fun `fichier d'exemple Tapis AC11 - 508 points, 506 triangles, sans erreur`() {
        assertEquals(emptyList(), tapis.erreurs)
        assertEquals(emptyList(), tapis.avertissements)
        val surface = tapis.surfaces.single()
        assertEquals("TAPIS AC11", surface.nom)
        assertEquals(508, surface.sommets.size)
        assertEquals(506, surface.faces.size)
        assertEquals(0, surface.trianglesInvisibles)
        assertEquals(4, surface.nombreLignesRupture)
    }

    @Test
    fun `remet les coordonnees dans l'ordre Est, Nord, altitude et relie les triangles par leur id`() {
        val surface = tapis.surfaces.single()
        // Dans le fichier : <P id="1" name="44">1130617.246 2499609.547 476.33</P> (Nord Est altitude)
        val point = surface.sommets.first { it.nom == "44" }
        assertEquals(2499609.547, point.est)
        assertEquals(1130617.246, point.nord)
        assertEquals(476.33, point.altitude)
        // Premier triangle du fichier : <F>8 262 7</F> → positions des points id 8, 262 et 7.
        val ids = Regex("""<P id="(\d+)"""").findAll(File("../exemples/3D SUR Tapis AC11.xml").readText()).map { it.groupValues[1] }.toList()
        assertEquals(listOf("8", "262", "7"), surface.faces.first().map { ids[it] })
    }

    @Test
    fun `retrouve la surface et les altitudes annoncees par Mensura`() {
        val surface = tapis.surfaces.single()
        val resume = resumerSurface(surface.triangles())
        assertEquals(surface.surfaceAnnoncee!!, resume.surfacePlan, 0.01) // 16807.696 m²
        assertEquals(473.79, resume.altitudeMin, 1e-6)
        assertEquals(483.88, resume.altitudeMax, 1e-6)
    }

    private fun fichier(surfaces: String) = "<?xml version=\"1.0\"?>\n<LandXML version=\"1.1\"><Surfaces>$surfaces</Surfaces></LandXML>"
    private fun tin(nom: String, faces: String) = """
        <Surface name="$nom"><Definition surfType="TIN">
          <Pnts>
            <P id="10">200 100 5</P><P id="20">200 110 5</P><P id="30">210 100 6</P><P id="40">210 110 7</P>
          </Pnts>
          <Faces>$faces</Faces>
        </Definition></Surface>"""

    @Test
    fun `propose toutes les surfaces TIN et ignore les autres types`() {
        val lecture = lireLandXml(
            fichier(tin("Terrain", "<F>10 20 30</F>") + tin("Projet", "<F>20 40 30</F>") + "<Surface name=\"Grille\"><Definition surfType=\"grid\"></Definition></Surface>"),
        )
        assertEquals(listOf("Terrain", "Projet"), lecture.surfaces.map { it.nom })
        assertTrue(lecture.avertissements[0].contains("Grille"))
    }

    @Test
    fun `ignore les triangles invisibles`() {
        val surface = lireLandXml(fichier(tin("T", "<F>10 20 30</F><F i=\"1\">20 40 30</F>"))).surfaces.single()
        assertEquals(1, surface.faces.size)
        assertEquals(1, surface.trianglesInvisibles)
    }

    @Test
    fun `signale un triangle qui utilise un point absent, avec le numero de ligne`() {
        val lecture = lireLandXml(fichier(tin("T", "<F>10 20 30</F>\n<F>10 20 99</F>")))
        assertEquals(1, lecture.erreurs.size)
        assertTrue(Regex("""ligne \d+ : triangle qui utilise le point id 99""").containsMatchIn(lecture.erreurs[0]), lecture.erreurs[0])
        assertEquals(1, lecture.surfaces.single().faces.size)
    }

    @Test
    fun `ignore les commentaires, decode les noms, refuse un fichier qui n'est pas du LandXML`() {
        val lecture = lireLandXml(fichier("<!-- <Surface name=\"faux\"></Surface> -->" + tin("Tas &amp; talus", "<F>10 20 30</F>")))
        assertEquals(listOf("Tas & talus"), lecture.surfaces.map { it.nom })
        assertTrue(lireLandXml("b1\t1\t2\t3").erreurs[0].contains("pas un fichier LandXML"))
    }
}

/** Tests de la lecture des DXF (repris de la V1, tests/dxf-lecture.test.js). */
class LectureDxfTest {

    /** Petit DXF écrit à la main : une section ENTITIES avec les entités données. */
    private fun dxf(vararg entites: List<String>) =
        (listOf("0", "SECTION", "2", "ENTITIES") + entites.flatMap { it } + listOf("0", "ENDSEC", "0", "EOF")).joinToString("\n")

    @Test
    fun `relit les faces 3D ecrites par l'export DXF de l'appli (une surface par calque)`() {
        val lecture = lireDxf(genererDxf(stocksDeReference()))
        assertEquals(emptyList(), lecture.erreurs)
        assertEquals(listOf("TRIANGLES_STOCK_1", "TRIANGLES_STOCK_2", "TRIANGLES_STOCK_3"), lecture.surfaces.map { it.nom })
        assertEquals(listOf(77, 45, 50), lecture.surfaces.map { it.faces.size })
        // Même surface en plan que le calcul du stock 1 (758.638 m²), sommets partagés.
        assertEquals(758.638, aire(lecture.surfaces[0].triangles()), 0.001)
        assertTrue(lecture.surfaces[0].sommets.size < 77 * 3)
    }

    @Test
    fun `coupe une face a 4 sommets en 2 triangles et ignore une face verticale`() {
        val texte = dxf(
            listOf("0", "3DFACE", "8", "TN", "10", "0", "20", "0", "30", "1", "11", "10", "21", "0", "31", "1", "12", "10", "22", "10", "32", "2", "13", "0", "23", "10", "33", "2"),
            listOf("0", "3DFACE", "8", "TN", "10", "0", "20", "0", "30", "0", "11", "5", "21", "0", "31", "3", "12", "10", "22", "0", "32", "0", "13", "10", "23", "0", "33", "0"),
        )
        val lecture = lireDxf(texte)
        assertEquals(2, lecture.surfaces.single().faces.size)
        assertTrue(lecture.avertissements.joinToString().contains("1 face(s) verticale(s)"))
    }

    @Test
    fun `lit un maillage polyface`() {
        fun vertex(x: String, y: String, z: String) = listOf("0", "VERTEX", "8", "MNT", "10", x, "20", y, "30", z, "70", "192")
        fun face(vararg numeros: Int) = listOf("0", "VERTEX", "8", "MNT", "10", "0", "20", "0", "30", "0", "70", "128") +
            numeros.withIndex().flatMap { (i, n) -> listOf((71 + i).toString(), n.toString()) }
        val texte = dxf(
            listOf("0", "POLYLINE", "8", "MNT", "66", "1", "70", "64", "71", "4", "72", "2"),
            vertex("0", "0", "5"), vertex("10", "0", "5"), vertex("10", "10", "6"), vertex("0", "10", "6"),
            face(1, 2, 3), face(1, 3, -4),
            listOf("0", "SEQEND", "8", "MNT"),
        )
        val lecture = lireDxf(texte)
        assertEquals(emptyList(), lecture.erreurs)
        val surface = lecture.surfaces.single()
        assertEquals("MNT", surface.nom)
        assertEquals(2, surface.faces.size)
        assertEquals(4, surface.sommets.size)
    }

    @Test
    fun `explique quand il n'y a aucune face 3D, ou un DXF binaire`() {
        assertTrue(lireDxf(dxf(listOf("0", "LINE", "8", "0", "10", "0", "20", "0", "11", "1", "21", "1"))).erreurs[0].contains("aucune face 3D"))
        assertTrue(lireDxf("AutoCAD Binary DXF\r\n...").erreurs[0].contains("binaire"))
    }
}

/** Surfaces levées (repris de la V1, tests/surface.test.js). */
class SurfaceLeveeTest {

    @Test
    fun `triangule tous les points (bord automatique)`() {
        val r = calculerSurfaceLevee(CARRE, emptySet(), emptySet())
        assertNull(r.erreur)
        assertEquals(4, r.triangles.size)
        assertEquals(100.0, aire(r.triangles), 1e-9)
    }

    @Test
    fun `retire les triangles supprimes a la main, et peut les remettre`() {
        val premier = calculerSurfaceLevee(CARRE, emptySet(), emptySet()).triangles.first()
        val r = calculerSurfaceLevee(CARRE, emptySet(), setOf(cleTriangle(premier)))
        assertEquals(3, r.triangles.size)
        assertEquals(1, r.trianglesRetires.size)
        assertEquals(75.0, aire(r.triangles), 1e-9)
        assertEquals(4, calculerSurfaceLevee(CARRE, emptySet(), emptySet()).triangles.size)
    }

    @Test
    fun `la cle d'un triangle ne depend pas de l'ordre des sommets`() {
        val t = calculerSurfaceLevee(CARRE, emptySet(), emptySet()).triangles.first()
        assertEquals(cleTriangle(t), cleTriangle(Triangle(t.c, t.a, t.b)))
    }

    @Test
    fun `respecte un contour (stock 1 en L - creux non triangule)`() {
        val stock1 = stocksDeReference()[0].stock
        val r = calculerSurfaceLevee(stock1.points, stock1.lignesFermees, emptySet())
        assertNull(r.erreur)
        assertEquals(77, r.triangles.size)
        assertEquals(758.638, aire(r.triangles), 0.001) // même surface que le calcul de volume du stock 1
    }

    @Test
    fun `ignore un point en double avec un avertissement`() {
        val r = calculerSurfaceLevee(CARRE + c("m2", 6, 5.0, 5.0, 2.1), emptySet(), emptySet())
        assertEquals(4, r.triangles.size)
        assertTrue(r.avertissements[0].contains("m2"))
    }

    @Test
    fun `signale les lignes de rupture qui se croisent`() {
        val h = listOf(
            pt(ModeLeve.HAUT_DE_TALUS, "a", 1, 0.0, 0.0, 0.0, 1), pt(ModeLeve.HAUT_DE_TALUS, "c", 2, 10.0, 10.0, 0.0, 1),
            pt(ModeLeve.HAUT_DE_TALUS, "b", 1, 10.0, 0.0, 0.0, 2), pt(ModeLeve.HAUT_DE_TALUS, "d", 2, 0.0, 10.0, 0.0, 2),
        )
        assertTrue(calculerSurfaceLevee(h, emptySet(), emptySet()).erreur!!.contains("se croisent"))
    }

    @Test
    fun `moins de 3 points - message clair`() {
        assertTrue(calculerSurfaceLevee(CARRE.take(2), emptySet(), emptySet()).erreur!!.contains("au moins 3 points"))
    }
}

/**
 * Base du volume en mode B (repris de la V1, tests/volume.test.js) : surface LandXML
 * « Terrain1 » (fichier 20260520_Stock.xml, plan horizontal à 431.000 m).
 * Valeurs de la V1 (calculées avec son code) : remblai / déblai / surface en plan.
 */
class VolumeModeBTest {

    private val plan431 = lireLandXml(File("../exemples/20260520_Stock.xml").readText()).surfaces.single()
    private val base = BaseDeVolume(plan431.nom, plan431.triangles())

    @Test
    fun `les 3 stocks de reference sur le plan a 431 m donnent les valeurs de la V1`() {
        val attendus = listOf(
            Triple(62.5696, 1101.7002, 758.6382),
            Triple(0.0, 1006.2474, 436.9694),
            Triple(0.0, 941.5549, 323.4844),
        )
        for ((s, attendu) in stocksDeReference().zip(attendus)) {
            val r = calculerVolumeStock(s.points, s.stock.lignesFermees, base)
            assertNull(r.erreur)
            assertEquals("B", r.mode)
            assertEquals("Terrain1", r.nomBase)
            assertEquals(2, r.trianglesBase.size)
            assertEquals(0.0, r.surfaceNonCouverte)
            assertEquals(attendu.first, r.remblai, 0.001, s.nom)
            assertEquals(attendu.second, r.deblai, 0.001, s.nom)
            assertEquals(attendu.third, r.surfacePlan, 0.001, s.nom)
            // Contrôle indépendant : remblai − déblai = volume sous le dessus − 431 m × surface.
            val sousLeDessus = r.trianglesDessus.sumOf { t -> abs(aireTriangle(t)) * (t.a.altitude + t.b.altitude + t.c.altitude) / 3 }
            assertEquals(sousLeDessus - 431 * r.surfacePlan, r.remblai - r.deblai, 1e-6)
        }
    }

    @Test
    fun `signale la surface non couverte par la base`() {
        val carre = listOf(
            pt(ModeLeve.BAS_DE_TALUS, "B1", 1, 0.0, 0.0, 0.0), pt(ModeLeve.BAS_DE_TALUS, "B2", 2, 10.0, 0.0, 0.0),
            pt(ModeLeve.BAS_DE_TALUS, "B3", 3, 10.0, 10.0, 0.0), pt(ModeLeve.BAS_DE_TALUS, "B4", 4, 0.0, 10.0, 0.0), c("C1", 1, 5.0, 5.0, 3.0),
        )
        // Base qui ne couvre que la moitié Ouest du carré (50 m² non couverts).
        val demi = calculerSurfaceLevee(
            listOf(c("p1", 1, -1.0, -1.0, 0.0), c("p2", 2, 5.0, -1.0, 0.0), c("p3", 3, 5.0, 11.0, 0.0), c("p4", 4, -1.0, 11.0, 0.0)),
            emptySet(), emptySet(),
        )
        val r = calculerVolumeStock(carre, emptySet(), BaseDeVolume("Demi", demi.triangles))
        assertEquals(50.0, r.surfaceNonCouverte, 1e-9)
    }

    @Test
    fun `base = surface levee sur les points B du stock - meme volume qu'en mode A`() {
        val s = stocksDeReference()[0]
        val levee = Stock("tn", "c", "Terrain naturel", 0, type = TypeLeve.SURFACE, points = s.points.filter { it.mode == ModeLeve.BAS_DE_TALUS })
        val stock = Stock("s1", "c", "Stock 1", 0, points = s.points, lignesFermees = setOf(1), baseSurfaceId = "tn")
        val surfaces = SurfacesDuChantier(listOf(stock, levee), emptyList())
        assertEquals(listOf("Terrain naturel (levée)"), surfaces.choix().map { it.nom })
        val r = surfaces.volume(stock)
        assertEquals("B", r.mode)
        assertEquals(1636.197, r.remblai, 0.001)
        // 15 triangles dans le contour (le mode A en garde 20 : aussi les 5 du creux du « L », sans effet sur le volume).
        assertEquals(15, r.trianglesBase.size)
        // Sans base choisie : mode A, même résultat.
        stock.baseSurfaceId = null
        assertEquals(1636.197, surfaces.volume(stock).remblai, 0.001)
        assertEquals("A", surfaces.volume(stock).mode)
    }

    @Test
    fun `base importee, ou base qui n'existe plus`() {
        val s = stocksDeReference()[1]
        val importee = SurfaceImportee("x", "c", plan431.nom, "LandXML", "20260520_Stock.xml", 0, plan431.sommets, plan431.faces)
        val stock = Stock("s2", "c", "Stock 2", 0, points = s.points, lignesFermees = setOf(1), baseSurfaceId = "x")
        val surfaces = SurfacesDuChantier(emptyList(), listOf(importee))
        assertEquals(1006.247, surfaces.volume(stock).deblai, 0.001)
        stock.baseSurfaceId = "disparue"
        val r = surfaces.volume(stock)
        assertNotNull(r.erreur)
        assertTrue(r.erreur!!.contains("n'existe plus"))
    }
}
