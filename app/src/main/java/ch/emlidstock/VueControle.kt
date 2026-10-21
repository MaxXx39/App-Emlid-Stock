package ch.emlidstock

import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import ch.emlidstock.noyau.leve.Stock
import ch.emlidstock.noyau.volume.ResultatVolumeStock

/**
 * Vues de contrôle d'un stock après le calcul du volume (étape 6 du cahier des charges) :
 * - 2D : la carte en grand, avec triangles, altitudes des points, courbes de niveau,
 *   couleurs selon l'altitude, remblai / déblai (au choix dans « Calques ») ;
 * - 3D : le stock en relief, coloré selon l'altitude, qu'on tourne au doigt.
 */
class VueControle(
    private val ecran: EcranPrincipal,
    private val stock: Stock,
    private val resultat: ResultatVolumeStock,
) : Vue {

    private val ui = ecran.ui
    private val carte = CarteAvecBoutons(ecran).also { c ->
        c.carte.points = stock.points.toList()
        c.carte.lignesFermees = stock.lignesFermees.toSet()
        c.carte.triangles = resultat.trianglesDessus
        c.carte.trianglesEcart = resultat.trianglesEcart
    }
    private val vue3D = Vue3D(ecran).apply { triangles = resultat.trianglesDessus }
    private val boutons3D = LinearLayout(ecran).apply {
        orientation = LinearLayout.HORIZONTAL
        listOf(1.0, 2.0, 5.0).forEach { facteur ->
            addView(ui.bouton("×${facteur.toInt()}") { vue3D.exageration = facteur }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(ui.bouton("Dessus") { vue3D.vueDeDessus() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ui.bouton("Départ") { vue3D.reinitialiser() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    private val onglet2D = onglet("2D (carte)") { afficher2D(true) }
    private val onglet3D = onglet("3D (relief)") { afficher2D(false) }

    override val racine: View = LinearLayout(ecran).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(8))
        addView(ui.ligneBoutons(ui.bouton("← Levé du stock") { retour() }))
        addView(ui.titre("Contrôle : ${stock.nom}"))
        addView(ui.ligneBoutons(onglet2D, onglet3D))
        val zone = FrameLayout(ecran).apply {
            addView(carte.racine, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(vue3D, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        addView(zone, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(boutons3D)
        addView(ui.zoneTexte(13f).apply {
            text = "Remblai ${nombre(resultat.remblai, 3)} m³  ·  déblai ${nombre(resultat.deblai, 3)} m³\n" +
                "Surface ${nombre(resultat.surfacePlan, 3)} m²  ·  ${resultat.trianglesDessus.size} triangles"
        })
        addView(ui.texte(12f).apply {
            text = "2D : « Calques » pour les courbes de niveau, les couleurs selon l'altitude, le remblai / déblai.\n" +
                "3D : glisser pour tourner, pincer pour zoomer, double toucher pour revenir au départ."
        })
    }

    init {
        afficher2D(true)
    }

    /** Retour à l'écran de levé, en gardant le volume calculé. */
    fun retour() = ecran.afficherLeve(stock, resultat)

    override fun rafraichir() {
        carte.carte.position = ecran.recepteur.dernierePosition
        carte.carte.positionFiable = ecran.recepteur.derniereCompensation?.estActive == true
        carte.carte.invalidate()
    }

    private fun onglet(texte: String, action: () -> Unit) = Button(ecran).apply {
        text = texte
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun afficher2D(deux: Boolean) {
        carte.racine.visibility = if (deux) View.VISIBLE else View.GONE
        vue3D.visibility = if (deux) View.GONE else View.VISIBLE
        boutons3D.visibility = vue3D.visibility
        for ((bouton, actif) in listOf(onglet2D to deux, onglet3D to !deux)) {
            bouton.setBackgroundColor(if (actif) ecran.getColor(R.color.ambre) else Color.rgb(230, 230, 230))
            bouton.setTypeface(null, if (actif) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}
