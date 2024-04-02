package megamek.server.rating;

public class PlayerRating {
    private double rating;

    public PlayerRating(double initialRating) {
        this.rating = initialRating;
    }

    public double getRating() {
        return rating;
    }

    // Seuils pour ajuster le ratingChange basé sur la performance au sein du match
    final double VICTORY_THRESHOLD = 80; // Considéré comme une grande victoire
    final double DEFEAT_THRESHOLD = 20; // Considéré comme une défaite écrasante

    public void updateRating(double matchScore, double opponentRating, boolean isVictory) {
        double expectedScore = 1 / (1.0 + Math.pow(10, (opponentRating - rating) / 400));
        double actualScore = isVictory ? 1 : 0;

        double scoreModifier = 1.0; // Modificateur par défaut

        // Ajuster le modificateur de score basé sur le matchScore pour les victoires
        if (isVictory) {
            if (matchScore >= VICTORY_THRESHOLD) {
                scoreModifier = 1.2; // Augmente le ratingChange de 20% pour une grande victoire
            }
        } else {
            // Ajuster le modificateur de score pour les défaites basées sur le matchScore
            if (matchScore <= DEFEAT_THRESHOLD) {
                scoreModifier = 0.8; // Réduit le ratingChange de 20% pour une défaite écrasante
            }
        }

        double ratingChange = EloRatingSystem.K * (actualScore - expectedScore) * scoreModifier;
        rating += ratingChange;
    }

}

