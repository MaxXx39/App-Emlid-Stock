package ch.emlidstock.noyau.export

import ch.emlidstock.noyau.controle.courbesDeNiveau
import ch.emlidstock.noyau.volume.Triangle
import java.text.Normalizer

/*
 * Export DXF (format AutoCAD R12, lu par AutoCAD, Mensura et la plupart des logiciels de DAO),
 * repris de l'appli topo V1 (src/export/dxf.js). Coordonnées MN95 : X = Est, Y = Nord, Z = altitude (m).
 *
 * Calques : POINTS (points levés), NOMS (nom de chaque point), CONTOURS (bas de talus, 3D),
 * LIGNES_RUPTURE (hauts de talus, 3D), TRIANGLES_<stock> (faces 3D du dessus si le volume est calculé,
 * ou de la surface levée),
 * COURBES (courbes de niveau, si demandées).
 */

// Couleurs AutoCAD : 1 rouge, 7 blanc/noir, 8 gris, 30 orange, 32 brun.
private val COULEURS = linkedMapOf("POINTS" to 7, "NOMS" to 8, "CONTOURS" to 1, "LIGNES_RUPTURE" to 30, "COURBES" to 32)

/** Texte du fichier DXF. [equidistance] : courbes de niveau tous les … m, ou null pour ne pas en mettre. */
fun genererDxf(stocks: List<StockExporte>, equidistance: Double? = null): String {
    val entites = mutableListOf<List<String>>()
    val calques = LinkedHashMap(COULEURS.filterKeys { it != "COURBES" || equidistance != null })

    for (s in stocks) {
        val parNom = s.points.associateBy { it.nom }
        for (p in s.points) {
            entites.add(listOf("0", "POINT", "8", "POINTS") + xyz(p.est, p.nord, p.altitude, 0))
            entites.add(listOf("0", "TEXT", "8", "NOMS") + xyz(p.est + 0.2, p.nord + 0.2, p.altitude, 0) + listOf("40", "0.3", "1", p.nom))
        }
        val contour = s.contour().mapNotNull { parNom[it] }
        if (contour.size >= 3) entites.addAll(polyligne3D(contour.map { Triple(it.est, it.nord, it.altitude) }, true, "CONTOURS"))
        for ((noms, fermee) in s.lignesHaut()) {
            val sommets = noms.mapNotNull { parNom[it] }
            if (sommets.size >= 2) entites.addAll(polyligne3D(sommets.map { Triple(it.est, it.nord, it.altitude) }, fermee, "LIGNES_RUPTURE"))
        }
        val triangles = s.triangles
        if (triangles.isNotEmpty()) {
            val calque = nomDeCalque("TRIANGLES_${s.nom}")
            calques[calque] = 8
            triangles.forEach { entites.add(face3D(it, calque)) }
            if (equidistance != null) {
                for (c in courbesDeNiveau(triangles, equidistance)) {
                    entites.add(listOf("0", "LINE", "8", "COURBES") + xyz(c.a.est, c.a.nord, c.altitude, 0) + xyz(c.b.est, c.b.nord, c.altitude, 1))
                }
            }
        }
    }

    val lignes = mutableListOf(
        "0", "SECTION", "2", "HEADER", "9", "\$ACADVER", "1", "AC1009", "9", "\$INSUNITS", "70", "6", "0", "ENDSEC",
        "0", "SECTION", "2", "TABLES", "0", "TABLE", "2", "LAYER", "70", calques.size.toString(),
    )
    for ((nom, couleur) in calques) lignes += listOf("0", "LAYER", "2", nom, "70", "0", "62", couleur.toString(), "6", "CONTINUOUS")
    lignes += listOf("0", "ENDTAB", "0", "ENDSEC", "0", "SECTION", "2", "ENTITIES")
    entites.forEach { lignes += it }
    lignes += listOf("0", "ENDSEC", "0", "EOF")
    return lignes.joinToString("\r\n") + "\r\n"
}

/** Codes DXF d'un point : X (10+n), Y (20+n), Z (30+n), en mm. */
private fun xyz(est: Double, nord: Double, altitude: Double, n: Int) =
    listOf((10 + n).toString(), trois(est), (20 + n).toString(), trois(nord), (30 + n).toString(), trois(altitude))

/** Face 3D (triangle : le 4e sommet répète le 3e). */
private fun face3D(t: Triangle, calque: String) =
    listOf("0", "3DFACE", "8", calque) + xyz(t.a.est, t.a.nord, t.a.altitude, 0) + xyz(t.b.est, t.b.nord, t.b.altitude, 1) +
        xyz(t.c.est, t.c.nord, t.c.altitude, 2) + xyz(t.c.est, t.c.nord, t.c.altitude, 3)

/** Polyligne 3D (fermée ou non). */
private fun polyligne3D(sommets: List<Triple<Double, Double, Double>>, fermee: Boolean, calque: String): List<List<String>> =
    listOf(listOf("0", "POLYLINE", "8", calque, "66", "1", "10", "0", "20", "0", "30", "0", "70", (8 + if (fermee) 1 else 0).toString())) +
        sommets.map { (e, n, h) -> listOf("0", "VERTEX", "8", calque) + xyz(e, n, h, 0) + listOf("70", "32") } +
        listOf(listOf("0", "SEQEND", "8", calque))

/** Nom de calque accepté par AutoCAD : majuscules, sans accent ni espace, 31 caractères au plus. */
fun nomDeCalque(texte: String): String =
    Normalizer.normalize(texte, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .uppercase().replace(Regex("[^A-Z0-9_-]+"), "_").take(31)
