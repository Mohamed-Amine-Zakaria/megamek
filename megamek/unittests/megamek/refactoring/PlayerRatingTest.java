package megamek.refactoring;

import megamek.common.Player;
import megamek.server.rating.PlayerRating;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PlayerRatingTest {
    /**
     * vérifie que le rating initial est correctement retourné par la méthode
     */
    @Test
    public void testGetRating() {
        double initialRating = 1500.0;
        PlayerRating playerRating = new PlayerRating(initialRating);
        assertEquals(initialRating, playerRating.getRating(), "Le rating initial devrait être retourné");
    }

    /**
     * teste le cas où le joueur remporte le match avec un score élevé, vérifiant que le rating est correctement mis à jour
     */
    @Test
    public void testUpdateRatingVictory() {
        double initialRating = 1500.0;
        double opponentRating = 1400.0;
        boolean isVictory = true;
        double matchScore = 85.0;
        double expectedRatingChange = 8.0;

        PlayerRating playerRating = new PlayerRating(initialRating);
        playerRating.updateRating(matchScore, opponentRating, isVictory);

        assertEquals(initialRating + expectedRatingChange, playerRating.getRating(), 0.01, "Le rating devrait augmenter après une victoire avec un score élevé");
    }

    /**
     *  teste le cas où le joueur perd le match avec un score bas, vérifiant que le rating est correctement mis à jour.
     */
    @Test
    public void testUpdateRatingDefeat() {
        double initialRating = 1500.0;
        double opponentRating = 1400.0;
        boolean isVictory = false;
        double matchScore = 15.0; // Score bas, devrait diminuer le rating.
        double expectedRatingChange = -5.0; // Changement de rating attendu pour une défaite.

        PlayerRating playerRating = new PlayerRating(initialRating);
        playerRating.updateRating(matchScore, opponentRating, isVictory);

        assertEquals(initialRating + expectedRatingChange, playerRating.getRating(), "Le rating devrait diminuer après une défaite avec un score bas");
    }

    /**
     * este le cas où le rating de l'adversaire est le même, vérifiant que le rating du joueur ne change pas.
     */
    @Test
    public void testUpdateRatingNoChange() {
        double initialRating = 1500.0;
        double opponentRating = 1500.0; // Même rating, aucun changement attendu.
        boolean isVictory = false;
        double matchScore = 50.0; // Score moyen.
        double expectedRatingChange = 0.0; // Aucun changement attendu.

        PlayerRating playerRating = new PlayerRating(initialRating);
        playerRating.updateRating(matchScore, opponentRating, isVictory);

        assertEquals(initialRating + expectedRatingChange, playerRating.getRating(), "Le rating ne devrait pas changer si le rating de l'adversaire est le même");
    }
}
