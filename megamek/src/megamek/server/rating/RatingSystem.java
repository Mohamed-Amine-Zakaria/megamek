package megamek.server.rating;

/**
 * L'interface RatingSystem définit un contrat pour les systèmes de calcul de rating dans le jeu MegaMek.
 * Elle spécifie une méthode pour calculer le changement de rating d'un joueur basé sur le résultat d'une partie.
 */
public interface RatingSystem {
    /**
     * Calcule le changement de rating pour un joueur donné en fonction de son rating actuel,
     * du rating de son adversaire et du résultat de la partie.
     *
     * @param playerRating Le rating du joueur avant la partie.
     * @param opponentRating Le rating de l'adversaire du joueur avant la partie.
     * @param isVictory Un booléen indiquant si le joueur a gagné (true) ou perdu (false) la partie.
     * @return Le changement de rating du joueur en tant que valeur double. Ce nombre peut être positif
     *         (indiquant une augmentation du rating) ou négatif (indiquant une diminution).
     */
    double calculateRatingChange(double playerRating, double opponentRating, boolean isVictory);
}
