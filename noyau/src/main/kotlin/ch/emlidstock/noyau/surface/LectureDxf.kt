package ch.emlidstock.noyau.surface

import ch.emlidstock.noyau.volume.Sommet
import java.util.Locale
import kotlin.math.abs

/*
 * Lecture d'un fichier DXF (texte) : surfaces en triangles 3D, reprise de l'appli topo V1
 * (src/topo/dxf-lecture.js).
 *
 * Un DXF est une suite de paires de lignes : un code (nombre) puis une valeur.
 * On lit, dans la section des entités :
 * - les faces 3D (3DFACE) : 3 ou 4 sommets (codes 10/20/30, 11/21/31, 12/22/32, 13/23/33) ;
 *   une face à 4 sommets différents est coupée en 2 triangles ;
 * - les maillages « polyface » (POLYLINE avec le drapeau 64) : sommets puis faces numérotées.
 * Coordonnées : X = Est, Y = Nord, Z = altitude (m), sans aucune conversion.
 * Une surface est créée par calque (le nom du calque devient le nom de la surface).
 */

/** Une entité du DXF : type, calque et codes (pour chaque code, la première valeur rencontrée). */
private class Entite(val type: String, var calque: String = "0", val codes: HashMap<String, String> = HashMap())

/** Points (un seul par position) et triangles d'un calque. */
private class SurfaceDuCalque {
    val sommets = mutableListOf<Sommet>()
    val positions = HashMap<String, Int>()
    val faces = mutableListOf<IntArray>()

    fun indice(s: Sommet): Int {
        val cle = String.format(Locale.ROOT, "%.4f %.4f %.4f", s.est, s.nord, s.altitude)
        return positions.getOrPut(cle) {
            sommets.add(Sommet((sommets.size + 1).toString(), s.est, s.nord, s.altitude))
            sommets.size - 1
        }
    }
}

/** Lit le texte d'un fichier DXF : une surface par calque qui contient des faces 3D. */
fun lireDxf(texte: String): LectureSurfaces {
    if (texte.startsWith("AutoCAD Binary DXF")) {
        return LectureSurfaces(emptyList(), listOf("DXF binaire non lu : enregistrez le fichier en DXF « texte » (ASCII)"), emptyList())
    }
    val entites = entitesDuFichier(texte)
    val parCalque = LinkedHashMap<String, SurfaceDuCalque>()
    var facesIgnorees = 0

    fun ajouterFace(calque: String, sommets: List<Sommet>) {
        val surface = parCalque.getOrPut(calque) { SurfaceDuCalque() }
        // Une face à 4 sommets (le 4e différent du 3e) devient 2 triangles.
        val uniques = sommets.filterIndexed { i, s -> i < 3 || !memePosition(s, sommets[2]) }
        val triangles = if (uniques.size == 4) listOf(listOf(uniques[0], uniques[1], uniques[2]), listOf(uniques[0], uniques[2], uniques[3])) else listOf(uniques.take(3))
        for (t in triangles) {
            // Un triangle sans surface en plan (vertical ou aplati) ne sert pas au calcul.
            if (abs(aireEnPlan(t)) < 1e-9) {
                facesIgnorees++
                continue
            }
            surface.faces.add(IntArray(3) { surface.indice(t[it]) })
        }
    }

    var i = 0
    while (i < entites.size) {
        val entite = entites[i]
        if (entite.type == "3DFACE") {
            val sommets = (0..3).mapNotNull { sommetDe(entite, it) }
            if (sommets.size >= 3) ajouterFace(entite.calque, sommets)
        } else if (entite.type == "POLYLINE" && (entier(entite.codes["70"]) and 64) != 0) {
            // Maillage polyface : les VERTEX qui suivent, jusqu'à SEQEND.
            val sommets = mutableListOf<Sommet?>()
            i++
            while (i < entites.size && entites[i].type == "VERTEX") {
                val vertex = entites[i]
                val drapeau = entier(vertex.codes["70"])
                if ((drapeau and 64) != 0) {
                    sommets.add(sommetDe(vertex, 0))
                } else if ((drapeau and 128) != 0) {
                    // Face : numéros des sommets (à partir de 1, négatif = arête cachée).
                    val face = listOf("71", "72", "73", "74").map { abs(entier(vertex.codes[it])) }.filter { it > 0 }
                        .mapNotNull { sommets.getOrNull(it - 1) }
                    if (face.size >= 3) ajouterFace(entite.calque, face)
                }
                i++
            }
            continue
        }
        i++
    }

    val surfaces = parCalque.map { (calque, s) -> SurfaceLue(calque, s.sommets, s.faces) }.filter { it.faces.isNotEmpty() }
    val erreurs = if (surfaces.isEmpty()) listOf("aucune face 3D (3DFACE ou maillage polyface) trouvée dans le fichier") else emptyList()
    val avertissements = mutableListOf<String>()
    if (facesIgnorees > 0) avertissements.add("$facesIgnorees face(s) verticale(s) ou aplatie(s) ignorée(s)")
    if (surfaces.any { s -> s.sommets.any { it.est < it.nord } }) {
        avertissements.add(
            "certaines coordonnées ont X plus petit que Y : en MN95, X = Est (environ 2 600 000) et Y = Nord " +
                "(environ 1 200 000). Vérifiez que le fichier est bien en MN95.",
        )
    }
    return LectureSurfaces(surfaces, erreurs, avertissements)
}

/** Entités de la section ENTITIES. */
private fun entitesDuFichier(texte: String): List<Entite> {
    val lignes = texte.split(Regex("\r?\n"))
    val entites = mutableListOf<Entite>()
    var dansEntites = false
    var courante: Entite? = null
    var i = 0
    while (i + 1 < lignes.size) {
        val code = lignes[i].trim()
        val valeur = lignes[i + 1].trim()
        if (code == "0" && valeur == "SECTION" && i + 3 < lignes.size && lignes[i + 2].trim() == "2") {
            dansEntites = lignes[i + 3].trim() == "ENTITIES"
            i += 2
            continue
        }
        if (dansEntites) {
            if (code == "0") {
                if (valeur == "ENDSEC") {
                    dansEntites = false
                    courante = null
                } else {
                    courante = Entite(valeur).also { entites.add(it) }
                }
            } else if (courante != null) {
                if (code == "8") courante.calque = valeur
                courante.codes.putIfAbsent(code, valeur)
            }
        }
        i += 2
    }
    return entites
}

/** Sommet numéro k d'une entité (codes 10+k, 20+k, 30+k), ou null s'il manque. */
private fun sommetDe(entite: Entite, k: Int): Sommet? {
    val est = entite.codes[(10 + k).toString()]?.toDoubleOrNull() ?: return null
    val nord = entite.codes[(20 + k).toString()]?.toDoubleOrNull() ?: return null
    val altitude = entite.codes[(30 + k).toString()]?.let { it.toDoubleOrNull() ?: return null } ?: 0.0
    if (!est.isFinite() || !nord.isFinite() || !altitude.isFinite()) return null
    return Sommet("", est, nord, altitude)
}

private fun memePosition(a: Sommet, b: Sommet) =
    abs(a.est - b.est) < 1e-6 && abs(a.nord - b.nord) < 1e-6 && abs(a.altitude - b.altitude) < 1e-6

/** Aire signée en plan d'un triangle (m²). */
private fun aireEnPlan(t: List<Sommet>): Double {
    val (a, b, c) = t
    return ((b.est - a.est) * (c.nord - a.nord) - (c.est - a.est) * (b.nord - a.nord)) / 2
}

private fun entier(valeur: String?): Int = valeur?.trim()?.toIntOrNull() ?: 0
