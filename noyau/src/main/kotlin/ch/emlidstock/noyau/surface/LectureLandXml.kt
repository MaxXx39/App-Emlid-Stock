package ch.emlidstock.noyau.surface

import ch.emlidstock.noyau.volume.Sommet

/*
 * Lecture des fichiers LandXML 1.1 (export Mensura) : surfaces TIN, reprise de l'appli topo V1
 * (src/topo/landxml.js).
 *
 * Pièges de ce format :
 * - Les coordonnées sont dans l'ordre Nord, Est, altitude (et non Est, Nord).
 *   On les remet toujours dans l'ordre Est, Nord, altitude à la lecture.
 * - Les triangles (<F>) désignent les points par leur numéro interne « id »,
 *   pas par leur position dans la liste.
 * - Un fichier peut contenir plusieurs surfaces : l'utilisateur choisit.
 * - Seules les surfaces de type TIN sont lues.
 * - Un triangle marqué i="1" est invisible (exclu de la surface) : on l'ignore.
 */

private val BLOC_SURFACE = Regex("""<Surface\b([^>]*)>([\s\S]*?)</Surface>""")
private val DEFINITION = Regex("""<Definition\b([^>]*)>""")
private val BALISE_P = Regex("""<P\b([^>]*)>([^<]*)</P>""")
private val BALISE_F = Regex("""<F\b([^>]*)>([^<]*)</F>""")
private val BALISE_RUPTURE = Regex("""<Breakline\b[^>]*>[\s\S]*?</Breakline>""")
private val ATTRIBUT = Regex("""([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
private val COMMENTAIRE = Regex("""<!--[\s\S]*?-->""")

/** Lit le texte d'un fichier LandXML : surfaces TIN, erreurs (avec numéro de ligne) et avertissements. */
fun lireLandXml(texte: String): LectureSurfaces {
    val erreurs = mutableListOf<String>()
    val avertissements = mutableListOf<String>()
    val surfaces = mutableListOf<SurfaceLue>()

    // On remplace les commentaires par des espaces (même longueur) pour garder les numéros de ligne.
    val contenu = COMMENTAIRE.replace(texte) { m -> m.value.replace(Regex("[^\n]"), " ") }
    if (!Regex("""<LandXML[\s>]""").containsMatchIn(contenu)) {
        return LectureSurfaces(surfaces, listOf("ce fichier n'est pas un fichier LandXML"), avertissements)
    }
    val blocs = BLOC_SURFACE.findAll(contenu).toList()
    if (blocs.isEmpty()) erreurs.add("aucune surface trouvée dans le fichier")

    for (bloc in blocs) {
        val attributsSurface = lireAttributs(bloc.groupValues[1])
        val nom = attributsSurface["name"] ?: "Surface ${surfaces.size + 1}"
        val corps = bloc.groupValues[2]
        val debutCorps = bloc.groups[2]!!.range.first

        val definition = DEFINITION.find(corps)
        val attributsDefinition = definition?.let { lireAttributs(it.groupValues[1]) }.orEmpty()
        val type = attributsDefinition["surfType"]
        if (type != "TIN") {
            avertissements.add("surface « $nom » ignorée : type ${type ?: "inconnu"} (seul le type TIN est lu)")
            continue
        }
        val erreursSurface = mutableListOf<String>()
        fun signaler(position: Int, message: String) =
            erreursSurface.add("surface « $nom », ligne ${numeroDeLigne(contenu, debutCorps + position)} : $message")

        // --- Points : <P id="12" name="44">Nord Est altitude</P> ---
        val sommets = mutableListOf<Sommet>()
        val positionDuId = HashMap<String, Int>()
        for (balise in BALISE_P.findAll(corps)) {
            val attributs = lireAttributs(balise.groupValues[1])
            val id = attributs["id"]
            val valeurs = lireNombres(balise.groupValues[2])
            if (id == null) {
                signaler(balise.range.first, "point sans numéro « id »")
                continue
            }
            if (valeurs == null || valeurs.size < 3) {
                signaler(balise.range.first, "point id $id : 3 nombres attendus (Nord, Est, altitude)")
                continue
            }
            val (nord, est, altitude) = valeurs
            val sommet = Sommet(attributs["name"] ?: id, est, nord, altitude)
            val existante = positionDuId[id]
            if (existante != null) {
                sommets[existante] = sommet // même id deux fois : le dernier est gardé (comme la V1)
            } else {
                positionDuId[id] = sommets.size
                sommets.add(sommet)
            }
        }

        // --- Triangles : <F>id1 id2 id3</F>, avec i="1" pour un triangle invisible ---
        val faces = mutableListOf<IntArray>()
        var invisibles = 0
        for (balise in BALISE_F.findAll(corps)) {
            if (lireAttributs(balise.groupValues[1])["i"] == "1") {
                invisibles++
                continue
            }
            val ids = balise.groupValues[2].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (ids.size < 3) {
                signaler(balise.range.first, "triangle avec moins de 3 points")
                continue
            }
            val manquant = ids.take(3).firstOrNull { it !in positionDuId }
            if (manquant != null) {
                signaler(balise.range.first, "triangle qui utilise le point id $manquant, absent de la liste des points")
                continue
            }
            faces.add(IntArray(3) { positionDuId.getValue(ids[it]) })
        }

        erreurs.addAll(erreursSurface)
        if (faces.isEmpty()) {
            erreurs.add("surface « $nom » : aucun triangle lisible")
            continue
        }
        surfaces.add(
            SurfaceLue(
                nom = nom,
                sommets = sommets,
                faces = faces,
                description = attributsSurface["desc"].orEmpty(),
                nombreLignesRupture = BALISE_RUPTURE.findAll(corps).count(),
                trianglesInvisibles = invisibles,
                surfaceAnnoncee = attributsDefinition["area2DSurf"]?.toDoubleOrNull(),
            ),
        )
    }
    return LectureSurfaces(surfaces, erreurs, avertissements)
}

/** Lit les attributs d'une balise : ' id="12" name="a" ' → { id: 12, name: a }. */
private fun lireAttributs(texte: String): Map<String, String> =
    ATTRIBUT.findAll(texte).associate { m ->
        m.groupValues[1] to decoderEntites(m.groups[2]?.value ?: m.groups[3]?.value ?: "")
    }

/** Remplace les codes XML (&amp; …) par les caractères qu'ils représentent. */
private fun decoderEntites(texte: String) =
    texte.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

/** Lit une suite de nombres séparés par des espaces ; null si un élément n'est pas un nombre. */
private fun lireNombres(texte: String): List<Double>? {
    val nombres = texte.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toDoubleOrNull() ?: return null }
    return if (nombres.all { it.isFinite() }) nombres else null
}

/** Numéro de ligne (à partir de 1) d'une position dans le texte. */
private fun numeroDeLigne(texte: String, position: Int): Int {
    var ligne = 1
    for (i in 0 until minOf(position, texte.length)) if (texte[i] == '\n') ligne++
    return ligne
}
