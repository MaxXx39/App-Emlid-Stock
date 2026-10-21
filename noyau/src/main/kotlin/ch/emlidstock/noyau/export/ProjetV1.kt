package ch.emlidstock.noyau.export

import ch.emlidstock.noyau.surface.SurfaceImportee
import java.time.Instant

/**
 * Passerelle vers l'appli topo V1 (bureau) : fichier projet « .topo.json » que la V1 ouvre avec
 * « Ouvrir un fichier projet ». Format de la V1 (src/projet/projet.js, format « topo-projet », version 1) :
 * - un levé par stock ou surface levée (ses points : nom, Est, Nord, altitude) ;
 * - une surface par stock, créée à partir des points de ce levé : contour = bas de talus B1, B2…
 *   dans l'ordre, lignes de rupture = lignes de haut de talus (fermées ou non), tous les points du
 *   levé dans le contour ; base A (points du contour) ou B (la surface choisie), donc le même volume
 *   que dans cette appli ;
 * - une surface par surface levée (contour facultatif, triangles retirés au doigt, sans volume) ;
 * - une surface par surface importée ([importees], LandXML ou DXF : points et triangles) ;
 * - le cartouche (affaire, dessiné par).
 * Les surfaces gardent l'identifiant de cette appli, pour que la base B d'un stock les retrouve.
 */
fun ecrireProjetV1(
    nomProjet: String,
    stocks: List<StockExporte>,
    affaire: String,
    dessinePar: String,
    maintenant: Instant = Instant.now(),
    importees: List<SurfaceImportee> = emptyList(),
    nouvelId: () -> String = { java.util.UUID.randomUUID().toString() },
): String {
    val date = maintenant.toString()
    val leves = mutableListOf<String>()
    val surfaces = mutableListOf<String>()
    val idsSurfaces = stocks.filter { it.estSurface }.map { it.stock.id }.toSet() + importees.map { it.id }
    for (s in stocks) {
        val idLeve = nouvelId()
        val points = s.points.sortedWith(compareBy({ it.mode.ordinal }, { it.ligne ?: 0 }, { it.numero })).joinToString(",") { p ->
            """{"nom":${texteJson(p.nom)},"est":${trois(p.est)},"nord":${trois(p.nord)},"altitude":${trois(p.altitude)}}"""
        }
        leves.add("""{"id":${texteJson(idLeve)},"nom":${texteJson(s.nom)},"importeLe":${texteJson(date)},"points":[$points]}""")
        val lignes = s.lignesHaut().joinToString(",") { (noms, fermee) -> """{"noms":${listeJson(noms)},"fermee":$fermee}""" }
        val contour = s.contour().takeIf { !s.estSurface || it.size >= 3 }.orEmpty()
        val baseId = s.stock.baseSurfaceId?.takeIf { it in idsSurfaces }
        val volume = when {
            s.estSurface -> """{"base":"aucune","surfaceId":null}"""
            baseId != null -> """{"base":"surface","surfaceId":${texteJson(baseId)}}"""
            else -> """{"base":"contour","surfaceId":null}"""
        }
        val supprimes = if (s.estSurface) listeJson(s.stock.trianglesSupprimes.sorted()) else "[]"
        surfaces.add(
            """{"id":${texteJson(s.stock.id)},"nom":${texteJson(s.nom)},"type":"points","leveId":${texteJson(idLeve)},""" +
                """"tousLesPoints":true,"points":[],"contour":${listeJson(contour)},"lignesRupture":[$lignes],""" +
                """"trianglesSupprimes":$supprimes,"volume":$volume}""",
        )
    }
    for (surface in importees) {
        val points = surface.sommets.joinToString(",") { p ->
            """{"nom":${texteJson(p.nom)},"est":${trois(p.est)},"nord":${trois(p.nord)},"altitude":${trois(p.altitude)}}"""
        }
        val faces = surface.faces.joinToString(",") { "[${it[0]},${it[1]},${it[2]}]" }
        val type = if (surface.origine == "DXF") "dxf" else "landxml"
        surfaces.add(
            """{"id":${texteJson(surface.id)},"nom":${texteJson(surface.nom)},"type":"$type","nomFichier":${texteJson(surface.nomFichier)},""" +
                """"points":[$points],"faces":[$faces],"volume":{"base":"aucune","surfaceId":null}}""",
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
