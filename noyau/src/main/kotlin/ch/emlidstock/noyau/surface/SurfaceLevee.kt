package ch.emlidstock.noyau.surface

import ch.emlidstock.noyau.leve.PointLeve
import ch.emlidstock.noyau.volume.Triangle
import ch.emlidstock.noyau.volume.centre
import ch.emlidstock.noyau.volume.plan
import ch.emlidstock.noyau.volume.pointDansPolygone
import ch.emlidstock.noyau.volume.preparerPoints
import ch.emlidstock.noyau.volume.segmentsDeLigne
import ch.emlidstock.noyau.volume.trianglerAvecContraintes

/**
 * Triangles d'une surface levée (ou erreur), avec les triangles retirés au doigt
 * (pour les montrer et pouvoir les remettre).
 */
data class ResultatSurface(
    val triangles: List<Triangle> = emptyList(),
    val trianglesRetires: List<Triangle> = emptyList(),
    val avertissements: List<String> = emptyList(),
    val erreur: String? = null,
)

/**
 * Clé d'un triangle : les noms de ses 3 sommets, triés, séparés par « | » (comme la V1).
 * Elle ne dépend pas de l'ordre des sommets et reste la même tant que le triangle existe.
 */
fun cleTriangle(t: Triangle): String = t.sommets.map { it.nom }.sorted().joinToString("|")

/**
 * Surface levée (terrain naturel…), méthode de la V1 (src/topo/surface.js) :
 * - tous les points du levé sont triangulés (Delaunay), en respectant les lignes de rupture (H) ;
 * - avec un contour (au moins 3 points B, dans l'ordre) : contour imposé et seuls les triangles
 *   à l'intérieur sont gardés ; sans contour, le bord est l'enveloppe extérieure des points ;
 * - les triangles retirés au doigt ([trianglesSupprimes], voir [cleTriangle]) sont enlevés.
 * Unités : m.
 */
fun calculerSurfaceLevee(points: List<PointLeve>, lignesFermees: Set<Int>, trianglesSupprimes: Set<String>): ResultatSurface {
    val prepares = preparerPoints(points, lignesFermees)
    if (prepares.sommets.size < 3) return ResultatSurface(erreur = "il faut au moins 3 points pour faire une surface")
    val avecContour = prepares.contour.size >= 3
    val segments = (if (avecContour) segmentsDeLigne(prepares.contour, fermee = true) else emptyList()) +
        prepares.lignes.flatMap { (noms, fermee) -> segmentsDeLigne(noms, fermee) }
    val triangulation = trianglerAvecContraintes(prepares.sommets, segments)
    triangulation.erreur?.let { return ResultatSurface(erreur = it, avertissements = prepares.avertissements) }

    var triangles = triangulation.triangles
    if (avecContour) {
        val parNom = prepares.sommets.associateBy { it.nom }
        val polygone = prepares.contour.map { plan(parNom.getValue(it)) }
        triangles = triangles.filter { pointDansPolygone(centre(it), polygone) }
    }
    val (retires, gardes) = triangles.partition { cleTriangle(it) in trianglesSupprimes }
    if (gardes.isEmpty()) return ResultatSurface(trianglesRetires = retires, erreur = "aucun triangle (tous retirés)")
    return ResultatSurface(gardes, retires, prepares.avertissements)
}
