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
import ch.emlidstock.noyau.leve.TypeLeve
import ch.emlidstock.noyau.leve.libelleMode
import ch.emlidstock.noyau.leve.nomDePoint
import ch.emlidstock.noyau.leve.preparerPoint
import ch.emlidstock.noyau.surface.ResultatSurface
import ch.emlidstock.noyau.surface.calculerSurfaceLevee
import ch.emlidstock.noyau.surface.cleTriangle
import ch.emlidstock.noyau.surface.resumerSurface
import ch.emlidstock.noyau.volume.ResultatVolumeStock
import ch.emlidstock.noyau.volume.Triangle

/**
 * Écran de levé d'un stock ou d'une surface : choix du mode (stock : bas de talus, haut de talus,
 * point caractéristique ; surface : contour facultatif, ligne de rupture, point), nom du prochain point,
 * bouton « Prendre le point » (bloqué hors RTK fixe ou inclinaison pas prête), annulation du dernier point,
 * carte du levé et liste des points (toucher un point pour le modifier ou le supprimer).
 * Stock : choix de la base et calcul du volume. Surface : calcul des triangles, triangles retirés au doigt.
 */
class VueLeve(
    private val ecran: EcranPrincipal,
    private val stock: Stock,
    volumeDejaCalcule: ResultatVolumeStock? = null,
    surfaceDejaCalculee: ResultatSurface? = null,
) : Vue {

    private val ui = ecran.ui
    private var mode = ModeLeve.BAS_DE_TALUS
    private val estSurface = stock.type == TypeLeve.SURFACE

    private val texteTitre = ui.titre("")
    private val textePosition = ui.zoneTexte(16f)
    private val carte = CarteAvecBoutons(ecran).also { c ->
        c.carte.quandToucherPoint = { point -> actionsPoint(point) }
    }
    private val boutonsModes = ModeLeve.values().associateWith { m -> ui.bouton(libelleMode(stock.type, m)) { choisirMode(m) } }
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
    private var resultatSurface: ResultatSurface? = surfaceDejaCalculee
    private val texteBase = ui.texte(14f)
    private var retraitActif = false
    private val boutonRetrait = ui.bouton("") { basculerRetrait() }
    private val boutonControle = ui.bouton("Vues de contrôle (2D / 3D)") { ouvrirControle() }
    private val texteCompteurs = ui.texte(14f)
    private val listePoints = LinearLayout(ecran).apply { orientation = LinearLayout.VERTICAL }

    override val racine: View = ScrollView(ecran).apply {
        val colonne = ui.colonne()
        colonne.addView(texteTitre)
        colonne.addView(ui.ligneBoutons(
            ui.bouton("← Accueil") { ecran.afficherAccueil() },
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

        if (estSurface) {
            colonne.addView(ui.titre("Surface"))
            colonne.addView(ui.bouton("Calculer la surface (triangles)") { calculerSurface() })
            colonne.addView(boutonRetrait)
        } else {
            colonne.addView(ui.titre("Volume"))
            colonne.addView(texteBase)
            colonne.addView(ui.bouton("Changer la base du volume…") { choisirBase() })
            colonne.addView(ui.bouton("Calculer le volume") { calculerVolume() })
        }
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
        texteTitre.text = if (estSurface) "${stock.nom} (surface levée)" else stock.nom
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
        texteCompteurs.text = if (estSurface) {
            "Contour : ${stock.nombreDePoints(ModeLeve.BAS_DE_TALUS)}  ·  " +
                "Lignes de rupture : ${stock.nombreDePoints(ModeLeve.HAUT_DE_TALUS)} ($nbLignes lignes)  ·  " +
                "Points : ${stock.nombreDePoints(ModeLeve.POINT_CARACTERISTIQUE)}"
        } else {
            "Bas de talus : ${stock.nombreDePoints(ModeLeve.BAS_DE_TALUS)}  ·  " +
                "Haut de talus : ${stock.nombreDePoints(ModeLeve.HAUT_DE_TALUS)} ($nbLignes lignes)  ·  " +
                "Caractéristiques : ${stock.nombreDePoints(ModeLeve.POINT_CARACTERISTIQUE)}"
        }
        texteBase.text = "Base : ${nomDeLaBase()}"

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
        oublierResultats()
        remplir()
    }

    /** Les points ont changé : le volume (ou la surface) calculé n'est plus valable. */
    private fun oublierResultats() {
        resultatVolume = null
        resultatSurface = null
        if (retraitActif) basculerRetrait()
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
        val (b, h, c) = ModeLeve.values().map { libelleMode(stock.type, it) }
        choix.add(Pair("$b (B…)", Pair(ModeLeve.BAS_DE_TALUS, null)))
        lignes.forEach { choix.add(Pair("$h, ligne H$it", Pair(ModeLeve.HAUT_DE_TALUS, it))) }
        choix.add(Pair("$h, nouvelle ligne H$nouvelleLigne", Pair(ModeLeve.HAUT_DE_TALUS, nouvelleLigne)))
        choix.add(Pair("$c (C…)", Pair(ModeLeve.POINT_CARACTERISTIQUE, null)))
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
    // Lignes de haut de talus, base, volume, surface, export
    // =====================================================================

    private fun basculerFermetureLigne() {
        val ligne = stock.ligneHautActive
        val fermer = ligne !in stock.lignesFermees
        stock.fermerLigne(ligne, fermer)
        apresChangement("Ligne H$ligne ${if (fermer) "fermée" else "ouverte"}")
    }

    /** Nom de la base du volume : points B (mode A) ou la surface choisie (mode B). */
    private fun nomDeLaBase(): String {
        val id = stock.baseSurfaceId ?: return "points du bas de talus B (mode A)"
        val nom = ecran.depot.surfacesDuChantier(stock.chantierId).nom(id) ?: return "surface supprimée : choisissez une autre base"
        return "surface « $nom » (mode B)"
    }

    /** Base du volume : points B du stock (mode A) ou une surface du chantier, levée ou importée (mode B). */
    private fun choisirBase() {
        val choix = ecran.depot.surfacesDuChantier(stock.chantierId).choix()
        val libelles = listOf("Points du bas de talus B (mode A)") + choix.map { it.nom }
        val actuel = choix.indexOfFirst { it.id == stock.baseSurfaceId } + 1
        AlertDialog.Builder(ecran)
            .setTitle("Base du volume")
            .setSingleChoiceItems(libelles.toTypedArray(), actuel) { dialogue, i ->
                stock.baseSurfaceId = if (i == 0) null else choix[i - 1].id
                dialogue.dismiss()
                apresChangement("Base du volume : ${libelles[i]}")
                if (choix.isEmpty()) ecran.message("Aucune surface dans ce chantier : levez-en une (+ Nouvelle surface) ou importez un LandXML / DXF à l'accueil.")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Calcule le volume (validation du levé du stock) et l'affiche avec ses valeurs intermédiaires. */
    private fun calculerVolume() {
        val resultat = try {
            ecran.depot.surfacesDuChantier(stock.chantierId).volume(stock)
        } catch (erreur: Exception) {
            ResultatVolumeStock(erreur = "calcul impossible (${erreur.message})")
        }
        resultatVolume = resultat
        if (resultat.erreur == null) {
            ecran.journal.ajouter(
                "VOLUME",
                "${stock.nom} : remblai ${nombre(resultat.remblai, 3)} m³, déblai ${nombre(resultat.deblai, 3)} m³, " +
                    "surface ${nombre(resultat.surfacePlan, 3)} m², base ${resultat.nomBase ?: "points B"}",
            )
        }
        afficherVolume()
    }

    private fun afficherVolume() {
        if (estSurface) {
            afficherSurface()
            return
        }
        val r = resultatVolume
        val volumeValable = r != null && r.erreur == null
        carte.carte.triangles = if (volumeValable) r!!.trianglesDessus else emptyList()
        carte.carte.trianglesEcart = if (volumeValable) r!!.trianglesEcart else emptyList()
        carte.carte.trianglesBase = if (volumeValable && r!!.nomBase != null) r.trianglesBase else emptyList()
        boutonControle.visibility = if (volumeValable) View.VISIBLE else View.GONE
        texteVolume.setTextColor(if (r?.erreur != null) EcranPrincipal.ROUGE else Color.BLACK)
        texteVolume.text = when {
            r == null -> "Touchez « Calculer le volume » quand le levé est terminé.\n" +
                "Contour = points B dans l'ordre ; lignes H = lignes de rupture ; base : voir ci-dessus."
            r.erreur != null -> "Calcul impossible : ${r.erreur}"
            else -> buildString {
                append("Base             ${r.nomBase ?: "points B"} (mode ${r.mode})\n")
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

    /** Calcule les triangles de la surface levée (contour facultatif, lignes de rupture, triangles retirés). */
    private fun calculerSurface() {
        resultatSurface = try {
            calculerSurfaceLevee(stock.points, stock.lignesFermees, stock.trianglesSupprimes)
        } catch (erreur: Exception) {
            ResultatSurface(erreur = "calcul impossible (${erreur.message})")
        }
        resultatSurface?.takeIf { it.erreur == null }?.let {
            ecran.journal.ajouter("SURFACE", "${stock.nom} : ${it.triangles.size} triangles, ${nombre(resumerSurface(it.triangles).surfacePlan, 3)} m²")
        }
        afficherSurface()
    }

    private fun afficherSurface() {
        val r = resultatSurface
        val valable = r != null && r.erreur == null
        carte.carte.triangles = r?.triangles.orEmpty()
        carte.carte.trianglesRetires = r?.trianglesRetires.orEmpty()
        carte.carte.trianglesEcart = emptyList()
        boutonControle.visibility = if (valable) View.VISIBLE else View.GONE
        boutonRetrait.text = if (retraitActif) {
            "Retrait ACTIF : touchez un triangle sur la carte pour le retirer ou le remettre → Terminé"
        } else {
            "Retirer / remettre des triangles (toucher la carte)"
        }
        boutonRetrait.setBackgroundColor(if (retraitActif) ecran.getColor(R.color.ambre) else Color.rgb(230, 230, 230))
        texteVolume.setTextColor(if (r?.erreur != null) EcranPrincipal.ROUGE else Color.BLACK)
        texteVolume.text = when {
            r == null -> "Touchez « Calculer la surface » quand le levé est terminé.\n" +
                "Contour B facultatif (sans contour : tous les points, bord automatique) ; lignes H = lignes de rupture.\n" +
                "Cette surface peut servir de base au volume d'un stock (mode B)."
            r.erreur != null -> "Calcul impossible : ${r.erreur}"
            else -> buildString {
                val resume = resumerSurface(r.triangles)
                append("Surface en plan  ${nombre(resume.surfacePlan, 3)} m²\n")
                append("Triangles        ${r.triangles.size}")
                if (r.trianglesRetires.isNotEmpty()) append(" (+ ${r.trianglesRetires.size} retirés, en tirets rouges)")
                append("\nAltitudes        ${nombre(resume.altitudeMin, 3)} à ${nombre(resume.altitudeMax, 3)} m")
                r.avertissements.forEach { append("\nRemarque : $it") }
            }
        }
    }

    /** Active ou arrête le retrait des triangles au doigt (surface levée). */
    private fun basculerRetrait() {
        if (!retraitActif && resultatSurface == null) calculerSurface()
        val r = resultatSurface
        if (!retraitActif && (r == null || (r.triangles.isEmpty() && r.trianglesRetires.isEmpty()))) {
            ecran.message("Pas de triangles : ${r?.erreur ?: "calculez d'abord la surface"}")
            return
        }
        retraitActif = !retraitActif
        carte.carte.quandToucherTriangle = if (retraitActif) ::basculerTriangle else null
        afficherSurface()
    }

    /** Retire le triangle touché, ou le remet s'il était retiré. */
    private fun basculerTriangle(t: Triangle) {
        val cle = cleTriangle(t)
        if (!stock.trianglesSupprimes.remove(cle)) stock.trianglesSupprimes.add(cle)
        ecran.enregistrerLeves()
        resultatSurface = calculerSurfaceLevee(stock.points, stock.lignesFermees, stock.trianglesSupprimes)
        afficherSurface()
    }

    /** Vues de contrôle 2D / 3D du volume calculé ou de la surface. */
    private fun ouvrirControle() {
        if (estSurface) {
            val r = resultatSurface?.takeIf { it.erreur == null } ?: return
            val resume = resumerSurface(r.triangles)
            ecran.afficherControle(
                SujetControle(
                    stock.nom, stock.points.toList(), stock.lignesFermees.toSet(), r.triangles, emptyList(),
                    "Surface ${nombre(resume.surfacePlan, 3)} m²  ·  ${r.triangles.size} triangles  ·  " +
                        "altitudes ${nombre(resume.altitudeMin, 3)} à ${nombre(resume.altitudeMax, 3)} m",
                    "← Levé de la surface",
                ) { ecran.afficherLeve(stock, surface = r) },
            )
        } else {
            val r = resultatVolume?.takeIf { it.erreur == null } ?: return
            ecran.afficherControle(
                SujetControle(
                    stock.nom, stock.points.toList(), stock.lignesFermees.toSet(), r.trianglesDessus, r.trianglesEcart,
                    "Remblai ${nombre(r.remblai, 3)} m³  ·  déblai ${nombre(r.deblai, 3)} m³\n" +
                        "Surface ${nombre(r.surfacePlan, 3)} m²  ·  ${r.trianglesDessus.size} triangles  ·  base ${r.nomBase ?: "points B"}",
                    "← Levé du stock",
                ) { ecran.afficherLeve(stock, r) },
            )
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
        oublierResultats()
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
        ecran.demanderNom(if (estSurface) "Nouveau nom de la surface" else "Nouveau nom du stock", stock.nom) { nom ->
            stock.nom = nom
            ecran.enregistrerLeves()
            remplir()
        }
    }
}
