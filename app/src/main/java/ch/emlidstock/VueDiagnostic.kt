package ch.emlidstock

import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import ch.emlidstock.noyau.coordonnees.PositionSuisse
import ch.emlidstock.noyau.nmea.PositionGga
import ch.emlidstock.noyau.nmea.champsNumerotes
import ch.emlidstock.noyau.nmea.libelleQualite
import kotlin.math.hypot

/**
 * Écran de diagnostic (celui de la version 0.2) : position, test d'inclinaison,
 * journal avec notes et détails techniques du flux NMEA.
 */
class VueDiagnostic(private val ecran: EcranPrincipal) : Vue {

    private val ui = ecran.ui
    private val recepteur = ecran.recepteur
    private var depart: PositionSuisse? = null

    private val textePosition = ui.zoneTexte(20f)
    private val texteInclinaison = ui.zoneTexte()
    private val texteJournal = ui.zoneTexte()
    private val texteDetails = ui.zoneTexte()
    private val champNote = EditText(ecran).apply { hint = "Note (la position actuelle est ajoutée)" }

    override val racine: View = ScrollView(ecran).apply {
        val colonne = ui.colonne()
        colonne.addView(ui.bouton("← Accueil") { ecran.afficherAccueil() })
        colonne.addView(ui.titre("Position MN95 / altitude RAN95"))
        colonne.addView(textePosition)

        colonne.addView(ui.titre("Test d'inclinaison"))
        colonne.addView(texteInclinaison)
        colonne.addView(ui.bouton("Fixer le départ") { fixerDepart() })

        colonne.addView(ui.titre("Journal"))
        colonne.addView(texteJournal)
        colonne.addView(champNote)
        colonne.addView(ui.ligneBoutons(
            ui.bouton("Ajouter la note") { ajouterNote() },
            ui.bouton("Enregistrer…") { ecran.enregistrerJournal() },
            ui.bouton("Vider") { ecran.journal.vider() },
        ))

        colonne.addView(ui.titre("Détails techniques"))
        colonne.addView(texteDetails)
        addView(colonne)
    }

    override fun rafraichir() {
        val (texte, couleur) = ecran.texteEtCouleurPosition()
        textePosition.text = texte
        textePosition.setTextColor(couleur)

        val position = recepteur.dernierePosition
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

        texteJournal.text = "${ecran.journal.nombreDeLignes()} lignes en mémoire"

        val gga = recepteur.derniereGga
        val etc = recepteur.derniereEtc
        texteDetails.text = """
            |Bluetooth : ${recepteur.etatBluetooth}
            |GGA : ${if (gga == null) "aucun" else decrireGga(gga)}
            |ETC : ${etc?.let { champsNumerotes(it) } ?: "aucun"}
            |Sommes de contrôle fausses : ${recepteur.nbSommesFausses}
            |Dernières lignes :
            |${recepteur.dernieresLignes()}
            """.trimMargin()
    }

    private fun fixerDepart() {
        val position = recepteur.dernierePosition
        if (position == null) {
            ecran.message("Pas encore de position : connectez le récepteur.")
            return
        }
        depart = position
        ecran.journal.ajouter("NOTE", "Départ du test d'inclinaison : ${positionEnTexte(position)}")
        ecran.message("Départ fixé. Gardez la pointe de la canne immobile et inclinez-la.")
    }

    private fun ajouterNote() {
        val note = champNote.text.toString().trim()
        if (note.isEmpty()) return
        val position = recepteur.dernierePosition?.let { positionEnTexte(it) } ?: "pas de position"
        ecran.journal.ajouter("NOTE", "$note | $position | ${libelleQualite(recepteur.derniereGga?.qualite)}")
        champNote.setText("")
        ecran.message("Note ajoutée au journal.")
    }

    private fun positionEnTexte(p: PositionSuisse): String =
        "E ${nombre(p.est, 3)} N ${nombre(p.nord, 3)} H ${nombre(p.altitude, 3)}"

    private fun decrireGga(gga: PositionGga): String =
        "${libelleQualite(gga.qualite)}, ${gga.nbSatellites ?: "—"} satellites, HDOP ${nombre(gga.hdop, 1)}, " +
            "âge corr. ${nombre(gga.ageCorrectionsS, 1)} s, station ${gga.stationReference.ifEmpty { "—" }}\n" +
            "      lat ${nombre(gga.latitudeDeg, 9)}  lon ${nombre(gga.longitudeDeg, 9)}  " +
            "h ellipsoïdale ${nombre(gga.hauteurEllipsoidaleM, 3)} m"
}
