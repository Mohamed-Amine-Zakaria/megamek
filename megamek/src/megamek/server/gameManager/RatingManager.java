package megamek.server.gameManager;

import megamek.common.Player;
import megamek.common.Report;
import megamek.server.victory.VictoryResult;

import java.util.List;

public class RatingManager {
    public void proocessVictoryResults(Player winner, Player loser, VictoryResult victoryResult, GameManager gameManager) {
        double winnerRatingChange = gameManager.ratingSystem.calculateRatingChange(
                winner.getRating().getRating(),
                loser.getRating().getRating(),
                true
        );
        double loserRatingChange = gameManager.ratingSystem.calculateRatingChange(
                loser.getRating().getRating(),
                winner.getRating().getRating(),
                false
        );

        winner.getRating().updateRating(victoryResult.getPlayerScore(winner.hashCode()), loser.getRating().getRating(), true);
        loser.getRating().updateRating(victoryResult.getPlayerScore(loser.hashCode()), winner.getRating().getRating(), false);
    }

    void updatePlayersRating(GameManager gameManager) {
        VictoryResult victoryResult = gameManager.game.getVictoryResult();

        if (victoryResult != null) {
            List<Report> reports = victoryResult.processVictory(gameManager.game);
            int winningPlayerId = victoryResult.getWinningPlayer();
            if (winningPlayerId != Player.PLAYER_NONE) {
                for (int playerId : victoryResult.getPlayers()) {
                    if (playerId != winningPlayerId) {
                        Player winner = gameManager.game.getPlayer(winningPlayerId);
                        Player loser = gameManager.game.getPlayer(playerId);
                        proocessVictoryResults(winner, loser, victoryResult, gameManager);
                        break;
                    }
                }
            }
        }
    }
}
