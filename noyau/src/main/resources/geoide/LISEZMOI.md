# Grille du géoïde CHGeo2004 (altitudes LHN95 / RAN95)

- Source : swisstopo, modèle du géoïde CHGeo2004, fichier `chgeo2004_ETRS.agr`
  (diffusé aussi par le projet PROJ : `ch_swisstopo_chgeo2004_ETRS89_LHN95.tif`).
- Licence : Creative Commons CC0 1.0, © swisstopo.
- Contenu : hauteur du géoïde au-dessus de l'ellipsoïde GRS80 (ETRS89), en mètres,
  au pas de 30″ (1/120°), de 5.85° à 10.50° E et de 45.75° à 47.85° N.
  Altitude LHN95 = hauteur ellipsoïdale − valeur de la grille.

Format du fichier `chgeo2004_lhn95.grille` (octets « big-endian ») :
nombre de lignes (int), nombre de colonnes (int), longitude ouest (double),
latitude nord (double), pas en degrés (double), puis les valeurs (float),
ligne par ligne du nord au sud, chaque ligne d'ouest en est.
