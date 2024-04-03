package megamek.server.rating;

/**
 * La classe EloRatingSystem implémente l'interface RatingSystem pour fournir une implémentation spécifique
 * du calcul de changement de rating basé sur le système de rating Elo.
 * Ce système est utilisé pour évaluer la compétence relative des joueurs en fonction des résultats des matchs.
 */
public class EloRatingSystem implements RatingSystem {
    // Le facteur K détermine la volatilité du changement de rating. Une valeur standard dans le système Elo.
    public static final double K = 32;

    /**
     * Calcule le changement de rating d'un joueur en utilisant le système de rating Elo.
     *
     * @param playerRating Le rating actuel du joueur avant le match.
     * @param opponentRating Le rating de l'adversaire du joueur avant le match.
     * @param isVictory Un booléen indiquant si le joueur a gagné (true) ou perdu (false) le match.
     * @return Le changement de rating calculé, qui peut être positif (pour une victoire) ou négatif (pour une défaite).
     */
    @Override
    public double calculateRatingChange(double playerRating, double opponentRating, boolean isVictory) {
        // Calcul du score attendu, basé sur le rating actuel du joueur et celui de l'adversaire.
        double expectedScore = 1 / (1.0 + Math.pow(10, (opponentRating - playerRating) / 400));
        // Le score réel, défini comme 1 en cas de victoire et 0 en cas de défaite.
        double actualScore = isVictory ? 1 : 0;
        // Calcul du changement de rating en multipliant la différence entre le score réel et le score attendu par le facteur K.
        return K * (actualScore - expectedScore);
    }
}
