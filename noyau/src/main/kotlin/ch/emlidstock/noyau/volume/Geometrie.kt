package ch.emlidstock.noyau.volume

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/*
 * Petits outils de géométrie en plan (Est, Nord), repris de l'appli topo V1 (src/topo/volume.js).
 * Unités : mètres, mètres carrés.
 */

/** Un sommet de surface : nom, Est, Nord, altitude (m). */
data class Sommet(val nom: String, val est: Double, val nord: Double, val altitude: Double)

/** Un triangle de surface (3 sommets). */
data class Triangle(val a: Sommet, val b: Sommet, val c: Sommet) {
    val sommets: List<Sommet> get() = listOf(a, b, c)
}

/** Point en plan (Est, Nord en m), sans altitude. */
data class PointPlan(val est: Double, val nord: Double)

/** Aire signée d'un triangle (m²) : positive si les points tournent dans le sens inverse des aiguilles d'une montre. */
fun aireTriangle(a: PointPlan, b: PointPlan, c: PointPlan): Double =
    ((b.est - a.est) * (c.nord - a.nord) - (c.est - a.est) * (b.nord - a.nord)) / 2

fun aireTriangle(t: Triangle): Double = aireTriangle(plan(t.a), plan(t.b), plan(t.c))

/** Aire signée d'un polygone (m²), formule des trapèzes. */
fun airePolygone(sommets: List<PointPlan>): Double {
    var aire = 0.0
    for (i in sommets.indices) {
        val a = sommets[i]
        val b = sommets[(i + 1) % sommets.size]
        aire += (a.est - b.est) * (a.nord + b.nord)
    }
    return aire / 2
}

fun plan(s: Sommet) = PointPlan(s.est, s.nord)

/** Centre de gravité d'un triangle (en plan). */
fun centre(t: Triangle) = PointPlan((t.a.est + t.b.est + t.c.est) / 3, (t.a.nord + t.b.nord + t.c.nord) / 3)

/** Vrai si le point est à l'intérieur du polygone (méthode du rayon). */
fun pointDansPolygone(point: PointPlan, polygone: List<PointPlan>): Boolean {
    var dedans = false
    var j = polygone.size - 1
    for (i in polygone.indices) {
        val a = polygone[i]
        val b = polygone[j]
        if ((a.nord > point.nord) != (b.nord > point.nord)) {
            val estCroisement = a.est + (point.nord - a.nord) / (b.nord - a.nord) * (b.est - a.est)
            if (point.est < estCroisement) dedans = !dedans
        }
        j = i
    }
    return dedans
}

/** Altitude (m) au point (est, nord) sur le plan qui passe par les 3 sommets du triangle. */
fun altitudeSurTriangle(t: Triangle, est: Double, nord: Double): Double {
    val p = PointPlan(est, nord)
    val aire = aireTriangle(t)
    val poidsA = aireTriangle(p, plan(t.b), plan(t.c)) / aire
    val poidsB = aireTriangle(plan(t.a), p, plan(t.c)) / aire
    val poidsC = 1 - poidsA - poidsB
    return poidsA * t.a.altitude + poidsB * t.b.altitude + poidsC * t.c.altitude
}

/**
 * Partie commune (en plan) de deux triangles, sous forme de polygone convexe.
 * Méthode de Sutherland-Hodgman : on coupe le premier triangle par chaque côté du second.
 */
fun intersectionTriangles(triangle: Triangle, decoupe: Triangle): List<PointPlan> {
    var polygone = triangle.sommets.map { plan(it) }
    val sens = sign(aireTriangle(decoupe))
    val d = decoupe.sommets
    for (i in 0 until 3) {
        if (polygone.isEmpty()) break
        val a = d[i]
        val b = d[(i + 1) % 3]
        // Côté « intérieur » du bord a-b : distance signée positive.
        fun cote(p: PointPlan) = sens * ((b.est - a.est) * (p.nord - a.nord) - (b.nord - a.nord) * (p.est - a.est))
        val entree = polygone
        val sortie = mutableListOf<PointPlan>()
        for (j in entree.indices) {
            val p = entree[j]
            val q = entree[(j + 1) % entree.size]
            val cp = cote(p)
            val cq = cote(q)
            if (cp >= 0) sortie.add(p)
            if ((cp > 0 && cq < 0) || (cp < 0 && cq > 0)) {
                val t = cp / (cp - cq)
                sortie.add(PointPlan(p.est + t * (q.est - p.est), p.nord + t * (q.nord - p.nord)))
            }
        }
        polygone = sortie
    }
    return polygone
}

/** Orientation de c par rapport à la droite a-b : > 0 à gauche, < 0 à droite, 0 alignés. */
fun orientation(a: PointPlan, b: PointPlan, c: PointPlan): Double =
    (b.est - a.est) * (c.nord - a.nord) - (b.nord - a.nord) * (c.est - a.est)

/** Vrai si les segments p1-p2 et p3-p4 (sans point commun) se croisent ou se touchent. */
fun segmentsSeCroisent(p1: PointPlan, p2: PointPlan, p3: PointPlan, p4: PointPlan): Boolean {
    val o1 = orientation(p1, p2, p3)
    val o2 = orientation(p1, p2, p4)
    val o3 = orientation(p3, p4, p1)
    val o4 = orientation(p3, p4, p2)
    if (o1 * o2 < 0 && o3 * o4 < 0) return true
    // Cas où un point est exactement sur l'autre segment.
    return (o1 == 0.0 && surSegment(p1, p2, p3)) || (o2 == 0.0 && surSegment(p1, p2, p4)) ||
        (o3 == 0.0 && surSegment(p3, p4, p1)) || (o4 == 0.0 && surSegment(p3, p4, p2))
}

/** Vrai si deux segments qui partagent un point sont alignés et se superposent. */
fun segmentsSeChevauchent(p1: PointPlan, p2: PointPlan, p3: PointPlan, p4: PointPlan): Boolean {
    if (orientation(p1, p2, p3) != 0.0 || orientation(p1, p2, p4) != 0.0) return false
    val autres = listOf(p3, p4).filter { it != p1 && it != p2 }
    val miens = listOf(p1, p2).filter { it != p3 && it != p4 }
    return autres.any { surSegment(p1, p2, it) } || miens.any { surSegment(p3, p4, it) }
}

/** Vrai si c (aligné avec a-b) est entre a et b. */
fun surSegment(a: PointPlan, b: PointPlan, c: PointPlan): Boolean =
    min(a.est, b.est) <= c.est && c.est <= max(a.est, b.est) &&
        min(a.nord, b.nord) <= c.nord && c.nord <= max(a.nord, b.nord)

/** Boîte englobante d'un triangle (pour éviter de comparer des triangles éloignés). */
class Boite(t: Triangle) {
    val estMin = minOf(t.a.est, t.b.est, t.c.est)
    val estMax = maxOf(t.a.est, t.b.est, t.c.est)
    val nordMin = minOf(t.a.nord, t.b.nord, t.c.nord)
    val nordMax = maxOf(t.a.nord, t.b.nord, t.c.nord)

    fun touche(o: Boite) = estMin <= o.estMax && o.estMin <= estMax && nordMin <= o.nordMax && o.nordMin <= nordMax
}
