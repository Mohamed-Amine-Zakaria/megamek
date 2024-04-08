package megamek.refactoring;

import megamek.common.Player;
import megamek.server.gameManager.RatingManager;
import megamek.server.victory.VictoryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class RatingManagerTest {

    private RatingManager ratingManager;
    private Player winner;
    private Player loser;
    private VictoryResult victoryResult;

    @BeforeEach
    void setUp() {
        ratingManager = new RatingManager();
        winner = mock(Player.class, RETURNS_DEEP_STUBS);
        loser = mock(Player.class, RETURNS_DEEP_STUBS);
        victoryResult = mock(VictoryResult.class);

        when(winner.getRating().getRating()).thenReturn(1500.0);
        when(loser.getRating().getRating()).thenReturn(1500.0);
    }

    @Test
    void testProcessVictoryResults() {
        when(victoryResult.getPlayerScore(winner.hashCode())).thenReturn(100.0);
        when(victoryResult.getPlayerScore(loser.hashCode())).thenReturn(50.0);

        ratingManager.processVictoryResults(winner, loser, victoryResult);

        // Verifiez que updateRating a été appelé avec les bons paramètres.
        // Remarque : Vous devrez ajuster cette partie pour qu'elle corresponde à l'implémentation réelle des méthodes de mise à jour du rating.
        verify(winner.getRating(), times(1)).updateRating(anyDouble(), eq(1500.0), eq(true));
        verify(loser.getRating(), times(1)).updateRating(anyDouble(), eq(1500.0), eq(false));
    }
}
