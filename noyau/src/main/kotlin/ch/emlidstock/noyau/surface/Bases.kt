package ch.emlidstock.noyau.surface

import ch.emlidstock.noyau.leve.Stock
import ch.emlidstock.noyau.leve.TypeLeve
import ch.emlidstock.noyau.volume.BaseDeVolume
import ch.emlidstock.noyau.volume.ResultatVolumeStock
import ch.emlidstock.noyau.volume.calculerVolumeStock

/** Une surface du chantier proposée comme base de volume (mode B) : identifiant et nom affiché. */
data class ChoixDeBase(val id: String, val nom: String)

/**
 * Surfaces d'un chantier : levées (levés de type SURFACE) et importées (LandXML, DXF).
 * Sert à proposer et à retrouver la base du volume d'un stock (mode B).
 */
class SurfacesDuChantier(levees: List<Stock>, val importees: List<SurfaceImportee>) {

    val levees: List<Stock> = levees.filter { it.type == TypeLeve.SURFACE }

    /** Surfaces proposées comme base, levées puis importées. */
    fun choix(): List<ChoixDeBase> =
        levees.sortedBy { it.nom.lowercase() }.map { ChoixDeBase(it.id, "${it.nom} (levée)") } +
            importees.sortedBy { it.nom.lowercase() }.map { ChoixDeBase(it.id, "${it.nom} (${it.origine})") }

    /** Nom de la surface de cet identifiant, ou null si elle n'existe plus. */
    fun nom(id: String): String? = levees.firstOrNull { it.id == id }?.nom ?: importees.firstOrNull { it.id == id }?.nom

    /** Triangles de la surface de cet identifiant (ou erreur compréhensible). */
    fun base(id: String): Pair<BaseDeVolume?, String?> {
        levees.firstOrNull { it.id == id }?.let { levee ->
            val surface = calculerSurfaceLevee(levee.points, levee.lignesFermees, levee.trianglesSupprimes)
            return if (surface.erreur != null) {
                Pair(null, "surface de base « ${levee.nom} » : ${surface.erreur}")
            } else {
                Pair(BaseDeVolume(levee.nom, surface.triangles), null)
            }
        }
        importees.firstOrNull { it.id == id }?.let { return Pair(BaseDeVolume(it.nom, it.triangles), null) }
        return Pair(null, "la surface de base n'existe plus : choisissez une autre base")
    }

    /** Volume d'un stock avec la base qu'il a choisie : points B (mode A) ou une surface (mode B). */
    fun volume(stock: Stock): ResultatVolumeStock {
        val id = stock.baseSurfaceId ?: return calculerVolumeStock(stock.points, stock.lignesFermees)
        val (base, erreur) = base(id)
        if (base == null) return ResultatVolumeStock(erreur = erreur)
        return calculerVolumeStock(stock.points, stock.lignesFermees, base)
    }
}
