package megamek.server.rating;

import megamek.common.Player;
import megamek.server.victory.VictoryResult;

class GameEngine {
    private RatingSystem ratingSystem = new EloRatingSystem();

    public void updatePlayerRatings(Player winner, Player loser, VictoryResult victoryResult) {
        double winnerRatingChange = ratingSystem.calculateRatingChange(
                winner.getRating().getRating(),
                loser.getRating().getRating(),
                true
        );
        double loserRatingChange = ratingSystem.calculateRatingChange(
                loser.getRating().getRating(),
                winner.getRating().getRating(),
                false
        );

        winner.getRating().updateRating(victoryResult.getPlayerScore(winner.hashCode()), loser.getRating().getRating(), true);
        loser.getRating().updateRating(victoryResult.getPlayerScore(loser.hashCode()), winner.getRating().getRating(), false);
    }
}