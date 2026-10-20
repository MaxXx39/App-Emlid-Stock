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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ch.emlidstock.noyau.coordonnees.GrilleGeoide
import ch.emlidstock.noyau.coordonnees.PositionSuisse
import ch.emlidstock.noyau.coordonnees.versPositionSuisse
import ch.emlidstock.noyau.nmea.EtatCompensation
import ch.emlidstock.noyau.nmea.PhraseNmea
import ch.emlidstock.noyau.nmea.PositionGga
import ch.emlidstock.noyau.nmea.champsNumerotes
import ch.emlidstock.noyau.nmea.libelleQualite
import ch.emlidstock.noyau.nmea.lireEtc
import ch.emlidstock.noyau.nmea.lireGga
import ch.emlidstock.noyau.nmea.lirePhraseNmea
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

/**
 * Écran de l'étape 2 : position du RX2 en coordonnées suisses.
 *
 * L'appli lit directement le flux NMEA du RX2 en Bluetooth (Emlid Flow reste ouvert pour
 * envoyer les corrections swipos au RX2), puis convertit la position :
 * - Est, Nord en MN95 ;
 * - altitude RAN95 (LHN95) avec le géoïde CHGeo2004.
 *
 * Elle affiche aussi l'état de la compensation d'inclinaison, la hauteur de canne (rappel),
 * un test d'inclinaison et un journal enregistrable.
 */
class EcranPosition : Activity() {

    private val journal = Journal()
    private val recepteur = RecepteurBluetooth(::surLigneNmea, ::surEtatBluetooth)
    private val minuterie = Handler(Looper.getMainLooper())

    // Grille du géoïde : chargée une seule fois, à la première conversion.
    private val grille: GrilleGeoide by lazy { GrilleGeoide.chgeo2004Lhn95() }

    // --- Données reçues (écrites par le fil Bluetooth, lues par l'écran) ---
    @Volatile private var derniereGga: PositionGga? = null
    @Volatile private var dernierePosition: PositionSuisse? = null
    @Volatile private var derniereCompensation: EtatCompensation? = null
    @Volatile private var derniereEtc: PhraseNmea? = null
    @Volatile private var heureDerniereNmeaMs: Long? = null
    @Volatile private var heureDerniereEtcMs: Long? = null
    @Volatile private var nbSommesFausses = 0
    @Volatile private var etatBluetooth = "non connecté"
    @Volatile private var erreurConversion: String? = null
    private val dernieresLignes = ArrayDeque<String>()

    // Point de départ du test d'inclinaison.
    private var depart: PositionSuisse? = null

    // --- Éléments de l'écran ---
    private lateinit var bandeauStatut: TextView
    private lateinit var textePosition: TextView
    private lateinit var texteCompensation: TextView
    private lateinit var texteCanne: TextView
    private lateinit var texteBluetooth: TextView
    private lateinit var texteInclinaison: TextView
    private lateinit var texteJournal: TextView
    private lateinit var texteDetails: TextView
    private lateinit var champNote: EditText

    override fun onCreate(etatSauve: Bundle?) {
        super.onCreate(etatSauve)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        construireEcran()
        journal.ajouter("INFO", "Démarrage : ${infosAppareil()}")
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
        recepteur.deconnecter()
    }

    // =====================================================================
    // Construction de l'écran (sans fichier de mise en page, tout est ici)
    // =====================================================================

    private fun construireEcran() {
        val colonne = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(24))
        }

        bandeauStatut = TextView(this).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        colonne.addView(bandeauStatut)

        colonne.addView(titre("Position MN95 / altitude RAN95"))
        textePosition = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 20f
            setTextIsSelectable(true)
        }
        colonne.addView(textePosition)

        colonne.addView(titre("Compensation d'inclinaison"))
        texteCompensation = zoneTexte()
        colonne.addView(texteCompensation)

        colonne.addView(titre("Hauteur de canne (rappel)"))
        texteCanne = zoneTexte()
        colonne.addView(texteCanne)
        colonne.addView(ligneBoutons(bouton("Modifier la hauteur de canne") { modifierHauteurCanne() }))

        colonne.addView(titre("Récepteur Bluetooth"))
        texteBluetooth = zoneTexte()
        colonne.addView(texteBluetooth)
        colonne.addView(ligneBoutons(
            bouton("Choisir le récepteur") { choisirRecepteur() },
            bouton("Reconnecter") { reconnecter() },
            bouton("Couper") { recepteur.deconnecter(); surEtatBluetooth("déconnecté") },
        ))

        colonne.addView(titre("Test d'inclinaison"))
        texteInclinaison = zoneTexte()
        colonne.addView(texteInclinaison)
        colonne.addView(ligneBoutons(bouton("Fixer le départ") { fixerDepart() }))

        colonne.addView(titre("Journal"))
        texteJournal = zoneTexte()
        colonne.addView(texteJournal)
        champNote = EditText(this).apply { hint = "Note (la position actuelle est ajoutée)" }
        colonne.addView(champNote)
        colonne.addView(ligneBoutons(
            bouton("Ajouter la note") { ajouterNote() },
            bouton("Enregistrer…") { enregistrerJournal() },
            bouton("Vider") { journal.vider() },
        ))

        colonne.addView(titre("Détails techniques"))
        texteDetails = zoneTexte()
        colonne.addView(texteDetails)

        setContentView(ScrollView(this).apply { addView(colonne) })
    }

    private fun titre(texte: String) = TextView(this).apply {
        text = texte
        textSize = 17f
        setTypeface(null, Typeface.BOLD)
        setTextColor(getColor(R.color.bleu_nuit))
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun zoneTexte() = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = 13f
        setTextIsSelectable(true)
    }

    private fun bouton(texte: String, action: () -> Unit) = Button(this).apply {
        text = texte
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun ligneBoutons(vararg boutons: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (b in boutons) {
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun dp(valeur: Int): Int = (valeur * resources.displayMetrics.density).toInt()

    // =====================================================================
    // Permissions (Bluetooth seulement)
    // =====================================================================

    private fun aLaPermission(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun demanderPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !aLaPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), CODE_PERMISSIONS)
        }
    }

    // =====================================================================
    // Bluetooth / NMEA
    // =====================================================================

    private fun choisirRecepteur() {
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
                recepteur.connecter(appareil)
            }
            .show()
    }

    private fun reconnecter() {
        val adresse = preferences().getString(CLE_DERNIER_RECEPTEUR, null)
        if (adresse == null) {
            choisirRecepteur()
            return
        }
        val adaptateur = adaptateurBluetooth() ?: return
        recepteur.connecter(adaptateur.getRemoteDevice(adresse))
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

    /** Appelé (depuis le fil Bluetooth) pour chaque ligne reçue du récepteur. */
    private fun surLigneNmea(ligne: String) {
        heureDerniereNmeaMs = SystemClock.elapsedRealtime()
        journal.ajouter("NMEA", ligne)
        synchronized(dernieresLignes) {
            dernieresLignes.addLast(ligne)
            while (dernieresLignes.size > 8) dernieresLignes.removeFirst()
        }
        val phrase = lirePhraseNmea(ligne) ?: return
        if (phrase.sommeOk == false) {
            nbSommesFausses++
            return
        }
        when (phrase.type) {
            "GGA" -> lireGga(phrase)?.let { gga ->
                derniereGga = gga
                dernierePosition = convertir(gga)
            }
            "ETC" -> {
                derniereEtc = phrase
                derniereCompensation = lireEtc(phrase)
                heureDerniereEtcMs = SystemClock.elapsedRealtime()
            }
        }
    }

    /** Convertit un GGA en MN95 / RAN95, sans jamais faire planter l'appli. */
    private fun convertir(gga: PositionGga): PositionSuisse? =
        try {
            erreurConversion = null
            versPositionSuisse(gga, grille)
        } catch (erreur: Exception) {
            erreurConversion = "conversion impossible : ${erreur.message}"
            null
        }

    /** Appelé (depuis le fil Bluetooth) quand l'état de la connexion change. */
    private fun surEtatBluetooth(etat: String) {
        etatBluetooth = etat
        journal.ajouter("INFO", "Bluetooth : $etat")
    }

    // =====================================================================
    // Hauteur de canne (rappel seulement, n'entre dans aucun calcul)
    // =====================================================================

    private fun modifierHauteurCanne() {
        val champ = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(preferences().getString(CLE_HAUTEUR_CANNE, "") ?: "")
            hint = "ex. 1.800"
        }
        AlertDialog.Builder(this)
            .setTitle("Hauteur de canne réglée dans Emlid Flow (m)")
            .setView(champ)
            .setPositiveButton("Enregistrer") { _, _ ->
                val valeur = champ.text.toString().replace(',', '.').trim().toDoubleOrNull()
                if (valeur == null || valeur <= 0 || valeur > 10) {
                    message("Hauteur de canne non valable : tapez par exemple 1.800")
                } else {
                    preferences().edit().putString(CLE_HAUTEUR_CANNE, nombre(valeur, 3)).apply()
                    journal.ajouter("INFO", "Hauteur de canne (rappel) : ${nombre(valeur, 3)} m")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // =====================================================================
    // Test d'inclinaison
    // =====================================================================

    private fun fixerDepart() {
        val position = dernierePosition
        if (position == null) {
            message("Pas encore de position : connectez le récepteur.")
            return
        }
        depart = position
        journal.ajouter("NOTE", "Départ du test d'inclinaison : ${positionEnTexte(position)}")
        message("Départ fixé. Gardez la pointe de la canne immobile et inclinez-la.")
    }

    // =====================================================================
    // Journal
    // =====================================================================

    private fun ajouterNote() {
        val note = champNote.text.toString().trim()
        if (note.isEmpty()) return
        val position = dernierePosition?.let { positionEnTexte(it) } ?: "pas de position"
        journal.ajouter("NOTE", "$note | $position | ${libelleQualite(derniereGga?.qualite)}")
        champNote.setText("")
        message("Note ajoutée au journal.")
    }

    @Suppress("DEPRECATION") // méthode simple et suffisante pour un écran sans bibliothèque
    private fun enregistrerJournal() {
        val date = SimpleDateFormat("yyyy-MM-dd_HH'h'mm", Locale.ROOT).format(Date())
        val intention = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "journal-rx2_$date.txt")
        }
        startActivityForResult(intention, CODE_ENREGISTRER)
    }

    @Deprecated("Méthode Android classique, suffisante ici")
    override fun onActivityResult(code: Int, resultat: Int, donnees: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(code, resultat, donnees)
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
    // Rafraîchissement de l'écran (2 fois par seconde)
    // =====================================================================

    private val rafraichir = object : Runnable {
        override fun run() {
            mettreAJourEcran()
            minuterie.postDelayed(this, 500)
        }
    }

    private fun mettreAJourEcran() {
        val maintenant = SystemClock.elapsedRealtime()
        val gga = derniereGga
        val position = dernierePosition
        val nmeaRecente = heureDerniereNmeaMs?.let { maintenant - it < 5_000 } == true

        // Bandeau : statut RTK lu dans le GGA.
        when {
            !nmeaRecente || gga == null -> {
                bandeauStatut.text = "Pas de données du récepteur"
                bandeauStatut.setBackgroundColor(Color.GRAY)
            }
            gga?.estRtkFixe == true -> {
                bandeauStatut.text = "RTK FIXE"
                bandeauStatut.setBackgroundColor(Color.rgb(46, 125, 50))
            }
            gga?.qualite == 5 -> {
                bandeauStatut.text = "RTK FLOTTANT : point refusé"
                bandeauStatut.setBackgroundColor(getColor(R.color.ambre))
            }
            else -> {
                bandeauStatut.text = "${libelleQualite(gga?.qualite)} : point refusé"
                bandeauStatut.setBackgroundColor(Color.rgb(198, 40, 40))
            }
        }

        // Position MN95 / RAN95
        textePosition.text = when {
            position != null ->
                "E  ${nombre(position.est, 3)}\nN  ${nombre(position.nord, 3)}\nH  ${nombre(position.altitude, 3)}"
            erreurConversion != null -> erreurConversion
            gga != null && gga.latitudeDeg != null -> "Position hors de Suisse"
            else -> "En attente du récepteur…"
        }

        // Compensation
        val compensation = derniereCompensation
        val etcRecent = heureDerniereEtcMs?.let { maintenant - it < 5_000 } == true
        texteCompensation.text = when {
            compensation == null || !etcRecent -> "Aucun message ETC reçu"
            compensation.estActive ->
                "ACTIVE, inclinaison ${nombre(compensation.inclinaisonDeg, 1)}°"
            else -> "pas active (état ${compensation.etat ?: "?"})\n" +
                "→ bougez un peu la canne pour l'initialiser (voir Emlid Flow)"
        }

        texteCanne.text = "${hauteurCanneEnTexte()} — à vérifier dans Emlid Flow\n" +
            "(rappel seulement : le RX2 l'applique déjà à la position)"

        texteBluetooth.text = "État : $etatBluetooth\nDernière donnée il y a ${age(heureDerniereNmeaMs, maintenant)}"

        // Test d'inclinaison : écarts depuis le départ, en centimètres.
        val dep = depart
        texteInclinaison.text = if (dep == null || position == null) {
            "Pointe de canne sur un point fixe, canne droite : touchez « Fixer le départ »,\n" +
                "puis inclinez la canne sans bouger la pointe."
        } else {
            val deltaEst = (position.est - dep.est) * 100
            val deltaNord = (position.nord - dep.nord) * 100
            val deltaHaut = (position.altitude - dep.altitude) * 100
            "Écart depuis le départ :\n" +
                "  plan ${nombre(hypot(deltaEst, deltaNord), 1)} cm  (E ${nombre(deltaEst, 1)} / N ${nombre(deltaNord, 1)})\n" +
                "  hauteur ${nombre(deltaHaut, 1)} cm"
        }

        texteJournal.text = "${journal.nombreDeLignes()} lignes en mémoire"

        // Détails techniques
        val etc = derniereEtc
        val lignes = synchronized(dernieresLignes) { dernieresLignes.joinToString("\n") }
        texteDetails.text = """
            |GGA : ${if (gga == null) "aucun" else decrireGga(gga)}
            |ETC : ${etc?.let { champsNumerotes(it) } ?: "aucun"}
            |Sommes de contrôle fausses : $nbSommesFausses
            |Dernières lignes :
            |$lignes
            """.trimMargin()
    }

    private fun decrireGga(gga: PositionGga): String =
        "${libelleQualite(gga.qualite)}, ${gga.nbSatellites ?: "—"} satellites, HDOP ${nombre(gga.hdop, 1)}, " +
            "âge corr. ${nombre(gga.ageCorrectionsS, 1)} s, station ${gga.stationReference.ifEmpty { "—" }}\n" +
            "      lat ${nombre(gga.latitudeDeg, 9)}  lon ${nombre(gga.longitudeDeg, 9)}  " +
            "h ellipsoïdale ${nombre(gga.hauteurEllipsoidaleM, 3)} m"

    // =====================================================================
    // Petits outils
    // =====================================================================

    private fun preferences() = getSharedPreferences("reglages", MODE_PRIVATE)

    private fun hauteurCanneEnTexte(): String =
        preferences().getString(CLE_HAUTEUR_CANNE, null)?.let { "$it m" } ?: "non saisie"

    private fun positionEnTexte(p: PositionSuisse): String =
        "E ${nombre(p.est, 3)} N ${nombre(p.nord, 3)} H ${nombre(p.altitude, 3)}"

    private fun nombre(valeur: Double?, decimales: Int): String =
        if (valeur == null) "—" else String.format(Locale.ROOT, "%.${decimales}f", valeur)

    private fun age(heureMs: Long?, maintenant: Long): String =
        if (heureMs == null) "—" else "${nombre((maintenant - heureMs) / 1000.0, 1)} s"

    private fun infosAppareil(): String {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (erreur: Exception) {
            "?"
        }
        return "Emlid Stock $version, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    private fun message(texte: String) {
        runOnUiThread { Toast.makeText(this, texte, Toast.LENGTH_LONG).show() }
        journal.ajouter("INFO", texte)
    }

    companion object {
        private const val CODE_PERMISSIONS = 1
        private const val CODE_ENREGISTRER = 2
        private const val CLE_DERNIER_RECEPTEUR = "dernier_recepteur"
        private const val CLE_HAUTEUR_CANNE = "hauteur_canne"
    }
}
