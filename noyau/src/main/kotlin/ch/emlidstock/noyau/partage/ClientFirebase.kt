package ch.emlidstock.noyau.partage

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Ce que l'appli garde sur l'appareil pour retrouver son identité Firebase au prochain lancement. */
interface MemoireConnexion {
    var identifiant: String?
    var jetonRenouvellement: String?
}

/** Erreur de Firebase, avec un message compréhensible et le code HTTP (0 = pas de réseau). */
class ErreurFirebase(message: String, val codeHttp: Int) : Exception(message)

/** Un document lu dans Firestore : son identifiant (dernière partie du chemin) et ses champs. */
data class DocumentFirestore(val id: String, val champs: Map<String, Any?>)

/**
 * Accès à Firebase par internet, sans bibliothèque Google : on appelle directement les adresses
 * web de Firebase (« API REST »).
 * - Connexion « anonyme » : chaque appareil reçoit de Firebase une identité unique, sans mot de passe.
 *   Firebase renvoie un « jeton » (preuve d'identité valable une heure) et un « jeton de renouvellement »,
 *   gardé sur l'appareil, pour en redemander un sans changer d'identité.
 * - Firestore : lire, écrire, lister et supprimer des documents (chemin « collection/document »).
 * Les règles d'accès (fichier firebase/firestore.rules) sont vérifiées par le serveur de Google.
 * Toutes les fonctions attendent la réponse d'internet : à appeler hors de l'écran (autre fil).
 * Les adresses peuvent être changées pour les tests (émulateur Firebase sur l'ordinateur).
 */
class ClientFirebase(
    private val cleApi: String,
    private val projetId: String,
    private val memoire: MemoireConnexion,
    private val adresseAuth: String = "https://identitytoolkit.googleapis.com",
    private val adresseJeton: String = "https://securetoken.googleapis.com",
    private val adresseFirestore: String = "https://firestore.googleapis.com",
    /** En-têtes ajoutés à chaque appel (ex. nom du package Android, si la clé est limitée à l'appli). */
    private val entetesFixes: Map<String, String> = emptyMap(),
) {
    private var jeton: String? = null
    private var jetonValableJusquaMs = 0L

    /** Identité de cet appareil dans Firebase (null tant que jamais connecté). */
    val identifiant: String? get() = memoire.identifiant

    private val racineDocuments get() = "projects/$projetId/databases/(default)/documents"

    /**
     * Se connecte (crée l'identité la première fois, sinon la reprend) et renvoie l'identifiant.
     * Le jeton est redemandé seulement s'il expire dans moins de 5 minutes.
     */
    @Synchronized
    fun connecter(): String {
        val maintenant = System.currentTimeMillis()
        val identifiantConnu = memoire.identifiant
        if (jeton != null && identifiantConnu != null && maintenant < jetonValableJusquaMs - 5 * 60_000) {
            return identifiantConnu
        }
        val renouvellement = memoire.jetonRenouvellement
        if (renouvellement != null) {
            val corps = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(renouvellement, "UTF-8")
            val reponse = appeler("POST", "$adresseJeton/v1/token?key=$cleApi", corps, "application/x-www-form-urlencoded", avecJeton = false)
            garderJeton(reponse.getString("id_token"), reponse.getString("refresh_token"), reponse.getString("user_id"), reponse.optString("expires_in"))
        } else {
            val reponse = appeler("POST", "$adresseAuth/v1/accounts:signUp?key=$cleApi", JSONObject().put("returnSecureToken", true).toString(), avecJeton = false)
            garderJeton(reponse.getString("idToken"), reponse.getString("refreshToken"), reponse.getString("localId"), reponse.optString("expiresIn"))
        }
        return memoire.identifiant ?: throw ErreurFirebase("Identité Firebase manquante", 0)
    }

    private fun garderJeton(nouveauJeton: String, renouvellement: String, uid: String, dureeSecondes: String) {
        jeton = nouveauJeton
        jetonValableJusquaMs = System.currentTimeMillis() + (dureeSecondes.toLongOrNull() ?: 3600L) * 1000
        memoire.identifiant = uid
        memoire.jetonRenouvellement = renouvellement
    }

    /** Lit un document (« appareils/abc ») ; null s'il n'existe pas. */
    fun lireDocument(chemin: String): DocumentFirestore? {
        connecter()
        return try {
            documentDepuis(appeler("GET", "$adresseFirestore/v1/$racineDocuments/$chemin", null))
        } catch (erreur: ErreurFirebase) {
            if (erreur.codeHttp == 404) null else throw erreur
        }
    }

    /**
     * Écrit seulement les [champs] donnés dans un document (les autres champs ne changent pas).
     * Le document est créé s'il n'existe pas.
     */
    fun ecrireChamps(chemin: String, champs: Map<String, Any?>) {
        val ecriture = JSONObject()
            .put("update", JSONObject().put("name", "$racineDocuments/$chemin").put("fields", champsVersFirestore(champs)))
            .put("updateMask", JSONObject().put("fieldPaths", JSONArray(champs.keys.toList())))
        enregistrer(listOf(ecriture))
    }

    /** Supprime un document (rien ne se passe s'il n'existe pas). */
    fun supprimerDocument(chemin: String) {
        enregistrer(listOf(JSONObject().put("delete", "$racineDocuments/$chemin")))
    }

    /** Envoie des écritures en une fois (tout ou rien). */
    private fun enregistrer(ecritures: List<JSONObject>) {
        connecter()
        val corps = JSONObject().put("writes", JSONArray(ecritures)).toString()
        appeler("POST", "$adresseFirestore/v1/$racineDocuments:commit", corps)
    }

    /** Liste tous les documents d'une collection (« appareils »), page par page. */
    fun listerDocuments(collection: String): List<DocumentFirestore> {
        connecter()
        val documents = mutableListOf<DocumentFirestore>()
        var page: String? = null
        do {
            val suite = page?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
            val reponse = appeler("GET", "$adresseFirestore/v1/$racineDocuments/$collection?pageSize=300$suite", null)
            val liste = reponse.optJSONArray("documents") ?: JSONArray()
            for (i in 0 until liste.length()) documents.add(documentDepuis(liste.getJSONObject(i)))
            page = reponse.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return documents
    }

    private fun documentDepuis(json: JSONObject) = DocumentFirestore(
        id = json.getString("name").substringAfterLast('/'),
        champs = champsDepuisFirestore(json.optJSONObject("fields")),
    )

    /** Un appel web à Firebase. Renvoie la réponse JSON, ou lance une ErreurFirebase compréhensible. */
    private fun appeler(
        methode: String,
        adresse: String,
        corps: String?,
        typeCorps: String = "application/json; charset=utf-8",
        avecJeton: Boolean = true,
    ): JSONObject {
        try {
            val connexion = URI(adresse).toURL().openConnection() as HttpURLConnection
            connexion.requestMethod = methode
            connexion.connectTimeout = 15_000
            connexion.readTimeout = 30_000
            for ((nom, valeur) in entetesFixes) connexion.setRequestProperty(nom, valeur)
            if (avecJeton) connexion.setRequestProperty("Authorization", "Bearer $jeton")
            if (corps != null) {
                connexion.doOutput = true
                connexion.setRequestProperty("Content-Type", typeCorps)
                connexion.outputStream.use { it.write(corps.toByteArray(Charsets.UTF_8)) }
            }
            val code = connexion.responseCode
            val flux = if (code in 200..299) connexion.inputStream else connexion.errorStream
            val texte = flux?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connexion.disconnect()
            if (code !in 200..299) throw ErreurFirebase(messageErreur(code, texte), code)
            return if (texte.isBlank()) JSONObject() else JSONObject(texte)
        } catch (erreur: IOException) {
            throw ErreurFirebase("Pas de connexion internet (${erreur.message ?: erreur::class.simpleName})", 0)
        }
    }

    /** Traduit une erreur de Firebase en message compréhensible. */
    private fun messageErreur(code: Int, texte: String): String {
        val detail = try {
            JSONObject(texte).optJSONObject("error")?.optString("message").orEmpty()
        } catch (erreur: Exception) {
            ""
        }
        // Identité supprimée dans la console Firebase : on l'oublie, une nouvelle sera créée au prochain essai.
        if (detail.contains("USER_NOT_FOUND") || detail.contains("INVALID_REFRESH_TOKEN") || detail.contains("TOKEN_EXPIRED")) {
            memoire.identifiant = null
            memoire.jetonRenouvellement = null
            jeton = null
            return "Identité de l'appareil refusée par Firebase : une nouvelle sera demandée au prochain essai."
        }
        return when (code) {
            403 -> "Accès refusé par Firebase (règles d'accès)"
            404 -> "Introuvable dans Firebase"
            429 -> "Trop de demandes à Firebase : réessayez plus tard (limite gratuite atteinte ?)"
            else -> "Erreur Firebase $code ${detail.ifEmpty { texte.take(200) }}"
        }
    }
}
