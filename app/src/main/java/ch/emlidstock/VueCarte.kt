package ch.emlidstock

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import ch.emlidstock.noyau.carte.Cadrage
import ch.emlidstock.noyau.carte.Fond
import ch.emlidstock.noyau.carte.barreEchelle
import ch.emlidstock.noyau.carte.niveauDeZoom
import ch.emlidstock.noyau.carte.tuilesPourZone
import ch.emlidstock.noyau.controle.Bande
import ch.emlidstock.noyau.controle.Couleur
import ch.emlidstock.noyau.controle.Plage
import ch.emlidstock.noyau.controle.RemblaiDeblai
import ch.emlidstock.noyau.controle.SegmentCourbe
import ch.emlidstock.noyau.controle.bandesDeNiveau
import ch.emlidstock.noyau.controle.couleurAltitude
import ch.emlidstock.noyau.controle.courbesDeNiveau
import ch.emlidstock.noyau.controle.equidistanceAuto
import ch.emlidstock.noyau.controle.plageAltitudes
import ch.emlidstock.noyau.controle.zonesRemblaiDeblai
import ch.emlidstock.noyau.coordonnees.PositionSuisse
import ch.emlidstock.noyau.leve.ModeLeve
import ch.emlidstock.noyau.leve.PointLeve
import ch.emlidstock.noyau.volume.PointPlan
import ch.emlidstock.noyau.volume.Triangle
import ch.emlidstock.noyau.volume.pointDansPolygone
import kotlin.math.hypot

/** Calques de la carte qu'on peut afficher ou masquer (comme dans l'appli topo V1). */
data class Calques(val actifs: Set<String> = PAR_DEFAUT) {
    val photo get() = "photo" in actifs
    val cadastre get() = "cadastre" in actifs
    val noms get() = "noms" in actifs
    val altitudes get() = "altitudes" in actifs
    val triangles get() = "triangles" in actifs
    val couleursAltitude get() = "couleursAltitude" in actifs
    val courbes get() = "courbes" in actifs
    val remblaiDeblai get() = "remblaiDeblai" in actifs
    val base get() = "base" in actifs

    /** Un calque : clé (pour l'enregistrer), titre affiché, état au premier lancement. */
    class Calque(val cle: String, val titre: String, val parDefaut: Boolean)

    companion object {
        val LISTE = listOf(
            Calque("photo", "Photo aérienne (swisstopo)", true),
            Calque("cadastre", "Cadastre", false),
            Calque("noms", "Noms des points", true),
            Calque("altitudes", "Altitudes des points", false),
            Calque("triangles", "Triangles (après calcul du volume)", true),
            Calque("couleursAltitude", "Couleurs selon l'altitude", false),
            Calque("courbes", "Courbes de niveau", false),
            Calque("remblaiDeblai", "Remblai (rouge) / déblai (jaune)", true),
            Calque("base", "Surface de base (mode B)", true),
        )
        val PAR_DEFAUT = LISTE.filter { it.parDefaut }.map { it.cle }.toSet()
    }
}

/**
 * Carte 2D du levé : fond swisstopo (photo aérienne, cadastre), points levés avec leur nom,
 * contour (bas de talus B1 → B2 → … fermé), lignes de haut de talus, position actuelle.
 * Zoom à deux doigts (ou double toucher), déplacement au doigt, toucher un point pour agir dessus.
 */
@SuppressLint("ViewConstructor")
class VueCarte(private val ecran: EcranPrincipal) : View(ecran) {

    /** Points du stock (mis à jour par l'écran de levé). */
    var points: List<PointLeve> = emptyList()
        set(valeur) {
            field = valeur
            invalidate()
        }

    /** Position actuelle ; [positionFiable] = faux si ce n'est pas le bout de canne (dessin gris). */
    var position: PositionSuisse? = null
    var positionFiable = false

    /** Lignes de haut de talus fermées (dernier point relié au premier). */
    var lignesFermees: Set<Int> = emptySet()

    /** Triangles du dessus du dernier calcul de volume (vide = pas de calcul à jour). */
    var triangles: List<Triangle> = emptyList()
        set(valeur) {
            field = valeur
            preparerVuesDeControle()
            invalidate()
        }

    /** Surface levée : triangles retirés au doigt (dessinés en tirets rouges, on peut les remettre). */
    var trianglesRetires: List<Triangle> = emptyList()
        set(valeur) {
            field = valeur
            invalidate()
        }

    /** Triangles de la surface de base en mode B (traits fins bleu clair, calque « Surface de base »). */
    var trianglesBase: List<Triangle> = emptyList()
        set(valeur) {
            field = valeur
            invalidate()
        }

    /** Si non nul : toucher la carte désigne un triangle (pour le retirer ou le remettre), et non un point. */
    var quandToucherTriangle: ((Triangle) -> Unit)? = null

    /** Triangles d'écart dessus − base du dernier calcul (couleurs remblai / déblai). */
    var trianglesEcart: List<Triangle> = emptyList()
        set(valeur) {
            field = valeur
            preparerVuesDeControle()
            invalidate()
        }

    // Vues de contrôle préparées à partir des triangles (calculées une fois, pas à chaque dessin).
    private var plage: Plage? = null
    private var equidistance = 1.0
    private var bandes: List<Bande> = emptyList()
    private var courbes: List<SegmentCourbe> = emptyList()
    private var remblaiDeblai: RemblaiDeblai? = null

    private fun preparerVuesDeControle() {
        if (triangles.isEmpty()) {
            plage = null
            bandes = emptyList()
            courbes = emptyList()
        } else {
            val p = plageAltitudes(triangles)
            plage = p
            equidistance = equidistanceAuto(p.min, p.max)
            bandes = bandesDeNiveau(triangles, equidistance)
            courbes = courbesDeNiveau(triangles, equidistance)
        }
        remblaiDeblai = if (trianglesEcart.isEmpty()) null else zonesRemblaiDeblai(trianglesEcart)
    }

    var calques = Calques()
        set(valeur) {
            field = valeur
            invalidate()
        }

    /** Appelé quand on touche un point de la carte. */
    var quandToucherPoint: (PointLeve) -> Unit = {}

    private var cadrage: Cadrage? = null
    private val chargeur = ChargeurTuiles { postInvalidate() }
    private val densite = resources.displayMetrics.density
    private fun dp(valeur: Float) = valeur * densite

    // --- Pinceaux ---
    private val pinceauImage = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pinceauFond = Paint().apply { color = Color.rgb(226, 231, 234) }
    private val pinceauContour = trait(COULEUR_B, 3f)
    private val pinceauLigneHaut = trait(COULEUR_H, 2.5f)
    private val pinceauPoint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pinceauBord = trait(Color.WHITE, 1.5f)
    private val pinceauTexte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(12f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val pinceauTexteBord = Paint(pinceauTexte).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val pinceauPetitTexte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = dp(10f)
    }
    private val pinceauCadre = Paint().apply { color = Color.argb(190, 255, 255, 255) }
    private val pinceauEchelle = trait(Color.BLACK, 2f)

    private fun trait(couleur: Int, epaisseurDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = couleur
        style = Paint.Style.STROKE
        strokeWidth = dp(epaisseurDp)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // =====================================================================
    // Cadrage
    // =====================================================================

    /** Cadre la carte sur tous les points et triangles (et la position actuelle). */
    fun toutVoir() {
        val coordonnees = points.map { Pair(it.est, it.nord) } +
            (triangles + trianglesRetires).flatMap { t -> t.sommets.map { Pair(it.est, it.nord) } } +
            listOfNotNull(position?.let { Pair(it.est, it.nord) })
        Cadrage.surPoints(coordonnees, largeur(), hauteur(), dp(40f).toInt())?.let { cadrage = it }
        invalidate()
    }

    /** Centre la carte sur la position actuelle (sans changer le zoom). */
    fun centrerSurPosition() {
        val p = position ?: run {
            ecran.message("Pas encore de position du récepteur.")
            return
        }
        cadrage = (cadrage ?: Cadrage(p.est, p.nord, largeur() / 40.0)).copy(centreEst = p.est, centreNord = p.nord)
        invalidate()
    }

    fun zoomerAuCentre(facteur: Double) {
        cadrage = cadrage?.zoomer(facteur, largeur() / 2.0, hauteur() / 2.0, largeur(), hauteur())
        invalidate()
    }

    private fun largeur() = if (width > 0) width else 1
    private fun hauteur() = if (height > 0) height else 1

    // =====================================================================
    // Dessin
    // =====================================================================

    override fun onDraw(toile: Canvas) {
        toile.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pinceauFond)
        if (cadrage == null && width > 0 && (points.isNotEmpty() || triangles.isNotEmpty() || position != null)) toutVoir()
        val c = cadrage
        if (c == null) {
            toile.drawText("En attente de la position…", dp(12f), dp(24f), pinceauPetitTexte)
            return
        }
        if (calques.photo) dessinerFond(toile, c, Fond.PHOTO)
        if (calques.cadastre) dessinerFond(toile, c, Fond.CADASTRE)
        if (calques.remblaiDeblai) dessinerRemblaiDeblai(toile, c)
        if (calques.couleursAltitude) dessinerCouleursAltitude(toile, c)
        if (calques.base) dessinerAretes(toile, c, trianglesBase, pinceauBase)
        if (calques.triangles) dessinerTriangles(toile, c)
        dessinerAretes(toile, c, trianglesRetires, pinceauRetire)
        if (calques.courbes) dessinerCourbes(toile, c)
        dessinerLignes(toile, c)
        dessinerPoints(toile, c)
        dessinerPosition(toile, c)
        dessinerEchelleEtMentions(toile, c)
        dessinerLegende(toile)
    }

    // --- Vues de contrôle : couleurs, courbes, remblai / déblai (reprises de la V1) ---

    private val pinceauZone = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pinceauCourbe = trait(Color.rgb(90, 50, 20), 1f)
    private val pinceauCourbeMaitresse = trait(Color.rgb(90, 50, 20), 2f)

    private fun couleurAndroid(couleur: Couleur, opacite: Int = 255) = Color.argb(opacite, couleur.rouge, couleur.vert, couleur.bleu)

    private fun remplirPolygone(toile: Canvas, c: Cadrage, polygone: List<PointPlan>, couleur: Int) {
        if (polygone.size < 3) return
        val chemin = Path()
        chemin.moveTo(ecranX(c, polygone[0].est), ecranY(c, polygone[0].nord))
        for (i in 1 until polygone.size) chemin.lineTo(ecranX(c, polygone[i].est), ecranY(c, polygone[i].nord))
        chemin.close()
        pinceauZone.color = couleur
        toile.drawPath(chemin, pinceauZone)
    }

    private fun dessinerCouleursAltitude(toile: Canvas, c: Cadrage) {
        val p = plage ?: return
        for (bande in bandes) {
            // Couleur du milieu de la bande.
            val couleur = couleurAltitude(bande.altitude + equidistance / 2, p)
            remplirPolygone(toile, c, bande.polygone, couleurAndroid(couleur, 210))
        }
    }

    private fun dessinerRemblaiDeblai(toile: Canvas, c: Cadrage) {
        val r = remblaiDeblai ?: return
        if (calques.couleursAltitude) return // les deux coloriages ne se superposent pas
        for (zone in r.zones) remplirPolygone(toile, c, zone.polygone, couleurAndroid(zone.couleur, 200))
    }

    /** Courbes de niveau ; une courbe sur 5 (« maîtresse ») plus épaisse. */
    private fun dessinerCourbes(toile: Canvas, c: Cadrage) {
        for (segment in courbes) {
            val rang = Math.round(segment.altitude / equidistance)
            val pinceau = if (rang % 5 == 0L) pinceauCourbeMaitresse else pinceauCourbe
            toile.drawLine(
                ecranX(c, segment.a.est), ecranY(c, segment.a.nord),
                ecranX(c, segment.b.est), ecranY(c, segment.b.nord), pinceau,
            )
        }
    }

    /** Légende en bas à gauche, au-dessus de la barre d'échelle. */
    private fun dessinerLegende(toile: Canvas) {
        val lignes = mutableListOf<Pair<Int?, String>>()
        val p = plage
        if (calques.couleursAltitude && p != null) {
            lignes.add(Pair(couleurAndroid(couleurAltitude(p.max, p)), "haut ${nombre(p.max, 2)} m"))
            lignes.add(Pair(couleurAndroid(couleurAltitude(p.min, p)), "bas ${nombre(p.min, 2)} m"))
        } else if (calques.remblaiDeblai) {
            remblaiDeblai?.let { r ->
                r.remblai.asReversed().forEach { lignes.add(Pair(couleurAndroid(it.couleur), "remblai ${nombre(it.de, 2)} à ${nombre(it.a, 2)} m")) }
                r.deblai.forEach { lignes.add(Pair(couleurAndroid(it.couleur), "déblai ${nombre(it.de, 2)} à ${nombre(it.a, 2)} m")) }
            }
        }
        if (calques.courbes && courbes.isNotEmpty()) lignes.add(Pair(null, "courbes tous les ${nombre(equidistance, 2)} m"))
        if (lignes.isEmpty()) return
        val hauteurLigne = dp(14f)
        val largeur = lignes.maxOf { pinceauPetitTexte.measureText(it.second) } + dp(26f)
        val bas = height - dp(36f)
        val haut = bas - lignes.size * hauteurLigne - dp(6f)
        toile.drawRect(dp(6f), haut, dp(6f) + largeur, bas, pinceauCadre)
        lignes.forEachIndexed { i, (couleur, texte) ->
            val y = haut + dp(4f) + (i + 1) * hauteurLigne - dp(3f)
            if (couleur != null) {
                pinceauZone.color = couleur
                toile.drawRect(dp(10f), y - dp(10f), dp(20f), y, pinceauZone)
            }
            toile.drawText(texte, dp(24f), y - dp(1f), pinceauPetitTexte)
        }
    }

    private fun ecranX(c: Cadrage, est: Double) = (width / 2.0 + (est - c.centreEst) * c.echelle).toFloat()
    private fun ecranY(c: Cadrage, nord: Double) = (height / 2.0 - (nord - c.centreNord) * c.echelle).toFloat()

    private fun dessinerFond(toile: Canvas, c: Cadrage, fond: Fond) {
        val (estMin, nordMax) = c.versTerrain(0.0, 0.0, width, height)
        val (estMax, nordMin) = c.versTerrain(width.toDouble(), height.toDouble(), width, height)
        val niveau = niveauDeZoom(1.0 / c.echelle, densite.toDouble())
        val tuiles = tuilesPourZone(estMin, estMax, nordMin, nordMax, niveau)
        if (tuiles.size > 200) return // sécurité : jamais des centaines de téléchargements d'un coup
        for (tuile in tuiles) {
            val image = chargeur.image(fond, tuile) ?: continue
            val gauche = ecranX(c, tuile.est)
            val haut = ecranY(c, tuile.nord)
            val cote = (tuile.cote * c.echelle).toFloat()
            toile.drawBitmap(image, null, RectF(gauche, haut, gauche + cote, haut + cote), pinceauImage)
        }
    }

    /** Contour (bas de talus, fermé) et lignes de haut de talus, dans l'ordre des numéros. */
    private fun dessinerLignes(toile: Canvas, c: Cadrage) {
        val bas = points.filter { it.mode == ModeLeve.BAS_DE_TALUS }.sortedBy { it.numero }
        dessinerPolyligne(toile, c, bas, fermee = bas.size >= 3, pinceauContour)
        points.filter { it.mode == ModeLeve.HAUT_DE_TALUS }
            .groupBy { it.ligne ?: 1 }
            .forEach { (numero, ligne) ->
                dessinerPolyligne(toile, c, ligne.sortedBy { it.numero }, fermee = numero in lignesFermees && ligne.size >= 3, pinceauLigneHaut)
            }
    }

    private val pinceauTriangle = trait(Color.argb(200, 255, 255, 255), 1f)
    private val pinceauBase = trait(Color.argb(200, 129, 212, 250), 1f)
    private val pinceauRetire = trait(Color.rgb(229, 57, 53), 1.5f).apply {
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }

    /** Triangles du calcul de volume (ou de la surface), en traits fins blancs. */
    private fun dessinerTriangles(toile: Canvas, c: Cadrage) = dessinerAretes(toile, c, triangles, pinceauTriangle)

    /** Côtés de triangles avec le pinceau donné. */
    private fun dessinerAretes(toile: Canvas, c: Cadrage, liste: List<Triangle>, pinceau: Paint) {
        for (t in liste) {
            val xa = ecranX(c, t.a.est); val ya = ecranY(c, t.a.nord)
            val xb = ecranX(c, t.b.est); val yb = ecranY(c, t.b.nord)
            val xc = ecranX(c, t.c.est); val yc = ecranY(c, t.c.nord)
            toile.drawLine(xa, ya, xb, yb, pinceau)
            toile.drawLine(xb, yb, xc, yc, pinceau)
            toile.drawLine(xc, yc, xa, ya, pinceau)
        }
    }

    private fun dessinerPolyligne(toile: Canvas, c: Cadrage, liste: List<PointLeve>, fermee: Boolean, pinceau: Paint) {
        if (liste.size < 2) return
        val suite = if (fermee) liste + liste.first() else liste
        for (i in 0 until suite.size - 1) {
            toile.drawLine(
                ecranX(c, suite[i].est), ecranY(c, suite[i].nord),
                ecranX(c, suite[i + 1].est), ecranY(c, suite[i + 1].nord), pinceau,
            )
        }
    }

    private fun dessinerPoints(toile: Canvas, c: Cadrage) {
        for (point in points) {
            val x = ecranX(c, point.est)
            val y = ecranY(c, point.nord)
            if (x < -50 || y < -50 || x > width + 50 || y > height + 50) continue
            pinceauPoint.color = couleurDuMode(point.mode)
            toile.drawCircle(x, y, dp(5f), pinceauPoint)
            toile.drawCircle(x, y, dp(5f), pinceauBord)
            val lignes = mutableListOf<String>()
            if (calques.noms) lignes.add(point.nom)
            if (calques.altitudes) lignes.add(nombre(point.altitude, 2))
            lignes.forEachIndexed { i, texte ->
                val tx = x + dp(7f)
                val ty = y - dp(6f) + i * dp(14f)
                toile.drawText(texte, tx, ty, pinceauTexteBord)
                toile.drawText(texte, tx, ty, pinceauTexte)
            }
        }
    }

    private fun dessinerPosition(toile: Canvas, c: Cadrage) {
        val p = position ?: return
        val x = ecranX(c, p.est)
        val y = ecranY(c, p.nord)
        pinceauPoint.color = if (positionFiable) COULEUR_POSITION else Color.GRAY
        toile.drawCircle(x, y, dp(9f), pinceauBordEpais)
        toile.drawCircle(x, y, dp(7f), pinceauPoint)
    }

    private val pinceauBordEpais = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private fun dessinerEchelleEtMentions(toile: Canvas, c: Cadrage) {
        // Barre d'échelle en bas à gauche.
        val (metres, pixels) = barreEchelle(c.echelle, dp(110f).toDouble())
        val gauche = dp(10f)
        val bas = height - dp(10f)
        val texteEchelle = if (metres >= 1) "${metres.toInt()} m" else "${nombre(metres * 100, 0)} cm"
        toile.drawRect(gauche - dp(4f), bas - dp(22f), gauche + pixels.toFloat() + dp(4f), bas + dp(4f), pinceauCadre)
        toile.drawLine(gauche, bas, gauche + pixels.toFloat(), bas, pinceauEchelle)
        toile.drawLine(gauche, bas, gauche, bas - dp(6f), pinceauEchelle)
        toile.drawLine(gauche + pixels.toFloat(), bas, gauche + pixels.toFloat(), bas - dp(6f), pinceauEchelle)
        toile.drawText(texteEchelle, gauche + dp(2f), bas - dp(9f), pinceauPetitTexte)

        // Mentions obligatoires en bas à droite, et message si le fond ne se charge pas.
        val mentions = listOfNotNull(
            if (calques.photo) Fond.PHOTO.attribution else null,
            if (calques.cadastre) Fond.CADASTRE.attribution else null,
        ).joinToString(" · ")
        if (mentions.isNotEmpty()) {
            val largeurTexte = pinceauPetitTexte.measureText(mentions)
            toile.drawRect(width - largeurTexte - dp(8f), height - dp(16f), width.toFloat(), height.toFloat(), pinceauCadre)
            toile.drawText(mentions, width - largeurTexte - dp(4f), height - dp(4f), pinceauPetitTexte)
        }
        chargeur.dernierEchec?.let { echec ->
            if (calques.photo || calques.cadastre) {
                val largeurTexte = pinceauPetitTexte.measureText(echec)
                toile.drawRect(0f, 0f, largeurTexte + dp(8f), dp(16f), pinceauCadre)
                toile.drawText(echec, dp(4f), dp(12f), pinceauPetitTexte)
            }
        }
    }

    // =====================================================================
    // Gestes : déplacement, zoom, toucher un point
    // =====================================================================

    private val gestes = GestureDetector(ecran, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            cadrage = cadrage?.deplacer(-distanceX.toDouble(), -distanceY.toDouble())
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            cadrage = cadrage?.zoomer(2.0, e.x.toDouble(), e.y.toDouble(), width, height)
            invalidate()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val actionTriangle = quandToucherTriangle
            if (actionTriangle != null) {
                triangleSousLeDoigt(e.x, e.y)?.let { actionTriangle(it) }
            } else {
                pointLePlusProche(e.x, e.y)?.let { quandToucherPoint(it) }
            }
            return true
        }
    })

    private val zoom = ScaleGestureDetector(ecran, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detecteur: ScaleGestureDetector): Boolean {
            cadrage = cadrage?.zoomer(
                detecteur.scaleFactor.toDouble(), detecteur.focusX.toDouble(), detecteur.focusY.toDouble(), width, height,
            )
            invalidate()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(evenement: MotionEvent): Boolean {
        // La carte garde le doigt : la page ne défile pas pendant qu'on déplace la carte.
        parent?.requestDisallowInterceptTouchEvent(true)
        zoom.onTouchEvent(evenement)
        if (!zoom.isInProgress) gestes.onTouchEvent(evenement)
        return true
    }

    /** Point le plus proche du doigt, à moins de 24 dp ; null sinon. */
    private fun pointLePlusProche(x: Float, y: Float): PointLeve? {
        val c = cadrage ?: return null
        return points
            .map { Pair(it, hypot((ecranX(c, it.est) - x).toDouble(), (ecranY(c, it.nord) - y).toDouble())) }
            .filter { it.second <= dp(24f) }
            .minByOrNull { it.second }
            ?.first
    }

    /** Triangle (gardé ou retiré) qui contient le point touché ; null sinon. */
    private fun triangleSousLeDoigt(x: Float, y: Float): Triangle? {
        val c = cadrage ?: return null
        val (est, nord) = c.versTerrain(x.toDouble(), y.toDouble(), width, height)
        val touche = PointPlan(est, nord)
        return (triangles + trianglesRetires).firstOrNull { t -> pointDansPolygone(touche, t.sommets.map { PointPlan(it.est, it.nord) }) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        chargeur.arreter()
    }

    companion object {
        val COULEUR_B = Color.rgb(246, 171, 7) // ambre (contour)
        val COULEUR_H = Color.rgb(229, 57, 53) // rouge
        val COULEUR_C = Color.rgb(41, 182, 246) // bleu clair
        val COULEUR_POSITION = Color.rgb(30, 136, 229)

        fun couleurDuMode(mode: ModeLeve): Int = when (mode) {
            ModeLeve.BAS_DE_TALUS -> COULEUR_B
            ModeLeve.HAUT_DE_TALUS -> COULEUR_H
            ModeLeve.POINT_CARACTERISTIQUE -> COULEUR_C
        }
    }
}

/**
 * La carte avec ses boutons posés dessus : « Calques » en haut à gauche ;
 * zoom +, zoom −, « Tout voir » et « Ma position » en haut à droite.
 */
class CarteAvecBoutons(private val ecran: EcranPrincipal) {

    val carte = VueCarte(ecran).apply { calques = lireCalques() }

    val racine: FrameLayout = FrameLayout(ecran).apply {
        addView(carte, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(
            petitBouton("Calques") { choisirCalques() },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START),
        )
        val colonne = LinearLayout(ecran).apply {
            orientation = LinearLayout.VERTICAL
            addView(petitBouton("+") { carte.zoomerAuCentre(2.0) })
            addView(petitBouton("−") { carte.zoomerAuCentre(0.5) })
            addView(petitBouton("Tout voir") { carte.toutVoir() })
            addView(petitBouton("Ma position") { carte.centrerSurPosition() })
        }
        addView(
            colonne,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END),
        )
    }

    private fun petitBouton(texte: String, action: () -> Unit) = Button(ecran).apply {
        this.text = texte
        isAllCaps = false
        textSize = 13f
        minWidth = 0
        minHeight = 0
        minimumWidth = ecran.ui.dp(44)
        minimumHeight = ecran.ui.dp(40)
        setPadding(ecran.ui.dp(10), ecran.ui.dp(4), ecran.ui.dp(10), ecran.ui.dp(4))
        setBackgroundColor(Color.argb(220, 255, 255, 255))
        setTextColor(ecran.getColor(R.color.bleu_nuit))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(ecran.ui.dp(4), ecran.ui.dp(4), ecran.ui.dp(4), 0) }
    }

    private fun choisirCalques() {
        val liste = Calques.LISTE
        val coches = BooleanArray(liste.size) { liste[it].cle in carte.calques.actifs }
        AlertDialog.Builder(ecran)
            .setTitle("Calques")
            .setMultiChoiceItems(liste.map { it.titre }.toTypedArray(), coches) { _, i, coche -> coches[i] = coche }
            .setPositiveButton("OK") { _, _ ->
                val nouveaux = Calques(liste.filterIndexed { i, _ -> coches[i] }.map { it.cle }.toSet())
                carte.calques = nouveaux
                enregistrerCalques(nouveaux)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun lireCalques(): Calques {
        val p = ecran.preferences()
        return Calques(Calques.LISTE.filter { p.getBoolean("calque_${it.cle}", it.parDefaut) }.map { it.cle }.toSet())
    }

    private fun enregistrerCalques(c: Calques) {
        val edition = ecran.preferences().edit()
        Calques.LISTE.forEach { edition.putBoolean("calque_${it.cle}", it.cle in c.actifs) }
        edition.apply()
    }
}
