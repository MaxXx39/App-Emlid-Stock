package ch.emlidstock

import android.app.AlertDialog
import android.widget.EditText
import android.widget.LinearLayout
import ch.emlidstock.noyau.controle.equidistanceAuto
import ch.emlidstock.noyau.controle.plageAltitudes
import ch.emlidstock.noyau.export.StockExporte
import ch.emlidstock.noyau.export.ecrireLandXml
import ch.emlidstock.noyau.export.ecrireProjetV1
import ch.emlidstock.noyau.export.genererCsv
import ch.emlidstock.noyau.export.genererDxf
import ch.emlidstock.noyau.leve.Chantier
import ch.emlidstock.noyau.leve.Stock
import ch.emlidstock.noyau.leve.TypeLeve
import ch.emlidstock.noyau.leve.exporterPointsTxt
import ch.emlidstock.noyau.leve.nomDeFichier
import ch.emlidstock.noyau.surface.calculerSurfaceLevee
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Menu « Exporter… » : PDF, CSV (Excel), DXF, LandXML, TXT, fichier projet pour l'appli topo (V1).
 * Le fichier est enregistré là où l'utilisateur le choisit (Téléchargements, Google Drive…).
 * - [stocks] : les stocks à exporter (un seul depuis l'écran de levé, tous ceux du chantier depuis l'accueil),
 *   ou une surface levée ; le volume (avec la base choisie) ou la surface est recalculé ici.
 * - [nomBase] : début du nom des fichiers.
 * - [toutesLesSurfaces] : le fichier projet V1 contient toutes les surfaces du chantier ; sinon seulement
 *   les surfaces qui servent de base aux stocks exportés.
 */
class Exports(
    private val ecran: EcranPrincipal,
    private val chantier: Chantier,
    private val stocks: List<Stock>,
    private val nomBase: String,
    private val toutesLesSurfaces: Boolean = false,
) {

    private val surfaces = ecran.depot.surfacesDuChantier(chantier.id)
    private val exportes = stocks.map { exporte(it) }

    /** Stock avec son volume, ou surface levée avec ses triangles. */
    private fun exporte(stock: Stock): StockExporte =
        if (stock.type == TypeLeve.SURFACE) {
            StockExporte(stock, null, calculerSurfaceLevee(stock.points, stock.lignesFermees, stock.trianglesSupprimes).triangles)
        } else {
            StockExporte(stock, surfaces.volume(stock))
        }

    fun proposer() {
        if (exportes.all { it.points.isEmpty() }) {
            ecran.message("Aucun point à exporter.")
            return
        }
        val choix = listOf(
            "PDF : plan, volumes et cartouche" to { demanderCartouchePuisPdf() },
            "CSV (Excel) : volumes et points" to { enregistrer("csv", "text/csv", genererCsv(exportes)) },
            "DXF (AutoCAD, Mensura)" to { enregistrer("dxf", "application/dxf", genererDxf(exportes, equidistance())) },
            "LandXML (surfaces TIN)" to { exporterLandXml() },
            "TXT : points (nom, E, N, H)" to { enregistrer("txt", "text/plain", exportes.joinToString("") { exporterPointsTxt(it.points) }) },
            "Fichier projet pour l'appli topo (V1, bureau)" to { exporterProjetV1() },
        )
        AlertDialog.Builder(ecran)
            .setTitle("Exporter : $nomBase")
            .setItems(choix.map { it.first }.toTypedArray()) { _, i -> choix[i].second() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun enregistrer(extension: String, type: String, texte: String) =
        ecran.exporterFichier(nomDeFichier(nomBase, extension), type, texte.toByteArray(Charsets.UTF_8))

    /** Équidistance des courbes de niveau du DXF : automatique, selon la hauteur des stocks. */
    private fun equidistance(): Double? {
        val triangles = exportes.flatMap { it.triangles }
        if (triangles.isEmpty()) return null
        val plage = plageAltitudes(triangles)
        return equidistanceAuto(plage.min, plage.max)
    }

    private fun exporterLandXml() {
        val tin = exportes.filter { it.triangles.isNotEmpty() }.map { it.nom to it.triangles }
        if (tin.isEmpty()) {
            ecran.message("Pas de surface : le calcul n'a pas pu être fait (stock : il faut au moins 3 points B).")
            return
        }
        enregistrer("xml", "application/xml", ecrireLandXml(tin, chantier.nom))
    }

    private fun exporterProjetV1() {
        // Surfaces ajoutées au projet : toutes celles du chantier, ou les bases des stocks exportés.
        fun utile(id: String) = toutesLesSurfaces || stocks.any { it.baseSurfaceId == id }
        val levees = surfaces.levees.filter { l -> utile(l.id) && stocks.none { it.id == l.id } }.map { exporte(it) }
        val importees = surfaces.importees.filter { utile(it.id) }
        val texte = ecrireProjetV1(nomBase, exportes + levees, chantier.affaire, chantier.dessinePar, importees = importees)
        ecran.exporterFichier(nomDeFichier(nomBase, "topo.json"), "application/json", texte.toByteArray(Charsets.UTF_8))
    }

    /** Cartouche (affaire, dessiné par) gardé dans le chantier, puis choix des calques du plan, puis PDF. */
    private fun demanderCartouchePuisPdf() {
        val champAffaire = EditText(ecran).apply { setText(chantier.affaire); hint = "Affaire (ex. 10 203)" }
        val champDessinePar = EditText(ecran).apply { setText(chantier.dessinePar); hint = "Dessiné par (initiales)" }
        val formulaire = LinearLayout(ecran).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ecran.ui.dp(20), ecran.ui.dp(8), ecran.ui.dp(20), 0)
            addView(ecran.ui.texte(13f).apply { text = "Affaire" })
            addView(champAffaire)
            addView(ecran.ui.texte(13f).apply { text = "Dessiné par" })
            addView(champDessinePar)
        }
        AlertDialog.Builder(ecran)
            .setTitle("Cartouche du PDF")
            .setView(formulaire)
            .setPositiveButton("Suite") { _, _ ->
                chantier.affaire = champAffaire.text.toString().trim()
                chantier.dessinePar = champDessinePar.text.toString().trim()
                ecran.enregistrerLeves()
                choisirCalquesPuisPdf()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun choisirCalquesPuisPdf() {
        val titres = arrayOf(
            "Triangles", "Noms des points", "Remblai (rouge) / déblai (jaune)", "Courbes de niveau",
            "Couleurs selon l'altitude", "Photo aérienne (swisstopo)", "Cadastre",
        )
        val cles = listOf("triangles", "noms", "remblaiDeblai", "courbes", "couleurs", "photo", "cadastre")
        val defauts = CalquesPdf()
        val valeursDefaut = listOf(defauts.triangles, defauts.noms, defauts.remblaiDeblai, defauts.courbes, defauts.couleurs, defauts.photo, defauts.cadastre)
        val preferences = ecran.preferences()
        val coches = BooleanArray(cles.size) { preferences.getBoolean("pdf_${cles[it]}", valeursDefaut[it]) }
        AlertDialog.Builder(ecran)
            .setTitle("Calques du plan")
            .setMultiChoiceItems(titres, coches) { _, i, coche -> coches[i] = coche }
            .setPositiveButton("Créer le PDF") { _, _ ->
                val edition = preferences.edit()
                cles.forEachIndexed { i, cle -> edition.putBoolean("pdf_$cle", coches[i]) }
                edition.apply()
                creerPdf(CalquesPdf(coches[0], coches[1], coches[2], coches[3], coches[4], coches[5], coches[6]))
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Le PDF se crée hors de l'écran (la photo aérienne se télécharge), puis on choisit où l'enregistrer. */
    private fun creerPdf(calques: CalquesPdf) {
        ecran.message("Création du PDF…")
        val date = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(Date())
        val cartouche = Cartouche(chantier.affaire, date, chantier.dessinePar, chantier.nom)
        thread(name = "creation-pdf") {
            try {
                val (octets, messageFond) = ExportPdf(exportes, cartouche, calques).creer()
                ecran.runOnUiThread {
                    if (messageFond != null) ecran.message(messageFond)
                    ecran.exporterFichier(nomDeFichier(nomBase, "pdf"), "application/pdf", octets)
                }
            } catch (erreur: Exception) {
                ecran.message("PDF impossible : ${erreur.message}")
            }
        }
    }
}
