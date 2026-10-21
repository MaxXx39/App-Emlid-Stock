package ch.emlidstock.noyau.export

import ch.emlidstock.noyau.leve.ModeLeve
import ch.emlidstock.noyau.leve.PointLeve
import ch.emlidstock.noyau.leve.Stock
import ch.emlidstock.noyau.volume.ResultatVolumeStock
import java.util.Locale

/** Un stock à exporter : ses points et le résultat de son volume (null ou erreur = pas de volume). */
data class StockExporte(val stock: Stock, val resultat: ResultatVolumeStock?) {
    val nom: String get() = stock.nom
    val points: List<PointLeve> get() = stock.points
    val volumeValable: Boolean get() = resultat != null && resultat.erreur == null

    /** Noms des points du contour (bas de talus B1, B2… dans l'ordre). */
    fun contour(): List<String> = points.filter { it.mode == ModeLeve.BAS_DE_TALUS }.sortedBy { it.numero }.map { it.nom }

    /** Lignes de haut de talus : (noms dans l'ordre, fermée ?). */
    fun lignesHaut(): List<Pair<List<String>, Boolean>> =
        points.filter { it.mode == ModeLeve.HAUT_DE_TALUS }
            .groupBy { it.ligne ?: 1 }
            .toSortedMap()
            .map { (numero, liste) -> Pair(liste.sortedBy { it.numero }.map { it.nom }, numero in stock.lignesFermees) }
}

/** Nombre avec 3 décimales et le point décimal (m, m², m³), comme Mensura. */
fun trois(valeur: Double): String = String.format(Locale.ROOT, "%.3f", valeur)
