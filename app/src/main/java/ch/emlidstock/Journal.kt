package ch.emlidstock

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal du test terrain : garde en mémoire tout ce qui est reçu (positions et phrases NMEA),
 * avec l'heure, pour pouvoir l'enregistrer dans un fichier et l'analyser ensuite.
 * Au-delà de [nbLignesMax] lignes, les plus anciennes sont effacées.
 */
class Journal(private val nbLignesMax: Int = 100_000) {
    private val lignes = ArrayDeque<String>()
    private val formatHeure = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    /** Ajoute une ligne, précédée de l'heure du téléphone et d'une étiquette (POS, NMEA, INFO...). */
    @Synchronized
    fun ajouter(etiquette: String, texte: String) {
        lignes.addLast("${formatHeure.format(Date())} $etiquette $texte")
        while (lignes.size > nbLignesMax) lignes.removeFirst()
    }

    @Synchronized
    fun nombreDeLignes(): Int = lignes.size

    /** Tout le journal sous forme de texte (une ligne par événement). */
    @Synchronized
    fun texteComplet(): String = lignes.joinToString("\n")

    @Synchronized
    fun vider() = lignes.clear()
}
