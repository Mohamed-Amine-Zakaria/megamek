package megamek.refactoring;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

import megamek.server.rating.EloRatingSystem;

public class EloRatingSystemTest {
    /**
     * vérifie que le changement de rating est correctement calculé pour une victoire.
     */
    @Test
    public void testCalculateRatingChangeVictory() {
        double playerRating = 1500.0;
        double opponentRating = 1600.0;
        boolean isVictory = true;
        double expectedRatingChange = 5.93;

        EloRatingSystem eloRatingSystem = new EloRatingSystem();
        double ratingChange = eloRatingSystem.calculateRatingChange(playerRating, opponentRating, isVictory);

        assertEquals(expectedRatingChange, ratingChange, 0.01, "Le changement de rating devrait être calculé correctement pour une victoire");
    }
}
