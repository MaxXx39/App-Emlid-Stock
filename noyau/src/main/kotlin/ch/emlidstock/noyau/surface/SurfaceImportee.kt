package ch.emlidstock.noyau.surface

import ch.emlidstock.noyau.volume.Sommet
import ch.emlidstock.noyau.volume.Triangle
import ch.emlidstock.noyau.volume.aireTriangle
import kotlin.math.abs

/**
 * Surface lue dans un fichier (LandXML ou DXF) : ses points (Est, Nord, altitude en m) et ses
 * triangles, donnés par les positions de leurs 3 sommets dans [sommets].
 */
data class SurfaceLue(
    val nom: String,
    val sommets: List<Sommet>,
    val faces: List<IntArray>,
    val description: String = "",
    val nombreLignesRupture: Int = 0,
    val trianglesInvisibles: Int = 0,
    /** Surface en plan écrite dans le fichier (m²), ou null. */
    val surfaceAnnoncee: Double? = null,
) {
    fun triangles(): List<Triangle> = faces.map { Triangle(sommets[it[0]], sommets[it[1]], sommets[it[2]]) }
}

/** Résultat de la lecture d'un fichier de surfaces. */
data class LectureSurfaces(val surfaces: List<SurfaceLue>, val erreurs: List<String>, val avertissements: List<String>)

/**
 * Surface importée rangée dans un chantier (étape 9) : elle peut servir de base de volume (mode B).
 * [origine] : « LandXML » ou « DXF ».
 */
class SurfaceImportee(
    val id: String,
    val chantierId: String,
    var nom: String,
    val origine: String,
    val nomFichier: String,
    val creeLeMs: Long,
    val sommets: List<Sommet>,
    val faces: List<IntArray>,
) {
    /** Triangles de la surface (calculés une seule fois). */
    val triangles: List<Triangle> by lazy { faces.map { Triangle(sommets[it[0]], sommets[it[1]], sommets[it[2]]) } }
}

/** Résumé chiffré d'une surface : surface en plan (m²), altitudes min et max (m). */
data class ResumeSurface(val surfacePlan: Double, val altitudeMin: Double, val altitudeMax: Double)

/** Surface en plan (m²) et altitudes extrêmes (m) des triangles. */
fun resumerSurface(triangles: List<Triangle>): ResumeSurface {
    if (triangles.isEmpty()) return ResumeSurface(0.0, 0.0, 0.0)
    val altitudes = triangles.flatMap { t -> t.sommets.map { it.altitude } }
    return ResumeSurface(triangles.sumOf { abs(aireTriangle(it)) }, altitudes.min(), altitudes.max())
}
