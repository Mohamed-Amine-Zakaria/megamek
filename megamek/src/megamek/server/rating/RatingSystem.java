package megamek.server.rating;

public interface RatingSystem {
    double calculateRatingChange(double playerRating, double opponentRating, boolean isVictory);
}
