package ch.emlidstock.noyau.export

import java.time.Instant

/**
 * Passerelle vers l'appli topo V1 (bureau) : fichier projet « .topo.json » que la V1 ouvre avec
 * « Ouvrir un fichier projet ». Format de la V1 (src/projet/projet.js, format « topo-projet », version 1) :
 * - un levé par stock (ses points : nom, Est, Nord, altitude) ;
 * - une surface par stock, créée à partir des points de ce levé : contour = bas de talus B1, B2…
 *   dans l'ordre, lignes de rupture = lignes de haut de talus (fermées ou non), tous les points du
 *   levé dans le contour, base A (points du contour) — donc le même volume que dans cette appli ;
 * - le cartouche (affaire, dessiné par).
 */
fun ecrireProjetV1(
    nomProjet: String,
    stocks: List<StockExporte>,
    affaire: String,
    dessinePar: String,
    maintenant: Instant = Instant.now(),
    nouvelId: () -> String = { java.util.UUID.randomUUID().toString() },
): String {
    val date = maintenant.toString()
    val leves = mutableListOf<String>()
    val surfaces = mutableListOf<String>()
    for (s in stocks) {
        val idLeve = nouvelId()
        val points = s.points.sortedWith(compareBy({ it.mode.ordinal }, { it.ligne ?: 0 }, { it.numero })).joinToString(",") { p ->
            """{"nom":${texteJson(p.nom)},"est":${trois(p.est)},"nord":${trois(p.nord)},"altitude":${trois(p.altitude)}}"""
        }
        leves.add("""{"id":${texteJson(idLeve)},"nom":${texteJson(s.nom)},"importeLe":${texteJson(date)},"points":[$points]}""")
        val lignes = s.lignesHaut().joinToString(",") { (noms, fermee) -> """{"noms":${listeJson(noms)},"fermee":$fermee}""" }
        surfaces.add(
            """{"id":${texteJson(nouvelId())},"nom":${texteJson(s.nom)},"type":"points","leveId":${texteJson(idLeve)},""" +
                """"tousLesPoints":true,"points":[],"contour":${listeJson(s.contour())},"lignesRupture":[$lignes],""" +
                """"trianglesSupprimes":[],"volume":{"base":"contour","surfaceId":null}}""",
        )
    }
    val premierLeve = if (leves.isEmpty()) "null" else Regex("\"id\":(\"[^\"]*\")").find(leves.first())!!.groupValues[1]
    return """{"format":"topo-projet","version":1,"id":${texteJson(nouvelId())},"nom":${texteJson(nomProjet)},""" +
        """"creeLe":${texteJson(date)},"modifieLe":${texteJson(date)},"leves":[${leves.joinToString(",")}],""" +
        """"leveActifId":$premierLeve,"surfaces":[${surfaces.joinToString(",")}],"comparaisons":[],""" +
        """"cartouche":{"affaire":${texteJson(affaire)},"dessinePar":${texteJson(dessinePar)}}}"""
}

private fun listeJson(noms: List<String>) = noms.joinToString(",", "[", "]") { texteJson(it) }

/** Texte JSON entre guillemets, avec les caractères spéciaux protégés. */
fun texteJson(texte: String): String {
    val sortie = StringBuilder("\"")
    for (c in texte) {
        when {
            c == '"' -> sortie.append("\\\"")
            c == '\\' -> sortie.append("\\\\")
            c == '\n' -> sortie.append("\\n")
            c == '\r' -> sortie.append("\\r")
            c == '\t' -> sortie.append("\\t")
            c < ' ' -> sortie.append(String.format("\\u%04x", c.code))
            else -> sortie.append(c)
        }
    }
    return sortie.append('"').toString()
}
