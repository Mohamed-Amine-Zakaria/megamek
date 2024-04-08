package megamek.refactoring;

import megamek.common.Player;
import megamek.server.rating.PlayerRating;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PlayerRatingTest {
    /**
     * Tests that the PlayerRating constructor correctly initializes the player's rating.
     * Asserts that the initial rating set through the constructor is correctly returned by getRating().
     */
    @Test
    public void testConstructorInitializesCorrectRating() {
        PlayerRating playerRating = new PlayerRating(1500.0);
        Assertions.assertEquals(1500.0, playerRating.getRating(), "Constructor should initialize with the correct rating.");
    }

    /**
     * Tests the updateRating method for scenarios where the player wins.
     * First, simulates a significant victory with a match score greater than the victory threshold,
     * asserting that the player's rating increases.
     * Then, it tests a normal victory against a higher-rated opponent,
     * asserting that the player's rating further increases.
     */
    @Test
    public void testUpdateRatingWithVictory() {
        PlayerRating playerRating = new PlayerRating(1500.0);
        playerRating.updateRating(85, 1400, true); // Simulates a significant victory.
        Assertions.assertTrue(playerRating.getRating() > 1500.0, "Rating should increase after a significant victory.");

        double ratingAfterVictory = playerRating.getRating();

        playerRating.updateRating(50, 1600, true); // Simulates a normal victory against a higher-rated opponent.
        Assertions.assertTrue(playerRating.getRating() > ratingAfterVictory, "Rating should further increase after a victory against a higher-rated opponent.");
    }

    /**
     * Tests the updateRating method for scenarios where the player loses.
     * First, simulates an overwhelming defeat with a match score lower than the defeat threshold,
     * asserting that the player's rating decreases.
     * Then, it tests a normal defeat against a higher-rated opponent,
     * asserting that the player's rating decreases, but l-ess so than in the case of the overwhelming defeat.
     */
    @Test
    public void testUpdateRatingWithDefeat() {
        PlayerRating playerRating = new PlayerRating(1500.0);
        playerRating.updateRating(15, 1400, false); // Simulates an overwhelming defeat.
        Assertions.assertTrue(playerRating.getRating() < 1500.0, "Rating should decrease after an overwhelming defeat.");

        double ratingAfterDefeat = playerRating.getRating();

        playerRating.updateRating(50, 1600, false); // Simulates a normal defeat against a higher-rated opponent.
        Assertions.assertTrue(playerRating.getRating() < ratingAfterDefeat, "Rating should decrease after a defeat, but not as much as for an overwhelming defeat.");
    }
}
