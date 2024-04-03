package megamek.server.rating;

/**
 * La classe PlayerRating gère le rating d'un joueur dans le système de rating.
 * Elle permet d'initialiser, récupérer et mettre à jour le rating d'un joueur en fonction des résultats des matchs.
 */
public class PlayerRating {
    // Le rating actuel du joueur.
    private double rating;

    /**
     * Constructeur qui initialise le rating du joueur avec une valeur spécifiée.
     *
     * @param initialRating Le rating initial du joueur.
     */
    public PlayerRating(double initialRating) {
        this.rating = initialRating;
    }

    /**
     * Renvoie le rating actuel du joueur.
     *
     * @return Le rating du joueur.
     */
    public double getRating() {
        return rating;
    }

    // Seuils utilisés pour ajuster le changement de rating en fonction de la performance au sein du match.
    final double VICTORY_THRESHOLD = 80; // Score considéré comme une grande victoire.
    final double DEFEAT_THRESHOLD = 20; // Score considéré comme une défaite écrasante.

    /**
     * Met à jour le rating du joueur en fonction du score du match, du rating de l'adversaire et du résultat du match (victoire ou défaite).
     *
     * @param matchScore Le score du joueur dans le match.
     * @param opponentRating Le rating de l'adversaire.
     * @param isVictory Booléen indiquant si le joueur a gagné le match.
     */
    public void updateRating(double matchScore, double opponentRating, boolean isVictory) {
        // Calcul du score attendu basé sur le rating du joueur et celui de l'adversaire.
        double expectedScore = 1 / (1.0 + Math.pow(10, (opponentRating - rating) / 400));
        // Score réel basé sur le résultat du match : 1 pour une victoire, 0 pour une défaite.
        double actualScore = isVictory ? 1 : 0;

        // Modificateur de score par défaut.
        double scoreModifier = 1.0;

        // Ajustement du modificateur de score en fonction du score du match et du résultat.
        if (isVictory && matchScore >= VICTORY_THRESHOLD) {
            // Augmente le changement de rating pour une grande victoire.
            scoreModifier = 1.2;
        } else if (!isVictory && matchScore <= DEFEAT_THRESHOLD) {
            // Réduit le changement de rating pour une défaite écrasante.
            scoreModifier = 0.8;
        }

        // Calcul du changement de rating en utilisant le coefficient K de l'EloRatingSystem, le score réel et attendu, et le modificateur de score.
        double ratingChange = EloRatingSystem.K * (actualScore - expectedScore) * scoreModifier;
        // Mise à jour du rating du joueur.
        rating += ratingChange;
    }
}
