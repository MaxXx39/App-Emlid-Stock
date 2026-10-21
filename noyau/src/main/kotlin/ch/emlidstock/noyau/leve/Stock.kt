package ch.emlidstock.noyau.leve

/**
 * Un stock (un seul tas), rattaché à un chantier. Il peut être repris sur plusieurs sessions.
 *
 * Nommage des points (cahier des charges) :
 * - bas de talus : B1, B2… (une seule ligne, elle forme le contour) ;
 * - haut de talus : H1-1, H1-2… ; « nouvelle ligne » passe à H2-1, H3-1… ;
 * - points caractéristiques : C1, C2…
 * Le numéro suivant est toujours « le plus grand existant + 1 » dans le groupe.
 */
class Stock(
    val id: String,
    val chantierId: String,
    var nom: String,
    val creeLeMs: Long,
    val type: TypeLeve = TypeLeve.STOCK,
    points: List<PointLeve> = emptyList(),
    ligneHautActive: Int? = null,
    lignesFermees: Set<Int> = emptySet(),
) {
    private val listePoints = points.toMutableList()

    /** Lignes de haut de talus fermées (dernier point relié au premier), au choix de l'utilisateur. */
    val lignesFermees: MutableSet<Int> = lignesFermees.toMutableSet()

    /** Points dans l'ordre où ils ont été levés. */
    val points: List<PointLeve>
        get() = listePoints

    /** Ligne de haut de talus en cours (par défaut la dernière existante, ou 1). */
    var ligneHautActive: Int = ligneHautActive ?: (points.mapNotNull { it.ligne }.maxOrNull() ?: 1)
        private set

    /**
     * Numéro et ligne du prochain point dans ce mode (ligne de haut de talus : [ligne], sinon la ligne en cours).
     * On saute les noms déjà pris (un point renommé à la main peut porter un nom comme « B5 »).
     */
    fun prochainNumero(mode: ModeLeve, ligne: Int? = null): Pair<Int?, Int> {
        val ligneChoisie = if (mode == ModeLeve.HAUT_DE_TALUS) (ligne ?: ligneHautActive) else null
        var numero = (listePoints.filter { it.mode == mode && it.ligne == ligneChoisie }.maxOfOrNull { it.numero } ?: 0) + 1
        while (listePoints.any { it.nom == nomDePoint(mode, ligneChoisie, numero) }) numero++
        return Pair(ligneChoisie, numero)
    }

    /** Nom du prochain point dans ce mode (B4, H2-1, C3…). */
    fun prochainNom(mode: ModeLeve): String {
        val (ligne, numero) = prochainNumero(mode)
        return nomDePoint(mode, ligne, numero)
    }

    /** Ajoute un point levé (son nom doit venir de [prochainNom]). */
    fun ajouterPoint(point: PointLeve) {
        require(listePoints.none { it.nom == point.nom }) { "Le point ${point.nom} existe déjà" }
        listePoints.add(point)
    }

    /** Supprime le dernier point levé et le renvoie (null s'il n'y en a pas). */
    fun annulerDernierPoint(): PointLeve? =
        if (listePoints.isEmpty()) null else listePoints.removeAt(listePoints.size - 1)

    /** Le point de ce nom, ou null. */
    fun point(nom: String): PointLeve? = listePoints.firstOrNull { it.nom == nom }

    /**
     * Remplace le point [ancienNom] par [nouveau] (même place dans la liste).
     * Renvoie un message d'erreur (nom déjà pris, point absent), ou null si c'est fait.
     */
    fun remplacerPoint(ancienNom: String, nouveau: PointLeve): String? {
        val position = listePoints.indexOfFirst { it.nom == ancienNom }
        if (position < 0) return "le point $ancienNom n'existe plus"
        if (nouveau.nom != ancienNom && listePoints.any { it.nom == nouveau.nom }) return "le nom ${nouveau.nom} est déjà pris"
        listePoints[position] = nouveau
        return null
    }

    /** Change le rôle d'un point (B, H ou C) : il prend le prochain nom libre du nouveau groupe. */
    fun changerRole(nom: String, mode: ModeLeve, ligne: Int? = null): String? {
        val point = point(nom) ?: return "le point $nom n'existe plus"
        val (nouvelleLigne, numero) = prochainNumero(mode, ligne)
        return remplacerPoint(
            nom,
            point.copy(nom = nomDePoint(mode, nouvelleLigne, numero), mode = mode, ligne = nouvelleLigne, numero = numero),
        )
    }

    /** Lignes de haut de talus existantes (numéros), dans l'ordre. */
    fun lignesHaut(): List<Int> = listePoints.mapNotNull { it.ligne }.distinct().sorted()

    /** Ferme ou ouvre une ligne de haut de talus. */
    fun fermerLigne(ligne: Int, fermee: Boolean) {
        if (fermee) lignesFermees.add(ligne) else lignesFermees.remove(ligne)
    }

    /** Supprime le point de ce nom. Renvoie vrai s'il existait. */
    fun supprimerPoint(nom: String): Boolean = listePoints.removeAll { it.nom == nom }

    /**
     * Ouvre une nouvelle ligne de haut de talus (H2, H3…).
     * Si la ligne en cours est encore vide, on la garde (pas de ligne vide).
     */
    fun nouvelleLigneHaut(): Int {
        val ligneEnCoursUtilisee = listePoints.any { it.mode == ModeLeve.HAUT_DE_TALUS && it.ligne == ligneHautActive }
        if (ligneEnCoursUtilisee) {
            val plusGrande = listePoints.mapNotNull { it.ligne }.maxOrNull() ?: ligneHautActive
            ligneHautActive = maxOf(plusGrande, ligneHautActive) + 1
        }
        return ligneHautActive
    }

    /** Nombre de points par mode, pour l'affichage. */
    fun nombreDePoints(mode: ModeLeve): Int = listePoints.count { it.mode == mode }
}

/** Écrit le nom d'un point : B7, H2-5, C3. */
fun nomDePoint(mode: ModeLeve, ligne: Int?, numero: Int): String =
    if (mode == ModeLeve.HAUT_DE_TALUS) "${mode.lettre}${ligne ?: 1}-$numero" else "${mode.lettre}$numero"

/**
 * Nom proposé pour un nouveau stock : « date chantier numéro », par ex. « 2026-10-21 Gravière 1 ».
 * Le numéro est le premier qui n'est pas déjà pris pour cette date dans ce chantier.
 */
fun nomStockParDefaut(date: String, nomChantier: String, nomsExistants: Collection<String>): String {
    var numero = 1
    while ("$date $nomChantier $numero" in nomsExistants) numero++
    return "$date $nomChantier $numero"
}
