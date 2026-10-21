package ch.emlidstock.noyau.volume

import ch.emlidstock.noyau.leve.ModeLeve
import ch.emlidstock.noyau.leve.PointLeve

/**
 * Résultat du volume d'un stock (m², m³), avec les valeurs intermédiaires à afficher.
 * Si le calcul est impossible, [erreur] dit pourquoi et les autres valeurs sont à zéro.
 */
data class ResultatVolumeStock(
    val surfacePlan: Double = 0.0,
    val surfaceContour: Double = 0.0,
    val remblai: Double = 0.0,
    val deblai: Double = 0.0,
    val surfaceRemblai: Double = 0.0,
    val surfaceDeblai: Double = 0.0,
    val surfaceNonCouverte: Double = 0.0,
    val trianglesDessus: List<Triangle> = emptyList(),
    val trianglesBase: List<Triangle> = emptyList(),
    val avertissements: List<String> = emptyList(),
    val erreur: String? = null,
)

/**
 * Volume d'un stock, méthode de l'appli topo V1 en « mode A » (base mesurée), adaptée aux talus
 * (cahier des charges) :
 * - contour et surface de référence = les points de bas de talus B1, B2… dans l'ordre (polygone fermé) ;
 * - dessus = triangulation de tous les points (B, H, C) qui respecte le contour et chaque ligne de
 *   haut de talus (lignes de rupture), en ne gardant que les triangles dans le contour ;
 * - base = triangulation des seuls points du bas de talus, qui respecte le contour.
 *
 * [lignesFermees] : numéros des lignes de haut de talus à fermer (dernier point relié au premier).
 */
fun calculerVolumeStock(points: List<PointLeve>, lignesFermees: Set<Int> = emptySet()): ResultatVolumeStock {
    val bas = points.filter { it.mode == ModeLeve.BAS_DE_TALUS }.sortedBy { it.numero }
    if (bas.size < 3) return ResultatVolumeStock(erreur = "il faut au moins 3 points de bas de talus (le contour)")
    val lignes = points.filter { it.mode == ModeLeve.HAUT_DE_TALUS }
        .groupBy { it.ligne ?: 1 }
        .toSortedMap()
        .mapValues { (_, liste) -> liste.sortedBy { it.numero } }

    // Deux points à la même position en plan : on garde le premier (B, puis H, puis C) et on prévient.
    val avertissements = mutableListOf<String>()
    val gardes = LinkedHashMap<String, Sommet>()
    val alias = HashMap<String, String>() // point ignoré → point gardé à la même position
    val ordre = bas + lignes.values.flatten() + points.filter { it.mode == ModeLeve.POINT_CARACTERISTIQUE }
    for (p in ordre) {
        val sommet = Sommet(p.nom, p.est, p.nord, p.altitude)
        val cle = cleDePosition(sommet)
        val existant = gardes[cle]
        if (existant != null) {
            avertissements.add("point ${p.nom} ignoré : même position que ${existant.nom}")
            alias[p.nom] = existant.nom
        } else {
            gardes[cle] = sommet
        }
    }
    fun vrai(nom: String) = alias[nom] ?: nom

    val nomsContour = bas.map { vrai(it.nom) }.distinct()
    if (nomsContour.size < 3) return ResultatVolumeStock(erreur = "le contour a moins de 3 points à des positions différentes")
    val segmentsContour = segmentsDeLigne(nomsContour, fermee = true)
    val segmentsLignes = lignes.flatMap { (numero, liste) ->
        segmentsDeLigne(liste.map { vrai(it.nom) }.distinct(), fermee = numero in lignesFermees)
    }

    // Dessus : tous les points, contour et lignes imposés, triangles dans le contour seulement.
    val tousLesSommets = gardes.values.toList()
    val dessus = trianglerAvecContraintes(tousLesSommets, segmentsContour + segmentsLignes)
    dessus.erreur?.let { return ResultatVolumeStock(erreur = it, avertissements = avertissements) }
    val parNom = tousLesSommets.associateBy { it.nom }
    val polygone = nomsContour.map { plan(parNom.getValue(it)) }
    val trianglesDessus = dessus.triangles.filter { pointDansPolygone(centre(it), polygone) }
    if (trianglesDessus.isEmpty()) return ResultatVolumeStock(erreur = "aucun triangle dans le contour", avertissements = avertissements)

    // Base : les points du contour seuls, contour imposé.
    val base = trianglerAvecContraintes(nomsContour.map { parNom.getValue(it) }, segmentsContour)
    base.erreur?.let { return ResultatVolumeStock(erreur = "base : $it", avertissements = avertissements) }

    val volume = volumeEntreSurfaces(trianglesDessus, base.triangles)
    return ResultatVolumeStock(
        surfacePlan = volume.surfacePlan,
        surfaceContour = kotlin.math.abs(airePolygone(polygone)),
        remblai = volume.remblai,
        deblai = volume.deblai,
        surfaceRemblai = volume.surfaceRemblai,
        surfaceDeblai = volume.surfaceDeblai,
        surfaceNonCouverte = volume.surfaceNonCouverte,
        trianglesDessus = trianglesDessus,
        trianglesBase = base.triangles,
        avertissements = avertissements,
    )
}
