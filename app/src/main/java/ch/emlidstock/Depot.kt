package ch.emlidstock

import ch.emlidstock.noyau.leve.Chantier
import ch.emlidstock.noyau.leve.ModeLeve
import ch.emlidstock.noyau.leve.PointLeve
import ch.emlidstock.noyau.leve.Stock
import ch.emlidstock.noyau.leve.TypeLeve
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Enregistrement des chantiers et des stocks sur le téléphone, dans un fichier
 * `leves.json` (dossier privé de l'appli). Le fichier est réécrit à chaque changement :
 * on écrit d'abord un fichier temporaire, puis on le renomme, pour ne jamais perdre les données
 * si le téléphone s'éteint pendant l'écriture.
 */
class Depot(private val dossier: File) {

    private val fichier = File(dossier, "leves.json")

    val chantiers = mutableListOf<Chantier>()
    val stocks = mutableListOf<Stock>()
    var chantierActifId: String? = null

    /** Lit le fichier. Renvoie un message d'erreur si le fichier est illisible, sinon null. */
    fun charger(): String? {
        if (!fichier.exists()) return null
        return try {
            val racine = JSONObject(fichier.readText(Charsets.UTF_8))
            chantiers.clear()
            stocks.clear()
            val listeChantiers = racine.getJSONArray("chantiers")
            for (i in 0 until listeChantiers.length()) chantiers.add(lireChantier(listeChantiers.getJSONObject(i)))
            val listeStocks = racine.getJSONArray("stocks")
            for (i in 0 until listeStocks.length()) stocks.add(lireStock(listeStocks.getJSONObject(i)))
            chantierActifId = racine.optString("chantierActif").ifEmpty { null }
            null
        } catch (erreur: Exception) {
            // On garde une copie du fichier abîmé pour pouvoir le récupérer.
            fichier.copyTo(File(dossier, "leves-illisible-${System.currentTimeMillis()}.json"), overwrite = true)
            "Fichier des levés illisible (${erreur.message}). Une copie a été gardée."
        }
    }

    /** Écrit tout dans le fichier. */
    fun enregistrer() {
        val racine = JSONObject()
        racine.put("version", 1)
        racine.put("chantierActif", chantierActifId ?: "")
        racine.put("chantiers", JSONArray().apply { chantiers.forEach { put(ecrireChantier(it)) } })
        racine.put("stocks", JSONArray().apply { stocks.forEach { put(ecrireStock(it)) } })
        val temporaire = File(dossier, "leves.json.tmp")
        temporaire.writeText(racine.toString(1), Charsets.UTF_8)
        if (!temporaire.renameTo(fichier)) {
            fichier.delete()
            temporaire.renameTo(fichier)
        }
    }

    fun chantierActif(): Chantier? = chantiers.firstOrNull { it.id == chantierActifId }

    fun stocksDuChantier(chantierId: String): List<Stock> =
        stocks.filter { it.chantierId == chantierId }.sortedByDescending { it.creeLeMs }

    // --- Lecture / écriture des objets ---

    private fun ecrireChantier(c: Chantier) = JSONObject().apply {
        put("id", c.id)
        put("nom", c.nom)
        put("creeLe", c.creeLeMs)
        put("affaire", c.affaire)
        put("dessinePar", c.dessinePar)
    }

    private fun lireChantier(o: JSONObject) = Chantier(
        o.getString("id"), o.getString("nom"), o.getLong("creeLe"), o.optString("affaire"), o.optString("dessinePar"),
    )

    private fun ecrireStock(s: Stock) = JSONObject().apply {
        put("id", s.id)
        put("chantierId", s.chantierId)
        put("nom", s.nom)
        put("creeLe", s.creeLeMs)
        put("type", s.type.name)
        put("ligneHautActive", s.ligneHautActive)
        put("lignesFermees", JSONArray().apply { s.lignesFermees.sorted().forEach { put(it) } })
        put("points", JSONArray().apply { s.points.forEach { put(ecrirePoint(it)) } })
    }

    private fun lireStock(o: JSONObject): Stock {
        val points = mutableListOf<PointLeve>()
        val liste = o.getJSONArray("points")
        for (i in 0 until liste.length()) points.add(lirePoint(liste.getJSONObject(i)))
        return Stock(
            id = o.getString("id"),
            chantierId = o.getString("chantierId"),
            nom = o.getString("nom"),
            creeLeMs = o.getLong("creeLe"),
            type = TypeLeve.valueOf(o.optString("type", TypeLeve.STOCK.name)),
            points = points,
            ligneHautActive = if (o.has("ligneHautActive")) o.getInt("ligneHautActive") else null,
            lignesFermees = o.optJSONArray("lignesFermees")?.let { liste -> (0 until liste.length()).map { liste.getInt(it) }.toSet() }
                ?: emptySet(),
        )
    }

    private fun ecrirePoint(p: PointLeve) = JSONObject().apply {
        put("nom", p.nom)
        put("mode", p.mode.name)
        put("ligne", p.ligne ?: JSONObject.NULL)
        put("numero", p.numero)
        put("est", p.est)
        put("nord", p.nord)
        put("altitude", p.altitude)
        put("latitude", p.latitudeDeg)
        put("longitude", p.longitudeDeg)
        put("hauteurEllipsoidale", p.hauteurEllipsoidaleM)
        put("horodatage", p.horodatageMs)
        put("qualite", p.qualiteGga)
        put("satellites", p.nbSatellites ?: JSONObject.NULL)
        put("hdop", p.hdop ?: JSONObject.NULL)
        put("ageCorrections", p.ageCorrectionsS ?: JSONObject.NULL)
        put("station", p.stationReference)
        put("inclinaison", p.inclinaisonDeg ?: JSONObject.NULL)
        put("modifieALaMain", p.modifieALaMain)
    }

    private fun lirePoint(o: JSONObject) = PointLeve(
        nom = o.getString("nom"),
        mode = ModeLeve.valueOf(o.getString("mode")),
        ligne = if (o.isNull("ligne")) null else o.getInt("ligne"),
        numero = o.getInt("numero"),
        est = o.getDouble("est"),
        nord = o.getDouble("nord"),
        altitude = o.getDouble("altitude"),
        latitudeDeg = o.getDouble("latitude"),
        longitudeDeg = o.getDouble("longitude"),
        hauteurEllipsoidaleM = o.getDouble("hauteurEllipsoidale"),
        horodatageMs = o.getLong("horodatage"),
        qualiteGga = o.getInt("qualite"),
        nbSatellites = if (o.isNull("satellites")) null else o.getInt("satellites"),
        hdop = if (o.isNull("hdop")) null else o.getDouble("hdop"),
        ageCorrectionsS = if (o.isNull("ageCorrections")) null else o.getDouble("ageCorrections"),
        stationReference = o.optString("station"),
        inclinaisonDeg = if (o.isNull("inclinaison")) null else o.getDouble("inclinaison"),
        modifieALaMain = o.optBoolean("modifieALaMain", false),
    )
}
