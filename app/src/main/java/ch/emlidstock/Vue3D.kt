package ch.emlidstock

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import ch.emlidstock.noyau.controle.Projection3D
import ch.emlidstock.noyau.controle.couleurAltitude
import ch.emlidstock.noyau.controle.plageAltitudes
import ch.emlidstock.noyau.volume.Triangle
import kotlin.math.max

/**
 * Vue 3D du stock : triangles du dessus colorés selon l'altitude (bleu en bas, rouge en haut),
 * éclairés pour bien voir le relief. Glisser un doigt : tourner (gauche / droite) et incliner
 * (haut / bas) ; pincer : zoomer. Aucune bibliothèque : chaque triangle est projeté puis peint,
 * du plus loin au plus proche.
 */
@SuppressLint("ViewConstructor")
class Vue3D(private val ecran: EcranPrincipal) : View(ecran) {

    /** Triangles à afficher (le dessus du stock). */
    var triangles: List<Triangle> = emptyList()
        set(valeur) {
            field = valeur
            reinitialiser()
        }

    /** Exagération des hauteurs (1 = réel). */
    var exageration = 1.0
        set(valeur) {
            field = valeur
            invalidate()
        }

    private var azimut = 30.0
    private var elevation = 35.0
    private var zoom = 1.0

    private val densite = resources.displayMetrics.density
    private val pinceauFace = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pinceauArete = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.7f * densite
        color = Color.argb(110, 0, 0, 0)
    }
    private val pinceauTexte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 11f * densite
    }

    /** Remet la vue de départ : de biais depuis le Sud-Sud-Ouest, sans zoom. */
    fun reinitialiser() {
        azimut = 30.0
        elevation = 35.0
        zoom = 1.0
        invalidate()
    }

    /** Vue de dessus (comme la carte). */
    fun vueDeDessus() {
        azimut = 0.0
        elevation = 90.0
        invalidate()
    }

    override fun onDraw(toile: Canvas) {
        toile.drawColor(Color.rgb(236, 240, 243))
        if (triangles.isEmpty()) {
            toile.drawText("Calculez d'abord le volume.", 12 * densite, 24 * densite, pinceauTexte)
            return
        }
        val plage = plageAltitudes(triangles)
        val sommets = triangles.flatMap { it.sommets }
        val estMin = sommets.minOf { it.est }
        val estMax = sommets.maxOf { it.est }
        val nordMin = sommets.minOf { it.nord }
        val nordMax = sommets.maxOf { it.nord }
        val projection = Projection3D(
            centreEst = (estMin + estMax) / 2,
            centreNord = (nordMin + nordMax) / 2,
            altitudeCentre = (plage.min + plage.max) / 2,
            azimutDeg = azimut,
            elevationDeg = elevation,
            exageration = exageration,
        )
        // Échelle : le stock occupe environ 80 % du côté le plus petit de l'écran.
        val taille = max(max(estMax - estMin, nordMax - nordMin), (plage.max - plage.min) * exageration).coerceAtLeast(1.0)
        val echelle = 0.8 * minOf(width, height) / taille * zoom
        val cx = width / 2.0
        val cy = height / 2.0

        for (t in projection.trierPourDessin(triangles)) {
            val chemin = Path()
            t.sommets.forEachIndexed { i, s ->
                val (x, y, _) = projection.projeter(s.est, s.nord, s.altitude)
                val ex = (cx + x * echelle).toFloat()
                val ey = (cy - y * echelle).toFloat()
                if (i == 0) chemin.moveTo(ex, ey) else chemin.lineTo(ex, ey)
            }
            chemin.close()
            val altitudeMoyenne = t.sommets.sumOf { it.altitude } / 3
            val couleur = couleurAltitude(altitudeMoyenne, plage)
            val lumiere = projection.eclairage(t)
            pinceauFace.color = Color.rgb(
                (couleur.rouge * lumiere).toInt(), (couleur.vert * lumiere).toInt(), (couleur.bleu * lumiere).toInt(),
            )
            toile.drawPath(chemin, pinceauFace)
            toile.drawPath(chemin, pinceauArete)
        }
        val texte = "Hauteurs ×${nombre(exageration, 0)}  ·  ${nombre(plage.min, 2)} à ${nombre(plage.max, 2)} m"
        toile.drawText(texte, 10 * densite, height - 10 * densite, pinceauTexte)
    }

    // --- Gestes : un doigt tourne et incline, deux doigts zooment ---

    private val gestes = GestureDetector(ecran, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            azimut = (azimut + distanceX / densite * 0.5) % 360
            elevation = (elevation - distanceY / densite * 0.3).coerceIn(5.0, 90.0)
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            reinitialiser()
            return true
        }
    })

    private val pince = ScaleGestureDetector(ecran, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detecteur: ScaleGestureDetector): Boolean {
            zoom = (zoom * detecteur.scaleFactor).coerceIn(0.3, 8.0)
            invalidate()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(evenement: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        pince.onTouchEvent(evenement)
        if (!pince.isInProgress) gestes.onTouchEvent(evenement)
        return true
    }
}
