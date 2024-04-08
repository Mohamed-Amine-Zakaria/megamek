package megamek.refactoring;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

import megamek.server.rating.EloRatingSystem;

public class EloRatingSystemTest {
    // An instance of the EloRatingSystem to be used in all test cases.
    private final EloRatingSystem eloRatingSystem = new EloRatingSystem();

    /**
     * Tests that a victory against an equally rated opponent results in a positive rating change.
     * This test ensures that winning a game increases a player's rating, adhering to the basic principle of the Elo system.
     */
    @Test
    public void testCalculateRatingChangeForVictory() {
        double playerRating = 1500;
        double opponentRating = 1500;
        boolean isVictory = true;

        double ratingChange = eloRatingSystem.calculateRatingChange(playerRating, opponentRating, isVictory);
        Assertions.assertTrue(ratingChange > 0, "Rating change should be positive for a victory.");
    }

    /**
     * Tests that losing to a higher-rated opponent results in a negative rating change,
     * but the magnitude of the change is less severe due to the opponent's higher rating.
     * This reflects the Elo system's feature where losses against stronger opponents penalize less.
     */
    @Test
    public void testCalculateRatingChangeForDefeatAgainstHigherRatedOpponent() {
        double playerRating = 1500;
        double opponentRating = 1600;
        boolean isVictory = false;

        double ratingChange = eloRatingSystem.calculateRatingChange(playerRating, opponentRating, isVictory);
        Assertions.assertTrue(ratingChange < 0, "Rating change should be negative for a defeat.");
        Assertions.assertTrue(Math.abs(ratingChange) < EloRatingSystem.K / 2, "Rating loss should be less severe against a higher-rated opponent.");
    }

    /**
     * Tests that winning against a significantly higher-rated opponent results in a substantial positive rating change.
     * This scenarioa checks the system's incentive for players to challenge and win against stronger opponents.
     */
    @Test
    public void testCalculateRatingChangeForVictoryAgainstHigherRatedOpponent() {
        double playerRating = 1500;
        double opponentRating = 1700;
        boolean isVictory = true;

        double ratingChange = eloRatingSystem.calculateRatingChange(playerRating, opponentRating, isVictory);
        Assertions.assertTrue(ratingChange > 0 && ratingChange > EloRatingSystem.K / 2, "Rating gain should be significant for a victory against a higher-rated opponent.");
    }

    /**
     * Tests the calculation when both players have equal ratings and the match results in a victory.
     * It checks for a specific rating change, ensuring the method adheres to the expected formula for equally rated players.
     */
    @Test
    public void testCalculateRatingChangeWithEqualRatings() {
        double playerRating = 1500;
        double opponentRating = 1500;
        boolean isVictory = true;

        double ratingChange = eloRatingSystem.calculateRatingChange(playerRating, opponentRating, isVictory);
        // Expect a moderate rating change for victory against an equally rated opponent
        Assertions.assertEquals(16, ratingChange, "Rating change should be exactly K/2 for a victory against an equally rated opponent.");
    }
}
