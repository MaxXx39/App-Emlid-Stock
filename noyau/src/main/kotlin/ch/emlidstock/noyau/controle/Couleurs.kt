package ch.emlidstock.noyau.controle

import ch.emlidstock.noyau.volume.PointPlan
import ch.emlidstock.noyau.volume.Triangle
import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * Couleurs des vues de contrôle, reprises de l'appli topo V1 (src/plan/couleurs.js et ecarts.js).
 * Une couleur = [rouge, vert, bleu] de 0 à 255.
 */

/** Couleur rouge, vert, bleu (0 à 255). */
data class Couleur(val rouge: Int, val vert: Int, val bleu: Int)

// Du plus bas (bleu) au plus haut (rouge), palette classique des cartes d'altitude.
private val PALETTE = listOf(Couleur(44, 123, 182), Couleur(171, 217, 233), Couleur(255, 255, 191), Couleur(253, 174, 97), Couleur(215, 25, 28))

/** Couleur d'une altitude (m) entre [plage].min et [plage].max, en mélangeant les couleurs voisines de la palette. */
fun couleurAltitude(altitude: Double, plage: Plage): Couleur {
    val etendue = plage.max - plage.min
    val t = if (etendue > 0) ((altitude - plage.min) / etendue).coerceIn(0.0, 1.0) else 0.5
    val position = t * (PALETTE.size - 1)
    val i = minOf(position.toInt(), PALETTE.size - 2)
    return melanger(PALETTE[i], PALETTE[i + 1], position - i)
}

private fun melanger(a: Couleur, b: Couleur, t: Double) = Couleur(
    (a.rouge + (b.rouge - a.rouge) * t).roundToInt(),
    (a.vert + (b.vert - a.vert) * t).roundToInt(),
    (a.bleu + (b.bleu - a.bleu) * t).roundToInt(),
)

// --- Remblai (rouge) / déblai (jaune) par paliers de hauteur ---
// On part des triangles d'écart du calcul de volume (altitude = écart dessus − base, m) et on les
// découpe en bandes d'un palier de hauteur : plus le remblai est épais, plus le rouge est foncé ;
// plus le déblai est profond, plus le jaune est foncé.

private val REMBLAI_CLAIR = Couleur(254, 202, 202)
private val REMBLAI_FONCE = Couleur(153, 27, 27)
private val DEBLAI_CLAIR = Couleur(254, 240, 138)
private val DEBLAI_FONCE = Couleur(161, 98, 7)
private val PALIERS_AUTO = listOf(0.05, 0.1, 0.2, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0)

// Au-delà, les classes restantes prennent la couleur la plus foncée (légende lisible).
private const val CLASSES_MAX = 12

/** Plus grand remblai et plus grand déblai (m, valeurs positives) des triangles d'écart. */
data class Extremes(val remblai: Double, val deblai: Double)

fun ecartsExtremes(trianglesEcart: List<Triangle>): Extremes {
    var remblai = 0.0
    var deblai = 0.0
    for (t in trianglesEcart) for (s in t.sommets) {
        remblai = maxOf(remblai, s.altitude)
        deblai = maxOf(deblai, -s.altitude)
    }
    return Extremes(remblai, deblai)
}

/** Palier (m) : [choix] s'il est donné, sinon le plus petit qui donne au plus 6 classes. */
fun choisirPalier(trianglesEcart: List<Triangle>, choix: Double? = null): Double {
    if (choix != null && choix > 0) return choix
    val (remblai, deblai) = ecartsExtremes(trianglesEcart)
    val plusGrand = maxOf(remblai, deblai)
    return PALIERS_AUTO.firstOrNull { plusGrand / it <= 6 } ?: 20.0
}

private fun nombreDeClasses(ecartMax: Double, palier: Double): Int =
    ceil(ecartMax / palier - 1e-9).toInt().coerceIn(1, CLASSES_MAX)

/** Couleur de la classe [rang] (0 = la plus faible) sur [nombre] classes. Une seule classe : teinte moyenne. */
private fun couleurDeClasse(clair: Couleur, fonce: Couleur, rang: Int, nombre: Int): Couleur {
    val t = if (nombre > 1) minOf(rang, nombre - 1).toDouble() / (nombre - 1) else 0.5
    return melanger(clair, fonce, t)
}

/** Zone colorée (un morceau de bande) et classe de légende (de, a en m). */
data class Zone(val couleur: Couleur, val polygone: List<PointPlan>)
data class ClasseLegende(val de: Double, val a: Double, val couleur: Couleur)
data class RemblaiDeblai(val palier: Double, val zones: List<Zone>, val remblai: List<ClasseLegende>, val deblai: List<ClasseLegende>)

/** Découpe les triangles d'écart en zones colorées, une par palier, avec la légende. */
fun zonesRemblaiDeblai(trianglesEcart: List<Triangle>, choix: Double? = null): RemblaiDeblai {
    val palier = choisirPalier(trianglesEcart, choix)
    val extremes = ecartsExtremes(trianglesEcart)
    val classesRemblai = nombreDeClasses(extremes.remblai, palier)
    val classesDeblai = nombreDeClasses(extremes.deblai, palier)

    val zones = bandesDeNiveau(trianglesEcart, palier).map { (bas, polygone) ->
        // Bande [bas, bas + palier] : au-dessus de 0 c'est du remblai, en dessous du déblai.
        val couleur = if (bas >= 0) {
            couleurDeClasse(REMBLAI_CLAIR, REMBLAI_FONCE, (bas / palier).roundToInt(), classesRemblai)
        } else {
            couleurDeClasse(DEBLAI_CLAIR, DEBLAI_FONCE, (-bas / palier).roundToInt() - 1, classesDeblai)
        }
        Zone(couleur, polygone)
    }

    fun legende(clair: Couleur, fonce: Couleur, ecartMax: Double, nombre: Int): List<ClasseLegende> =
        if (ecartMax <= 1e-6) {
            emptyList()
        } else {
            (0 until nombre).map { rang ->
                ClasseLegende(
                    de = rang * palier,
                    a = if (rang == nombre - 1) maxOf(ecartMax, (rang + 1) * palier) else (rang + 1) * palier,
                    couleur = couleurDeClasse(clair, fonce, rang, nombre),
                )
            }
        }
    return RemblaiDeblai(
        palier = palier,
        zones = zones,
        remblai = legende(REMBLAI_CLAIR, REMBLAI_FONCE, extremes.remblai, classesRemblai),
        deblai = legende(DEBLAI_CLAIR, DEBLAI_FONCE, extremes.deblai, classesDeblai),
    )
}
