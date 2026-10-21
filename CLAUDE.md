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

- Position : flux **NMEA Bluetooth** lu directement sur le RX2, avec le réglage Emlid Flow « Envoyer la position compensée » activé (GGA : position du bout de canne, compensée, **seulement quand ETC est à l'état 30**, et statut RTK ; ETC : état de la compensation). Emlid Flow reste ouvert seulement pour envoyer les corrections swipos au RX2. (Le cahier des charges prévoyait la mock location : abandonnée après le test terrain, voir § 4.)
- V1 = **mode stock** seulement ; l'architecture doit pouvoir accueillir d'autres modes plus tard (sans les coder).
- Hors V1 : autres modes de levé, mode hors ligne, moyenne de plusieurs secondes par point.

## 3. Étapes de construction (dans l'ordre)

1. **Prototype de test terrain** : affiche position, statut RTK et message ETC ; sert à vérifier les 5 points ci-dessous. ← **fait (version 0.1), testé le 20.10.2026**
2. Réception de la position et conversion en MN95 avec altitudes RAN95 (voir § 5). ← **fait (version 0.2), validé sur le terrain le 21.10.2026**
3. Chantiers, stocks, trois modes de levé (bas de talus B1…, haut de talus H1-1…, points caractéristiques C1…), bouton de prise de point bloqué hors fix **ou inclinaison pas prête**, annulation. ← **fait (version 0.3), validé sur le terrain le 21.10.2026** (2 stocks levés ; RTK fixe / flottant et blocage par l'inclinaison OK ; le 2e stock levé avec Emlid Flow « fermé » sans aucune différence : Emlid Flow continue probablement en arrière-plan à envoyer les corrections, à confirmer)
   - Décisions (21.10.2026) : points caractéristiques **C1, C2…** ; nom de stock proposé **« date chantier numéro »** (ex. « 2026-10-21 Gravière 1 », modifiable) ; numéro suivant = plus grand existant + 1 dans le groupe ; « Nouvelle ligne » ne crée pas de ligne vide.
   - Bandeaux toujours visibles : « RTK » et « Inclinaison PRÊTE / NON PRÊTE » (position grisée « antenne, pas le bout de canne » si pas prête).
   - Suppression d'un point : pour l'instant en touchant le point dans la liste (sur la carte à l'étape 4).
   - Données : fichier `leves.json` dans le dossier privé de l'appli (classe `Depot`, écriture via fichier temporaire). Pas encore d'export ni de synchro (étape 7).
4. Carte 2D en cours de levé avec fond swisstopo (SWISSIMAGE). ← **fait (version 0.4), testé le 21.10.2026** (cadrage, photo, zoom, calques, suppression au toucher : OK)
   - Inspirée du plan de l'appli topo V1 (demande de l'utilisateur) : tuiles WMTS swisstopo directement en MN95 (`noyau/.../carte/Fond.kt`, repris de `app-topo/src/plan/fond.js` avec ses tests), bouton « Calques » (photo aérienne, cadastre, noms, altitudes ; mémorisés), zoom + / −, « Tout voir », « Ma position », barre d'échelle, mention « © swisstopo ».
   - Dessin : contour B1 → B2 → … fermé (ambre), lignes de haut de talus (rouge), points C (bleu clair), noms en blanc bordé de noir, position actuelle (bleu ; grise si l'inclinaison n'est pas prête).
   - Gestes : déplacement au doigt, zoom à deux doigts ou double toucher, toucher un point → suppression (avec confirmation).
   - Tuiles en mémoire (40 Mo) + cache internet sur le téléphone (50 Mo, `HttpResponseCache`) ; pas de vrai mode hors ligne (hors V1).
5. Triangulation et calcul du volume (même méthode que l'appli topo : bas de talus = contour et surface de référence ; bas et hauts de talus = lignes de rupture), comparé à Mensura (tolérance 0,5 %). ← **fait (version 0.5)**, test terrain en attente (comparaison Mensura sur un stock levé avec l'appli)
   - Méthode = V1 « mode A » (validée sur Mensura) : dessus = Delaunay contraint de tous les points (contour B fermé + chaque ligne H), triangles dans le contour ; base = Delaunay contraint des points B ; superposition exacte des triangles (`noyau/.../volume/`).
   - La V1 utilise les bibliothèques JS Delaunator + Constrainautor ; ici **triangulation contrainte écrite en Kotlin, sans bibliothèque** (Bowyer-Watson + bascule des arêtes croisées + Lawson). Résultat identique à la V1 sur le levé du 20/05/2026 (`exemples/stock_export_2026-05-20.txt`, copié de app-topo) : 1636.1971 / 648.9788 / 372.2010 m³, mêmes surfaces et mêmes nombres de triangles (77/20, 45/18, 50/21) ; Mensura 1636.197 / 648.979 / 372.201 (test `VolumeReferenceTest`).
   - Décisions (21.10.2026) : lignes H **ouvertes ou fermées au choix** (bouton par ligne) ; volume calculé à la demande (« Calculer le volume » = validation du levé) et effacé dès qu'un point change ; triangles affichés sur la carte (calque « Triangles »).
   - Aussi dans la 0.5 (demandes de l'utilisateur) : **nouveau logo** (canne GNSS plantée dans un tas vert, fond blanc, pour ne plus ressembler à la V1) ; **toucher un point** → changer le rôle (prochain nom libre du groupe), renommer, modifier E/N/H à la main (point marqué « modifié »), supprimer ; **export TXT** des points d'un stock (tabulations, nom/E/N/H, comme stock_export.txt) en avance sur l'étape 7, pour contrôler dans Mensura.
6. Vues de contrôle : 2D, 3D, courbes de niveau, tranches de couleur. ← **fait (version 0.6), en attente du test terrain**
   - Écran « Contrôle » (`VueControle`), ouvert par « Vues de contrôle (2D / 3D) » après le calcul du volume ; onglets 2D / 3D.
   - 2D = la carte, avec de nouveaux calques repris de la V1 : couleurs selon l'altitude, courbes de niveau (équidistance auto, maîtresse 1 sur 5), remblai rouge / déblai jaune par paliers (auto), légende. Calculs portés en Kotlin avec les tests de la V1 (`noyau/.../controle/Courbes.kt`, `Couleurs.kt`, test `ControleTest`).
   - 3D = `Vue3D`, dessinée sans bibliothèque (projection `Projection3D` + tri du plus loin au plus proche + éclairage) : glisser pour tourner / incliner, pincer pour zoomer, exagération ×1 / ×2 / ×5, vue de dessus.
   - Le calcul de volume garde maintenant les triangles d'écart dessus − base (`trianglesEcart`), comme la V1.
7. Exports PDF, DXF, Excel et synchronisation Google Drive.

### Après le cahier des charges : tout centraliser dans la V2 (décision du 21.10.2026)

L'utilisateur veut **toutes** les fonctions de l'appli topo V1 (dépôt `app-topo`) dans la V2. La V1 reste l'outil de **bureau** (la V2 Android ne tourne pas sur Windows), avec une **passerelle** : la V2 exporte un fichier projet que la V1 ouvre (`.topo.json`), et inversement. Ordre choisi : finir d'abord les étapes 6 et 7 du cahier des charges, puis :

8. Importer des points (CSV Emlid, TXT) comme un stock, avec calcul du volume.
9. Base = autre surface (mode B) : surfaces importées (LandXML Mensura, DXF faces 3D) ou levées (terrain naturel) ; surfaces libres (sans contour, triangles supprimés au doigt).
10. Comparer deux levés (même stock à deux dates) : volume ajouté / enlevé, sur tout ou dans un contour.
11. Photos du stock avec légende, imprimées dans le PDF.
12. Annuler / Rétablir et bulles d'aide activables.

L'étape 6 reprend aussi les vues de la V1 (couleurs selon l'altitude, courbes de niveau, remblai rouge / déblai jaune par paliers) ; l'étape 7 les exports de la V1 (PDF avec cartouche et calques, CSV, DXF R12, LandXML) en plus d'Excel et Google Drive, et la passerelle V1. Toujours reprendre les formules et les tests de la V1 (`app-topo/src/topo`, `app-topo/tests`) plutôt que de réinventer.

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
- ~~À reconfirmer : la compensation dans le GGA.~~ → **infirmé par le test n° 2**, voir ci-dessous.

### Résultats du test terrain n° 2 (20.10.2026, version 0.2, `exemples/journal-rx2_2026-10-20_10h08.txt`)

Sur le point 1 (`exemples/test_bureau_pix4d.csv` : 2509500.921 / 1141119.690 / 414.856), canne verticale :
- **Plan** : écart d'environ 1 cm → la conversion MN95 est confirmée en conditions réelles.
- **Hauteur** : la position GGA est **1.96 m plus haute** que le point (environ la hauteur de canne + décalage de l'antenne).
- **Inclinaison** : même avec ETC à l'état 30, la position GGA bouge comme la tête de canne (jusqu'à 85 cm pour 26°, hauteur qui baisse en cos). **Le GGA reçu en Bluetooth est donc la position de l'antenne, sans hauteur de canne ni compensation.** (Le test n° 1 avait été mal interprété : la canne ne bougeait sans doute pas.)
- **ETC** : le champ 5 est bien la direction de l'inclinaison (azimut vers lequel la tête de canne part) et le champ 6 l'angle. En calculant `bout = antenne − L × (sin i · sin d, sin i · cos d, cos i)` avec L = 1.962 m, on retrouve le point à 1 à 3 cm près quand la canne est immobile (plus d'écart pendant les mouvements rapides). Calcul fait à titre d'analyse, **pas validé** et pas codé. Hauteur de canne réglée dans Emlid Flow pendant ce test : 1.85 m ; décalage d'antenne du RX2 selon Emlid : 147.1 mm (L1).

### Résultats du test terrain n° 3 (21.10.2026, `exemples/journal-rx2_2026-10-21_14h04.txt`, `exemples/test_appli_2026-10-21.csv`)

Emlid Flow mis à jour ; réglage activé : **Récepteurs → Intégration à des logiciels externes → « Envoyer la position compensée »**. Point de contrôle : point 1 levé avec le RX2 dans Emlid Flow au même moment (2503979.890 / 1137570.674 / 459.605, hauteur d'antenne 1.995 m).

- **ETC à l'état 30** (compensation prête) : le GGA donne **le bout de canne compensé**. Canne inclinée de 12° à 28° sur le point : écart 0 à 5 cm en plan pendant les mouvements, quelques mm canne immobile, **hauteur à ± 6 mm** (ex. 15.7° : dE +0.2, dN −0.4 cm, dH 0.0). Les 3 « départs » notés : 459.602 / 459.606 / 459.607 m pour 459.605 m.
- **ETC à l'état 20** (Emlid Flow affiche « CI NON PRÊTE ») : le GGA redevient **la position de l'antenne** (environ 1.99 m plus haut = canne 1.85 + 0.147, et elle bouge avec l'inclinaison). Le passage d'un mode à l'autre est immédiat.
- **Règle à appliquer** : un point n'est valable que si RTK fixe **et** ETC état 30. Sinon, la position n'est pas celle du bout de canne.

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
  - `noyau/.../leve/` : modèle des chantiers, stocks et points, nommage, règle de prise de point (`autoriserPriseDePoint`).
  - `app/` : l'appli Android : une seule activité `EcranPrincipal` (bandeaux RTK / inclinaison) et les vues `VueAccueil`, `VueLeve` (avec la carte `VueCarte` / `CarteAvecBoutons`, tuiles par `ChargeurTuiles`), `VueControle` (2D / `Vue3D`), `VueDiagnostic` ; `EtatRecepteur` (données reçues), `Depot` (enregistrement), `RecepteurBluetooth` (SPP).
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
