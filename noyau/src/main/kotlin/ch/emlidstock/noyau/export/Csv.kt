package ch.emlidstock.noyau.export

/**
 * Export CSV pour Excel (séparateur point-virgule, réglage suisse d'Excel ; point décimal) :
 * 1. tableau des volumes, comme l'appli topo V1 : Nom ; Base ; Surface (m²) ; Remblai (m³) ; Déblai (m³)
 *    (base « A » = points du bas de talus) ;
 * 2. après une ligne vide, la liste des points : Stock ; Point ; Rôle ; Est ; Nord ; Altitude.
 * Fins de ligne Windows.
 */
fun genererCsv(stocks: List<StockExporte>): String {
    val lignes = mutableListOf(listOf("Nom", "Base", "Surface (m²)", "Remblai (m³)", "Déblai (m³)"))
    for (s in stocks) {
        val r = s.resultat
        if (r == null || r.erreur != null) continue
        lignes.add(listOf(s.nom, "A", trois(r.surfacePlan), trois(r.remblai), trois(r.deblai)))
    }
    lignes.add(emptyList())
    lignes.add(listOf("Stock", "Point", "Rôle", "Est", "Nord", "Altitude"))
    for (s in stocks) {
        for (p in s.points.sortedWith(compareBy({ it.mode.ordinal }, { it.ligne ?: 0 }, { it.numero }))) {
            lignes.add(listOf(s.nom, p.nom, p.mode.libelle, trois(p.est), trois(p.nord), trois(p.altitude)))
        }
    }
    return lignes.joinToString("") { ligne -> ligne.joinToString(";") { champCsv(it) } + "\r\n" }
}

/** Met un champ entre guillemets s'il contient un point-virgule, un guillemet ou un retour à la ligne. */
private fun champCsv(valeur: String): String =
    if (valeur.any { it == ';' || it == '"' || it == '\r' || it == '\n' }) "\"" + valeur.replace("\"", "\"\"") + "\"" else valeur
