package megamek.server.gameManager;

import megamek.client.bot.princess.BehaviorSettings;
import megamek.common.*;
import megamek.common.actions.ArtilleryAttackAction;
import megamek.common.actions.EntityAction;
import megamek.common.enums.GamePhase;
import megamek.common.enums.WeaponSortOrder;
import megamek.common.force.Force;
import megamek.common.force.Forces;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.weapons.AttackHandler;
import megamek.common.weapons.WeaponHandler;
import megamek.server.ServerBoardHelper;
import megamek.server.ServerLobbyHelper;

import java.util.*;
import java.util.stream.Collectors;

public class PacketManager {
    /**
     * Creates a packet containing all entities visible to the player in a blind game
     * @param p
     * @param losCache
     * @param gameManager
     */
    protected Packet createFilteredEntitiesPacket(Player p,
                                                  Map<EntityTargetPair, LosEffects> losCache, GameManager gameManager) {
        return new Packet(PacketCommand.SENDING_ENTITIES,
                gameManager.filterEntities(p, gameManager.getGame().getEntitiesVector(), losCache));
    }

    /**
     * Creates a packet containing the map settings
     * @param gameManager
     */
    Packet createMapSettingsPacket(GameManager gameManager) {
        MapSettings mapSettings = gameManager.game.getMapSettings();
        return new Packet(PacketCommand.SENDING_MAP_SETTINGS, mapSettings);
    }

    /**
     * Creates a packet containing a Vector of Reports
     * @param p
     * @param gameManager
     */
    protected Packet createReportPacket(Player p, GameManager gameManager) {
        // When the final report is created, MM sends a null player to create the report. This will
        // handle that issue.
        return new Packet(PacketCommand.SENDING_REPORTS,
                (p == null) || !gameManager.doBlind() ? gameManager.vPhaseReport : gameManager.filterReportVector(gameManager.vPhaseReport, p));
    }

    /**
     * Creates a packet containing a single entity, for update
     * @param entityId
     * @param movePath
     * @param gameManager
     */
    protected Packet createEntityPacket(int entityId, Vector<UnitLocation> movePath, GameManager gameManager) {
        return new Packet(PacketCommand.ENTITY_UPDATE, entityId, gameManager.getGame().getEntity(entityId), movePath);
    }

    /**
     * Creates a packet containing all current entities
     * @param gameManager
     */
    protected Packet createEntitiesPacket(GameManager gameManager) {
        return new Packet(PacketCommand.SENDING_ENTITIES, gameManager.getGame().getEntitiesVector());
    }

    /**
     * Creates a packet containing all current and out-of-game entities
     * @param gameManager
     */
    public Packet createFullEntitiesPacket(GameManager gameManager) {
        return new Packet(PacketCommand.SENDING_ENTITIES, gameManager.getGame().getEntitiesVector(),
                gameManager.getGame().getOutOfGameEntitiesVector(), gameManager.getGame().getForces());
    }

    protected Packet createAddEntityPacket(int entityId, GameManager gameManager) {
        ArrayList<Integer> entityIds = new ArrayList<>(1);
        entityIds.add(entityId);
        return gameManager.packetManager.createAddEntityPacket(entityIds, new ArrayList<>(), gameManager);
    }

    /**
     * Creates a packet detailing the addition of an entity
     * @param entityIds
     * @param forceIds
     * @param gameManager
     */
    Packet createAddEntityPacket(List<Integer> entityIds, List<Integer> forceIds, GameManager gameManager) {
        final List<Entity> entities = entityIds.stream()
                .map(id -> gameManager.getGame().getEntity(id))
                .collect(Collectors.toList());
        final List<Force> forceList = forceIds.stream()
                .map(id -> gameManager.getGame().getForces().getForce(id))
                .collect(Collectors.toList());
        return new Packet(PacketCommand.ENTITY_ADD, entities, forceList);
    }

    /**
     * Creates a packet detailing the removal of an entity. Maintained for
     * backwards compatibility.
     *
     * @param entityId - the <code>int</code> ID of the entity being removed.
     * @param gameManager
     * @return A <code>Packet</code> to be sent to clients.
     */
    protected Packet createRemoveEntityPacket(int entityId, GameManager gameManager) {
        return gameManager.packetManager.createRemoveEntityPacket(entityId, IEntityRemovalConditions.REMOVE_SALVAGEABLE, gameManager);
    }

    /**
     * Creates a packet detailing the removal of an entity. Determines which force
     * is affected and adds it to the packet.
     *
     * @param entityId  - the <code>int</code> ID of the entity being removed.
     * @param condition - the <code>int</code> condition the unit was in. This value
     *                  must be one of constants in
     *                  <code>IEntityRemovalConditions</code>, or an
     *                  <code>IllegalArgumentException</code> will be thrown.
     * @param gameManager
     * @return A <code>Packet</code> to be sent to clients.
     */
    protected Packet createRemoveEntityPacket(int entityId, int condition, GameManager gameManager) {
        List<Integer> ids = new ArrayList<>(1);
        ids.add(entityId);
        Forces forces = gameManager.getGame().getForces().clone();
        return gameManager.packetManager.createRemoveEntityPacket(ids, forces.removeEntityFromForces(entityId), condition);
    }

    /**
     * Creates a packet containing all the round reports unfiltered
     * @param gameManager
     */
    protected Packet createAllReportsPacket(GameManager gameManager) {
        return new Packet(PacketCommand.SENDING_REPORTS_ALL, gameManager.getGame().getAllReports());
    }

    /**
     * Creates a packet containing all entities, including wrecks, visible to
     * the player in a blind game
     * @param p
     * @param losCache
     * @param gameManager
     */
    protected Packet createFilteredFullEntitiesPacket(Player p,
                                                      Map<EntityTargetPair, LosEffects> losCache, GameManager gameManager) {
        return new Packet(PacketCommand.SENDING_ENTITIES,
                gameManager.filterEntities(p, gameManager.getGame().getEntitiesVector(), losCache),
                gameManager.getGame().getOutOfGameEntitiesVector(), gameManager.getGame().getForces());
    }

    /**
     * Creates a packet detailing the removal of a list of entities.
     *
     * @param entityIds - the <code>int</code> ID of each entity being removed.
     * @param affectedForces - a list of forces that are affected by the removal and
     *                  must be updated
     * @param condition - the <code>int</code> condition the units were in. This value
     *                  must be one of constants in
     *                  <code>IEntityRemovalConditions</code>, or an
     *                  <code>IllegalArgumentException</code> will be thrown.
     * @return A <code>Packet</code> to be sent to clients.
     */
    protected Packet createRemoveEntityPacket(List<Integer> entityIds, List<Force> affectedForces, int condition) {
        if ((condition != IEntityRemovalConditions.REMOVE_UNKNOWN)
                && (condition != IEntityRemovalConditions.REMOVE_IN_RETREAT)
                && (condition != IEntityRemovalConditions.REMOVE_PUSHED)
                && (condition != IEntityRemovalConditions.REMOVE_SALVAGEABLE)
                && (condition != IEntityRemovalConditions.REMOVE_EJECTED)
                && (condition != IEntityRemovalConditions.REMOVE_CAPTURED)
                && (condition != IEntityRemovalConditions.REMOVE_DEVASTATED)
                && (condition != IEntityRemovalConditions.REMOVE_NEVER_JOINED)) {
            throw new IllegalArgumentException("Unknown unit condition: " + condition);
        }

        return new Packet(PacketCommand.ENTITY_REMOVE, entityIds, condition, affectedForces);
    }

    /**
     * Creates a packet containing a hex, and the coordinates it goes at.
     * @param coords
     * @param hex
     */
    protected Packet createHexChangePacket(Coords coords, Hex hex) {
        return new Packet(PacketCommand.CHANGE_HEX, coords, hex);
    }

    /**
     * Creates a packet indicating end of game, including detailed unit status
     * @param gameManager
     */
    protected Packet createEndOfGamePacket(GameManager gameManager) {
        return new Packet(PacketCommand.END_OF_GAME, gameManager.reportManager.getDetailedVictoryReport(gameManager),
                gameManager.getGame().getVictoryPlayerId(), gameManager.getGame().getVictoryTeam());
    }

    /**
     * Tell the clients to replace the given building hexes with rubble hexes.
     *
     * @param coords - a <code>Vector</code> of <code>Coords</code>s that has
     *               collapsed.
     * @return a <code>Packet</code> for the command.
     */
    protected Packet createCollapseBuildingPacket(Vector<Coords> coords) {
        return new Packet(PacketCommand.BLDG_COLLAPSE, coords);
    }

    /**
     * Tell the clients to update the CFs of the given buildings.
     *
     * @param buildings - a <code>Vector</code> of <code>Building</code>s that need to
     *                  be updated.
     * @return a <code>Packet</code> for the command.
     */
    protected Packet createUpdateBuildingPacket(Vector<Building> buildings) {
        return new Packet(PacketCommand.BLDG_UPDATE, buildings);
    }

    /**
     * Tell the clients to replace the given building with rubble hexes.
     *
     * @param coords - the <code>Coords</code> that has collapsed.
     * @param gameManager
     * @return a <code>Packet</code> for the command.
     */
    protected Packet createCollapseBuildingPacket(Coords coords, GameManager gameManager) {
        Vector<Coords> coordsV = new Vector<>();
        coordsV.addElement(coords);
        return createCollapseBuildingPacket(coordsV);
    }

    /**
     * Creates a packet containing the current turn index
     * @param playerId
     * @param gameManager
     */
    protected Packet createTurnIndexPacket(int playerId, GameManager gameManager) {
        return new Packet(PacketCommand.TURN, gameManager.getGame().getTurnIndex(), playerId);
    }

    /**
     * Creates a packet containing the planetary conditions
     * @param gameManager
     */
    Packet createPlanetaryConditionsPacket(GameManager gameManager) {
        return new Packet(PacketCommand.SENDING_PLANETARY_CONDITIONS, gameManager.getGame().getPlanetaryConditions());
    }

    Packet createMapSizesPacket(GameManager gameManager) {
        return new Packet(PacketCommand.SENDING_AVAILABLE_MAP_SIZES, gameManager.getBoardSizes());
    }

    /**
     * Creates a packet containing the game settings
     * @param gameManager
     */
    protected Packet createGameSettingsPacket(GameManager gameManager) {
        return new Packet(PacketCommand.SENDING_GAME_SETTINGS, gameManager.getGame().getOptions());
    }

    /**
     * Creates a packet containing the game board
     * @param gameManager
     */
    Packet createBoardPacket(GameManager gameManager) {
        return new Packet(PacketCommand.SENDING_BOARD, gameManager.getGame().getBoard());
    }

    /**
     * Creates a packet containing a Vector of special Reports which needs to be
     * sent during a phase that is not a report phase.
     * @param gameManager
     */
    public Packet createSpecialReportPacket(GameManager gameManager) {
        return new Packet(PacketCommand.SENDING_REPORTS_SPECIAL, gameManager.vPhaseReport.clone());
    }

    /**
     * Creates a packet containing a Vector of Reports that represent a Tactical
     * Genius re-roll request which needs to update a current phase's report.
     * @param p
     * @param gameManager
     */
    protected Packet createTacticalGeniusReportPacket(Player p, GameManager gameManager) {
        return new Packet(PacketCommand.SENDING_REPORTS_TACTICAL_GENIUS,
                (p == null) || !gameManager.doBlind() ? gameManager.vPhaseReport.clone() : gameManager.filterReportVector(gameManager.vPhaseReport, p));
    }

    /**
     * Creates a packet containing all the round reports
     * @param p
     * @param gameManager
     */
    protected Packet createAllReportsPacket(Player p, GameManager gameManager) {
        return new Packet(PacketCommand.SENDING_REPORTS_ALL, gameManager.filterPastReports(gameManager.getGame().getAllReports(), p));
    }

    /**
     * Creates a packet containing the current turn vector
     * @param gameManager
     */
    protected Packet createTurnVectorPacket(GameManager gameManager) {
        return new Packet(PacketCommand.SENDING_TURNS, gameManager.getGame().getTurnVector());
    }

    /**
     * Creates a packet containing the player ready status
     * @param playerId
     * @param gameManager
     */
    protected Packet createPlayerDonePacket(int playerId, GameManager gameManager) {
        return new Packet(PacketCommand.PLAYER_READY, playerId, gameManager.game.getPlayer(playerId).isDone());
    }

    /**
     * Creates a packet containing a hex, and the coordinates it goes at.
     * @param coords
     * @param hex
     */
    protected Packet createHexesChangePacket(Set<Coords> coords, Set<Hex> hex) {
        return new Packet(PacketCommand.CHANGE_HEXES, coords, hex);
    }

    /**
     * Creates a packet containing a vector of mines.
     * @param coords
     * @param gameManager
     */
    protected Packet createMineChangePacket(Coords coords, GameManager gameManager) {
        return new Packet(PacketCommand.UPDATE_MINEFIELDS, gameManager.getGame().getMinefields(coords));
    }

    protected Packet createSpecialHexDisplayPacket(int toPlayer, GameManager gameManager) {
        Hashtable<Coords, Collection<SpecialHexDisplay>> shdTable = gameManager.game
                .getBoard().getSpecialHexDisplayTable();
        Hashtable<Coords, Collection<SpecialHexDisplay>> shdTable2 = new Hashtable<>();
        LinkedList<SpecialHexDisplay> tempList;
        Player player = gameManager.game.getPlayer(toPlayer);
        if (player != null) {
            for (Coords coord : shdTable.keySet()) {
                tempList = new LinkedList<>();
                for (SpecialHexDisplay shd : shdTable.get(coord)) {
                    if (!shd.isObscured(player)) {
                        tempList.add(0, shd);
                    }
                }
                if (!tempList.isEmpty()) {
                    shdTable2.put(coord, tempList);
                }
            }
        }
        return new Packet(PacketCommand.SENDING_SPECIAL_HEX_DISPLAY, shdTable2);
    }

    /**
     * Creates a packet containing off board artillery attacks
     * @param p
     * @param gameManager
     */
    Packet createArtilleryPacket(Player p, GameManager gameManager) {
        Vector<ArtilleryAttackAction> v = new Vector<>();
        int team = p.getTeam();
        for (Enumeration<AttackHandler> i = gameManager.game.getAttacks(); i.hasMoreElements(); ) {
            WeaponHandler wh = (WeaponHandler) i.nextElement();
            if (wh.waa instanceof ArtilleryAttackAction) {
                ArtilleryAttackAction aaa = (ArtilleryAttackAction) wh.waa;
                if ((aaa.getPlayerId() == p.getId())
                        || ((team != Player.TEAM_NONE)
                        && (team == gameManager.game.getPlayer(aaa.getPlayerId()).getTeam()))
                        || p.canIgnoreDoubleBlind()) {
                    v.addElement(aaa);
                }
            }
        }
        return new Packet(PacketCommand.SENDING_ARTILLERY_ATTACKS, v);
    }

    protected Packet createIlluminatedHexesPacket(GameManager gameManager) {
        return new Packet(PacketCommand.SENDING_ILLUM_HEXES, gameManager.getGame().getIlluminatedPositions());
    }

    /**
     * Creates a packet containing flares
     * @param gameManager
     */
    protected Packet createFlarePacket(GameManager gameManager) {
        return new Packet(PacketCommand.SENDING_FLARES, gameManager.getGame().getFlares());
    }

    /**
     * Creates a packet for an attack
     * @param ea
     * @param charge
     */
    protected Packet createAttackPacket(EntityAction ea, int charge) {
        Vector<EntityAction> vector = new Vector<>(1);
        vector.addElement(ea);
        return new Packet(PacketCommand.ENTITY_ATTACK, vector, charge);
    }

    /**
     * Creates a packet for an attack
     * @param vector
     * @param charges
     */
    protected Packet createAttackPacket(List<?> vector, int charges) {
        return new Packet(PacketCommand.ENTITY_ATTACK, vector, charges);
    }

    public void packetHandler(int connId, Packet packet, GameManager gameManager){
        final Player player = gameManager.game.getPlayer(connId);
        switch (packet.getCommand()) {
            case PLAYER_READY:
                gameManager.communicationManager.receivePlayerDone(packet, connId, gameManager);
                gameManager.communicationManager.send(createPlayerDonePacket(connId, gameManager));
                gameManager.gameStateManager.checkReady(gameManager);
                break;
            case PRINCESS_SETTINGS:
                if (player != null) {
                    if (gameManager.game.getBotSettings() == null) {
                        gameManager.game.setBotSettings(new HashMap<>());
                    }

                    gameManager.game.getBotSettings().put(player.getName(), (BehaviorSettings) packet.getObject(0));
                }
                break;
            case REROLL_INITIATIVE:
                gameManager.communicationManager.receiveInitiativeRerollRequest(packet, connId, gameManager);
                break;
            case FORWARD_INITIATIVE:
                gameManager.communicationManager.receiveForwardIni(connId, gameManager);
                break;
            case BLDG_EXPLODE:
                Building.DemolitionCharge charge = (Building.DemolitionCharge) packet.getData()[0];
                if (charge.playerId == connId) {
                    if (!gameManager.explodingCharges.contains(charge)) {
                        gameManager.explodingCharges.add(charge);
                        Player p = gameManager.game.getPlayer(connId);
                        gameManager.communicationManager.sendServerChat(p.getName() + " has touched off explosives "
                                + "(handled in end phase)!");
                    }
                }
                break;
            case ENTITY_MOVE:
                gameManager.entityActionManager.receiveMovement(packet, connId, gameManager);
                break;
            case ENTITY_DEPLOY:
                gameManager.entityActionManager.receiveDeployment(packet, connId, gameManager);
                break;
            case ENTITY_DEPLOY_UNLOAD:
                gameManager.entityActionManager.receiveDeploymentUnload(packet, connId, gameManager);
                break;
            case DEPLOY_MINEFIELDS:
                gameManager.communicationManager.receiveDeployMinefields(packet, connId, gameManager);
                break;
            case ENTITY_ATTACK:
                gameManager.entityActionManager.receiveAttack(packet, connId, gameManager);
                break;
            case ENTITY_PREPHASE:
                gameManager.communicationManager.receivePrephase(packet, connId, gameManager);
                break;
            case ENTITY_GTA_HEX_SELECT:
                gameManager.packetManager.receiveGroundToAirHexSelectPacket(packet, connId, gameManager);
                break;
            case ENTITY_ADD:
                gameManager.communicationManager.receiveEntityAdd(packet, connId, gameManager);
                gameManager.resetPlayersDone();
                break;
            case ENTITY_UPDATE:
                gameManager.communicationManager.receiveEntityUpdate(packet, connId, gameManager);
                gameManager.resetPlayersDone();
                break;
            case ENTITY_MULTIUPDATE:
                gameManager.communicationManager.receiveEntitiesUpdate(packet, connId, gameManager);
                gameManager.resetPlayersDone();
                break;
            case ENTITY_ASSIGN:
                ServerLobbyHelper.receiveEntitiesAssign(packet, connId, gameManager.getGame(), gameManager);
                gameManager.resetPlayersDone();
                break;
            case FORCE_UPDATE:
                ServerLobbyHelper.receiveForceUpdate(packet, connId, gameManager.getGame(), gameManager);
                gameManager.resetPlayersDone();
                break;
            case FORCE_ADD:
                ServerLobbyHelper.receiveForceAdd(packet, connId, gameManager.getGame(), gameManager);
                gameManager.resetPlayersDone();
                break;
            case FORCE_DELETE:
                gameManager.communicationManager.receiveForcesDelete(packet, connId, gameManager);
                gameManager.resetPlayersDone();
                break;
            case FORCE_PARENT:
                ServerLobbyHelper.receiveForceParent(packet, connId, gameManager.getGame(), gameManager);
                gameManager.resetPlayersDone();
                break;
            case FORCE_ADD_ENTITY:
                ServerLobbyHelper.receiveAddEntititesToForce(packet, connId, gameManager.getGame(), gameManager);
                gameManager.resetPlayersDone();
                break;
            case FORCE_ASSIGN_FULL:
                ServerLobbyHelper.receiveForceAssignFull(packet, connId, gameManager.getGame(), gameManager);
                gameManager.resetPlayersDone();
                break;
            case ENTITY_LOAD:
                gameManager.communicationManager.receiveEntityLoad(packet, connId, gameManager);
                gameManager.resetPlayersDone();
                break;
            case ENTITY_MODECHANGE:
                gameManager.communicationManager.receiveEntityModeChange(packet, connId, gameManager);
                break;
            case ENTITY_SENSORCHANGE:
                gameManager.communicationManager.receiveEntitySensorChange(packet, connId, gameManager);
                break;
            case ENTITY_SINKSCHANGE:
                gameManager.communicationManager.receiveEntitySinksChange(packet, connId, gameManager);
                break;
            case ENTITY_ACTIVATE_HIDDEN:
                gameManager.communicationManager.receiveEntityActivateHidden(packet, connId, gameManager);
                break;
            case ENTITY_NOVA_NETWORK_CHANGE:
                gameManager.communicationManager.receiveEntityNovaNetworkModeChange(packet, connId, gameManager);
                break;
            case ENTITY_MOUNTED_FACING_CHANGE:
                gameManager.communicationManager.receiveEntityMountedFacingChange(packet, connId, gameManager);
                break;
            case ENTITY_CALLEDSHOTCHANGE:
                gameManager.communicationManager.receiveEntityCalledShotChange(packet, connId, gameManager);
                break;
            case ENTITY_SYSTEMMODECHANGE:
                gameManager.communicationManager.receiveEntitySystemModeChange(packet, connId, gameManager);
                break;
            case ENTITY_AMMOCHANGE:
                gameManager.communicationManager.receiveEntityAmmoChange(packet, connId, gameManager);
                break;
            case ENTITY_REMOVE:
                gameManager.communicationManager.receiveEntityDelete(packet, connId, gameManager);
                gameManager.resetPlayersDone();
                break;
            case ENTITY_WORDER_UPDATE:
                Object[] data = packet.getData();
                Entity ent = gameManager.game.getEntity((Integer) data[0]);
                if (ent != null) {
                    WeaponSortOrder order = (WeaponSortOrder) data[1];
                    ent.setWeaponSortOrder(order);
                    // Used by the client but is set in setWeaponSortOrder
                    ent.setWeapOrderChanged(false);
                    if (order.isCustom()) {
                        // Unchecked cause of limitations in Java when casting to a collection
                        @SuppressWarnings(value = "unchecked")
                        Map<Integer, Integer> customWeaponOrder = (Map<Integer, Integer>) data[2];
                        ent.setCustomWeaponOrder(customWeaponOrder);
                    }
                }
                break;
            case SENDING_GAME_SETTINGS:
                if (gameManager.communicationManager.receiveGameOptions(packet, connId, gameManager)) {
                    gameManager.resetPlayersDone();
                    gameManager.communicationManager.send(createGameSettingsPacket(gameManager));
                    gameManager.communicationManager.receiveGameOptionsAux(packet, connId, gameManager);
                }
                break;
            case SENDING_MAP_SETTINGS:
                if (gameManager.game.getPhase().isBefore(GamePhase.DEPLOYMENT)) {
                    MapSettings newSettings = (MapSettings) packet.getObject(0);
                    if (!gameManager.game.getMapSettings().equalMapGenParameters(newSettings)) {
                        gameManager.communicationManager.sendServerChat(player + " changed map settings");
                    }
                    MapSettings mapSettings = newSettings;
                    mapSettings.setBoardsAvailableVector(ServerBoardHelper.scanForBoards(mapSettings));
                    mapSettings.removeUnavailable();
                    mapSettings.setNullBoards(GameManager.DEFAULT_BOARD);
                    gameManager.game.setMapSettings(mapSettings);
                    gameManager.resetPlayersDone();
                    gameManager.communicationManager.send(gameManager.communicationManager.packetManager.createMapSettingsPacket(gameManager));
                }
                break;
            case SENDING_MAP_DIMENSIONS:
                if (gameManager.game.getPhase().isBefore(GamePhase.DEPLOYMENT)) {
                    MapSettings newSettings = (MapSettings) packet.getObject(0);
                    if (!gameManager.game.getMapSettings().equalMapGenParameters(newSettings)) {
                        gameManager.communicationManager.sendServerChat(player + " changed map dimensions");
                    }
                    MapSettings mapSettings = newSettings;
                    mapSettings.setBoardsAvailableVector(ServerBoardHelper.scanForBoards(mapSettings));
                    mapSettings.removeUnavailable();
                    mapSettings.setNullBoards(GameManager.DEFAULT_BOARD);
                    gameManager.game.setMapSettings(mapSettings);
                    gameManager.resetPlayersDone();
                    gameManager.communicationManager.send(gameManager.communicationManager.packetManager.createMapSettingsPacket(gameManager));
                }
                break;
            case SENDING_PLANETARY_CONDITIONS:
                if (gameManager.game.getPhase().isBefore(GamePhase.DEPLOYMENT)) {
                    PlanetaryConditions conditions = (PlanetaryConditions) packet.getObject(0);
                    gameManager.communicationManager.sendServerChat(player + " changed planetary conditions");
                    gameManager.game.setPlanetaryConditions(conditions);
                    gameManager.resetPlayersDone();
                    gameManager.communicationManager.send(createPlanetaryConditionsPacket(gameManager));
                }
                break;
            case UNLOAD_STRANDED:
                gameManager.communicationManager.receiveUnloadStranded(packet, connId, gameManager);
                break;
            case SET_ARTILLERY_AUTOHIT_HEXES:
                gameManager.communicationManager.receiveArtyAutoHitHexes(packet, connId, gameManager);
                break;
            case CUSTOM_INITIATIVE:
                gameManager.communicationManager.receiveCustomInit(packet, connId, gameManager);
                gameManager.resetPlayersDone();
                break;
            case SQUADRON_ADD:
                gameManager.communicationManager.receiveSquadronAdd(packet, connId, gameManager);
                gameManager.resetPlayersDone();
                break;
            case RESET_ROUND_DEPLOYMENT:
                gameManager.game.setupRoundDeployment();
                break;
            case SPECIAL_HEX_DISPLAY_DELETE:
                gameManager.game.getBoard().removeSpecialHexDisplay((Coords) packet.getObject(0),
                        (SpecialHexDisplay) packet.getObject(1));
                gameManager.communicationManager.sendSpecialHexDisplayPackets(gameManager);
                break;
            case SPECIAL_HEX_DISPLAY_APPEND:
                gameManager.game.getBoard().addSpecialHexDisplay((Coords) packet.getObject(0),
                        (SpecialHexDisplay) packet.getObject(1));
                gameManager.communicationManager.sendSpecialHexDisplayPackets(gameManager);
                break;
            case PLAYER_TEAM_CHANGE:
                ServerLobbyHelper.receiveLobbyTeamChange(packet, connId, gameManager.getGame(), gameManager);
                break;
        }
    }

    /**
     * Client has sent an update indicating that a ground unit is firing at
     * an airborne unit and is overriding the default select for the position
     * in the flight path.
     * @param packet the packet to be processed
     * @param connId the id for connection that received the packet.
     * @param gameManager
     */
    protected void receiveGroundToAirHexSelectPacket(Packet packet, int connId, GameManager gameManager) {
        Integer targetId = (Integer) packet.getObject(0);
        Integer attackerId = (Integer) packet.getObject(1);
        Coords pos = (Coords) packet.getObject(2);
        gameManager.game.getEntity(targetId).setPlayerPickedPassThrough(attackerId, pos);
    }

    /**
     * Sends out a notification message indicating that the current turn is an
     * error and should be skipped.
     *
     * @param skip - the <code>Player</code> who is to be skipped. This value
     *             must not be <code>null</code>.
     * @param gameManager
     */
    protected void sendTurnErrorSkipMessage(Player skip, GameManager gameManager) {
        String message = "Player '" + skip.getName() +
                "' has no units to move.  You should skip his/her/your current turn with the /skip command. " +
                "You may want to report this error at https://github.com/MegaMek/megamek/issues";
        gameManager.communicationManager.sendServerChat(message);
    }
}
