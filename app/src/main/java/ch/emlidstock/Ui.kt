package ch.emlidstock

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Petits outils pour construire les écrans en code (sans fichiers de mise en page). */
class Ui(private val contexte: Context) {

    fun dp(valeur: Int): Int = (valeur * contexte.resources.displayMetrics.density).toInt()

    fun colonne() = LinearLayout(contexte).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(24))
    }

    fun titre(texte: String) = TextView(contexte).apply {
        text = texte
        textSize = 17f
        setTypeface(null, Typeface.BOLD)
        setTextColor(contexte.getColor(R.color.bleu_nuit))
        setPadding(0, dp(16), 0, dp(4))
    }

    fun texte(taille: Float = 15f) = TextView(contexte).apply {
        textSize = taille
        setTextIsSelectable(false)
    }

    fun zoneTexte(taille: Float = 13f) = TextView(contexte).apply {
        typeface = Typeface.MONOSPACE
        textSize = taille
        setTextIsSelectable(true)
    }

    fun bouton(texte: String, action: () -> Unit) = Button(contexte).apply {
        text = texte
        isAllCaps = false
        setOnClickListener { action() }
    }

    fun ligneBoutons(vararg boutons: Button) = LinearLayout(contexte).apply {
        orientation = LinearLayout.HORIZONTAL
        for (b in boutons) addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
}

/** Nombre avec un nombre fixe de décimales et le point comme séparateur ; « — » si absent. */
fun nombre(valeur: Double?, decimales: Int): String =
    if (valeur == null) "—" else String.format(Locale.ROOT, "%.${decimales}f", valeur)
