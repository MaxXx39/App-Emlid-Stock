package ch.emlidstock.noyau.controle

import ch.emlidstock.noyau.volume.PointPlan
import ch.emlidstock.noyau.volume.Triangle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/*
 * Courbes de niveau et bandes de couleur selon l'altitude, calculées sur une surface en triangles.
 * Repris de l'appli topo V1 (src/topo/courbes.js) avec ses tests.
 *
 * Sur chaque triangle, l'altitude varie de façon linéaire (plan passant par les 3 sommets).
 * Une courbe de niveau traverse donc chaque triangle en ligne droite : on calcule ses
 * deux points de passage sur les côtés du triangle.
 * Règle pour les points pile à l'altitude d'une courbe : ils sont comptés « au-dessus ».
 * Ainsi un côté posé exactement sur la courbe n'est dessiné qu'une seule fois.
 *
 * Unités : mètres (Est, Nord, altitude), mètres carrés (surfaces).
 */

/** Morceau de courbe de niveau : de [a] à [b] (m), à l'altitude [altitude] (m). */
data class SegmentCourbe(val altitude: Double, val a: PointPlan, val b: PointPlan)

/** Bande de couleur : morceau de surface entre [altitude] (bas de la bande, m) et altitude + équidistance. */
data class Bande(val altitude: Double, val polygone: List<PointPlan>)

/** Altitudes la plus basse et la plus haute d'une liste de triangles (m). */
data class Plage(val min: Double, val max: Double)

/** Point d'un polygone avec son altitude (pour les découpes). */
private data class Point3(val est: Double, val nord: Double, val altitude: Double)

/**
 * Choisit une équidistance « ronde » (m) pour avoir environ 10 courbes entre
 * l'altitude la plus basse et la plus haute.
 */
fun equidistanceAuto(altitudeMin: Double, altitudeMax: Double): Double {
    val ecart = altitudeMax - altitudeMin
    val valeurs = listOf(0.05, 0.1, 0.2, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0)
    return valeurs.firstOrNull { ecart / it <= 12 } ?: 100.0
}

fun plageAltitudes(triangles: List<Triangle>): Plage {
    var min = Double.POSITIVE_INFINITY
    var max = Double.NEGATIVE_INFINITY
    for (t in triangles) for (s in t.sommets) {
        min = minOf(min, s.altitude)
        max = maxOf(max, s.altitude)
    }
    return Plage(min, max)
}

/** Courbes de niveau : une courbe tous les [equidistance] mètres. */
fun courbesDeNiveau(triangles: List<Triangle>, equidistance: Double): List<SegmentCourbe> {
    val segments = mutableListOf<SegmentCourbe>()
    for (t in triangles) {
        val sommets = t.sommets
        val altitudes = sommets.map { it.altitude }
        val premier = ceil(altitudes.min() / equidistance).toLong()
        val dernier = floor(altitudes.max() / equidistance).toLong()
        for (k in premier..dernier) {
            val niveau = arrondir(k * equidistance)
            val passages = mutableListOf<PointPlan>()
            for (i in 0 until 3) {
                val p = sommets[i]
                val q = sommets[(i + 1) % 3]
                // Le côté p-q croise la courbe si un bout est au-dessus et l'autre en dessous.
                if ((p.altitude >= niveau) != (q.altitude >= niveau)) {
                    val r = (niveau - p.altitude) / (q.altitude - p.altitude)
                    passages.add(PointPlan(p.est + r * (q.est - p.est), p.nord + r * (q.nord - p.nord)))
                }
            }
            if (passages.size == 2) segments.add(SegmentCourbe(niveau, passages[0], passages[1]))
        }
    }
    return segments
}

/** Bandes de couleur : chaque triangle découpé en morceaux compris entre deux courbes. */
fun bandesDeNiveau(triangles: List<Triangle>, equidistance: Double): List<Bande> {
    val bandes = mutableListOf<Bande>()
    for (t in triangles) {
        val sommets = t.sommets.map { Point3(it.est, it.nord, it.altitude) }
        val premier = floor(sommets.minOf { it.altitude } / equidistance).toLong()
        val dernier = floor(sommets.maxOf { it.altitude } / equidistance).toLong()
        for (k in premier..dernier) {
            val bas = arrondir(k * equidistance)
            val haut = arrondir((k + 1) * equidistance)
            // On garde la partie du triangle au-dessus du bas, puis celle en dessous du haut.
            val morceau = couperSelonAltitude(couperSelonAltitude(sommets, bas, garderDessus = true), haut, garderDessus = false)
            if (morceau.size >= 3) bandes.add(Bande(bas, morceau.map { PointPlan(it.est, it.nord) }))
        }
    }
    return bandes
}

/** Garde la partie d'un polygone située au-dessus (ou en dessous) d'une altitude donnée. */
private fun couperSelonAltitude(polygone: List<Point3>, niveau: Double, garderDessus: Boolean): List<Point3> {
    fun dedans(p: Point3) = if (garderDessus) p.altitude >= niveau else p.altitude <= niveau
    val resultat = mutableListOf<Point3>()
    for (i in polygone.indices) {
        val p = polygone[i]
        val q = polygone[(i + 1) % polygone.size]
        if (dedans(p)) resultat.add(p)
        if (dedans(p) != dedans(q)) {
            val r = (niveau - p.altitude) / (q.altitude - p.altitude)
            resultat.add(Point3(p.est + r * (q.est - p.est), p.nord + r * (q.nord - p.nord), niveau))
        }
    }
    return resultat
}

/** Évite les restes d'arrondi (0.30000000000000004 → 0.3). */
private fun arrondir(valeur: Double): Double = (valeur * 1e6).roundToLong() / 1e6
