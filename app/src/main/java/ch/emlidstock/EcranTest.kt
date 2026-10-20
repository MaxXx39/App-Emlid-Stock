package ch.emlidstock

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ch.emlidstock.noyau.nmea.PhraseNmea
import ch.emlidstock.noyau.nmea.PositionGga
import ch.emlidstock.noyau.nmea.champsNumerotes
import ch.emlidstock.noyau.nmea.libelleQualite
import ch.emlidstock.noyau.nmea.lireGga
import ch.emlidstock.noyau.nmea.lirePhraseNmea
import ch.emlidstock.noyau.verification.ecartsAltitude
import ch.emlidstock.noyau.verification.fluxEnParallele
import ch.emlidstock.noyau.verification.interpreterAltitudeMock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Écran unique du prototype de test terrain (étape 1 du cahier des charges).
 *
 * Il affiche en direct :
 * 1. la position fournie par Emlid Flow (mock location) ;
 * 2. le flux NMEA lu directement en Bluetooth (GGA = position et statut RTK, ETC = inclinaison) ;
 * 3. l'état des 5 points à vérifier avant de construire la suite ;
 * 4. un journal de tout ce qui est reçu, à enregistrer dans un fichier.
 */
class EcranTest : Activity() {

    private val journal = Journal()
    private val recepteur = RecepteurBluetooth(::surLigneNmea, ::surEtatBluetooth)
    private val minuterie = Handler(Looper.getMainLooper())

    // --- Données reçues (mises à jour par le Bluetooth et la position, lues par l'écran) ---
    @Volatile private var dernierePosition: Location? = null
    @Volatile private var heureDernierePositionMs: Long? = null
    @Volatile private var nbPositions = 0

    @Volatile private var derniereGga: PositionGga? = null
    @Volatile private var derniereEtc: PhraseNmea? = null
    @Volatile private var heureDerniereEtcMs: Long? = null
    @Volatile private var heureDerniereNmeaMs: Long? = null
    @Volatile private var nbSommesFausses = 0
    @Volatile private var etatBluetooth = "non connecté"
    private val compteursPhrases = ConcurrentHashMap<String, Int>()
    private val dernieresLignes = ArrayDeque<String>()

    // Point de départ du test d'inclinaison (canne pointe fixe, on l'incline).
    private var departMock: Location? = null
    private var departGga: PositionGga? = null

    // --- Éléments de l'écran ---
    private lateinit var bandeauStatut: TextView
    private lateinit var textePosition: TextView
    private lateinit var texteBluetooth: TextView
    private lateinit var texteNmea: TextView
    private lateinit var texteVerifications: TextView
    private lateinit var texteJournal: TextView
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
        getSystemService(LocationManager::class.java)?.removeUpdates(ecouteurPosition)
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

        colonne.addView(titre("1. Position Emlid Flow (mock location)"))
        textePosition = zoneTexte()
        colonne.addView(textePosition)

        colonne.addView(titre("2. Flux NMEA Bluetooth (RX2)"))
        texteBluetooth = zoneTexte()
        colonne.addView(texteBluetooth)
        colonne.addView(ligneBoutons(
            bouton("Choisir le récepteur") { choisirRecepteur() },
            bouton("Reconnecter") { reconnecter() },
            bouton("Couper") { recepteur.deconnecter(); surEtatBluetooth("déconnecté") },
        ))
        texteNmea = zoneTexte()
        colonne.addView(texteNmea)

        colonne.addView(titre("3. Points à vérifier"))
        texteVerifications = zoneTexte()
        colonne.addView(texteVerifications)
        colonne.addView(ligneBoutons(
            bouton("Fixer le départ (test inclinaison)") { fixerDepartInclinaison() },
        ))

        colonne.addView(titre("4. Journal"))
        texteJournal = zoneTexte()
        colonne.addView(texteJournal)
        champNote = EditText(this).apply { hint = "Note (ex. : canne inclinée 30° vers le nord)" }
        colonne.addView(champNote)
        colonne.addView(ligneBoutons(
            bouton("Ajouter la note") { ajouterNote() },
            bouton("Enregistrer…") { enregistrerJournal() },
            bouton("Vider") { journal.vider() },
        ))

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
        textSize = 12f
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
    // Permissions
    // =====================================================================

    private fun permissionsNecessaires(): List<String> {
        val liste = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) liste.add(Manifest.permission.BLUETOOTH_CONNECT)
        return liste
    }

    private fun aLaPermission(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun demanderPermissions() {
        val manquantes = permissionsNecessaires().filterNot { aLaPermission(it) }
        if (manquantes.isEmpty()) {
            demarrerPosition()
        } else {
            requestPermissions(manquantes.toTypedArray(), CODE_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, resultats: IntArray) {
        super.onRequestPermissionsResult(code, permissions, resultats)
        if (code != CODE_PERMISSIONS) return
        if (aLaPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            demarrerPosition()
        } else {
            message("Sans la permission « position précise », l'appli ne peut pas lire la position d'Emlid Flow.")
        }
    }

    // =====================================================================
    // 1. Position (mock location d'Emlid Flow)
    // =====================================================================

    private val ecouteurPosition = object : LocationListener {
        override fun onLocationChanged(position: Location) {
            dernierePosition = position
            heureDernierePositionMs = SystemClock.elapsedRealtime()
            nbPositions++
            journal.ajouter("POS", decrireLocationPourJournal(position))
        }

        override fun onProviderEnabled(fournisseur: String) {
            journal.ajouter("INFO", "Localisation activée ($fournisseur)")
        }

        override fun onProviderDisabled(fournisseur: String) {
            journal.ajouter("INFO", "Localisation désactivée ($fournisseur)")
        }

        @Deprecated("Ancienne méthode Android, gardée pour les vieux téléphones")
        override fun onStatusChanged(fournisseur: String?, statut: Int, extras: Bundle?) {
            // Rien à faire.
        }
    }

    @SuppressLint("MissingPermission") // vérifiée juste avant
    private fun demarrerPosition() {
        if (!aLaPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val gestionnaire = getSystemService(LocationManager::class.java) ?: return
        try {
            if (!gestionnaire.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                message("La localisation du téléphone est désactivée : activez-la dans les réglages.")
            }
            gestionnaire.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 0L, 0f, ecouteurPosition, Looper.getMainLooper(),
            )
            journal.ajouter("INFO", "Écoute de la position (fournisseur GPS) démarrée")
        } catch (erreur: Exception) {
            message("Impossible d'écouter la position : ${erreur.message}")
        }
    }

    private fun estPositionFictive(position: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            position.isMock
        } else {
            @Suppress("DEPRECATION")
            position.isFromMockProvider
        }

    /** Les « extras » : informations supplémentaires qu'Emlid Flow ajoute peut-être à la position. */
    private fun extrasEnTexte(position: Location): String {
        val extras = position.extras ?: return "(aucun)"
        if (extras.isEmpty) return "(aucun)"
        return extras.keySet().sorted().joinToString(", ") { cle ->
            @Suppress("DEPRECATION")
            "$cle=${extras.get(cle)}"
        }
    }

    private fun decrireLocationPourJournal(p: Location): String {
        val altMsl = if (Build.VERSION.SDK_INT >= 34 && p.hasMslAltitude()) nombre(p.mslAltitudeMeters, 3) else "—"
        return "lat=${nombre(p.latitude, 9)} lon=${nombre(p.longitude, 9)} alt=${nombre(p.altitude, 3)} " +
            "altMSL=$altMsl precH=${p.accuracy} precV=${precisionVerticale(p)} fictive=${estPositionFictive(p)} " +
            "fournisseur=${p.provider} heureGNSS=${heureUtc(p.time)} extras=[${extrasEnTexte(p)}]"
    }

    private fun precisionVerticale(p: Location): String =
        if (p.hasVerticalAccuracy()) nombre(p.verticalAccuracyMeters.toDouble(), 3) else "—"

    // =====================================================================
    // 2. Bluetooth / NMEA
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
                getPreferences(MODE_PRIVATE).edit().putString(CLE_DERNIER_RECEPTEUR, appareil.address).apply()
                recepteur.connecter(appareil)
            }
            .show()
    }

    private fun reconnecter() {
        val adresse = getPreferences(MODE_PRIVATE).getString(CLE_DERNIER_RECEPTEUR, null)
        if (adresse == null) {
            choisirRecepteur()
            return
        }
        val adaptateur = adaptateurBluetooth() ?: return
        recepteur.connecter(adaptateur.getRemoteDevice(adresse))
    }

    /** Renvoie l'adaptateur Bluetooth prêt à l'emploi, ou null (avec un message) s'il ne l'est pas. */
    private fun adaptateurBluetooth(): android.bluetooth.BluetoothAdapter? {
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
            while (dernieresLignes.size > 12) dernieresLignes.removeFirst()
        }
        val phrase = lirePhraseNmea(ligne) ?: return
        compteursPhrases.merge(phrase.adresse, 1, Int::plus)
        if (phrase.sommeOk == false) {
            nbSommesFausses++
            return
        }
        when (phrase.type) {
            "GGA" -> lireGga(phrase)?.let { derniereGga = it }
            "ETC" -> {
                derniereEtc = phrase
                heureDerniereEtcMs = SystemClock.elapsedRealtime()
            }
        }
    }

    /** Appelé (depuis le fil Bluetooth) quand l'état de la connexion change. */
    private fun surEtatBluetooth(etat: String) {
        etatBluetooth = etat
        journal.ajouter("INFO", "Bluetooth : $etat")
    }

    // =====================================================================
    // 3. Test d'inclinaison
    // =====================================================================

    private fun fixerDepartInclinaison() {
        departMock = dernierePosition
        departGga = derniereGga
        journal.ajouter("NOTE", "Départ du test d'inclinaison fixé")
        message("Départ fixé. Gardez la pointe de la canne immobile et inclinez-la.")
    }

    /** Écart horizontal en centimètres entre deux positions (calcul fourni par Android). */
    private fun ecartHorizontalCm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val resultat = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, resultat)
        return resultat[0] * 100.0
    }

    private fun texteInclinaison(): String {
        val dm = departMock
        val pm = dernierePosition
        val partieMock = if (dm != null && pm != null) {
            "mock : ${nombre(ecartHorizontalCm(dm.latitude, dm.longitude, pm.latitude, pm.longitude), 1)} cm en plan, " +
                "${nombre((pm.altitude - dm.altitude) * 100, 1)} cm en hauteur"
        } else {
            "mock : —"
        }
        // Valeurs recopiées dans des variables locales pour pouvoir les utiliser sans risque.
        val latDepart = departGga?.latitudeDeg
        val lonDepart = departGga?.longitudeDeg
        val altDepart = departGga?.altitudeGeoideRecepteurM
        val latActuelle = derniereGga?.latitudeDeg
        val lonActuelle = derniereGga?.longitudeDeg
        val altActuelle = derniereGga?.altitudeGeoideRecepteurM
        val partieGga = if (latDepart != null && lonDepart != null && latActuelle != null && lonActuelle != null) {
            val ecartHauteur = if (altDepart != null && altActuelle != null) {
                nombre((altActuelle - altDepart) * 100, 1)
            } else {
                "—"
            }
            "GGA  : ${nombre(ecartHorizontalCm(latDepart, lonDepart, latActuelle, lonActuelle), 1)} cm en plan, " +
                "$ecartHauteur cm en hauteur"
        } else {
            "GGA  : —"
        }
        return "$partieMock\n     $partieGga"
    }

    // =====================================================================
    // 4. Journal
    // =====================================================================

    private fun ajouterNote() {
        val note = champNote.text.toString().trim()
        if (note.isEmpty()) return
        journal.ajouter("NOTE", note)
        champNote.setText("")
        message("Note ajoutée au journal.")
    }

    @Suppress("DEPRECATION") // méthode simple et suffisante pour un écran sans bibliothèque
    private fun enregistrerJournal() {
        val date = SimpleDateFormat("yyyy-MM-dd_HH'h'mm", Locale.ROOT).format(Date())
        val intention = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "test-rx2_$date.txt")
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
            contentResolver.openOutputStream(adresse)?.bufferedWriter(Charsets.UTF_8)?.use { fichier ->
                fichier.write("=== Test RX2 / Emlid Stock ===\n")
                fichier.write("${infosAppareil()}\n\n")
                fichier.write("--- Points à vérifier (au moment de l'enregistrement) ---\n")
                fichier.write(texteVerifications.text.toString())
                fichier.write("\n\n--- Journal ---\n")
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

        // Bandeau : statut RTK lu dans le GGA du flux NMEA.
        val nmeaRecente = heureDerniereNmeaMs?.let { maintenant - it < 5_000 } == true
        when {
            !nmeaRecente || gga == null -> {
                bandeauStatut.text = "Statut RTK : inconnu (pas de NMEA)"
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

        // 1. Position mock
        textePosition.text = if (position == null) {
            "Aucune position reçue.\n" +
                "Vérifiez : Emlid Flow connecté au RX2, « position fictive » activée,\n" +
                "et Emlid Flow choisi comme appli de position fictive (options pour développeurs)."
        } else {
            val altMsl = if (Build.VERSION.SDK_INT >= 34 && position.hasMslAltitude()) {
                nombre(position.mslAltitudeMeters, 3)
            } else {
                "—"
            }
            """
            |Position fictive (Emlid Flow) : ${if (estPositionFictive(position)) "OUI" else "NON ⚠ (GPS du téléphone ?)"}
            |Latitude   : ${nombre(position.latitude, 9)}°
            |Longitude  : ${nombre(position.longitude, 9)}°
            |Altitude   : ${nombre(position.altitude, 3)} m
            |Alt. MSL   : $altMsl m
            |Précision  : H ${nombre(position.accuracy.toDouble(), 3)} m / V ${precisionVerticale(position)} m
            |Heure GNSS : ${heureUtc(position.time)} UTC
            |Reçue il y a ${age(heureDernierePositionMs, maintenant)} — $nbPositions positions
            |Fournisseur : ${position.provider}
            |Extras : ${extrasEnTexte(position)}
            """.trimMargin()
        }

        // 2. Bluetooth et NMEA
        texteBluetooth.text = "État : $etatBluetooth\nDernière phrase il y a ${age(heureDerniereNmeaMs, maintenant)}"
        val compteurs = compteursPhrases.entries.sortedBy { it.key }.joinToString("  ") { "${it.key}:${it.value}" }
        val lignes = synchronized(dernieresLignes) { dernieresLignes.joinToString("\n") }
        val etc = derniereEtc
        texteNmea.text = """
            |Phrases reçues : ${compteurs.ifEmpty { "aucune" }}
            |Sommes de contrôle fausses : $nbSommesFausses
            |
            |GGA : ${if (gga == null) "aucun" else decrireGga(gga)}
            |
            |ETC (inclinaison) reçu il y a ${age(heureDerniereEtcMs, maintenant)} :
            |${etc?.texte ?: "aucun"}
            |${etc?.let { champsNumerotes(it) } ?: ""}
            |
            |Dernières lignes :
            |$lignes
            """.trimMargin()

        // 3. Points à vérifier
        val parallele = fluxEnParallele(heureDernierePositionMs, heureDerniereNmeaMs, maintenant)
        val ecarts = ecartsAltitude(position?.altitude, gga)
        texteVerifications.text = """
            |1) Mock location + NMEA en même temps : ${if (parallele) "OUI ✔" else "NON (position il y a ${age(heureDernierePositionMs, maintenant)}, NMEA il y a ${age(heureDerniereNmeaMs, maintenant)})"}
            |2) Hauteur de canne dans ETC : ${if (etc == null) "pas encore de message ETC" else "chercher la valeur dans les champs ETC ci-dessus"}
            |3) Compensation (test d'inclinaison, écart depuis le départ) :
            |     ${texteInclinaison()}
            |4) Altitude mock − altitude GGA : ${nombre(ecarts.avecAltitudeGga, 3)} m
            |   Altitude mock − hauteur ellipsoïdale GGA : ${nombre(ecarts.avecHauteurEllipsoidale, 3)} m
            |   → ${interpreterAltitudeMock(ecarts)}
            |5) Statut RTK (GGA) : ${libelleQualite(gga?.qualite)} ; extras mock : ${position?.let { extrasEnTexte(it) } ?: "—"}
            """.trimMargin()

        texteJournal.text = "${journal.nombreDeLignes()} lignes en mémoire"
    }

    private fun decrireGga(gga: PositionGga): String =
        "qualité ${gga.qualite} = ${libelleQualite(gga.qualite)}, ${gga.nbSatellites ?: "—"} satellites, " +
            "HDOP ${nombre(gga.hdop, 1)}, âge corr. ${nombre(gga.ageCorrectionsS, 1)} s\n" +
            "      lat ${nombre(gga.latitudeDeg, 9)}  lon ${nombre(gga.longitudeDeg, 9)}\n" +
            "      altitude ${nombre(gga.altitudeGeoideRecepteurM, 3)} m + séparation ${nombre(gga.separationGeoideM, 3)} m" +
            " = ellipsoïdale ${nombre(gga.hauteurEllipsoidaleM, 3)} m"

    // =====================================================================
    // Petits outils
    // =====================================================================

    private fun nombre(valeur: Double?, decimales: Int): String =
        if (valeur == null) "—" else String.format(Locale.ROOT, "%.${decimales}f", valeur)

    private fun age(heureMs: Long?, maintenant: Long): String =
        if (heureMs == null) "—" else "${nombre((maintenant - heureMs) / 1000.0, 1)} s"

    private fun heureUtc(tempsMs: Long): String {
        val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(tempsMs))
    }

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
    }
}
