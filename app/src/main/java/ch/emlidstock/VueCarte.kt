package ch.emlidstock

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import ch.emlidstock.noyau.coordonnees.PositionSuisse
import ch.emlidstock.noyau.leve.ModeLeve
import ch.emlidstock.noyau.leve.PointLeve
import kotlin.math.hypot

/** Calques de la carte qu'on peut afficher ou masquer (comme dans l'appli topo V1). */
data class Calques(
    val photo: Boolean = true,
    val cadastre: Boolean = false,
    val noms: Boolean = true,
    val altitudes: Boolean = false,
)

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

    /** Cadre la carte sur tous les points du stock (et la position actuelle). */
    fun toutVoir() {
        val coordonnees = points.map { Pair(it.est, it.nord) } +
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
        if (cadrage == null && width > 0 && (points.isNotEmpty() || position != null)) toutVoir()
        val c = cadrage
        if (c == null) {
            toile.drawText("En attente de la position…", dp(12f), dp(24f), pinceauPetitTexte)
            return
        }
        if (calques.photo) dessinerFond(toile, c, Fond.PHOTO)
        if (calques.cadastre) dessinerFond(toile, c, Fond.CADASTRE)
        dessinerLignes(toile, c)
        dessinerPoints(toile, c)
        dessinerPosition(toile, c)
        dessinerEchelleEtMentions(toile, c)
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
            .groupBy { it.ligne }
            .values
            .forEach { ligne -> dessinerPolyligne(toile, c, ligne.sortedBy { it.numero }, fermee = false, pinceauLigneHaut) }
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
            pointLePlusProche(e.x, e.y)?.let { quandToucherPoint(it) }
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
        val titres = arrayOf("Photo aérienne (swisstopo)", "Cadastre", "Noms des points", "Altitudes des points")
        val c = carte.calques
        val coches = booleanArrayOf(c.photo, c.cadastre, c.noms, c.altitudes)
        AlertDialog.Builder(ecran)
            .setTitle("Calques")
            .setMultiChoiceItems(titres, coches) { _, i, coche -> coches[i] = coche }
            .setPositiveButton("OK") { _, _ ->
                val nouveaux = Calques(coches[0], coches[1], coches[2], coches[3])
                carte.calques = nouveaux
                enregistrerCalques(nouveaux)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun lireCalques(): Calques {
        val p = ecran.preferences()
        return Calques(
            photo = p.getBoolean("calque_photo", true),
            cadastre = p.getBoolean("calque_cadastre", false),
            noms = p.getBoolean("calque_noms", true),
            altitudes = p.getBoolean("calque_altitudes", false),
        )
    }

    private fun enregistrerCalques(c: Calques) {
        ecran.preferences().edit()
            .putBoolean("calque_photo", c.photo)
            .putBoolean("calque_cadastre", c.cadastre)
            .putBoolean("calque_noms", c.noms)
            .putBoolean("calque_altitudes", c.altitudes)
            .apply()
    }
}
