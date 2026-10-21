package ch.emlidstock.noyau.controle

import ch.emlidstock.noyau.volume.Triangle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Projection d'une surface en 3D vers l'écran (vue « en perspective cavalière », sans point de fuite).
 * - [azimutDeg] : direction de la vue, 0 = on regarde vers le Nord, 90 = vers l'Est (degrés) ;
 * - [elevationDeg] : 0 = vue de côté, 90 = vue de dessus (degrés) ;
 * - [exageration] : facteur appliqué aux hauteurs (1 = réel) pour mieux voir le relief.
 * Les coordonnées sont prises autour de [centreEst], [centreNord], [altitudeCentre] (m).
 */
data class Projection3D(
    val centreEst: Double,
    val centreNord: Double,
    val altitudeCentre: Double,
    val azimutDeg: Double = 30.0,
    val elevationDeg: Double = 35.0,
    val exageration: Double = 1.0,
) {
    private val a = Math.toRadians(azimutDeg)
    private val e = Math.toRadians(elevationDeg)

    /**
     * Point à l'écran, en mètres « écran » : x vers la droite, y vers le haut, et profondeur
     * (plus grande = plus loin de l'observateur). L'écran multiplie ensuite par son échelle.
     */
    fun projeter(est: Double, nord: Double, altitude: Double): Triple<Double, Double, Double> {
        val dx = est - centreEst
        val dy = nord - centreNord
        val dz = (altitude - altitudeCentre) * exageration
        // Rotation autour de la verticale : l'axe « profond » pointe dans la direction regardée.
        val droite = dx * cos(a) - dy * sin(a)
        val devant = dx * sin(a) + dy * cos(a)
        // Inclinaison de la vue.
        val haut = dz * cos(e) + devant * sin(e)
        val profondeur = devant * cos(e) - dz * sin(e)
        return Triple(droite, haut, profondeur)
    }

    /** Triangles triés du plus loin au plus proche (on dessine les plus loin d'abord). */
    fun trierPourDessin(triangles: List<Triangle>): List<Triangle> =
        triangles.sortedByDescending { t -> t.sommets.sumOf { projeter(it.est, it.nord, it.altitude).third } }

    /**
     * Éclairage d'un triangle de 0.35 (sombre) à 1 (plein jour), lumière venant d'en haut
     * et un peu du Nord-Ouest : les pentes face à la lumière sont plus claires.
     */
    fun eclairage(t: Triangle): Double {
        val ux = t.b.est - t.a.est; val uy = t.b.nord - t.a.nord; val uz = (t.b.altitude - t.a.altitude) * exageration
        val vx = t.c.est - t.a.est; val vy = t.c.nord - t.a.nord; val vz = (t.c.altitude - t.a.altitude) * exageration
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val longueur = sqrt(nx * nx + ny * ny + nz * nz)
        if (longueur == 0.0) return 1.0
        nx /= longueur; ny /= longueur; nz /= longueur
        if (nz < 0) { nx = -nx; ny = -ny; nz = -nz }
        // Direction de la lumière (normalisée) : Nord-Ouest, haute dans le ciel.
        val lumiere = doubleArrayOf(-0.4, 0.4, 0.82)
        val produit = nx * lumiere[0] + ny * lumiere[1] + nz * lumiere[2]
        return 0.35 + 0.65 * abs(produit).coerceIn(0.0, 1.0)
    }
}
