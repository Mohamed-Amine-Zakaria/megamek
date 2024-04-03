package megamek.server.gameManager;

import megamek.common.Player;
import megamek.common.Report;
import megamek.server.victory.VictoryResult;

import java.util.List;

/**
 * La classe RatingManager est responsable de la gestion des ratings des joueurs après chaque match dans MegaMek.
 * Elle utilise les résultats des victoires pour ajuster les ratings des joueurs en fonction de leurs performances.
 */
public class RatingManager {

    /**
     * Traite les résultats d'une victoire en calculant et en appliquant les changements de rating pour le gagnant et le perdant.
     * Utilise le système de rating configuré dans le GameManager pour déterminer l'ajustement du rating.
     *
     * @param winner Le joueur qui a gagné le match.
     * @param loser Le joueur qui a perdu le match.
     * @param victoryResult Les résultats du match, contenant les scores et d'autres informations pertinentes.
     */
    public void processVictoryResults(Player winner, Player loser, VictoryResult victoryResult) {

        // Applique les changements de rating en fonction des scores obtenus dans le match.
        winner.getRating().updateRating(victoryResult.getPlayerScore(winner.hashCode()) , loser.getRating().getRating(), true);
        loser.getRating().updateRating(victoryResult.getPlayerScore(loser.hashCode()), winner.getRating().getRating(), false);
    }

    /**
     * Met à jour les ratings des joueurs après la conclusion d'un match.
     * Cette méthode est appelée par le GameManager une fois que le match est terminé et que le résultat est connu.
     *
     * @param gameManager Le gestionnaire de jeu, fournissant l'accès aux résultats du match et aux informations des joueurs.
     */
    void updatePlayersRating(GameManager gameManager) {
        // Obtient les résultats de victoire du match.
        VictoryResult victoryResult = gameManager.game.getVictoryResult();

        // Si les résultats de victoire sont disponibles, traite les résultats pour chaque joueur.
        if (victoryResult != null) {
            List<Report> reports = victoryResult.processVictory(gameManager.game); // Potentiellement utilisé pour générer des rapports de match.
            int winningPlayerId = victoryResult.getWinningPlayer();

            // S'assure qu'il y a un joueur gagnant identifié avant de procéder.
            if (winningPlayerId != Player.PLAYER_NONE) {
                // Itère sur tous les joueurs du match pour trouver le perdant et traiter les résultats de la victoire.
                for (int playerId : victoryResult.getPlayers()) {
                    if (playerId != winningPlayerId) {
                        Player winner = gameManager.game.getPlayer(winningPlayerId);
                        Player loser = gameManager.game.getPlayer(playerId);
                        processVictoryResults(winner, loser, victoryResult);
                        break; // Arrête après avoir trouvé le premier perdant, car cela suppose un match 1 contre 1.
                    }
                }
            }
        }
    }
}
