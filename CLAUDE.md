# CLAUDE.md - Appli Android « Emlid Stock » (levé de stocks en RTK)

Ce fichier est lu par Claude Code au début de chaque session. Le cahier des charges complet est le PDF à la racine du dépôt (« Cahier des charges appli Android de levé de stocks en RTK.pdf ») ; ce fichier en reprend l'essentiel et note les décisions prises.

## 1. Qui est l'utilisateur et comment travailler avec lui

- Géomètre topo / projeteur dans une entreprise de gros œuvre, en Suisse. Il construit cette appli seul, pour son usage personnel.
- **Il est débutant en développement.** Explique simplement, sans jargon, par petites étapes. Si un terme technique est nécessaire, définis-le en une phrase.
- **Rien n'est installé sur son ordinateur.** Le code est écrit par Claude Code dans le cloud, gardé sur GitHub, et l'APK est fabriqué par GitHub Actions. L'utilisateur installe l'APK depuis le navigateur de son téléphone.
- Réponds toujours **en français**. L'interface de l'appli est aussi en français.
- **Pose-lui des questions avant de décider** d'un point important ou ambigu.
- **Ne devine jamais une formule ou une norme de topographie** (transformations de coordonnées, géoïde, altitudes). Si tu n'es pas sûr, dis-le et fais valider.
- Une seule étape à la fois. Après chaque étape : tests, explication de ce qui marche, comment l'essayer, puis attendre son accord.

## 2. But et périmètre

Appli Android **native en Kotlin** pour lever des stocks de matériaux avec un GNSS **Emlid Reach RX2** en RTK et calculer leur volume sur place. Distincte de l'appli topo PWA (dépôt `app-topo`), qui reste un projet à part.

- Position : via la **mock location** Android fournie par Emlid Flow (position compensée en inclinaison, bout de canne).
- Métadonnées : flux **NMEA Bluetooth** direct du RX2, message `$GNETC` (état de la compensation, peut-être la hauteur de canne).
- V1 = **mode stock** seulement ; l'architecture doit pouvoir accueillir d'autres modes plus tard (sans les coder).
- Hors V1 : autres modes de levé, mode hors ligne, moyenne de plusieurs secondes par point.

## 3. Étapes de construction (dans l'ordre)

1. **Prototype de test terrain** : affiche position, statut RTK et message ETC ; sert à vérifier les 5 points ci-dessous. ← **fait (version 0.1), en attente du test terrain**
2. Réception de la position et conversion en MN95 avec altitudes (voir § 5).
3. Chantiers, stocks, trois modes de levé (bas de talus B1…, haut de talus H1-1…, points caractéristiques C1… ou 1…), bouton de prise de point bloqué hors fix, annulation.
4. Carte 2D en cours de levé avec fond swisstopo (SWISSIMAGE).
5. Triangulation et calcul du volume (même méthode que l'appli topo : bas de talus = contour et surface de référence ; bas et hauts de talus = lignes de rupture), comparé à Mensura (tolérance 0,5 %).
6. Vues de contrôle : 2D, 3D, courbes de niveau, tranches de couleur.
7. Exports PDF, DXF, Excel et synchronisation Google Drive.

## 4. Points à vérifier sur le terrain (étape 1)

| Point | Plan B si ça ne marche pas |
| --- | --- |
| Mock location et flux NMEA Bluetooth en même temps | L'appli lit seule le NMEA et fournit la position |
| Hauteur de canne dans le message ETC | La lire autrement, ou la saisir une fois dans l'appli |
| Compensation active dans la position mock | Appliquer la compensation côté appli (messages ETC) |
| Altitude mock : ellipsoïdale ou liée à un géoïde ? | Lire la hauteur ellipsoïdale dans le NMEA (GGA) |
| Statut RTK « fix » lisible de façon fiable | Le lire dans le NMEA (GGA, qualité 4) |

Résultats du test terrain : **à compléter** quand l'utilisateur aura envoyé son journal (`exemples/test-rx2_….txt`).

## 5. Coordonnées et altitudes - décisions et points ouverts

Réponses de l'utilisateur (20.10.2026) :
- Corrections RTK : **swipos** (swisstopo).
- Altitudes voulues : **NF02** (altitudes usuelles, comme dans ses plans et dans Mensura), et non RAN95.

À valider avec l'utilisateur avant de coder l'étape 2 (ne pas deviner) :
- Le cahier des charges cite la grille FINELTRA / REFRAME pour WGS84 → MN95. À vérifier dans la documentation swisstopo : avec swipos, les positions sont en ETRS89/CHTRS95, et le passage vers MN95 se fait peut-être par les formules officielles (translation + projection) ; FINELTRA concerne plutôt MN03 ↔ MN95.
- Altitudes NF02 : hauteur ellipsoïdale → CHGeo2004 → altitude orthométrique RAN95 → transformation **HTRANS** vers NF02. Grilles officielles swisstopo à obtenir (licence et format à vérifier).
- Valider sur un point connu (point fixe) avant d'aller plus loin.

## 6. Choix techniques

- Kotlin, Android natif, **sans bibliothèque** pour l'instant (écrans construits en code, `Activity` standard). minSdk 26 (Android 8), targetSdk 35.
- Deux modules Gradle :
  - `noyau/` : calculs en Kotlin pur (lecture NMEA, vérifications, plus tard conversions et volumes), **testés automatiquement** (`./gradlew :noyau:test`).
  - `app/` : l'appli Android (écran, Bluetooth SPP, position).
- Fabrication par **GitHub Actions** (`.github/workflows/apk.yml`) : tests + APK à chaque envoi ; sur `main`, l'APK est publié dans la release « Dernière version » (tag `derniere-version`).
- Signature : clé fixe `app/cle-signature.jks` (mot de passe dans `app/build.gradle.kts`), pour que les mises à jour s'installent par-dessus. Clé d'usage personnel uniquement.
- Dans le cloud Claude Code, `dl.google.com` est bloqué : le SDK Android n'est pas disponible localement. On teste `noyau` localement (miroir Maven `maven-central.storage-download.googleapis.com` si Maven Central répond 429) et on peut vérifier la compilation du code Android contre `org.robolectric:android-all` ; la vraie fabrication de l'APK se fait dans GitHub Actions.
- Avant d'ajouter une bibliothèque : dire ce qu'elle fait, pourquoi, et demander l'accord.

## 7. Git et GitHub

- Un « commit » (sauvegarde dans l'historique) après chaque étape qui marche, message clair en français.
- Ne jamais demander de mot de passe ni de jeton dans le chat.

## 8. Règles de code

- Code et commentaires simples, en français. Noms clairs (`estPoint`, `nordPoint`, `altitude`…). Coordonnées dans l'ordre **Est, Nord, altitude**.
- Chaque calcul de topo : une fonction courte dans `noyau/`, avec un commentaire (ce qu'elle fait, unités) et un test.
- Nombres affichés avec 3 décimales pour les coordonnées et les volumes. Séparateur décimal : le point.
- Messages d'erreur compréhensibles, jamais de plantage silencieux.
- Ne pas modifier ni supprimer les fichiers du dossier `exemples/`.
