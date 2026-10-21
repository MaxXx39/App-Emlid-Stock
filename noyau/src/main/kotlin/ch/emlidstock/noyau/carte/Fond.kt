package ch.emlidstock.noyau.carte

import kotlin.math.floor
import kotlin.math.min

/*
 * Fonds de carte swisstopo (service WMTS de geo.admin.ch), directement en MN95 (EPSG:2056) :
 * aucune conversion de coordonnées. Repris de l'appli topo (V1, src/plan/fond.js).
 *
 * Le fond est découpé en « tuiles » carrées de 256 × 256 pixels. Pour chaque niveau de zoom,
 * une tuile couvre 256 × résolution mètres. La grille part du coin en haut à gauche
 * (Est 2 420 000, Nord 1 350 000) ; les colonnes vont vers l'Est, les lignes vers le Sud.
 */

/** Résolutions (m par pixel) de chaque niveau de zoom, du niveau 0 au niveau 28. */
val RESOLUTIONS = doubleArrayOf(
    4000.0, 3750.0, 3500.0, 3250.0, 3000.0, 2750.0, 2500.0, 2250.0, 2000.0, 1750.0, 1500.0, 1250.0, 1000.0,
    750.0, 650.0, 500.0, 250.0, 100.0, 50.0, 20.0, 10.0, 5.0, 2.5, 2.0, 1.5, 1.0, 0.5, 0.25, 0.1,
)
private const val ORIGINE_EST = 2_420_000.0
private const val ORIGINE_NORD = 1_350_000.0
const val TAILLE_TUILE = 256 // pixels

/** Fonds disponibles : photo aérienne et plan cadastral (transparent, posé par-dessus). */
enum class Fond(val attribution: String) {
    PHOTO("© swisstopo"),
    CADASTRE("Cadastre © cantons");

    /** Adresse internet de la tuile. */
    fun adresse(tuile: Tuile): String = when (this) {
        PHOTO -> "https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.swissimage/default/current/2056/" +
            "${tuile.niveau}/${tuile.colonne}/${tuile.ligne}.jpeg"
        CADASTRE -> "https://wmts.geo.admin.ch/1.0.0/ch.kantone.cadastralwebmap-farbe/default/current/2056/" +
            "${tuile.niveau}/${tuile.colonne}/${tuile.ligne}.png"
    }
}

/** Une tuile : niveau, colonne, ligne ; (est, nord) = coin en haut à gauche, [cote] = largeur (m). */
data class Tuile(val niveau: Int, val colonne: Int, val ligne: Int, val est: Double, val nord: Double, val cote: Double)

/**
 * Choisit le niveau de zoom des tuiles : le moins détaillé qui reste net à l'écran.
 * - [metresParPixel] : taille d'un pixel de l'écran sur le terrain (m)
 * - [densite] : pixels réels par pixel affiché (écrans haute définition : 2 ou 3)
 */
fun niveauDeZoom(metresParPixel: Double, densite: Double = 1.0): Int {
    val cible = metresParPixel / min(densite, 2.0)
    val niveau = RESOLUTIONS.indexOfFirst { it <= cible }
    return if (niveau == -1) RESOLUTIONS.size - 1 else niveau
}

/** Tuiles nécessaires pour couvrir une zone du terrain (m), au niveau [niveau]. */
fun tuilesPourZone(estMin: Double, estMax: Double, nordMin: Double, nordMax: Double, niveau: Int): List<Tuile> {
    val cote = TAILLE_TUILE * RESOLUTIONS[niveau]
    val colonneMin = floor((estMin - ORIGINE_EST) / cote).toInt()
    val colonneMax = floor((estMax - ORIGINE_EST) / cote).toInt()
    val ligneMin = floor((ORIGINE_NORD - nordMax) / cote).toInt()
    val ligneMax = floor((ORIGINE_NORD - nordMin) / cote).toInt()
    val tuiles = mutableListOf<Tuile>()
    for (ligne in ligneMin..ligneMax) {
        for (colonne in colonneMin..colonneMax) {
            tuiles.add(Tuile(niveau, colonne, ligne, ORIGINE_EST + colonne * cote, ORIGINE_NORD - ligne * cote, cote))
        }
    }
    return tuiles
}
