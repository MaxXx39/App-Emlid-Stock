package ch.emlidstock.noyau.carte

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Cadrage de la carte : point du terrain au centre de l'écran ([centreEst], [centreNord], en m)
 * et [echelle] en pixels par mètre. L'axe Nord est vers le haut de l'écran.
 */
data class Cadrage(val centreEst: Double, val centreNord: Double, val echelle: Double) {

    /** Position à l'écran (pixels) d'un point du terrain, pour un écran de [largeur] × [hauteur]. */
    fun versEcran(est: Double, nord: Double, largeur: Int, hauteur: Int): Pair<Double, Double> =
        Pair(largeur / 2.0 + (est - centreEst) * echelle, hauteur / 2.0 - (nord - centreNord) * echelle)

    /** Point du terrain (Est, Nord en m) sous la position [x], [y] de l'écran (pixels). */
    fun versTerrain(x: Double, y: Double, largeur: Int, hauteur: Int): Pair<Double, Double> =
        Pair(centreEst + (x - largeur / 2.0) / echelle, centreNord - (y - hauteur / 2.0) / echelle)

    /** Déplace la carte de [dx], [dy] pixels (le terrain suit le doigt). */
    fun deplacer(dx: Double, dy: Double) = copy(centreEst = centreEst - dx / echelle, centreNord = centreNord + dy / echelle)

    /**
     * Zoom de [facteur] autour du point de l'écran ([x], [y]) : ce point du terrain reste sous le doigt.
     * L'échelle reste entre [ECHELLE_MIN] et [ECHELLE_MAX].
     */
    fun zoomer(facteur: Double, x: Double, y: Double, largeur: Int, hauteur: Int): Cadrage {
        val nouvelleEchelle = min(max(echelle * facteur, ECHELLE_MIN), ECHELLE_MAX)
        val (est, nord) = versTerrain(x, y, largeur, hauteur)
        return Cadrage(
            centreEst = est - (x - largeur / 2.0) / nouvelleEchelle,
            centreNord = nord + (y - hauteur / 2.0) / nouvelleEchelle,
            echelle = nouvelleEchelle,
        )
    }

    companion object {
        /** 1 pixel = 50 m (vue de toute une région). */
        const val ECHELLE_MIN = 0.02
        /** 1 pixel = 5 mm. */
        const val ECHELLE_MAX = 200.0

        /**
         * Cadrage qui montre tous les points (Est, Nord en m) avec une marge de [margePixels].
         * Un seul point (ou des points confondus) : vue de 40 m de large.
         */
        fun surPoints(points: List<Pair<Double, Double>>, largeur: Int, hauteur: Int, margePixels: Int = 40): Cadrage? {
            if (points.isEmpty()) return null
            val estMin = points.minOf { it.first }
            val estMax = points.maxOf { it.first }
            val nordMin = points.minOf { it.second }
            val nordMax = points.maxOf { it.second }
            val etendue = max(max(estMax - estMin, nordMax - nordMin), 1.0)
            val largeurUtile = max(largeur - 2 * margePixels, 1)
            val hauteurUtile = max(hauteur - 2 * margePixels, 1)
            val echelle = if (estMax - estMin < 1.0 && nordMax - nordMin < 1.0) {
                largeur / 40.0
            } else {
                min(largeurUtile / max(estMax - estMin, etendue / 1000), hauteurUtile / max(nordMax - nordMin, etendue / 1000))
            }
            return Cadrage((estMin + estMax) / 2, (nordMin + nordMax) / 2, min(max(echelle, ECHELLE_MIN), ECHELLE_MAX))
        }
    }
}

/**
 * Barre d'échelle : une longueur ronde (1, 2 ou 5 × 10ⁿ m) qui tient dans [pixelsMax].
 * Renvoie (mètres, pixels). Reprise de l'appli topo (V1).
 */
fun barreEchelle(echelle: Double, pixelsMax: Double = 120.0): Pair<Double, Double> {
    val metresMax = pixelsMax / echelle
    val puissance = 10.0.pow(floor(log10(metresMax)))
    var metres = puissance
    for (multiple in listOf(1.0, 2.0, 5.0)) {
        if (multiple * puissance <= metresMax) metres = multiple * puissance
    }
    return Pair(metres, metres * echelle)
}
