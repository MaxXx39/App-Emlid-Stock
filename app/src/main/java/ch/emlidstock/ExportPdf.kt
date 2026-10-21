package ch.emlidstock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import ch.emlidstock.noyau.carte.Fond
import ch.emlidstock.noyau.carte.niveauDeZoom
import ch.emlidstock.noyau.carte.tuilesPourZone
import ch.emlidstock.noyau.controle.Couleur
import ch.emlidstock.noyau.controle.bandesDeNiveau
import ch.emlidstock.noyau.controle.couleurAltitude
import ch.emlidstock.noyau.controle.courbesDeNiveau
import ch.emlidstock.noyau.controle.equidistanceAuto
import ch.emlidstock.noyau.controle.plageAltitudes
import ch.emlidstock.noyau.controle.zonesRemblaiDeblai
import ch.emlidstock.noyau.export.StockExporte
import ch.emlidstock.noyau.export.barreEchellePapier
import ch.emlidstock.noyau.export.choisirEchelle
import ch.emlidstock.noyau.export.trois
import ch.emlidstock.noyau.volume.PointPlan
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Calques du plan imprimé (comme dans l'appli topo V1). */
data class CalquesPdf(
    val triangles: Boolean = true,
    val noms: Boolean = true,
    val remblaiDeblai: Boolean = true,
    val courbes: Boolean = false,
    val couleurs: Boolean = false,
    val photo: Boolean = false,
    val cadastre: Boolean = false,
)

/** Données du cartouche : affaire, date, dessiné par, nom du chantier. */
data class Cartouche(val affaire: String, val date: String, val dessinePar: String, val chantier: String)

/**
 * Export PDF : une feuille A4 paysage avec le plan à l'échelle, le tableau des volumes,
 * les légendes et le cartouche. Mise en page reprise de l'appli topo V1 (src/export/pdf.js).
 * Tout est dessiné en millimètres (la toile est mise à l'échelle 1 mm = 72 / 25.4 points PDF).
 * Le plan est vectoriel (net à l'impression) ; seule la photo aérienne est une image.
 *
 * À lancer hors du fil de l'écran : la photo aérienne se télécharge pendant la création.
 * Renvoie le PDF (octets) et un message si le fond n'a pas pu être mis (ou null).
 */
class ExportPdf(private val stocks: List<StockExporte>, private val cartouche: Cartouche, private val calques: CalquesPdf) {

    private var messageFond: String? = null

    fun creer(): Pair<ByteArray, String?> {
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_POINTS_LARGEUR, PAGE_POINTS_HAUTEUR, 1).create())
        val toile = page.canvas
        toile.scale(POINTS_PAR_MM, POINTS_PAR_MM)
        dessinerFeuille(toile)
        document.finishPage(page)
        val sortie = ByteArrayOutputStream()
        document.writeTo(sortie)
        document.close()
        return Pair(sortie.toByteArray(), messageFond)
    }

    // --- Pinceaux (épaisseurs en mm) ---

    private fun trait(couleur: Int, epaisseur: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = couleur
        style = Paint.Style.STROKE
        strokeWidth = epaisseur
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun plein(couleur: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = couleur; style = Paint.Style.FILL }

    /** Texte : taille en points typographiques (1 pt = 0.3528 mm). */
    private fun texte(taillePt: Float, couleur: Int = TEXTE, gras: Boolean = false, alignement: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = couleur
            textSize = taillePt * 0.3528f
            typeface = if (gras) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textAlign = alignement
        }

    private fun couleur(c: Couleur, opacite: Int = 255) = Color.argb(opacite, c.rouge, c.vert, c.bleu)

    // =====================================================================
    // Feuille
    // =====================================================================

    private fun dessinerFeuille(toile: Canvas) {
        toile.drawColor(Color.WHITE)
        val cadre = trait(TEXTE, 0.4f)
        toile.drawRect(MARGE, MARGE, PAGE_LARGEUR - MARGE, PAGE_HAUTEUR - MARGE, cadre)
        toile.drawRect(PLAN.left, PLAN.top, PLAN.right, PLAN.bottom, cadre)
        val echelle = dessinerPlan(toile)
        dessinerColonne(toile, echelle)
    }

    /** Plan à l'échelle normalisée qui fait tout tenir. Renvoie le dénominateur de l'échelle. */
    private fun dessinerPlan(toile: Canvas): Int {
        val calcules = stocks.filter { it.volumeValable }
        val tousTriangles = calcules.flatMap { it.resultat!!.trianglesDessus }
        val coordonnees = stocks.flatMap { s -> s.points.map { PointPlan(it.est, it.nord) } } +
            tousTriangles.flatMap { t -> t.sommets.map { PointPlan(it.est, it.nord) } }
        if (coordonnees.isEmpty()) return 500
        val estMin = coordonnees.minOf { it.est }
        val estMax = coordonnees.maxOf { it.est }
        val nordMin = coordonnees.minOf { it.nord }
        val nordMax = coordonnees.maxOf { it.nord }
        val echelle = choisirEchelle(estMax - estMin, nordMax - nordMin, PLAN.width() - 20.0, PLAN.height() - 30.0)
        val mmParMetre = 1000.0 / echelle
        val centreEst = (estMin + estMax) / 2
        val centreNord = (nordMin + nordMax) / 2
        val milieuX = PLAN.centerX()
        val milieuY = PLAN.top + (PLAN.height() - 10) / 2
        fun x(est: Double) = (milieuX + (est - centreEst) * mmParMetre).toFloat()
        fun y(nord: Double) = (milieuY - (nord - centreNord) * mmParMetre).toFloat()
        fun polygone(sommets: List<PointPlan>): Path = Path().apply {
            sommets.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.est), y(p.nord)) else lineTo(x(p.est), y(p.nord)) }
            close()
        }

        toile.save()
        toile.clipRect(PLAN)
        if (calques.photo || calques.cadastre) {
            dessinerFond(toile) { px, py -> Pair(centreEst + (px - milieuX) / mmParMetre, centreNord - (py - milieuY) / mmParMetre) }
        }

        // Remblai (rouge) / déblai (jaune) par paliers, ou couleurs selon l'altitude (pas les deux).
        val ecarts = calcules.flatMap { it.resultat!!.trianglesEcart }
        if (calques.remblaiDeblai && ecarts.isNotEmpty()) {
            for (zone in zonesRemblaiDeblai(ecarts).zones) {
                val chemin = polygone(zone.polygone)
                toile.drawPath(chemin, plein(couleur(zone.couleur)))
                toile.drawPath(chemin, trait(couleur(zone.couleur), 0.05f))
            }
        }
        val plage = if (tousTriangles.isNotEmpty()) plageAltitudes(tousTriangles) else null
        val equidistance = plage?.let { equidistanceAuto(it.min, it.max) }
        if (calques.couleurs && !calques.remblaiDeblai && plage != null && equidistance != null) {
            for (bande in bandesDeNiveau(tousTriangles, equidistance)) {
                toile.drawPath(polygone(bande.polygone), plein(couleur(couleurAltitude(bande.altitude + equidistance / 2, plage), 180)))
            }
        }
        if (calques.courbes && equidistance != null) {
            for (c in courbesDeNiveau(tousTriangles, equidistance)) {
                val rang = c.altitude / (5 * equidistance)
                val maitresse = kotlin.math.abs(rang - Math.round(rang)) < 1e-6
                toile.drawLine(x(c.a.est), y(c.a.nord), x(c.b.est), y(c.b.nord), trait(BRUN, if (maitresse) 0.25f else 0.1f))
            }
        }
        if (calques.triangles) {
            val pinceau = trait(GRIS_TRIANGLE, 0.08f)
            for (t in tousTriangles) toile.drawPath(polygone(t.sommets.map { PointPlan(it.est, it.nord) }), pinceau)
        }

        // Lignes de haut de talus, puis contours.
        for (s in stocks) {
            val parNom = s.points.associateBy { it.nom }
            for ((noms, fermee) in s.lignesHaut()) {
                val sommets = noms.mapNotNull { parNom[it] }
                for (i in 0 until sommets.size - 1) toile.drawLine(x(sommets[i].est), y(sommets[i].nord), x(sommets[i + 1].est), y(sommets[i + 1].nord), trait(ORANGE, 0.25f))
                if (fermee && sommets.size >= 3) toile.drawLine(x(sommets.last().est), y(sommets.last().nord), x(sommets[0].est), y(sommets[0].nord), trait(ORANGE, 0.25f))
            }
            val contour = s.contour().mapNotNull { parNom[it] }
            if (contour.size >= 3) toile.drawPath(polygone(contour.map { PointPlan(it.est, it.nord) }), trait(ROUGE, 0.35f))
        }

        // Points et noms.
        val taillePt = if (echelle <= 250) 6f else 5f
        val pinceauNom = texte(taillePt)
        for (s in stocks) for (p in s.points) {
            toile.drawCircle(x(p.est), y(p.nord), 0.3f, plein(TEXTE))
            if (calques.noms) toile.drawText(p.nom, x(p.est) + 0.6f, y(p.nord) - 0.4f, pinceauNom)
        }

        // Nom et volume de chaque stock, dans une étiquette blanche.
        for (s in calcules) {
            val r = s.resultat!!
            val sommets = r.trianglesDessus.flatMap { it.sommets }
            val cx = x(sommets.sumOf { it.est } / sommets.size)
            val cy = y(sommets.sumOf { it.nord } / sommets.size)
            val lignes = listOfNotNull(s.nom, "Remblai ${trois(r.remblai)} m³", if (r.deblai > 0) "Déblai ${trois(r.deblai)} m³" else null)
            val taille = if (echelle >= 1000) 5.5f else 7f
            val interligne = taille * 0.46f
            val largeur = lignes.maxOf { texte(taille, gras = true).measureText(it) } + 3f
            val hauteur = lignes.size * interligne + 1.6f
            val boite = RectF(cx - largeur / 2, cy - hauteur / 2, cx + largeur / 2, cy + hauteur / 2)
            toile.drawRoundRect(boite, 1f, 1f, plein(Color.WHITE))
            toile.drawRoundRect(boite, 1f, 1f, trait(ROUGE, 0.25f))
            lignes.forEachIndexed { i, ligne ->
                toile.drawText(ligne, cx, boite.top + 0.8f + interligne * (i + 0.85f), texte(taille, gras = i == 0, alignement = Paint.Align.CENTER))
            }
        }
        toile.restore()

        dessinerBarreEchelle(toile, echelle)
        dessinerNord(toile)
        if ((calques.photo || calques.cadastre) && messageFond == null) {
            val source = listOfNotNull(if (calques.photo) "© swisstopo" else null, if (calques.cadastre) "Cadastre © cantons" else null).joinToString(" · ")
            toile.drawText(source, PLAN.right - 1.5f, PLAN.bottom - 1.5f, texte(5.5f, alignement = Paint.Align.RIGHT))
        }
        return echelle
    }

    /** Photo aérienne et/ou cadastre swisstopo assemblés dans une image de la taille du cadre (150 dpi). */
    private fun dessinerFond(toile: Canvas, versTerrain: (Float, Float) -> Pair<Double, Double>) {
        val pixelsParMm = 150 / 25.4
        val largeurPx = (PLAN.width() * pixelsParMm).toInt()
        val hauteurPx = (PLAN.height() * pixelsParMm).toInt()
        val (estGauche, nordHaut) = versTerrain(PLAN.left, PLAN.top)
        val (estDroite, nordBas) = versTerrain(PLAN.right, PLAN.bottom)
        val metresParPixel = (estDroite - estGauche) / largeurPx
        val niveau = niveauDeZoom(metresParPixel)
        val tuiles = tuilesPourZone(estGauche, estDroite, nordBas, nordHaut, niveau)
        if (tuiles.size > 200) {
            messageFond = "fond non mis : plan trop étendu pour la photo aérienne"
            return
        }
        val image = Bitmap.createBitmap(largeurPx, hauteurPx, Bitmap.Config.ARGB_8888)
        val toileImage = Canvas(image)
        toileImage.drawColor(Color.WHITE)
        val pinceau = Paint(Paint.FILTER_BITMAP_FLAG)
        val fonds = listOfNotNull(if (calques.photo) Fond.PHOTO else null, if (calques.cadastre) Fond.CADASTRE else null)
        for (fond in fonds) for (tuile in tuiles) {
            val bitmap = telecharger(fond.adresse(tuile))
            if (bitmap == null) {
                messageFond = "fond swisstopo incomplet (pas de connexion ?)"
                continue
            }
            val gauche = ((tuile.est - estGauche) / metresParPixel).toFloat()
            val haut = ((nordHaut - tuile.nord) / metresParPixel).toFloat()
            val cote = (tuile.cote / metresParPixel).toFloat()
            toileImage.drawBitmap(bitmap, null, RectF(gauche, haut, gauche + cote + 0.5f, haut + cote + 0.5f), pinceau)
        }
        toile.drawBitmap(image, null, PLAN, pinceau)
        image.recycle()
    }

    private fun telecharger(adresse: String): Bitmap? = try {
        val connexion = URL(adresse).openConnection() as HttpURLConnection
        connexion.connectTimeout = 10_000
        connexion.readTimeout = 15_000
        connexion.useCaches = true
        connexion.setRequestProperty("User-Agent", "EmlidStock (appli Android personnelle)")
        try {
            if (connexion.responseCode == HttpURLConnection.HTTP_OK) connexion.inputStream.use { BitmapFactory.decodeStream(it) } else null
        } finally {
            connexion.disconnect()
        }
    } catch (erreur: Exception) {
        null
    }

    /** Barre d'échelle en bas à gauche du plan : 4 cases noires et blanches, et l'échelle écrite. */
    private fun dessinerBarreEchelle(toile: Canvas, echelle: Int) {
        val (metres, mm) = barreEchellePapier(echelle, 60.0)
        val x = PLAN.left + 6
        val y = PLAN.bottom - 9
        for (i in 0 until 4) {
            val case = RectF(x + (i * mm / 4).toFloat(), y, x + ((i + 1) * mm / 4).toFloat(), y + 1.6f)
            toile.drawRect(case, plein(if (i % 2 == 0) TEXTE else Color.WHITE))
            toile.drawRect(case, trait(TEXTE, 0.2f))
        }
        toile.drawText("0", x, y - 1, texte(6.5f, alignement = Paint.Align.CENTER))
        val texteMetres = if (metres >= 1) "${metres.toInt()} m" else "${trois(metres)} m"
        toile.drawText(texteMetres, x + mm.toFloat(), y - 1, texte(6.5f, alignement = Paint.Align.CENTER))
        toile.drawText("Échelle 1:$echelle", x + mm.toFloat() + 6, y + 1.6f, texte(8f, gras = true))
    }

    /** Flèche du Nord en haut à droite du plan. */
    private fun dessinerNord(toile: Canvas) {
        val x = PLAN.right - 10
        val y = PLAN.top + 8
        val fleche = Path().apply {
            moveTo(x, y - 4)
            lineTo(x - 2.2f, y + 3)
            lineTo(x + 2.2f, y + 3)
            close()
        }
        toile.drawPath(fleche, plein(TEXTE))
        toile.drawText("N", x, y + 7, texte(8f, gras = true, alignement = Paint.Align.CENTER))
    }

    // =====================================================================
    // Colonne de droite : titre, tableau des volumes, légendes, cartouche
    // =====================================================================

    private fun dessinerColonne(toile: Canvas, echelle: Int) {
        val x = COLONNE_X
        val fin = COLONNE_X + COLONNE_LARGEUR
        var y = 17f
        toile.drawText("Volumes", x, y, texte(13f, gras = true))
        toile.drawRect(x, y + 2, x + 24, y + 2.9f, plein(AMBRE))
        y += 9

        // Tableau : Nom | Surface m² | Remblai m³ | Déblai m³
        val colonnes = listOf(x + 32f, x + 55f, fin - 1)
        toile.drawText("Nom", x, y, texte(6.5f, GRIS, gras = true))
        listOf("Surface m²", "Remblai m³", "Déblai m³").forEachIndexed { i, titre ->
            toile.drawText(titre, colonnes[i], y, texte(6.5f, GRIS, gras = true, alignement = Paint.Align.RIGHT))
        }
        y += 1.5f
        toile.drawLine(x, y, fin, y, trait(TEXTE, 0.2f))
        y += 3.5f
        for (s in stocks) {
            val r = s.resultat
            val nom = if (s.nom.length > 20) s.nom.take(19) + "…" else s.nom
            toile.drawText(nom, x, y, texte(7f))
            if (r != null && r.erreur == null) {
                toile.drawText(trois(r.surfacePlan), colonnes[0], y, texte(7f, alignement = Paint.Align.RIGHT))
                toile.drawText(trois(r.remblai), colonnes[1], y, texte(7f, gras = true, alignement = Paint.Align.RIGHT))
                toile.drawText(trois(r.deblai), colonnes[2], y, texte(7f, alignement = Paint.Align.RIGHT))
            } else {
                toile.drawText("pas de volume", fin - 1, y, texte(6.5f, GRIS, alignement = Paint.Align.RIGHT))
            }
            y += 4.4f
        }
        val calcules = stocks.filter { it.volumeValable }
        if (calcules.size > 1) {
            toile.drawLine(x, y - 3, fin, y - 3, trait(TEXTE, 0.2f))
            toile.drawText("Total", x, y + 0.5f, texte(7f, gras = true))
            toile.drawText(trois(calcules.sumOf { it.resultat!!.surfacePlan }), colonnes[0], y + 0.5f, texte(7f, gras = true, alignement = Paint.Align.RIGHT))
            toile.drawText(trois(calcules.sumOf { it.resultat!!.remblai }), colonnes[1], y + 0.5f, texte(7f, gras = true, alignement = Paint.Align.RIGHT))
            toile.drawText(trois(calcules.sumOf { it.resultat!!.deblai }), colonnes[2], y + 0.5f, texte(7f, gras = true, alignement = Paint.Align.RIGHT))
            y += 5f
        }

        // Remarques
        y += 2
        val remarques = mutableListOf("Base : points du bas de talus (B). Contour = B dans l'ordre, lignes de rupture = hauts de talus (H).")
        calcules.filter { it.resultat!!.surfaceNonCouverte > 0 }.forEach {
            remarques.add("Attention ${it.nom} : ${trois(it.resultat!!.surfaceNonCouverte)} m² non couverts par la base.")
        }
        for (remarque in remarques) y = texteSurPlusieursLignes(toile, remarque, x, y, COLONNE_LARGEUR, texte(6.5f, GRIS), 2.8f)

        // Légende
        y += 3
        val legende = listOfNotNull(
            ROUGE to "Contour du stock (bas de talus)",
            ORANGE to "Ligne de rupture (haut de talus)",
            if (calques.triangles) GRIS_TRIANGLE to "Triangulation du dessus" else null,
            if (calques.courbes) BRUN to "Courbes de niveau" else null,
        )
        for ((c, libelle) in legende) {
            toile.drawLine(x, y - 1, x + 6, y - 1, trait(c, 0.5f))
            toile.drawText(libelle, x + 8, y, texte(6.5f))
            y += 3.4f
        }
        val ecarts = calcules.flatMap { it.resultat!!.trianglesEcart }
        if (calques.remblaiDeblai && ecarts.isNotEmpty()) {
            val r = zonesRemblaiDeblai(ecarts)
            for ((titre, classes) in listOf("Remblai" to r.remblai, "Déblai" to r.deblai)) {
                if (classes.isEmpty()) continue
                y += 2
                toile.drawText("$titre (épaisseur en m, palier ${trois(r.palier).trimEnd('0').trimEnd('.')} m)", x, y, texte(6.5f, gras = true))
                y += 1.5f
                val pas = (COLONNE_LARGEUR - 8) / classes.size
                classes.forEachIndexed { i, classe -> toile.drawRect(x + i * pas, y, x + (i + 1) * pas, y + 3, plein(couleur(classe.couleur))) }
                val limites = listOf(0.0) + classes.map { it.a }
                val saut = if (pas < 5) 2 else 1
                limites.forEachIndexed { i, valeur ->
                    if (i % saut != 0 && i != limites.lastIndex) return@forEachIndexed
                    val alignement = when (i) { 0 -> Paint.Align.LEFT; limites.lastIndex -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                    toile.drawText(nombreCourt(valeur), x + i * pas, y + 5.5f, texte(5.5f, alignement = alignement))
                }
                y += 9
            }
        } else if (calques.couleurs) {
            val triangles = calcules.flatMap { it.resultat!!.trianglesDessus }
            if (triangles.isNotEmpty()) {
                val plage = plageAltitudes(triangles)
                val largeur = 50f
                for (i in 0 until 20) {
                    val altitude = plage.min + (i + 0.5) / 20 * (plage.max - plage.min)
                    toile.drawRect(x + i * largeur / 20, y - 2, x + (i + 1) * largeur / 20 + 0.1f, y + 0.5f, plein(couleur(couleurAltitude(altitude, plage))))
                }
                toile.drawText(trois(plage.min), x, y + 3, texte(6.5f))
                toile.drawText("${trois(plage.max)} m", x + largeur, y + 3, texte(6.5f, alignement = Paint.Align.RIGHT))
            }
        }

        dessinerCartouche(toile, echelle)
    }

    /** Écrit un texte sur plusieurs lignes dans une largeur donnée ; renvoie la position suivante. */
    private fun texteSurPlusieursLignes(toile: Canvas, texteComplet: String, x: Float, yDepart: Float, largeur: Float, pinceau: Paint, interligne: Float): Float {
        var y = yDepart
        var ligne = ""
        for (mot in texteComplet.split(' ')) {
            val essai = if (ligne.isEmpty()) mot else "$ligne $mot"
            if (pinceau.measureText(essai) > largeur && ligne.isNotEmpty()) {
                toile.drawText(ligne, x, y, pinceau)
                y += interligne
                ligne = mot
            } else {
                ligne = essai
            }
        }
        if (ligne.isNotEmpty()) {
            toile.drawText(ligne, x, y, pinceau)
            y += interligne
        }
        return y
    }

    /** Cartouche en bas à droite : affaire, date, dessiné par, échelle, coordonnées, chantier. */
    private fun dessinerCartouche(toile: Canvas, echelle: Int) {
        val lignes = listOf(
            "Affaire" to cartouche.affaire.ifEmpty { "-" },
            "Date" to cartouche.date.ifEmpty { "-" },
            "Dessiné par" to cartouche.dessinePar.ifEmpty { "-" },
            "Échelle" to "1:$echelle",
            "Coordonnées" to "MN95 (m), altitudes RAN95",
            "Chantier" to cartouche.chantier.ifEmpty { "-" },
        )
        val hauteurLigne = 6f
        val haut = PAGE_HAUTEUR - MARGE - 2 - lignes.size * hauteurLigne
        val x = COLONNE_X
        toile.drawRect(x, haut, x + COLONNE_LARGEUR, haut + lignes.size * hauteurLigne, trait(TEXTE, 0.3f))
        lignes.forEachIndexed { i, (titre, valeur) ->
            val yl = haut + i * hauteurLigne
            if (i > 0) toile.drawLine(x, yl, x + COLONNE_LARGEUR, yl, trait(TEXTE, 0.3f))
            toile.drawText(titre, x + 1.5f, yl + 2.4f, texte(6f, GRIS))
            val pinceau = texte(8f, gras = true)
            var valeurCourte = valeur
            while (pinceau.measureText(valeurCourte) > COLONNE_LARGEUR - 3 && valeurCourte.length > 3) valeurCourte = valeurCourte.dropLast(2) + "…"
            toile.drawText(valeurCourte, x + 1.5f, yl + 5.2f, pinceau)
        }
    }

    private fun nombreCourt(valeur: Double): String = trois(valeur).trimEnd('0').trimEnd('.')

    companion object {
        // A4 paysage : 297 × 210 mm = 842 × 595 points PDF.
        private const val PAGE_POINTS_LARGEUR = 842
        private const val PAGE_POINTS_HAUTEUR = 595
        private const val POINTS_PAR_MM = 72f / 25.4f
        private const val PAGE_LARGEUR = 297f
        private const val PAGE_HAUTEUR = 210f
        private const val MARGE = 8f
        private val PLAN = RectF(10f, 10f, 203f, 200f)
        private const val COLONNE_X = 207f
        private const val COLONNE_LARGEUR = 80f

        private val TEXTE = Color.rgb(27, 43, 54)
        private val GRIS = Color.rgb(95, 109, 119)
        private val AMBRE = Color.rgb(246, 171, 7)
        private val ROUGE = Color.rgb(220, 38, 38)
        private val ORANGE = Color.rgb(234, 88, 12)
        private val GRIS_TRIANGLE = Color.rgb(170, 178, 184)
        private val BRUN = Color.rgb(107, 79, 42)
    }
}
