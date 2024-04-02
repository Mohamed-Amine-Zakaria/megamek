package megamek.server.rating;

public class EloRatingSystem implements RatingSystem {
    public static final double K = 32;

    @Override
    public double calculateRatingChange(double playerRating, double opponentRating, boolean isVictory) {
        double expectedScore = 1 / (1.0 + Math.pow(10, (opponentRating - playerRating) / 400));
        double actualScore = isVictory ? 1 : 0;
        return K * (actualScore - expectedScore);
    }
}
