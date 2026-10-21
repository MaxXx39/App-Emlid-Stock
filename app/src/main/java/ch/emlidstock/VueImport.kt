package ch.emlidstock

import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import ch.emlidstock.noyau.importation.PointImporte
import ch.emlidstock.noyau.importation.creerStockImporte
import ch.emlidstock.noyau.importation.detecterSeries
import ch.emlidstock.noyau.importation.ecrireListeDeNoms
import ch.emlidstock.noyau.importation.lireListeDeNoms
import ch.emlidstock.noyau.importation.lirePoints
import ch.emlidstock.noyau.leve.ModeLeve
import ch.emlidstock.noyau.leve.PointLeve
import ch.emlidstock.noyau.leve.TypeLeve
import ch.emlidstock.noyau.leve.nomStockParDefaut
import ch.emlidstock.noyau.surface.calculerSurfaceLevee
import ch.emlidstock.noyau.volume.calculerVolumeStock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Import d'un fichier de points (CSV Emlid ou TXT), étape 8 : le fichier est lu une fois, puis on crée
 * autant de stocks que voulu, chacun avec ses plages de noms (comme dans l'appli topo V1) :
 * contour « b100 à b116 », lignes de haut de talus « h100 à h114 » (plusieurs lignes séparées par « ; »).
 * Les autres points du fichier situés dans le contour deviennent des points caractéristiques.
 * On peut aussi créer une surface (terrain naturel…, étape 9) : contour facultatif ; sans contour,
 * tous les points du fichier sont gardés.
 */
class VueImport(private val ecran: EcranPrincipal, private val nomFichier: String, texte: String) : Vue {

    private val ui = ecran.ui
    private val lecture = lirePoints(texte)
    private val noms = lecture.points.map { it.nom }

    private val champNom = EditText(ecran).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
    private val champContour = EditText(ecran).apply { hint = "ex. b100 à b116" }
    private val champLignes = EditText(ecran).apply { hint = "ex. h100 à h114   (plusieurs lignes : séparer par ;)" }
    private val caseFermees = CheckBox(ecran).apply { text = "Lignes de haut de talus fermées (dernier point relié au premier)" }
    private val texteCrees = ui.zoneTexte(13f)
    private val crees = mutableListOf<String>()
    private val carte = CarteAvecBoutons(ecran).also { c ->
        // Aperçu : tous les points du fichier, en points caractéristiques (sans rôle encore).
        c.carte.points = lecture.points.mapIndexed { i, p -> apercu(p, i + 1) }
    }

    override val racine: View = ScrollView(ecran).apply {
        val colonne = ui.colonne()
        colonne.addView(ui.bouton("← Accueil") { ecran.afficherAccueil() })
        colonne.addView(ui.titre("Import : $nomFichier"))
        colonne.addView(ui.zoneTexte(13f).apply {
            text = resume()
            setTextColor(if (lecture.erreurs.isEmpty()) Color.BLACK else EcranPrincipal.ROUGE)
        })
        if (lecture.points.isNotEmpty()) {
            val hauteurCarte = (ecran.resources.displayMetrics.heightPixels * 0.35).toInt()
            colonne.addView(carte.racine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hauteurCarte))

            colonne.addView(ui.titre("Séries trouvées dans le fichier"))
            colonne.addView(ui.texte(12f).apply { text = "Toucher une série pour l'utiliser comme contour ou comme ligne de haut de talus." })
            for (serie in detecterSeries(noms)) {
                val texteSerie = ecrireListeDeNoms(serie)
                colonne.addView(ui.bouton("$texteSerie  (${serie.size} points)") { utiliserSerie(texteSerie) })
            }

            colonne.addView(ui.titre("Nouveau stock ou nouvelle surface"))
            colonne.addView(ui.texte(13f).apply { text = "Nom" })
            colonne.addView(champNom)
            colonne.addView(ui.texte(13f).apply { text = "Contour = bas de talus, dans l'ordre (au moins 3 points ; facultatif pour une surface)" })
            colonne.addView(champContour)
            colonne.addView(ui.texte(13f).apply { text = "Lignes de haut de talus / de rupture (facultatif)" })
            colonne.addView(champLignes)
            colonne.addView(caseFermees)
            colonne.addView(ui.bouton("Créer le stock") { creerStock(TypeLeve.STOCK) })
            colonne.addView(ui.bouton("Créer une surface (terrain naturel…, base de volume)") { creerStock(TypeLeve.SURFACE) })
            colonne.addView(texteCrees)
            colonne.addView(ui.bouton("Terminé") { ecran.afficherAccueil() })
        }
        addView(colonne)
    }

    init {
        proposerNom()
        afficherCrees()
    }

    override fun rafraichir() {
        carte.carte.position = ecran.recepteur.dernierePosition
        carte.carte.positionFiable = ecran.recepteur.derniereCompensation?.estActive == true
    }

    private fun resume(): String = buildString {
        append("${lecture.points.size} points lus (format ${lecture.format}).")
        if (lecture.erreurs.isNotEmpty()) {
            append("\n${lecture.erreurs.size} ligne(s) illisible(s) :")
            lecture.erreurs.take(20).forEach { append("\n  ligne ${it.ligne} : ${it.message}") }
            if (lecture.erreurs.size > 20) append("\n  …")
        }
        lecture.avertissements.take(10).forEach { append("\nAttention : $it") }
        if (lecture.points.isNotEmpty()) append("\nCoordonnées lues telles quelles (MN95, altitudes supposées RAN95).")
    }

    /** Point d'aperçu sur la carte (rôle provisoire « C »). */
    private fun apercu(p: PointImporte, numero: Int) = PointLeve(
        nom = p.nom, mode = ModeLeve.POINT_CARACTERISTIQUE, ligne = null, numero = numero, est = p.est, nord = p.nord,
        altitude = p.altitude, latitudeDeg = 0.0, longitudeDeg = 0.0, hauteurEllipsoidaleM = 0.0, horodatageMs = 0,
        qualiteGga = 0, nbSatellites = null, hdop = null, ageCorrectionsS = null, stationReference = "", inclinaisonDeg = null,
    )

    private fun proposerNom() {
        val chantier = ecran.depot.chantierActif() ?: return
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        champNom.setText(nomStockParDefaut(date, chantier.nom, ecran.depot.stocksDuChantier(chantier.id).map { it.nom }))
    }

    /** Une série touchée devient le contour, ou une ligne de haut de talus de plus. */
    private fun utiliserSerie(serie: String) {
        AlertDialog.Builder(ecran)
            .setTitle(serie)
            .setItems(arrayOf("Contour (bas de talus)", "Ligne de haut de talus")) { _, choix ->
                if (choix == 0) {
                    champContour.setText(serie)
                } else {
                    val actuel = champLignes.text.toString().trim()
                    champLignes.setText(if (actuel.isEmpty()) serie else "$actuel ; $serie")
                }
            }
            .show()
    }

    private fun creerStock(type: TypeLeve) {
        val chantier = ecran.depot.chantierActif() ?: run {
            ecran.message("Créez d'abord un chantier (accueil).")
            return
        }
        val nom = champNom.text.toString().trim()
        if (nom.isEmpty()) {
            ecran.message("Donnez un nom au stock ou à la surface.")
            return
        }
        val contour = lireListeDeNoms(champContour.text.toString(), noms)
        val lignesTapees = champLignes.text.toString().split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val lignes = lignesTapees.map { lireListeDeNoms(it, noms) }
        val problemes = contour.erreurs + lignes.flatMap { it.erreurs }
        if (problemes.isNotEmpty()) {
            message("Problème dans les listes de points", problemes.joinToString("\n"))
            return
        }
        val resultat = creerStockImporte(
            UUID.randomUUID().toString(), chantier.id, nom, System.currentTimeMillis(), lecture.points,
            contour.noms, lignes.map { it.noms to caseFermees.isChecked }, nomFichier, type,
        )
        val stock = resultat.stock ?: run {
            message("Stock non créé", resultat.erreur ?: "erreur inconnue")
            return
        }
        ecran.depot.stocks.add(stock)
        if (!ecran.enregistrerLeves()) return
        val texteVolume = if (type == TypeLeve.SURFACE) {
            val surface = calculerSurfaceLevee(stock.points, stock.lignesFermees, emptySet())
            if (surface.erreur == null) "surface : ${surface.triangles.size} triangles" else "surface impossible : ${surface.erreur}"
        } else {
            val volume = calculerVolumeStock(stock.points, stock.lignesFermees)
            if (volume.erreur == null) "remblai ${nombre(volume.remblai, 3)} m³" else "volume impossible : ${volume.erreur}"
        }
        crees.add("${stock.nom} : B ${stock.nombreDePoints(ModeLeve.BAS_DE_TALUS)}, H ${stock.nombreDePoints(ModeLeve.HAUT_DE_TALUS)}, " +
            "C ${stock.nombreDePoints(ModeLeve.POINT_CARACTERISTIQUE)} — $texteVolume")
        val quoi = if (type == TypeLeve.SURFACE) "surface" else "stock"
        ecran.journal.ajouter("INFO", "Import $nomFichier : $quoi ${stock.nom} créé ($texteVolume)")
        champContour.setText("")
        champLignes.setText("")
        proposerNom()
        afficherCrees()
        ecran.message(if (type == TypeLeve.SURFACE) "Surface « ${stock.nom} » créée." else "Stock « ${stock.nom} » créé.")
    }

    private fun afficherCrees() {
        texteCrees.text = if (crees.isEmpty()) "" else "Stocks créés depuis ce fichier :\n" + crees.joinToString("\n") { "• $it" }
    }

    private fun message(titre: String, texte: String) {
        AlertDialog.Builder(ecran).setTitle(titre).setMessage(texte).setPositiveButton("OK", null).show()
    }
}
