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

- Position : flux **NMEA Bluetooth** lu directement sur le RX2 (GGA : position du bout de canne, compensée, et statut RTK ; ETC : état de la compensation). Emlid Flow reste ouvert seulement pour envoyer les corrections swipos au RX2. (Le cahier des charges prévoyait la mock location : abandonnée après le test terrain, voir § 4.)
- V1 = **mode stock** seulement ; l'architecture doit pouvoir accueillir d'autres modes plus tard (sans les coder).
- Hors V1 : autres modes de levé, mode hors ligne, moyenne de plusieurs secondes par point.

## 3. Étapes de construction (dans l'ordre)

1. **Prototype de test terrain** : affiche position, statut RTK et message ETC ; sert à vérifier les 5 points ci-dessous. ← **fait (version 0.1), testé le 20.10.2026**
2. Réception de la position et conversion en MN95 avec altitudes RAN95 (voir § 5). ← **fait (version 0.2), en attente du test terrain**
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

### Résultats du test terrain n° 1 (20.10.2026, `exemples/test-rx2_2026-10-20_08h20.txt`)

Téléphone Crosscall Core-T4, Android 10. RX2 « RX2-953A9 », corrections swipos.

| Point | Résultat |
| --- | --- |
| Mock location | **Pas active** : toutes les positions venaient du GPS du téléphone (`fictive=false`, précision 3 à 20 m). La doc Emlid du RX2 parle de la mock location via l'appli « Lefebure NTRIP Client », pas via Emlid Flow : à confirmer. |
| NMEA Bluetooth direct | **Marche**, en même temps qu'Emlid Flow (le RTK fixe a été gardé pendant la connexion). Phrases : GGA (5 Hz), ETC (5 Hz), RMC, GST, VTG, ZDA, GSA, GSV, EBP. |
| Statut RTK | **Lisible** dans GGA : qualité 1 (autonome) puis 4 (fixe), ID station 1927 puis 1935. |
| Compensation | Dans le **GGA**, avec ETC à l'état 30 (compensation), inclinaison 5° à 17° : position stable à ± 3 cm en plan et ± 2 cm en hauteur → le GGA semble déjà donner la position du bout de canne. |
| Hauteur de canne dans ETC | **Absente** (aucun champ constant). |
| Altitude | GGA : altitude 419.06 m + séparation 47.234 m (constante, modèle du récepteur) = hauteur ellipsoïdale 466.29 m. |

Message `$GNETC` observé (ordre des champs selon la doc Emlid « NMEA specification », à confirmer) : 1 heure UTC, 2 état (10, 20, 30 ; 30 = compensation, seuls les états 30 remplissent les champs suivants), 3 code de notification (00 ou 20), 4 cap (°), 5 direction de l'inclinaison (°), 6 valeur de l'inclinaison (°), 7 et 8 coefficients de précision axes N et E (toujours égaux dans le test), 9 non identifié (varie avec l'inclinaison).

Bug du prototype 0.1 : le point 1 affichait « OUI » avec les positions du GPS du téléphone ; il faudrait exiger `fictive=true`.

### Décisions après le test (20.10.2026)

- **Position : lecture directe du NMEA Bluetooth du RX2** (plan B du cahier des charges). Pas de mock location. Emlid Flow reste ouvert seulement pour envoyer les corrections swipos au RX2. Statut RTK et position du bout de canne lus dans le GGA, hauteur ellipsoïdale = altitude GGA + séparation.
- **Hauteur de canne** : saisie une fois dans l'appli, affichée comme **rappel seulement** (le RX2 l'applique déjà ; elle n'entre dans aucun calcul).
- Firmware du RX2 : 2.11 ou plus récent (corrige l'écart GGA / RMC avec la compensation).
- **À reconfirmer** : la compensation dans le GGA. L'utilisateur ne sait plus si la pointe de la canne restait immobile pendant le test d'inclinaison ; refaire un test court (pointe sur un point fixe, canne inclinée dans plusieurs directions).

## 5. Coordonnées et altitudes - méthode validée (20.10.2026)

Réponses de l'utilisateur :
- Corrections RTK : **swipos** (point de montage `MSM_GISGEO_LV95LHN95`).
- Altitudes : **RAN95 (LHN95) seulement**, comme ses projets Emlid Flow (« CH1903+ / LV95 + LHN95 height »). Il avait d'abord dit NF02 ; il a choisi RAN95 après avoir vu que son projet Emlid était en LHN95. Pas de NF02 dans l'appli pour l'instant.

Méthode (validée par l'utilisateur, code dans `noyau/.../coordonnees/`) :
- **Est, Nord MN95** : position GNSS ETRS89 / CHTRS95 (GRS80) → X, Y, Z → translations CHTRS95 → CH1903+ (−674.374, −15.056, −405.346 m) → latitude, longitude sur Bessel → projection suisse (cylindrique conforme à axe oblique, centre Berne, 2 600 000 / 1 200 000). Pas de FINELTRA (sert seulement entre MN03 et MN95).
- **Altitude RAN95** = hauteur ellipsoïdale (GGA altitude + séparation) − hauteur du géoïde **CHGeo2004** (grille swisstopo, licence CC0, pas de 30″, interpolation bilinéaire). Ne jamais utiliser l'altitude GGA du RX2 : son géoïde (47.234 m) diffère de CHGeo2004 (50.129 m) de près de 3 m à Gland.
- **Contrôle** : deux points d'Emlid Flow (`exemples/test_bureau_pix4d.csv`, point 1 et base swipos) retrouvés à ≤ 2 mm en plan et ≤ 0.3 mm en altitude (test `CoordonneesTest`).
- Pour info : à Gland, NF02 − RAN95 ≈ +2,3 cm (grille swisstopo « CHGeo2004 + HTRANS », non intégrée).
- Reste à faire si possible : contrôle sur un point fixe officiel, et comparaison avec le service REFRAME de swisstopo (domaines `geo.admin.ch` / `swisstopo.admin.ch` bloqués dans le cloud Claude Code pour l'instant).

## 6. Choix techniques

- Kotlin, Android natif, **sans bibliothèque** pour l'instant (écrans construits en code, `Activity` standard). minSdk 26 (Android 8), targetSdk 35.
- Deux modules Gradle :
  - `noyau/` : calculs en Kotlin pur (lecture NMEA, conversion MN95 / RAN95 avec la grille `src/main/resources/geoide/chgeo2004_lhn95.grille`, plus tard volumes), **testés automatiquement** (`./gradlew :noyau:test`).
  - `app/` : l'appli Android (écran `EcranPosition`, Bluetooth SPP).
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
