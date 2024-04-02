package megamek.server.gameManager;

import megamek.common.Entity;
import megamek.common.IEntityRemovalConditions;
import megamek.common.Player;
import megamek.common.force.Forces;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.server.ServerLobbyHelper;
import org.apache.logging.log4j.LogManager;

import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;

public class PlayerManager {


    public void processGameMasterRequest(GameManager gameManager) {
        if (gameManager.playerRequestingGameMaster != null) {
            gameManager.playerManager.setGameMaster(gameManager.playerRequestingGameMaster, true, gameManager);
            gameManager.playerRequestingGameMaster = null;
        }
    }

    public void setGameMaster(Player player, boolean gameMaster, GameManager gameManager) {
        player.setGameMaster(gameMaster);
        gameManager.transmitPlayerUpdate(player);
        gameManager.sendServerChat(player.getName() + " set GameMaster: " + player.getGameMaster());
    }

    void processTeamChangeRequest(GameManager gameManager) {
        if (gameManager.playerChangingTeam != null) {
            gameManager.playerChangingTeam.setTeam(gameManager.requestedTeam);
            gameManager.getGame().setupTeams();
            gameManager.transmitPlayerUpdate(gameManager.playerChangingTeam);
            String teamString = "Team " + gameManager.requestedTeam + "!";
            if (gameManager.requestedTeam == Player.TEAM_UNASSIGNED) {
                teamString = " unassigned!";
            } else if (gameManager.requestedTeam == Player.TEAM_NONE) {
                teamString = " lone wolf!";
            }
            gameManager.sendServerChat(gameManager.playerChangingTeam.getName() + " has changed teams to " + teamString);
            gameManager.playerChangingTeam = null;
        }
        gameManager.changePlayersTeam = false;
    }

    public void allowTeamChange(GameManager gameManager) {
        gameManager.changePlayersTeam = true;
    }

    /**
     * Checks each player to see if he has no entities, and if true, sets the
     * observer flag for that player. An exception is that there are no
     * observers during the lounge phase.
     * @param gameManager
     */
    public void checkForObservers(GameManager gameManager) {
        for (Enumeration<Player> e = gameManager.getGame().getPlayers(); e.hasMoreElements(); ) {
            Player p = e.nextElement();
            p.setObserver((!p.isGameMaster()) && (gameManager.getGame().getEntitiesOwnedBy(p) < 1) && !gameManager.getGame().getPhase().isLounge());
        }
    }

    protected void transferAllEnititiesOwnedBy(Player pFrom, Player pTo, GameManager gameManager) {
        for (Entity entity : gameManager.game.getEntitiesVector().stream().filter(e -> e.getOwner().equals(pFrom)).collect(Collectors.toList())) {
            entity.setOwner(pTo);
        }
        gameManager.game.getForces().correct();
        ServerLobbyHelper.correctLoading(gameManager.game);
        ServerLobbyHelper.correctC3Connections(gameManager.game);
        gameManager.send(gameManager.createFullEntitiesPacket());
    }

    void disconnectAPlayer(Player player, GameManager gameManager) {
        if (gameManager.getGame().getPhase().isLounge()) {
            List<Player> gms = gameManager.game.getPlayersList().stream().filter(p -> p.isGameMaster()).collect(Collectors.toList());

            if (gms.size() > 0) {
                transferAllEnititiesOwnedBy(player, gms.get(0), gameManager);
            } else {
                gameManager.removeAllEntitiesOwnedBy(player);
            }
        }

        // if a player has active entities, he becomes a ghost
        // except the VICTORY_PHASE when the disconnected
        // player is most likely the Bot disconnected after receiving
        // the COMMAND_END_OF_GAME command
        // see the Bug 1225949.
        // Ghost players (Bots mostly) are now removed during the
        // resetGame(), so we don't need to do it here.
        // This fixes Bug 3399000 without reintroducing 1225949
        if (gameManager.getGame().getPhase().isVictory() || gameManager.getGame().getPhase().isLounge() || player.isObserver()) {
            gameManager.getGame().removePlayer(player.getId());
            gameManager.send(new Packet(PacketCommand.PLAYER_REMOVE, player.getId()));
            // Prevent situation where all players but the disconnected one
            // are done, and the disconnecting player causes the game to start
            if (gameManager.getGame().getPhase().isLounge()) {
                gameManager.resetActivePlayersDone();
            }
        } else {
            player.setGhost(true);
            player.setDone(true);
            gameManager.transmitPlayerUpdate(player);
        }

        // make sure the game advances
        if (gameManager.getGame().getPhase().hasTurns() && (null != gameManager.getGame().getTurn())) {
            if (gameManager.getGame().getTurn().isValid(player.getId(), gameManager.getGame())) {
                gameManager.sendGhostSkipMessage(player);
            }
        } else {
            gameManager.checkReady();
        }

        // notify other players
        gameManager.sendServerChat(player.getName() + " disconnected.");

        // log it
        LogManager.getLogger().info("s: removed player " + player.getName());

        // Reset the game after Elvis has left the building.
        if (0 == gameManager.getGame().getNoOfPlayers()) {
            gameManager.resetGame();
        }
    }

    void removeEntities(Player player, GameManager gameManager) {
        int pid = player.getId();
        Forces forces = gameManager.game.getForces();
        // Disentangle everything!
        // remove other player's forces from player's forces
        forces.getAllForces().stream()
                .filter(f -> !f.isTopLevel())
                .filter(f -> f.getOwnerId() != pid)
                .filter(f -> forces.getForce(f.getParentId()).getOwnerId() == pid)
                .forEach(forces::promoteForce);

        // remove other player's units from player's forces
        gameManager.game.getEntitiesVector().stream()
                .filter(e -> e.getOwnerId() != pid)
                .filter(Entity::partOfForce)
                .filter(e -> forces.getForce(e.getForceId()).getOwnerId() == pid)
                .forEach(forces::removeEntityFromForces);

        // delete forces of player
        forces.deleteForces(forces.getAllForces().stream()
                .filter(f -> f.getOwnerId() == pid)
                .filter(f -> f.isTopLevel() || !forces.getOwner(f.getParentId()).equals(player))
                .collect(Collectors.toList()));

        Collection<Entity> delEntities = gameManager.game.getEntitiesVector().stream()
                .filter(e -> e.getOwner().equals(player))
                .collect(Collectors.toList());

        // remove entities of player from any forces, disembark and C3 disconnect them
        delEntities.forEach(forces::removeEntityFromForces);
        ServerLobbyHelper.lobbyUnload(gameManager.game, delEntities);
        ServerLobbyHelper.performC3Disconnect(gameManager.game, delEntities);

        // delete entities of player
        delEntities.forEach(e -> gameManager.game.removeEntity(e.getId(), IEntityRemovalConditions.REMOVE_NEVER_JOINED));

        // send full update
        gameManager.send(gameManager.createFullEntitiesPacket());
    }

    void changeTeam(int team, Player player, GameManager gameManager) {
        gameManager.requestedTeam = team;
        gameManager.playerChangingTeam = player;
        gameManager.changePlayersTeam = false;
    }
}
