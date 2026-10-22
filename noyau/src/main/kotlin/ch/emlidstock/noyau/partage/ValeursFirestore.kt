package ch.emlidstock.noyau.partage

import org.json.JSONArray
import org.json.JSONObject

/*
 * Firestore (la base de données en ligne de Firebase) n'enregistre pas du JSON simple :
 * chaque valeur est « emballée » avec son type, par exemple
 *   "nom": {"stringValue": "Tablette Gland"}   ou   "vuLe": {"integerValue": "1792650000000"}.
 * Ces deux fonctions passent d'une forme à l'autre.
 * Types gérés : texte, nombre entier, nombre à virgule, oui/non, rien (null), liste, groupe de valeurs (Map).
 */

/** Emballe des champs Kotlin au format Firestore : {"champ": {"stringValue": …}, …}. */
fun champsVersFirestore(champs: Map<String, Any?>): JSONObject {
    val resultat = JSONObject()
    for ((nom, valeur) in champs) resultat.put(nom, valeurVersFirestore(valeur))
    return resultat
}

/** Emballe une seule valeur au format Firestore. */
fun valeurVersFirestore(valeur: Any?): JSONObject = when (valeur) {
    null -> JSONObject().put("nullValue", JSONObject.NULL)
    is String -> JSONObject().put("stringValue", valeur)
    is Boolean -> JSONObject().put("booleanValue", valeur)
    // Firestore veut les nombres entiers écrits comme du texte.
    is Int, is Long -> JSONObject().put("integerValue", valeur.toString())
    is Double, is Float -> JSONObject().put("doubleValue", (valeur as Number).toDouble())
    is Map<*, *> -> {
        val champs = JSONObject()
        for ((cle, sousValeur) in valeur) champs.put(cle.toString(), valeurVersFirestore(sousValeur))
        JSONObject().put("mapValue", JSONObject().put("fields", champs))
    }
    is List<*> -> {
        val valeurs = JSONArray()
        for (element in valeur) valeurs.put(valeurVersFirestore(element))
        JSONObject().put("arrayValue", JSONObject().put("values", valeurs))
    }
    else -> throw IllegalArgumentException("Type non géré pour Firestore : ${valeur::class.simpleName}")
}

/** Déballe les champs d'un document Firestore ({"champ": {"stringValue": …}}) en valeurs Kotlin. */
fun champsDepuisFirestore(champs: JSONObject?): Map<String, Any?> {
    if (champs == null) return emptyMap()
    val resultat = linkedMapOf<String, Any?>()
    for (nom in champs.keys()) resultat[nom] = valeurDepuisFirestore(champs.getJSONObject(nom))
    return resultat
}

/** Déballe une seule valeur Firestore. Les dates (timestampValue) restent en texte. */
fun valeurDepuisFirestore(valeur: JSONObject): Any? = when {
    valeur.has("stringValue") -> valeur.getString("stringValue")
    valeur.has("integerValue") -> valeur.get("integerValue").toString().toLong()
    valeur.has("doubleValue") -> valeur.getDouble("doubleValue")
    valeur.has("booleanValue") -> valeur.getBoolean("booleanValue")
    valeur.has("timestampValue") -> valeur.getString("timestampValue")
    valeur.has("mapValue") -> champsDepuisFirestore(valeur.getJSONObject("mapValue").optJSONObject("fields"))
    valeur.has("arrayValue") -> {
        val liste = valeur.getJSONObject("arrayValue").optJSONArray("values") ?: JSONArray()
        List(liste.length()) { i -> valeurDepuisFirestore(liste.getJSONObject(i)) }
    }
    else -> null
}
