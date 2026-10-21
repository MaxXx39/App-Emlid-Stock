package ch.emlidstock.noyau.leve

import java.util.Locale

/**
 * Export des points d'un stock en texte : une ligne par point, séparée par des tabulations,
 * « nom  Est  Nord  altitude » (m, 3 décimales, point décimal), fins de ligne Windows.
 * Même format que stock_export.txt, lu par Mensura et par l'appli topo V1.
 * Les points sont dans l'ordre B, puis H (ligne par ligne), puis C, chacun par numéro.
 */
fun exporterPointsTxt(points: List<PointLeve>): String {
    val ordre = points.sortedWith(compareBy({ it.mode.ordinal }, { it.ligne ?: 0 }, { it.numero }))
    return ordre.joinToString("") { p ->
        String.format(Locale.ROOT, "%s\t%.3f\t%.3f\t%.3f\r\n", p.nom, p.est, p.nord, p.altitude)
    }
}

/** Nom de fichier sans caractères gênants, à partir du nom du stock. */
fun nomDeFichier(nom: String, extension: String): String =
    nom.replace(Regex("[^A-Za-z0-9À-ÿ._ -]"), "_").trim().ifEmpty { "stock" } + ".$extension"
