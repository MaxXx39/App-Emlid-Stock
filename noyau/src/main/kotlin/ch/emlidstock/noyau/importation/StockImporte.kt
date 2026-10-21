package ch.emlidstock.noyau.importation

import ch.emlidstock.noyau.leve.ModeLeve
import ch.emlidstock.noyau.leve.PointLeve
import ch.emlidstock.noyau.leve.Stock
import ch.emlidstock.noyau.volume.PointPlan
import ch.emlidstock.noyau.volume.pointDansPolygone

/** Stock créé, ou message d'erreur compréhensible. */
data class ResultatCreation(val stock: Stock?, val erreur: String?)

/**
 * Crée un stock à partir de points importés, avec les noms d'origine (décision du 21.10.2026) :
 * - [contour] : noms du bas de talus dans l'ordre (au moins 3) → rôle B, numérotés dans cet ordre ;
 * - [lignes] : lignes de haut de talus (noms dans l'ordre, fermée ?) → rôle H, lignes 1, 2… ;
 * - les autres points du fichier situés **dans le contour** deviennent des points caractéristiques (C),
 *   comme « tous les points du levé dans le contour » de la V1.
 * Les coordonnées viennent du fichier (MN95, RAN95 supposé : aucune conversion) ; la position GNSS
 * d'origine n'est pas connue (0), et la qualité est notée 0 (point importé, pas mesuré par l'appli).
 */
fun creerStockImporte(
    id: String,
    chantierId: String,
    nom: String,
    creeLeMs: Long,
    points: List<PointImporte>,
    contour: List<String>,
    lignes: List<Pair<List<String>, Boolean>>,
    nomFichier: String,
): ResultatCreation {
    val parNom = points.associateBy { it.nom } // en cas de nom en double, le dernier lu est gardé
    if (contour.size < 3) return ResultatCreation(null, "le contour doit avoir au moins 3 points")
    val utilises = mutableSetOf<String>()
    val doublons = (contour + lignes.flatMap { it.first }).groupBy { it }.filterValues { it.size > 1 }.keys
    if (doublons.isNotEmpty()) return ResultatCreation(null, "point(s) utilisé(s) deux fois : ${doublons.joinToString(", ")}")
    lignes.forEachIndexed { i, (noms, fermee) ->
        val minimum = if (fermee) 3 else 2
        if (noms.size < minimum) return ResultatCreation(null, "la ligne de haut de talus ${i + 1} doit avoir au moins $minimum points")
    }
    val absents = (contour + lignes.flatMap { it.first }).filter { it !in parNom }
    if (absents.isNotEmpty()) return ResultatCreation(null, "point(s) absent(s) du fichier : ${absents.joinToString(", ")}")

    fun pointLeve(p: PointImporte, mode: ModeLeve, ligne: Int?, numero: Int) = PointLeve(
        nom = p.nom, mode = mode, ligne = ligne, numero = numero, est = p.est, nord = p.nord, altitude = p.altitude,
        latitudeDeg = 0.0, longitudeDeg = 0.0, hauteurEllipsoidaleM = 0.0, horodatageMs = creeLeMs, qualiteGga = 0,
        nbSatellites = null, hdop = null, ageCorrectionsS = null, stationReference = "importé : $nomFichier", inclinaisonDeg = null,
    ).also { utilises.add(p.nom) }

    val resultat = mutableListOf<PointLeve>()
    contour.forEachIndexed { i, n -> resultat.add(pointLeve(parNom.getValue(n), ModeLeve.BAS_DE_TALUS, null, i + 1)) }
    lignes.forEachIndexed { l, (noms, _) -> noms.forEachIndexed { i, n -> resultat.add(pointLeve(parNom.getValue(n), ModeLeve.HAUT_DE_TALUS, l + 1, i + 1)) } }

    // Autres points du fichier dans le contour → points caractéristiques.
    val polygone = contour.map { parNom.getValue(it).let { p -> PointPlan(p.est, p.nord) } }
    var numeroC = 0
    for (p in points) {
        if (p.nom in utilises) continue
        if (pointDansPolygone(PointPlan(p.est, p.nord), polygone)) resultat.add(pointLeve(p, ModeLeve.POINT_CARACTERISTIQUE, null, ++numeroC))
    }
    val fermees = lignes.withIndex().filter { it.value.second }.map { it.index + 1 }.toSet()
    return ResultatCreation(Stock(id, chantierId, nom, creeLeMs, points = resultat, lignesFermees = fermees), null)
}
