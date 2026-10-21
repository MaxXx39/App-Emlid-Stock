package ch.emlidstock

import ch.emlidstock.noyau.leve.Chantier
import ch.emlidstock.noyau.leve.ModeLeve
import ch.emlidstock.noyau.leve.PointLeve
import ch.emlidstock.noyau.leve.Stock
import ch.emlidstock.noyau.leve.TypeLeve
import ch.emlidstock.noyau.surface.SurfaceImportee
import ch.emlidstock.noyau.surface.SurfacesDuChantier
import ch.emlidstock.noyau.volume.Sommet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Enregistrement des chantiers et des stocks sur le téléphone, dans un fichier
 * `leves.json` (dossier privé de l'appli). Le fichier est réécrit à chaque changement :
 * on écrit d'abord un fichier temporaire, puis on le renomme, pour ne jamais perdre les données
 * si le téléphone s'éteint pendant l'écriture.
 * Les surfaces importées (LandXML, DXF), parfois grosses, sont dans un fichier à part
 * (`surfaces.json`), réécrit seulement quand elles changent.
 */
class Depot(private val dossier: File) {

    private val fichier = File(dossier, "leves.json")
    private val fichierSurfaces = File(dossier, "surfaces.json")

    val chantiers = mutableListOf<Chantier>()
    val stocks = mutableListOf<Stock>()
    val surfacesImportees = mutableListOf<SurfaceImportee>()
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
            chargerSurfaces()
        } catch (erreur: Exception) {
            // On garde une copie du fichier abîmé pour pouvoir le récupérer.
            fichier.copyTo(File(dossier, "leves-illisible-${System.currentTimeMillis()}.json"), overwrite = true)
            "Fichier des levés illisible (${erreur.message}). Une copie a été gardée."
        }
    }

    /** Lit le fichier des surfaces importées. Renvoie un message d'erreur, ou null. */
    private fun chargerSurfaces(): String? {
        if (!fichierSurfaces.exists()) return null
        return try {
            val liste = JSONObject(fichierSurfaces.readText(Charsets.UTF_8)).getJSONArray("surfaces")
            surfacesImportees.clear()
            for (i in 0 until liste.length()) surfacesImportees.add(lireSurface(liste.getJSONObject(i)))
            null
        } catch (erreur: Exception) {
            fichierSurfaces.copyTo(File(dossier, "surfaces-illisible-${System.currentTimeMillis()}.json"), overwrite = true)
            "Fichier des surfaces importées illisible (${erreur.message}). Une copie a été gardée."
        }
    }

    /** Écrit le fichier des surfaces importées (après un import, un renommage ou une suppression). */
    fun enregistrerSurfaces() {
        val racine = JSONObject()
        racine.put("version", 1)
        racine.put("surfaces", JSONArray().apply { surfacesImportees.forEach { put(ecrireSurface(it)) } })
        ecrireSansRisque(fichierSurfaces, racine.toString())
    }

    /** Écrit tout dans le fichier. */
    fun enregistrer() {
        val racine = JSONObject()
        racine.put("version", 1)
        racine.put("chantierActif", chantierActifId ?: "")
        racine.put("chantiers", JSONArray().apply { chantiers.forEach { put(ecrireChantier(it)) } })
        racine.put("stocks", JSONArray().apply { stocks.forEach { put(ecrireStock(it)) } })
        ecrireSansRisque(fichier, racine.toString(1))
    }

    /** Écrit d'abord un fichier temporaire, puis le renomme (jamais de fichier à moitié écrit). */
    private fun ecrireSansRisque(cible: File, texte: String) {
        val temporaire = File(dossier, "${cible.name}.tmp")
        temporaire.writeText(texte, Charsets.UTF_8)
        if (!temporaire.renameTo(cible)) {
            cible.delete()
            temporaire.renameTo(cible)
        }
    }

    fun chantierActif(): Chantier? = chantiers.firstOrNull { it.id == chantierActifId }

    /** Stocks et surfaces levées du chantier, les plus récents en premier. */
    fun stocksDuChantier(chantierId: String): List<Stock> =
        stocks.filter { it.chantierId == chantierId }.sortedByDescending { it.creeLeMs }

    /** Surfaces du chantier (levées et importées), pour choisir et calculer la base des volumes. */
    fun surfacesDuChantier(chantierId: String): SurfacesDuChantier =
        SurfacesDuChantier(stocksDuChantier(chantierId), surfacesImportees.filter { it.chantierId == chantierId })

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
        put("baseSurfaceId", s.baseSurfaceId ?: JSONObject.NULL)
        put("trianglesSupprimes", JSONArray().apply { s.trianglesSupprimes.sorted().forEach { put(it) } })
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
            baseSurfaceId = if (o.isNull("baseSurfaceId")) null else o.optString("baseSurfaceId").ifEmpty { null },
            trianglesSupprimes = o.optJSONArray("trianglesSupprimes")?.let { liste -> (0 until liste.length()).map { liste.getString(it) }.toSet() }
                ?: emptySet(),
        )
    }

    // Surface importée : coordonnées en listes (Est, Nord, altitude, noms) et triangles à plat (3 positions par triangle).
    private fun ecrireSurface(s: SurfaceImportee) = JSONObject().apply {
        put("id", s.id)
        put("chantierId", s.chantierId)
        put("nom", s.nom)
        put("origine", s.origine)
        put("nomFichier", s.nomFichier)
        put("creeLe", s.creeLeMs)
        put("noms", JSONArray().apply { s.sommets.forEach { put(it.nom) } })
        put("est", JSONArray().apply { s.sommets.forEach { put(it.est) } })
        put("nord", JSONArray().apply { s.sommets.forEach { put(it.nord) } })
        put("altitude", JSONArray().apply { s.sommets.forEach { put(it.altitude) } })
        put("faces", JSONArray().apply { s.faces.forEach { f -> f.forEach { put(it) } } })
    }

    private fun lireSurface(o: JSONObject): SurfaceImportee {
        val noms = o.getJSONArray("noms")
        val est = o.getJSONArray("est")
        val nord = o.getJSONArray("nord")
        val altitude = o.getJSONArray("altitude")
        val sommets = (0 until noms.length()).map { Sommet(noms.getString(it), est.getDouble(it), nord.getDouble(it), altitude.getDouble(it)) }
        val plates = o.getJSONArray("faces")
        val faces = (0 until plates.length() / 3).map { i -> IntArray(3) { plates.getInt(3 * i + it) } }
        return SurfaceImportee(
            o.getString("id"), o.getString("chantierId"), o.getString("nom"), o.getString("origine"),
            o.optString("nomFichier"), o.getLong("creeLe"), sommets, faces,
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
