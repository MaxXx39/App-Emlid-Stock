package ch.emlidstock.noyau.coordonnees

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream

/**
 * Grille du géoïde : pour chaque nœud (latitude, longitude), la hauteur du géoïde
 * au-dessus de l'ellipsoïde GRS80 (m). On l'utilise ainsi :
 *     altitude = hauteur ellipsoïdale − valeur de la grille.
 *
 * Grille fournie : CHGeo2004 de swisstopo pour les altitudes LHN95 (RAN95),
 * fichier « chgeo2004_ETRS.agr » (licence libre CC0, © swisstopo), au pas de 30″,
 * convertie en fichier binaire `geoide/chgeo2004_lhn95.grille` (voir LISEZMOI.md à côté).
 *
 * Les nœuds sont rangés ligne par ligne, du nord au sud, et d'ouest en est.
 */
class GrilleGeoide(
    private val nbLignes: Int,
    private val nbColonnes: Int,
    private val longitudeOuestDeg: Double,
    private val latitudeNordDeg: Double,
    private val pasDeg: Double,
    private val valeurs: FloatArray,
) {
    init {
        require(valeurs.size == nbLignes * nbColonnes) { "grille du géoïde incomplète" }
    }

    /**
     * Hauteur du géoïde (m) à la position donnée (degrés), par interpolation bilinéaire
     * entre les 4 nœuds voisins. Renvoie null en dehors de la grille (hors de Suisse).
     */
    fun hauteurGeoide(latitudeDeg: Double, longitudeDeg: Double): Double? {
        val ligneDecimale = (latitudeNordDeg - latitudeDeg) / pasDeg
        val colonneDecimale = (longitudeDeg - longitudeOuestDeg) / pasDeg
        if (ligneDecimale < 0 || colonneDecimale < 0) return null
        val ligne = ligneDecimale.toInt()
        val colonne = colonneDecimale.toInt()
        if (ligne + 1 >= nbLignes || colonne + 1 >= nbColonnes) return null

        // Part de la distance vers le nœud suivant (0 à 1) vers le sud et vers l'est.
        val versSud = ligneDecimale - ligne
        val versEst = colonneDecimale - colonne
        return valeur(ligne, colonne) * (1 - versSud) * (1 - versEst) +
            valeur(ligne, colonne + 1) * (1 - versSud) * versEst +
            valeur(ligne + 1, colonne) * versSud * (1 - versEst) +
            valeur(ligne + 1, colonne + 1) * versSud * versEst
    }

    private fun valeur(ligne: Int, colonne: Int): Double = valeurs[ligne * nbColonnes + colonne].toDouble()

    companion object {
        /**
         * Lit une grille au format binaire de l'appli :
         * nombre de lignes, nombre de colonnes (entiers), longitude ouest, latitude nord, pas (degrés),
         * puis toutes les valeurs (nombres à virgule sur 4 octets).
         */
        fun lire(flux: InputStream): GrilleGeoide {
            DataInputStream(BufferedInputStream(flux)).use { lecteur ->
                val nbLignes = lecteur.readInt()
                val nbColonnes = lecteur.readInt()
                val longitudeOuest = lecteur.readDouble()
                val latitudeNord = lecteur.readDouble()
                val pas = lecteur.readDouble()
                val valeurs = FloatArray(nbLignes * nbColonnes) { lecteur.readFloat() }
                return GrilleGeoide(nbLignes, nbColonnes, longitudeOuest, latitudeNord, pas, valeurs)
            }
        }

        /** Charge la grille CHGeo2004 (altitudes LHN95 / RAN95) livrée avec l'appli. */
        fun chgeo2004Lhn95(): GrilleGeoide {
            val flux = GrilleGeoide::class.java.getResourceAsStream("/geoide/chgeo2004_lhn95.grille")
                ?: error("Fichier du géoïde CHGeo2004 introuvable dans l'appli")
            return lire(flux)
        }
    }
}
