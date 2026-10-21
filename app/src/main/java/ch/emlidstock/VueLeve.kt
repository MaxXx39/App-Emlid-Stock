package ch.emlidstock

import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import ch.emlidstock.noyau.leve.ModeLeve
import ch.emlidstock.noyau.leve.PointLeve
import ch.emlidstock.noyau.leve.Stock
import ch.emlidstock.noyau.leve.preparerPoint

/**
 * Écran de levé d'un stock : choix du mode (bas de talus, haut de talus, point caractéristique),
 * nom du prochain point, bouton « Prendre le point » (bloqué hors RTK fixe ou inclinaison pas prête),
 * annulation du dernier point, carte du levé et liste des points (toucher un point pour le supprimer).
 */
class VueLeve(private val ecran: EcranPrincipal, private val stock: Stock) : Vue {

    private val ui = ecran.ui
    private var mode = ModeLeve.BAS_DE_TALUS

    private val texteTitre = ui.titre("")
    private val textePosition = ui.zoneTexte(16f)
    private val carte = CarteAvecBoutons(ecran).also { c ->
        c.carte.quandToucherPoint = { point -> demanderSuppression(point) }
    }
    private val boutonsModes = ModeLeve.values().associateWith { m -> ui.bouton(m.libelle) { choisirMode(m) } }
    private val ligneHaut = LinearLayout(ecran).apply { orientation = LinearLayout.HORIZONTAL }
    private val texteLigneHaut = ui.texte(16f)
    private val texteProchain = ui.texte(22f).apply { setTypeface(null, Typeface.BOLD) }
    private val boutonPrendre = Button(ecran).apply {
        textSize = 20f
        isAllCaps = false
        minHeight = ui.dp(80)
        setOnClickListener { prendrePoint() }
    }
    private val texteCompteurs = ui.texte(14f)
    private val listePoints = LinearLayout(ecran).apply { orientation = LinearLayout.VERTICAL }

    override val racine: View = ScrollView(ecran).apply {
        val colonne = ui.colonne()
        colonne.addView(texteTitre)
        colonne.addView(ui.ligneBoutons(
            ui.bouton("← Stocks") { ecran.afficherAccueil() },
            ui.bouton("Renommer") { renommer() },
        ))
        // Carte : environ 45 % de la hauteur de l'écran (comme dans l'appli topo V1).
        val hauteurCarte = (ecran.resources.displayMetrics.heightPixels * 0.45).toInt()
        colonne.addView(carte.racine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hauteurCarte))
        colonne.addView(textePosition)

        colonne.addView(ui.titre("Mode de levé"))
        val ligneModes = LinearLayout(ecran).apply { orientation = LinearLayout.VERTICAL }
        boutonsModes.values.forEach { ligneModes.addView(it) }
        colonne.addView(ligneModes)
        ligneHaut.addView(texteLigneHaut, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ligneHaut.addView(ui.bouton("Nouvelle ligne") { nouvelleLigne() })
        colonne.addView(ligneHaut)

        colonne.addView(texteProchain)
        colonne.addView(boutonPrendre)
        colonne.addView(ui.bouton("Annuler le dernier point") { annulerDernier() })

        colonne.addView(ui.titre("Points levés"))
        colonne.addView(texteCompteurs)
        colonne.addView(ui.texte(12f).apply { text = "Toucher un point (ici ou sur la carte) pour le supprimer." })
        colonne.addView(listePoints)
        addView(colonne)
    }

    init {
        // On reprend le mode du dernier point levé (reprise d'un stock sur une autre session).
        stock.points.lastOrNull()?.let { mode = it.mode }
        remplir()
    }

    override fun rafraichir() {
        val (textePos, couleur) = ecran.texteEtCouleurPosition()
        textePosition.text = textePos
        textePosition.setTextColor(couleur)
        carte.carte.position = ecran.recepteur.dernierePosition
        carte.carte.positionFiable = ecran.recepteur.derniereCompensation?.estActive == true
        carte.carte.invalidate()

        val autorisation = ecran.recepteur.autorisation()
        boutonPrendre.isEnabled = autorisation.autorise
        boutonPrendre.text = if (autorisation.autorise) {
            "PRENDRE LE POINT ${stock.prochainNom(mode)}"
        } else {
            "Point bloqué :\n${autorisation.raison}"
        }
    }

    /** Réécrit tout ce qui dépend des points et du mode (après un changement). */
    private fun remplir() {
        texteTitre.text = stock.nom
        for ((m, bouton) in boutonsModes) {
            if (m == mode) {
                bouton.setBackgroundColor(ecran.getColor(R.color.ambre))
                bouton.setTextColor(Color.BLACK)
                bouton.setTypeface(null, Typeface.BOLD)
            } else {
                bouton.setBackgroundColor(Color.rgb(230, 230, 230))
                bouton.setTextColor(Color.DKGRAY)
                bouton.setTypeface(null, Typeface.NORMAL)
            }
        }
        ligneHaut.visibility = if (mode == ModeLeve.HAUT_DE_TALUS) View.VISIBLE else View.GONE
        texteLigneHaut.text = "Ligne en cours : H${stock.ligneHautActive}"
        texteProchain.text = "Prochain point : ${stock.prochainNom(mode)}"

        val nbLignes = stock.points.mapNotNull { it.ligne }.distinct().size
        texteCompteurs.text = "Bas de talus : ${stock.nombreDePoints(ModeLeve.BAS_DE_TALUS)}  ·  " +
            "Haut de talus : ${stock.nombreDePoints(ModeLeve.HAUT_DE_TALUS)} ($nbLignes lignes)  ·  " +
            "Caractéristiques : ${stock.nombreDePoints(ModeLeve.POINT_CARACTERISTIQUE)}"

        carte.carte.points = stock.points.toList()

        // Les points les plus récents en premier.
        listePoints.removeAllViews()
        for (point in stock.points.asReversed()) {
            val ligne = ui.zoneTexte(14f).apply {
                setTextIsSelectable(false)
                text = String.format(
                    "%-7s E %s  N %s  H %s",
                    point.nom, nombre(point.est, 3), nombre(point.nord, 3), nombre(point.altitude, 3),
                )
                setPadding(0, ui.dp(6), 0, ui.dp(6))
                setOnClickListener { demanderSuppression(point) }
            }
            listePoints.addView(ligne)
        }
        rafraichir()
    }

    private fun demanderSuppression(point: PointLeve) {
        ecran.confirmer(
            "Supprimer ${point.nom} ?",
            "E ${nombre(point.est, 3)}  N ${nombre(point.nord, 3)}  H ${nombre(point.altitude, 3)}\nLe point sera effacé du stock.",
        ) {
            stock.supprimerPoint(point.nom)
            ecran.journal.ajouter("NOTE", "Point ${point.nom} supprimé (stock ${stock.nom})")
            ecran.enregistrerLeves()
            remplir()
        }
    }

    private fun choisirMode(nouveauMode: ModeLeve) {
        mode = nouveauMode
        remplir()
    }

    private fun nouvelleLigne() {
        val ligne = stock.nouvelleLigneHaut()
        ecran.enregistrerLeves()
        ecran.message("Ligne de haut de talus H$ligne")
        remplir()
    }

    /** Prend le point à l'instant du clic, si le RTK est fixe et l'inclinaison prête. */
    private fun prendrePoint() {
        val autorisation = ecran.recepteur.autorisation()
        val mesure = ecran.recepteur.derniereMesure
        val position = mesure?.position
        if (!autorisation.autorise || mesure == null || position == null) {
            ecran.message("Point refusé : ${if (autorisation.autorise) "position non convertie" else autorisation.raison}")
            return
        }
        val point = preparerPoint(
            stock, mode, mesure.gga, position, ecran.recepteur.derniereCompensation, System.currentTimeMillis(),
        )
        stock.ajouterPoint(point)
        ecran.journal.ajouter(
            "POINT",
            "${point.nom} E ${nombre(point.est, 3)} N ${nombre(point.nord, 3)} H ${nombre(point.altitude, 3)} " +
                "incl ${nombre(point.inclinaisonDeg, 1)}° (stock ${stock.nom})",
        )
        if (ecran.enregistrerLeves()) ecran.message("${point.nom} enregistré")
        remplir()
    }

    private fun annulerDernier() {
        val dernier = stock.points.lastOrNull() ?: run {
            ecran.message("Aucun point à annuler.")
            return
        }
        ecran.confirmer("Annuler ${dernier.nom} ?", "Le dernier point levé sera effacé.") {
            stock.annulerDernierPoint()
            ecran.journal.ajouter("NOTE", "Point ${dernier.nom} annulé (stock ${stock.nom})")
            ecran.enregistrerLeves()
            remplir()
        }
    }

    private fun renommer() {
        ecran.demanderNom("Nouveau nom du stock", stock.nom) { nom ->
            stock.nom = nom
            ecran.enregistrerLeves()
            remplir()
        }
    }
}
