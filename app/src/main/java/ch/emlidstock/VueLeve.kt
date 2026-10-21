package ch.emlidstock

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import ch.emlidstock.noyau.leve.ModeLeve
import ch.emlidstock.noyau.leve.PointLeve
import ch.emlidstock.noyau.leve.Stock
import ch.emlidstock.noyau.leve.nomDePoint
import ch.emlidstock.noyau.leve.preparerPoint
import ch.emlidstock.noyau.volume.ResultatVolumeStock
import ch.emlidstock.noyau.volume.calculerVolumeStock

/**
 * Écran de levé d'un stock : choix du mode (bas de talus, haut de talus, point caractéristique),
 * nom du prochain point, bouton « Prendre le point » (bloqué hors RTK fixe ou inclinaison pas prête),
 * annulation du dernier point, carte du levé et liste des points (toucher un point pour le modifier
 * ou le supprimer), calcul du volume, export des points en TXT.
 */
class VueLeve(
    private val ecran: EcranPrincipal,
    private val stock: Stock,
    volumeDejaCalcule: ResultatVolumeStock? = null,
) : Vue {

    private val ui = ecran.ui
    private var mode = ModeLeve.BAS_DE_TALUS

    private val texteTitre = ui.titre("")
    private val textePosition = ui.zoneTexte(16f)
    private val carte = CarteAvecBoutons(ecran).also { c ->
        c.carte.quandToucherPoint = { point -> actionsPoint(point) }
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
    private val boutonFermerLigne = ui.bouton("") { basculerFermetureLigne() }
    private val texteVolume = ui.zoneTexte(14f)
    private var resultatVolume: ResultatVolumeStock? = volumeDejaCalcule
    private val boutonControle = ui.bouton("Vues de contrôle (2D / 3D)") {
        resultatVolume?.takeIf { it.erreur == null }?.let { ecran.afficherControle(stock, it) }
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
        colonne.addView(boutonFermerLigne)

        colonne.addView(texteProchain)
        colonne.addView(boutonPrendre)
        colonne.addView(ui.bouton("Annuler le dernier point") { annulerDernier() })

        colonne.addView(ui.titre("Volume"))
        colonne.addView(ui.bouton("Calculer le volume") { calculerVolume() })
        colonne.addView(texteVolume)
        colonne.addView(boutonControle)

        colonne.addView(ui.titre("Points levés"))
        colonne.addView(texteCompteurs)
        colonne.addView(ui.bouton("Exporter… (PDF, CSV, DXF, LandXML, TXT, V1)") { exporter() })
        colonne.addView(ui.texte(12f).apply { text = "Toucher un point (ici ou sur la carte) pour le modifier ou le supprimer." })
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
        val ligneFermee = stock.ligneHautActive in stock.lignesFermees
        boutonFermerLigne.visibility = ligneHaut.visibility
        boutonFermerLigne.text = if (ligneFermee) {
            "Ligne H${stock.ligneHautActive} fermée → l'ouvrir"
        } else {
            "Ligne H${stock.ligneHautActive} ouverte → la fermer (dernier point relié au premier)"
        }
        texteProchain.text = "Prochain point : ${stock.prochainNom(mode)}"

        val nbLignes = stock.points.mapNotNull { it.ligne }.distinct().size
        texteCompteurs.text = "Bas de talus : ${stock.nombreDePoints(ModeLeve.BAS_DE_TALUS)}  ·  " +
            "Haut de talus : ${stock.nombreDePoints(ModeLeve.HAUT_DE_TALUS)} ($nbLignes lignes)  ·  " +
            "Caractéristiques : ${stock.nombreDePoints(ModeLeve.POINT_CARACTERISTIQUE)}"

        carte.carte.points = stock.points.toList()
        carte.carte.lignesFermees = stock.lignesFermees.toSet()
        afficherVolume()

        // Les points les plus récents en premier.
        listePoints.removeAllViews()
        for (point in stock.points.asReversed()) {
            val ligne = ui.zoneTexte(14f).apply {
                setTextIsSelectable(false)
                text = String.format(
                    "%-7s E %s  N %s  H %s%s",
                    point.nom, nombre(point.est, 3), nombre(point.nord, 3), nombre(point.altitude, 3),
                    if (point.modifieALaMain) "  (modifié)" else "",
                )
                setPadding(0, ui.dp(6), 0, ui.dp(6))
                setOnClickListener { actionsPoint(point) }
            }
            listePoints.addView(ligne)
        }
        rafraichir()
    }

    /** Après une modification des points : enregistrer, oublier l'ancien volume, réafficher. */
    private fun apresChangement(noteJournal: String) {
        ecran.journal.ajouter("NOTE", "$noteJournal (stock ${stock.nom})")
        ecran.enregistrerLeves()
        resultatVolume = null
        remplir()
    }

    // =====================================================================
    // Modifier un point (toucher un point sur la carte ou dans la liste)
    // =====================================================================

    private fun actionsPoint(point: PointLeve) {
        val titre = "${point.nom}   E ${nombre(point.est, 3)}  N ${nombre(point.nord, 3)}  H ${nombre(point.altitude, 3)}"
        AlertDialog.Builder(ecran)
            .setTitle(titre)
            .setItems(arrayOf("Changer le rôle (B, H, C)", "Renommer", "Modifier E, N, H à la main", "Supprimer")) { _, choix ->
                when (choix) {
                    0 -> changerRole(point)
                    1 -> renommerPoint(point)
                    2 -> modifierCoordonnees(point)
                    else -> demanderSuppression(point)
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    /** Rôle : bas de talus, haut de talus (ligne existante ou nouvelle), point caractéristique. */
    private fun changerRole(point: PointLeve) {
        val lignes = stock.lignesHaut()
        val nouvelleLigne = (lignes.maxOrNull() ?: 0) + 1
        val choix = mutableListOf<Pair<String, Pair<ModeLeve, Int?>>>()
        choix.add(Pair("Bas de talus (B…)", Pair(ModeLeve.BAS_DE_TALUS, null)))
        lignes.forEach { choix.add(Pair("Haut de talus, ligne H$it", Pair(ModeLeve.HAUT_DE_TALUS, it))) }
        choix.add(Pair("Haut de talus, nouvelle ligne H$nouvelleLigne", Pair(ModeLeve.HAUT_DE_TALUS, nouvelleLigne)))
        choix.add(Pair("Point caractéristique (C…)", Pair(ModeLeve.POINT_CARACTERISTIQUE, null)))
        AlertDialog.Builder(ecran)
            .setTitle("Nouveau rôle de ${point.nom}")
            .setItems(choix.map { it.first }.toTypedArray()) { _, i ->
                val (mode, ligne) = choix[i].second
                val (ligneNouvelle, numero) = stock.prochainNumero(mode, ligne)
                val nouveauNom = nomDePoint(mode, ligneNouvelle, numero)
                val erreur = stock.changerRole(point.nom, mode, ligne)
                if (erreur != null) {
                    ecran.message(erreur)
                } else {
                    apresChangement("Point ${point.nom} devient $nouveauNom")
                    ecran.message("${point.nom} devient $nouveauNom")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun renommerPoint(point: PointLeve) {
        ecran.demanderNom("Nouveau nom du point", point.nom) { nom ->
            val erreur = stock.remplacerPoint(point.nom, point.copy(nom = nom))
            if (erreur != null) ecran.message(erreur) else apresChangement("Point ${point.nom} renommé $nom")
        }
    }

    /** Coordonnées tapées à la main (le point est marqué « modifié »). */
    private fun modifierCoordonnees(point: PointLeve) {
        fun champ(valeur: Double, indication: String) = EditText(ecran).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(nombre(valeur, 3))
            hint = indication
        }
        val champEst = champ(point.est, "Est (m)")
        val champNord = champ(point.nord, "Nord (m)")
        val champAltitude = champ(point.altitude, "Altitude RAN95 (m)")
        val formulaire = LinearLayout(ecran).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
            addView(ui.texte(13f).apply { text = "Est (m)" })
            addView(champEst)
            addView(ui.texte(13f).apply { text = "Nord (m)" })
            addView(champNord)
            addView(ui.texte(13f).apply { text = "Altitude RAN95 (m)" })
            addView(champAltitude)
        }
        AlertDialog.Builder(ecran)
            .setTitle("Modifier ${point.nom}")
            .setView(formulaire)
            .setPositiveButton("Enregistrer") { _, _ ->
                fun lire(c: EditText) = c.text.toString().replace(',', '.').trim().toDoubleOrNull()
                val est = lire(champEst)
                val nord = lire(champNord)
                val altitude = lire(champAltitude)
                when {
                    est == null || nord == null || altitude == null -> ecran.message("Valeur illisible : tapez par ex. 2503979.890")
                    est !in 2_400_000.0..2_900_000.0 || nord !in 1_050_000.0..1_350_000.0 ->
                        ecran.message("Coordonnées hors de Suisse (MN95 : E 2 xxx xxx, N 1 xxx xxx)")
                    else -> {
                        val modifie = point.copy(est = est, nord = nord, altitude = altitude, modifieALaMain = true)
                        val erreur = stock.remplacerPoint(point.nom, modifie)
                        if (erreur != null) {
                            ecran.message(erreur)
                        } else {
                            apresChangement(
                                "Point ${point.nom} modifié à la main : E ${nombre(est, 3)} N ${nombre(nord, 3)} H ${nombre(altitude, 3)}",
                            )
                        }
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun demanderSuppression(point: PointLeve) {
        ecran.confirmer(
            "Supprimer ${point.nom} ?",
            "E ${nombre(point.est, 3)}  N ${nombre(point.nord, 3)}  H ${nombre(point.altitude, 3)}\nLe point sera effacé du stock.",
        ) {
            stock.supprimerPoint(point.nom)
            apresChangement("Point ${point.nom} supprimé")
        }
    }

    // =====================================================================
    // Lignes de haut de talus, volume, export
    // =====================================================================

    private fun basculerFermetureLigne() {
        val ligne = stock.ligneHautActive
        val fermer = ligne !in stock.lignesFermees
        stock.fermerLigne(ligne, fermer)
        apresChangement("Ligne H$ligne ${if (fermer) "fermée" else "ouverte"}")
    }

    /** Calcule le volume (validation du levé du stock) et l'affiche avec ses valeurs intermédiaires. */
    private fun calculerVolume() {
        val resultat = try {
            calculerVolumeStock(stock.points, stock.lignesFermees)
        } catch (erreur: Exception) {
            ResultatVolumeStock(erreur = "calcul impossible (${erreur.message})")
        }
        resultatVolume = resultat
        if (resultat.erreur == null) {
            ecran.journal.ajouter(
                "VOLUME",
                "${stock.nom} : remblai ${nombre(resultat.remblai, 3)} m³, déblai ${nombre(resultat.deblai, 3)} m³, " +
                    "surface ${nombre(resultat.surfacePlan, 3)} m²",
            )
        }
        afficherVolume()
    }

    private fun afficherVolume() {
        val r = resultatVolume
        val volumeValable = r != null && r.erreur == null
        carte.carte.triangles = if (volumeValable) r!!.trianglesDessus else emptyList()
        carte.carte.trianglesEcart = if (volumeValable) r!!.trianglesEcart else emptyList()
        boutonControle.visibility = if (volumeValable) View.VISIBLE else View.GONE
        texteVolume.setTextColor(if (r?.erreur != null) EcranPrincipal.ROUGE else Color.BLACK)
        texteVolume.text = when {
            r == null -> "Touchez « Calculer le volume » quand le levé est terminé.\n" +
                "Contour = points B dans l'ordre ; lignes H = lignes de rupture ; base = points B."
            r.erreur != null -> "Calcul impossible : ${r.erreur}"
            else -> buildString {
                append("Remblai          ${nombre(r.remblai, 3)} m³\n")
                append("Déblai           ${nombre(r.deblai, 3)} m³\n")
                append("Surface en plan  ${nombre(r.surfacePlan, 3)} m²\n")
                append("Surface contour  ${nombre(r.surfaceContour, 3)} m²\n")
                append("Triangles        ${r.trianglesDessus.size} dessus / ${r.trianglesBase.size} base\n")
                append("Au-dessus base   ${nombre(r.surfaceRemblai, 3)} m²\n")
                append("Au-dessous base  ${nombre(r.surfaceDeblai, 3)} m²")
                if (r.surfaceNonCouverte > 0) append("\nATTENTION : ${nombre(r.surfaceNonCouverte, 3)} m² non couverts par la base")
                r.avertissements.forEach { append("\nRemarque : $it") }
            }
        }
    }

    private fun exporter() {
        val chantier = ecran.depot.chantiers.firstOrNull { it.id == stock.chantierId } ?: return
        Exports(ecran, chantier, listOf(stock), stock.nom).proposer()
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
        resultatVolume = null
        remplir()
    }

    private fun annulerDernier() {
        val dernier = stock.points.lastOrNull() ?: run {
            ecran.message("Aucun point à annuler.")
            return
        }
        ecran.confirmer("Annuler ${dernier.nom} ?", "Le dernier point levé sera effacé.") {
            stock.annulerDernierPoint()
            apresChangement("Point ${dernier.nom} annulé")
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
