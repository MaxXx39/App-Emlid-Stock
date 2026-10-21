# Emlid Stock

Appli Android de levé de stocks en RTK avec un GNSS Emlid Reach RX2.

**Version actuelle : 0.5 - volume** (étapes 3 à 5 du cahier des charges) : chantiers, stocks, trois modes de levé (bas de talus B1…, haut de talus H1-1…, points caractéristiques C1…), prise de point en **MN95 / altitude RAN95 (LHN95)**, refusée tant que le RTK n'est pas fixe ou que l'inclinaison n'est pas prête. Carte avec photo aérienne swisstopo, calcul du volume (même méthode que l'appli topo, contrôlée sur Mensura), export des points en TXT.

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
2. Dans **Emlid Flow** : Récepteurs → Intégration à des logiciels externes → activer **« Envoyer la position compensée »** (indispensable : sinon le RX2 envoie la position de l'antenne).
3. Ouvrir Emlid Stock et accepter l'autorisation « appareils à proximité » (Bluetooth).
4. « Modifier la hauteur de canne » : taper la hauteur réglée dans Emlid Flow (rappel seulement).

## Lever un stock

1. Lancer **Emlid Flow**, se connecter au RX2 avec swipos, attendre le RTK fixe. Laisser Emlid Flow ouvert (il envoie les corrections au RX2).
2. Dans Emlid Stock : « Choisir le récepteur » → le RX2 (les fois suivantes : « Reconnecter »).
3. En haut, deux bandeaux : **RTK FIXE** et **Inclinaison PRÊTE** doivent être verts. Si l'inclinaison n'est pas prête, bougez un peu la canne.
4. « Nouveau chantier » (une fois), puis « + Nouveau stock » : le nom proposé (date chantier numéro) peut être modifié.
5. Choisir le mode : **Bas de talus** (B1, B2…), **Haut de talus** (H1-1, H1-2… ; « Nouvelle ligne » passe à H2-1), **Point caractéristique** (C1, C2…).
6. **PRENDRE LE POINT** : le point est pris à l'instant du clic. Le bouton est bloqué tant que le RTK n'est pas fixe ou que l'inclinaison n'est pas prête.
7. « Annuler le dernier point » efface le dernier point. Toucher un point dans la liste **ou sur la carte** : changer son rôle (B, H, C), le renommer, modifier E/N/H à la main ou le supprimer.
8. **Carte** : déplacer au doigt, zoomer à deux doigts (ou double toucher), boutons + / −, « Tout voir », « Ma position ». « Calques » : photo aérienne, cadastre, noms, altitudes. La photo aérienne demande une connexion internet.
9. **Haut de talus** : chaque ligne peut être fermée (dernier point relié au premier) ou ouverte avec le bouton sous « Nouvelle ligne ».
10. **Volume** : « Calculer le volume » quand le levé est fini → remblai, déblai, surface, nombre de triangles ; les triangles s'affichent sur la carte. Le contour = points B dans l'ordre, la base = les points B, les lignes H = lignes de rupture.
11. **Export** : « Exporter les points (TXT) » → fichier nom / Est / Nord / altitude séparés par des tabulations, à ouvrir dans Mensura.
12. Un stock se reprend plus tard : il suffit de le toucher dans la liste ; la numérotation continue.

Les levés sont enregistrés sur le téléphone à chaque point. (Export et synchronisation : plus tard.)

## Diagnostic

Accueil → « Diagnostic et journal » : position détaillée, test d'inclinaison, journal avec notes, à enregistrer et envoyer à Claude en cas de problème.

## Pour les développeurs

- `noyau/` : calculs en Kotlin pur, testés avec `./gradlew :noyau:test`.
- `app/` : l'appli Android.
- L'APK est fabriqué par GitHub Actions (`.github/workflows/apk.yml`).
