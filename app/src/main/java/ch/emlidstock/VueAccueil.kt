package ch.emlidstock

import android.app.AlertDialog
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import ch.emlidstock.noyau.leve.Chantier
import ch.emlidstock.noyau.leve.ModeLeve
import ch.emlidstock.noyau.leve.Stock
import ch.emlidstock.noyau.leve.nomStockParDefaut
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Écran d'accueil : chantier actif, ses stocks (ouvrir, créer, renommer, supprimer),
 * connexion au récepteur, hauteur de canne, accès au diagnostic.
 */
class VueAccueil(private val ecran: EcranPrincipal) : Vue {

    private val ui = ecran.ui
    private val depot = ecran.depot
    private val texteChantier = ui.texte(16f)
    private val listeStocks = LinearLayout(ecran).apply { orientation = LinearLayout.VERTICAL }
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
        colonne.addView(ui.bouton("+ Nouveau stock") { creerStock() })
        colonne.addView(ui.bouton("Importer des points (CSV Emlid, TXT)…") { importer() })
        colonne.addView(listeStocks)

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
        if (chantier == null) return
        val stocks = depot.stocksDuChantier(chantier.id)
        if (stocks.isEmpty()) {
            listeStocks.addView(ui.texte().apply { text = "Aucun stock dans ce chantier." })
        }
        for (stock in stocks) {
            val resume = "${stock.nom}\n" +
                "B ${stock.nombreDePoints(ModeLeve.BAS_DE_TALUS)} · " +
                "H ${stock.nombreDePoints(ModeLeve.HAUT_DE_TALUS)} · " +
                "C ${stock.nombreDePoints(ModeLeve.POINT_CARACTERISTIQUE)} points"
            val bouton = ui.bouton(resume) { ecran.afficherLeve(stock) }
            bouton.setOnLongClickListener {
                actionsStock(stock)
                true
            }
            listeStocks.addView(bouton)
        }
        listeStocks.addView(ui.texte(12f).apply { text = "Appui long sur un stock : renommer ou supprimer." })
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

    /** Exporte tous les stocks du chantier actif (volumes recalculés). */
    private fun exporterChantier() {
        val chantier = depot.chantierActif() ?: run {
            ecran.message("Créez d'abord un chantier.")
            return
        }
        val stocks = depot.stocksDuChantier(chantier.id).sortedBy { it.creeLeMs }
        if (stocks.isEmpty()) {
            ecran.message("Aucun stock dans ce chantier.")
            return
        }
        Exports(ecran, chantier, stocks, chantier.nom).proposer()
    }

    // --- Stocks ---

    private fun creerStock() {
        val chantier = depot.chantierActif()
        if (chantier == null) {
            ecran.message("Créez d'abord un chantier.")
            return
        }
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        val nomPropose = nomStockParDefaut(date, chantier.nom, depot.stocksDuChantier(chantier.id).map { it.nom })
        ecran.demanderNom("Nom du nouveau stock", nomPropose) { nom ->
            val stock = Stock(UUID.randomUUID().toString(), chantier.id, nom, System.currentTimeMillis())
            depot.stocks.add(stock)
            if (ecran.enregistrerLeves()) ecran.afficherLeve(stock)
        }
    }

    private fun actionsStock(stock: Stock) {
        AlertDialog.Builder(ecran)
            .setTitle(stock.nom)
            .setItems(arrayOf("Renommer", "Supprimer")) { _, choix ->
                if (choix == 0) {
                    ecran.demanderNom("Nouveau nom du stock", stock.nom) { nom ->
                        stock.nom = nom
                        ecran.enregistrerLeves()
                        remplir()
                    }
                } else {
                    ecran.confirmer("Supprimer le stock ?", "« ${stock.nom} » et ses ${stock.points.size} points seront effacés.") {
                        depot.stocks.remove(stock)
                        ecran.enregistrerLeves()
                        remplir()
                    }
                }
            }
            .show()
    }
}
