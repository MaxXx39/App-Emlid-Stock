package ch.emlidstock

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import ch.emlidstock.noyau.surface.SurfaceImportee
import ch.emlidstock.noyau.surface.resumerSurface

/**
 * Écran d'une surface importée (LandXML, DXF) : résumé chiffré, plan des triangles, vues de contrôle,
 * renommer, supprimer. Elle sert de base au volume des stocks qui la choisissent (mode B).
 */
class VueSurfaceImportee(private val ecran: EcranPrincipal, private val surface: SurfaceImportee) : Vue {

    private val ui = ecran.ui
    private val resume = resumerSurface(surface.triangles)
    private val texteTitre = ui.titre("")
    private val carte = CarteAvecBoutons(ecran).also { it.carte.triangles = surface.triangles }

    override val racine: View = ScrollView(ecran).apply {
        val colonne = ui.colonne()
        colonne.addView(texteTitre)
        colonne.addView(ui.ligneBoutons(
            ui.bouton("← Accueil") { ecran.afficherAccueil() },
            ui.bouton("Renommer") { renommer() },
            ui.bouton("Supprimer") { supprimer() },
        ))
        val hauteurCarte = (ecran.resources.displayMetrics.heightPixels * 0.45).toInt()
        colonne.addView(carte.racine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hauteurCarte))
        colonne.addView(ui.zoneTexte(14f).apply { text = texteResume() })
        colonne.addView(ui.bouton("Vues de contrôle (2D / 3D)") { ouvrirControle() })
        addView(colonne)
    }

    init {
        texteTitre.text = "${surface.nom} (${surface.origine})"
    }

    override fun rafraichir() {
        carte.carte.position = ecran.recepteur.dernierePosition
        carte.carte.positionFiable = ecran.recepteur.derniereCompensation?.estActive == true
        carte.carte.invalidate()
    }

    private fun texteResume(): String = buildString {
        append("Fichier          ${surface.nomFichier}\n")
        append("Points           ${surface.sommets.size}\n")
        append("Triangles        ${surface.faces.size}\n")
        append("Surface en plan  ${nombre(resume.surfacePlan, 3)} m²\n")
        append("Altitudes        ${nombre(resume.altitudeMin, 3)} à ${nombre(resume.altitudeMax, 3)} m\n")
        val stocks = stocksQuiLUtilisent()
        append(
            if (stocks.isEmpty()) {
                "Base d'aucun stock pour l'instant (dans un stock : « Changer la base du volume… »)."
            } else {
                "Base du volume de : ${stocks.joinToString(", ") { it.nom }}"
            },
        )
    }

    private fun stocksQuiLUtilisent() = ecran.depot.stocks.filter { it.baseSurfaceId == surface.id }

    private fun ouvrirControle() {
        ecran.afficherControle(
            SujetControle(
                surface.nom, emptyList(), emptySet(), surface.triangles, emptyList(),
                "Surface ${nombre(resume.surfacePlan, 3)} m²  ·  ${surface.faces.size} triangles  ·  " +
                    "altitudes ${nombre(resume.altitudeMin, 3)} à ${nombre(resume.altitudeMax, 3)} m",
                "← Surface importée",
            ) { ecran.afficherSurfaceImportee(surface) },
        )
    }

    private fun renommer() {
        ecran.demanderNom("Nouveau nom de la surface", surface.nom) { nom ->
            surface.nom = nom
            ecran.enregistrerSurfaces()
            texteTitre.text = "${surface.nom} (${surface.origine})"
        }
    }

    private fun supprimer() {
        val stocks = stocksQuiLUtilisent()
        val avertissement = if (stocks.isEmpty()) "" else "\n\nElle sert de base à : ${stocks.joinToString(", ") { it.nom }}. Ces stocks reviendront à la base « points B » (mode A)."
        ecran.confirmer("Supprimer la surface ?", "« ${surface.nom} » sera effacée du téléphone.$avertissement") {
            stocks.forEach { it.baseSurfaceId = null }
            ecran.depot.surfacesImportees.remove(surface)
            ecran.enregistrerSurfaces()
            ecran.enregistrerLeves()
            ecran.afficherAccueil()
        }
    }
}
