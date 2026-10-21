package ch.emlidstock

import android.app.AlertDialog
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import ch.emlidstock.noyau.leve.Chantier
import ch.emlidstock.noyau.leve.ModeLeve
import ch.emlidstock.noyau.leve.Stock
import ch.emlidstock.noyau.leve.TypeLeve
import ch.emlidstock.noyau.leve.nomStockParDefaut
import ch.emlidstock.noyau.surface.LectureSurfaces
import ch.emlidstock.noyau.surface.SurfaceImportee
import ch.emlidstock.noyau.surface.SurfaceLue
import ch.emlidstock.noyau.surface.lireDxf
import ch.emlidstock.noyau.surface.lireLandXml
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Écran d'accueil : chantier actif, ses stocks et ses surfaces (levées ou importées : ouvrir, créer,
 * importer, renommer, supprimer), connexion au récepteur, hauteur de canne, accès au diagnostic.
 */
class VueAccueil(private val ecran: EcranPrincipal) : Vue {

    private val ui = ecran.ui
    private val depot = ecran.depot
    private val texteChantier = ui.texte(16f)
    private val listeStocks = LinearLayout(ecran).apply { orientation = LinearLayout.VERTICAL }
    private val listeSurfaces = LinearLayout(ecran).apply { orientation = LinearLayout.VERTICAL }
    private val texteRecepteur = ui.zoneTexte()
    private val texteCanne = ui.zoneTexte()

    override val racine: View = ScrollView(ecran).apply {
        val colonne = ui.colonne()
        colonne.addView(ui.titre("Chantier"))
        colonne.addView(texteChantier)
        colonne.addView(ui.ligneBoutons(
            ui.bouton("Changer de chantier") { choisirChantier() },
            ui.bouton("Nouveau chantier") { creerChantier() },
        ))

        colonne.addView(ui.bouton("Exporter le chantier… (PDF, CSV, DXF, V1…)") { exporterChantier() })

        colonne.addView(ui.titre("Stocks"))
        colonne.addView(ui.bouton("+ Nouveau stock") { creerStock(TypeLeve.STOCK) })
        colonne.addView(ui.bouton("Importer des points (CSV Emlid, TXT)…") { importer() })
        colonne.addView(listeStocks)

        colonne.addView(ui.titre("Surfaces (bases de volume)"))
        colonne.addView(ui.texte(12f).apply {
            text = "Terrain naturel, fond de fouille… : une surface peut servir de base au volume d'un stock (mode B)."
        })
        colonne.addView(ui.bouton("+ Nouvelle surface levée") { creerStock(TypeLeve.SURFACE) })
        colonne.addView(ui.bouton("Importer une surface (LandXML Mensura, DXF)…") { importerSurface() })
        colonne.addView(listeSurfaces)

        colonne.addView(ui.titre("Récepteur"))
        colonne.addView(texteRecepteur)
        colonne.addView(ui.ligneBoutons(
            ui.bouton("Choisir le récepteur") { ecran.choisirRecepteur() },
            ui.bouton("Reconnecter") { ecran.reconnecter() },
            ui.bouton("Couper") { ecran.couperRecepteur() },
        ))

        colonne.addView(ui.titre("Hauteur de canne (rappel)"))
        colonne.addView(texteCanne)
        colonne.addView(ui.bouton("Modifier la hauteur de canne") { ecran.modifierHauteurCanne() })

        colonne.addView(ui.titre("Outils"))
        colonne.addView(ui.bouton("Diagnostic et journal") { ecran.afficherDiagnostic() })
        addView(colonne)
    }

    init {
        remplir()
    }

    override fun rafraichir() {
        texteRecepteur.text = "État : ${ecran.recepteur.etatBluetooth}"
        texteCanne.text = "${ecran.hauteurCanneEnTexte()} — à vérifier dans Emlid Flow"
    }

    /** Réécrit le nom du chantier et la liste des stocks (après un changement). */
    private fun remplir() {
        val chantier = depot.chantierActif()
        texteChantier.text = chantier?.nom ?: "Aucun chantier : créez-en un."
        listeStocks.removeAllViews()
        listeSurfaces.removeAllViews()
        if (chantier == null) return
        val tous = depot.stocksDuChantier(chantier.id)
        val surfaces = depot.surfacesDuChantier(chantier.id)
        val stocks = tous.filter { it.type == TypeLeve.STOCK }
        if (stocks.isEmpty()) {
            listeStocks.addView(ui.texte().apply { text = "Aucun stock dans ce chantier." })
        }
        for (stock in stocks) {
            val base = stock.baseSurfaceId?.let { "\nBase : ${surfaces.nom(it) ?: "surface supprimée"}" }.orEmpty()
            val resume = "${stock.nom}\n" +
                "B ${stock.nombreDePoints(ModeLeve.BAS_DE_TALUS)} · " +
                "H ${stock.nombreDePoints(ModeLeve.HAUT_DE_TALUS)} · " +
                "C ${stock.nombreDePoints(ModeLeve.POINT_CARACTERISTIQUE)} points$base"
            listeStocks.addView(boutonAvecAppuiLong(resume, { ecran.afficherLeve(stock) }) { actionsStock(stock) })
        }
        if (stocks.isNotEmpty()) listeStocks.addView(ui.texte(12f).apply { text = "Appui long sur un stock : renommer ou supprimer." })

        for (levee in surfaces.levees) {
            val resume = "${levee.nom}  (levée)\n${levee.points.size} points" +
                if (levee.nombreDePoints(ModeLeve.BAS_DE_TALUS) >= 3) " · avec contour" else ""
            listeSurfaces.addView(boutonAvecAppuiLong(resume, { ecran.afficherLeve(levee) }) { actionsStock(levee) })
        }
        for (importee in surfaces.importees.sortedByDescending { it.creeLeMs }) {
            val resume = "${importee.nom}  (${importee.origine})\n${importee.faces.size} triangles · ${importee.nomFichier}"
            listeSurfaces.addView(boutonAvecAppuiLong(resume, { ecran.afficherSurfaceImportee(importee) }) { actionsSurfaceImportee(importee) })
        }
        if (surfaces.levees.isEmpty() && surfaces.importees.isEmpty()) {
            listeSurfaces.addView(ui.texte().apply { text = "Aucune surface dans ce chantier." })
        } else {
            listeSurfaces.addView(ui.texte(12f).apply { text = "Appui long sur une surface : renommer ou supprimer." })
        }
    }

    private fun boutonAvecAppuiLong(texte: String, action: () -> Unit, appuiLong: () -> Unit) =
        ui.bouton(texte) { action() }.apply {
            setOnLongClickListener {
                appuiLong()
                true
            }
        }

    // --- Chantiers ---

    private fun creerChantier() {
        ecran.demanderNom("Nom du nouveau chantier", "") { nom ->
            val chantier = Chantier(UUID.randomUUID().toString(), nom, System.currentTimeMillis())
            depot.chantiers.add(chantier)
            depot.chantierActifId = chantier.id
            ecran.enregistrerLeves()
            remplir()
        }
    }

    private fun choisirChantier() {
        val chantiers = depot.chantiers.sortedBy { it.nom.lowercase() }
        if (chantiers.isEmpty()) {
            creerChantier()
            return
        }
        AlertDialog.Builder(ecran)
            .setTitle("Chantier actif")
            .setItems(chantiers.map { it.nom }.toTypedArray()) { _, numero ->
                depot.chantierActifId = chantiers[numero].id
                ecran.enregistrerLeves()
                remplir()
            }
            .show()
    }

    /** Choix d'un fichier de points, puis écran d'import (création des stocks par plages de noms). */
    private fun importer() {
        if (depot.chantierActif() == null) {
            ecran.message("Créez d'abord un chantier.")
            return
        }
        ecran.ouvrirFichierTexte { nomFichier, texte -> ecran.afficherImport(nomFichier, texte) }
    }

    /**
     * Import d'une surface : LandXML (Mensura) ou DXF (faces 3D, maillage polyface).
     * Si le fichier en contient plusieurs, l'utilisateur choisit lesquelles garder.
     */
    private fun importerSurface() {
        val chantier = depot.chantierActif() ?: run {
            ecran.message("Créez d'abord un chantier.")
            return
        }
        ecran.ouvrirFichierTexte { nomFichier, texte ->
            val estLandXml = texte.contains("<LandXML")
            val estDxf = !estLandXml && (texte.startsWith("AutoCAD Binary DXF") || (texte.contains("SECTION") && texte.contains("ENTITIES")))
            if (!estLandXml && !estDxf) {
                messageLong("Fichier non reconnu", "« $nomFichier » n'est ni un LandXML ni un DXF (texte).")
                return@ouvrirFichierTexte
            }
            val lecture = if (estLandXml) lireLandXml(texte) else lireDxf(texte)
            val origine = if (estLandXml) "LandXML" else "DXF"
            when {
                lecture.surfaces.isEmpty() -> messageLong("Aucune surface lue", remarques(lecture).ifEmpty { "Aucune surface dans ce fichier." })
                lecture.surfaces.size == 1 -> garderSurfaces(chantier, lecture.surfaces, lecture, origine, nomFichier)
                else -> {
                    val coches = BooleanArray(lecture.surfaces.size) { true }
                    AlertDialog.Builder(ecran)
                        .setTitle("Surfaces à importer")
                        .setMultiChoiceItems(
                            lecture.surfaces.map { "${it.nom} (${it.faces.size} triangles)" }.toTypedArray(), coches,
                        ) { _, i, coche -> coches[i] = coche }
                        .setPositiveButton("Importer") { _, _ ->
                            garderSurfaces(chantier, lecture.surfaces.filterIndexed { i, _ -> coches[i] }, lecture, origine, nomFichier)
                        }
                        .setNegativeButton("Annuler", null)
                        .show()
                }
            }
        }
    }

    private fun garderSurfaces(chantier: Chantier, choisies: List<SurfaceLue>, lecture: LectureSurfaces, origine: String, nomFichier: String) {
        if (choisies.isEmpty()) return
        val maintenant = System.currentTimeMillis()
        for (lue in choisies) {
            depot.surfacesImportees.add(SurfaceImportee(UUID.randomUUID().toString(), chantier.id, lue.nom, origine, nomFichier, maintenant, lue.sommets, lue.faces))
        }
        if (!ecran.enregistrerSurfaces()) return
        ecran.journal.ajouter("INFO", "Import $nomFichier : ${choisies.joinToString { "${it.nom} (${it.faces.size} triangles)" }}")
        remplir()
        val texte = choisies.joinToString("\n") { "• ${it.nom} : ${it.sommets.size} points, ${it.faces.size} triangles" } +
            "\n\nPour l'utiliser : ouvrir un stock → « Changer la base du volume… »." +
            remarques(lecture).let { if (it.isEmpty()) "" else "\n\n$it" }
        messageLong("Surface(s) importée(s)", texte)
    }

    /** Erreurs et avertissements de lecture, à montrer à l'utilisateur. */
    private fun remarques(lecture: LectureSurfaces): String =
        (lecture.erreurs.take(10).map { "Erreur : $it" } + lecture.avertissements.take(10).map { "Attention : $it" }).joinToString("\n")

    private fun messageLong(titre: String, texte: String) {
        AlertDialog.Builder(ecran).setTitle(titre).setMessage(texte).setPositiveButton("OK", null).show()
    }

    private fun actionsSurfaceImportee(surface: SurfaceImportee) {
        AlertDialog.Builder(ecran)
            .setTitle(surface.nom)
            .setItems(arrayOf("Renommer", "Supprimer")) { _, choix ->
                if (choix == 0) {
                    ecran.demanderNom("Nouveau nom de la surface", surface.nom) { nom ->
                        surface.nom = nom
                        ecran.enregistrerSurfaces()
                        remplir()
                    }
                } else {
                    val utilisateurs = depot.stocks.filter { it.baseSurfaceId == surface.id }
                    ecran.confirmer("Supprimer la surface ?", "« ${surface.nom} » sera effacée du téléphone.${avertissementBase(utilisateurs)}") {
                        utilisateurs.forEach { it.baseSurfaceId = null }
                        depot.surfacesImportees.remove(surface)
                        ecran.enregistrerSurfaces()
                        ecran.enregistrerLeves()
                        remplir()
                    }
                }
            }
            .show()
    }

    /** Message ajouté quand on supprime une surface qui sert de base à des stocks. */
    private fun avertissementBase(stocks: List<Stock>): String =
        if (stocks.isEmpty()) "" else "\n\nElle sert de base à : ${stocks.joinToString(", ") { it.nom }}. Ces stocks reviendront à la base « points B » (mode A)."

    /** Exporte tous les stocks du chantier actif (volumes recalculés). */
    private fun exporterChantier() {
        val chantier = depot.chantierActif() ?: run {
            ecran.message("Créez d'abord un chantier.")
            return
        }
        val stocks = depot.stocksDuChantier(chantier.id).filter { it.type == TypeLeve.STOCK }.sortedBy { it.creeLeMs }
        if (stocks.isEmpty()) {
            ecran.message("Aucun stock dans ce chantier.")
            return
        }
        Exports(ecran, chantier, stocks, chantier.nom, toutesLesSurfaces = true).proposer()
    }

    // --- Stocks ---

    /** Nouveau stock, ou nouvelle surface levée (terrain naturel…). */
    private fun creerStock(type: TypeLeve) {
        val chantier = depot.chantierActif()
        if (chantier == null) {
            ecran.message("Créez d'abord un chantier.")
            return
        }
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        val surface = type == TypeLeve.SURFACE
        val nomPropose = nomStockParDefaut(date, if (surface) "${chantier.nom} surface" else chantier.nom, depot.stocksDuChantier(chantier.id).map { it.nom })
        ecran.demanderNom(if (surface) "Nom de la nouvelle surface" else "Nom du nouveau stock", nomPropose) { nom ->
            val stock = Stock(UUID.randomUUID().toString(), chantier.id, nom, System.currentTimeMillis(), type)
            depot.stocks.add(stock)
            if (ecran.enregistrerLeves()) ecran.afficherLeve(stock)
        }
    }

    private fun actionsStock(stock: Stock) {
        AlertDialog.Builder(ecran)
            .setTitle(stock.nom)
            .setItems(arrayOf("Renommer", "Supprimer")) { _, choix ->
                if (choix == 0) {
                    ecran.demanderNom(if (stock.type == TypeLeve.SURFACE) "Nouveau nom de la surface" else "Nouveau nom du stock", stock.nom) { nom ->
                        stock.nom = nom
                        ecran.enregistrerLeves()
                        remplir()
                    }
                } else {
                    val utilisateurs = depot.stocks.filter { it.baseSurfaceId == stock.id }
                    val quoi = if (stock.type == TypeLeve.SURFACE) "la surface" else "le stock"
                    ecran.confirmer("Supprimer $quoi ?", "« ${stock.nom} » et ses ${stock.points.size} points seront effacés.${avertissementBase(utilisateurs)}") {
                        utilisateurs.forEach { it.baseSurfaceId = null }
                        depot.stocks.remove(stock)
                        ecran.enregistrerLeves()
                        remplir()
                    }
                }
            }
            .show()
    }
}
