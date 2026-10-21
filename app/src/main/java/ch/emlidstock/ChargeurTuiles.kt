package ch.emlidstock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import ch.emlidstock.noyau.carte.Fond
import ch.emlidstock.noyau.carte.Tuile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Télécharge les tuiles des fonds swisstopo et les garde en mémoire.
 * (Les fichiers téléchargés sont aussi gardés sur le téléphone par le cache internet
 * installé au démarrage de l'appli : une zone déjà vue se recharge sans réseau.)
 *
 * [quandTuileArrivee] est appelé (depuis un autre fil) quand une tuile est prête, pour redessiner.
 */
class ChargeurTuiles(private val quandTuileArrivee: () -> Unit) {

    // Mémoire : environ 40 Mo d'images.
    private val memoire = object : LruCache<String, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(cle: String, image: Bitmap) = image.byteCount
    }
    private val enCours = Collections.synchronizedSet(HashSet<String>())
    private val echecs = Collections.synchronizedSet(HashSet<String>())
    // Heure du dernier essai raté (pas de réseau) : on attend 10 s avant de réessayer.
    private val essaisRates = ConcurrentHashMap<String, Long>()
    private val ouvriers = Executors.newFixedThreadPool(4)

    @Volatile var dernierEchec: String? = null
        private set

    /** Renvoie l'image de la tuile si elle est prête ; sinon lance son téléchargement et renvoie null. */
    fun image(fond: Fond, tuile: Tuile): Bitmap? {
        val adresse = fond.adresse(tuile)
        memoire.get(adresse)?.let { return it }
        if (adresse in echecs) return null
        val rate = essaisRates[adresse]
        if (rate != null && System.currentTimeMillis() - rate < 10_000) return null
        if (!enCours.add(adresse)) return null
        ouvriers.execute { telecharger(adresse) }
        return null
    }

    /** Oublie les échecs (par ex. quand le réseau revient) pour réessayer. */
    fun reessayer() {
        echecs.clear()
        essaisRates.clear()
        dernierEchec = null
    }

    fun arreter() = ouvriers.shutdownNow()

    private fun telecharger(adresse: String) {
        try {
            val connexion = URL(adresse).openConnection() as HttpURLConnection
            connexion.connectTimeout = 10_000
            connexion.readTimeout = 15_000
            connexion.useCaches = true
            connexion.setRequestProperty("User-Agent", "EmlidStock (appli Android personnelle)")
            try {
                if (connexion.responseCode != HttpURLConnection.HTTP_OK) {
                    // Hors de la zone couverte (404) : on ne réessaie pas.
                    echecs.add(adresse)
                    return
                }
                val image = connexion.inputStream.use { BitmapFactory.decodeStream(it) }
                if (image != null) {
                    memoire.put(adresse, image)
                    essaisRates.remove(adresse)
                    dernierEchec = null
                    quandTuileArrivee()
                }
            } finally {
                connexion.disconnect()
            }
        } catch (erreur: Exception) {
            essaisRates[adresse] = System.currentTimeMillis()
            dernierEchec = "fond swisstopo non chargé (pas de connexion ?)"
        } finally {
            enCours.remove(adresse)
        }
    }
}
