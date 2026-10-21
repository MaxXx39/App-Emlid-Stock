package ch.emlidstock.noyau.volume

/*
 * Calcul du volume entre une surface du dessus et une surface de base, toutes deux en triangles.
 * Méthode de l'appli topo V1 (src/topo/volume.js), validée avec l'utilisateur sur le levé du
 * 20/05/2026 (écart avec Mensura < 0,001 m³) :
 *   on superpose les deux triangulations ; chaque triangle du dessus est découpé par les
 *   triangles de la base ; sur chaque morceau, l'écart de hauteur (dessus − base) varie de façon
 *   linéaire, donc le volume se calcule exactement.
 *   Remblai = là où le dessus est au-dessus de la base, déblai = là où il est au-dessous.
 * Unités : m, m², m³.
 */

/** Résultat brut du volume (m², m³). */
data class ResultatVolume(
    var surfacePlan: Double = 0.0,
    var remblai: Double = 0.0,
    var deblai: Double = 0.0,
    var surfaceRemblai: Double = 0.0,
    var surfaceDeblai: Double = 0.0,
    var surfaceNonCouverte: Double = 0.0,
)

/** Volume entre les triangles du dessus et ceux de la base, sur l'emprise des triangles du dessus. */
fun volumeEntreSurfaces(trianglesDessus: List<Triangle>, trianglesBase: List<Triangle>): ResultatVolume {
    val resultat = ResultatVolume()
    val boitesBase = trianglesBase.map { Boite(it) }

    for (dessus in trianglesDessus) {
        val aireDessus = kotlin.math.abs(aireTriangle(dessus))
        resultat.surfacePlan += aireDessus
        val boiteDessus = Boite(dessus)
        var aireCouverte = 0.0

        for ((index, base) in trianglesBase.withIndex()) {
            if (!boiteDessus.touche(boitesBase[index])) continue
            // Morceau commun aux deux triangles (vu en plan).
            val morceau = intersectionTriangles(dessus, base)
            if (morceau.size < 3) continue
            aireCouverte += kotlin.math.abs(airePolygone(morceau))

            // Écart de hauteur dessus − base à chaque sommet du morceau (m).
            val ecarts = morceau.map { altitudeSurTriangle(dessus, it.est, it.nord) - altitudeSurTriangle(base, it.est, it.nord) }
            // Le morceau est convexe : on le découpe en éventail de triangles.
            for (i in 1 until morceau.size - 1) {
                val aire = kotlin.math.abs(aireTriangle(morceau[0], morceau[i], morceau[i + 1]))
                ajouterPrisme(resultat, aire, ecarts[0], ecarts[i], ecarts[i + 1])
            }
        }
        resultat.surfaceNonCouverte += maxOf(aireDessus - aireCouverte, 0.0)
    }
    // On ignore les restes d'arrondi de calcul (moins de 1 cm²).
    if (resultat.surfaceNonCouverte < 1e-4) resultat.surfaceNonCouverte = 0.0
    return resultat
}

/**
 * Ajoute le volume d'un prisme à base triangulaire (aire en m²) dont les hauteurs aux 3 coins
 * sont d1, d2, d3 (m, positives = remblai, négatives = déblai). Si la hauteur change de signe
 * dans le triangle, on le coupe exactement le long de la ligne où elle vaut zéro.
 */
fun ajouterPrisme(resultat: ResultatVolume, aire: Double, d1: Double, d2: Double, d3: Double) {
    val hauteurs = listOf(d1, d2, d3)
    val total = aire * (d1 + d2 + d3) / 3
    val positifs = hauteurs.count { it > 0 }
    val negatifs = hauteurs.count { it < 0 }

    if (negatifs == 0) {
        resultat.remblai += total
        if (positifs > 0) resultat.surfaceRemblai += aire
        return
    }
    if (positifs == 0) {
        resultat.deblai += -total
        resultat.surfaceDeblai += aire
        return
    }

    // Un coin est seul de son signe : on calcule la petite pointe autour de lui.
    val seulPositif = positifs == 1
    val seul = if (seulPositif) hauteurs.first { it > 0 } else hauteurs.first { it < 0 }
    val autres = if (seulPositif) hauteurs.filter { it <= 0 } else hauteurs.filter { it >= 0 }
    val partAire = seul * seul / ((seul - autres[0]) * (seul - autres[1]))
    val volumePointe = aire * partAire * seul / 3
    val volumeReste = total - volumePointe

    if (seulPositif) {
        resultat.remblai += volumePointe
        resultat.deblai += -volumeReste
        resultat.surfaceRemblai += aire * partAire
        resultat.surfaceDeblai += aire * (1 - partAire)
    } else {
        resultat.deblai += -volumePointe
        resultat.remblai += volumeReste
        resultat.surfaceDeblai += aire * partAire
        resultat.surfaceRemblai += aire * (1 - partAire)
    }
}
