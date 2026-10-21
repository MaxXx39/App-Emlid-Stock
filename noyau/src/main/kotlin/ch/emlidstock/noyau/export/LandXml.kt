package ch.emlidstock.noyau.export

import ch.emlidstock.noyau.volume.Triangle
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Export LandXML 1.1 (surfaces TIN), repris de l'appli topo V1 (src/topo/landxml.js) :
 * une surface par stock (le dessus, triangles du calcul de volume). Attention : dans le LandXML,
 * les points s'écrivent « Nord Est altitude » (et non Est, Nord).
 */
fun ecrireLandXml(surfaces: List<Pair<String, List<Triangle>>>, nomProjet: String, maintenant: LocalDateTime = LocalDateTime.now()): String {
    val blocs = surfaces.map { (nom, triangles) ->
        val ids = LinkedHashMap<String, Int>()
        val lignesPoints = mutableListOf<String>()
        fun idDuPoint(est: Double, nord: Double, altitude: Double, nomPoint: String): Int {
            val cle = "${trois(est)} ${trois(nord)} ${trois(altitude)}"
            return ids.getOrPut(cle) {
                val id = ids.size + 1
                val attributNom = if (nomPoint.isNotEmpty()) " name=\"${encoder(nomPoint)}\"" else ""
                lignesPoints.add("          <P id=\"$id\"$attributNom>${trois(nord)} ${trois(est)} ${trois(altitude)}</P>")
                id
            }
        }
        val faces = triangles.map { t -> "          <F>${t.sommets.joinToString(" ") { idDuPoint(it.est, it.nord, it.altitude, it.nom).toString() }}</F>" }
        val aire = triangles.sumOf { t -> abs((t.b.est - t.a.est) * (t.c.nord - t.a.nord) - (t.c.est - t.a.est) * (t.b.nord - t.a.nord)) / 2 }
        """    <Surface name="${encoder(nom)}">
      <Definition surfType="TIN" area2DSurf="${trois(aire)}">
        <Pnts>
${lignesPoints.joinToString("\n")}
        </Pnts>
        <Faces>
${faces.joinToString("\n")}
        </Faces>
      </Definition>
    </Surface>"""
    }
    val date = maintenant.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    val heure = maintenant.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    return """<?xml version="1.0" encoding="utf-8"?>
<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.1" version="1.1" date="$date" time="$heure">
  <Application name="Emlid Stock - Volumes de stocks" />
  <Project name="${encoder(nomProjet)}" />
  <Units>
    <Metric areaUnit="squareMeter" linearUnit="meter" volumeUnit="cubicMeter" />
  </Units>
  <Surfaces>
${blocs.joinToString("\n")}
  </Surfaces>
</LandXML>
"""
}

/** Protège les caractères spéciaux du XML (&, <, >, "). */
private fun encoder(texte: String) = texte.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
