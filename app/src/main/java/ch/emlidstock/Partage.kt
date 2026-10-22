package ch.emlidstock

import android.os.Build
import ch.emlidstock.noyau.partage.Appareil
import ch.emlidstock.noyau.partage.ClientFirebase
import ch.emlidstock.noyau.partage.ErreurFirebase
import ch.emlidstock.noyau.partage.MemoireConnexion
import ch.emlidstock.noyau.partage.appareilDepuis
import ch.emlidstock.noyau.partage.champsDeLAppareil
import ch.emlidstock.noyau.partage.codeAppareil
import ch.emlidstock.noyau.partage.trierAppareils
import kotlin.concurrent.thread

/**
 * Partage des projets par Firebase (projet « perrin-topo », formule gratuite Spark).
 * Pour l'instant : l'appareil se connecte, reçoit son identité et son code, met à jour sa fiche
 * (appareils/<identifiant>) et apprend s'il est administrateur. L'administrateur peut lister,
 * nommer et supprimer les appareils (écran VueAdministration).
 * Les appels à internet se font dans un autre fil : l'écran n'est jamais bloqué.
 */
class Partage(private val ecran: EcranPrincipal) {

    private val reglages = ecran.getSharedPreferences("partage", android.content.Context.MODE_PRIVATE)

    /** Identité Firebase gardée sur l'appareil (perdue si on efface les données de l'appli). */
    private val memoire = object : MemoireConnexion {
        override var identifiant: String?
            get() = reglages.getString("identifiant", null)
            set(valeur) { reglages.edit().putString("identifiant", valeur).apply() }
        override var jetonRenouvellement: String?
            get() = reglages.getString("jeton_renouvellement", null)
            set(valeur) { reglages.edit().putString("jeton_renouvellement", valeur).apply() }
    }

    private val client = ClientFirebase(
        cleApi = CLE_API,
        projetId = PROJET_FIREBASE,
        memoire = memoire,
        // Pour le cas où la clé serait un jour limitée à l'appli Android dans la console Google.
        entetesFixes = mapOf("X-Android-Package" to "ch.emlidstock", "X-Android-Cert" to EMPREINTE_SHA1),
    )

    /** État affiché à l'accueil. */
    @Volatile var etat: String = "pas encore connecté"
        private set
    @Volatile var connecte = false
        private set
    @Volatile var estAdmin = false
        private set
    /** Nom donné à cet appareil par l'administrateur. */
    @Volatile var nomAppareil: String? = null
        private set
    @Volatile private var enCours = false

    /** Code de cet appareil (« K7F3XA »), ou null s'il n'a encore jamais été connecté. */
    val code: String? get() = memoire.identifiant?.let { codeAppareil(it) }

    /** Identifiant complet (à copier dans la console Firebase pour déclarer un administrateur). */
    val identifiant: String? get() = memoire.identifiant

    /** Se connecte et met à jour la fiche de l'appareil (au démarrage, ou bouton « Réessayer »). */
    fun demarrer() {
        if (enCours) return
        enCours = true
        etat = "connexion…"
        thread(name = "partage") {
            try {
                val uid = client.connecter()
                client.ecrireChamps("appareils/$uid", champsDeLAppareil(uid, modele(), "android", version(), System.currentTimeMillis()))
                nomAppareil = client.lireDocument("appareils/$uid")?.let { appareilDepuis(it).nom }
                estAdmin = client.lireDocument("admins/$uid") != null
                connecte = true
                etat = if (estAdmin) "connecté (administrateur)" else "connecté"
                ecran.journal.ajouter("INFO", "Partage : $etat, code ${codeAppareil(uid)}")
            } catch (erreur: ErreurFirebase) {
                connecte = false
                etat = "pas connecté : ${erreur.message}"
                ecran.journal.ajouter("INFO", "Partage : ${erreur.message}")
            } catch (erreur: Exception) {
                connecte = false
                etat = "pas connecté : erreur inattendue (${erreur.message})"
                ecran.journal.ajouter("INFO", "Partage : erreur inattendue ${erreur.message}")
            } finally {
                enCours = false
            }
        }
    }

    // --- Administration (seulement pour un administrateur ; les règles Firebase le vérifient aussi) ---

    /** Liste des appareils, triée ; [quandFini] est appelé sur l'écran avec la liste, ou un message d'erreur. */
    fun listerAppareils(quandFini: (List<Appareil>?, String?) -> Unit) =
        enArrierePlan({ trierAppareils(client.listerDocuments("appareils").map { appareilDepuis(it) }) }, quandFini)

    /** Donne un nom à un appareil (texte vide = enlever le nom). */
    fun nommerAppareil(appareil: Appareil, nom: String, quandFini: (Unit?, String?) -> Unit) =
        enArrierePlan({ client.ecrireChamps("appareils/${appareil.identifiant}", mapOf("nom" to nom.ifBlank { null })) }, quandFini)

    /** Supprime la fiche d'un appareil (il en recréera une, sans nom, à sa prochaine connexion). */
    fun supprimerAppareil(appareil: Appareil, quandFini: (Unit?, String?) -> Unit) =
        enArrierePlan({ client.supprimerDocument("appareils/${appareil.identifiant}") }, quandFini)

    /** Lance [travail] dans un autre fil, puis appelle [quandFini] sur l'écran (résultat ou message d'erreur). */
    private fun <T> enArrierePlan(travail: () -> T, quandFini: (T?, String?) -> Unit) {
        thread(name = "partage") {
            val (resultat, erreur) = try {
                Pair(travail(), null)
            } catch (erreur: Exception) {
                Pair(null, erreur.message ?: "erreur inattendue")
            }
            ecran.runOnUiThread { quandFini(resultat, erreur) }
        }
    }

    private fun modele() = "${Build.MANUFACTURER} ${Build.MODEL}"

    private fun version(): String = try {
        ecran.packageManager.getPackageInfo(ecran.packageName, 0).versionName ?: "?"
    } catch (erreur: Exception) {
        "?"
    }

    companion object {
        // Valeurs du fichier google-services.json du projet Firebase « perrin-topo ».
        // Cette clé n'est pas un mot de passe : elle dit seulement à quel projet parler.
        // La protection, ce sont les règles d'accès (firebase/firestore.rules), vérifiées par Google.
        const val PROJET_FIREBASE = "perrin-topo"
        const val CLE_API = "AIzaSyCRMfxmCW6x7TIU6zMAGjySatFStL0YPj4"
        /** Empreinte SHA-1 de la clé de signature de l'appli (app/cle-signature.jks). */
        const val EMPREINTE_SHA1 = "288D7F451A5C21FAF018787DCA1FE6B0476BF2DB"
    }
}
