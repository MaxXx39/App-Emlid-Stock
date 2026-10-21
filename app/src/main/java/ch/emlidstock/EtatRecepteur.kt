package ch.emlidstock

import android.os.SystemClock
import ch.emlidstock.noyau.coordonnees.GrilleGeoide
import ch.emlidstock.noyau.coordonnees.PositionSuisse
import ch.emlidstock.noyau.coordonnees.versPositionSuisse
import ch.emlidstock.noyau.leve.Autorisation
import ch.emlidstock.noyau.leve.autoriserPriseDePoint
import ch.emlidstock.noyau.nmea.EtatCompensation
import ch.emlidstock.noyau.nmea.PhraseNmea
import ch.emlidstock.noyau.nmea.PositionGga
import ch.emlidstock.noyau.nmea.lireEtc
import ch.emlidstock.noyau.nmea.lireGga
import ch.emlidstock.noyau.nmea.lirePhraseNmea

/**
 * Tout ce que l'appli sait du récepteur à l'instant présent : connexion Bluetooth,
 * dernière position (GGA convertie en MN95 / RAN95), état de l'inclinaison (ETC).
 * Les données arrivent par le fil Bluetooth ; les écrans les lisent 2 fois par seconde.
 */
class EtatRecepteur(val journal: Journal) {

    val bluetooth = RecepteurBluetooth(::surLigneNmea, ::surEtatBluetooth)

    // Grille du géoïde : chargée une seule fois, à la première conversion.
    private val grille: GrilleGeoide by lazy { GrilleGeoide.chgeo2004Lhn95() }

    /** Une position reçue : le GGA, sa conversion MN95 / RAN95 et l'heure de réception, ensemble. */
    class Mesure(val gga: PositionGga, val position: PositionSuisse?, val heureMs: Long)

    // Un seul objet pour que GGA, position et heure soient toujours lus ensemble.
    @Volatile var derniereMesure: Mesure? = null
        private set
    val derniereGga: PositionGga? get() = derniereMesure?.gga
    val dernierePosition: PositionSuisse? get() = derniereMesure?.position
    val heureGgaMs: Long? get() = derniereMesure?.heureMs
    @Volatile var derniereCompensation: EtatCompensation? = null
        private set
    @Volatile var derniereEtc: PhraseNmea? = null
        private set
    @Volatile var heureEtcMs: Long? = null
        private set
    @Volatile var heureNmeaMs: Long? = null
        private set
    @Volatile var nbSommesFausses = 0
        private set
    @Volatile var etatBluetooth = "non connecté"
        private set
    @Volatile var erreurConversion: String? = null
        private set
    private val dernieresLignes = ArrayDeque<String>()

    /** Âge (ms) d'une donnée reçue à [heureMs], ou null si jamais reçue. */
    fun age(heureMs: Long?): Long? = heureMs?.let { SystemClock.elapsedRealtime() - it }

    /** Peut-on prendre un point maintenant ? (RTK fixe + inclinaison prête, données récentes) */
    fun autorisation(): Autorisation {
        val mesure = derniereMesure
        return autoriserPriseDePoint(mesure?.gga, age(mesure?.heureMs), derniereCompensation, age(heureEtcMs))
    }

    fun dernieresLignes(): String = synchronized(dernieresLignes) { dernieresLignes.joinToString("\n") }

    /** Appelé (depuis le fil Bluetooth) pour chaque ligne reçue du récepteur. */
    private fun surLigneNmea(ligne: String) {
        val maintenant = SystemClock.elapsedRealtime()
        heureNmeaMs = maintenant
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
            "GGA" -> lireGga(phrase)?.let { gga -> derniereMesure = Mesure(gga, convertir(gga), maintenant) }
            "ETC" -> {
                derniereEtc = phrase
                derniereCompensation = lireEtc(phrase)
                heureEtcMs = maintenant
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

    private fun surEtatBluetooth(etat: String) {
        etatBluetooth = etat
        journal.ajouter("INFO", "Bluetooth : $etat")
    }
}
