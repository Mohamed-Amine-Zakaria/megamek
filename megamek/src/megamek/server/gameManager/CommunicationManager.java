package megamek.server.gameManager;

import megamek.common.*;
import megamek.common.actions.EntityAction;
import megamek.common.actions.UnloadStrandedAction;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.containers.PlayerIDandList;
import megamek.common.enums.GamePhase;
import megamek.common.force.Force;
import megamek.common.force.Forces;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.options.IBasicOption;
import megamek.common.options.IOption;
import megamek.common.options.OptionsConstants;
import megamek.common.util.EmailService;
import megamek.common.verifier.TestEntity;
import megamek.server.*;
import org.apache.logging.log4j.LogManager;

import java.util.*;
import java.util.stream.Collectors;

public class CommunicationManager {

    PacketManager packetManager = new PacketManager();

    public void send(Packet p) {
        Server.getServerInstance().send(p);
    }

    public void send(int connId, Packet p) {
        Server.getServerInstance().send(connId, p);
    }

    public void sendServerChat(String message) {
        Server.getServerInstance().sendServerChat(message);
    }

    public void sendServerChat(int connId, String message) {
        Server.getServerInstance().sendServerChat(connId, message);
    }

    public void sendChat(String origin, String message) {
        Server.getServerInstance().sendChat(origin, message);
    }

    public void sendChat(int connId, String origin, String message) {
        Server.getServerInstance().sendChat(connId, origin, message);
    }

    void sendInfo(int connId, GameManager gameManager) {
        send(connId, gameManager.packetManager.createGameSettingsPacket(gameManager));
        send(connId, gameManager.packetManager.createPlanetaryConditionsPacket(gameManager));

        Player player = gameManager.getGame().getPlayer(connId);
        if (null != player) {
            send(connId, new Packet(PacketCommand.SENDING_MINEFIELDS, player.getMinefields()));

            if (gameManager.getGame().getPhase().isLounge()) {
                send(connId, gameManager.communicationManager.packetManager.createMapSettingsPacket(gameManager));
                send(gameManager.packetManager.createMapSizesPacket(gameManager));
                // Send Entities *after* the Lounge Phase Change
                send(connId, new Packet(PacketCommand.PHASE_CHANGE, gameManager.getGame().getPhase()));
                if (gameManager.doBlind()) {
                    send(connId, gameManager.packetManager.createFilteredFullEntitiesPacket(player, null, gameManager));
                } else {
                    send(connId, gameManager.packetManager.createFullEntitiesPacket(gameManager));
                }
            } else {
                send(connId, new Packet(PacketCommand.ROUND_UPDATE, gameManager.getGame().getRoundCount()));
                send(connId, gameManager.packetManager.createBoardPacket(gameManager));
                send(connId, gameManager.packetManager.createAllReportsPacket(player, gameManager));

                // Send entities *before* other phase changes.
                if (gameManager.doBlind()) {
                    send(connId, gameManager.packetManager.createFilteredFullEntitiesPacket(player, null, gameManager));
                } else {
                    send(connId, gameManager.packetManager.createFullEntitiesPacket(gameManager));
                }

                gameManager.playerManager.setPlayerDone(player, gameManager.getGame().getEntitiesOwnedBy(player) <= 0, gameManager);
                send(connId, new Packet(PacketCommand.PHASE_CHANGE, gameManager.getGame().getPhase()));
            }

            // LOUNGE triggers a Game.reset() on the client, which wipes out
            // the PlanetaryCondition, so resend
            if (gameManager.game.getPhase().isLounge()) {
                send(connId, gameManager.packetManager.createPlanetaryConditionsPacket(gameManager));
            }

            if (gameManager.game.getPhase().isFiring() || gameManager.game.getPhase().isTargeting()
                    || gameManager.game.getPhase().isOffboard() || gameManager.game.getPhase().isPhysical()) {
                // can't go above, need board to have been sent
                send(connId, gameManager.packetManager.createAttackPacket(gameManager.getGame().getActionsVector(), 0));
                send(connId, gameManager.packetManager.createAttackPacket(gameManager.getGame().getChargesVector(), 1));
                send(connId, gameManager.packetManager.createAttackPacket(gameManager.getGame().getRamsVector(), 1));
                send(connId, gameManager.packetManager.createAttackPacket(gameManager.getGame().getTeleMissileAttacksVector(), 1));
            }

            if (gameManager.getGame().getPhase().hasTurns() && gameManager.getGame().hasMoreTurns()) {
                send(connId, gameManager.packetManager.createTurnVectorPacket(gameManager));
                send(connId, gameManager.packetManager.createTurnIndexPacket(connId, gameManager));
            } else if (!gameManager.getGame().getPhase().isLounge() && !gameManager.getGame().getPhase().isStartingScenario()) {
                gameManager.gameStateManager.endCurrentPhase(gameManager);
            }

            send(connId, gameManager.packetManager.createArtilleryPacket(player, gameManager));
            send(connId, gameManager.packetManager.createFlarePacket(gameManager));
            send(connId, gameManager.packetManager.createSpecialHexDisplayPacket(connId, gameManager));
            send(connId, new Packet(PacketCommand.PRINCESS_SETTINGS, gameManager.getGame().getBotSettings()));
        }
    }

    /**
     * Resend entities to the player called by SeeAll command
     * @param connId
     * @param gameManager
     */
    public void sendEntities(int connId, GameManager gameManager) {
        if (gameManager.doBlind()) {
            send(connId, gameManager.packetManager.createFilteredFullEntitiesPacket(gameManager.game.getPlayer(connId), null, gameManager));
        } else {
            send(connId, gameManager.packetManager.createEntitiesPacket(gameManager));
        }
    }

    protected void sendDominoEffectCFR(Entity e, GameManager gameManager) {
        send(e.getOwnerId(), new Packet(PacketCommand.CLIENT_FEEDBACK_REQUEST,
                PacketCommand.CFR_DOMINO_EFFECT, e.getId()));
    }

    protected void sendAMSAssignCFR(Entity e, Mounted ams, List<WeaponAttackAction> waas, GameManager gameManager) {
        send(e.getOwnerId(), new Packet(PacketCommand.CLIENT_FEEDBACK_REQUEST,
                PacketCommand.CFR_AMS_ASSIGN, e.getId(), e.getEquipmentNum(ams), waas));
    }

    protected void sendAPDSAssignCFR(Entity e, List<Integer> apdsDists, List<WeaponAttackAction> waas, GameManager gameManager) {
        send(e.getOwnerId(), new Packet(PacketCommand.CLIENT_FEEDBACK_REQUEST,
                PacketCommand.CFR_APDS_ASSIGN, e.getId(), apdsDists, waas));
    }

    protected void sendPointBlankShotCFR(Entity hidden, Entity target, GameManager gameManager) {
        // Send attacker/target IDs to PBS Client
        send(hidden.getOwnerId(), new Packet(PacketCommand.CLIENT_FEEDBACK_REQUEST,
                PacketCommand.CFR_HIDDEN_PBS, hidden.getId(), target.getId()));
    }

    protected void sendTeleguidedMissileCFR(int playerId, List<Integer> targetIds, List<Integer> toHitValues, GameManager gameManager) {
        // Send target id numbers and to-hit values to Client
        send(playerId, new Packet(PacketCommand.CLIENT_FEEDBACK_REQUEST,
                PacketCommand.CFR_TELEGUIDED_TARGET, targetIds, toHitValues));
    }

    protected void sendTAGTargetCFR(int playerId, List<Integer> targetIds, List<Integer> targetTypes, GameManager gameManager) {
        // Send target id numbers and type identifiers to Client
        send(playerId, new Packet(PacketCommand.CLIENT_FEEDBACK_REQUEST,
                PacketCommand.CFR_TAG_TARGET, targetIds, targetTypes));
    }

    public void sendSmokeCloudAdded(SmokeCloud cloud, GameManager gameManager) {
        send(new Packet(PacketCommand.ADD_SMOKE_CLOUD, cloud));
    }

    /**
     * Sends notification to clients that the specified hex has changed.
     * @param coords
     * @param gameManager
     */
    public void sendChangedHex(Coords coords, GameManager gameManager) {
        send(gameManager.packetManager.createHexChangePacket(coords, gameManager.game.getBoard().getHex(coords)));
    }

    /**
     * Send the round report to all connected clients.
     * @param tacticalGeniusReport
     * @param gameManager
     */
    protected void sendReport(boolean tacticalGeniusReport, GameManager gameManager) {
        EmailService mailer = Server.getServerInstance().getEmailService();
        if (mailer != null) {
            for (var player: mailer.getEmailablePlayers(gameManager.game)) {
                try {
                    var reports = gameManager.filterReportVector(gameManager.vPhaseReport, player);
                    var message = mailer.newReportMessage(gameManager.game, reports, player);
                    mailer.send(message);
                } catch (Exception ex) {
                    LogManager.getLogger().error("Error sending round report", ex);
                }
            }
        }

        for (Player p : gameManager.game.getPlayersVector()) {
            send(p.getId(), tacticalGeniusReport ? gameManager.packetManager.createTacticalGeniusReportPacket(p, gameManager) : this.packetManager.createReportPacket(p, gameManager));
        }
    }

    void sendReport(GameManager gameManager) {
        sendReport(false, gameManager);
    }

    /**
     * Send a packet to all connected clients.
     * @param id
     * @param net
     * @param gameManager
     */
    public void sendNovaChange(int id, String net, GameManager gameManager) {
        send(new Packet(PacketCommand.ENTITY_NOVA_NETWORK_CHANGE, id, net));
    }

    public void sendVisibilityIndicator(Entity e, GameManager gameManager) {
        send(new Packet(PacketCommand.ENTITY_VISIBILITY_INDICATOR, e.getId(), e.isEverSeenByEnemy(),
                e.isVisibleToEnemy(), e.isDetectedByEnemy(), e.getWhoCanSee(), e.getWhoCanDetect()));
    }

    /**
     * Sends notification to clients that the specified hex has changed.
     * @param coords
     * @param gameManager
     */
    public void sendChangedMines(Coords coords, GameManager gameManager) {
        send(gameManager.packetManager.createMineChangePacket(coords, gameManager));
    }

    public void sendChangedBuildings(Vector<Building> buildings, GameManager gameManager) {
        send(gameManager.packetManager.createUpdateBuildingPacket(buildings));
    }

    void sendSpecialHexDisplayPackets(GameManager gameManager) {
        for (Player player : gameManager.game.getPlayersVector()) {
            send(gameManager.packetManager.createSpecialHexDisplayPacket(player.getId(), gameManager));
        }
    }

    void sendTagInfoUpdates(GameManager gameManager) {
        send(new Packet(PacketCommand.SENDING_TAG_INFO, gameManager.getGame().getTagInfo()));
    }

    public void sendTagInfoReset(GameManager gameManager) {
        send(new Packet(PacketCommand.RESET_TAG_INFO));
    }

    /**
     * Sends notification to clients that the specified hex has changed.
     * @param coords
     * @param gameManager
     */
    public void sendChangedHexes(Set<Coords> coords, GameManager gameManager) {
        send(gameManager.packetManager.createHexesChangePacket(coords, coords.stream()
                .map(coord -> gameManager.game.getBoard().getHex(coord))
                .collect(Collectors.toCollection(LinkedHashSet::new))));
    }

    /**
     * Sends out a notification message indicating that a ghost player may be
     * skipped.
     *
     * @param ghost - the <code>Player</code> who is ghosted. This value must not
     *              be <code>null</code>.
     * @param gameManager
     */
    protected void sendGhostSkipMessage(Player ghost, GameManager gameManager) {
        String message = "Player '" + ghost.getName() +
                "' is disconnected.  You may skip his/her current turn with the /skip command.";
        sendServerChat(message);
    }

    /**
     * receive a packet that contains hexes that are automatically hit by
     * artillery
     *  @param packet the packet to be processed
     * @param connId the id for connection that received the packet.
     * @param gameManager
     */
    @SuppressWarnings("unchecked")
    protected void receiveArtyAutoHitHexes(Packet packet, int connId, GameManager gameManager) {
        PlayerIDandList<Coords> artyAutoHitHexes = (PlayerIDandList<Coords>) packet
                .getObject(0);

        int playerId = artyAutoHitHexes.getPlayerID();

        // is this the right phase?
        if (!gameManager.game.getPhase().isSetArtilleryAutohitHexes()) {
            LogManager.getLogger().error("Server got set artyautohithexespacket in wrong phase");
            return;
        }
        gameManager.game.getPlayer(playerId).setArtyAutoHitHexes(artyAutoHitHexes);

        for (Coords coord : artyAutoHitHexes) {
            gameManager.game.getBoard().addSpecialHexDisplay(coord,
                    new SpecialHexDisplay(
                            SpecialHexDisplay.Type.ARTILLERY_AUTOHIT,
                            SpecialHexDisplay.NO_ROUND, gameManager.game.getPlayer(playerId),
                            "Artillery auto hit hex, for "
                                    + gameManager.game.getPlayer(playerId).getName(),
                            SpecialHexDisplay.SHD_OBSCURED_TEAM));
        }
        gameManager.gameStateManager.endCurrentTurn(null, gameManager);
    }

    /**
     * receive a packet that contains minefields
     *  @param packet the packet to be processed
     * @param connId the id for connection that received the packet.
     * @param gameManager
     */
    @SuppressWarnings("unchecked")
    protected void receiveDeployMinefields(Packet packet, int connId, GameManager gameManager) {
        Vector<Minefield> minefields = (Vector<Minefield>) packet.getObject(0);

        // is this the right phase?
        if (!gameManager.getGame().getPhase().isDeployMinefields()) {
            LogManager.getLogger().error("Server got deploy minefields packet in wrong phase");
            return;
        }

        // looks like mostly everything's okay
        gameManager.entityActionManager.processDeployMinefields(minefields, gameManager);
        gameManager.gameStateManager.endCurrentTurn(null, gameManager);
    }

    /**
     * The end of a unit's Premovement or Prefiring
     * @param packet
     * @param connId
     * @param gameManager
     */
    @SuppressWarnings("unchecked")
    protected void receivePrephase(Packet packet, int connId, GameManager gameManager) {
        Entity entity = gameManager.game.getEntity(packet.getIntValue(0));

        // is this the right phase?
        if (!gameManager.getGame().getPhase().isPrefiring() && !gameManager.getGame().getPhase().isPremovement()) {
            LogManager.getLogger().error("Server got Prephase packet in wrong phase " + gameManager.game.getPhase());
            return;
        }

        // can this player/entity act right now?
        GameTurn turn = gameManager.game.getTurn();
        if (gameManager.getGame().getPhase().isSimultaneous(gameManager.getGame())) {
            turn = gameManager.game.getTurnForPlayer(connId);
        }
        if ((turn == null) || !turn.isValid(connId, entity, gameManager.game)) {
            LogManager.getLogger().error(String.format(
                    "Server got invalid packet from Connection %s, Entity %s, %s Turn",
                    connId, ((entity == null) ? "null" : entity.getShortName()),
                    ((turn == null) ? "null" : "invalid")));
            send(connId, gameManager.packetManager.createTurnVectorPacket(gameManager));
            send(connId, gameManager.packetManager.createTurnIndexPacket((turn == null) ? Player.PLAYER_NONE : turn.getPlayerNum(), gameManager));
            return;
        }

        entity.setDone(true);

        // Update visibility indications if using double blind.
        if (gameManager.doBlind()) {
            gameManager.updateVisibilityIndicator(null);
        }

        gameManager.entityUpdate(entity.getId());
        gameManager.gameStateManager.endCurrentTurn(entity, gameManager);
    }

    /**
     * Checks if an entity added by the client is valid and if so, adds it to
     * the list
     *  @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     * @param gameManager
     */
    protected void receiveEntityAdd(Packet c, int connIndex, GameManager gameManager) {
        @SuppressWarnings("unchecked")
        final List<Entity> entities = (List<Entity>) c.getObject(0);
        List<Integer> entityIds = new ArrayList<>(entities.size());
        // Map client-received to server-given IDs:
        Map<Integer, Integer> idMap = new HashMap<>();
        // Map MUL force ids to real Server-given force ids;
        Map<Integer, Integer> forceMapping = new HashMap<>();

        // Need to use a new ArrayLiut to prevent a concurrent modification exception when removing
        // illegal entities
        for (final Entity entity : new ArrayList<>(entities)) {
            // Create a TestEntity instance for supported unit types
            TestEntity testEntity = TestEntity.getEntityVerifier(entity);
            entity.restore();

            if (testEntity != null) {
                StringBuffer sb = new StringBuffer();
                if (testEntity.correctEntity(sb, TechConstants.getGameTechLevel(gameManager.game, entity.isClan()))) {
                    entity.setDesignValid(true);
                } else {
                    LogManager.getLogger().error(sb.toString());
                    if (gameManager.game.getOptions().booleanOption(OptionsConstants.ALLOWED_ALLOW_ILLEGAL_UNITS)) {
                        entity.setDesignValid(false);
                    } else {
                        Player cheater = gameManager.game.getPlayer(connIndex);
                        sendServerChat(String.format(
                                "Player %s attempted to add an illegal unit design (%s), the unit was rejected.",
                                cheater.getName(), entity.getShortNameRaw()));
                        entities.remove(entity);
                        continue;
                    }
                }
            }

            // If we're adding a ProtoMech, calculate it's unit number.
            if (entity instanceof Protomech) {
                // How many ProtoMechs does the player already have?
                int numPlayerProtos = gameManager.game.getSelectedEntityCount(new EntitySelector() {
                    protected final int ownerId = entity.getOwnerId();

                    @Override
                    public boolean accept(Entity entity) {
                        return (entity instanceof Protomech) && (ownerId == entity.getOwnerId());
                    }
                });

                // According to page 54 of the BMRr, ProtoMechs must be
                // deployed in full Points of five, unless circumstances have
                // reduced the number to less than that.
                entity.setUnitNumber((short) (numPlayerProtos / 5));
            }

            // Only assign an entity ID when the client hasn't.
            if (Entity.NONE == entity.getId()) {
                entity.setId(gameManager.game.getNextEntityId());
            }

            int clientSideId = entity.getId();
            gameManager.game.addEntity(entity);

            // Remember which received ID corresponds to which actual ID
            idMap.put(clientSideId, entity.getId());

            // Now we relink C3/NC3/C3i to our guys! Yes, this is hackish... but, we
            // do what we must. Its just too bad we have to loop over the entire entities array..
            if (entity.hasC3() || entity.hasC3i() || entity.hasNavalC3()) {
                boolean C3iSet = false;

                for (Entity e : gameManager.game.getEntitiesVector()) {

                    // C3 Checks
                    if (entity.hasC3()) {
                        if ((entity.getC3MasterIsUUIDAsString() != null)
                                && entity.getC3MasterIsUUIDAsString().equals(e.getC3UUIDAsString())) {
                            entity.setC3Master(e, false);
                            entity.setC3MasterIsUUIDAsString(null);
                        } else if ((e.getC3MasterIsUUIDAsString() != null)
                                && e.getC3MasterIsUUIDAsString().equals(entity.getC3UUIDAsString())) {
                            e.setC3Master(entity, false);
                            e.setC3MasterIsUUIDAsString(null);
                            // Taharqa: we need to update the other entity for
                            // the
                            // client
                            // or it won't show up right. I am not sure if I
                            // like
                            // the idea of updating other entities in this
                            // method,
                            // but it
                            // will work for now.
                            if (!entities.contains(e)) {
                                gameManager.entityUpdate(e.getId());
                            }
                        }
                    }

                    // C3i Checks
                    if (entity.hasC3i() && !C3iSet) {
                        entity.setC3NetIdSelf();
                        int pos = 0;
                        while (pos < Entity.MAX_C3i_NODES) {
                            // We've found a network, join it.
                            if ((entity.getC3iNextUUIDAsString(pos) != null)
                                    && (e.getC3UUIDAsString() != null)
                                    && entity.getC3iNextUUIDAsString(pos)
                                    .equals(e.getC3UUIDAsString())) {
                                entity.setC3NetId(e);
                                C3iSet = true;
                                break;
                            }

                            pos++;
                        }
                    }

                    // NC3 Checks
                    if (entity.hasNavalC3() && !C3iSet) {
                        entity.setC3NetIdSelf();
                        int pos = 0;
                        while (pos < Entity.MAX_C3i_NODES) {
                            // We've found a network, join it.
                            if ((entity.getNC3NextUUIDAsString(pos) != null)
                                    && (e.getC3UUIDAsString() != null)
                                    && entity.getNC3NextUUIDAsString(pos)
                                    .equals(e.getC3UUIDAsString())) {
                                entity.setC3NetId(e);
                                C3iSet = true;
                                break;
                            }

                            pos++;
                        }
                    }
                }
            }
            // Give the unit a spotlight, if it has the spotlight quirk
            entity.setExternalSearchlight(entity.hasExternalSearchlight()
                    || entity.hasQuirk(OptionsConstants.QUIRK_POS_SEARCHLIGHT));
            entityIds.add(entity.getId());

            if (!gameManager.getGame().getPhase().isLounge()) {
                entity.getOwner().changeInitialEntityCount(1);
                entity.getOwner().changeInitialBV(entity.calculateBattleValue());
            }

            // Restore forces from MULs or other external sources from the forceString, if any
            if (!entity.getForceString().isBlank()) {
                List<Force> forceList = Forces.parseForceString(entity);
                int realId = Force.NO_FORCE;
                boolean topLevel = true;

                for (Force force: forceList) {
                    if (!forceMapping.containsKey(force.getId())) {
                        if (topLevel) {
                            realId = gameManager.game.getForces().addTopLevelForce(force, entity.getOwner());
                        } else {
                            Force parent = gameManager.game.getForces().getForce(realId);
                            realId = gameManager.game.getForces().addSubForce(force, parent);
                        }
                        forceMapping.put(force.getId(), realId);
                    } else {
                        realId = forceMapping.get(force.getId());
                    }
                    topLevel = false;
                }
                entity.setForceString("");
                gameManager.game.getForces().addEntity(entity, realId);
            }
        }

        // Cycle through the entities again and update any carried units
        // and carrier units to use the correct server-given IDs.
        // Typically necessary when loading a MUL containing transported units.

        // First, deal with units loaded into bays. These are saved for the carrier
        // in MULs and must be restored exactly to recreate the bay loading.
        Set<Entity> transportCorrected = new HashSet<>();
        for (final Entity carrier : entities) {
            for (int carriedId : carrier.getBayLoadedUnitIds()) {
                // First, see if a bay loaded unit can be found and unloaded,
                // because it might be the wrong unit
                Entity carried = gameManager.game.getEntity(carriedId);
                if (carried == null) {
                    continue;
                }
                int bay = carrier.getBay(carried).getBayNumber();
                carrier.unload(carried);
                // Now, load the correct unit if there is one
                if (idMap.containsKey(carriedId)) {
                    Entity newCarried = gameManager.game.getEntity(idMap.get(carriedId));
                    if (carrier.canLoad(newCarried, false)) {
                        carrier.load(newCarried, false, bay);
                        newCarried.setTransportId(carrier.getId());
                        // Remember that the carried unit should not be treated again below
                        transportCorrected.add(newCarried);
                    }
                }
            }
        }

        // Now restore the transport settings from the entities' transporter IDs
        // With anything other than bays, MULs only show the carrier, not the carried units
        for (final Entity entity : entities) {
            // Don't correct those that are already corrected
            if (transportCorrected.contains(entity)) {
                continue;
            }
            // Get the original (client side) ID of the transporter
            int origTrsp = entity.getTransportId();
            // Only act if the unit thinks it is transported
            if (origTrsp != Entity.NONE) {
                // If the transporter is among the new units, go on with loading
                if (idMap.containsKey(origTrsp)) {
                    // The wrong transporter doesn't know of anything and does not need an update
                    Entity carrier = gameManager.game.getEntity(idMap.get(origTrsp));
                    if (carrier.canLoad(entity, false)) {
                        // The correct transporter must be told it's carrying something and
                        // the carried unit must be told where it is embarked
                        carrier.load(entity, false);
                        entity.setTransportId(idMap.get(origTrsp));
                    } else {
                        // This seems to be an invalid carrier; update the entity accordingly
                        entity.setTransportId(Entity.NONE);
                    }
                } else {
                    // this transporter does not exist; update the entity accordingly
                    entity.setTransportId(Entity.NONE);
                }
            }
        }

        // Set the "loaded keepers" which is apparently used for deployment unloading to
        // differentiate between units loaded in the lobby and other carried units
        // When entering a game from the lobby, this list is generated again, but not when
        // the added entities are loaded during a game. When getting loaded units from a MUL,
        // act as if they were loaded in the lobby.
        for (final Entity entity : entities) {
            if (!entity.getLoadedUnits().isEmpty()) {
                Vector<Integer> v = new Vector<>();
                for (Entity en : entity.getLoadedUnits()) {
                    v.add(en.getId());
                }
                entity.setLoadedKeepers(v);
            }
        }

        List<Integer> changedForces = new ArrayList<>(forceMapping.values());

        send(gameManager.packetManager.createAddEntityPacket(entityIds, changedForces, gameManager));
    }

    /**
     * adds a squadron to the game
     * @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     * @param gameManager
     */
    @SuppressWarnings("unchecked")
    protected void receiveSquadronAdd(Packet c, int connIndex, GameManager gameManager) {
        final FighterSquadron fs = (FighterSquadron) c.getObject(0);
        final Collection<Integer> fighters = (Collection<Integer>) c.getObject(1);
        if (fighters.isEmpty()) {
            return;
        }
        // Only assign an entity ID when the client hasn't.
        if (Entity.NONE == fs.getId()) {
            fs.setId(gameManager.game.getNextEntityId());
        }
        gameManager.game.addEntity(fs);
        var formerCarriers = new HashSet<Entity>();

        for (int id : fighters) {
            Entity fighter = gameManager.game.getEntity(id);
            if (null != fighter) {
                formerCarriers.addAll(ServerLobbyHelper.lobbyUnload(gameManager.game, List.of(fighter)));
                fs.load(fighter, false);
                fs.autoSetMaxBombPoints();
                fighter.setTransportId(fs.getId());
                // If this is the lounge, we want to configure bombs
                if (gameManager.getGame().getPhase().isLounge()) {
                    ((IBomber) fighter).setBombChoices(fs.getExtBombChoices());
                }
                gameManager.entityUpdate(fighter.getId());
            }
        }
        if (!formerCarriers.isEmpty()) {
            send(new Packet(PacketCommand.ENTITY_MULTIUPDATE, formerCarriers));
        }
        send(gameManager.packetManager.createAddEntityPacket(fs.getId(), gameManager));
    }

    /**
     * Updates an entity with the info from the client. Only valid to do this
     * during the lounge phase, except for heat sink changing.
     * @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     * @param gameManager
     */
    protected void receiveEntityUpdate(Packet c, int connIndex, GameManager gameManager) {
        Entity entity = (Entity) c.getObject(0);
        Entity oldEntity = gameManager.game.getEntity(entity.getId());
        if ((oldEntity != null) && (!oldEntity.getOwner().isEnemyOf(gameManager.game.getPlayer(connIndex)))) {
            gameManager.game.setEntity(entity.getId(), entity);
            gameManager.entityUpdate(entity.getId());
            if (entity.isPartOfFighterSquadron()) {
                // Update the stats of any Squadrons that the new units are part of
                FighterSquadron squadron = (FighterSquadron) gameManager.game.getEntity(entity.getTransportId());
                squadron.updateSkills();
                squadron.updateWeaponGroups();
                squadron.updateSensors();
                gameManager.entityUpdate(squadron.getId());
            }
            // In the chat lounge, notify players of customizing of unit
            if (gameManager.game.getPhase().isLounge()) {
                sendServerChat(ServerLobbyHelper.entityUpdateMessage(entity, gameManager.game));
            }
        }
    }

    /**
     * Updates multiple entities with the info from the client. Only valid
     * during the lobby phase!
     * Will only update units that are teammates of the sender. Other entities
     * remain unchanged but still be sent back to overwrite incorrect client changes.
     * @param c
     * @param connIndex
     * @param gameManager
     */
    protected void receiveEntitiesUpdate(Packet c, int connIndex, GameManager gameManager) {
        if (!gameManager.getGame().getPhase().isLounge()) {
            LogManager.getLogger().error("Multi entity updates should not be used outside the lobby phase!");
        }
        Set<Entity> newEntities = new HashSet<>();
        @SuppressWarnings("unchecked")
        Collection<Entity> entities = (Collection<Entity>) c.getObject(0);
        for (Entity entity: entities) {
            Entity oldEntity = gameManager.game.getEntity(entity.getId());
            // Only update entities that existed and are owned by a teammate of the sender
            if ((oldEntity != null) && (!oldEntity.getOwner().isEnemyOf(gameManager.game.getPlayer(connIndex)))) {
                gameManager.game.setEntity(entity.getId(), entity);
                sendServerChat(ServerLobbyHelper.entityUpdateMessage(entity, gameManager.game));
                newEntities.add(gameManager.game.getEntity(entity.getId()));
                if (entity.isPartOfFighterSquadron()) {
                    // Update the stats of any Squadrons that the new units are part of
                    FighterSquadron squadron = (FighterSquadron) gameManager.game.getEntity(entity.getTransportId());
                    squadron.updateSkills();
                    squadron.updateWeaponGroups();
                    squadron.updateSensors();
                    newEntities.add(squadron);
                }
            }
        }
        send(new Packet(PacketCommand.ENTITY_MULTIUPDATE, newEntities));
    }

    /**
     * Handles a packet detailing removal of a list of forces. Only valid during the lobby phase.
     * @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     * @param gameManager
     */
    protected void receiveForcesDelete(Packet c, int connIndex, GameManager gameManager) {
        @SuppressWarnings("unchecked")
        List<Integer> forceList = (List<Integer>) c.getObject(0);

        // Gather the forces and entities to be deleted
        Forces forces = gameManager.game.getForces();
        Set<Force> delForces = new HashSet<>();
        forceList.stream().map(forces::getForce).forEach(delForces::add);
        Set<Force> allSubForces = new HashSet<>();
        delForces.stream().map(forces::getFullSubForces).forEach(allSubForces::addAll);
        delForces.removeIf(allSubForces::contains);
        Set<Entity> delEntities = new HashSet<>();
        delForces.stream().map(forces::getFullEntities).map(ForceAssignable::filterToEntityList).forEach(delEntities::addAll);

        // Unload units and disconnect any C3 networks
        Set<Entity> updateCandidates = new HashSet<>();
        updateCandidates.addAll(ServerLobbyHelper.lobbyUnload(gameManager.game, delEntities));
        updateCandidates.addAll(ServerLobbyHelper.performC3Disconnect(gameManager.game, delEntities));

        // Units that get deleted must not receive updates
        updateCandidates.removeIf(delEntities::contains);
        if (!updateCandidates.isEmpty()) {
            send(ServerLobbyHelper.createMultiEntityPacket(updateCandidates));
        }

        // Delete entities and forces
        for (Entity entity : delEntities) {
            gameManager.game.removeEntity(entity.getId(), IEntityRemovalConditions.REMOVE_NEVER_JOINED);
        }
        forces.deleteForces(delForces);
        send(ServerLobbyHelper.createForcesDeletePacket(forceList));
    }

    /**
     * loads an entity into another one. Meant to be called from the chat lounge
     * @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     * @param gameManager
     */
    protected void receiveEntityLoad(Packet c, int connIndex, GameManager gameManager) {
        int loadeeId = (Integer) c.getObject(0);
        int loaderId = (Integer) c.getObject(1);
        int bayNumber = (Integer) c.getObject(2);
        Entity loadee = gameManager.getGame().getEntity(loadeeId);
        Entity loader = gameManager.getGame().getEntity(loaderId);

        if ((loadee != null) && (loader != null)) {
            gameManager.loadUnit(loader, loadee, bayNumber);
            // In the chat lounge, notify players of customizing of unit
            if (gameManager.getGame().getPhase().isLounge()) {
                ServerLobbyHelper.entityUpdateMessage(loadee, gameManager.getGame());
                // Set this so units can be unloaded in the first movement phase
                loadee.setLoadedThisTurn(false);
            }
        }
    }

    /**
     *  @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     * @param gameManager
     */
    protected void receiveCustomInit(Packet c, int connIndex, GameManager gameManager) {
        // In the chat lounge, notify players of customizing of unit
        if (gameManager.game.getPhase().isLounge()) {
            Player p = (Player) c.getObject(0);
            sendServerChat("" + p.getName() + " has customized initiative.");
        }
    }

    /**
     * receive and process an entity mode change packet
     *  @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     * @param gameManager
     */
    protected void receiveEntityModeChange(Packet c, int connIndex, GameManager gameManager) {
        int entityId = c.getIntValue(0);
        int equipId = c.getIntValue(1);
        int mode = c.getIntValue(2);
        Entity e = gameManager.game.getEntity(entityId);
        if (e.getOwner() != gameManager.game.getPlayer(connIndex)) {
            return;
        }
        Mounted m = e.getEquipment(equipId);

        if (m == null) {
            return;
        }

        try {
            // Check for BA dumping body mounted missile launchers
            if ((e instanceof BattleArmor) && (!m.isMissing())
                    && m.isBodyMounted()
                    && m.getType().hasFlag(WeaponType.F_MISSILE)
                    && (m.getLinked() != null)
                    && (m.getLinked().getUsableShotsLeft() > 0)
                    && (mode <= 0)) {
                m.setPendingDump(mode == -1);
                // a mode change for ammo means dumping or hot loading
            } else if ((m.getType() instanceof AmmoType)
                    && !m.getType().hasInstantModeSwitch() && (mode < 0
                    || mode == 0 && m.isPendingDump())) {
                m.setPendingDump(mode == -1);
            } else if ((m.getType() instanceof WeaponType) && m.isDWPMounted()
                    && (mode <= 0)) {
                m.setPendingDump(mode == -1);
            } else {
                if (!m.setMode(mode)) {
                    String message = e.getShortName() + ": " + m.getName() + ": " + e.getLocationName(m.getLocation())
                            + " trying to compensate";
                    LogManager.getLogger().error(message);
                    sendServerChat(message);
                    e.setGameOptions();

                    if (!m.setMode(mode)) {
                        message = e.getShortName() + ": " + m.getName() + ": " + e.getLocationName(m.getLocation())
                                + " unable to compensate";
                        LogManager.getLogger().error(message);
                        sendServerChat(message);
                    }

                }
            }
        } catch (Exception ex) {
            LogManager.getLogger().error("", ex);
        }
    }

    /**
     * Receive and process an Entity Sensor Change Packet
     * @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     * @param gameManager
     */
    protected void receiveEntitySensorChange(Packet c, int connIndex, GameManager gameManager) {
        int entityId = c.getIntValue(0);
        int sensorId = c.getIntValue(1);
        Entity e = gameManager.game.getEntity(entityId);
        e.setNextSensor(e.getSensors().elementAt(sensorId));
    }

    /**
     * Receive and process an Entity Heat Sinks Change Packet
     * @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     * @param gameManager
     */
    protected void receiveEntitySinksChange(Packet c, int connIndex, GameManager gameManager) {
        int entityId = c.getIntValue(0);
        int numSinks = c.getIntValue(1);
        Entity e = gameManager.game.getEntity(entityId);
        if ((e instanceof Mech) && (connIndex == e.getOwnerId())) {
            ((Mech) e).setActiveSinksNextRound(numSinks);
        }
    }

    /**
     *  @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     * @param gameManager
     */
    protected void receiveEntityActivateHidden(Packet c, int connIndex, GameManager gameManager) {
        int entityId = c.getIntValue(0);
        GamePhase phase = (GamePhase) c.getObject(1);
        Entity e = gameManager.game.getEntity(entityId);
        if (connIndex != e.getOwnerId()) {
            LogManager.getLogger().error("Player " + connIndex
                    + " tried to activate a hidden unit owned by Player " + e.getOwnerId());
            return;
        }
        e.setHiddenActivationPhase(phase);
        gameManager.entityUpdate(entityId);
    }

    /**
     * receive and process an entity nova network mode change packet
     *  @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     * @param gameManager
     */
    protected void receiveEntityNovaNetworkModeChange(Packet c, int connIndex, GameManager gameManager) {
        try {
            int entityId = c.getIntValue(0);
            String networkID = c.getObject(1).toString();
            Entity e = gameManager.game.getEntity(entityId);
            if (e.getOwner() != gameManager.game.getPlayer(connIndex)) {
                return;
            }
            // FIXME: Greg: This can result in setting the network to link to
            // hostile units.
            // However, it should be caught by both the isMemberOfNetwork test
            // from the c3 module as well as
            // by the clients possible input.
            e.setNewRoundNovaNetworkString(networkID);
        } catch (Exception ex) {
            LogManager.getLogger().error("", ex);
        }
    }

    /**
     * receive and process an entity mounted facing change packet
     *  @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     * @param gameManager
     */
    protected void receiveEntityMountedFacingChange(Packet c, int connIndex, GameManager gameManager) {
        int entityId = c.getIntValue(0);
        int equipId = c.getIntValue(1);
        int facing = c.getIntValue(2);
        Entity e = gameManager.game.getEntity(entityId);
        if (e.getOwner() != gameManager.game.getPlayer(connIndex)) {
            return;
        }
        Mounted m = e.getEquipment(equipId);

        if (m == null) {
            return;
        }
        m.setFacing(facing);
    }

    /**
     * receive and process an entity mode change packet
     *  @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     * @param gameManager
     */
    protected void receiveEntityCalledShotChange(Packet c, int connIndex, GameManager gameManager) {
        int entityId = c.getIntValue(0);
        int equipId = c.getIntValue(1);
        Entity e = gameManager.game.getEntity(entityId);
        if (e.getOwner() != gameManager.game.getPlayer(connIndex)) {
            return;
        }
        Mounted m = e.getEquipment(equipId);

        if (m == null) {
            return;
        }
        m.getCalledShot().switchCalledShot();
    }

    /**
     * receive and process an entity system mode change packet
     *  @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     * @param gameManager
     */
    protected void receiveEntitySystemModeChange(Packet c, int connIndex, GameManager gameManager) {
        int entityId = c.getIntValue(0);
        int equipId = c.getIntValue(1);
        int mode = c.getIntValue(2);
        Entity e = gameManager.game.getEntity(entityId);
        if (e.getOwner() != gameManager.game.getPlayer(connIndex)) {
            return;
        }
        if ((e instanceof Mech) && (equipId == Mech.SYSTEM_COCKPIT)) {
            ((Mech) e).setCockpitStatus(mode);
        }
    }

    /**
     * Receive a packet that contains an Entity ammo change
     *  @param c the packet to be processed
     * @param connIndex the id for connection that received the packet.
     * @param gameManager
     */
    protected void receiveEntityAmmoChange(Packet c, int connIndex, GameManager gameManager) {
        int entityId = c.getIntValue(0);
        int weaponId = c.getIntValue(1);
        int ammoId = c.getIntValue(2);
        int reason = c.getIntValue(3);
        Entity e = gameManager.game.getEntity(entityId);

        // Did we receive a request for a valid Entity?
        if (null == e) {
            LogManager.getLogger().error("Could not find entity# " + entityId);
            return;
        }
        Player player = gameManager.game.getPlayer(connIndex);
        if ((null != player) && (e.getOwner() != player)) {
            LogManager.getLogger().error("Player " + player.getName() + " does not own the entity " + e.getDisplayName());
            return;
        }

        // Make sure that the entity has the given equipment.
        Mounted mWeap = e.getEquipment(weaponId);
        Mounted mAmmo = e.getEquipment(ammoId);
        Mounted oldAmmo = (mWeap == null) ? null : mWeap.getLinked();
        if (null == mAmmo) {
            LogManager.getLogger().error("Entity " + e.getDisplayName() + " does not have ammo #" + ammoId);
            return;
        }
        if (!(mAmmo.getType() instanceof AmmoType)) {
            LogManager.getLogger().error("Item #" + ammoId + " of entity " + e.getDisplayName()
                    + " is a " + mAmmo.getName() + " and not ammo.");
            return;
        }
        if (null == mWeap) {
            LogManager.getLogger().error("Entity " + e.getDisplayName() + " does not have weapon #" + weaponId);
            return;
        }
        if (!(mWeap.getType() instanceof WeaponType)) {
            LogManager.getLogger().error("Item #" + weaponId + " of entity " + e.getDisplayName()
                    + " is a " + mWeap.getName() + " and not a weapon.");
            return;
        }
        if (((WeaponType) mWeap.getType()).getAmmoType() == AmmoType.T_NA) {
            LogManager.getLogger().error("Item #" + weaponId + " of entity " + e.getDisplayName()
                    + " is a " + mWeap.getName() + " and does not use ammo.");
            return;
        }
        if (mWeap.getType().hasFlag(WeaponType.F_ONESHOT)
                && !mWeap.getType().hasFlag(WeaponType.F_DOUBLE_ONESHOT)) {
            LogManager.getLogger().error("Item #" + weaponId + " of entity " + e.getDisplayName()
                    + " is a " + mWeap.getName() + " and cannot use external ammo.");
            return;
        }

        // Load the weapon.
        e.loadWeapon(mWeap, mAmmo);

        // Report the change, if reason is provided and it's not already being used.
        if (reason != 0 && oldAmmo != mAmmo) {
            Report r = new Report(1500);
            r.subject = entityId;
            r.addDesc(e);
            r.add(mAmmo.getShortName());
            r.add(ReportMessages.getString(String.valueOf(reason)));
            gameManager.addReport(r);
            if (LogManager.getLogger().isDebugEnabled()) {

            }
        }
    }

    /**
     * Deletes an entity owned by a certain player from the list
     * @param c
     * @param connIndex
     * @param gameManager
     */
    protected void receiveEntityDelete(Packet c, int connIndex, GameManager gameManager) {
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) c.getObject(0);

        Set<Entity> delEntities = new HashSet<>();
        ids.stream().map(id -> gameManager.game.getEntity(id)).forEach(delEntities::add);

        // Unload units and disconnect any C3 networks
        Set<Entity> updateCandidates = new HashSet<>();
        updateCandidates.addAll(ServerLobbyHelper.lobbyUnload(gameManager.game, delEntities));
        updateCandidates.addAll(ServerLobbyHelper.performC3Disconnect(gameManager.game, delEntities));

        // Units that get deleted must not receive updates
        updateCandidates.removeIf(delEntities::contains);
        if (!updateCandidates.isEmpty()) {
            send(ServerLobbyHelper.createMultiEntityPacket(updateCandidates));
        }

        ArrayList<Force> affectedForces = new ArrayList<>();
        for (Integer entityId : ids) {
            final Entity entity = gameManager.game.getEntity(entityId);

            // Players can delete units of their teammates
            if ((entity != null) && (!entity.getOwner().isEnemyOf(gameManager.game.getPlayer(connIndex)))) {

                affectedForces.addAll(gameManager.game.getForces().removeEntityFromForces(entity));

                // If we're deleting a ProtoMech, recalculate unit numbers.
                if (entity instanceof Protomech) {

                    // How many ProtoMechs does the player have (include this one)?
                    int numPlayerProtos = gameManager.game.getSelectedEntityCount(new EntitySelector() {
                        protected final int ownerId = entity.getOwnerId();

                        @Override
                        public boolean accept(Entity entity) {
                            return (entity instanceof Protomech) && (ownerId == entity.getOwnerId());
                        }
                    });

                    // According to page 54 of the BMRr, ProtoMechs must be
                    // deployed in full Points of five, unless "losses" have
                    // reduced the number to less than that.
                    final char oldMax = (char) (Math.ceil(numPlayerProtos / 5.0) - 1);
                    char newMax = (char) (Math.ceil((numPlayerProtos - 1) / 5.0) - 1);
                    short deletedUnitNum = entity.getUnitNumber();

                    // Do we have to update a ProtoMech from the last unit?
                    if ((oldMax != deletedUnitNum) && (oldMax != newMax)) {

                        // Yup. Find a ProtoMech from the last unit, and
                        // set it's unit number to the deleted entity.
                        Iterator<Entity> lastUnit =
                                gameManager.game.getSelectedEntities(new EntitySelector() {
                                    protected final int ownerId = entity.getOwnerId();

                                    protected final char lastUnitNum = oldMax;

                                    @Override
                                    public boolean accept(Entity entity) {
                                        return (entity instanceof Protomech)
                                                && (ownerId == entity.getOwnerId())
                                                && (lastUnitNum == entity.getUnitNumber());
                                    }
                                });
                        Entity lastUnitMember = lastUnit.next();
                        lastUnitMember.setUnitNumber(deletedUnitNum);
                        gameManager.entityUpdate(lastUnitMember.getId());
                    } // End update-unit-number
                } // End added-ProtoMech

                if (!gameManager.getGame().getPhase().isDeployment()) {
                    // if a unit is removed during deployment just keep going
                    // without adjusting the turn vector.
                    gameManager.game.removeTurnFor(entity);
                    gameManager.game.removeEntity(entityId, IEntityRemovalConditions.REMOVE_NEVER_JOINED);
                }

                if (!gameManager.game.getPhase().isLounge()) {
                    ServerHelper.clearBloodStalkers(gameManager.game, entityId, gameManager);
                }
            }
        }

        // during deployment this absolutely must be called before game.removeEntity(), otherwise the game hangs
        // when a unit is removed. Cause unknown.
        send(gameManager.packetManager.createRemoveEntityPacket(ids, affectedForces, IEntityRemovalConditions.REMOVE_NEVER_JOINED));

        // Prevents deployment hanging. Only do this during deployment.
        if (gameManager.game.getPhase().isDeployment()) {
            for (Integer entityId : ids) {
                final Entity entity = gameManager.game.getEntity(entityId);
                gameManager.game.removeEntity(entityId, IEntityRemovalConditions.REMOVE_NEVER_JOINED);
                gameManager.gameStateManager.endCurrentTurn(entity, gameManager);
            }
        }
    }

    /**
     * Sets a player's ready status
     * @param pkt
     * @param connIndex
     * @param gameManager
     */
    protected void receivePlayerDone(Packet pkt, int connIndex, GameManager gameManager) {
        boolean ready = pkt.getBooleanValue(0);
        Player player = gameManager.game.getPlayer(connIndex);
        if (null != player) {
            player.setDone(ready);
        }
    }

    protected void receiveInitiativeRerollRequest(Packet pkt, int connIndex, GameManager gameManager) {
        Player player = gameManager.game.getPlayer(connIndex);
        if (!gameManager.game.getPhase().isInitiativeReport()) {
            StringBuilder message = new StringBuilder();
            if (null == player) {
                message.append("Player #").append(connIndex);
            } else {
                message.append(player.getName());
            }
            message.append(" is not allowed to ask for a reroll at this time.");
            LogManager.getLogger().error(message.toString());
            sendServerChat(message.toString());
            return;
        }
        if (gameManager.game.hasTacticalGenius(player)) {
            gameManager.game.addInitiativeRerollRequest(gameManager.game.getTeamForPlayer(player));
        }
        if (null != player) {
            player.setDone(true);
        }

        gameManager.gameStateManager.checkReady(gameManager);
    }

    /**
     * Sets game options, providing that the player has specified the password
     * correctly.
     *
     * @return true if any options have been successfully changed.
     * @param packet
     * @param connId
     * @param gameManager
     */
    protected boolean receiveGameOptions(Packet packet, int connId, GameManager gameManager) {
        Player player = gameManager.game.getPlayer(connId);
        // Check player
        if (null == player) {
            LogManager.getLogger().error("Server does not recognize player at connection " + connId);
            return false;
        }

        // check password
        if (!Server.getServerInstance().passwordMatches(packet.getObject(0))) {
            sendServerChat(connId, "The password you specified to change game options is incorrect.");
            return false;
        }

        if (gameManager.game.getPhase().isDuringOrAfter(GamePhase.DEPLOYMENT)) {
            return false;
        }

        int changed = 0;

        for (Enumeration<?> i = ((Vector<?>) packet.getObject(1)).elements(); i.hasMoreElements(); ) {
            IBasicOption option = (IBasicOption) i.nextElement();
            IOption originalOption = gameManager.game.getOptions().getOption(option.getName());

            if (originalOption == null) {
                continue;
            }

            String message = "Player " + player + " changed option \"" +
                    originalOption.getDisplayableName() + "\" to " + option.getValue().toString() + '.';
            sendServerChat(message);
            originalOption.setValue(option.getValue());
            changed++;
        }

        // Set proper RNG
        Compute.setRNG(gameManager.game.getOptions().intOption(OptionsConstants.BASE_RNG_TYPE));

        if (changed > 0) {
            for (Entity en : gameManager.game.getEntitiesVector()) {
                en.setGameOptions();
            }
            gameManager.entityAllUpdate();
            return true;
        }
        return false;
    }

    /**
     * Performs the additional processing of the received options after the
     * <code>receiveGameOptions<code> done its job; should be called after
     * <code>receiveGameOptions<code> only if the <code>receiveGameOptions<code>
     * returned <code>true</code>
     *  @param packet the packet to be processed
     * @param connId the id for connection that received the packet.
     * @param gameManager
     */
    protected void receiveGameOptionsAux(Packet packet, int connId, GameManager gameManager) {
        MapSettings mapSettings = gameManager.game.getMapSettings();
        for (Enumeration<?> i = ((Vector<?>) packet.getObject(1)).elements(); i.hasMoreElements(); ) {
            IBasicOption option = (IBasicOption) i.nextElement();
            IOption originalOption = gameManager.game.getOptions().getOption(option.getName());
            if (originalOption != null) {
                if ("maps_include_subdir".equals(originalOption.getName())) {
                    mapSettings.setBoardsAvailableVector(ServerBoardHelper.scanForBoards(mapSettings));
                    mapSettings.removeUnavailable();
                    mapSettings.setNullBoards(GameManager.DEFAULT_BOARD);
                    send(this.packetManager.createMapSettingsPacket(gameManager));
                }
            }
        }

    }

    /**
     * Receives an packet to unload entity is stranded on immobile transports,
     * and queue all valid requests for execution. If all players that have
     * stranded entities have answered, executes the pending requests and end
     * the current turn.
     * @param packet
     * @param connId
     * @param gameManager
     */
    protected void receiveUnloadStranded(Packet packet, int connId, GameManager gameManager) {
        GameTurn.UnloadStrandedTurn turn;
        final Player player = gameManager.game.getPlayer(connId);
        int[] entityIds = (int[]) packet.getObject(0);
        Vector<Player> declared;
        Player other;
        Enumeration<EntityAction> pending;
        UnloadStrandedAction action;
        Entity entity;

        // Is this the right phase?
        if (!gameManager.getGame().getPhase().isMovement()) {
            LogManager.getLogger().error("Server got unload stranded packet in wrong phase");
            return;
        }

        // Are we in an "unload stranded entities" turn?
        if (gameManager.getGame().getTurn() instanceof GameTurn.UnloadStrandedTurn) {
            turn = (GameTurn.UnloadStrandedTurn) gameManager.getGame().getTurn();
        } else {
            LogManager.getLogger().error("Server got unload stranded packet out of sequence");
            sendServerChat(player.getName() + " should not be sending 'unload stranded entity' packets at this time.");
            return;
        }

        // Can this player act right now?
        if (!turn.isValid(connId, gameManager.getGame())) {
            LogManager.getLogger().error("Server got unload stranded packet from invalid player");
            sendServerChat(player.getName() + " should not be sending 'unload stranded entity' packets.");
            return;
        }

        // Did the player already send an 'unload' request?
        // N.B. we're also building the list of players who
        // have declared their "unload stranded" actions.
        declared = new Vector<>();
        pending = gameManager.getGame().getActions();
        while (pending.hasMoreElements()) {
            action = (UnloadStrandedAction) pending.nextElement();
            if (action.getPlayerId() == connId) {
                LogManager.getLogger().error("Server got multiple unload stranded packets from player");
                sendServerChat(player.getName() + " should not send multiple 'unload stranded entity' packets.");
                return;
            }
            // This player is not from the current connection.
            // Record this player to determine if this turn is done.
            other = gameManager.getGame().getPlayer(action.getPlayerId());
            if (!declared.contains(other)) {
                declared.addElement(other);
            }
        } // Handle the next "unload stranded" action.

        // Make sure the player selected at least *one* valid entity ID.
        boolean foundValid = false;
        for (int index = 0; (null != entityIds) && (index < entityIds.length); index++) {
            entity = gameManager.game.getEntity(entityIds[index]);
            if (!gameManager.game.getTurn().isValid(connId, entity, gameManager.game)) {
                LogManager.getLogger().error("Server got unload stranded packet for invalid entity");
                StringBuilder message = new StringBuilder();
                message.append(player.getName()).append(" can not unload stranded entity ");
                if (null == entity) {
                    message.append('#').append(entityIds[index]);
                } else {
                    message.append(entity.getDisplayName());
                }
                message.append(" at this time.");
                sendServerChat(message.toString());
            } else {
                foundValid = true;
                gameManager.game.addAction(new UnloadStrandedAction(connId, entityIds[index]));
            }
        }

        // Did the player choose not to unload any valid stranded entity?
        if (!foundValid) {
            gameManager.game.addAction(new UnloadStrandedAction(connId, Entity.NONE));
        }

        // Either way, the connection's player has now declared.
        declared.addElement(player);

        // Are all players who are unloading entities done? Walk
        // through the turn's stranded entities, and look to see
        // if their player has finished their turn.
        entityIds = turn.getEntityIds();
        for (int entityId : entityIds) {
            entity = gameManager.game.getEntity(entityId);
            other = entity.getOwner();
            if (!declared.contains(other)) {
                // At least one player still needs to declare.
                return;
            }
        }

        // All players have declared whether they're unloading stranded units.
        // Walk the list of pending actions and unload the entities.
        pending = gameManager.game.getActions();
        while (pending.hasMoreElements()) {
            action = (UnloadStrandedAction) pending.nextElement();

            // Some players don't want to unload any stranded units.
            if (Entity.NONE != action.getEntityId()) {
                entity = gameManager.game.getEntity(action.getEntityId());
                if (null == entity) {
                    // After all this, we couldn't find the entity!!!
                    LogManager.getLogger().error("Server could not find stranded entity #"
                            + action.getEntityId() + " to unload!!!");
                } else {
                    // Unload the entity. Get the unit's transporter.
                    Entity transporter = gameManager.game.getEntity(entity.getTransportId());
                    gameManager.unloadUnit(transporter, entity, transporter.getPosition(),
                            transporter.getFacing(), transporter.getElevation());
                }
            }

        } // Handle the next pending unload action

        // Clear the list of pending units and move to the next turn.
        gameManager.game.resetActions();
        gameManager.gameStateManager.changeToNextTurn(connId, gameManager);
    }

    /**
     * Hand over a turn to the next player. This is only possible if you haven't
     * yet started your turn (i.e. not yet moved anything like infantry where
     * you have to move multiple units)
     *
     * @param connectionId - connection id of the player sending the packet
     * @param gameManager
     */
    protected void receiveForwardIni(int connectionId, GameManager gameManager) {
        // this is the player sending the packet
        Player current = gameManager.game.getPlayer(connectionId);

        if (gameManager.game.getTurn().getPlayerNum() != current.getId()) {
            // this player is not the current player, so just ignore this
            // command!
            return;
        }
        // if individual initiative is active we cannot forward our initiative
        // ever!
        if (gameManager.game.getOptions().booleanOption(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE)) {
            return;
        }

        // if the player isn't on a team, there is no next team by definition. Skip the rest.
        Team currentPlayerTeam = gameManager.game.getTeamForPlayer(current);
        if (currentPlayerTeam == null) {
            return;
        }

        // get the next player from the team this player is on.
        Player next = currentPlayerTeam.getNextValidPlayer(current, gameManager.game);

        while (!next.equals(current)) {
            // if the chosen player is a valid player, we change the turn order and
            // inform the clients.
            if ((next != null) && (gameManager.game.getEntitiesOwnedBy(next) != 0)
                    && (gameManager.game.getTurnForPlayer(next.getId()) != null)) {

                int currentTurnIndex = gameManager.game.getTurnIndex();
                // now look for the next occurrence of player next in the turn order
                List<GameTurn> turns = gameManager.game.getTurnVector();
                GameTurn turn = gameManager.game.getTurn();
                // not entirely necessary. As we will also check this for the
                // activity of the button but to be sure do it on the server too.
                boolean isGeneralMoveTurn = !(turn instanceof GameTurn.SpecificEntityTurn)
                        && !(turn instanceof GameTurn.UnitNumberTurn)
                        && !(turn instanceof GameTurn.UnloadStrandedTurn);
                if (!isGeneralMoveTurn) {
                    // if this is not a general turn the player cannot forward his turn.
                    return;
                }

                // if it is an EntityClassTurn we have to check make sure, that the
                // turn it is exchanged with is the same kind of turn!
                // in fact this requires an access function to the mask of an
                // EntityClassTurn.
                boolean isEntityClassTurn = (turn instanceof GameTurn.EntityClassTurn);
                int classMask = 0;
                if (isEntityClassTurn) {
                    classMask = ((GameTurn.EntityClassTurn) turn).getTurnCode();
                }

                boolean switched = false;
                int nextTurnId = 0;
                for (int i = currentTurnIndex; i < turns.size(); i++) {
                    // if we find a turn for the specific player, swap the current
                    // player with the player noted there
                    // and stop
                    if (turns.get(i).isValid(next.getId(), gameManager.game)) {
                        nextTurnId = i;
                        if (isEntityClassTurn) {
                            // if we had an EntityClassTurn
                            if ((turns.get(i) instanceof GameTurn.EntityClassTurn)) {
                                // and found another EntityClassTurn
                                if (!(((GameTurn.EntityClassTurn) turns.get(i)).getTurnCode() == classMask)) {
                                    // both have to refer to the SAME class(es) or
                                    // they need to be rejected.
                                    continue;
                                }
                            } else {
                                continue;
                            }
                        }
                        switched = true;
                        break;
                    }
                }

                // update turn order
                if (switched) {
                    gameManager.game.swapTurnOrder(currentTurnIndex, nextTurnId);
                    // update the turn packages for all players.
                    send(gameManager.packetManager.createTurnVectorPacket(gameManager));
                    send(gameManager.packetManager.createTurnIndexPacket(connectionId, gameManager));
                    return;
                }
                // if nothing changed return without doing anything
            }

            next = currentPlayerTeam.getNextValidPlayer(next, gameManager.game);
        }
    }

    /**
     * Sends out the player info updates for all players to all connections
     * @param gameManager
     */
    protected void transmitAllPlayerUpdates(GameManager gameManager) {
        for (var player: gameManager.getGame().getPlayersVector()) {
            gameManager.communicationManager.transmitPlayerUpdate(player);
        }
    }

    public void transmitPlayerUpdate(Player p) {
        Server.getServerInstance().transmitPlayerUpdate(p);
    }

    /**
     * Sends out the player ready stats for all players to all connections
     * @param gameManager
     */
    protected void transmitAllPlayerDones(GameManager gameManager) {
        for (Player player : gameManager.getGame().getPlayersList()) {
            send(gameManager.packetManager.createPlayerDonePacket(player.getId(), gameManager));
        }
    }

    /**
     * Sends out the game victory event to all connections
     * @param gameManager
     */
    void transmitGameVictoryEventToAll(GameManager gameManager) {
        send(new Packet(PacketCommand.GAME_VICTORY_EVENT));
    }
}
