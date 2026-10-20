package ch.emlidstock

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Connexion Bluetooth au récepteur (RX2) pour lire son flux NMEA, ligne par ligne.
 *
 * On utilise le « port série Bluetooth » (profil SPP), le moyen standard pour recevoir du NMEA.
 * La lecture se fait dans un fil d'exécution séparé, pour ne pas bloquer l'écran.
 *
 * - [surLigne] est appelé pour chaque ligne reçue.
 * - [surEtat] est appelé quand l'état de la connexion change (texte à afficher).
 */
class RecepteurBluetooth(
    private val surLigne: (String) -> Unit,
    private val surEtat: (String) -> Unit,
) {
    @Volatile private var prise: BluetoothSocket? = null

    // Numéro de la connexion en cours : une ancienne connexion qui se termine
    // ne doit pas toucher à la nouvelle.
    @Volatile private var numeroConnexion = 0

    /** Se connecte à l'appareil choisi (déjà appairé au téléphone) et lit le flux. */
    @SuppressLint("MissingPermission") // la permission est vérifiée par l'écran avant l'appel
    fun connecter(appareil: BluetoothDevice) {
        deconnecter()
        val monNumero = ++numeroConnexion
        thread(name = "lecture-nmea") {
            val nom = appareil.name ?: appareil.address
            var maPrise: BluetoothSocket? = null
            try {
                surEtat("connexion à $nom…")
                maPrise = ouvrirPrise(appareil)
                if (monNumero != numeroConnexion) return@thread
                prise = maPrise
                surEtat("connecté à $nom")
                val lecteur = maPrise.inputStream.bufferedReader(Charsets.US_ASCII)
                while (monNumero == numeroConnexion) {
                    val ligne = lecteur.readLine() ?: break
                    if (ligne.isNotBlank()) surLigne(ligne.trim())
                }
                if (monNumero == numeroConnexion) surEtat("connexion coupée par le récepteur")
            } catch (erreur: IOException) {
                if (monNumero == numeroConnexion) surEtat("échec de la connexion à $nom : ${erreur.message}")
            } catch (erreur: SecurityException) {
                surEtat("permission Bluetooth refusée")
            } finally {
                fermer(maPrise)
                if (monNumero == numeroConnexion) prise = null
            }
        }
    }

    /** Coupe la connexion en cours. */
    fun deconnecter() {
        numeroConnexion++
        fermer(prise)
        prise = null
    }

    /**
     * Ouvre la liaison SPP. Si la méthode normale échoue, on essaie la variante « non sécurisée »,
     * que certains récepteurs GNSS demandent.
     */
    @SuppressLint("MissingPermission")
    private fun ouvrirPrise(appareil: BluetoothDevice): BluetoothSocket {
        val priseNormale = appareil.createRfcommSocketToServiceRecord(UUID_SPP)
        return try {
            priseNormale.connect()
            priseNormale
        } catch (premiereErreur: IOException) {
            fermer(priseNormale)
            appareil.createInsecureRfcommSocketToServiceRecord(UUID_SPP).also { it.connect() }
        }
    }

    private fun fermer(prise: BluetoothSocket?) {
        try {
            prise?.close()
        } catch (erreur: IOException) {
            // Rien à faire : la connexion est déjà fermée.
        }
    }

    companion object {
        /** Identifiant standard du « port série Bluetooth » (SPP). */
        val UUID_SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
