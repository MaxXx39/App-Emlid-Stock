package ch.emlidstock.noyau.partage

/*
 * Appareils autorisés à partager les projets (tablettes, téléphones, plus tard navigateurs).
 * Chaque appareil a une fiche dans Firestore : appareils/<identifiant Firebase>.
 * L'appareil écrit lui-même code, modèle, appli, version et « vu le » ;
 * seul l'administrateur peut écrire le nom (règles firebase/firestore.rules).
 * Les administrateurs sont listés dans admins/<identifiant>, créés à la main dans la console Firebase.
 */

/** Fiche d'un appareil, telle que lue dans Firestore. */
data class Appareil(
    val identifiant: String,
    val code: String,
    /** Nom donné par l'administrateur (« Tablette chantier Gland »), null si pas encore nommé. */
    val nom: String?,
    val modele: String,
    /** « android » ou « navigateur ». */
    val appli: String,
    val version: String,
    /** Dernière connexion, en millisecondes depuis 1970 (0 si inconnue). */
    val vuLeMs: Long,
)

/**
 * Petit code pour reconnaître un appareil (« K7F3XA ») : les 6 premiers caractères
 * de son identifiant Firebase, en majuscules. Il sert seulement à l'affichage, pas de sécurité.
 */
fun codeAppareil(identifiant: String): String = identifiant.take(6).uppercase()

/** Champs écrits par l'appareil lui-même à chaque connexion. */
fun champsDeLAppareil(identifiant: String, modele: String, appli: String, version: String, maintenantMs: Long): Map<String, Any?> =
    mapOf(
        "code" to codeAppareil(identifiant),
        "modele" to modele,
        "appli" to appli,
        "version" to version,
        "vuLe" to maintenantMs,
    )

/** Relit une fiche d'appareil depuis Firestore (champs manquants : valeurs vides). */
fun appareilDepuis(document: DocumentFirestore): Appareil = Appareil(
    identifiant = document.id,
    code = document.champs["code"] as? String ?: codeAppareil(document.id),
    nom = (document.champs["nom"] as? String)?.ifBlank { null },
    modele = document.champs["modele"] as? String ?: "",
    appli = document.champs["appli"] as? String ?: "",
    version = document.champs["version"] as? String ?: "",
    vuLeMs = (document.champs["vuLe"] as? Number)?.toLong() ?: 0L,
)

/** Liste triée pour l'administration : appareils nommés d'abord (par nom), puis les nouveaux (les plus récents d'abord). */
fun trierAppareils(appareils: List<Appareil>): List<Appareil> =
    appareils.sortedWith(compareBy<Appareil> { it.nom == null }.thenBy { it.nom?.lowercase() }.thenByDescending { it.vuLeMs })
