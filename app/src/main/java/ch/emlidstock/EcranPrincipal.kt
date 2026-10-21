package ch.emlidstock

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.http.HttpResponseCache
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import ch.emlidstock.noyau.leve.Stock
import ch.emlidstock.noyau.nmea.libelleQualite
import ch.emlidstock.noyau.volume.ResultatVolumeStock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Un écran de l'appli (accueil, levé, diagnostic), affiché sous les deux bandeaux d'état. */
interface Vue {
    val racine: View

    /** Mise à jour des valeurs qui changent (appelée 2 fois par seconde). */
    fun rafraichir()
}

/**
 * Activité unique de l'appli. Elle garde la connexion au récepteur et les données,
 * affiche en haut les bandeaux « RTK » et « Inclinaison », et en dessous l'écran en cours :
 * accueil (chantiers, stocks), levé d'un stock, ou diagnostic.
 */
class EcranPrincipal : Activity() {

    val journal = Journal()
    val recepteur = EtatRecepteur(journal)
    lateinit var depot: Depot
        private set
    val ui by lazy { Ui(this) }

    private val minuterie = Handler(Looper.getMainLooper())
    private lateinit var bandeauRtk: TextView
    private lateinit var bandeauInclinaison: TextView
    private lateinit var contenu: FrameLayout
    private var vueActuelle: Vue? = null

    override fun onCreate(etatSauve: Bundle?) {
        super.onCreate(etatSauve)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        journal.ajouter("INFO", "Démarrage : ${infosAppareil()}")

        // Cache internet sur le téléphone (50 Mo) : les tuiles swisstopo déjà vues se rechargent sans réseau.
        try {
            HttpResponseCache.install(File(cacheDir, "tuiles"), 50L * 1024 * 1024)
        } catch (erreur: Exception) {
            journal.ajouter("INFO", "Cache des tuiles indisponible : ${erreur.message}")
        }

        depot = Depot(filesDir)
        depot.charger()?.let { message(it) }

        bandeauRtk = bandeau()
        bandeauInclinaison = bandeau()
        contenu = FrameLayout(this)
        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bandeauRtk)
            addView(bandeauInclinaison)
            addView(contenu, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(racine)

        afficherAccueil()
        demanderPermissions()
    }

    override fun onResume() {
        super.onResume()
        minuterie.post(rafraichir)
    }

    override fun onPause() {
        super.onPause()
        minuterie.removeCallbacks(rafraichir)
    }

    override fun onDestroy() {
        super.onDestroy()
        recepteur.bluetooth.deconnecter()
    }

    @Deprecated("Méthode Android classique, suffisante ici")
    override fun onBackPressed() {
        when (val vue = vueActuelle) {
            is VueAccueil -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
            is VueControle -> vue.retour()
            else -> afficherAccueil()
        }
    }

    // =====================================================================
    // Navigation entre les écrans
    // =====================================================================

    fun afficherAccueil() = afficher(VueAccueil(this))

    fun afficherLeve(stock: Stock, volume: ResultatVolumeStock? = null) = afficher(VueLeve(this, stock, volume))

    fun afficherControle(stock: Stock, volume: ResultatVolumeStock) = afficher(VueControle(this, stock, volume))

    fun afficherDiagnostic() = afficher(VueDiagnostic(this))

    private fun afficher(vue: Vue) {
        vueActuelle = vue
        contenu.removeAllViews()
        contenu.addView(vue.racine)
        vue.rafraichir()
    }

    // =====================================================================
    // Bandeaux d'état (toujours visibles)
    // =====================================================================

    private fun bandeau() = TextView(this).apply {
        textSize = 18f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
    }

    private val rafraichir = object : Runnable {
        override fun run() {
            mettreAJourBandeaux()
            vueActuelle?.rafraichir()
            minuterie.postDelayed(this, 500)
        }
    }

    private fun mettreAJourBandeaux() {
        val gga = recepteur.derniereGga
        val ggaRecente = recepteur.age(recepteur.heureGgaMs)?.let { it < 5_000 } == true
        when {
            !ggaRecente || gga == null -> colorer(bandeauRtk, "RTK : pas de données du récepteur", Color.GRAY)
            gga.estRtkFixe -> colorer(bandeauRtk, "RTK FIXE", VERT)
            gga.qualite == 5 -> colorer(bandeauRtk, "RTK FLOTTANT", getColor(R.color.ambre))
            else -> colorer(bandeauRtk, "RTK : ${libelleQualite(gga.qualite)}", ROUGE)
        }

        val compensation = recepteur.derniereCompensation
        val etcRecent = recepteur.age(recepteur.heureEtcMs)?.let { it < 5_000 } == true
        when {
            !etcRecent || compensation == null -> colorer(bandeauInclinaison, "Inclinaison : inconnue", Color.GRAY)
            compensation.estActive -> colorer(
                bandeauInclinaison, "Inclinaison PRÊTE (${nombre(compensation.inclinaisonDeg, 1)}°)", VERT,
            )
            else -> colorer(bandeauInclinaison, "Inclinaison NON PRÊTE : bougez la canne", ROUGE)
        }
    }

    private fun colorer(bandeau: TextView, texte: String, couleur: Int) {
        bandeau.text = texte
        bandeau.setBackgroundColor(couleur)
    }

    /**
     * Position actuelle à afficher (E, N, H), avec un avertissement si elle n'est pas
     * celle du bout de canne (inclinaison pas prête : le RX2 envoie alors la position de l'antenne).
     */
    fun texteEtCouleurPosition(): Pair<String, Int> {
        val position = recepteur.dernierePosition
        val recente = recepteur.age(recepteur.heureGgaMs)?.let { it < 5_000 } == true
        val gga = recepteur.derniereGga
        return when {
            position != null && recente -> {
                val texte = "E  ${nombre(position.est, 3)}\nN  ${nombre(position.nord, 3)}\nH  ${nombre(position.altitude, 3)}"
                if (recepteur.derniereCompensation?.estActive == true) {
                    Pair(texte, Color.BLACK)
                } else {
                    Pair("$texte\n(antenne, pas le bout de canne)", Color.GRAY)
                }
            }
            recepteur.erreurConversion != null -> Pair(recepteur.erreurConversion ?: "", ROUGE)
            gga?.latitudeDeg != null && recente -> Pair("Position hors de Suisse", ROUGE)
            else -> Pair("En attente du récepteur…", Color.GRAY)
        }
    }

    // =====================================================================
    // Permissions et Bluetooth
    // =====================================================================

    private fun aLaPermission(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun demanderPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !aLaPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), CODE_PERMISSIONS)
        }
    }

    fun choisirRecepteur() {
        val adaptateur = adaptateurBluetooth() ?: return
        @SuppressLint("MissingPermission")
        val appareils: List<BluetoothDevice> = adaptateur.bondedDevices?.toList().orEmpty()
        if (appareils.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Aucun appareil appairé")
                .setMessage("Appairez d'abord le RX2 dans les réglages Bluetooth du téléphone, puis revenez ici.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        @SuppressLint("MissingPermission")
        val noms = appareils.map { "${it.name ?: "(sans nom)"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choisir le récepteur")
            .setItems(noms) { _, numero ->
                val appareil = appareils[numero]
                preferences().edit().putString(CLE_DERNIER_RECEPTEUR, appareil.address).apply()
                recepteur.bluetooth.connecter(appareil)
            }
            .show()
    }

    fun reconnecter() {
        val adresse = preferences().getString(CLE_DERNIER_RECEPTEUR, null)
        if (adresse == null) {
            choisirRecepteur()
            return
        }
        val adaptateur = adaptateurBluetooth() ?: return
        recepteur.bluetooth.connecter(adaptateur.getRemoteDevice(adresse))
    }

    fun couperRecepteur() {
        recepteur.bluetooth.deconnecter()
        journal.ajouter("INFO", "Bluetooth : coupé par l'utilisateur")
    }

    /** Renvoie l'adaptateur Bluetooth prêt à l'emploi, ou null (avec un message) s'il ne l'est pas. */
    private fun adaptateurBluetooth(): BluetoothAdapter? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !aLaPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), CODE_PERMISSIONS)
            return null
        }
        val adaptateur = getSystemService(BluetoothManager::class.java)?.adapter
        if (adaptateur == null) {
            message("Ce téléphone n'a pas de Bluetooth.")
            return null
        }
        if (!adaptateur.isEnabled) {
            message("Activez le Bluetooth du téléphone.")
            return null
        }
        return adaptateur
    }

    // =====================================================================
    // Hauteur de canne (rappel seulement, n'entre dans aucun calcul)
    // =====================================================================

    fun hauteurCanneEnTexte(): String =
        preferences().getString(CLE_HAUTEUR_CANNE, null)?.let { "$it m" } ?: "non saisie"

    fun modifierHauteurCanne() {
        val champ = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(preferences().getString(CLE_HAUTEUR_CANNE, "") ?: "")
            hint = "ex. 1.850"
        }
        AlertDialog.Builder(this)
            .setTitle("Hauteur de canne réglée dans Emlid Flow (m)")
            .setView(champ)
            .setPositiveButton("Enregistrer") { _, _ ->
                val valeur = champ.text.toString().replace(',', '.').trim().toDoubleOrNull()
                if (valeur == null || valeur <= 0 || valeur > 10) {
                    message("Hauteur de canne non valable : tapez par exemple 1.850")
                } else {
                    preferences().edit().putString(CLE_HAUTEUR_CANNE, nombre(valeur, 3)).apply()
                    journal.ajouter("INFO", "Hauteur de canne (rappel) : ${nombre(valeur, 3)} m")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // =====================================================================
    // Saisie de texte (noms de chantier, de stock)
    // =====================================================================

    /** Ouvre une fenêtre pour taper un nom ; [quandValide] reçoit le texte (non vide). */
    fun demanderNom(titre: String, valeurInitiale: String, quandValide: (String) -> Unit) {
        val champ = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(valeurInitiale)
            setSelection(valeurInitiale.length)
        }
        AlertDialog.Builder(this)
            .setTitle(titre)
            .setView(champ)
            .setPositiveButton("OK") { _, _ ->
                val nom = champ.text.toString().trim()
                if (nom.isEmpty()) message("Le nom ne peut pas être vide.") else quandValide(nom)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Demande une confirmation avant une action (suppression…). */
    fun confirmer(titre: String, texte: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(titre)
            .setMessage(texte)
            .setPositiveButton("Oui") { _, _ -> action() }
            .setNegativeButton("Non", null)
            .show()
    }

    // =====================================================================
    // Enregistrement des levés et du journal
    // =====================================================================

    /** Enregistre chantiers et stocks ; affiche un message si l'écriture échoue. */
    fun enregistrerLeves(): Boolean =
        try {
            depot.enregistrer()
            true
        } catch (erreur: Exception) {
            message("ATTENTION : enregistrement impossible (${erreur.message})")
            false
        }

    @Suppress("DEPRECATION") // méthode simple et suffisante pour un écran sans bibliothèque
    fun enregistrerJournal() {
        val date = SimpleDateFormat("yyyy-MM-dd_HH'h'mm", Locale.ROOT).format(Date())
        val intention = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "journal-rx2_$date.txt")
        }
        startActivityForResult(intention, CODE_ENREGISTRER)
    }

    // Contenu en attente d'être écrit dans le fichier choisi par l'utilisateur (export).
    private var contenuAExporter: ByteArray? = null

    /** Demande où enregistrer le fichier (Téléchargements, Google Drive…), puis y écrit [contenu]. */
    @Suppress("DEPRECATION")
    fun exporterFichier(nomFichier: String, type: String, contenu: ByteArray) {
        contenuAExporter = contenu
        val intention = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = type
            putExtra(Intent.EXTRA_TITLE, nomFichier)
        }
        startActivityForResult(intention, CODE_EXPORTER)
    }

    @Deprecated("Méthode Android classique, suffisante ici")
    override fun onActivityResult(code: Int, resultat: Int, donnees: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(code, resultat, donnees)
        if (code == CODE_EXPORTER) {
            val contenu = contenuAExporter
            contenuAExporter = null
            val adresse = donnees?.data
            if (resultat != RESULT_OK || adresse == null || contenu == null) return
            try {
                val flux = contentResolver.openOutputStream(adresse) ?: error("fichier impossible à ouvrir")
                flux.use { it.write(contenu) }
                message("Fichier exporté.")
            } catch (erreur: Exception) {
                message("Échec de l'export : ${erreur.message}")
            }
            return
        }
        if (code != CODE_ENREGISTRER || resultat != RESULT_OK) return
        val adresse = donnees?.data ?: return
        try {
            val flux = contentResolver.openOutputStream(adresse) ?: error("fichier impossible à ouvrir")
            flux.bufferedWriter(Charsets.UTF_8).use { fichier ->
                fichier.write("=== Journal RX2 / Emlid Stock ===\n")
                fichier.write("${infosAppareil()}\n")
                fichier.write("Hauteur de canne (rappel) : ${hauteurCanneEnTexte()}\n\n")
                fichier.write(journal.texteComplet())
                fichier.write("\n")
            }
            message("Journal enregistré (${journal.nombreDeLignes()} lignes).")
        } catch (erreur: Exception) {
            message("Échec de l'enregistrement : ${erreur.message}")
        }
    }

    // =====================================================================
    // Petits outils
    // =====================================================================

    fun preferences() = getSharedPreferences("reglages", MODE_PRIVATE)

    fun infosAppareil(): String {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (erreur: Exception) {
            "?"
        }
        return "Emlid Stock $version, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    fun message(texte: String) {
        runOnUiThread { Toast.makeText(this, texte, Toast.LENGTH_LONG).show() }
        journal.ajouter("INFO", texte)
    }

    companion object {
        val VERT = Color.rgb(46, 125, 50)
        val ROUGE = Color.rgb(198, 40, 40)
        private const val CODE_PERMISSIONS = 1
        private const val CODE_ENREGISTRER = 2
        private const val CODE_EXPORTER = 3
        private const val CLE_DERNIER_RECEPTEUR = "dernier_recepteur"
        private const val CLE_HAUTEUR_CANNE = "hauteur_canne"
    }
}
