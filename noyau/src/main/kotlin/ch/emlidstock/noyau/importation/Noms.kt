package ch.emlidstock.noyau.importation

/* Listes de noms tapées par l'utilisateur (« b100 à b116, b120 »), reprises de l'appli topo V1 (src/topo/noms.js). */

/** Noms lus et problèmes rencontrés (points introuvables, plage mal écrite). */
data class ListeDeNoms(val noms: List<String>, val erreurs: List<String>)

/** Nom coupé en début + numéro final : « b105 » → (« b », 105, largeur 3). */
private data class NomDecoupe(val prefixe: String, val numero: Int, val largeur: Int)

private fun decouperNom(nom: String): NomDecoupe? {
    val morceaux = Regex("^(.*?)(\\d+)$").find(nom) ?: return null
    val chiffres = morceaux.groupValues[2]
    // Largeur utile seulement pour les numéros écrits avec des zéros devant (« p007 »).
    return NomDecoupe(morceaux.groupValues[1], chiffres.toInt(), if (chiffres.startsWith("0")) chiffres.length else 1)
}

private fun ecrire(prefixe: String, numero: Int, largeur: Int) = prefixe + numero.toString().padStart(largeur, '0')

/**
 * Lit une liste de noms : « b100 à b116 » (aussi à l'envers), « b1 à b5, b9, h3 » (morceaux séparés par
 * des virgules ou des points-virgules). Une plage relie deux noms qui ont le même début et finissent par
 * un numéro. Les noms absents de [nomsExistants] sont signalés.
 */
fun lireListeDeNoms(texte: String, nomsExistants: Collection<String>): ListeDeNoms {
    val existe = nomsExistants.toHashSet()
    val noms = mutableListOf<String>()
    val erreurs = mutableListOf<String>()
    for (morceau in texte.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
        val plage = Regex("^(\\S+)\\s+(?:à|a)\\s+(\\S+)$", RegexOption.IGNORE_CASE).find(morceau)
        if (plage == null) {
            if (morceau in existe) noms.add(morceau) else erreurs.add("point « $morceau » introuvable")
            continue
        }
        val debut = decouperNom(plage.groupValues[1])
        val fin = decouperNom(plage.groupValues[2])
        if (debut == null || fin == null || debut.prefixe != fin.prefixe) {
            erreurs.add("« $morceau » : une plage doit relier deux noms comme « b100 à b116 »")
            continue
        }
        val pas = if (fin.numero >= debut.numero) 1 else -1
        val absents = mutableListOf<String>()
        var numero = debut.numero
        while (numero != fin.numero + pas) {
            val nom = ecrire(debut.prefixe, numero, debut.largeur)
            if (nom in existe) noms.add(nom) else absents.add(nom)
            numero += pas
        }
        if (absents.isNotEmpty()) erreurs.add("« $morceau » : point(s) introuvable(s) ignoré(s) : ${absents.joinToString(", ")}")
    }
    return ListeDeNoms(noms, erreurs)
}

/** +1 si [b] suit [a] (b2 après b1), −1 s'il le précède, 0 sinon. */
private fun pasEntre(a: String, b: String?): Int {
    if (b == null) return 0
    val da = decouperNom(a) ?: return 0
    val db = decouperNom(b) ?: return 0
    if (da.prefixe != db.prefixe) return 0
    return when (b) {
        ecrire(da.prefixe, da.numero + 1, da.largeur) -> 1
        ecrire(da.prefixe, da.numero - 1, da.largeur) -> -1
        else -> 0
    }
}

/** Écrit une liste de noms de façon courte : [b1, b2, b3, h7] → « b1 à b3, h7 » (inverse de [lireListeDeNoms]). */
fun ecrireListeDeNoms(noms: List<String>): String {
    val morceaux = mutableListOf<String>()
    var i = 0
    while (i < noms.size) {
        var j = i
        val pas = pasEntre(noms[i], noms.getOrNull(i + 1))
        if (pas != 0) while (j + 1 < noms.size && pasEntre(noms[j], noms[j + 1]) == pas) j++
        if (j - i >= 2) morceaux.add("${noms[i]} à ${noms[j]}") else morceaux.add(noms[i])
        if (j - i == 1) morceaux.add(noms[j])
        i = j + 1
    }
    return morceaux.joinToString(", ")
}

/**
 * Repère les séries de points numérotés à la suite dans le fichier (b1, b2… b17 puis h1… h31).
 * Ce ne sont que des propositions : c'est l'utilisateur qui choisit le rôle de chaque série.
 */
fun detecterSeries(noms: List<String>): List<List<String>> {
    val series = mutableListOf<List<String>>()
    var serie = mutableListOf<String>()
    for (nom in noms) {
        if (serie.isNotEmpty() && pasEntre(serie.last(), nom) == 1) {
            serie.add(nom)
        } else {
            if (serie.size >= 2) series.add(serie)
            serie = mutableListOf(nom)
        }
    }
    if (serie.size >= 2) series.add(serie)
    return series
}
