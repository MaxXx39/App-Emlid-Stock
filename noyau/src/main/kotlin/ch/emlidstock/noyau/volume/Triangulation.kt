package ch.emlidstock.noyau.volume

import kotlin.math.max

/**
 * Résultat d'une triangulation : les triangles (sens inverse des aiguilles d'une montre),
 * ou une erreur compréhensible pour l'utilisateur.
 */
data class ResultatTriangulation(val triangles: List<Triangle>, val erreur: String? = null)

/**
 * Triangulation de Delaunay avec segments imposés (« triangulation contrainte ») :
 * les triangles ne traversent jamais les segments imposés (contour, lignes de rupture).
 * Même résultat que l'appli topo V1, qui utilise les bibliothèques Delaunator et Constrainautor ;
 * ici tout est écrit en Kotlin, sans bibliothèque :
 *   1. Delaunay classique (algorithme de Bowyer-Watson) ;
 *   2. chaque segment imposé est ajouté en « basculant » les arêtes qui le croisent ;
 *   3. on rebascule les arêtes non imposées qui ne respectent plus Delaunay (algorithme de Lawson).
 *
 * - [sommets] : points à trianguler (noms différents, positions en plan différentes)
 * - [segments] : paires de noms que les triangles doivent respecter
 */
fun trianglerAvecContraintes(sommets: List<Sommet>, segments: List<Pair<String, String>>): ResultatTriangulation {
    if (sommets.size < 3) return ResultatTriangulation(emptyList(), "il faut au moins 3 points pour faire une surface")

    // Deux points à la même position (en plan) empêchent la triangulation.
    val positions = HashMap<String, String>()
    for (s in sommets) {
        val cle = cleDePosition(s)
        positions[cle]?.let { return ResultatTriangulation(emptyList(), "les points $it et ${s.nom} sont à la même position en plan") }
        positions[cle] = s.nom
    }

    val parNom = sommets.associateBy { it.nom }
    val uniques = segmentsUniques(segments).filter { (a, b) -> a in parNom && b in parNom }
    chercherCroisement(uniques, parNom)?.let { return ResultatTriangulation(emptyList(), it) }

    val maillage = Maillage(sommets)
    val index = sommets.withIndex().associate { (i, s) -> s.nom to i }
    for ((a, b) in uniques) {
        val erreur = maillage.imposer(index.getValue(a), index.getValue(b))
        if (erreur != null) return ResultatTriangulation(emptyList(), erreur)
    }
    maillage.retablirDelaunay()
    return ResultatTriangulation(maillage.trianglesReels())
}

/** Segments [a, b] reliant les noms d'une ligne, avec le retour au début si elle est fermée. */
fun segmentsDeLigne(noms: List<String>, fermee: Boolean): List<Pair<String, String>> {
    val segments = mutableListOf<Pair<String, String>>()
    for (i in 0 until noms.size - 1) segments.add(Pair(noms[i], noms[i + 1]))
    if (fermee && noms.size >= 3) segments.add(Pair(noms.last(), noms.first()))
    return segments
}

/** Clé de position en plan, arrondie au 1/10 de mm. */
fun cleDePosition(s: Sommet): String = String.format(java.util.Locale.ROOT, "%.4f %.4f", s.est, s.nord)

/** Enlève les segments en double (même ligne saisie deux fois, ou contour = ligne) et les segments nuls. */
private fun segmentsUniques(segments: List<Pair<String, String>>): List<Pair<String, String>> {
    val uniques = LinkedHashMap<String, Pair<String, String>>()
    for ((a, b) in segments) {
        if (a == b) continue
        uniques[listOf(a, b).sorted().joinToString("\u0000")] = Pair(a, b)
    }
    return uniques.values.toList()
}

/** Cherche deux segments imposés qui se croisent (ou se superposent) ; renvoie le message, ou null. */
private fun chercherCroisement(segments: List<Pair<String, String>>, parNom: Map<String, Sommet>): String? {
    for (i in segments.indices) {
        for (j in i + 1 until segments.size) {
            val (a, b) = segments[i]
            val (c, d) = segments[j]
            val communs = listOf(a, b).count { it == c || it == d }
            val p1 = plan(parNom.getValue(a))
            val p2 = plan(parNom.getValue(b))
            val p3 = plan(parNom.getValue(c))
            val p4 = plan(parNom.getValue(d))
            if (communs == 0 && segmentsSeCroisent(p1, p2, p3, p4)) {
                return "les segments $a-$b et $c-$d se croisent : vérifiez le contour et les lignes de haut de talus"
            }
            if (communs == 1 && segmentsSeChevauchent(p1, p2, p3, p4)) {
                return "les segments $a-$b et $c-$d se superposent : vérifiez l'ordre des points"
            }
        }
    }
    return null
}

/**
 * Le maillage en cours de construction. Les sommets sont repérés par leur numéro ;
 * les 3 derniers numéros sont ceux d'un très grand triangle de départ (« super-triangle »),
 * retiré à la fin. Les calculs se font en coordonnées locales (autour du premier point)
 * pour garder la précision.
 */
private class Maillage(private val sommets: List<Sommet>) {
    private val n = sommets.size
    private val x = DoubleArray(n + 3)
    private val y = DoubleArray(n + 3)

    // Triangles (3 numéros de sommets, sens inverse des aiguilles d'une montre) ; null = retiré.
    private val triangles = ArrayList<IntArray?>()

    // Arête orientée a → b : numéro du triangle qui la contient dans ce sens.
    private val aretes = HashMap<Long, Int>()

    // Arêtes imposées (non orientées).
    private val imposees = HashSet<Long>()

    // Seuil du test « dans le cercle » : proportionnel à la taille de la zone (puissance 4),
    // pour ne pas basculer sans fin des points presque sur un même cercle (grilles régulières).
    private var seuilCercle = 0.0

    init {
        val origine = sommets[0]
        for (i in 0 until n) {
            x[i] = sommets[i].est - origine.est
            y[i] = sommets[i].nord - origine.nord
        }
        // Super-triangle qui contient largement tous les points.
        val xMin = x.take(n).min()
        val xMax = x.take(n).max()
        val yMin = y.take(n).min()
        val yMax = y.take(n).max()
        val taille = max(max(xMax - xMin, yMax - yMin), 1.0)
        seuilCercle = 1e-10 * taille * taille * taille * taille
        val cx = (xMin + xMax) / 2
        val cy = (yMin + yMax) / 2
        x[n] = cx - 50 * taille; y[n] = cy - 30 * taille
        x[n + 1] = cx + 50 * taille; y[n + 1] = cy - 30 * taille
        x[n + 2] = cx; y[n + 2] = cy + 50 * taille
        ajouter(n, n + 1, n + 2)
        for (p in 0 until n) inserer(p)
    }

    private fun cle(a: Int, b: Int): Long = a.toLong() * 1_000_000L + b
    private fun cleNonOrientee(a: Int, b: Int): Long = if (a < b) cle(a, b) else cle(b, a)

    private fun ajouter(a: Int, b: Int, c: Int): Int {
        val t = triangles.size
        triangles.add(intArrayOf(a, b, c))
        aretes[cle(a, b)] = t
        aretes[cle(b, c)] = t
        aretes[cle(c, a)] = t
        return t
    }

    private fun retirer(t: Int) {
        val tri = triangles[t] ?: return
        for (i in 0 until 3) aretes.remove(cle(tri[i], tri[(i + 1) % 3]))
        triangles[t] = null
    }

    /** Sommet du triangle t qui n'est ni a ni b. */
    private fun troisieme(t: Int, a: Int, b: Int): Int = triangles[t]!!.first { it != a && it != b }

    private fun orient(a: Int, b: Int, c: Int): Double =
        (x[b] - x[a]) * (y[c] - y[a]) - (y[b] - y[a]) * (x[c] - x[a])

    /** > 0 si d est dans le cercle qui passe par a, b, c (triangle dans le sens inverse des aiguilles). */
    private fun dansLeCercle(a: Int, b: Int, c: Int, d: Int): Double {
        val adx = x[a] - x[d]; val ady = y[a] - y[d]
        val bdx = x[b] - x[d]; val bdy = y[b] - y[d]
        val cdx = x[c] - x[d]; val cdy = y[c] - y[d]
        return (adx * adx + ady * ady) * (bdx * cdy - cdx * bdy) -
            (bdx * bdx + bdy * bdy) * (adx * cdy - cdx * ady) +
            (cdx * cdx + cdy * cdy) * (adx * bdy - bdx * ady)
    }

    /** Bowyer-Watson : ajoute le point p en refaisant les triangles dont le cercle le contient. */
    private fun inserer(p: Int) {
        val mauvais = triangles.indices.filter { t ->
            val tri = triangles[t]
            tri != null && dansLeCercle(tri[0], tri[1], tri[2], p) > 0
        }
        val bord = mutableListOf<Pair<Int, Int>>()
        val ensembleMauvais = mauvais.toHashSet()
        for (t in mauvais) {
            val tri = triangles[t]!!
            for (i in 0 until 3) {
                val a = tri[i]
                val b = tri[(i + 1) % 3]
                val voisin = aretes[cle(b, a)]
                if (voisin == null || voisin !in ensembleMauvais) bord.add(Pair(a, b))
            }
        }
        for (t in mauvais) retirer(t)
        for ((a, b) in bord) ajouter(a, b, p)
    }

    /** Bascule l'arête u-v : les triangles (u, v, p) et (v, u, q) deviennent (u, q, p) et (q, v, p). */
    private fun basculer(u: Int, v: Int): Pair<Int, Int> {
        val t1 = aretes.getValue(cle(u, v))
        val t2 = aretes.getValue(cle(v, u))
        val p = troisieme(t1, u, v)
        val q = troisieme(t2, v, u)
        retirer(t1)
        retirer(t2)
        ajouter(u, q, p)
        ajouter(q, v, p)
        return Pair(p, q)
    }

    /** Vrai si le quadrilatère autour de l'arête u-v est strictement convexe (bascule possible). */
    private fun basculable(u: Int, v: Int): Boolean {
        val t1 = aretes[cle(u, v)] ?: return false
        val t2 = aretes[cle(v, u)] ?: return false
        val p = troisieme(t1, u, v)
        val q = troisieme(t2, v, u)
        return orient(p, q, u) * orient(p, q, v) < 0
    }

    /** Vrai si les segments a-b et u-v se croisent franchement (pas seulement à une extrémité). */
    private fun croisement(a: Int, b: Int, u: Int, v: Int): Boolean =
        orient(a, b, u) * orient(a, b, v) < 0 && orient(u, v, a) * orient(u, v, b) < 0

    /** Ajoute le segment imposé a-b au maillage. Renvoie un message d'erreur, ou null. */
    fun imposer(a: Int, b: Int): String? {
        if (aretes.containsKey(cle(a, b)) || aretes.containsKey(cle(b, a))) {
            imposees.add(cleNonOrientee(a, b))
            return null
        }
        // Un point exactement sur le segment : on impose les deux morceaux.
        for (p in 0 until n) {
            if (p == a || p == b) continue
            val longueur2 = (x[b] - x[a]) * (x[b] - x[a]) + (y[b] - y[a]) * (y[b] - y[a])
            val produit = (x[p] - x[a]) * (x[b] - x[a]) + (y[p] - y[a]) * (y[b] - y[a])
            val aligne = kotlin.math.abs(orient(a, b, p)) <= 1e-9 * longueur2
            if (aligne && produit > 0 && produit < longueur2) return imposer(a, p) ?: imposer(p, b)
        }

        // Arêtes du maillage qui croisent le segment.
        val aCroiser = ArrayDeque<Pair<Int, Int>>()
        for (tri in triangles) {
            if (tri == null) continue
            for (i in 0 until 3) {
                val u = tri[i]
                val v = tri[(i + 1) % 3]
                if (u < v && croisement(a, b, u, v)) aCroiser.addLast(Pair(u, v))
                if (u > v && !aretes.containsKey(cle(v, u)) && croisement(a, b, u, v)) aCroiser.addLast(Pair(v, u))
            }
        }
        if (aCroiser.any { (u, v) -> cleNonOrientee(u, v) in imposees }) {
            return "triangulation impossible : le segment ${sommets[a].nom}-${sommets[b].nom} croise une autre ligne"
        }

        var garde = 0
        while (aCroiser.isNotEmpty()) {
            if (++garde > 200_000) return "triangulation impossible avec les lignes imposées (${sommets[a].nom}-${sommets[b].nom})"
            val (u, v) = aCroiser.removeFirst()
            if (!aretes.containsKey(cle(u, v))) continue
            if (!basculable(u, v)) {
                aCroiser.addLast(Pair(u, v))
                continue
            }
            val (p, q) = basculer(u, v)
            if (croisement(a, b, p, q)) aCroiser.addLast(Pair(p, q))
        }
        imposees.add(cleNonOrientee(a, b))
        return null
    }

    /** Algorithme de Lawson : bascule les arêtes non imposées qui ne respectent pas Delaunay. */
    fun retablirDelaunay() {
        var passes = 0
        var change = true
        while (change && passes++ < 1000) {
            change = false
            for (t in triangles.indices) {
                val tri = triangles[t] ?: continue
                for (i in 0 until 3) {
                    val u = tri[i]
                    val v = tri[(i + 1) % 3]
                    if (cleNonOrientee(u, v) in imposees) continue
                    val voisin = aretes[cle(v, u)] ?: continue
                    val p = tri[(i + 2) % 3]
                    val q = troisieme(voisin, v, u)
                    if (dansLeCercle(u, v, p, q) > seuilCercle && basculable(u, v)) {
                        basculer(u, v)
                        change = true
                        break // ce triangle n'existe plus : on passe au suivant
                    }
                }
            }
        }
    }

    /** Triangles finaux, sans ceux qui touchent le super-triangle. */
    fun trianglesReels(): List<Triangle> = triangles.filterNotNull()
        .filter { tri -> tri.all { it < n } }
        .map { tri -> Triangle(sommets[tri[0]], sommets[tri[1]], sommets[tri[2]]) }
}
