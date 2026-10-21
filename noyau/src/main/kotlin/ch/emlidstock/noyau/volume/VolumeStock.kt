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
    /** Triangles d'écart dessus − base (voir [ResultatVolume.trianglesEcart]). */
    val trianglesEcart: List<Triangle> = emptyList(),
    /** Nom de la surface de base en mode B ; null en mode A (base = points du bas de talus). */
    val nomBase: String? = null,
    val avertissements: List<String> = emptyList(),
    val erreur: String? = null,
) {
    /** « A » : base = points du bas de talus ; « B » : base = une autre surface (comme la V1). */
    val mode: String get() = if (nomBase == null) "A" else "B"
}

/**
 * Base du volume en mode B : une autre surface (levée ou importée), avec son nom et ses triangles.
 */
data class BaseDeVolume(val nom: String, val triangles: List<Triangle>)

/**
 * Points d'un levé prêts à trianguler : un seul sommet par position en plan, contour (B dans l'ordre)
 * et lignes H (noms dans l'ordre, fermée ?), avec les avertissements pour les points en double.
 */
internal class PointsPrepares(
    val sommets: List<Sommet>,
    val contour: List<String>,
    val lignes: List<Pair<List<String>, Boolean>>,
    val avertissements: List<String>,
)

/**
 * Prépare les points d'un levé. Deux points à la même position en plan : on garde le premier
 * (B, puis H, puis C) et on prévient ; le point ignoré est remplacé par le point gardé dans le
 * contour et les lignes.
 */
internal fun preparerPoints(points: List<PointLeve>, lignesFermees: Set<Int>): PointsPrepares {
    val bas = points.filter { it.mode == ModeLeve.BAS_DE_TALUS }.sortedBy { it.numero }
    val lignes = points.filter { it.mode == ModeLeve.HAUT_DE_TALUS }
        .groupBy { it.ligne ?: 1 }
        .toSortedMap()
        .mapValues { (_, liste) -> liste.sortedBy { it.numero } }

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
    return PointsPrepares(
        sommets = gardes.values.toList(),
        contour = bas.map { vrai(it.nom) }.distinct(),
        lignes = lignes.map { (numero, liste) -> Pair(liste.map { vrai(it.nom) }.distinct(), numero in lignesFermees) },
        avertissements = avertissements,
    )
}

/**
 * Volume d'un stock, méthode de l'appli topo V1, adaptée aux talus (cahier des charges) :
 * - contour = les points de bas de talus B1, B2… dans l'ordre (polygone fermé) ;
 * - dessus = triangulation de tous les points (B, H, C) qui respecte le contour et chaque ligne de
 *   haut de talus (lignes de rupture), en ne gardant que les triangles dans le contour ;
 * - base, mode A (par défaut) : triangulation des seuls points du bas de talus, qui respecte le contour ;
 * - base, mode B ([base] donnée) : les triangles d'une autre surface (levée ou importée). Si elle ne
 *   couvre pas tout le contour, le volume est calculé sur la partie couverte et la surface non
 *   couverte est indiquée (comme la V1).
 *
 * [lignesFermees] : numéros des lignes de haut de talus à fermer (dernier point relié au premier).
 */
fun calculerVolumeStock(
    points: List<PointLeve>,
    lignesFermees: Set<Int> = emptySet(),
    base: BaseDeVolume? = null,
): ResultatVolumeStock {
    if (points.count { it.mode == ModeLeve.BAS_DE_TALUS } < 3) {
        return ResultatVolumeStock(erreur = "il faut au moins 3 points de bas de talus (le contour)")
    }
    val prepares = preparerPoints(points, lignesFermees)
    val avertissements = prepares.avertissements
    val nomsContour = prepares.contour
    if (nomsContour.size < 3) return ResultatVolumeStock(erreur = "le contour a moins de 3 points à des positions différentes")
    val segmentsContour = segmentsDeLigne(nomsContour, fermee = true)
    val segmentsLignes = prepares.lignes.flatMap { (noms, fermee) -> segmentsDeLigne(noms, fermee) }

    // Dessus : tous les points, contour et lignes imposés, triangles dans le contour seulement.
    val tousLesSommets = prepares.sommets
    val dessus = trianglerAvecContraintes(tousLesSommets, segmentsContour + segmentsLignes)
    dessus.erreur?.let { return ResultatVolumeStock(erreur = it, avertissements = avertissements) }
    val parNom = tousLesSommets.associateBy { it.nom }
    val polygone = nomsContour.map { plan(parNom.getValue(it)) }
    val trianglesDessus = dessus.triangles.filter { pointDansPolygone(centre(it), polygone) }
    if (trianglesDessus.isEmpty()) return ResultatVolumeStock(erreur = "aucun triangle dans le contour", avertissements = avertissements)

    val trianglesBase = if (base == null) {
        // Mode A : les points du contour seuls, contour imposé.
        val triangulation = trianglerAvecContraintes(nomsContour.map { parNom.getValue(it) }, segmentsContour)
        triangulation.erreur?.let { return ResultatVolumeStock(erreur = "base : $it", avertissements = avertissements) }
        triangulation.triangles
    } else {
        // Mode B : les triangles d'une autre surface.
        if (base.triangles.isEmpty()) {
            return ResultatVolumeStock(erreur = "la surface de base « ${base.nom} » n'a aucun triangle", avertissements = avertissements)
        }
        base.triangles
    }

    val volume = volumeEntreSurfaces(trianglesDessus, trianglesBase)
    return ResultatVolumeStock(
        surfacePlan = volume.surfacePlan,
        surfaceContour = kotlin.math.abs(airePolygone(polygone)),
        remblai = volume.remblai,
        deblai = volume.deblai,
        surfaceRemblai = volume.surfaceRemblai,
        surfaceDeblai = volume.surfaceDeblai,
        surfaceNonCouverte = volume.surfaceNonCouverte,
        trianglesDessus = trianglesDessus,
        trianglesBase = trianglesBase,
        trianglesEcart = volume.trianglesEcart,
        nomBase = base?.nom,
        avertissements = avertissements,
    )
}
