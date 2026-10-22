package ch.emlidstock.noyau

import ch.emlidstock.noyau.partage.ClientFirebase
import ch.emlidstock.noyau.partage.DocumentFirestore
import ch.emlidstock.noyau.partage.ErreurFirebase
import ch.emlidstock.noyau.partage.MemoireConnexion
import ch.emlidstock.noyau.partage.appareilDepuis
import ch.emlidstock.noyau.partage.champsDeLAppareil
import ch.emlidstock.noyau.partage.champsDepuisFirestore
import ch.emlidstock.noyau.partage.champsVersFirestore
import ch.emlidstock.noyau.partage.codeAppareil
import ch.emlidstock.noyau.partage.trierAppareils
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartageTest {

    @Test
    fun valeursFirestoreAllerRetour() {
        val champs = mapOf(
            "nom" to "Tablette Gland",
            "vuLe" to 1792650000000L,
            "altitude" to 427.125,
            "actif" to true,
            "vide" to null,
            "liste" to listOf("a", 2L),
            "groupe" to mapOf("est" to 2509500.921),
        )
        val json = champsVersFirestore(champs)
        assertEquals("Tablette Gland", json.getJSONObject("nom").getString("stringValue"))
        // Les nombres entiers sont écrits comme du texte, comme Firestore le demande.
        assertEquals("1792650000000", json.getJSONObject("vuLe").getString("integerValue"))
        // Passage par le texte JSON, comme sur internet.
        val relu = champsDepuisFirestore(JSONObject(json.toString()))
        assertEquals(champs, relu)
    }

    @Test
    fun valeursFirestoreRecues() {
        // Forme renvoyée par Firestore, avec une date.
        val recu = JSONObject(
            """{"code":{"stringValue":"ABC123"},"n":{"integerValue":"42"},"cree":{"timestampValue":"2026-10-22T10:00:00Z"},
               "vide":{"arrayValue":{}},"groupe":{"mapValue":{}}}""",
        )
        val champs = champsDepuisFirestore(recu)
        assertEquals("ABC123", champs["code"])
        assertEquals(42L, champs["n"])
        assertEquals("2026-10-22T10:00:00Z", champs["cree"])
        assertEquals(emptyList<Any?>(), champs["vide"])
        assertEquals(emptyMap<String, Any?>(), champs["groupe"])
    }

    @Test
    fun codeEtFicheDAppareil() {
        assertEquals("K7F3XA", codeAppareil("k7f3xAbcdEFGhijklmnop1234567"))
        val champs = champsDeLAppareil("k7f3xAbcd", "Crosscall Core-T4", "android", "0.10.1", 1000L)
        assertEquals(setOf("code", "modele", "appli", "version", "vuLe"), champs.keys)
        val appareil = appareilDepuis(DocumentFirestore("k7f3xAbcd", champs + ("nom" to "Tablette Gland")))
        assertEquals("K7F3XA", appareil.code)
        assertEquals("Tablette Gland", appareil.nom)
        assertEquals(1000L, appareil.vuLeMs)
        // Fiche incomplète : pas de plantage.
        val vide = appareilDepuis(DocumentFirestore("zz1234567", emptyMap()))
        assertEquals("ZZ1234", vide.code)
        assertNull(vide.nom)
    }

    @Test
    fun appareilsNommesDAbord() {
        val a = appareilDepuis(DocumentFirestore("a", mapOf("nom" to "Tablette B", "vuLe" to 1L)))
        val b = appareilDepuis(DocumentFirestore("b", mapOf("nom" to "tablette A", "vuLe" to 2L)))
        val c = appareilDepuis(DocumentFirestore("c", mapOf("vuLe" to 3L)))
        val d = appareilDepuis(DocumentFirestore("d", mapOf("vuLe" to 9L)))
        assertEquals(listOf("b", "a", "d", "c"), trierAppareils(listOf(c, a, d, b)).map { it.identifiant })
    }

    /**
     * Essai complet avec l'émulateur Firebase (Firebase d'essai sur l'ordinateur, avec les vraies règles
     * firebase/firestore.rules). Lancé seulement si la variable FIREBASE_EMULATEUR est définie
     * (ex. « localhost »), sinon ignoré (dans GitHub Actions par exemple).
     */
    @Test
    fun essaiAvecLEmulateur() {
        val hote = System.getenv("FIREBASE_EMULATEUR") ?: return
        val projet = "demo-topo"
        fun nouveauClient(memoire: MemoireConnexion = Memoire()) = ClientFirebase(
            cleApi = "cle-essai", projetId = projet, memoire = memoire,
            adresseAuth = "http://$hote:9099/identitytoolkit.googleapis.com",
            adresseJeton = "http://$hote:9099/securetoken.googleapis.com",
            adresseFirestore = "http://$hote:8080",
        )

        // 1. Une tablette se connecte et écrit sa fiche.
        val memoireTablette = Memoire()
        val tablette = nouveauClient(memoireTablette)
        val uidTablette = tablette.connecter()
        tablette.ecrireChamps("appareils/$uidTablette", champsDeLAppareil(uidTablette, "Core-T4", "android", "0.10", 1L))
        assertEquals("Core-T4", appareilDepuis(tablette.lireDocument("appareils/$uidTablette")!!).modele)
        // Mise à jour de sa fiche : autorisée.
        tablette.ecrireChamps("appareils/$uidTablette", mapOf("vuLe" to 2L))
        // Elle ne peut pas se donner un nom, ni se déclarer administrateur, ni lister les appareils.
        assertEquals(403, assertFailsWith<ErreurFirebase> { tablette.ecrireChamps("appareils/$uidTablette", mapOf("nom" to "Moi")) }.codeHttp)
        assertEquals(403, assertFailsWith<ErreurFirebase> { tablette.ecrireChamps("admins/$uidTablette", mapOf("nom" to "Moi")) }.codeHttp)
        assertEquals(403, assertFailsWith<ErreurFirebase> { tablette.listerDocuments("appareils") }.codeHttp)
        // Elle peut savoir qu'elle n'est pas administrateur.
        assertNull(tablette.lireDocument("admins/$uidTablette"))

        // 2. Au lancement suivant, la tablette garde la même identité.
        assertEquals(uidTablette, nouveauClient(memoireTablette).connecter())

        // 3. Un second appareil devient administrateur (comme fait à la main dans la console Firebase).
        val admin = nouveauClient()
        val uidAdmin = admin.connecter()
        admin.ecrireChamps("appareils/$uidAdmin", champsDeLAppareil(uidAdmin, "Pixel", "android", "0.10", 1L))
        assertNotEquals(uidTablette, uidAdmin)
        creerAdminCommeLaConsole(hote, projet, uidAdmin)
        assertTrue(admin.lireDocument("admins/$uidAdmin") != null)
        // La tablette ne peut pas lire la fiche de l'administrateur.
        assertEquals(403, assertFailsWith<ErreurFirebase> { tablette.lireDocument("appareils/$uidAdmin") }.codeHttp)

        // 4. L'administrateur liste, nomme et supprime.
        assertTrue(admin.listerDocuments("appareils").any { it.id == uidTablette })
        admin.ecrireChamps("appareils/$uidTablette", mapOf("nom" to "Tablette Gland"))
        assertEquals("Tablette Gland", appareilDepuis(tablette.lireDocument("appareils/$uidTablette")!!).nom)
        // La tablette met à jour sa fiche sans effacer le nom.
        tablette.ecrireChamps("appareils/$uidTablette", mapOf("vuLe" to 3L))
        assertEquals("Tablette Gland", appareilDepuis(admin.lireDocument("appareils/$uidTablette")!!).nom)
        admin.supprimerDocument("appareils/$uidTablette")
        assertNull(admin.lireDocument("appareils/$uidTablette"))
    }

    private class Memoire : MemoireConnexion {
        override var identifiant: String? = null
        override var jetonRenouvellement: String? = null
    }

    /** Écrit admins/<uid> en passant outre les règles (jeton « owner » de l'émulateur), comme la console. */
    private fun creerAdminCommeLaConsole(hote: String, projet: String, uid: String) {
        val connexion = java.net.URL(
            "http://$hote:8080/v1/projects/$projet/databases/(default)/documents/admins?documentId=$uid",
        ).openConnection() as java.net.HttpURLConnection
        connexion.requestMethod = "POST"
        connexion.doOutput = true
        connexion.setRequestProperty("Authorization", "Bearer owner")
        connexion.setRequestProperty("Content-Type", "application/json")
        connexion.outputStream.use { it.write("""{"fields":{"nom":{"stringValue":"Admin"}}}""".toByteArray()) }
        assertEquals(200, connexion.responseCode)
    }
}
