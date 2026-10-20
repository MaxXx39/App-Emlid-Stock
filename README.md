# Emlid Stock

Appli Android de levé de stocks en RTK avec un GNSS Emlid Reach RX2.

**Version actuelle : 0.2 - position en coordonnées suisses** (étape 2 du cahier des charges). Elle ne lève pas encore de stocks : elle affiche la position du RX2 en **MN95 (Est, Nord)** et en **altitude RAN95 (LHN95)**, l'état de la compensation d'inclinaison et le statut RTK.

## Installer l'appli sur le téléphone

1. Sur le téléphone, ouvrez le navigateur (Chrome) et allez sur la page du projet : `https://github.com/MaxXx39/App-Emlid-Stock/releases`
2. Sous « Dernière version », touchez **EmlidStock.apk** : le fichier se télécharge.
3. Ouvrez le fichier téléchargé (notification « Téléchargement terminé », ou appli « Fichiers »).
4. La première fois, Android bloque : touchez **Paramètres**, puis activez **Autoriser cette source** (pour Chrome), puis revenez et touchez **Installer**.
5. Si Google Play Protect affiche un avertissement (appli inconnue), touchez **Plus de détails** puis **Installer quand même**. C'est normal pour une appli personnelle qui ne vient pas du Play Store.
6. L'appli « Emlid Stock » apparaît avec les autres applis.

Pour une mise à jour : refaire les étapes 1 à 3, puis **Mettre à jour**. Les réglages sont gardés.

## Préparer (une seule fois)

1. **Appairer le RX2** avec le téléphone : Réglages Android → Bluetooth → choisir le RX2.
2. Ouvrir l'appli et accepter l'autorisation « appareils à proximité » (Bluetooth).
3. Toucher « Modifier la hauteur de canne » et taper la hauteur réglée dans Emlid Flow (rappel seulement).

Plus besoin des options pour développeurs ni de la « position fictive ».

## Utiliser

1. Lancer **Emlid Flow**, se connecter au RX2 avec les corrections swipos, attendre le RTK fixe. Laisser Emlid Flow ouvert (il envoie les corrections au RX2).
2. Lancer **Emlid Stock**, « Choisir le récepteur » → le RX2 (ensuite : « Reconnecter »).
3. En haut : bandeau vert « RTK FIXE », puis E, N, H (MN95 / RAN95), à comparer avec Emlid Flow.
4. **Test d'inclinaison** : pointe de canne sur un point fixe, canne droite, « Fixer le départ ». Incliner la canne (15° à 30°) dans plusieurs directions sans bouger la pointe : l'écart doit rester de quelques centimètres.
5. **Journal** : « Ajouter la note » enregistre la note avec la position actuelle (pratique sur un point connu). « Enregistrer… » → Téléchargements, puis envoyer le fichier à Claude.

## Pour les développeurs

- `noyau/` : calculs en Kotlin pur, testés avec `./gradlew :noyau:test`.
- `app/` : l'appli Android.
- L'APK est fabriqué par GitHub Actions (`.github/workflows/apk.yml`).
