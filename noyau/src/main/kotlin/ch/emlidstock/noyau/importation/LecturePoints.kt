package ch.emlidstock.noyau.importation

/*
 * Lecture des fichiers de points : CSV Emlid et TXT (tabulations), reprise de l'appli topo V1
 * (src/topo/import-points.js) avec ses tests. Coordonnées MN95 en mètres, sans aucune conversion.
 */

/** Point lu dans un fichier : nom, Est, Nord, altitude (m), et numéro de la ligne du fichier. */
data class PointImporte(val nom: String, val est: Double, val nord: Double, val altitude: Double, val ligne: Int)

/** Ligne illisible : son numéro et la raison. */
data class ErreurLigne(val ligne: Int, val message: String)

/** Résultat de la lecture : format reconnu, points lus, lignes illisibles, noms en double. */
data class ResultatLecture(
    val format: String,
    val points: List<PointImporte>,
    val erreurs: List<ErreurLigne>,
    val avertissements: List<String>,
)

/**
 * Lit le contenu d'un fichier de points et reconnaît son format tout seul :
 * - CSV Emlid : la première ligne contient les titres (Name, Easting, Northing, Elevation…) ;
 * - TXT : chaque ligne contient nom, Est, Nord, altitude séparés par des tabulations.
 * Une ligne illisible ne bloque pas la lecture : elle est signalée avec son numéro.
 */
fun lirePoints(texte: String): ResultatLecture {
    // Enlève le caractère invisible (BOM) que certains logiciels ajoutent au début du fichier.
    val lignes = texte.removePrefix("﻿").split(Regex("\r\n|\r|\n"))
    val premiereLigne = lignes.firstOrNull { it.isNotBlank() } ?: ""
    val (format, points, erreurs) = if (premiereLigne.contains("easting", ignoreCase = true)) lireCsvEmlid(lignes) else lireTxt(lignes)
    return ResultatLecture(format, points, erreurs, chercherNomsEnDouble(points))
}

/** CSV exporté par Emlid Flow : les colonnes Name, Easting, Northing, Elevation sont repérées par leur titre. */
private fun lireCsvEmlid(lignes: List<String>): Triple<String, List<PointImporte>, List<ErreurLigne>> {
    val points = mutableListOf<PointImporte>()
    val erreurs = mutableListOf<ErreurLigne>()
    val indexTitres = lignes.indexOfFirst { it.isNotBlank() }
    val titres = decouperLigneCsv(lignes[indexTitres]).map { it.trim().lowercase() }
    val colonnes = linkedMapOf(
        "Name" to titres.indexOf("name"),
        "Easting" to titres.indexOf("easting"),
        "Northing" to titres.indexOf("northing"),
        "Elevation" to titres.indexOf("elevation"),
    )
    val manquantes = colonnes.filterValues { it == -1 }.keys
    if (manquantes.isNotEmpty()) {
        erreurs.add(ErreurLigne(indexTitres + 1, "colonne(s) introuvable(s) : ${manquantes.joinToString(", ")}"))
        return Triple("CSV Emlid", points, erreurs)
    }
    for (i in indexTitres + 1 until lignes.size) {
        if (lignes[i].isBlank()) continue
        val champs = decouperLigneCsv(lignes[i])
        fun champ(cle: String) = champs.getOrNull(colonnes.getValue(cle))
        creerPoint(champ("Name"), champ("Easting"), champ("Northing"), champ("Elevation"), i + 1)
            .onSuccess { points.add(it) }
            .onFailure { erreurs.add(ErreurLigne(i + 1, it.message ?: "ligne illisible")) }
    }
    return Triple("CSV Emlid", points, erreurs)
}

/** TXT : nom, Est, Nord, altitude séparés par des tabulations. */
private fun lireTxt(lignes: List<String>): Triple<String, List<PointImporte>, List<ErreurLigne>> {
    val points = mutableListOf<PointImporte>()
    val erreurs = mutableListOf<ErreurLigne>()
    for ((i, ligne) in lignes.withIndex()) {
        if (ligne.isBlank()) continue
        val champs = ligne.split('\t')
        if (champs.size < 4) {
            erreurs.add(ErreurLigne(i + 1, "4 valeurs attendues (nom, Est, Nord, altitude) séparées par des tabulations"))
            continue
        }
        creerPoint(champs[0], champs[1], champs[2], champs[3], i + 1)
            .onSuccess { points.add(it) }
            .onFailure { erreurs.add(ErreurLigne(i + 1, it.message ?: "ligne illisible")) }
    }
    return Triple("TXT", points, erreurs)
}

/** Découpe une ligne CSV (virgules), en gérant les champs entre guillemets qui contiennent une virgule. */
private fun decouperLigneCsv(ligne: String): List<String> {
    val champs = mutableListOf<String>()
    val champ = StringBuilder()
    var entreGuillemets = false
    var i = 0
    while (i < ligne.length) {
        val c = ligne[i]
        when {
            entreGuillemets && c == '"' && ligne.getOrNull(i + 1) == '"' -> { champ.append('"'); i++ } // deux guillemets = un guillemet
            entreGuillemets && c == '"' -> entreGuillemets = false
            entreGuillemets -> champ.append(c)
            c == '"' -> entreGuillemets = true
            c == ',' -> { champs.add(champ.toString()); champ.clear() }
            else -> champ.append(c)
        }
        i++
    }
    champs.add(champ.toString())
    return champs
}

/** Crée un point à partir des textes lus ; échec si le nom manque ou si une coordonnée n'est pas un nombre. */
private fun creerPoint(texteNom: String?, texteEst: String?, texteNord: String?, texteAltitude: String?, ligne: Int): Result<PointImporte> {
    val nom = texteNom?.trim().orEmpty()
    if (nom.isEmpty()) return Result.failure(IllegalArgumentException("nom du point manquant"))
    val est = lireNombre(texteEst)
    val nord = lireNombre(texteNord)
    val altitude = lireNombre(texteAltitude)
    val illisibles = listOfNotNull(if (est == null) "Est" else null, if (nord == null) "Nord" else null, if (altitude == null) "altitude" else null)
    if (illisibles.isNotEmpty()) return Result.failure(IllegalArgumentException("point « $nom » : ${illisibles.joinToString(", ")} illisible(s)"))
    return Result.success(PointImporte(nom, est!!, nord!!, altitude!!, ligne))
}

/** Nombre écrit avec le point décimal (la virgule est refusée, pour ne pas confondre avec un séparateur). */
private fun lireNombre(texte: String?): Double? {
    val propre = texte?.trim().orEmpty()
    if (!Regex("^[-+]?(\\d+\\.?\\d*|\\.\\d+)$").matches(propre)) return null
    return propre.toDouble()
}

/** Signale les noms présents plusieurs fois (tous les points sont gardés : c'est l'utilisateur qui décide). */
private fun chercherNomsEnDouble(points: List<PointImporte>): List<String> =
    points.groupBy { it.nom }.filterValues { it.size > 1 }
        .map { (nom, liste) -> "le nom « $nom » apparaît plusieurs fois (lignes ${liste.joinToString(", ") { it.ligne.toString() }})" }
