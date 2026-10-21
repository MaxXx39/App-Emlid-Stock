package ch.emlidstock.noyau.export

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/* Choix de l'échelle du plan imprimé, repris de l'appli topo V1 (src/export/echelle.js). */

/** Échelles normalisées proposées (1:100, 1:200…). */
val ECHELLES = listOf(100, 200, 250, 500, 1000, 2000, 2500, 5000, 10000, 25000, 50000)

/**
 * Plus grande échelle (le plus de détails) pour laquelle une zone du terrain tient sur le papier.
 * Terrain en m, papier en mm. Renvoie le dénominateur (500 pour 1:500).
 * À l'échelle 1:E, 1 m sur le terrain mesure 1000 / E mm sur le papier.
 */
fun choisirEchelle(largeurTerrain: Double, hauteurTerrain: Double, largeurPapier: Double, hauteurPapier: Double): Int =
    ECHELLES.firstOrNull { largeurTerrain * 1000 / it <= largeurPapier && hauteurTerrain * 1000 / it <= hauteurPapier } ?: ECHELLES.last()

/**
 * Longueur « ronde » (1, 2 ou 5 × 10ⁿ m) pour la barre d'échelle du plan imprimé, la plus grande
 * qui ne dépasse pas [longueurMaxMm] sur le papier. Renvoie (mètres, millimètres).
 */
fun barreEchellePapier(echelle: Int, longueurMaxMm: Double = 50.0): Pair<Double, Double> {
    val metresMax = longueurMaxMm * echelle / 1000
    val puissance = 10.0.pow(floor(log10(metresMax)))
    var metres = puissance
    for (multiple in listOf(1.0, 2.0, 5.0)) if (multiple * puissance <= metresMax) metres = multiple * puissance
    return Pair(metres, metres * 1000 / echelle)
}
