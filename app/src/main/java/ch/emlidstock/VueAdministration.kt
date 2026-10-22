package ch.emlidstock

import android.app.AlertDialog
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import ch.emlidstock.noyau.partage.Appareil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Administration du partage (seulement pour un administrateur) : liste des appareils
 * (code, nom, modèle, dernière connexion), nommer ou supprimer un appareil.
 * Plus tard : quels projets chaque appareil peut voir.
 */
class VueAdministration(private val ecran: EcranPrincipal) : Vue {

    private val ui = ecran.ui
    private val partage = ecran.partage
    private val texteEtat = ui.texte(13f)
    private val liste = LinearLayout(ecran).apply { orientation = LinearLayout.VERTICAL }
    private val formatDate = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT)

    override val racine: View = ScrollView(ecran).apply {
        val colonne = ui.colonne()
        colonne.addView(ui.bouton("← Accueil") { ecran.afficherAccueil() })
        colonne.addView(ui.titre("Appareils du partage"))
        colonne.addView(ui.texte(12f).apply {
            text = "Chaque appareil qui ouvre l'appli apparaît ici avec son code (affiché à l'accueil de l'appareil). " +
                "Touchez un appareil pour lui donner un nom ou le supprimer."
        })
        colonne.addView(ui.bouton("Actualiser") { charger() })
        colonne.addView(texteEtat)
        colonne.addView(liste)
        addView(colonne)
    }

    init {
        charger()
    }

    override fun rafraichir() {}

    private fun charger() {
        texteEtat.text = "Chargement…"
        partage.listerAppareils { appareils, erreur ->
            liste.removeAllViews()
            if (appareils == null) {
                texteEtat.text = "Liste impossible à lire : $erreur"
                return@listerAppareils
            }
            texteEtat.text = "${appareils.size} appareil(s)"
            for (appareil in appareils) {
                val ceLuiCi = if (appareil.identifiant == partage.identifiant) "  (cet appareil)" else ""
                val vuLe = if (appareil.vuLeMs > 0) formatDate.format(Date(appareil.vuLeMs)) else "?"
                val resume = "${appareil.nom ?: "Sans nom"} · code ${appareil.code}$ceLuiCi\n" +
                    "${appareil.modele} · ${appareil.appli} ${appareil.version} · vu le $vuLe"
                liste.addView(ui.bouton(resume) { actions(appareil) })
            }
        }
    }

    private fun actions(appareil: Appareil) {
        AlertDialog.Builder(ecran)
            .setTitle("${appareil.nom ?: "Sans nom"} (code ${appareil.code})")
            .setItems(arrayOf("Donner un nom", "Supprimer")) { _, choix ->
                when (choix) {
                    0 -> nommer(appareil)
                    1 -> supprimer(appareil)
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun nommer(appareil: Appareil) {
        ecran.demanderNom("Nom de l'appareil ${appareil.code}", appareil.nom ?: "") { nom ->
            partage.nommerAppareil(appareil, nom) { _, erreur ->
                if (erreur != null) ecran.message("Nom pas enregistré : $erreur") else charger()
            }
        }
    }

    private fun supprimer(appareil: Appareil) {
        ecran.confirmer(
            "Supprimer l'appareil ${appareil.code} ?",
            "Sa fiche est effacée. S'il ouvre de nouveau l'appli, il réapparaîtra sans nom.",
        ) {
            partage.supprimerAppareil(appareil) { _, erreur ->
                if (erreur != null) ecran.message("Suppression impossible : $erreur") else charger()
            }
        }
    }
}
