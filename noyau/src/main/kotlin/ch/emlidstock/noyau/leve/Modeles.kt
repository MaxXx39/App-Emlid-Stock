package ch.emlidstock.noyau.leve

/**
 * Type de levé :
 * - STOCK : un tas, avec son volume (contour B, lignes de haut de talus H, points C) ;
 * - SURFACE : une surface levée (ex. terrain naturel avant le stock) qui peut servir de base
 *   de volume à un stock (mode B) : contour B facultatif, lignes de rupture H, points C (étape 9).
 */
enum class TypeLeve { STOCK, SURFACE }

/** Les trois modes de levé d'un stock, avec la lettre des noms de points. */
enum class ModeLeve(val libelle: String, val lettre: String) {
    BAS_DE_TALUS("Bas de talus", "B"),
    HAUT_DE_TALUS("Haut de talus", "H"),
    POINT_CARACTERISTIQUE("Point caractéristique", "C"),
}

/** Nom d'un mode à afficher : pour une surface levée, B = contour (facultatif), H = ligne de rupture, C = point. */
fun libelleMode(type: TypeLeve, mode: ModeLeve): String =
    if (type == TypeLeve.STOCK) {
        mode.libelle
    } else {
        when (mode) {
            ModeLeve.BAS_DE_TALUS -> "Contour (facultatif)"
            ModeLeve.HAUT_DE_TALUS -> "Ligne de rupture"
            ModeLeve.POINT_CARACTERISTIQUE -> "Point"
        }
    }

/**
 * Un point levé. Coordonnées en MN95 (m) et altitude RAN95 / LHN95 (m) ;
 * on garde aussi la position GNSS d'origine (degrés, hauteur ellipsoïdale en m)
 * et la qualité au moment de la mesure.
 *
 * - [ligne] : numéro de la ligne de haut de talus (H2-5 → 2), null pour B et C.
 * - [numero] : numéro du point dans son groupe (H2-5 → 5, B7 → 7).
 */
data class PointLeve(
    val nom: String,
    val mode: ModeLeve,
    val ligne: Int?,
    val numero: Int,
    val est: Double,
    val nord: Double,
    val altitude: Double,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val hauteurEllipsoidaleM: Double,
    val horodatageMs: Long,
    val qualiteGga: Int,
    val nbSatellites: Int?,
    val hdop: Double?,
    val ageCorrectionsS: Double?,
    val stationReference: String,
    val inclinaisonDeg: Double?,
    /** Vrai si E, N ou H ont été tapés à la main après la mesure. */
    val modifieALaMain: Boolean = false,
)

/**
 * Un chantier : il contient plusieurs stocks.
 * [affaire] et [dessinePar] remplissent le cartouche des PDF (comme dans l'appli topo V1).
 */
data class Chantier(
    val id: String,
    var nom: String,
    val creeLeMs: Long,
    var affaire: String = "",
    var dessinePar: String = "",
)
