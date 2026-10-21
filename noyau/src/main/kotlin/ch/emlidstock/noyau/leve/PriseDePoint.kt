package ch.emlidstock.noyau.leve

import ch.emlidstock.noyau.coordonnees.PositionSuisse
import ch.emlidstock.noyau.nmea.EtatCompensation
import ch.emlidstock.noyau.nmea.PositionGga

/** Réponse à « peut-on prendre un point maintenant ? » : vrai, ou faux avec la raison à afficher. */
data class Autorisation(val autorise: Boolean, val raison: String)

/**
 * Règle de prise de point (cahier des charges + test terrain n° 3) : le point n'est accepté que si
 * - le récepteur a envoyé une position il y a moins de [delaiMaxMs] ;
 * - le statut RTK est « fixe » (GGA qualité 4) ;
 * - la compensation d'inclinaison est prête (ETC état 30, reçu il y a moins de [delaiMaxMs]).
 *   Sinon, le RX2 envoie la position de l'antenne et non celle du bout de canne.
 */
fun autoriserPriseDePoint(
    gga: PositionGga?,
    ageGgaMs: Long?,
    compensation: EtatCompensation?,
    ageEtcMs: Long?,
    delaiMaxMs: Long = 2_000,
): Autorisation = when {
    gga == null || ageGgaMs == null || ageGgaMs > delaiMaxMs ->
        Autorisation(false, "Pas de position récente du récepteur")
    !gga.estRtkFixe ->
        Autorisation(false, "RTK pas fixe")
    compensation == null || ageEtcMs == null || ageEtcMs > delaiMaxMs ->
        Autorisation(false, "État de l'inclinaison inconnu")
    !compensation.estActive ->
        Autorisation(false, "Inclinaison NON PRÊTE : bougez la canne")
    else -> Autorisation(true, "Prêt")
}

/**
 * Prépare le point suivant du stock dans ce mode, à partir de la dernière position reçue.
 * Le point n'est pas encore ajouté : l'appelant fait `stock.ajouterPoint(point)`.
 */
fun preparerPoint(
    stock: Stock,
    mode: ModeLeve,
    gga: PositionGga,
    position: PositionSuisse,
    compensation: EtatCompensation?,
    horodatageMs: Long,
): PointLeve {
    val (ligne, numero) = stock.prochainNumero(mode)
    return PointLeve(
        nom = nomDePoint(mode, ligne, numero),
        mode = mode,
        ligne = ligne,
        numero = numero,
        est = position.est,
        nord = position.nord,
        altitude = position.altitude,
        latitudeDeg = gga.latitudeDeg ?: 0.0,
        longitudeDeg = gga.longitudeDeg ?: 0.0,
        hauteurEllipsoidaleM = gga.hauteurEllipsoidaleM ?: 0.0,
        horodatageMs = horodatageMs,
        qualiteGga = gga.qualite ?: 0,
        nbSatellites = gga.nbSatellites,
        hdop = gga.hdop,
        ageCorrectionsS = gga.ageCorrectionsS,
        stationReference = gga.stationReference,
        inclinaisonDeg = compensation?.inclinaisonDeg,
    )
}
