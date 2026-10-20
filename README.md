# Emlid Stock

Appli Android de levé de stocks en RTK avec un GNSS Emlid Reach RX2.

**Version actuelle : 0.1 - prototype de test terrain** (étape 1 du cahier des charges). Elle ne lève pas encore de stocks : elle affiche ce que le téléphone reçoit du RX2 pour vérifier les 5 points du cahier des charges.

## Installer l'appli sur le téléphone

1. Sur le téléphone, ouvrez le navigateur (Chrome) et allez sur la page du projet : `https://github.com/MaxXx39/App-Emlid-Stock/releases`
2. Sous « Dernière version », touchez **EmlidStock.apk** : le fichier se télécharge.
3. Ouvrez le fichier téléchargé (notification « Téléchargement terminé », ou appli « Fichiers »).
4. La première fois, Android bloque : touchez **Paramètres**, puis activez **Autoriser cette source** (pour Chrome), puis revenez et touchez **Installer**.
5. Si Google Play Protect affiche un avertissement (appli inconnue), touchez **Plus de détails** puis **Installer quand même**. C'est normal pour une appli personnelle qui ne vient pas du Play Store.
6. L'appli « Emlid Stock » apparaît avec les autres applis.

Pour une mise à jour : refaire les étapes 1 à 3, puis **Mettre à jour**. Les réglages sont gardés.

## Préparer le test (une seule fois)

1. **Appairer le RX2** avec le téléphone : Réglages Android → Bluetooth → choisir le RX2.
2. **Emlid Flow, position fictive** : dans Emlid Flow, activer la sortie de position vers Android (« mock location » / position fictive), avec la compensation d'inclinaison et la hauteur de canne réglées.
3. **Options pour développeurs** d'Android (si ce n'est pas déjà fait) : Réglages → À propos du téléphone → toucher 7 fois « Numéro de build ». Puis Réglages → Options pour développeurs → **Sélectionner une application de position fictive** → Emlid Flow.
4. Si possible, dans Emlid Flow, activer la **sortie NMEA en Bluetooth** du RX2 (c'est justement un des points à tester).

## Faire le test terrain

1. Lancer Emlid Flow, se connecter au RX2, attendre le RTK fixe.
2. Lancer Emlid Stock, accepter les permissions (position précise, appareils à proximité).
3. Partie 1 : la position d'Emlid Flow doit s'afficher, avec « Position fictive : OUI ».
4. Partie 2 : « Choisir le récepteur » → le RX2. Les phrases NMEA (GGA, ETC…) doivent défiler.
5. Partie 3 :
   - **Test d'inclinaison** : pointe de canne posée sur un point fixe, canne droite, toucher « Fixer le départ ». Incliner la canne (environ 15° à 30°) dans plusieurs directions sans bouger la pointe. Si la compensation marche, l'écart « mock » reste de quelques centimètres.
   - **Hauteur de canne** : chercher dans les champs du message ETC une valeur égale à la hauteur de canne saisie dans Emlid Flow. Changer la hauteur dans Emlid Flow et regarder quel champ change.
   - Noter ce que vous faites avec « Ajouter la note » (ex. « canne inclinée 30° vers le nord »).
6. Partie 4 : **Enregistrer…** → choisir un endroit (Téléchargements ou Google Drive). Envoyez ce fichier à Claude pour l'analyse.

## Pour les développeurs

- `noyau/` : calculs en Kotlin pur, testés avec `./gradlew :noyau:test`.
- `app/` : l'appli Android.
- L'APK est fabriqué par GitHub Actions (`.github/workflows/apk.yml`).
