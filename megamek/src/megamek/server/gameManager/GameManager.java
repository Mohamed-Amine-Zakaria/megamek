/*
 * Copyright (c) 2022 - The MegaMek Team. All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 */
package megamek.server.gameManager;

import megamek.MMConstants;
import megamek.client.ui.swing.GUIPreferences;
import megamek.common.*;
import megamek.common.Building.DemolitionCharge;
import megamek.common.actions.*;
import megamek.common.annotations.Nullable;
import megamek.common.enums.BasementType;
import megamek.common.enums.GamePhase;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.weapons.*;
import megamek.common.weapons.infantry.InfantryWeapon;
import megamek.server.*;
import megamek.server.commands.*;
import megamek.server.rating.EloRatingSystem;
import megamek.server.rating.RatingSystem;
import org.apache.logging.log4j.LogManager;

import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Manages the Game and processes player actions.
 */
public class GameManager implements IGameManager {

    protected RatingSystem ratingSystem = new EloRatingSystem();
    protected CombatManager combatManager = new CombatManager();
    public PacketManager packetManager = new PacketManager();
    public EnvironmentalEffectManager environmentalEffectManager = new EnvironmentalEffectManager();
    protected BVCountHelper bvCount = new BVCountHelper();
    public CommunicationManager communicationManager = new CommunicationManager();
    protected RatingManager ratingManager = new RatingManager();
    public GameStateManager gameStateManager = new GameStateManager();
    public PlayerManager playerManager = new PlayerManager();
    public EntityActionManager entityActionManager = new EntityActionManager();
    public ReportManager reportManager = new ReportManager();
    public UtilityManager utilityManager = new UtilityManager();



    public static final String DEFAULT_BOARD = MapSettings.BOARD_GENERATED;

    public Game game = new Game();

    public Vector<Report> vPhaseReport = new Vector<>();

    // Track buildings that are affected by an entity's movement.
    protected Hashtable<Building, Boolean> affectedBldgs = new Hashtable<>();

    // Track Physical Action results, HACK to deal with opposing pushes
    // canceling each other
    protected Vector<PhysicalResult> physicalResults = new Vector<>();

    protected Vector<DynamicTerrainProcessor> terrainProcessors = new Vector<>();

    protected ArrayList<int[]> scheduledNukes = new ArrayList<>();

    /**
     * Stores a set of <code>Coords</code> that have changed during this phase.
     */
    protected Set<Coords> hexUpdateSet = new LinkedHashSet<>();

    protected List<DemolitionCharge> explodingCharges = new ArrayList<>();

    /**
     * Keeps track of what team a player requested to join.
     */
    protected int requestedTeam = Player.TEAM_NONE;

    /**
     * Keeps track of which player made a request to change teams.
     */
    protected Player playerChangingTeam = null;

    /**
     * Flag that is set to true when all players have voted to allow another
     * player to change teams.
     */
    boolean changePlayersTeam = false;

    /**
     * Keeps track of which player made a request to become Game Master.
     */
    protected Player playerRequestingGameMaster = null;

    /**
     * Special packet queue for client feedback requests.
     */
    protected final ConcurrentLinkedQueue<Server.ReceivedPacket> cfrPacketQueue = new ConcurrentLinkedQueue<>();

    public GameManager() {
        game.getOptions().initialize();
        game.getOptions().loadOptions();

        game.setPhase(GamePhase.LOUNGE);
        MapSettings mapSettings = game.getMapSettings();
        mapSettings.setBoardsAvailableVector(ServerBoardHelper.scanForBoards(mapSettings));
        mapSettings.setNullBoards(DEFAULT_BOARD);

        // register terrain processors
        terrainProcessors.add(new FireProcessor(this));
        terrainProcessors.add(new GeyserProcessor(this));
        terrainProcessors.add(new ElevatorProcessor(this));
        terrainProcessors.add(new ScreenProcessor(this));
        terrainProcessors.add(new WeatherProcessor(this));
        terrainProcessors.add(new QuicksandProcessor(this));
    }

    @Override
    public List<ServerCommand> getCommandList(Server server) {
        List<ServerCommand> commands = new ArrayList<>();
        commands.add(new DefeatCommand(server));
        commands.add(new ExportListCommand(server));
        commands.add(new FixElevationCommand(server, this));
        commands.add(new HelpCommand(server));
        commands.add(new BotHelpCommand(server));
        commands.add(new KickCommand(server));
        commands.add(new ListSavesCommand(server));
        commands.add(new LocalSaveGameCommand(server));
        commands.add(new LocalLoadGameCommand(server));
        commands.add(new ResetCommand(server));
        commands.add(new RollCommand(server));
        commands.add(new SaveGameCommand(server));
        commands.add(new LoadGameCommand(server));
        commands.add(new SeeAllCommand(server, this));
        commands.add(new SingleBlindCommand(server, this));
        commands.add(new SkipCommand(server, this));
        commands.add(new VictoryCommand(server, this));
        commands.add(new WhoCommand(server));
        commands.add(new TeamCommand(server));
        commands.add(new ShowTileCommand(server, this));
        commands.add(new ShowEntityCommand(server, this));
        commands.add(new RulerCommand(server, this));
        commands.add(new ShowValidTargetsCommand(server, this));
        commands.add(new AddBotCommand(server, this));
        commands.add(new CheckBVCommand(server));
        commands.add(new CheckBVTeamCommand(server));
        commands.add(new NukeCommand(server, this));
        commands.add(new TraitorCommand(server, this));
        commands.add(new ListEntitiesCommand(server, this));
        commands.add(new AssignNovaNetServerCommand(server, this));
        commands.add(new AllowTeamChangeCommand(server, this));
        commands.add(new JoinTeamCommand(server));
        commands.add(new AllowGameMasterCommand(server, this));
        commands.add(new GameMasterCommand(server));
        return commands;
    }

    @Override
    public Game getGame() {
        return game;
    }

    @Override
    public void setGame(IGame g) {
        gameStateManager._setGame(g, this);
    }

    /**
     * Reset the game back to the lounge.
     */
    @Override
    public void resetGame() {
        gameStateManager._resetGame(this);
    }

    @Override
    public void requestGameMaster(Player player) {
        playerRequestingGameMaster = player;
    }

    @Override
    public void requestTeamChange(int team, Player player) {
        playerManager.changeTeam(team, player, this);
    }

    /**
     * save the game and send it to the specified connection
     *
     * @param connId     The <code>int</code> connection id to send to
     * @param sFile      The <code>String</code> filename to use
     * @param sLocalPath The <code>String</code> path to the file to be used on the
     *                   client
     */
    @Override
    public void sendSaveGame(int connId, String sFile, String sLocalPath) {
        communicationManager._sendSaveGame(connId, sFile, sLocalPath, this);
    }

    /**
     * save the game
     *
     * @param sFile The <code>String</code> filename to use
     */
    @Override
    public void saveGame(String sFile) {
        gameStateManager.saveGame(sFile, true, this);
    }


    @Override
    public void disconnect(Player player) {
        // in the lounge, just remove all entities for that player
        playerManager.disconnectAPlayer(player, this);
    }

    @Override
    public void removeAllEntitiesOwnedBy(Player player) {
        playerManager.removeEntities(player, this);
    }

    /**
     * Sends a player the info they need to look at the current phase. This is
     * triggered when a player first connects to the server.
     */
    @Override
    public void sendCurrentInfo(int connId) {
        communicationManager.sendInfo(connId, this);
    }

    @Override
    public void handleCfrPacket(Server.ReceivedPacket rp) {
        synchronized (cfrPacketQueue) {
            cfrPacketQueue.add(rp);
            cfrPacketQueue.notifyAll();
        }

    }

    @Override
    public void handlePacket(int connId, Packet packet) {
        packetManager.packetHandler(connId,packet, this);
    }


    /**
     * Marks ineligible entities as not ready for this phase
     */
    protected void setIneligible(GamePhase phase) {
        Vector<Entity> assistants = new Vector<>();
        boolean assistable = false;

        if (gameStateManager.isPlayerForcedVictory(this)) {
            assistants.addAll(game.getEntitiesVector());
        } else {
            for (Entity entity : game.getEntitiesVector()) {
                if (entity.isEligibleFor(phase)) {
                    assistable = true;
                } else {
                    assistants.addElement(entity);
                }
            }
        }
        for (Entity assistant : assistants) {
            if (!assistable || !assistant.canAssist(phase)) {
                assistant.setDone(true);
            }
        }
    }

    /**
     * Have the loader load the indicated unit. The unit being loaded loses its
     * turn.
     *
     * @param loader - the <code>Entity</code> that is loading the unit.
     * @param unit   - the <code>Entity</code> being loaded.
     */
    protected void loadUnit(Entity loader, Entity unit, int bayNumber) {
        // ProtoMechs share a single turn for a Point. When loading one we don't remove its turn
        // unless it's the last unit in the Point to act.
        int remainingProtos = 0;
        if (unit.hasETypeFlag(Entity.ETYPE_PROTOMECH)) {
            remainingProtos = game.getSelectedEntityCount(en -> en.hasETypeFlag(Entity.ETYPE_PROTOMECH)
                    && en.getId() != unit.getId()
                    && en.isSelectableThisTurn()
                    && en.getOwnerId() == unit.getOwnerId()
                    && en.getUnitNumber() == unit.getUnitNumber());
        }

        if (!getGame().getPhase().isLounge() && !unit.isDone() && (remainingProtos == 0)) {
            // Remove the *last* friendly turn (removing the *first* penalizes
            // the opponent too much, and re-calculating moves is too hard).
            game.removeTurnFor(unit);
            communicationManager.send(packetManager.createTurnVectorPacket(this));
        }

        // Fighter Squadrons may become too big for the bay they're parked in
        if ((loader instanceof FighterSquadron) && (loader.getTransportId() != Entity.NONE)) {
            Entity carrier = game.getEntity(loader.getTransportId());
            Transporter bay = carrier.getBay(loader);

            if (bay.getUnused() < 1) {
                if (getGame().getPhase().isLounge()) {
                    // In the lobby, unload the squadron if too big
                    loader.setTransportId(Entity.NONE);
                    carrier.unload(loader);
                    entityUpdate(carrier.getId());
                } else {
                    // Outside the lobby, reject the load
                    entityUpdate(unit.getId());
                    entityUpdate(loader.getId());
                    return;
                }
            }
        }

        // When loading an Aero into a squadron in the lounge, make sure the
        // loaded aero has the same bomb loadout as the squadron
        // We want to do this before the fighter is loaded: when the fighter
        // is loaded into the squadron, the squadrons bombing attacks are
        // adjusted based on the bomb loadout on the fighter.
        if (getGame().getPhase().isLounge() && (loader instanceof FighterSquadron)) {
            ((IBomber) unit).setBombChoices(((FighterSquadron) loader).getExtBombChoices());
            ((FighterSquadron) loader).updateSkills();
            ((FighterSquadron) loader).updateWeaponGroups();
        }

        // Load the unit. Do not check for elevation during deployment
        boolean checkElevation = !getGame().getPhase().isLounge()
                && !getGame().getPhase().isDeployment();
        try {
            loader.load(unit, checkElevation, bayNumber);
        } catch (IllegalArgumentException e) {
            LogManager.getLogger().info(e.getMessage());
            communicationManager.sendServerChat(e.getMessage());
            return;
        }
        // The loaded unit is being carried by the loader.
        unit.setTransportId(loader.getId());

        // Remove the loaded unit from the screen.
        unit.setPosition(null);

        // set deployment round of the loadee to equal that of the loader
        unit.setDeployRound(loader.getDeployRound());

        // Update the loading unit's passenger count, if it's a large craft
        if ((loader instanceof SmallCraft) || (loader instanceof Jumpship)) {
            // Don't add DropShip crew to a JumpShip or station's passenger list
            if (!unit.isLargeCraft()) {
                loader.setNPassenger(loader.getNPassenger() + unit.getCrew().getSize());
            }
        }

        // Update the loaded unit.
        entityUpdate(unit.getId());
        entityUpdate(loader.getId());
    }

    /**
     * Have the loader tow the indicated unit. The unit being towed loses its
     * turn.
     *
     * @param loader - the <code>Entity</code> that is towing the unit.
     * @param unit   - the <code>Entity</code> being towed.
     */
    protected void towUnit(Entity loader, Entity unit) {
        if (!getGame().getPhase().isLounge() && !unit.isDone()) {
            // Remove the *last* friendly turn (removing the *first* penalizes
            // the opponent too much, and re-calculating moves is too hard).
            game.removeTurnFor(unit);
            communicationManager.send(packetManager.createTurnVectorPacket(this));
        }

        loader.towUnit(unit.getId());

        // set deployment round of the loadee to equal that of the loader
        unit.setDeployRound(loader.getDeployRound());

        // Update the loader and towed units.
        entityUpdate(unit.getId());
        entityUpdate(loader.getId());
    }

    /**
     * Have the tractor drop the indicated trailer. This will also disconnect all
     * trailers that follow the one dropped.
     *
     * @param tractor
     *            - the <code>Entity</code> that is disconnecting the trailer.
     * @param unloaded
     *            - the <code>Targetable</code> unit being unloaded.
     * @param pos
     *            - the <code>Coords</code> for the unloaded unit.
     * @return <code>true</code> if the unit was successfully unloaded,
     *         <code>false</code> if the trailer isn't carried by tractor.
     */
    protected boolean disconnectUnit(Entity tractor, Targetable unloaded, Coords pos) {
        // We can only unload Entities.
        Entity trailer;
        if (unloaded instanceof Entity) {
            trailer = (Entity) unloaded;
        } else {
            return false;
        }
        // disconnectUnit() updates anything behind 'trailer' too, so copy
        // the list of trailers before we alter it so entityUpdate() can be
        // run on all of them. Also, add the entity towing Trailer to the list
        List<Integer> trailerList = new ArrayList<>(trailer.getConnectedUnits());
        trailerList.add(trailer.getTowedBy());

        // Unload the unit.
        tractor.disconnectUnit(trailer.getId());

        // Update the tractor and all affected trailers.
        for (int id : trailerList) {
            entityUpdate(id);
        }
        entityUpdate(trailer.getId());
        entityUpdate(tractor.getId());

        // Unloaded successfully.
        return true;
    }

    protected boolean unloadUnit(Entity unloader, Targetable unloaded,
                               Coords pos, int facing, int elevation) {
        return unloadUnit(unloader, unloaded, pos, facing, elevation, false,
                false);
    }

    /**
     * Have the unloader unload the indicated unit. The unit being unloaded may
     * or may not gain a turn
     *
     * @param unloader
     *            - the <code>Entity</code> that is unloading the unit.
     * @param unloaded
     *            - the <code>Targetable</code> unit being unloaded.
     * @param pos
     *            - the <code>Coords</code> for the unloaded unit.
     * @param facing
     *            - the <code>int</code> facing for the unloaded unit.
     * @param elevation
     *            - the <code>int</code> elevation at which to unload, if both
     *            loader and loaded units use VTOL movement.
     * @param evacuation
     *            - a <code>boolean</code> indicating whether this unit is being
     *            unloaded as a result of its carrying units destruction
     * @return <code>true</code> if the unit was successfully unloaded,
     *         <code>false</code> if the unit isn't carried in unloader.
     */
    protected boolean unloadUnit(Entity unloader, Targetable unloaded,
                               Coords pos, int facing, int elevation, boolean evacuation,
                               boolean duringDeployment) {

        // We can only unload Entities.
        Entity unit;
        if (unloaded instanceof Entity) {
            unit = (Entity) unloaded;
        } else {
            return false;
        }

        // Unload the unit.
        if (!unloader.unload(unit)) {
            return false;
        }

        // The unloaded unit is no longer being carried.
        unit.setTransportId(Entity.NONE);

        // Place the unloaded unit onto the screen.
        unit.setPosition(pos);

        // Units unloaded onto the screen are deployed.
        if (pos != null) {
            unit.setDeployed(true);
        }

        // Point the unloaded unit in the given direction.
        unit.setFacing(facing);
        unit.setSecondaryFacing(facing);

        Hex hex = game.getBoard().getHex(pos);
        boolean isBridge = (hex != null)
                && hex.containsTerrain(Terrains.PAVEMENT);

        if (hex == null) {
            unit.setElevation(elevation);
        } else if (unloader.getMovementMode() == EntityMovementMode.VTOL) {
            if (unit.getMovementMode() == EntityMovementMode.VTOL) {
                // Flying units unload to the same elevation as the flying
                // transport
                unit.setElevation(elevation);
            } else if (game.getBoard().getBuildingAt(pos) != null) {
                // non-flying unit unloaded from a flying onto a building
                // -> sit on the roof
                unit.setElevation(hex.terrainLevel(Terrains.BLDG_ELEV));
            } else {
                while (elevation >= -hex.depth()) {
                    if (unit.isElevationValid(elevation, hex)) {
                        unit.setElevation(elevation);
                        break;
                    }
                    elevation--;
                    // If unit is landed, the while loop breaks before here
                    // And unit.moved will be MOVE_NONE
                    // If we can jump, use jump
                    if (unit.getJumpMP() > 0) {
                        unit.moved = EntityMovementType.MOVE_JUMP;
                    } else { // Otherwise, use walk trigger check for ziplines
                        unit.moved = EntityMovementType.MOVE_WALK;
                    }
                }
                if (!unit.isElevationValid(elevation, hex)) {
                    return false;
                }
            }
        } else if (game.getBoard().getBuildingAt(pos) != null) {
            // non flying unit unloading units into a building
            // -> sit in the building at the same elevation
            unit.setElevation(elevation);
        } else if (hex.terrainLevel(Terrains.WATER) > 0) {
            if ((unit.getMovementMode() == EntityMovementMode.HOVER)
                    || (unit.getMovementMode() == EntityMovementMode.WIGE)
                    || (unit.getMovementMode() == EntityMovementMode.HYDROFOIL)
                    || (unit.getMovementMode() == EntityMovementMode.NAVAL)
                    || (unit.getMovementMode() == EntityMovementMode.SUBMARINE)
                    || (unit.getMovementMode() == EntityMovementMode.INF_UMU)
                    || hex.containsTerrain(Terrains.ICE) || isBridge) {
                // units that can float stay on the surface, or we go on the
                // bridge
                // this means elevation 0, because elevation is relative to the
                // surface
                unit.setElevation(0);
            }
        } else {
            // default to the floor of the hex.
            // unit elevation is relative to the surface
            unit.setElevation(hex.floor() - hex.getLevel());
        }

        // Check for zip lines PSR -- MOVE_WALK implies ziplines
        if (unit.moved == EntityMovementType.MOVE_WALK) {
            if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_TACOPS_ZIPLINES)
                    && (unit instanceof Infantry)
                    && !((Infantry) unit).isMechanized()) {

                // Handle zip lines
                PilotingRollData psr = getEjectModifiers(game, unit, 0, false,
                        unit.getPosition(), "Anti-mek skill");
                // Factor in Elevation
                if (unloader.getElevation() > 0) {
                    psr.addModifier(unloader.getElevation(), "elevation");
                }
                Roll diceRoll = Compute.rollD6(2);

                // Report ziplining
                Report r = new Report(9920);
                r.subject = unit.getId();
                r.addDesc(unit);
                r.newlines = 0;
                addReport(r);

                // Report TN
                r = new Report(9921);
                r.subject = unit.getId();
                r.add(psr.getValue());
                r.add(psr.getDesc());
                r.add(diceRoll);
                r.newlines = 0;
                addReport(r);

                if (diceRoll.getIntValue() < psr.getValue()) { // Failure!
                    r = new Report(9923);
                    r.subject = unit.getId();
                    r.add(psr.getValue());
                    r.add(diceRoll);
                    addReport(r);

                    HitData hit = unit.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                    hit.setIgnoreInfantryDoubleDamage(true);
                    reportManager.addReport(damageEntity(unit, hit, 5), this);
                } else { //  Report success
                    r = new Report(9922);
                    r.subject = unit.getId();
                    r.add(psr.getValue());
                    r.add(diceRoll);
                    addReport(r);
                }
                addNewLines();
            } else {
                return false;
            }
        }

        reportManager.addReport(utilityManager.doSetLocationsExposure(unit, hex, false, unit.getElevation(), this), this);

        // unlike other unloaders, entities unloaded from droppers can still
        // move (unless infantry)
        if (!evacuation && (unloader instanceof SmallCraft)
                && !(unit instanceof Infantry)) {
            unit.setUnloaded(false);
            unit.setDone(false);

            // unit uses half of walk mp and is treated as moving one hex
            unit.mpUsed = unit.getOriginalWalkMP() / 2;
            unit.delta_distance = 1;
        }

        // If we unloaded during deployment, allow a turn
        if (duringDeployment) {
            unit.setUnloaded(false);
            unit.setDone(false);
        }

        //Update the transport unit's passenger count, if it's a large craft
        if (unloader instanceof SmallCraft || unloader instanceof Jumpship) {
            //Don't add dropship crew to a jumpship or station's passenger list
            if (!unit.isLargeCraft()) {
                unloader.setNPassenger(Math.max(0, unloader.getNPassenger() - unit.getCrew().getSize()));
            }
        }

        // Update the unloaded unit.
        entityUpdate(unit.getId());

        // Unloaded successfully.
        return true;
    }

    /**
     * Do a piloting skill check to attempt landing
     *
     * @param entity The <code>Entity</code> that is landing
     * @param roll   The <code>PilotingRollData</code> to be used for this landing.
     */
    protected void attemptLanding(Entity entity, PilotingRollData roll) {
        if (roll.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            return;
        }

        // okay, print the info
        Report r = new Report(9605);
        r.subject = entity.getId();
        r.addDesc(entity);
        r.add(roll.getLastPlainDesc(), true);
        addReport(r);

        // roll
        final Roll diceRoll = Compute.rollD6(2);
        r = new Report(9606);
        r.subject = entity.getId();
        r.add(roll.getValueAsString());
        r.add(roll.getDesc());
        r.add(diceRoll);

        // boolean suc;
        if (diceRoll.getIntValue() < roll.getValue()) {
            r.choose(false);
            addReport(r);
            int mof = roll.getValue() - diceRoll.getIntValue();
            int damage = 10 * (mof);
            // Report damage taken
            r = new Report(9609);
            r.indent();
            r.addDesc(entity);
            r.add(damage);
            r.add(mof);
            addReport(r);

            int side = ToHitData.SIDE_FRONT;
            if ((entity instanceof Aero) && ((Aero) entity).isSpheroid()) {
                side = ToHitData.SIDE_REAR;
            }
            while (damage > 0) {
                HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, side);
                reportManager.addReport(damageEntity(entity, hit, 10), this);
                damage -= 10;
            }
            // suc = false;
        } else {
            r.choose(true);
            addReport(r);
            // suc = true;
        }
    }

    /**
     * Checks whether the entity used MASC or a supercharger during movement, and if so checks for
     * and resolves any failures.
     *
     * @param entity  The unit using MASC/supercharger
     * @param md      The current <code>MovePath</code>
     * @return        Whether the unit failed the check
     */
    protected boolean checkMASCFailure(Entity entity, MovePath md) {
        HashMap<Integer, List<CriticalSlot>> crits = new HashMap<>();
        Vector<Report> vReport = new Vector<>();
        if (entity.checkForMASCFailure(md, vReport, crits)) {
            boolean mascFailure = true;
            // Check to see if the pilot can reroll due to Edge
            if (entity.getCrew().hasEdgeRemaining()
                    && entity.getCrew().getOptions()
                    .booleanOption(OptionsConstants.EDGE_WHEN_MASC_FAILS)) {
                entity.getCrew().decreaseEdge();
                // Need to reset the MASCUsed flag
                entity.setMASCUsed(false);
                // Report to notify user that masc check was rerolled
                Report masc_report = new Report(6501);
                masc_report.subject = entity.getId();
                masc_report.indent(2);
                masc_report.addDesc(entity);
                vReport.add(masc_report);
                // Report to notify user how much edge pilot has left
                masc_report = new Report(6510);
                masc_report.subject = entity.getId();
                masc_report.indent(2);
                masc_report.addDesc(entity);
                masc_report.add(entity.getCrew().getOptions()
                        .intOption(OptionsConstants.EDGE));
                vReport.addElement(masc_report);
                // Recheck MASC failure
                if (!entity.checkForMASCFailure(md, vReport, crits)) {
                    // The reroll passed, don't process the failure
                    mascFailure = false;
                    reportManager.addReport(vReport, this);
                }
            }
            // Check for failure and process it
            if (mascFailure) {
                reportManager.addReport(vReport, this);
                ApplyMASCOrSuperchargerCriticals(entity, md, crits);
                return true;
            }
        } else {
            reportManager.addReport(vReport, this);
        }
        return false;
    }

    /**
     * Checks whether the entity used a supercharger during movement, and if so checks for
     * and resolves any failures.
     *
     * @param entity  The unit using MASC/supercharger
     * @param md      The current <code>MovePath</code>
     * @return        Whether the unit failed the check
     */
    protected boolean checkSuperchargerFailure(Entity entity, MovePath md) {
        HashMap<Integer, List<CriticalSlot>> crits = new HashMap<>();
        Vector<Report> vReport = new Vector<>();
        if (entity.checkForSuperchargerFailure(md, vReport, crits)) {
            boolean superchargerFailure = true;
            // Check to see if the pilot can reroll due to Edge
            if (entity.getCrew().hasEdgeRemaining()
                    && entity.getCrew().getOptions()
                    .booleanOption(OptionsConstants.EDGE_WHEN_MASC_FAILS)) {
                entity.getCrew().decreaseEdge();
                // Need to reset the SuperchargerUsed flag
                entity.setSuperchargerUsed(false);
                // Report to notify user that supercharger check was rerolled
                Report supercharger_report = new Report(6501);
                supercharger_report.subject = entity.getId();
                supercharger_report.indent(2);
                supercharger_report.addDesc(entity);
                vReport.add(supercharger_report);
                // Report to notify user how much edge pilot has left
                supercharger_report = new Report(6510);
                supercharger_report.subject = entity.getId();
                supercharger_report.indent(2);
                supercharger_report.addDesc(entity);
                supercharger_report.add(entity.getCrew().getOptions()
                        .intOption(OptionsConstants.EDGE));
                vReport.addElement(supercharger_report);
                // Recheck Supercharger failure
                if (!entity.checkForSuperchargerFailure(md, vReport, crits)) {
                    // The reroll passed, don't process the failure
                    superchargerFailure = false;
                    reportManager.addReport(vReport, this);
                }
            }
            // Check for failure and process it
            if (superchargerFailure) {
                reportManager.addReport(vReport, this);
                // If this is supercharger failure we need to damage the supercharger as well as
                // the additional criticals. For mechs this requires the additional step of finding
                // the slot and marking it as hit so it can't absorb future damage.
                Mounted supercharger = entity.getSuperCharger();
                if ((null != supercharger) && supercharger.curMode().equals("Armed")) {
                    if (entity.hasETypeFlag(Entity.ETYPE_MECH)) {
                        final int loc = supercharger.getLocation();
                        for (int slot = 0; slot < entity.getNumberOfCriticals(loc); slot++) {
                            final CriticalSlot crit = entity.getCritical(loc, slot);
                            if ((null != crit) && (crit.getType() == CriticalSlot.TYPE_EQUIPMENT)
                                    && (crit.getMount().getType().equals(supercharger.getType()))) {
                                reportManager.addReport(applyCriticalHit(entity, loc, crit,
                                        true, 0, false), this);
                                break;
                            }
                        }
                    } else {
                        supercharger.setHit(true);
                    }
                    supercharger.setMode("Off");
                }
                ApplyMASCOrSuperchargerCriticals(entity, md, crits);
                return true;
            }
        } else {
            reportManager.addReport(vReport, this);
        }
        return false;
    }

    protected void ApplyMASCOrSuperchargerCriticals(Entity entity, MovePath md,
                                                  HashMap<Integer, List<CriticalSlot>> crits ) {
        for (Integer loc : crits.keySet()) {
            List<CriticalSlot> lcs = crits.get(loc);
            for (CriticalSlot cs : lcs) {
                // HACK: if loc is -1, we need to deal motive damage to
                // the tank, the severity of which is stored in the critslot index
                if (loc == -1) {
                    reportManager.addReport(vehicleMotiveDamage((Tank) entity,
                            0, true, cs.getIndex()), this);
                } else {
                    reportManager.addReport(applyCriticalHit(entity, loc, cs,
                            true, 0, false), this);
                }
            }
        }
        // do any PSR immediately
        reportManager.addReport(resolvePilotingRolls(entity), this);
        game.resetPSRs(entity);
        // let the player replot their move as MP might be changed
        md.clear();
    }

    /**
     * LAMs or QuadVees converting from leg mode may force any carried infantry (including swarming)
     * to fall into the current hex. A LAM may suffer damage.
     *
     * @param carrier       The <code>Entity</code> making the conversion.
     * @param rider         The <code>Entity</code> possibly being forced off.
     * @param curPos        The coordinates of the hex where the conversion starts.
     * @param curFacing     The carrier's facing when conversion starts.
     * @param automatic     Whether the infantry falls automatically. If false, an anti-mech roll is made
     *                      to see whether it stays mounted.
     * @param infDamage     If true, the infantry takes falling damage, +1D6 for conventional.
     * @param carrierDamage If true, the carrier takes damage from converting while carrying infantry.
     */
    protected Vector<Report> checkDropBAFromConverting(Entity carrier, Entity rider, Coords curPos, int curFacing,
                                                     boolean automatic, boolean infDamage, boolean carrierDamage) {
        Vector<Report> reports = new Vector<>();
        Report r;
        PilotingRollData prd = rider.getBasePilotingRoll(EntityMovementType.MOVE_NONE);
        boolean falls = automatic;
        if (automatic) {
            r = new Report(2465);
            r.subject = rider.getId();
            r.addDesc(rider);
            r.addDesc(carrier);
        } else {
            r = new Report(2460);
            r.subject = rider.getId();
            r.addDesc(rider);
            r.add(prd);
            r.addDesc(carrier);
            final Roll diceRoll = carrier.getCrew().rollPilotingSkill();
            r.add(diceRoll);

            if (diceRoll.getIntValue() < prd.getValue()) {
                r.choose(false);
                falls = true;
            } else {
                r.choose(true);
            }
        }
        reports.add(r);
        if (falls) {
            if (carrier.getSwarmAttackerId() == rider.getId()) {
                rider.setDone(true);
                carrier.setSwarmAttackerId(Entity.NONE);
                rider.setSwarmTargetId(Entity.NONE);
            } else if (!unloadUnit(carrier, rider, curPos, curFacing, 0)) {
                LogManager.getLogger().error("Server was told to unload "
                        + rider.getDisplayName() + " from "
                        + carrier.getDisplayName() + " into "
                        + curPos.getBoardNum());
                return reports;
            }
            if (infDamage) {
                reports.addAll(doEntityFall(rider, curPos, 2, prd));
                if (rider.getEntityType() == Entity.ETYPE_INFANTRY) {
                    int extra = Compute.d6();
                    reports.addAll(damageEntity(rider, new HitData(Infantry.LOC_INFANTRY), extra));
                }
            }
            if (carrierDamage) {
                //Report the possibility of a critical hit.
                r = new Report(2470);
                r.subject = carrier.getId();
                r.addDesc(carrier);
                reports.addElement(r);
                int mod = 0;
                if (rider.getEntityType() == Entity.ETYPE_INFANTRY) {
                    mod = -2;
                }
                HitData hit = carrier.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                reports.addAll(criticalEntity(carrier, hit.getLocation(), false, mod, 0));
            }
        }
        return reports;
    }

    /**
     * If an aero unit takes off in the same turn that other units loaded, then
     * it risks damage to itself and those units
     *
     * @param a - The <code>Aero</code> taking off
     */
    protected void checkForTakeoffDamage(IAero a) {
        boolean unsecured = false;
        for (Entity loaded : ((Entity) a).getLoadedUnits()) {
            if (loaded.wasLoadedThisTurn() && !(loaded instanceof Infantry)) {
                unsecured = true;
                // uh-oh, you forgot your seat belt
                Report r = new Report(6800);
                r.subject = loaded.getId();
                r.addDesc(loaded);
                addReport(r);
                int damage = 25;
                ToHitData toHit = new ToHitData();
                while (damage > 0) {
                    HitData hit = loaded.rollHitLocation(toHit.getHitTable(), ToHitData.SIDE_FRONT);
                    reportManager.addReport(damageEntity(loaded, hit, 5, false,
                            DamageType.NONE, false, true, false), this);
                    damage -= 5;
                }
            }
        }

        if (unsecured) {
            // roll hit location to get a new critical
            HitData hit = ((Entity) a).rollHitLocation(ToHitData.HIT_ABOVE, ToHitData.SIDE_FRONT);
            reportManager.addReport(applyCriticalHit((Entity) a, hit.getLocation(), new CriticalSlot(
                    0, ((Aero) a).getPotCrit()), true, 1, false), this);
        }

    }

    /**
     * Checks to see if an entity sets off any vibrabombs.
     */
    protected boolean checkVibrabombs(Entity entity, Coords coords, boolean displaced,
                                    Vector<Report> vMineReport) {
        return checkVibrabombs(entity, coords, displaced, null, null, vMineReport);
    }

    /**
     * Checks to see if an entity sets off any vibrabombs.
     */
    protected boolean checkVibrabombs(Entity entity, Coords coords, boolean displaced, Coords lastPos,
                                    Coords curPos, Vector<Report> vMineReport) {
        int mass = (int) entity.getWeight();

        // Check for Mine sweepers
        Mounted minesweeper = null;
        for (Mounted m : entity.getMisc()) {
            if (m.getType().hasFlag(MiscType.F_MINESWEEPER) && m.isReady() && (m.getArmorValue() > 0)) {
                minesweeper = m;
                break; // Can only have one minesweeper
            }
        }

        // Check for minesweepers sweeping VB minefields
        if (minesweeper != null) {
            Vector<Minefield> fieldsToRemove = new Vector<>();
            for (Minefield mf : game.getVibrabombs()) {
                // Ignore mines if they aren't in this position
                if (!mf.getCoords().equals(coords)) {
                    continue;
                }

                // Minesweepers on units within 9 tons of the vibrafield setting
                // automatically clear the minefield
                if (Math.abs(mass - mf.getSetting()) < 10) {
                    // Clear the minefield
                    Report r = new Report(2158);
                    r.subject = entity.getId();
                    r.add(entity.getShortName(), true);
                    r.add(Minefield.getDisplayableName(mf.getType()), true);
                    r.add(mf.getCoords().getBoardNum(), true);
                    r.indent();
                    vMineReport.add(r);
                    fieldsToRemove.add(mf);

                    // Handle armor value damage
                    int remainingAV = minesweeper.getArmorValue() - 10;
                    minesweeper.setArmorValue(Math.max(remainingAV, 0));

                    r = new Report(2161);
                    r.indent(2);
                    r.subject = entity.getId();
                    r.add(entity.getShortName(), true);
                    r.add(10);
                    r.add(Math.max(remainingAV, 0));
                    vMineReport.add(r);

                    if (remainingAV <= 0) {
                        minesweeper.setDestroyed(true);
                    }
                    // Check for damage transfer
                    if (remainingAV < 0) {
                        int damage = Math.abs(remainingAV);
                        r = new Report(2162);
                        r.indent(2);
                        r.subject = entity.getId();
                        r.add(damage, true);
                        vMineReport.add(r);

                        // Damage is dealt to the location of minesweeper
                        HitData hit = new HitData(minesweeper.getLocation());
                        Vector<Report> damageReports = damageEntity(entity, hit, damage);
                        for (Report r1 : damageReports) {
                            r1.indent(1);
                        }
                        vMineReport.addAll(damageReports);
                        entity.applyDamage();
                    }
                    Report.addNewline(vMineReport);
                }
            }
            for (Minefield mf : fieldsToRemove) {
                environmentalEffectManager.removeMinefield(mf, this);
            }
        }

        boolean boom = false;
        // Only mechs can set off vibrabombs. QuadVees should only be able to set off a
        // vibrabomb in Mech mode. Those that are converting to or from Mech mode should
        // are using leg movement and should be able to set them off.
        if (!(entity instanceof Mech) || (entity instanceof QuadVee
                && (entity.getConversionMode() == QuadVee.CONV_MODE_VEHICLE)
                && !entity.isConvertingNow())) {
            return false;
        }

        Enumeration<Minefield> e = game.getVibrabombs().elements();

        while (e.hasMoreElements()) {
            Minefield mf = e.nextElement();

            // Bug 954272: Mines shouldn't work underwater, and BMRr says
            // Vibrabombs are mines
            if (game.getBoard().getHex(mf.getCoords()).containsTerrain(Terrains.WATER)
                    && !game.getBoard().getHex(mf.getCoords()).containsTerrain(Terrains.PAVEMENT)
                    && !game.getBoard().getHex(mf.getCoords()).containsTerrain(Terrains.ICE)) {
                continue;
            }

            // Mech weighing 10 tons or less can't set off the bomb
            if (mass <= (mf.getSetting() - 10)) {
                continue;
            }

            int effectiveDistance = (mass - mf.getSetting()) / 10;
            int actualDistance = coords.distance(mf.getCoords());

            if (actualDistance <= effectiveDistance) {
                Report r = new Report(2156);
                r.subject = entity.getId();
                r.add(entity.getShortName(), true);
                r.add(mf.getCoords().getBoardNum(), true);
                vMineReport.add(r);

                // if the moving entity is not actually moving into the vibrabomb
                // hex, it won't get damaged
                Integer excludeEntityID = null;
                if (!coords.equals(mf.getCoords())) {
                    excludeEntityID = entity.getId();
                }

                utilityManager.explodeVibrabomb(mf, vMineReport, excludeEntityID, this);
            }

            // Hack; when moving, the Mech isn't in the hex during
            // the movement.
            if (!displaced && (actualDistance == 0)) {
                // report getting hit by vibrabomb
                Report r = new Report(2160);
                r.subject = entity.getId();
                r.add(entity.getShortName(), true);
                vMineReport.add(r);
                int damage = mf.getDensity();
                while (damage > 0) {
                    int cur_damage = Math.min(5, damage);
                    damage = damage - cur_damage;
                    HitData hit = entity.rollHitLocation(Minefield.TO_HIT_TABLE, Minefield.TO_HIT_SIDE);
                    vMineReport.addAll(damageEntity(entity, hit, cur_damage));
                }
                vMineReport.addAll(resolvePilotingRolls(entity, true, lastPos, curPos));
                // we need to apply Damage now, in case the entity lost a leg,
                // otherwise it won't get a leg missing mod if it hasn't yet
                // moved and lost a leg, see bug 1071434 for an example
                entity.applyDamage();
            }

            // don't check for reduction until the end or units in the same hex
            // through
            // movement will get the reduced damage
            if (mf.hasDetonated()) {
                boom = true;
                mf.checkReduction(0, true);
                revealMinefield(mf);
            }

        }
        return boom;
    }

    /**
     * Reveals a minefield for all players.
     *
     * @param mf The <code>Minefield</code> to be revealed
     */
    protected void revealMinefield(Minefield mf) {
        game.getTeams().forEach(team -> environmentalEffectManager.revealMinefield(team, mf, this));
    }


    /**
     * Called during the weapons fire phase. Resolves anything other than
     * weapons fire that happens. Torso twists, for example.
     */
    void resolveAllButWeaponAttacks() {
        Vector<EntityAction> triggerPodActions = new Vector<>();
        // loop through actions and handle everything we expect except attacks
        for (Enumeration<EntityAction> i = game.getActions(); i.hasMoreElements(); ) {
            EntityAction ea = i.nextElement();
            Entity entity = game.getEntity(ea.getEntityId());
            if (ea instanceof TorsoTwistAction) {
                TorsoTwistAction tta = (TorsoTwistAction) ea;
                if (entity.canChangeSecondaryFacing()) {
                    entity.setSecondaryFacing(tta.getFacing());
                    entity.postProcessFacingChange();
                }
            } else if (ea instanceof FlipArmsAction) {
                FlipArmsAction faa = (FlipArmsAction) ea;
                entity.setArmsFlipped(faa.getIsFlipped());
            } else if (ea instanceof FindClubAction) {
                combatManager.resolveFindClub(entity, this);
            } else if (ea instanceof UnjamAction) {
                combatManager.resolveUnjam(entity, this);
            } else if (ea instanceof ClearMinefieldAction) {
                combatManager.resolveClearMinefield(entity, ((ClearMinefieldAction) ea).getMinefield(), this);
            } else if (ea instanceof TriggerAPPodAction) {
                TriggerAPPodAction tapa = (TriggerAPPodAction) ea;

                // Don't trigger the same pod twice.
                if (!triggerPodActions.contains(tapa)) {
                    combatManager.triggerAPPod(entity, tapa.getPodId(), this);
                    triggerPodActions.addElement(tapa);
                } else {
                    LogManager.getLogger().error("AP Pod #" + tapa.getPodId() + " on "
                            + entity.getDisplayName() + " was already triggered this round!!");
                }
            } else if (ea instanceof TriggerBPodAction) {
                TriggerBPodAction tba = (TriggerBPodAction) ea;

                // Don't trigger the same pod twice.
                if (!triggerPodActions.contains(tba)) {
                    combatManager.triggerBPod(entity, tba.getPodId(), game.getEntity(tba.getTargetId()), this);
                    triggerPodActions.addElement(tba);
                } else {
                    LogManager.getLogger().error("B Pod #" + tba.getPodId() + " on "
                            + entity.getDisplayName() + " was already triggered this round!!");
                }
            } else if (ea instanceof SearchlightAttackAction) {
                SearchlightAttackAction saa = (SearchlightAttackAction) ea;
                reportManager.addReport(saa.resolveAction(game), this);
            } else if (ea instanceof UnjamTurretAction) {
                if (entity instanceof Tank) {
                    ((Tank) entity).unjamTurret(((Tank) entity).getLocTurret());
                    ((Tank) entity).unjamTurret(((Tank) entity).getLocTurret2());
                    Report r = new Report(3033);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    addReport(r);
                } else {
                    LogManager.getLogger().error("Non-Tank tried to unjam turret");
                }
            } else if (ea instanceof RepairWeaponMalfunctionAction) {
                if (entity instanceof Tank) {
                    Mounted m = entity.getEquipment(((RepairWeaponMalfunctionAction) ea).getWeaponId());
                    m.setJammed(false);
                    ((Tank) entity).getJammedWeapons().remove(m);
                    Report r = new Report(3034);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    r.add(m.getName());
                    addReport(r);
                } else {
                    LogManager.getLogger().error("Non-Tank tried to repair weapon malfunction");
                }
            } else if (ea instanceof DisengageAction) {
                MovePath path = new MovePath(game, entity);
                path.addStep(MovePath.MoveStepType.FLEE);
                reportManager.addReport(entityActionManager.processLeaveMap(path, false, -1, this), this);
            } else if (ea instanceof ActivateBloodStalkerAction) {
                ActivateBloodStalkerAction bloodStalkerAction = (ActivateBloodStalkerAction) ea;
                Entity target = game.getEntity(bloodStalkerAction.getTargetID());

                if ((entity != null) && (target != null)) {
                    game.getEntity(bloodStalkerAction.getEntityId())
                            .setBloodStalkerTarget(bloodStalkerAction.getTargetID());
                    Report r = new Report(10000);
                    r.subject = entity.getId();
                    r.add(entity.getDisplayName());
                    r.add(target.getDisplayName());
                    addReport(r);
                }
            }
        }
    }

    void reportGhostTargetRolls() {
        // run through an enumeration of deployed game entities. If they have
        // ghost targets, then check the roll
        // and report it
        Report r;
        for (Iterator<Entity> e = game.getEntities(); e.hasNext(); ) {
            Entity ent = e.next();
            if (ent.isDeployed() && ent.hasGhostTargets(false)) {
                r = new Report(3630);
                r.subject = ent.getId();
                r.addDesc(ent);
                // Ghost target mod is +3 per errata
                int target = ent.getCrew().getPiloting() + 3;
                if (ent.hasETypeFlag(Entity.ETYPE_PROTOMECH)) {
                    target = ent.getCrew().getGunnery() + 3;
                }
                r.add(target);
                r.add(ent.getGhostTargetRoll());
                if (ent.getGhostTargetRoll().getIntValue() >= target) {
                    r.choose(true);
                } else {
                    r.choose(false);
                }
                addReport(r);
            }
        }
        addNewLines();
    }

    /**
     * Apply damage to mech for zweihandering (melee attack with both hands) as per pg. 82,
     * Campaign Operations 2nd Printing
     *
     * @param ae the attacking entity
     * @param missed did the attack miss? If so, a PSR is necessary.
     * @param criticalLocations the locations for possible criticals, should be one or both arms
     *                          depending on if it was an unarmed attack (both arms) or a weapon
     *                          attack (the arm with the weapon).
     */
    protected void applyZweihanderSelfDamage(Entity ae, boolean missed, int... criticalLocations) {
        Report r = new Report(4022);
        r.subject = ae.getId();
        r.indent();
        r.addDesc(ae);
        addReport(r);
        for (int location : criticalLocations) {
            reportManager.addReport(criticalEntity(ae, location, false, 0, 1), this);
        }

        if (missed) {
            game.addPSR(new PilotingRollData(ae.getId(), 0, "Zweihander miss"));
        }
    }

    /**
     * End-phase checks for laid explosives; check whether explosives are
     * touched off, or if we should report laying explosives
     */
    protected void checkLayExplosives() {
        // Report continuing explosive work
        for (Entity e : game.getEntitiesVector()) {
            if (!(e instanceof Infantry)) {
                continue;
            }
            Infantry inf = (Infantry) e;
            if (inf.turnsLayingExplosives > 0) {
                Report r = new Report(4271);
                r.subject = inf.getId();
                r.addDesc(inf);
                addReport(r);
            }
        }
        // Check for touched-off explosives
        Vector<Building> updatedBuildings = new Vector<>();
        for (Building.DemolitionCharge charge : explodingCharges) {
            Building bldg = game.getBoard().getBuildingAt(charge.pos);
            if (bldg == null) { // Shouldn't happen...
                continue;
            }
            bldg.removeDemolitionCharge(charge);
            updatedBuildings.add(bldg);
            Report r = new Report(4272, Report.PUBLIC);
            r.add(bldg.getName());
            addReport(r);
            Vector<Report> dmgReports = damageBuilding(bldg, charge.damage, " explodes for ", charge.pos);
            for (Report rep : dmgReports) {
                rep.indent();
                addReport(rep);
            }
        }
        explodingCharges.clear();
        communicationManager.sendChangedBuildings(updatedBuildings, this);
    }

    /**
     * Get the Kick or Push PSR, modified by weight class
     *
     * @param psrEntity The <code>Entity</code> that should make a PSR
     * @param attacker  The attacking <code>Entity></code>
     * @param target    The target <code>Entity</code>
     * @return The <code>PilotingRollData</code>
     */
    protected PilotingRollData getKickPushPSR(Entity psrEntity, Entity attacker,
                                            Entity target, String reason) {
        int mod = 0;
        PilotingRollData psr = new PilotingRollData(psrEntity.getId(), mod, reason);
        if (psrEntity.hasQuirk(OptionsConstants.QUIRK_POS_STABLE)) {
            psr.addModifier(-1, "stable", false);
        }
        if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_TACOPS_PHYSICAL_PSR)) {

            switch (target.getWeightClass()) {
                case EntityWeightClass.WEIGHT_LIGHT:
                    mod = 1;
                    break;
                case EntityWeightClass.WEIGHT_MEDIUM:
                    mod = 0;
                    break;
                case EntityWeightClass.WEIGHT_HEAVY:
                    mod = -1;
                    break;
                case EntityWeightClass.WEIGHT_ASSAULT:
                    mod = -2;
                    break;
            }
            String reportStr;
            if (mod > 0) {
                reportStr = ("weight class modifier +") + mod;
            } else {
                reportStr = ("weight class modifier ") + mod;
            }
            psr.addModifier(mod, reportStr, false);
        }
        return psr;
    }

    /**
     * Each mech sinks the amount of heat appropriate to its current heat
     * capacity.
     */
    protected void resolveHeat() {
        Report r;
        // Heat phase header
        addReport(new Report(5000, Report.PUBLIC));
        for (Iterator<Entity> i = game.getEntities(); i.hasNext(); ) {
            Entity entity = i.next();
            if ((null == entity.getPosition()) && !entity.isAero()) {
                continue;
            }
            Hex entityHex = game.getBoard().getHex(entity.getPosition());

            int hotDogMod = 0;
            if (entity.hasAbility(OptionsConstants.PILOT_HOT_DOG)) {
                hotDogMod = 1;
            }
            if (entity.getTaserInterferenceHeat()) {
                entity.heatBuildup += 5;
            }
            if (entity.hasDamagedRHS() && entity.weaponFired()) {
                entity.heatBuildup += 1;
            }
            if ((entity instanceof Mech) && ((Mech) entity).hasDamagedCoolantSystem() && entity.weaponFired()) {
                entity.heatBuildup += 1;
            }

            int radicalHSBonus = 0;
            Vector<Report> rhsReports = new Vector<>();
            Vector<Report> heatEffectsReports = new Vector<>();
            if (entity.hasActivatedRadicalHS()) {
                if (entity instanceof Mech) {
                    radicalHSBonus = ((Mech) entity).getActiveSinks();
                } else if (entity instanceof Aero) {
                    radicalHSBonus = ((Aero) entity).getHeatSinks();
                } else {
                    LogManager.getLogger().error("Radical heat sinks mounted on non-mech, non-aero Entity!");
                }

                // RHS activation report
                r = new Report(5540);
                r.subject = entity.getId();
                r.indent();
                r.addDesc(entity);
                r.add(radicalHSBonus);
                rhsReports.add(r);

                Roll diceRoll = Compute.rollD6(2);
                entity.setConsecutiveRHSUses(entity.getConsecutiveRHSUses() + 1);
                int targetNumber = ServerHelper.radicalHeatSinkSuccessTarget(entity.getConsecutiveRHSUses());
                boolean rhsFailure = diceRoll.getIntValue() < targetNumber;

                r = new Report(5541);
                r.indent(2);
                r.subject = entity.getId();
                r.add(targetNumber);
                r.add(diceRoll);
                r.choose(rhsFailure);
                rhsReports.add(r);

                if (rhsFailure) {
                    entity.setHasDamagedRHS(true);
                    int loc = Entity.LOC_NONE;
                    for (Mounted m : entity.getEquipment()) {
                        if (m.getType().hasFlag(MiscType.F_RADICAL_HEATSINK)) {
                            loc = m.getLocation();
                            m.setDestroyed(true);
                            break;
                        }
                    }
                    if (loc == Entity.LOC_NONE) {
                        throw new IllegalStateException("Server.resolveHeat(): " +
                                "Could not find Radical Heat Sink mount on unit that used RHS!");
                    }
                    for (int s = 0; s < entity.getNumberOfCriticals(loc); s++) {
                        CriticalSlot slot = entity.getCritical(loc, s);
                        if ((slot.getType() == CriticalSlot.TYPE_EQUIPMENT)
                                && slot.getMount().getType().hasFlag(MiscType.F_RADICAL_HEATSINK)) {
                            slot.setHit(true);
                            break;
                        }
                    }
                }
            }

            if (entity.tracksHeat() && (entityHex != null) && entityHex.containsTerrain(Terrains.FIRE) && (entityHex.getFireTurn() > 0)
                    && (entity.getElevation() <= 1) && (entity.getAltitude() == 0)) {
                int heatToAdd = 5;
                boolean isMekWithHeatDissipatingArmor = (entity instanceof Mech) && ((Mech) entity).hasIntactHeatDissipatingArmor();
                if (isMekWithHeatDissipatingArmor) {
                    heatToAdd /= 2;
                }
                entity.heatFromExternal += heatToAdd;
                r = new Report(5030);
                r.add(heatToAdd);
                r.subject = entity.getId();
                heatEffectsReports.add(r);
                if (isMekWithHeatDissipatingArmor) {
                    r = new Report(5550);
                    heatEffectsReports.add(r);
                }
            }

            // put in ASF heat build-up first because there are few differences
            if (entity instanceof Aero && !(entity instanceof ConvFighter)) {
                ServerHelper.resolveAeroHeat(game, entity, vPhaseReport, rhsReports, radicalHSBonus, hotDogMod, this);
                continue;
            }

            // heat doesn't matter for non-mechs
            if (!(entity instanceof Mech)) {
                entity.heat = 0;
                entity.heatBuildup = 0;
                entity.heatFromExternal = 0;
                entity.coolFromExternal = 0;

                if (entity.infernos.isStillBurning()) {
                    doFlamingDamage(entity, entity.getPosition());
                }
                if (entity.getTaserShutdownRounds() == 0) {
                    entity.setBATaserShutdown(false);
                    if (entity.isShutDown() && !entity.isManualShutdown()
                            && (entity.getTsempEffect() != MMConstants.TSEMP_EFFECT_SHUTDOWN)) {
                        entity.setShutDown(false);
                        r = new Report(5045);
                        r.subject = entity.getId();
                        r.addDesc(entity);
                        heatEffectsReports.add(r);
                    }
                } else if (entity.isBATaserShutdown()) {
                    // if we're shutdown by a BA taser, we might activate again
                    int roll = Compute.d6(2);
                    if (roll >= 8) {
                        entity.setTaserShutdownRounds(0);
                        if (!(entity.isManualShutdown())) {
                            entity.setShutDown(false);
                        }
                        entity.setBATaserShutdown(false);
                    }
                }

                continue;
            }

            // Only Mechs after this point

            // Meks gain heat from inferno hits.
            if (entity.infernos.isStillBurning()) {
                int infernoHeat = entity.infernos.getHeat();
                entity.heatFromExternal += infernoHeat;
                r = new Report(5010);
                r.subject = entity.getId();
                r.add(infernoHeat);
                heatEffectsReports.add(r);
            }

            // should we even bother for this mech?
            if (entity.isDestroyed() || entity.isDoomed() || entity.getCrew().isDoomed()
                    || entity.getCrew().isDead()) {
                continue;
            }

            // engine hits add a lot of heat, provided the engine is on
            entity.heatBuildup += entity.getEngineCritHeat();

            // If a Mek had an active Stealth suite, add 10 heat.
            if (entity.isStealthOn()) {
                entity.heatBuildup += 10;
                r = new Report(5015);
                r.subject = entity.getId();
                heatEffectsReports.add(r);
            }

            // Greg: Nova CEWS If a Mek had an active Nova suite, add 2 heat.
            if (entity.hasActiveNovaCEWS()) {
                entity.heatBuildup += 2;
                r = new Report(5013);
                r.subject = entity.getId();
                heatEffectsReports.add(r);
            }

            // void sig adds 10 heat
            if (entity.isVoidSigOn()) {
                entity.heatBuildup += 10;
                r = new Report(5016);
                r.subject = entity.getId();
                heatEffectsReports.add(r);
            }

            // null sig adds 10 heat
            if (entity.isNullSigOn()) {
                entity.heatBuildup += 10;
                r = new Report(5017);
                r.subject = entity.getId();
                heatEffectsReports.add(r);
            }

            // chameleon polarization field adds 6
            if (entity.isChameleonShieldOn()) {
                entity.heatBuildup += 6;
                r = new Report(5014);
                r.subject = entity.getId();
                heatEffectsReports.add(r);
            }

            // If a Mek is in extreme Temperatures, add or subtract one
            // heat per 10 degrees (or fraction of 10 degrees) above or
            // below 50 or -30 degrees Celsius
            ServerHelper.adjustHeatExtremeTemp(game, entity, vPhaseReport);

            // Add +5 Heat if the hex you're in is on fire
            // and was on fire for the full round.
            if (entityHex != null) {
                int magma = entityHex.terrainLevel(Terrains.MAGMA);
                if ((magma > 0) && (entity.getElevation() == 0)) {
                    int heatToAdd = 5 * magma;
                    if (((Mech) entity).hasIntactHeatDissipatingArmor()) {
                        heatToAdd /= 2;
                    }
                    entity.heatFromExternal += heatToAdd;
                    r = new Report(5032);
                    r.subject = entity.getId();
                    r.add(heatToAdd);
                    heatEffectsReports.add(r);
                    if (((Mech) entity).hasIntactHeatDissipatingArmor()) {
                        r = new Report(5550);
                        heatEffectsReports.add(r);
                    }
                }
            }

            // Check the mech for vibroblades if so then check to see if any
            // are active and what heat they will produce.
            if (entity.hasVibroblades()) {
                int vibroHeat;

                vibroHeat = entity.getActiveVibrobladeHeat(Mech.LOC_RARM);
                vibroHeat += entity.getActiveVibrobladeHeat(Mech.LOC_LARM);

                if (vibroHeat > 0) {
                    r = new Report(5018);
                    r.subject = entity.getId();
                    r.add(vibroHeat);
                    heatEffectsReports.add(r);
                    entity.heatBuildup += vibroHeat;
                }
            }

            int capHeat = 0;
            for (Mounted m : entity.getEquipment()) {
                if ((m.hasChargedOrChargingCapacitor() == 1) && !m.isUsedThisRound()) {
                    capHeat += 5;
                }
                if ((m.hasChargedOrChargingCapacitor() == 2) && !m.isUsedThisRound()) {
                    capHeat += 10;
                }
            }
            if (capHeat > 0) {
                r = new Report(5019);
                r.subject = entity.getId();
                r.add(capHeat);
                heatEffectsReports.add(r);
                entity.heatBuildup += capHeat;
            }

            // Add heat from external sources to the heat buildup
            int max_ext_heat = game.getOptions().intOption(OptionsConstants.ADVCOMBAT_MAX_EXTERNAL_HEAT);
            // Check Game Options
            if (max_ext_heat < 0) {
                max_ext_heat = 15; // standard value specified in TW p.159
            }
            entity.heatBuildup += Math.min(max_ext_heat, entity.heatFromExternal);
            entity.heatFromExternal = 0;
            // remove heat we cooled down
            entity.heatBuildup -= Math.min(9, entity.coolFromExternal);
            entity.coolFromExternal = 0;

            // Combat computers help manage heat
            if (entity.hasQuirk(OptionsConstants.QUIRK_POS_COMBAT_COMPUTER)) {
                int reduce = Math.min(entity.heatBuildup, 4);
                r = new Report(5026);
                r.subject = entity.getId();
                r.add(reduce);
                heatEffectsReports.add(r);
                entity.heatBuildup -= reduce;
            }

            if (entity.hasQuirk(OptionsConstants.QUIRK_NEG_FLAWED_COOLING)
                    && ((Mech) entity).isCoolingFlawActive()) {
                int flaw = 5;
                r = new Report(5021);
                r.subject = entity.getId();
                r.add(flaw);
                heatEffectsReports.add(r);
                entity.heatBuildup += flaw;
            }
            // if heat build up is negative due to temperature, set it to 0
            // for prettier turn reports
            if (entity.heatBuildup < 0) {
                entity.heatBuildup = 0;
            }

            // add the heat we've built up so far.
            entity.heat += entity.heatBuildup;

            // how much heat can we sink?
            int toSink = entity.getHeatCapacityWithWater() + radicalHSBonus;

            if (entity.getCoolantFailureAmount() > 0) {
                int failureAmount = entity.getCoolantFailureAmount();
                r = new Report(5520);
                r.subject = entity.getId();
                r.add(failureAmount);
                heatEffectsReports.add(r);
                toSink -= failureAmount;
            }

            // should we use a coolant pod?
            int safeHeat = entity.hasInfernoAmmo() ? 9 : 13;
            int possibleSinkage = ((Mech) entity).getNumberOfSinks() - entity.getCoolantFailureAmount();
            for (Mounted m : entity.getEquipment()) {
                if (m.getType() instanceof AmmoType) {
                    AmmoType at = (AmmoType) m.getType();
                    if ((at.getAmmoType() == AmmoType.T_COOLANT_POD) && m.isAmmoUsable()) {
                        EquipmentMode mode = m.curMode();
                        if (mode.equals("dump")) {
                            r = new Report(5260);
                            r.subject = entity.getId();
                            heatEffectsReports.add(r);
                            m.setShotsLeft(0);
                            toSink += possibleSinkage;
                            break;
                        }
                        if (mode.equals("safe") && ((entity.heat - toSink) > safeHeat)) {
                            r = new Report(5265);
                            r.subject = entity.getId();
                            heatEffectsReports.add(r);
                            m.setShotsLeft(0);
                            toSink += possibleSinkage;
                            break;
                        }
                        if (mode.equals("efficient") && ((entity.heat - toSink) >= possibleSinkage)) {
                            r = new Report(5270);
                            r.subject = entity.getId();
                            heatEffectsReports.add(r);
                            m.setShotsLeft(0);
                            toSink += possibleSinkage;
                            break;
                        }
                    }
                }
            }

            toSink = Math.min(toSink, entity.heat);
            entity.heat -= toSink;
            r = new Report(5035);
            r.subject = entity.getId();
            r.addDesc(entity);
            r.add(entity.heatBuildup);
            r.add(toSink);
            Color color = GUIPreferences.getInstance().getColorForHeat(entity.heat, Color.BLACK);
            r.add(r.bold(r.fgColor(color, String.valueOf(entity.heat))));
            addReport(r);
            entity.heatBuildup = 0;
            vPhaseReport.addAll(rhsReports);
            vPhaseReport.addAll(heatEffectsReports);

            // Does the unit have inferno ammo?
            if (entity.hasInfernoAmmo()) {

                // Roll for possible inferno ammo explosion.
                if (entity.heat >= 10) {
                    int boom = (4 + (entity.heat >= 14 ? 2 : 0) + (entity.heat >= 19 ? 2 : 0)
                            + (entity.heat >= 23 ? 2 : 0) + (entity.heat >= 28 ? 2 : 0))
                            - hotDogMod;
                    Roll diceRoll = Compute.rollD6(2);
                    int rollValue = diceRoll.getIntValue();
                    r = new Report(5040);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    r.add(boom);
                    if (entity.getCrew().hasActiveTechOfficer()) {
                        rollValue += 2;
                        String rollCalc = rollValue + " [" + diceRoll.getIntValue() + " + 2]";
                        r.addDataWithTooltip(rollCalc, diceRoll.getReport());
                    } else {
                        r.add(diceRoll);
                    }

                    if (rollValue >= boom) {
                        // avoided
                        r.choose(true);
                        addReport(r);
                    } else {
                        r.choose(false);
                        addReport(r);
                        reportManager.addReport(explodeInfernoAmmoFromHeat(entity), this);
                    }
                }
            } // End avoid-inferno-explosion
            int autoShutDownHeat;
            boolean mtHeat;

            if (game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_HEAT)) {
                autoShutDownHeat = 50;
                mtHeat = true;
            } else {
                autoShutDownHeat = 30;
                mtHeat = false;
            }
            // heat effects: start up
            if ((entity.heat < autoShutDownHeat) && entity.isShutDown() && !entity.isStalled()) {
                if ((entity.getTaserShutdownRounds() == 0)
                        && (entity.getTsempEffect() != MMConstants.TSEMP_EFFECT_SHUTDOWN)) {
                    if ((entity.heat < 14) && !(entity.isManualShutdown())) {
                        // automatically starts up again
                        entity.setShutDown(false);
                        r = new Report(5045);
                        r.subject = entity.getId();
                        r.addDesc(entity);
                        addReport(r);
                    } else if (!(entity.isManualShutdown())) {
                        // If the pilot is KO and we need to roll, auto-fail.
                        if (!entity.getCrew().isActive()) {
                            r = new Report(5049);
                            r.subject = entity.getId();
                            r.addDesc(entity);
                        } else {
                            // roll for startup
                            int startup = (4 + (((entity.heat - 14) / 4) * 2)) - hotDogMod;
                            if (mtHeat) {
                                startup -= 5;
                                switch (entity.getCrew().getPiloting()) {
                                    case 0:
                                    case 1:
                                        startup -= 2;
                                        break;
                                    case 2:
                                    case 3:
                                        startup -= 1;
                                        break;
                                    case 6:
                                    case 7:
                                        startup += 1;
                                }
                            }
                            Roll diceRoll = Compute.rollD6(2);
                            r = new Report(5050);
                            r.subject = entity.getId();
                            r.addDesc(entity);
                            r.add(startup);
                            r.add(diceRoll);

                            if (diceRoll.getIntValue() >= startup) {
                                // start 'er back up
                                entity.setShutDown(false);
                                r.choose(true);
                            } else {
                                r.choose(false);
                            }
                        }
                        addReport(r);
                    }
                } else {
                    // if we're shutdown by a BA taser, we might activate
                    // again
                    if (entity.isBATaserShutdown()) {
                        int roll = Compute.d6(2);
                        if (roll >= 7) {
                            entity.setTaserShutdownRounds(0);
                            if (!(entity.isManualShutdown())) {
                                entity.setShutDown(false);
                            }
                            entity.setBATaserShutdown(false);
                        }
                    }
                }
            }

            // heat effects: shutdown!
            // Don't shut down if you just restarted.
            else if ((entity.heat >= 14) && !entity.isShutDown()) {
                if (entity.heat >= autoShutDownHeat) {
                    r = new Report(5055);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    addReport(r);
                    // add a piloting roll and resolve immediately
                    if (entity.canFall()) {
                        game.addPSR(new PilotingRollData(entity.getId(), 3, "reactor shutdown"));
                        reportManager.addReport((Vector<Report>) resolvePilotingRolls(), this);
                    }
                    // okay, now mark shut down
                    entity.setShutDown(true);
                } else {
                    // Again, pilot KO means shutdown is automatic.
                    if (!entity.getCrew().isActive()) {
                        r = new Report(5056);
                        r.subject = entity.getId();
                        r.addDesc(entity);
                        addReport(r);
                        entity.setShutDown(true);
                    } else {
                        int shutdown = (4 + (((entity.heat - 14) / 4) * 2)) - hotDogMod;
                        if (mtHeat) {
                            shutdown -= 5;
                            switch (entity.getCrew().getPiloting()) {
                                case 0:
                                case 1:
                                    shutdown -= 2;
                                    break;
                                case 2:
                                case 3:
                                    shutdown -= 1;
                                    break;
                                case 6:
                                case 7:
                                    shutdown += 1;
                            }
                        }
                        Roll diceRoll = Compute.rollD6(2);
                        int rollValue = diceRoll.getIntValue();
                        r = new Report(5060);
                        r.subject = entity.getId();
                        r.addDesc(entity);
                        r.add(shutdown);

                        if (entity.getCrew().hasActiveTechOfficer()) {
                            rollValue += 2;
                            String rollCalc = rollValue + " [" + diceRoll.getIntValue() + "]";
                            r.addDataWithTooltip(rollCalc, diceRoll.getReport());
                        } else {
                            r.add(diceRoll);
                        }
                        if (rollValue >= shutdown) {
                            // avoided
                            r.choose(true);
                            addReport(r);
                        } else {
                            // shutting down...
                            r.choose(false);
                            addReport(r);
                            // add a piloting roll and resolve immediately
                            if (entity.canFall()) {
                                game.addPSR(new PilotingRollData(entity.getId(), 3, "reactor shutdown"));
                                reportManager.addReport((Vector<Report>) resolvePilotingRolls(), this);
                            }
                            // okay, now mark shut down
                            entity.setShutDown(true);
                        }
                    }
                }
            }

            // LAMs in fighter mode need to check for random movement due to heat
            checkRandomAeroMovement(entity, hotDogMod);

            // heat effects: ammo explosion!
            if (entity.heat >= 19) {
                int boom = (4 + (entity.heat >= 23 ? 2 : 0) + (entity.heat >= 28 ? 2 : 0))
                        - hotDogMod;
                if (mtHeat) {
                    boom += (entity.heat >= 35 ? 2 : 0)
                            + (entity.heat >= 40 ? 2 : 0)
                            + (entity.heat >= 45 ? 2 : 0);
                    // Last line is a crutch; 45 heat should be no roll
                    // but automatic explosion.
                }
                if (((Mech) entity).hasLaserHeatSinks()) {
                    boom--;
                }
                Roll diceRoll = Compute.rollD6(2);
                int rollValue = diceRoll.getIntValue();
                r = new Report(5065);
                r.subject = entity.getId();
                r.addDesc(entity);
                r.add(boom);
                if (entity.getCrew().hasActiveTechOfficer()) {
                    rollValue += 2;
                    String rollCalc = rollValue + " [" + diceRoll.getIntValue() + " + 2]";;
                    r.addDataWithTooltip(rollCalc, diceRoll.getReport());
                } else {
                    r.add(diceRoll);
                }
                if (rollValue >= boom) {
                    // mech is ok
                    r.choose(true);
                    addReport(r);
                } else {
                    // boom!
                    r.choose(false);
                    addReport(r);
                    reportManager.addReport(explodeAmmoFromHeat(entity), this);
                }
            }

            // heat effects: mechwarrior damage
            // N.B. The pilot may already be dead.
            int lifeSupportCritCount;
            boolean torsoMountedCockpit = ((Mech) entity).getCockpitType() == Mech.COCKPIT_TORSO_MOUNTED;
            if (torsoMountedCockpit) {
                lifeSupportCritCount = entity.getHitCriticals(CriticalSlot.TYPE_SYSTEM,
                        Mech.SYSTEM_LIFE_SUPPORT, Mech.LOC_RT);
                lifeSupportCritCount += entity.getHitCriticals(CriticalSlot.TYPE_SYSTEM,
                        Mech.SYSTEM_LIFE_SUPPORT, Mech.LOC_LT);
            } else {
                lifeSupportCritCount = entity.getHitCriticals(CriticalSlot.TYPE_SYSTEM,
                        Mech.SYSTEM_LIFE_SUPPORT, Mech.LOC_HEAD);
            }
            int damageHeat = entity.heat;
            if (entity.hasQuirk(OptionsConstants.QUIRK_POS_IMP_LIFE_SUPPORT)) {
                damageHeat -= 5;
            }
            if (entity.hasQuirk(OptionsConstants.QUIRK_NEG_POOR_LIFE_SUPPORT)) {
                damageHeat += 5;
            }
            if ((lifeSupportCritCount > 0)
                    && ((damageHeat >= 15) || (torsoMountedCockpit && (damageHeat > 0)))
                    && !entity.getCrew().isDead() && !entity.getCrew().isDoomed()
                    && !entity.getCrew().isEjected()) {
                int heatLimitDesc = 1;
                int damageToCrew = 0;
                if ((damageHeat >= 47) && mtHeat) {
                    // mechwarrior takes 5 damage
                    heatLimitDesc = 47;
                    damageToCrew = 5;
                } else if ((damageHeat >= 39) && mtHeat) {
                    // mechwarrior takes 4 damage
                    heatLimitDesc = 39;
                    damageToCrew = 4;
                } else if ((damageHeat >= 32) && mtHeat) {
                    // mechwarrior takes 3 damage
                    heatLimitDesc = 32;
                    damageToCrew = 3;
                } else if (damageHeat >= 25) {
                    // mechwarrior takes 2 damage
                    heatLimitDesc = 25;
                    damageToCrew = 2;
                } else if (damageHeat >= 15) {
                    // mechwarrior takes 1 damage
                    heatLimitDesc = 15;
                    damageToCrew = 1;
                }
                if ((((Mech) entity).getCockpitType() == Mech.COCKPIT_TORSO_MOUNTED)
                        && !entity.hasAbility(OptionsConstants.MD_PAIN_SHUNT)) {
                    damageToCrew += 1;
                }
                r = new Report(5070);
                r.subject = entity.getId();
                r.addDesc(entity);
                r.add(heatLimitDesc);
                r.add(damageToCrew);
                addReport(r);
                reportManager.addReport(damageCrew(entity, damageToCrew), this);
            } else if (mtHeat && (entity.heat >= 32) && !entity.getCrew().isDead()
                    && !entity.getCrew().isDoomed()
                    && !entity.hasAbility(OptionsConstants.MD_PAIN_SHUNT)) {
                // Crew may take damage from heat if MaxTech option is set
                Roll diceRoll = Compute.rollD6(2);
                int avoidNumber;
                if (entity.heat >= 47) {
                    avoidNumber = 12;
                } else if (entity.heat >= 39) {
                    avoidNumber = 10;
                } else {
                    avoidNumber = 8;
                }
                avoidNumber -= hotDogMod;
                r = new Report(5075);
                r.subject = entity.getId();
                r.addDesc(entity);
                r.add(avoidNumber);
                r.add(diceRoll);

                if (diceRoll.getIntValue() >= avoidNumber) {
                    // damage avoided
                    r.choose(true);
                    addReport(r);
                } else {
                    r.choose(false);
                    addReport(r);
                    reportManager.addReport(damageCrew(entity, 1), this);
                }
            }

            // The pilot may have just expired.
            if ((entity.getCrew().isDead() || entity.getCrew().isDoomed())
                    && !entity.getCrew().isEjected()) {
                r = new Report(5080);
                r.subject = entity.getId();
                r.addDesc(entity);
                addReport(r);
                reportManager.addReport(entityActionManager.destroyEntity(entity, "crew death", true, this), this);
            }

            // With MaxTech Heat Scale, there may occur critical damage
            if (mtHeat) {
                if (entity.heat >= 36) {
                    Roll diceRoll = Compute.rollD6(2);
                    int damageNumber;
                    if (entity.heat >= 44) {
                        damageNumber = 10;
                    } else {
                        damageNumber = 8;
                    }
                    damageNumber -= hotDogMod;
                    r = new Report(5085);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    r.add(damageNumber);
                    r.add(diceRoll);
                    r.newlines = 0;

                    if (diceRoll.getIntValue() >= damageNumber) {
                        r.choose(true);
                    } else {
                        r.choose(false);
                        addReport(r);
                        reportManager.addReport(oneCriticalEntity(entity, Compute.randomInt(8), false, 0), this);
                        // add an empty report, for line breaking
                        r = new Report(1210, Report.PUBLIC);
                    }
                    addReport(r);
                }
            }

            if (game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_COOLANT_FAILURE)
                    && (entity.getHeatCapacity() > entity.getCoolantFailureAmount())
                    && (entity.heat >= 5)) {
                Roll diceRoll = Compute.rollD6(2);
                int hitNumber = 10;
                hitNumber -= Math.max(0, (int) Math.ceil(entity.heat / 5.0) - 2);
                r = new Report(5525);
                r.subject = entity.getId();
                r.add(entity.getShortName());
                r.add(hitNumber);
                r.add(diceRoll);
                r.newlines = 0;
                addReport(r);

                if (diceRoll.getIntValue() >= hitNumber) {
                    r = new Report(5052);
                    r.subject = entity.getId();
                    addReport(r);
                    r = new Report(5526);
                    r.subject = entity.getId();
                    r.add(entity.getShortNameRaw());
                    addReport(r);
                    entity.addCoolantFailureAmount(1);
                } else {
                    r = new Report(5041);
                    r.subject = entity.getId();
                    addReport(r);
                }
            }
        }

        if (vPhaseReport.size() == 1) {
            // I guess nothing happened...
            addReport(new Report(1205, Report.PUBLIC));
        }
    }

    public void checkRandomAeroMovement(Entity entity, int hotDogMod) {
        if (!entity.isAero()) {
            return;
        }
        IAero a = (IAero) entity;
        // heat effects: control effects (must make it unless already random moving)
        if ((entity.heat >= 5) && !a.isRandomMove()) {
            int controlAvoid = (5 + (entity.heat >= 10 ? 1 : 0) + (entity.heat >= 15 ? 1 : 0)
                    + (entity.heat >= 20 ? 1 : 0) + (entity.heat >= 25 ? 2 : 0)) - hotDogMod;
            Roll diceRoll = Compute.rollD6(2);
            Report r = new Report(9210);
            r.subject = entity.getId();
            r.addDesc(entity);
            r.add(controlAvoid);
            r.add(diceRoll);

            if (diceRoll.getIntValue() >= controlAvoid) {
                // in control
                r.choose(true);
                addReport(r);
            } else {
                // out of control
                r.choose(false);
                addReport(r);
                // if not already out of control, this may lead to
                // elevation decline
                if (!a.isOutControl() && !a.isSpaceborne()
                        && a.isAirborne()) {
                    Roll diceRoll2 = Compute.rollD6(1);
                    r = new Report(9366);
                    r.newlines = 0;
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    r.add(diceRoll2);
                    addReport(r);
                    entity.setAltitude(entity.getAltitude() - diceRoll2.getIntValue());
                    // check for crash
                    if (entityActionManager.checkCrash(entity, entity.getPosition(), entity.getAltitude(), this)) {
                        reportManager.addReport(entityActionManager.processCrash(entity, a.getCurrentVelocity(), entity.getPosition(), this), this);
                    }
                }
                // force unit out of control through heat
                a.setOutCtrlHeat(true);
                a.setRandomMove(true);
            }
        }
    }

    protected void resolveEmergencyCoolantSystem() {
        for (Entity e : game.getEntitiesVector()) {
            if ((e instanceof Mech) && e.hasWorkingMisc(MiscType.F_EMERGENCY_COOLANT_SYSTEM)
                    && (e.heat > 13)) {
                Mech mech = (Mech) e;
                Vector<Report> vDesc = new Vector<>();
                HashMap<Integer, List<CriticalSlot>> crits = new HashMap<>();
                if (!(mech.doRISCEmergencyCoolantCheckFor(vDesc, crits))) {
                    mech.heat -= 6 + mech.getCoolantSystemMOS();
                    Report r = new Report(5027);
                    r.add(6+mech.getCoolantSystemMOS());
                    vDesc.add(r);
                }
                reportManager.addReport(vDesc, this);
                for (Integer loc : crits.keySet()) {
                    List<CriticalSlot> lcs = crits.get(loc);
                    for (CriticalSlot cs : lcs) {
                        reportManager.addReport(applyCriticalHit(mech, loc, cs, true, 0, false), this);
                    }
                }
            }
        }
    }

    /*
     * Resolve HarJel II/III repairs for Mechs so equipped.
     */
    protected void resolveHarJelRepairs() {
        Report r;
        for (Iterator<Entity> i = game.getEntities(); i.hasNext(); ) {
            Entity entity = i.next();
            if (!(entity instanceof Mech)) {
                continue;
            }

            Mech me = (Mech) entity;
            for (int loc = 0; loc < me.locations(); ++loc) {
                boolean harJelII = me.hasHarJelIIIn(loc); // false implies HarJel III
                if ((harJelII || me.hasHarJelIIIIn(loc))
                        && me.isArmorDamagedThisTurn(loc)) {
                    if (me.hasRearArmor(loc)) {
                        // must have at least one remaining armor in location
                        if (!((me.getArmor(loc) > 0) || (me.getArmor(loc, true) > 0))) {
                            continue;
                        }

                        int toRepair = harJelII ? 2 : 4;
                        int frontRepair, rearRepair;
                        int desiredFrontRepair, desiredRearRepair;

                        Mounted harJel = null;
                        // find HarJel item
                        // don't need to check ready or worry about null,
                        // we already know there is one, it's ready,
                        // and there can be at most one in a given location
                        for (Mounted m: me.getMisc()) {
                            if ((m.getLocation() == loc)
                                    && (m.getType().hasFlag(MiscType.F_HARJEL_II)
                                    || m.getType().hasFlag(MiscType.F_HARJEL_III))) {
                                harJel = m;
                            }
                        }

                        if (harJelII) {
                            if (harJel.curMode().equals(MiscType.S_HARJEL_II_1F1R)) {
                                desiredFrontRepair = 1;
                            } else if (harJel.curMode().equals(MiscType.S_HARJEL_II_2F0R)) {
                                desiredFrontRepair = 2;
                            } else { // 0F2R
                                desiredFrontRepair = 0;
                            }
                        } else { // HarJel III
                            if (harJel.curMode().equals(MiscType.S_HARJEL_III_2F2R)) {
                                desiredFrontRepair = 2;
                            } else if (harJel.curMode().equals(MiscType.S_HARJEL_III_4F0R)) {
                                desiredFrontRepair = 4;
                            } else if (harJel.curMode().equals(MiscType.S_HARJEL_III_3F1R)) {
                                desiredFrontRepair = 3;
                            } else if (harJel.curMode().equals(MiscType.S_HARJEL_III_1F3R)) {
                                desiredFrontRepair = 1;
                            } else { // 0F4R
                                desiredFrontRepair = 0;
                            }
                        }
                        desiredRearRepair = toRepair - desiredFrontRepair;

                        int availableFrontRepair = me.getOArmor(loc) - me.getArmor(loc);
                        int availableRearRepair = me.getOArmor(loc, true) - me.getArmor(loc, true);
                        frontRepair = Math.min(availableFrontRepair, desiredFrontRepair);
                        rearRepair = Math.min(availableRearRepair, desiredRearRepair);
                        int surplus = desiredFrontRepair - frontRepair;
                        if (surplus > 0) { // we couldn't use all the points we wanted in front
                            rearRepair = Math.min(availableRearRepair, rearRepair + surplus);
                        } else {
                            surplus = desiredRearRepair - rearRepair;
                            // try to move any excess points from rear to front
                            frontRepair = Math.min(availableFrontRepair, frontRepair + surplus);
                        }

                        if (frontRepair > 0) {
                            me.setArmor(me.getArmor(loc) + frontRepair, loc);
                            r = new Report(harJelII ? 9850 : 9851);
                            r.subject = me.getId();
                            r.addDesc(entity);
                            r.add(frontRepair);
                            r.add(me.getLocationAbbr(loc));
                            addReport(r);
                        }
                        if (rearRepair > 0) {
                            me.setArmor(me.getArmor(loc, true) + rearRepair, loc, true);
                            r = new Report(harJelII ? 9850 : 9851);
                            r.subject = me.getId();
                            r.addDesc(entity);
                            r.add(rearRepair);
                            r.add(me.getLocationAbbr(loc) + " (R)");
                            addReport(r);
                        }
                    } else {
                        // must have at least one remaining armor in location
                        if (!(me.getArmor(loc) > 0)) {
                            continue;
                        }
                        int toRepair = harJelII ? 2 : 4;
                        toRepair = Math.min(toRepair, me.getOArmor(loc) - me.getArmor(loc));
                        me.setArmor(me.getArmor(loc) + toRepair, loc);
                        r = new Report(harJelII ? 9850 : 9851);
                        r.subject = me.getId();
                        r.addDesc(entity);
                        r.add(toRepair);
                        r.add(me.getLocationAbbr(loc));
                        addReport(r);
                    }
                }
            }
        }
    }

    /**
     * Resolve Flaming Damage for the given Entity Taharqa: This is now updated
     * to TacOps rules which is much more lenient So I have change the name to
     * Flaming Damage rather than flaming death
     *
     * @param entity The <code>Entity</code> that may experience flaming damage.
     * @param coordinates the coordinate location of the fire
     */
    protected void doFlamingDamage(final Entity entity, final Coords coordinates) {
        // TO:AR p.41,p.43
        if (entity.tracksHeat() || entity.isDropShip()) {
            return;
        }

        Report r;
        Roll diceRoll = Compute.rollD6(2);

        if ((entity.getMovementMode() == EntityMovementMode.VTOL) && !entity.infernos.isStillBurning()) {
            // VTOLs don't check as long as they are flying higher than
            // the burning terrain. TODO : Check for rules conformity (ATPM?)
            // according to maxtech, elevation 0 or 1 should be affected,
            // this makes sense for level 2 as well
            if (entity.getElevation() > 1) {
                return;
            }
        }
        // Battle Armor squads equipped with fire protection
        // gear automatically avoid flaming damage
        // TODO : can conventional infantry mount fire-resistant armor?
        if ((entity instanceof BattleArmor) && ((BattleArmor) entity).isFireResistant()) {
            r = new Report(5095);
            r.subject = entity.getId();
            r.indent(1);
            r.addDesc(entity);
            addReport(r);
            return;
        }

        // Must roll 8+ to survive...
        r = new Report(5100);
        r.subject = entity.getId();
        r.newlines = 0;
        r.addDesc(entity);
        r.add(coordinates.getBoardNum());
        r.add(diceRoll);

        if (diceRoll.getIntValue() >= 8) {
            // phew!
            r.choose(true);
            addReport(r);
            Report.addNewline(vPhaseReport);
        } else {
            // eek
            r.choose(false);
            r.newlines = 1;
            addReport(r);
            // gun emplacements have their own critical rules
            if (entity instanceof GunEmplacement) {
                Vector<GunEmplacement> gun = new Vector<>();
                gun.add((GunEmplacement) entity);

                Building building = getGame().getBoard().getBuildingAt(entity.getPosition());

                Report.addNewline(vPhaseReport);
                reportManager.addReport(criticalGunEmplacement(gun, building, entity.getPosition()), this);
                // Taharqa: TacOps rules, protos and vees no longer die instantly
                // (hurray!)
            } else if (entity instanceof Tank) {
                int bonus = -2;
                if ((entity instanceof SupportTank) || (entity instanceof SupportVTOL)) {
                    bonus = 0;
                }
                // roll a critical hit
                Report.addNewline(vPhaseReport);
                reportManager.addReport(criticalTank((Tank) entity, Tank.LOC_FRONT, bonus, 0, true), this);
            } else if (entity instanceof Protomech) {
                // this code is taken from inferno hits
                HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                if (hit.getLocation() == Protomech.LOC_NMISS) {
                    Protomech proto = (Protomech) entity;
                    r = new Report(6035);
                    r.subject = entity.getId();
                    r.indent(2);
                    if (proto.isGlider()) {
                        r.messageId = 6036;
                        proto.setWingHits(proto.getWingHits() + 1);
                    }
                    addReport(r);
                } else {
                    r = new Report(6690);
                    r.subject = entity.getId();
                    r.indent(1);
                    r.add(entity.getLocationName(hit));
                    addReport(r);
                    entity.destroyLocation(hit.getLocation());
                    // Handle ProtoMech pilot damage due to location destruction
                    int hits = Protomech.POSSIBLE_PILOT_DAMAGE[hit.getLocation()]
                            - ((Protomech) entity).getPilotDamageTaken(hit.getLocation());
                    if (hits > 0) {
                        reportManager.addReport(damageCrew(entity, hits), this);
                        ((Protomech) entity).setPilotDamageTaken(hit.getLocation(),
                                Protomech.POSSIBLE_PILOT_DAMAGE[hit.getLocation()]);
                    }
                    if (entity.getTransferLocation(hit).getLocation() == Entity.LOC_DESTROYED) {
                        reportManager.addReport(entityActionManager.destroyEntity(entity, "flaming death", false, true, this), this);
                        Report.addNewline(vPhaseReport);
                    }
                }
            } else {
                // sucks to be you
                reportManager.addReport(entityActionManager.destroyEntity(entity, "fire", false, false, this), this);
                Report.addNewline(vPhaseReport);
            }
        }
    }

    protected void clearFlawedCoolingFlags(Entity entity) {
        // If we're not using quirks, no need to do this check.
        if (!game.getOptions().booleanOption(OptionsConstants.ADVANCED_STRATOPS_QUIRKS)) {
            return;
        }
        // Only applies to Mechs.
        if (!(entity instanceof Mech)) {
            return;
        }

        // Check for existence of flawed cooling quirk.
        if (!entity.hasQuirk(OptionsConstants.QUIRK_NEG_FLAWED_COOLING)) {
            return;
        }
        entity.setFallen(false);
        entity.setStruck(false);
    }

    void checkForFlawedCooling() {

        // If we're not using quirks, no need to do this check.
        if (!game.getOptions().booleanOption(OptionsConstants.ADVANCED_STRATOPS_QUIRKS)) {
            return;
        }

        for (Iterator<Entity> i = game.getEntities(); i.hasNext(); ) {
            final Entity entity = i.next();

            // Only applies to Mechs.
            if (!(entity instanceof Mech)) {
                continue;
            }

            // Check for existence of flawed cooling quirk.
            if (!entity.hasQuirk(OptionsConstants.QUIRK_NEG_FLAWED_COOLING)) {
                continue;
            }

            // Check for active Cooling Flaw
            if (((Mech) entity).isCoolingFlawActive()) {
                continue;
            }

            // Perform the check.
            if (entity.damageThisPhase >= 20) {
                reportManager.addReport(doFlawedCoolingCheck("20+ damage", entity), this);
            }
            if (entity.hasFallen()) {
                reportManager.addReport(doFlawedCoolingCheck("fall", entity), this);
            }
            if (entity.wasStruck()) {
                reportManager.addReport(doFlawedCoolingCheck("being struck", entity), this);
            }
            clearFlawedCoolingFlags(entity);
        }
    }

    /**
     * Checks to see if Flawed Cooling is triggered and generates a report of
     * the result.
     *
     * @param reason
     * @param entity
     * @return
     */
    protected Vector<Report> doFlawedCoolingCheck(String reason, Entity entity) {
        Vector<Report> out = new Vector<>();
        Report r = new Report(9800);
        r.addDesc(entity);
        r.add(reason);
        Roll diceRoll = Compute.rollD6(2);
        r.add(diceRoll);
        out.add(r);

        if (diceRoll.getIntValue() >= 10) {
            Report s = new Report(9805);
            ((Mech) entity).setCoolingFlawActive(true);
            out.add(s);
        }

        return out;
    }

    /**
     * For chain whip grapples, a roll needs to be made at the end of the
     * physical phase to maintain the grapple.
     */
    void checkForChainWhipGrappleChecks() {
        for (Entity ae : game.getEntitiesVector()) {
            if ((ae.getGrappled() != Entity.NONE) && ae.isChainWhipGrappled()
                    && ae.isGrappleAttacker() && !ae.isGrappledThisRound()) {
                Entity te = game.getEntity(ae.getGrappled());
                ToHitData grappleHit = GrappleAttackAction.toHit(game,
                        ae.getId(), te, ae.getGrappleSide(), true);
                Roll diceRoll = Compute.rollD6(2);

                Report r = new Report(4317);
                r.subject = ae.getId();
                r.indent();
                r.addDesc(ae);
                r.addDesc(te);
                r.newlines = 0;
                addReport(r);

                if (grappleHit.getValue() == TargetRoll.IMPOSSIBLE) {
                    r = new Report(4300);
                    r.subject = ae.getId();
                    r.add(grappleHit.getDesc());
                    addReport(r);
                    return;
                }

                // report the roll
                r = new Report(4025);
                r.subject = ae.getId();
                r.add(grappleHit);
                r.add(diceRoll);
                r.newlines = 0;
                addReport(r);

                // do we hit?
                if (diceRoll.getIntValue() >= grappleHit.getValue()) {
                    // hit
                    r = new Report(4040);
                    r.subject = ae.getId();
                    addReport(r);
                    // Nothing else to do
                    return;
                }

                // miss
                r = new Report(4035);
                r.subject = ae.getId();
                addReport(r);

                // Need to break grapple
                ae.setGrappled(Entity.NONE, false);
                te.setGrappled(Entity.NONE, false);
            }
        }
    }

    /**
     * Checks to see if any entity takes enough damage that requires them to make a piloting roll
     */
    void checkForPSRFromDamage() {
        for (Iterator<Entity> i = game.getEntities(); i.hasNext(); ) {
            final Entity entity = i.next();
            if (entity.canFall()) {
                if (entity.isAirborne()) {
                    // You can't fall over when you are combat dropping because you are already
                    // falling!
                    continue;
                }
                // If this mek has 20+ damage, add another roll to the list.
                // Hulldown meks ignore this rule, TO Errata
                int psrThreshold = 20;
                if ((((Mech) entity).getCockpitType() == Mech.COCKPIT_DUAL)
                        && entity.getCrew().hasDedicatedPilot()) {
                    psrThreshold = 30;
                }
                if ((entity.damageThisPhase >= psrThreshold) && !entity.isHullDown()) {
                    if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_TACOPS_TAKING_DAMAGE)) {
                        PilotingRollData damPRD = new PilotingRollData(entity.getId());
                        int damMod = entity.damageThisPhase / psrThreshold;
                        damPRD.addModifier(damMod, (damMod * psrThreshold) + "+ damage");
                        int weightMod = 0;
                        if (getGame().getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_TACOPS_PHYSICAL_PSR)) {
                            switch (entity.getWeightClass()) {
                                case EntityWeightClass.WEIGHT_LIGHT:
                                    weightMod = 1;
                                    break;
                                case EntityWeightClass.WEIGHT_MEDIUM:
                                    weightMod = 0;
                                    break;
                                case EntityWeightClass.WEIGHT_HEAVY:
                                    weightMod = -1;
                                    break;
                                case EntityWeightClass.WEIGHT_ASSAULT:
                                    weightMod = -2;
                                    break;
                            }
                            if (entity.isSuperHeavy()) {
                                weightMod = -4;
                            }
                            // the weight class PSR modifier is not cumulative
                            damPRD.addModifier(weightMod, "weight class modifier", false);
                        }

                        if (entity.hasQuirk(OptionsConstants.QUIRK_POS_EASY_PILOT)
                                && (entity.getCrew().getPiloting() > 3)) {
                            damPRD.addModifier(-1, "easy to pilot");
                        }
                        getGame().addPSR(damPRD);
                    } else {
                        PilotingRollData damPRD = new PilotingRollData(entity.getId(), 1,
                                psrThreshold + "+ damage");
                        if (entity.hasQuirk(OptionsConstants.QUIRK_POS_EASY_PILOT)
                                && (entity.getCrew().getPiloting() > 3)) {
                            damPRD.addModifier(-1, "easy to pilot");
                        }
                        getGame().addPSR(damPRD);
                    }
                }
            }
            if (entity.isAero() && entity.isAirborne() && !game.getBoard().inSpace()) {
                // if this aero has any damage, add another roll to the list.
                if (entity.damageThisPhase > 0) {
                    if (!getGame().getOptions().booleanOption(OptionsConstants.ADVAERORULES_ATMOSPHERIC_CONTROL)) {
                        int damMod = entity.damageThisPhase / 20;
                        PilotingRollData damPRD = new PilotingRollData(entity.getId(), damMod,
                                entity.damageThisPhase + " damage +" + damMod);
                        if (entity.hasQuirk(OptionsConstants.QUIRK_POS_EASY_PILOT)
                                && (entity.getCrew().getPiloting() > 3)) {
                            damPRD.addModifier(-1, "easy to pilot");
                        }
                        getGame().addControlRoll(damPRD);
                    } else {
                        // was the damage threshold exceeded this round?
                        if (((IAero) entity).wasCritThresh()) {
                            PilotingRollData damThresh = new PilotingRollData(entity.getId(), 0,
                                    "damage threshold exceeded");
                            if (entity.hasQuirk(OptionsConstants.QUIRK_POS_EASY_PILOT)
                                    && (entity.getCrew().getPiloting() > 3)) {
                                damThresh.addModifier(-1, "easy to pilot");
                            }
                            getGame().addControlRoll(damThresh);
                        }
                    }
                }
            }
            // Airborne AirMechs that take 20+ damage make a control roll instead of a PSR.
            if ((entity instanceof LandAirMech) && entity.isAirborneVTOLorWIGE()
                    && (entity.damageThisPhase >= 20)) {
                PilotingRollData damPRD = new PilotingRollData(entity.getId());
                int damMod = entity.damageThisPhase / 20;
                damPRD.addModifier(damMod, (damMod * 20) + "+ damage");
                getGame().addControlRoll(damPRD);
            }
        }
    }

    /**
     * Checks to see if any non-mech units are standing in fire. Called at the end of the movement
     * phase
     */
    public void checkForFlamingDamage() {
        for (Iterator<Entity> i = getGame().getEntities(); i.hasNext();) {
            final Entity entity = i.next();
            if ((null == entity.getPosition()) || (entity instanceof Mech)
                    || entity.isDoomed() || entity.isDestroyed() || entity.isOffBoard()) {
                continue;
            }
            final Hex curHex = getGame().getBoard().getHex(entity.getPosition());
            final boolean underwater = curHex.containsTerrain(Terrains.WATER)
                    && (curHex.depth() > 0)
                    && (entity.getElevation() < curHex.getLevel());
            final int numFloors = curHex.terrainLevel(Terrains.BLDG_ELEV);
            if (curHex.containsTerrain(Terrains.FIRE) && !underwater
                    && ((entity.getElevation() <= 1)
                    || (entity.getElevation() <= numFloors))) {
                doFlamingDamage(entity, entity.getPosition());
            }
        }
    }

    /**
     * Checks to see if any telemissiles are in a hex with enemy units. If so,
     * then attack one.
     */
    void checkForTeleMissileAttacks() {
        for (Iterator<Entity> i = getGame().getEntities(); i.hasNext();) {
            final Entity entity = i.next();
            if (entity instanceof TeleMissile) {
                // check for enemy units
                Vector<Integer> potTargets = new Vector<>();
                for (Entity te : getGame().getEntitiesVector(entity.getPosition())) {
                    //Telemissiles cannot target fighters or other telemissiles
                    //Fighters don't have a distinctive Etype flag, so we have to do
                    //this by exclusion.
                    if (!(te.hasETypeFlag(Entity.ETYPE_DROPSHIP)
                            || te.hasETypeFlag(Entity.ETYPE_SMALL_CRAFT)
                            || te.hasETypeFlag(Entity.ETYPE_JUMPSHIP)
                            || te.hasETypeFlag(Entity.ETYPE_WARSHIP)
                            || te.hasETypeFlag(Entity.ETYPE_SPACE_STATION))) {
                        continue;
                    }
                    if (te.isEnemyOf(entity)) {
                        // then add it to a vector of potential targets
                        potTargets.add(te.getId());
                    }
                }
                if (!potTargets.isEmpty()) {
                    // determine randomly
                    Entity target = getGame().getEntity(potTargets.get(Compute
                            .randomInt(potTargets.size())));
                    // report this and add a new TeleMissileAttackAction
                    Report r = new Report(9085);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    r.addDesc(target);
                    addReport(r);
                    getGame().addTeleMissileAttack(new TeleMissileAttackAction(entity, target));
                }
            }
        }
    }

    protected void checkForBlueShieldDamage() {
        Report r;
        for (Iterator<Entity> i = game.getEntities(); i.hasNext(); ) {
            final Entity entity = i.next();
            if (!(entity instanceof Aero) && entity.hasActiveBlueShield()
                    && (entity.getBlueShieldRounds() >= 6)) {
                Roll diceRoll = Compute.rollD6(2);
                int target = (3 + entity.getBlueShieldRounds()) - 6;
                r = new Report(1240);
                r.addDesc(entity);
                r.add(target);
                r.add(diceRoll);

                if (diceRoll.getIntValue() < target) {
                    for (Mounted m : entity.getMisc()) {
                        if (m.getType().hasFlag(MiscType.F_BLUE_SHIELD)) {
                            m.setBreached(true);
                        }
                    }
                    r.choose(true);
                } else {
                    r.choose(false);
                }
                vPhaseReport.add(r);
            }
        }
    }

    /**
     * Check to see if anyone dies due to being in certain planetary conditions.
     */
    protected void checkForConditionDeath() {
        Report r;
        for (Iterator<Entity> i = game.getEntities(); i.hasNext(); ) {
            final Entity entity = i.next();
            if ((null == entity.getPosition()) && !entity.isOffBoard() || (entity.getTransportId() != Entity.NONE)) {
                // Ignore transported units, and units that don't have a position for some unknown reason
                continue;
            }
            String reason = game.getPlanetaryConditions().whyDoomed(entity, game);
            if (null != reason) {
                r = new Report(6015);
                r.subject = entity.getId();
                r.addDesc(entity);
                r.add(reason);
                addReport(r);
                reportManager.addReport(entityActionManager.destroyEntity(entity, reason, true, true, this), this);
            }
        }
    }

    /**
     * Check to see if anyone dies due to being in atmosphere.
     */
    protected void checkForAtmosphereDeath() {
        Report r;
        for (Iterator<Entity> i = game.getEntities(); i.hasNext();) {
            final Entity entity = i.next();
            if ((null == entity.getPosition()) || entity.isOffBoard()) {
                // If it's not on the board - aboard something else, for
                // example...
                continue;
            }
            if (entity.doomedInAtmosphere() && (entity.getAltitude() == 0)) {
                r = new Report(6016);
                r.subject = entity.getId();
                r.addDesc(entity);
                addReport(r);
                reportManager.addReport(entityActionManager.destroyEntity(entity,
                        "being in atmosphere where it can't survive", true,
                        true, this), this);
            }
        }
    }

    /**
     * checks if IndustrialMechs should die because they moved into to-deep
     * water last round
     */
    protected void checkForIndustrialWaterDeath() {
        for (Iterator<Entity> i = game.getEntities(); i.hasNext();) {
            final Entity entity = i.next();
            if ((null == entity.getPosition()) || entity.isOffBoard()) {
                // If it's not on the board - aboard something else, for
                // example...
                continue;
            }
            if ((entity instanceof Mech) && ((Mech) entity).isIndustrial()
                    && ((Mech) entity).shouldDieAtEndOfTurnBecauseOfWater()) {
                reportManager.addReport(entityActionManager.destroyEntity(entity,
                        "being in water without environmental shielding", true,
                        true, this), this);
            }

        }
    }

    protected void checkForIndustrialEndOfTurn() {
        checkForIndustrialWaterDeath();
        checkForIndustrialUnstall();
        checkForIndustrialCrit(); // This might hit an actuator or gyro, so...
        reportManager.addReport((Vector<Report>) resolvePilotingRolls(), this);
    }

    protected void checkForIndustrialUnstall() {
        for (Iterator<Entity> i = game.getEntities(); i.hasNext(); ) {
            final Entity entity = i.next();
            entity.checkUnstall(vPhaseReport);
        }
    }

    /**
     * industrial mechs might need to check for critical damage
     */
    protected void checkForIndustrialCrit() {
        for (Iterator<Entity> i = game.getEntities(); i.hasNext();) {
            final Entity entity = i.next();
            if ((entity instanceof Mech) && ((Mech) entity).isIndustrial()) {
                Mech mech = (Mech) entity;
                // should we check for critical damage?
                if (mech.isCheckForCrit()) {
                    Report r = new Report(5530);
                    r.addDesc(mech);
                    r.subject = mech.getId();
                    r.newlines = 0;
                    vPhaseReport.add(r);
                    // for being hit by a physical weapon
                    if (mech.getLevelsFallen() == 0) {
                        r = new Report(5531);
                        r.subject = mech.getId();
                        // or for falling
                    } else {
                        r = new Report(5532);
                        r.subject = mech.getId();
                        r.add(mech.getLevelsFallen());
                    }
                    vPhaseReport.add(r);
                    HitData newHit = mech.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                    vPhaseReport.addAll(criticalEntity(mech,
                            newHit.getLocation(), newHit.isRear(),
                            mech.getLevelsFallen(), 0));
                }
            }
        }
    }

    /**
     * Check to see if anyone dies due to being in space.
     */
    protected void checkForSpaceDeath() {
        Report r;
        for (Iterator<Entity> i = game.getEntities(); i.hasNext();) {
            final Entity entity = i.next();
            if ((null == entity.getPosition()) || entity.isOffBoard()) {
                // If it's not on the board - aboard something else, for
                // example...
                continue;
            }
            if (entity.doomedInSpace()) {
                r = new Report(6017);
                r.subject = entity.getId();
                r.addDesc(entity);
                addReport(r);
                reportManager.addReport(entityActionManager.destroyEntity(entity,
                        "being in space where it can't survive", true, true, this), this);
            }
        }
    }

    /**
     * Checks to see if any entities are underwater (or in vacuum) with damaged
     * life support. Called during the end phase.
     */
    protected void checkForSuffocation() {
        for (Iterator<Entity> i = game.getEntities(); i.hasNext();) {
            final Entity entity = i.next();
            if ((null == entity.getPosition()) || entity.isOffBoard()) {
                continue;
            }
            final Hex curHex = game.getBoard().getHex(entity.getPosition());
            if ((((entity.getElevation() < 0) && ((curHex
                    .terrainLevel(Terrains.WATER) > 1) || ((curHex
                    .terrainLevel(Terrains.WATER) == 1) && entity.isProne()))) || game
                    .getPlanetaryConditions().isVacuum())
                    && (entity.getHitCriticals(CriticalSlot.TYPE_SYSTEM,
                    Mech.SYSTEM_LIFE_SUPPORT, Mech.LOC_HEAD) > 0)) {
                Report r = new Report(6020);
                r.subject = entity.getId();
                r.addDesc(entity);
                addReport(r);
                reportManager.addReport(damageCrew(entity, 1), this);

            }
        }
    }

    /**
     * Iterates over all entities and gets rid of Narc pods attached to destroyed
     * or lost locations.
     */
    void cleanupDestroyedNarcPods() {
        for (Iterator<Entity> i = game.getEntities(); i.hasNext(); ) {
            i.next().clearDestroyedNarcPods();
        }
    }

    /**
     * Resolves all built up piloting skill rolls. Used at end of weapons,
     * physical phases.
     * @return
     */
    List<Report> resolvePilotingRolls() {
        Vector<Report> vPhaseReport = new Vector<>();

        for (Iterator<Entity> i = game.getEntities(); i.hasNext(); ) {
            vPhaseReport.addAll(resolvePilotingRolls(i.next()));
        }
        game.resetPSRs();

        if (vPhaseReport.size() > 0) {
            vPhaseReport.insertElementAt(new Report(3900, Report.PUBLIC), 0);
        }

        return vPhaseReport;
    }

    /**
     * Resolves and reports all piloting skill rolls for a single mech.
     */
    protected Vector<Report> resolvePilotingRolls(Entity entity) {
        return resolvePilotingRolls(entity, false, entity.getPosition(),
                entity.getPosition());
    }

    protected Vector<Report> resolvePilotingRolls(Entity entity, boolean moving,
                                                Coords src, Coords dest) {
        Vector<Report> vPhaseReport = new Vector<>();
        // dead and undeployed and offboard units don't need to.
        if (entity.isDoomed() || entity.isDestroyed() || entity.isOffBoard() || !entity.isDeployed()
                || (entity.getTransportId() != Entity.NONE)) {
            return vPhaseReport;
        }

        // airborne units don't make piloting rolls, they make control rolls
        if (entity.isAirborne()) {
            return vPhaseReport;
        }

        Report r;

        // first, do extreme gravity PSR, because non-mechs do these, too
        PilotingRollData rollTarget = null;
        for (Enumeration<PilotingRollData> i = game.getExtremeGravityPSRs(); i.hasMoreElements(); ) {
            final PilotingRollData roll = i.nextElement();
            if (roll.getEntityId() != entity.getId()) {
                continue;
            }
            // found a roll, use it (there can be only 1 per entity)
            rollTarget = roll;
            game.resetExtremeGravityPSRs(entity);
        }
        if ((rollTarget != null) && (rollTarget.getValue() != TargetRoll.CHECK_FALSE)) {
            // okay, print the info
            r = new Report(2180);
            r.subject = entity.getId();
            r.addDesc(entity);
            r.add(rollTarget.getLastPlainDesc());
            vPhaseReport.add(r);
            // roll
            Roll diceRoll = entity.getCrew().rollPilotingSkill();
            r = new Report(2190);
            r.subject = entity.getId();
            r.add(rollTarget.getValueAsString());
            r.add(rollTarget.getDesc());
            r.add(diceRoll);

            if ((diceRoll.getIntValue() < rollTarget.getValue())
                    || (game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_FUMBLES)
                    && (diceRoll.getIntValue() == 2))) {
                r.choose(false);
                // Report the fumble
                if (game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_FUMBLES)
                        && (diceRoll.getIntValue() == 2)) {
                    r.messageId = 2306;
                }
                vPhaseReport.add(r);
                // walking and running, 1 damage per MP used more than we would
                // have normally
                if ((entity.moved == EntityMovementType.MOVE_WALK)
                        || (entity.moved == EntityMovementType.MOVE_VTOL_WALK)
                        || (entity.moved == EntityMovementType.MOVE_RUN)
                        || (entity.moved == EntityMovementType.MOVE_SPRINT)
                        || (entity.moved == EntityMovementType.MOVE_VTOL_RUN)
                        || (entity.moved == EntityMovementType.MOVE_VTOL_SPRINT)) {
                    if (entity instanceof Mech) {
                        int j = entity.mpUsed;
                        int damage = 0;
                        while (j > entity.getRunningGravityLimit()) {
                            j--;
                            damage++;
                        }
                        // Wee, direct internal damage
                        vPhaseReport.addAll(doExtremeGravityDamage(entity,
                                damage));
                    } else if (entity instanceof Tank) {
                        // if we got a pavement bonus, take care of it
                        int k = entity.gotPavementBonus ? 1 : 0;
                        if (!entity.gotPavementBonus) {
                            int j = entity.mpUsed;
                            int damage = 0;
                            while (j > (entity.getRunMP(MPCalculationSetting.NO_GRAVITY) + k)) {
                                j--;
                                damage++;
                            }
                            vPhaseReport.addAll(doExtremeGravityDamage(entity,
                                    damage));
                        }
                    }
                }
                // jumping
                if ((entity.moved == EntityMovementType.MOVE_JUMP)
                        && (entity instanceof Mech)) {
                    // low g, 1 damage for each hex jumped further than
                    // possible normally
                    if (game.getPlanetaryConditions().getGravity() < 1) {
                        int j = entity.mpUsed;
                        int damage = 0;
                        while (j > entity.getJumpMP(MPCalculationSetting.NO_GRAVITY)) {
                            j--;
                            damage++;
                        }
                        // Wee, direct internal damage
                        vPhaseReport.addAll(doExtremeGravityDamage(entity,
                                damage));
                    }
                    // high g, 1 damage for each MP we have less than normally
                    else if (game.getPlanetaryConditions().getGravity() > 1) {
                        int damage = entity.getWalkMP(MPCalculationSetting.NO_GRAVITY)
                                - entity.getWalkMP();
                        // Wee, direct internal damage
                        vPhaseReport.addAll(doExtremeGravityDamage(entity,
                                damage));
                    }
                }
                // failed a PSR, check for ICE engine stalling
                entity.doCheckEngineStallRoll(vPhaseReport);
            } else {
                r.choose(true);
                vPhaseReport.add(r);
            }
        }

        // Glider ProtoMechs without sufficient movement to stay airborne make forced landings.
        if ((entity instanceof Protomech) && ((Protomech) entity).isGlider()
                && entity.isAirborneVTOLorWIGE() && (entity.getRunMP() < 4)) {
            vPhaseReport.addAll(entityActionManager.landGliderPM((Protomech) entity, entity.getPosition(), entity.getElevation(),
                    entity.delta_distance, this));
        }

        // non mechs and prone mechs can now return
        if (!entity.canFall() || (entity.isHullDown() && entity.canGoHullDown())) {
            return vPhaseReport;
        }

        // Mechs with UMU float and don't have to roll???
        if (entity instanceof Mech) {
            Hex hex = game.getBoard().getHex(dest);
            int water = hex.terrainLevel(Terrains.WATER);
            if ((water > 0) && (entity.getElevation() != -hex.depth(true))
                    && ((entity.getElevation() < 0) || ((entity.getElevation() == 0)
                    && (hex.terrainLevel(Terrains.BRIDGE_ELEV) != 0) && !hex.containsTerrain(Terrains.ICE)))
                    && !entity.isMakingDfa() && !entity.isDropping()) {
                // mech is floating in water....
                if (entity.hasUMU()) {
                    return vPhaseReport;
                }
                // game.addPSR(new PilotingRollData(entity.getId(),
                // TargetRoll.AUTOMATIC_FAIL, "lost buoyancy"));
            }
        }
        // add all cumulative mods from other rolls to each PSR
        // holds all rolls to make
        Vector<PilotingRollData> rolls = new Vector<>();
        // holds the initial reason for each roll
        StringBuilder reasons = new StringBuilder();
        PilotingRollData base = entity.getBasePilotingRoll();
        entity.addPilotingModifierForTerrain(base);
        for (Enumeration<PilotingRollData> i = game.getPSRs(); i.hasMoreElements(); ) {
            PilotingRollData psr = i.nextElement();
            if (psr.getEntityId() != entity.getId()) {
                continue;
            }
            // found a roll
            if (reasons.length() > 0) {
                reasons.append("; ");
            }
            reasons.append(psr.getPlainDesc());
            PilotingRollData toUse = entity.getBasePilotingRoll();
            entity.addPilotingModifierForTerrain(toUse);
            toUse.append(psr);
            // now, append all other roll's cumulative mods, not the
            // non-cumulative
            // ones
            for (Enumeration<PilotingRollData> j = game.getPSRs(); j.hasMoreElements(); ) {
                final PilotingRollData other = j.nextElement();
                if ((other.getEntityId() != entity.getId()) || other.equals(psr)) {
                    continue;
                }
                toUse.append(other, false);
            }
            rolls.add(toUse);
        }
        // any rolls needed?
        if (rolls.isEmpty()) {
            return vPhaseReport;
        }
        // is our base roll impossible?
        if ((base.getValue() == TargetRoll.AUTOMATIC_FAIL)
                || (base.getValue() == TargetRoll.IMPOSSIBLE)) {
            r = new Report(2275);
            r.subject = entity.getId();
            r.addDesc(entity);
            r.add(rolls.size());
            r.add(base.getDesc());
            vPhaseReport.add(r);
            if (moving) {
                vPhaseReport.addAll(utilityManager.doEntityFallsInto(entity, entity.getElevation(), src, dest,
                        base, true, this));
            } else if ((entity instanceof Mech) && game.getOptions().booleanOption(
                    OptionsConstants.ADVGRNDMOV_TACOPS_FALLING_EXPANDED)
                    && (entity.getCrew().getPiloting() < 6)
                    && !entity.isHullDown() && entity.canGoHullDown()) {
                if (entity.isHullDown() && entity.canGoHullDown()) {
                    r = new Report(2317);
                    r.subject = entity.getId();
                    r.add(entity.getDisplayName());
                    vPhaseReport.add(r);
                } else {
                    vPhaseReport.addAll(doEntityFall(entity, base));
                }
            } else {
                vPhaseReport.addAll(doEntityFall(entity, base));
            }
            // failed a PSR, check for ICE engine stalling
            entity.doCheckEngineStallRoll(vPhaseReport);
            return vPhaseReport;
        }
        // loop through rolls we do have to make...
        r = new Report(2280);
        r.subject = entity.getId();
        r.addDesc(entity);
        r.add(rolls.size());
        r.add(reasons.toString());
        vPhaseReport.add(r);
        r = new Report(2285);
        r.subject = entity.getId();
        r.add(base);
        vPhaseReport.add(r);
        for (int i = 0; i < rolls.size(); i++) {
            PilotingRollData roll = rolls.elementAt(i);
            r = new Report(2291);
            r.subject = entity.getId();
            r.indent();
            r.newlines = 0;
            r.add(i + 1);
            vPhaseReport.add(r);
            if ((roll.getValue() == TargetRoll.AUTOMATIC_FAIL)
                    || (roll.getValue() == TargetRoll.IMPOSSIBLE)) {
                r = new Report(2296);
                r.subject = entity.getId();
                vPhaseReport.add(r);
                if (moving) {
                    vPhaseReport.addAll(utilityManager.doEntityFallsInto(entity, entity.getElevation(), src, dest,
                            roll, true, this));
                } else {
                    if ((entity instanceof Mech)
                            && game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_TACOPS_FALLING_EXPANDED)
                            && (entity.getCrew().getPiloting() < 6)
                            && !entity.isHullDown() && entity.canGoHullDown()) {
                        if (entity.isHullDown() && entity.canGoHullDown()) {
                            r = new Report(2317);
                            r.subject = entity.getId();
                            r.add(entity.getDisplayName());
                            vPhaseReport.add(r);
                        } else {
                            vPhaseReport.addAll(doEntityFall(entity, roll));
                        }
                    } else {
                        vPhaseReport.addAll(doEntityFall(entity, roll));
                    }
                }
                // failed a PSR, check for ICE engine stalling
                entity.doCheckEngineStallRoll(vPhaseReport);
                return vPhaseReport;
            }

            Roll diceRoll = entity.getCrew().rollPilotingSkill();
            r = new Report(2299);
            r.add(roll);
            r.add(diceRoll);
            r.subject = entity.getId();

            if ((diceRoll.getIntValue() < roll.getValue())
                    || (game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_FUMBLES) && (diceRoll.getIntValue() == 2))) {
                r.choose(false);
                // Report the fumble
                if (game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_FUMBLES)
                        && (diceRoll.getIntValue() == 2)) {
                    r.messageId = 2306;
                }
                vPhaseReport.add(r);
                if (moving) {
                    vPhaseReport.addAll(utilityManager.doEntityFallsInto(entity, entity.getElevation(), src, dest, roll, true, this));
                } else {
                    if ((entity instanceof Mech)
                            && game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_TACOPS_FALLING_EXPANDED)
                            && (entity.getCrew().getPiloting() < 6)
                            && !entity.isHullDown() && entity.canGoHullDown()) {
                        if ((entity.getCrew().getPiloting() > 1)
                                && ((roll.getValue() - diceRoll.getIntValue()) < 2)) {
                            entity.setHullDown(true);
                        } else if ((entity.getCrew().getPiloting() <= 1)
                                && ((roll.getValue() - diceRoll.getIntValue()) < 3)) {
                            entity.setHullDown(true);
                        }

                        if (entity.isHullDown() && entity.canGoHullDown()) {
                            ServerHelper.sinkToBottom(entity);

                            r = new Report(2317);
                            r.subject = entity.getId();
                            r.add(entity.getDisplayName());
                            vPhaseReport.add(r);
                        } else {
                            vPhaseReport.addAll(doEntityFall(entity, roll));
                        }
                    } else {
                        vPhaseReport.addAll(doEntityFall(entity, roll));
                    }
                }
                // failed a PSR, check for ICE engine stalling
                entity.doCheckEngineStallRoll(vPhaseReport);
                return vPhaseReport;
            }
            r.choose(true);
            vPhaseReport.add(r);
        }
        return vPhaseReport;
    }

    protected Vector<Report> checkForTraitors() {
        Vector<Report> vFullReport = new Vector<>();
        // check for traitors
        for (Iterator<Entity> i = game.getEntities(); i.hasNext(); ) {
            Entity entity = i.next();
            if (entity.isDoomed() || entity.isDestroyed() || entity.isOffBoard()
                    || !entity.isDeployed()) {
                continue;
            }
            if ((entity.getTraitorId() != -1) && (entity.getOwnerId() != entity.getTraitorId())) {
                final Player oldPlayer = game.getPlayer(entity.getOwnerId());
                final Player newPlayer = game.getPlayer(entity.getTraitorId());
                if (newPlayer != null) {
                    Report r = new Report(7305);
                    r.subject = entity.getId();
                    r.add(entity.getDisplayName());
                    r.add(newPlayer.getName());
                    entity.setOwner(newPlayer);
                    entityUpdate(entity.getId());
                    vFullReport.add(r);

                    // Move the initial count and BV to their new player
                    newPlayer.changeInitialEntityCount(1);
                    newPlayer.changeInitialBV(entity.calculateBattleValue());

                    // And remove it from their old player, if they exist
                    if (oldPlayer != null) {
                        oldPlayer.changeInitialEntityCount(-1);
                        // Note: I don't remove the full initial BV if I'm damaged, but that
                        // actually makes sense
                        oldPlayer.changeInitialBV(-1 * entity.calculateBattleValue());
                    }
                }
                entity.setTraitorId(-1);
            }
        }

        if (!vFullReport.isEmpty()) {
            vFullReport.add(0, new Report(7300));
        }

        return vFullReport;
    }

    /**
     * Resolves all built up control rolls. Used only during end phase
     */
    protected Vector<Report> resolveControlRolls() {
        Vector<Report> vFullReport = new Vector<>();
        vFullReport.add(new Report(5001, Report.PUBLIC));
        for (Iterator<Entity> i = game.getEntities(); i.hasNext(); ) {
            vFullReport.addAll(resolveControl(i.next()));
        }
        game.resetControlRolls();
        return vFullReport;
    }

    /**
     * Resolves and reports all control skill rolls for a single aero or airborne LAM in airmech mode.
     */
    protected Vector<Report> resolveControl(Entity e) {
        Vector<Report> vReport = new Vector<>();
        if (e.isDoomed() || e.isDestroyed() || e.isOffBoard() || !e.isDeployed()) {
            return vReport;
        }
        Report r;

        /*
         * See forum answers on OOC
         * http://forums.classicbattletech.com/index.php/topic,20424.0.html
         */

        IAero a = null;
        boolean canRecover = false;
        if (e.isAero() && (e.isAirborne() || e.isSpaceborne())) {
            a = (IAero) e;
            // they should get a shot at a recovery roll at the end of all this
            // if they are already out of control
            canRecover = a.isOutControl();
        } else if (!(e instanceof LandAirMech) || !e.isAirborneVTOLorWIGE()) {
            return vReport;
        }

        // if the unit already is moving randomly then it can't get any worse
        if (a == null || !a.isRandomMove()) {
            // find control rolls and make them
            Vector<PilotingRollData> rolls = new Vector<>();
            StringBuilder reasons = new StringBuilder();
            PilotingRollData target = e.getBasePilotingRoll();
            // maneuvering ace
            // TODO : pending rules query
            // http://www.classicbattletech.com/forums/index.php/topic,63552.new.html#new
            // for now I am assuming Man Ace applies to all out-of-control
            // rolls, but not other
            // uses of control rolls (thus it doesn't go in
            // Entity#addEntityBonuses) and
            // furthermore it doesn't apply to recovery rolls
            if (e.isUsingManAce()) {
                target.addModifier(-1, "maneuvering ace");
            }
            for (Enumeration<PilotingRollData> j = game.getControlRolls(); j.hasMoreElements(); ) {
                final PilotingRollData modifier = j.nextElement();
                if (modifier.getEntityId() != e.getId()) {
                    continue;
                }
                // found a roll, add it
                rolls.addElement(modifier);
                if (reasons.length() > 0) {
                    reasons.append("; ");
                }
                reasons.append(modifier.getCumulativePlainDesc());
                target.append(modifier);
            }
            // any rolls needed?
            if (!rolls.isEmpty()) {
                // loop through rolls we do have to make...
                r = new Report(9310);
                r.subject = e.getId();
                r.addDesc(e);
                r.add(rolls.size());
                r.add(reasons.toString());
                vReport.add(r);
                r = new Report(2285);
                r.subject = e.getId();
                r.add(target);
                vReport.add(r);
                for (int j = 0; j < rolls.size(); j++) {
                    PilotingRollData modifier = rolls.elementAt(j);
                    r = new Report(2290);
                    r.subject = e.getId();
                    r.indent();
                    r.newlines = 0;
                    r.add(j + 1);
                    r.add(modifier.getPlainDesc());
                    vReport.add(r);
                    Roll diceRoll = Compute.rollD6(2);

                    // different reports depending on out-of-control status
                    if (a != null && a.isOutControl()) {
                        r = new Report(9360);
                        r.subject = e.getId();
                        r.add(target);
                        r.add(diceRoll);
                        if (diceRoll.getIntValue() < (target.getValue() - 5)) {
                            r.choose(false);
                            vReport.add(r);
                            a.setRandomMove(true);
                        } else {
                            r.choose(true);
                            vReport.add(r);
                        }
                    } else {
                        r = new Report(9315);
                        r.subject = e.getId();
                        r.add(target);
                        r.add(diceRoll);
                        r.newlines = 1;
                        if (diceRoll.getIntValue() < target.getValue()) {
                            r.choose(false);
                            vReport.add(r);
                            if (a != null) {
                                a.setOutControl(true);
                                // do we have random movement?
                                if ((target.getValue() - diceRoll.getIntValue()) > 5) {
                                    r = new Report(9365);
                                    r.newlines = 0;
                                    r.subject = e.getId();
                                    vReport.add(r);
                                    a.setRandomMove(true);
                                }
                                // if on the atmospheric map, then lose altitude
                                // and check
                                // for crash
                                if (!a.isSpaceborne() && a.isAirborne()) {
                                    Roll diceRoll2 = Compute.rollD6(1);

                                    int origAltitude = e.getAltitude();
                                    e.setAltitude(e.getAltitude() - diceRoll2.getIntValue());
                                    // Reroll altitude loss with edge if the new altitude would result in a crash
                                    if (e.getAltitude() <= 0
                                            // Don't waste the edge if it won't help
                                            && origAltitude > 1
                                            && e.getCrew().hasEdgeRemaining()
                                            && e.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_AERO_ALT_LOSS)) {
                                        Roll diceRoll3 = Compute.rollD6(1);
                                        int rollValue3 = diceRoll3.getIntValue();
                                        String rollReport3 = diceRoll3.getReport();

                                        // Report the edge use
                                        r = new Report(9367);
                                        r.newlines = 1;
                                        r.subject = e.getId();
                                        vReport.add(r);
                                        e.setAltitude(origAltitude - rollValue3);
                                        // and spend the edge point
                                        e.getCrew().decreaseEdge();
                                        diceRoll2 = diceRoll3;
                                    }
                                    //Report the altitude loss
                                    r = new Report(9366);
                                    r.newlines = 0;
                                    r.subject = e.getId();
                                    r.addDesc(e);
                                    r.add(diceRoll2);
                                    vReport.add(r);
                                    // check for crash
                                    if (entityActionManager.checkCrash(e, e.getPosition(), e.getAltitude(), this)) {
                                        vReport.addAll(entityActionManager.processCrash(e, a.getCurrentVelocity(),
                                                e.getPosition(), this));
                                        break;
                                    }
                                }
                            } else if (e.isAirborneVTOLorWIGE()) {
                                int loss = target.getValue() - diceRoll.getIntValue();
                                r = new Report(9366);
                                r.subject = e.getId();
                                r.addDesc(e);
                                r.add(loss);
                                vReport.add(r);
                                Hex hex = game.getBoard().getHex(e.getPosition());
                                int elevation = Math.max(0, hex.terrainLevel(Terrains.BLDG_ELEV));
                                if (e.getElevation() - loss <= elevation) {
                                    entityActionManager.crashAirMech(e, target, vReport, this);
                                } else {
                                    e.setElevation(e.getElevation() - loss);
                                }
                            }
                        } else {
                            r.choose(true);
                            vReport.add(r);
                        }
                    }
                }
            }
        }

        // if they were out-of-control to start with, give them a chance to regain control
        if (canRecover) {
            PilotingRollData base = e.getBasePilotingRoll();
            // is our base roll impossible?
            if ((base.getValue() == TargetRoll.AUTOMATIC_FAIL)
                    || (base.getValue() == TargetRoll.IMPOSSIBLE)) {
                // report something
                r = new Report(9340);
                r.subject = e.getId();
                r.addDesc(e);
                r.add(base.getDesc());
                vReport.add(r);
                return vReport;
            }
            r = new Report(9345);
            r.subject = e.getId();
            r.addDesc(e);
            r.add(base.getDesc());
            vReport.add(r);
            Roll diceRoll = Compute.rollD6(2);
            r = new Report(9350);
            r.subject = e.getId();
            r.add(base);
            r.add(diceRoll);

            if (diceRoll.getIntValue() < base.getValue()) {
                r.choose(false);
                vReport.add(r);
            } else {
                r.choose(true);
                vReport.add(r);
                a.setOutControl(false);
                a.setOutCtrlHeat(false);
                a.setRandomMove(false);
            }
        }
        return vReport;
    }

    /**
     * Check all aircraft that may have used internal bomb bays for incidental explosions
     * caused by ground fire.
     * @return
     */
    protected Vector<Report> resolveInternalBombHits() {
        Vector<Report> vFullReport = new Vector<>();
        vFullReport.add(new Report(5600, Report.PUBLIC));
        for (Entity e : game.getEntitiesVector()) {
            Vector<Report> interim = resolveInternalBombHit(e);
            if (!interim.isEmpty()) {
                vFullReport.addAll(interim);
            }
        }
        // Return empty Vector if no reports (besides the header) are added.
        return (vFullReport.size() == 1) ? new Vector<>() : vFullReport;
    }

    /**
     * Resolves and reports all control skill rolls for a single aero or airborne LAM in airmech mode.
     */
    protected Vector<Report> resolveInternalBombHit(Entity e) {
        Vector<Report> vReport = new Vector<>();
        // Only applies to surviving bombing craft that took damage this last round
        if (!e.isBomber() || e.damageThisRound <= 0 || e.isDoomed() || e.isDestroyed() || !e.isDeployed()) {
            return vReport;
        }

        //
        if (e.isAero() && !(e instanceof LandAirMech)) {
            // Only ground fire can hit internal bombs
            if (e.getGroundAttackedByThisTurn().isEmpty()) {
                return vReport;
            }

            Aero b = (Aero) e;
            Report r;

            if (b.getUsedInternalBombs() > 0) {
                int id = e.getId();

                // Header
                r = new Report(5601);
                r.subject = id;
                r.addDesc(e);
                vReport.add(r);

                // Roll
                int rollTarget = 10; //Testing purposes
                int roll = Compute.d6(2);
                boolean explosion = roll >= rollTarget;
                r = new Report(5602);
                r.indent();
                r.subject = id;
                r.addDesc(e);
                r.add(rollTarget);
                r.add(roll, false);
                vReport.add(r);

                // Outcome
                r = (explosion) ? new Report(5603) : new Report(5604);
                r.indent();
                r.subject = id;
                r.addDesc(e);
                int bombsLeft = b.getBombs().stream().mapToInt(Mounted::getUsableShotsLeft).sum();
                int bombDamage = b.getInternalBombsDamageTotal();
                if (explosion) {
                    r.add(bombDamage);
                }
                r.add(bombsLeft);
                vReport.add(r);
                // Deal damage
                if (explosion) {
                    HitData hd = new HitData(b.getBodyLocation(), false, HitData.EFFECT_NONE);
                    vReport.addAll(damageEntity(e, hd, bombDamage, true, DamageType.NONE,true));
                    e.applyDamage();
                }
            }
        }
        return vReport;
    }

    /**
     * Inflict damage on a pilot
     *
     * @param en     The <code>Entity</code> who's pilot gets damaged.
     * @param damage The <code>int</code> amount of damage.
     */
    public Vector<Report> damageCrew(Entity en, int damage) {
        return damageCrew(en, damage, -1);
    }

    /**
     * Inflict damage on a pilot
     *
     * @param en        The <code>Entity</code> who's pilot gets damaged.
     * @param damage    The <code>int</code> amount of damage.
     * @param crewPos   The <code>int</code>position of the crew member in a <code>MultiCrewCockpit</code>
     *                  that takes the damage. A value &lt; 0 applies the damage to all crew members.
     *                  The basic <code>Crew</code> ignores this value.
     */
    public Vector<Report> damageCrew(Entity en, int damage, int crewPos) {
        Vector<Report> vDesc = new Vector<>();
        Crew crew = en.getCrew();
        Report r;
        if (!crew.isDead() && !crew.isEjected() && !crew.isDoomed()) {
            for (int pos = 0; pos < en.getCrew().getSlotCount(); pos++) {
                if (crewPos >= 0
                        && (crewPos != pos || crew.isDead(crewPos))) {
                    continue;
                }
                boolean wasPilot = crew.getCurrentPilotIndex() == pos;
                boolean wasGunner = crew.getCurrentGunnerIndex() == pos;
                crew.setHits(crew.getHits(pos) + damage, pos);
                if (en.isLargeCraft()) {
                    r = new Report (6028);
                    r.subject = en.getId();
                    r.indent(2);
                    r.addDesc(en);
                    r.add(damage);
                    if (((Aero) en).isEjecting()) {
                        r.add("as crew depart the ship");
                    } else {
                        //Blank data
                        r.add("");
                    }
                    r.add(crew.getHits(pos));
                    vDesc.addElement(r);
                    if (Crew.DEATH > crew.getHits()) {
                        vDesc.addAll(resolveCrewDamage(en, damage, pos));
                    } else if (!crew.isDoomed()) {
                        crew.setDoomed(true);
                        //Safety. We might use this logic for large naval vessels later on
                        if (en instanceof Aero && ((Aero) en).isEjecting()) {
                            vDesc.addAll(entityActionManager.destroyEntity(en, "ejection", true, this));
                            ((Aero) en).setEjecting(false);
                        } else {
                            vDesc.addAll(entityActionManager.destroyEntity(en, "crew casualties", true, this));
                        }
                    }
                } else {
                    if (Crew.DEATH > crew.getHits(pos)) {
                        r = new Report(6025);
                    } else {
                        r = new Report(6026);
                    }
                    r.subject = en.getId();
                    r.indent(2);
                    r.add(crew.getCrewType().getRoleName(pos));
                    r.addDesc(en);
                    r.add(crew.getName(pos));
                    r.add(damage);
                    r.add(crew.getHits(pos));
                    vDesc.addElement(r);
                    if (crew.isDead(pos)) {
                        r = createCrewTakeoverReport(en, pos, wasPilot, wasGunner);
                        if (null != r) {
                            vDesc.addElement(r);
                        }
                    }
                    if (Crew.DEATH > crew.getHits()) {
                        vDesc.addAll(resolveCrewDamage(en, damage, pos));
                    } else if (!crew.isDoomed()) {
                        crew.setDoomed(true);
                        vDesc.addAll(entityActionManager.destroyEntity(en, "pilot death", true, this));
                    }
                }
            }
        } else {
            boolean isPilot = (en instanceof Mech) || ((en instanceof Aero)
                    && !(en instanceof SmallCraft) && !(en instanceof Jumpship));
            if (crew.isDead() || crew.isDoomed()) {
                if (isPilot) {
                    r = new Report(6021);
                } else {
                    r = new Report(6022);
                }
            } else {
                if (isPilot) {
                    r = new Report(6023);
                } else {
                    r = new Report(6024);
                }
            }
            r.subject = en.getId();
            r.addDesc(en);
            r.add(crew.getName());
            r.indent(2);
            vDesc.add(r);
        }
        if (en.isAirborneVTOLorWIGE() && !en.getCrew().isActive()) {
            if (en instanceof LandAirMech) {
                entityActionManager.crashAirMech(en, en.getBasePilotingRoll(), vDesc, this);
            } else if (en instanceof Protomech) {
                vDesc.addAll(entityActionManager.landGliderPM((Protomech) en, this));
            }
        }
        return vDesc;
    }

    /**
     * Convenience method that fills in a report showing that a crew member of a multicrew cockpit
     * has taken over for another incapacitated crew member.
     *
     * @param e         The <code>Entity</code> for the crew.
     * @param slot      The slot index of the crew member that was incapacitated.
     * @param wasPilot  Whether the crew member was the pilot before becoming incapacitated.
     * @param wasGunner Whether the crew member was the gunner before becoming incapacitated.
     * @return          A completed <code>Report</code> if the position was assumed by another
     *                  crew members, otherwise null.
     */
    protected Report createCrewTakeoverReport(Entity e, int slot, boolean wasPilot, boolean wasGunner) {
        if (wasPilot && e.getCrew().getCurrentPilotIndex() != slot) {
            Report r = new Report(5560);
            r.subject = e.getId();
            r.indent(4);
            r.add(e.getCrew().getNameAndRole(e.getCrew().getCurrentPilotIndex()));
            r.add(e.getCrew().getCrewType().getRoleName(e.getCrew().getCrewType().getPilotPos()));
            r.addDesc(e);
            return r;
        }
        if (wasGunner && e.getCrew().getCurrentGunnerIndex() != slot) {
            Report r = new Report(5560);
            r.subject = e.getId();
            r.indent(4);
            r.add(e.getCrew().getNameAndRole(e.getCrew().getCurrentGunnerIndex()));
            r.add(e.getCrew().getCrewType().getRoleName(e.getCrew().getCrewType().getGunnerPos()));
            r.addDesc(e);
            return r;
        }
        return null;
    }

    /**
     * resolves consciousness rolls for one entity
     *
     * @param e         The <code>Entity</code> that took damage
     * @param damage    The <code>int</code> damage taken by the pilot
     * @param crewPos   The <code>int</code> index of the crew member for multi crew cockpits, ignored by
     *                  basic <code>crew</code>
     */
    protected Vector<Report> resolveCrewDamage(Entity e, int damage, int crewPos) {
        Vector<Report> vDesc = new Vector<>();
        final int totalHits = e.getCrew().getHits(crewPos);
        if ((e instanceof MechWarrior) || !e.isTargetable()
                || !e.getCrew().isActive(crewPos) || (damage == 0)) {
            return vDesc;
        }

        // no consciousness roll for pain-shunted warriors
        if (e.hasAbility(OptionsConstants.MD_PAIN_SHUNT)) {
            return vDesc;
        }

        // no consciousness roll for capital fighter pilots or large craft crews
        if (e.isCapitalFighter() || e.isLargeCraft()) {
            return vDesc;
        }

        for (int hit = (totalHits - damage) + 1; hit <= totalHits; hit++) {
            int rollTarget = Compute.getConsciousnessNumber(hit);
            if (game.getOptions().booleanOption(OptionsConstants.RPG_TOUGHNESS)) {
                rollTarget -= e.getCrew().getToughness(crewPos);
            }
            boolean edgeUsed = false;
            do {
                if (edgeUsed) {
                    e.getCrew().decreaseEdge();
                }
                Roll diceRoll = Compute.rollD6(2);
                int rollValue = diceRoll.getIntValue();
                String rollCalc = String.valueOf(rollValue);

                if (e.hasAbility(OptionsConstants.MISC_PAIN_RESISTANCE)) {
                    rollValue = Math.min(12, rollValue + 1);
                    rollCalc = rollValue + " [" + diceRoll.getIntValue() + " + 1] max 12";
                }

                Report r = new Report(6030);
                r.indent(2);
                r.subject = e.getId();
                r.add(e.getCrew().getCrewType().getRoleName(crewPos));
                r.addDesc(e);
                r.add(e.getCrew().getName(crewPos));
                r.add(rollTarget);
                r.addDataWithTooltip(rollCalc, diceRoll.getReport());

                if (rollValue >= rollTarget) {
                    e.getCrew().setKoThisRound(false, crewPos);
                    r.choose(true);
                } else {
                    e.getCrew().setKoThisRound(true, crewPos);
                    r.choose(false);
                    if (e.getCrew().hasEdgeRemaining()
                            && (e.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_KO)
                            || e.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_AERO_KO))) {
                        edgeUsed = true;
                        vDesc.add(r);
                        r = new Report(6520);
                        r.subject = e.getId();
                        r.addDesc(e);
                        r.add(e.getCrew().getName(crewPos));
                        r.add(e.getCrew().getOptions().intOption(OptionsConstants.EDGE));
                    } // if
                    // return true;
                } // else
                vDesc.add(r);
            } while (e.getCrew().hasEdgeRemaining()
                    && e.getCrew().isKoThisRound(crewPos)
                    && (e.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_KO)
                    || e.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_AERO_KO)));
            // end of do-while
            if (e.getCrew().isKoThisRound(crewPos)) {
                boolean wasPilot = e.getCrew().getCurrentPilotIndex() == crewPos;
                boolean wasGunner = e.getCrew().getCurrentGunnerIndex() == crewPos;
                e.getCrew().setUnconscious(true, crewPos);
                Report r = createCrewTakeoverReport(e, crewPos, wasPilot, wasGunner);
                if (null != r) {
                    vDesc.add(r);
                }
                return vDesc;
            }
        }
        return vDesc;
    }

    /**
     * Make the rolls indicating whether any unconscious crews wake up
     */
    protected void resolveCrewWakeUp() {
        for (Iterator<Entity> i = game.getEntities(); i.hasNext(); ) {
            final Entity e = i.next();

            // only unconscious pilots of mechs and protos, ASF and Small Craft
            // and MechWarriors can roll to wake up
            if (e.isTargetable()
                    && ((e instanceof Mech) || (e instanceof Protomech)
                    || (e instanceof MechWarrior) || ((e instanceof Aero) && !(e instanceof Jumpship)))) {
                for (int pos = 0; pos < e.getCrew().getSlotCount(); pos++) {
                    if (e.getCrew().isMissing(pos)) {
                        continue;
                    }
                    if (e.getCrew().isUnconscious(pos)
                            && !e.getCrew().isKoThisRound(pos)) {
                        Roll diceRoll = Compute.rollD6(2);
                        int rollValue = diceRoll.getIntValue();
                        String rollCalc = String.valueOf(rollValue);

                        if (e.hasAbility(OptionsConstants.MISC_PAIN_RESISTANCE)) {
                            rollValue = Math.min(12, rollValue + 1);
                            rollCalc = rollValue + " [" + diceRoll.getIntValue() + " + 1] max 12";
                        }

                        int rollTarget = Compute.getConsciousnessNumber(e.getCrew().getHits(pos));
                        Report r = new Report(6029);
                        r.subject = e.getId();
                        r.add(e.getCrew().getCrewType().getRoleName(pos));
                        r.addDesc(e);
                        r.add(e.getCrew().getName(pos));
                        r.add(rollTarget);
                        r.addDataWithTooltip(rollCalc, diceRoll.getReport());

                        if (rollValue >= rollTarget) {
                            r.choose(true);
                            e.getCrew().setUnconscious(false, pos);
                        } else {
                            r.choose(false);
                        }
                        addReport(r);
                    }
                }
            }
        }
    }

    /**
     * Check whether any <code>Entity</code> with a cockpit command console has been scheduled to swap
     * roles between the two crew members.
     */
    protected void resolveConsoleCrewSwaps() {
        for (Iterator<Entity> i = game.getEntities(); i.hasNext(); ) {
            final Entity e = i.next();
            if (e.getCrew().doConsoleRoleSwap()) {
                final Crew crew = e.getCrew();
                final int current = crew.getCurrentPilotIndex();
                Report r = new Report(5560);
                r.subject = e.getId();
                r.add(crew.getNameAndRole(current));
                r.add(crew.getCrewType().getRoleName(0));
                r.addDesc(e);
                addReport(r);
            }
        }
    }

    /*
     * Resolve any outstanding self destructions...
     */
    protected void resolveSelfDestruct() {
        for (Entity e : game.getEntitiesVector()) {
            if (e.getSelfDestructing()) {
                e.setSelfDestructing(false);
                e.setSelfDestructInitiated(true);
                Report r = new Report(5535, Report.PUBLIC);
                r.subject = e.getId();
                r.addDesc(e);
                addReport(r);
            }
        }
    }

    /*
     * Resolve any outstanding crashes from shutting down and being airborne
     * VTOL or WiGE...
     */
    protected void resolveShutdownCrashes() {
        for (Entity e : game.getEntitiesVector()) {
            if (e.isShutDown() && e.isAirborneVTOLorWIGE()
                    && !(e.isDestroyed() || e.isDoomed())) {
                Tank t = (Tank) e;
                t.immobilize();
                reportManager.addReport(entityActionManager.forceLandVTOLorWiGE(t, this), this);
            }
        }
    }

    /**
     * Resolve any potential fatal damage to Capital Fighter after each
     * individual attacker is finished
     */
    protected Vector<Report> checkFatalThresholds(int nextAE, int prevAE) {
        Vector<Report> vDesc = new Vector<>();
        for (Iterator<Entity> e = game.getEntities(); e.hasNext();) {
            Entity en = e.next();
            if (!en.isCapitalFighter() || (nextAE == Entity.NONE)) {
                continue;
            }
            IAero ship = (IAero) en;
            int damage = ship.getCurrentDamage();
            double divisor = 2.0;
            if (game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_AERO_SANITY)) {
                divisor = 20.0;
            }
            if (damage >= ship.getFatalThresh()) {
                int roll = Compute.d6(2)
                        + (int) Math.floor((damage - ship.getFatalThresh())
                        / divisor);
                if (roll > 9) {
                    // Lets auto-eject if we can!
                    if (ship instanceof LandAirMech) {
                        // LAMs eject if the CT destroyed switch is on
                        LandAirMech lam = (LandAirMech) ship;
                        if (lam.isAutoEject()
                                && (!game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                || (game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                && lam.isCondEjectCTDest()))) {
                            reportManager.addReport(ejectEntity(en, true, false), this);
                        }
                    } else {
                        // Aeros eject if the SI Destroyed switch is on
                        Aero aero = (Aero) ship;
                        if (aero.isAutoEject()
                                && (!game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                || (game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                && aero.isCondEjectSIDest()))) {
                            reportManager.addReport(ejectEntity(en, true, false), this);
                        }
                    }
                    vDesc.addAll(entityActionManager.destroyEntity((Entity) ship, "fatal damage threshold", this));
                    ship.doDisbandDamage();
                    if (prevAE != Entity.NONE) {
                        creditKill(en, game.getEntity(prevAE));
                    }
                }
            }
            ship.setCurrentDamage(0);
        }
        return vDesc;
    }

    /**
     * damage an Entity
     *
     * @param te            the <code>Entity</code> to be damaged
     * @param hit           the corresponding <code>HitData</code>
     * @param damage        the <code>int</code> amount of damage
     * @param ammoExplosion a <code>boolean</code> indicating if this is an ammo explosion
     * @return a <code>Vector<Report></code> containing the phase reports
     */
    protected Vector<Report> damageEntity(Entity te, HitData hit, int damage,
                                        boolean ammoExplosion) {
        return damageEntity(te, hit, damage, ammoExplosion, DamageType.NONE,
                false, false);
    }

    /**
     * Deals the listed damage to an entity. Returns a vector of Reports for the
     * phase report
     *
     * @param te     the target entity
     * @param hit    the hit data for the location hit
     * @param damage the damage to apply
     * @return a <code>Vector</code> of <code>Report</code>s
     */
    public Vector<Report> damageEntity(Entity te, HitData hit, int damage) {
        return damageEntity(te, hit, damage, false, DamageType.NONE, false,
                false);
    }

    /**
     * Deals the listed damage to an entity. Returns a vector of Reports for the
     * phase report
     *
     * @param te            the target entity
     * @param hit           the hit data for the location hit
     * @param damage        the damage to apply
     * @param ammoExplosion ammo explosion type damage is applied directly to the IS,
     *                      hurts the pilot, causes auto-ejects, and can blow the unit to
     *                      smithereens
     * @param bFrag         The DamageType of the attack.
     * @param damageIS      Should the target location's internal structure be damaged
     *                      directly?
     * @return a <code>Vector</code> of <code>Report</code>s
     */
    public Vector<Report> damageEntity(Entity te, HitData hit, int damage,
                                       boolean ammoExplosion, DamageType bFrag, boolean damageIS) {
        return damageEntity(te, hit, damage, ammoExplosion, bFrag, damageIS,
                false);
    }

    /**
     * Deals the listed damage to an entity. Returns a vector of Reports for the
     * phase report
     *
     * @param te            the target entity
     * @param hit           the hit data for the location hit
     * @param damage        the damage to apply
     * @param ammoExplosion ammo explosion type damage is applied directly to the IS,
     *                      hurts the pilot, causes auto-ejects, and can blow the unit to
     *                      smithereens
     * @param bFrag         The DamageType of the attack.
     * @param damageIS      Should the target location's internal structure be damaged
     *                      directly?
     * @param areaSatArty   Is the damage from an area saturating artillery attack?
     * @return a <code>Vector</code> of <code>Report</code>s
     */
    public Vector<Report> damageEntity(Entity te, HitData hit, int damage,
                                       boolean ammoExplosion, DamageType bFrag, boolean damageIS,
                                       boolean areaSatArty) {
        return damageEntity(te, hit, damage, ammoExplosion, bFrag, damageIS,
                areaSatArty, true);
    }

    /**
     * Deals the listed damage to an entity. Returns a vector of Reports for the
     * phase report
     *
     * @param te            the target entity
     * @param hit           the hit data for the location hit
     * @param damage        the damage to apply
     * @param ammoExplosion ammo explosion type damage is applied directly to the IS,
     *                      hurts the pilot, causes auto-ejects, and can blow the unit to
     *                      smithereens
     * @param bFrag         The DamageType of the attack.
     * @param damageIS      Should the target location's internal structure be damaged
     *                      directly?
     * @param areaSatArty   Is the damage from an area saturating artillery attack?
     * @param throughFront  Is the damage coming through the hex the unit is facing?
     * @return a <code>Vector</code> of <code>Report</code>s
     */
    public Vector<Report> damageEntity(Entity te, HitData hit, int damage,
                                       boolean ammoExplosion, DamageType bFrag, boolean damageIS,
                                       boolean areaSatArty, boolean throughFront) {
        return damageEntity(te, hit, damage, ammoExplosion, bFrag, damageIS,
                areaSatArty, throughFront, false, false);
    }

    /**
     * Deals the listed damage to an entity. Returns a vector of Reports for the
     * phase report
     *
     * @param te            the target entity
     * @param hit           the hit data for the location hit
     * @param damage        the damage to apply
     * @param ammoExplosion ammo explosion type damage is applied directly to the IS,
     *                      hurts the pilot, causes auto-ejects, and can blow the unit to
     *                      smithereens
     * @param bFrag         The DamageType of the attack.
     * @param damageIS      Should the target location's internal structure be damaged
     *                      directly?
     * @param areaSatArty   Is the damage from an area saturating artillery attack?
     * @param throughFront  Is the damage coming through the hex the unit is facing?
     * @param underWater    Is the damage coming from an underwater attack
     * @return a <code>Vector</code> of <code>Report</code>s
     */
    public Vector<Report> damageEntity(Entity te, HitData hit, int damage,
                                       boolean ammoExplosion, DamageType bFrag, boolean damageIS,
                                       boolean areaSatArty, boolean throughFront, boolean underWater) {
        return damageEntity(te, hit, damage, ammoExplosion, bFrag, damageIS,
                areaSatArty, throughFront, underWater, false);
    }

    /**
     * Deals the listed damage to an entity. Returns a vector of Reports for the
     * phase report
     *
     * @param te            the target entity
     * @param hit           the hit data for the location hit
     * @param damage        the damage to apply
     * @param ammoExplosion ammo explosion type damage is applied directly to the IS,
     *                      hurts the pilot, causes auto-ejects, and can blow the unit to
     *                      smithereens
     * @param damageType         The DamageType of the attack.
     * @param damageIS      Should the target location's internal structure be damaged
     *                      directly?
     * @param areaSatArty   Is the damage from an area saturating artillery attack?
     * @param throughFront  Is the damage coming through the hex the unit is facing?
     * @param underWater    Is the damage coming from an underwater attack?
     * @param nukeS2S       is this a ship-to-ship nuke?
     * @return a <code>Vector</code> of <code>Report</code>s
     */
    public Vector<Report> damageEntity(Entity te, HitData hit, int damage,
                                       boolean ammoExplosion, DamageType damageType, boolean damageIS,
                                       boolean areaSatArty, boolean throughFront, boolean underWater,
                                       boolean nukeS2S) {

        Vector<Report> vDesc = new Vector<>();
        Report r;
        int te_n = te.getId();

        // if this is a fighter squadron then pick an active fighter and pass on
        // the damage
        if (te instanceof FighterSquadron) {
            List<Entity> fighters = te.getActiveSubEntities();

            if (fighters.isEmpty()) {
                return vDesc;
            }
            Entity fighter = fighters.get(hit.getLocation());
            HitData new_hit = fighter.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
            new_hit.setBoxCars(hit.rolledBoxCars());
            new_hit.setGeneralDamageType(hit.getGeneralDamageType());
            new_hit.setCapital(hit.isCapital());
            new_hit.setCapMisCritMod(hit.getCapMisCritMod());
            new_hit.setSingleAV(hit.getSingleAV());
            new_hit.setAttackerId(hit.getAttackerId());
            return damageEntity(fighter, new_hit, damage, ammoExplosion, damageType,
                    damageIS, areaSatArty, throughFront, underWater, nukeS2S);
        }

        // Battle Armor takes full damage to each trooper from area-effect.
        if (areaSatArty && (te instanceof BattleArmor)) {
            r = new Report(6044);
            r.subject = te.getId();
            r.indent(2);
            vDesc.add(r);
            for (int i = 0; i < ((BattleArmor) te).getTroopers(); i++) {
                hit.setLocation(BattleArmor.LOC_TROOPER_1 + i);
                if (te.getInternal(hit) > 0) {
                    vDesc.addAll(damageEntity(te, hit, damage, ammoExplosion, damageType,
                            damageIS, false, throughFront, underWater, nukeS2S));
                }
            }
            return vDesc;
        }

        // This is good for shields if a shield absorps the hit it shouldn't
        // effect the pilot.
        // TC SRM's that hit the head do external and internal damage but its
        // one hit and shouldn't cause
        // 2 hits to the pilot.
        boolean isHeadHit = (te instanceof Mech)
                && (((Mech) te).getCockpitType() != Mech.COCKPIT_TORSO_MOUNTED)
                && (hit.getLocation() == Mech.LOC_HEAD)
                && ((hit.getEffect() & HitData.EFFECT_NO_CRITICALS) != HitData.EFFECT_NO_CRITICALS);

        // booleans to indicate criticals for AT2
        boolean critSI = false;
        boolean critThresh = false;

        // get the relevant damage for damage thresholding
        int threshDamage = damage;
        // weapon groups only get the damage of one weapon
        if ((hit.getSingleAV() > -1)
                && !game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_AERO_SANITY)) {
            threshDamage = hit.getSingleAV();
        }

        // is this capital-scale damage
        boolean isCapital = hit.isCapital();

        // check capital/standard damage
        if (isCapital
                && (!te.isCapitalScale() || game.getOptions().booleanOption(
                OptionsConstants.ADVAERORULES_AERO_SANITY))) {
            damage = 10 * damage;
            threshDamage = 10 * threshDamage;
        }
        if (!isCapital && te.isCapitalScale()
                && !game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_AERO_SANITY)) {
            damage = (int) Math.round(damage / 10.0);
            threshDamage = (int) Math.round(threshDamage / 10.0);
        }

        int damage_orig = damage;

        // show Locations which have rerolled with Edge
        HitData undoneLocation = hit.getUndoneLocation();
        while (undoneLocation != null) {
            r = new Report(6500);
            r.subject = te_n;
            r.indent(2);
            r.addDesc(te);
            r.add(te.getLocationAbbr(undoneLocation));
            vDesc.addElement(r);
            undoneLocation = undoneLocation.getUndoneLocation();
        } // while
        // if edge was uses, give at end overview of remaining
        if (hit.getUndoneLocation() != null) {
            r = new Report(6510);
            r.subject = te_n;
            r.indent(2);
            r.addDesc(te);
            r.add(te.getCrew().getOptions().intOption(OptionsConstants.EDGE));
            vDesc.addElement(r);
        } // if

        boolean autoEject = false;
        if (ammoExplosion) {
            if (te instanceof Mech) {
                Mech mech = (Mech) te;
                if (mech.isAutoEject() && (!game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                        || (game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                        && mech.isCondEjectAmmo()))) {
                    autoEject = true;
                    vDesc.addAll(ejectEntity(te, true));
                }
            } else if (te instanceof Aero) {
                Aero aero = (Aero) te;
                if (aero.isAutoEject() && (!game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                        || (game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                        && aero.isCondEjectAmmo()))) {
                    autoEject = true;
                    vDesc.addAll(ejectEntity(te, true));
                }
            }
        }
        boolean isBattleArmor = te instanceof BattleArmor;
        boolean isPlatoon = !isBattleArmor && (te instanceof Infantry);
        boolean isFerroFibrousTarget = false;
        boolean wasDamageIS = false;
        boolean tookInternalDamage = damageIS;
        Hex te_hex = null;

        boolean hardenedArmor = ((te instanceof Mech) || (te instanceof Tank))
                && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_HARDENED);
        boolean ferroLamellorArmor = ((te instanceof Mech) || (te instanceof Tank) || (te instanceof Aero))
                && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_FERRO_LAMELLOR);
        boolean reflectiveArmor = (((te instanceof Mech) || (te instanceof Tank) || (te instanceof Aero))
                && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_REFLECTIVE))
                || (isBattleArmor && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_BA_REFLECTIVE));
        boolean reactiveArmor = (((te instanceof Mech) || (te instanceof Tank) || (te instanceof Aero))
                && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_REACTIVE))
                || (isBattleArmor && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_BA_REACTIVE));
        boolean ballisticArmor = ((te instanceof Mech) || (te instanceof Tank) || (te instanceof Aero))
                && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_BALLISTIC_REINFORCED);
        boolean impactArmor = (te instanceof Mech)
                && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_IMPACT_RESISTANT);
        boolean bar5 = te.getBARRating(hit.getLocation()) <= 5;

        // TACs from the hit location table
        int crits;
        if ((hit.getEffect() & HitData.EFFECT_CRITICAL) == HitData.EFFECT_CRITICAL) {
            crits = 1;
        } else {
            crits = 0;
        }

        // this is for special crits, like AP and tandem-charge
        int specCrits = 0;

        // the bonus to the crit roll if using the
        // "advanced determining critical hits rule"
        int critBonus = 0;
        if (game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_CRIT_ROLL)
                && (damage_orig > 0)
                && ((te instanceof Mech) || (te instanceof Protomech))) {
            critBonus = Math.min((damage_orig - 1) / 5, 4);
        }

        // Find out if Human TRO plays a part it crit bonus
        Entity ae = game.getEntity(hit.getAttackerId());
        if ((ae != null) && !areaSatArty) {
            if ((te instanceof Mech) && ae.hasAbility(OptionsConstants.MISC_HUMAN_TRO, Crew.HUMANTRO_MECH)) {
                critBonus += 1;
            } else if ((te instanceof Aero) && ae.hasAbility(OptionsConstants.MISC_HUMAN_TRO, Crew.HUMANTRO_AERO)) {
                critBonus += 1;
            } else if ((te instanceof Tank) && ae.hasAbility(OptionsConstants.MISC_HUMAN_TRO, Crew.HUMANTRO_VEE)) {
                critBonus += 1;
            } else if ((te instanceof BattleArmor) && ae.hasAbility(OptionsConstants.MISC_HUMAN_TRO, Crew.HUMANTRO_BA)) {
                critBonus += 1;
            }
        }

        HitData nextHit = null;

        // Some "hits" on a ProtoMech are actually misses.
        if ((te instanceof Protomech) && (hit.getLocation() == Protomech.LOC_NMISS)) {
            Protomech proto = (Protomech) te;
            r = new Report(6035);
            r.subject = te.getId();
            r.indent(2);
            if (proto.isGlider()) {
                r.messageId = 6036;
                proto.setWingHits(proto.getWingHits() + 1);
            }
            vDesc.add(r);
            return vDesc;
        }

        // check for critical hit/miss vs. a BA
        if ((crits > 0) && (te instanceof BattleArmor)) {
            // possible critical miss if the rerolled location isn't alive
            if ((hit.getLocation() >= te.locations()) || (te.getInternal(hit.getLocation()) <= 0)) {
                r = new Report(6037);
                r.add(hit.getLocation());
                r.subject = te_n;
                r.indent(2);
                vDesc.addElement(r);
                return vDesc;
            }
            // otherwise critical hit
            r = new Report(6225);
            r.add(te.getLocationAbbr(hit));
            r.subject = te_n;
            r.indent(2);
            vDesc.addElement(r);

            crits = 0;
            damage = Math.max(te.getInternal(hit.getLocation()) + te.getArmor(hit.getLocation()), damage);
        }

        if ((te.getArmor(hit) > 0) && ((te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_FERRO_FIBROUS)
                || (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_LIGHT_FERRO)
                || (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_HEAVY_FERRO))) {
            isFerroFibrousTarget = true;
        }

        // Infantry with TSM implants get 2d6 burst damage from ATSM munitions
        if (damageType.equals(DamageType.ANTI_TSM) && te.isConventionalInfantry() && te.antiTSMVulnerable()) {
            Roll diceRoll = Compute.rollD6(2);
            r = new Report(6434);
            r.subject = te_n;
            r.add(diceRoll);
            r.indent(2);
            vDesc.addElement(r);
            damage += diceRoll.getIntValue();
        }

        // area effect against infantry is double damage
        if (isPlatoon && areaSatArty) {
            // PBI. Double damage.
            damage *= 2;
            r = new Report(6039);
            r.subject = te_n;
            r.indent(2);
            vDesc.addElement(r);
        }

        // Is the infantry in the open?
        if (ServerHelper.infantryInOpen(te, te_hex, game, isPlatoon, ammoExplosion, hit.isIgnoreInfantryDoubleDamage())) {
            // PBI. Damage is doubled.
            damage *= 2;
            r = new Report(6040);
            r.subject = te_n;
            r.indent(2);
            vDesc.addElement(r);
        }

        // Is the infantry in vacuum?
        if ((isPlatoon || isBattleArmor) && !te.isDestroyed() && !te.isDoomed()
                && game.getPlanetaryConditions().isVacuum()) {
            // PBI. Double damage.
            damage *= 2;
            r = new Report(6041);
            r.subject = te_n;
            r.indent(2);
            vDesc.addElement(r);
        }

        switch (damageType) {
            case FRAGMENTATION:
                // Fragmentation missiles deal full damage to conventional
                // infantry
                // (only) and no damage to other target types.
                if (!isPlatoon) {
                    damage = 0;
                    r = new Report(6050); // For some reason this report never
                    // actually shows up...
                    r.subject = te_n;
                    r.indent(2);
                    vDesc.addElement(r);
                } else {
                    r = new Report(6045); // ...but this one displays just fine.
                    r.subject = te_n;
                    r.indent(2);
                    vDesc.addElement(r);
                }
                break;
            case NONPENETRATING:
                if (!isPlatoon) {
                    damage = 0;
                    r = new Report(6051);
                    r.subject = te_n;
                    r.indent(2);
                    vDesc.addElement(r);
                }
                break;
            case FLECHETTE:
                // Flechette ammo deals full damage to conventional infantry and
                // half damage to other targets (including battle armor).
                if (!isPlatoon) {
                    damage /= 2;
                    r = new Report(6060);
                    r.subject = te_n;
                    r.indent(2);
                    vDesc.addElement(r);
                } else {
                    r = new Report(6055);
                    r.subject = te_n;
                    r.indent(2);
                    vDesc.addElement(r);
                }
                break;
            case ACID:
                if (isFerroFibrousTarget || reactiveArmor || reflectiveArmor
                        || ferroLamellorArmor || bar5) {
                    if (te.getArmor(hit) <= 0) {
                        break; // hitting IS, not acid-affected armor
                    }
                    damage = Math.min(te.getArmor(hit), 3);
                    r = new Report(6061);
                    r.subject = te_n;
                    r.indent(2);
                    r.add(damage);
                    vDesc.addElement(r);
                } else if (isPlatoon) {
                    damage = (int) Math.ceil(damage * 1.5);
                    r = new Report(6062);
                    r.subject = te_n;
                    r.indent(2);
                    vDesc.addElement(r);
                }
                break;
            case INCENDIARY:
                // Incendiary AC ammo does +2 damage to unarmoured infantry
                if (isPlatoon) {
                    damage += 2;
                    r = new Report(6064);
                    r.subject = te_n;
                    r.indent(2);
                    vDesc.addElement(r);
                }
                break;
            case NAIL_RIVET:
                // no damage against armor of BAR rating >=5
                if ((te.getBARRating(hit.getLocation()) >= 5)
                        && (te.getArmor(hit.getLocation()) > 0)) {
                    damage = 0;
                    r = new Report(6063);
                    r.subject = te_n;
                    r.indent(2);
                    vDesc.add(r);
                }
                break;
            default:
                // We can ignore this.
                break;
        }

        // adjust VTOL rotor damage
        if ((te instanceof VTOL) && (hit.getLocation() == VTOL.LOC_ROTOR)
                && (hit.getGeneralDamageType() != HitData.DAMAGE_PHYSICAL)
                && !game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_FULL_ROTOR_HITS)) {
            damage = (damage + 9) / 10;
        }

        // save EI status, in case sensors crit destroys it
        final boolean eiStatus = te.hasActiveEiCockpit();
        // BA using EI implants receive +1 damage from attacks
        if (!(te instanceof Mech) && !(te instanceof Protomech) && eiStatus) {
            damage += 1;
        }

        // check for case on Aeros
        if (te instanceof Aero) {
            Aero a = (Aero) te;
            if (ammoExplosion && a.hasCase()) {
                // damage should be reduced by a factor of 2 for ammo explosions
                // according to p. 161, TW
                damage /= 2;
                r = new Report(9010);
                r.subject = te_n;
                r.add(damage);
                r.indent(3);
                vDesc.addElement(r);
            }
        }

        // infantry armor can reduce damage
        if (isPlatoon && (((Infantry) te).calcDamageDivisor() != 1.0)) {
            r = new Report(6074);
            r.subject = te_n;
            r.indent(2);
            r.add(damage);
            damage = (int) Math.ceil((damage) / ((Infantry) te).calcDamageDivisor());
            r.add(damage);
            vDesc.addElement(r);
        }

        // Allocate the damage
        while (damage > 0) {

            // first check for ammo explosions on aeros separately, because it
            // must be done before
            // standard to capital damage conversions
            if ((te instanceof Aero) && (hit.getLocation() == Aero.LOC_AFT)
                    && !damageIS) {
                for (Mounted mAmmo : te.getAmmo()) {
                    if (mAmmo.isDumping() && !mAmmo.isDestroyed() && !mAmmo.isHit()
                            && !(mAmmo.getType() instanceof BombType)) {
                        // doh. explode it
                        vDesc.addAll(entityActionManager.explodeEquipment(te, mAmmo.getLocation(), mAmmo, this));
                        mAmmo.setHit(true);
                    }
                }
            }

            if (te.isAero()) {
                // chance of a critical if damage greater than threshold
                IAero a = (IAero) te;
                if ((threshDamage > a.getThresh(hit.getLocation()))) {
                    critThresh = true;
                    a.setCritThresh(true);
                }
            }

            // Capital fighters receive damage differently
            if (te.isCapitalFighter()) {
                IAero a = (IAero) te;
                a.setCurrentDamage(a.getCurrentDamage() + damage);
                a.setCapArmor(a.getCapArmor() - damage);
                r = new Report(9065);
                r.subject = te_n;
                r.indent(2);
                r.newlines = 0;
                r.addDesc(te);
                r.add(damage);
                vDesc.addElement(r);
                r = new Report(6085);
                r.subject = te_n;
                r.add(Math.max(a.getCapArmor(), 0));
                vDesc.addElement(r);
                // check to see if this destroyed the entity
                if (a.getCapArmor() <= 0) {
                    // Lets auto-eject if we can!
                    if (a instanceof LandAirMech) {
                        // LAMs eject if the CT destroyed switch is on
                        LandAirMech lam = (LandAirMech) a;
                        if (lam.isAutoEject()
                                && (!game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                || (game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                && lam.isCondEjectCTDest()))) {
                            reportManager.addReport(ejectEntity(te, true, false), this);
                        }
                    } else {
                        // Aeros eject if the SI Destroyed switch is on
                        Aero aero = (Aero) a;
                        if (aero.isAutoEject()
                                && (!game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                || (game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                && aero.isCondEjectSIDest()))) {
                            reportManager.addReport(ejectEntity(te, true, false), this);
                        }
                    }
                    vDesc.addAll(entityActionManager.destroyEntity(te, "Structural Integrity Collapse", this));
                    a.doDisbandDamage();
                    a.setCapArmor(0);
                    if (hit.getAttackerId() != Entity.NONE) {
                        creditKill(te, game.getEntity(hit.getAttackerId()));
                    }
                }
                // check for aero crits from natural 12 or threshold; LAMs take damage as mechs
                if (te instanceof Aero) {
                    checkAeroCrits(vDesc, (Aero) te, hit, damage_orig, critThresh,
                            critSI, ammoExplosion, nukeS2S);
                }
                return vDesc;
            }

            if (!((te instanceof Aero) && ammoExplosion)) {
                // report something different for Aero ammo explosions
                r = new Report(6065);
                r.subject = te_n;
                r.indent(2);
                r.addDesc(te);
                r.add(damage);
                if (damageIS) {
                    r.messageId = 6070;
                }
                r.add(te.getLocationAbbr(hit));
                vDesc.addElement(r);
            }

            // was the section destroyed earlier this phase?
            if (te.getInternal(hit) == IArmorState.ARMOR_DOOMED) {
                // cannot transfer a through armor crit if so
                crits = 0;
            }

            // here goes the fun :)
            // Shields take damage first then cowls then armor whee
            // Shield does not protect from ammo explosions or falls.
            if (!ammoExplosion && !hit.isFallDamage() && !damageIS && te.hasShield()
                    && ((hit.getEffect() & HitData.EFFECT_NO_CRITICALS) != HitData.EFFECT_NO_CRITICALS)) {
                Mech me = (Mech) te;
                int damageNew = me.shieldAbsorptionDamage(damage, hit.getLocation(), hit.isRear());
                // if a shield absorbed the damage then lets tell the world
                // about it.
                if (damageNew != damage) {
                    int absorb = damage - damageNew;
                    te.damageThisPhase += absorb;
                    damage = damageNew;

                    r = new Report(3530);
                    r.subject = te_n;
                    r.indent(3);
                    r.add(absorb);
                    vDesc.addElement(r);

                    if (damage <= 0) {
                        crits = 0;
                        specCrits = 0;
                        isHeadHit = false;
                    }
                }
            }

            // Armored Cowl may absorb some damage from hit
            if (te instanceof Mech) {
                Mech me = (Mech) te;
                if (me.hasCowl() && (hit.getLocation() == Mech.LOC_HEAD)
                        && !throughFront) {
                    int damageNew = me.damageCowl(damage);
                    int damageDiff = damage - damageNew;
                    me.damageThisPhase += damageDiff;
                    damage = damageNew;

                    r = new Report(3520);
                    r.subject = te_n;
                    r.indent(3);
                    r.add(damageDiff);
                    vDesc.addElement(r);
                }
            }

            // So might modular armor, if the location mounts any.
            if (!ammoExplosion && !damageIS
                    && ((hit.getEffect() & HitData.EFFECT_NO_CRITICALS) != HitData.EFFECT_NO_CRITICALS)) {
                int damageNew = te.getDamageReductionFromModularArmor(hit, damage, vDesc);
                int damageDiff = damage - damageNew;
                te.damageThisPhase += damageDiff;
                damage = damageNew;
            }

            // Destroy searchlights on 7+ (torso hits on mechs)
            if (te.hasSearchlight()) {
                boolean spotlightHittable = true;
                int loc = hit.getLocation();
                if (te instanceof Mech) {
                    if ((loc != Mech.LOC_CT) && (loc != Mech.LOC_LT) && (loc != Mech.LOC_RT)) {
                        spotlightHittable = false;
                    }
                } else if (te instanceof Tank) {
                    if (te instanceof SuperHeavyTank) {
                        if ((loc != Tank.LOC_FRONT)
                                && (loc != SuperHeavyTank.LOC_FRONTRIGHT)
                                && (loc != SuperHeavyTank.LOC_FRONTLEFT)
                                && (loc != SuperHeavyTank.LOC_REARRIGHT)
                                && (loc != SuperHeavyTank.LOC_REARLEFT)) {
                            spotlightHittable = false;
                        }
                    } else if (te instanceof LargeSupportTank) {
                        if ((loc != Tank.LOC_FRONT)
                                && (loc != LargeSupportTank.LOC_FRONTRIGHT)
                                && (loc != LargeSupportTank.LOC_FRONTLEFT)
                                && (loc != LargeSupportTank.LOC_REARRIGHT)
                                && (loc != LargeSupportTank.LOC_REARLEFT)) {
                            spotlightHittable = false;
                        }
                    } else {
                        if ((loc != Tank.LOC_FRONT) && (loc != Tank.LOC_RIGHT)
                                && (loc != Tank.LOC_LEFT)) {
                            spotlightHittable = false;
                        }
                    }

                }
                if (spotlightHittable) {
                    Roll diceRoll = Compute.rollD6(2);
                    r = new Report(6072);
                    r.indent(2);
                    r.subject = te_n;
                    r.add("7+");
                    r.add("Searchlight");
                    r.add(diceRoll);
                    vDesc.addElement(r);

                    if (diceRoll.getIntValue() >= 7) {
                        r = new Report(6071);
                        r.subject = te_n;
                        r.indent(2);
                        r.add("Searchlight");
                        vDesc.addElement(r);
                        te.destroyOneSearchlight();
                    }
                }
            }

            // Does an exterior passenger absorb some of the damage?
            if (!damageIS) {
                int nLoc = hit.getLocation();
                Entity passenger = te.getExteriorUnitAt(nLoc, hit.isRear());
                // Does an exterior passenger absorb some of the damage?
                if (!ammoExplosion && (null != passenger) && !passenger.isDoomed()
                        && (damageType != DamageType.IGNORE_PASSENGER)) {
                    damage = damageExternalPassenger(te, hit, damage, vDesc, passenger);
                }

                boolean bTorso = (nLoc == Mech.LOC_CT) || (nLoc == Mech.LOC_RT)
                        || (nLoc == Mech.LOC_LT);

                // Does a swarming unit absorb damage?
                int swarmer = te.getSwarmAttackerId();
                if ((!(te instanceof Mech) || bTorso) && (swarmer != Entity.NONE)
                        && ((hit.getEffect() & HitData.EFFECT_CRITICAL) == 0) && (Compute.d6() >= 5)
                        && (damageType != DamageType.IGNORE_PASSENGER) && !ammoExplosion) {
                    Entity swarm = game.getEntity(swarmer);
                    // Yup. Roll up some hit data for that passenger.
                    r = new Report(6076);
                    r.subject = swarmer;
                    r.indent(3);
                    r.addDesc(swarm);
                    vDesc.addElement(r);

                    HitData passHit = swarm.rollHitLocation(
                            ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);

                    // How much damage will the swarm absorb?
                    int absorb = 0;
                    HitData nextPassHit = passHit;
                    do {
                        if (0 < swarm.getArmor(nextPassHit)) {
                            absorb += swarm.getArmor(nextPassHit);
                        }
                        if (0 < swarm.getInternal(nextPassHit)) {
                            absorb += swarm.getInternal(nextPassHit);
                        }
                        nextPassHit = swarm.getTransferLocation(nextPassHit);
                    } while ((damage > absorb)
                            && (nextPassHit.getLocation() >= 0));

                    // Damage the swarm.
                    int absorbedDamage = Math.min(damage, absorb);
                    Vector<Report> newReports = damageEntity(swarm, passHit,
                            absorbedDamage);
                    for (Report newReport : newReports) {
                        newReport.indent(2);
                    }
                    vDesc.addAll(newReports);

                    // Did some damage pass on?
                    if (damage > absorb) {
                        // Yup. Remove the absorbed damage.
                        damage -= absorb;
                        r = new Report(6080);
                        r.subject = te_n;
                        r.indent(2);
                        r.add(damage);
                        r.addDesc(te);
                        vDesc.addElement(r);
                    } else {
                        // Nope. Return our description.
                        return vDesc;
                    }
                }

                // is this a mech/tank dumping ammo being hit in the rear torso?
                if (((te instanceof Mech) && hit.isRear() && bTorso)
                        || ((te instanceof Tank) && (hit.getLocation() == (te instanceof SuperHeavyTank ? SuperHeavyTank.LOC_REAR
                        : Tank.LOC_REAR)))) {
                    for (Mounted mAmmo : te.getAmmo()) {
                        if (mAmmo.isDumping() && !mAmmo.isDestroyed()
                                && !mAmmo.isHit()) {
                            // doh. explode it
                            vDesc.addAll(entityActionManager.explodeEquipment(te,
                                    mAmmo.getLocation(), mAmmo, this));
                            mAmmo.setHit(true);
                        }
                    }
                }
            }
            // is there armor in the location hit?
            if (!ammoExplosion && (te.getArmor(hit) > 0) && !damageIS) {
                int tmpDamageHold = -1;
                int origDamage = damage;

                if (isPlatoon) {
                    // infantry armour works differently
                    int armor = te.getArmor(hit);
                    int men = te.getInternal(hit);
                    tmpDamageHold = damage % 2;
                    damage /= 2;
                    if ((tmpDamageHold == 1) && (armor >= men)) {
                        // extra 1 point of damage to armor
                        tmpDamageHold = damage;
                        damage++;
                    } else {
                        // extra 0 or 1 point of damage to men
                        tmpDamageHold += damage;
                    }
                    // If the target has Ferro-Lamellor armor, we need to adjust
                    // damage. (4/5ths rounded down),
                    // Also check to eliminate crit chances for damage reduced
                    // to 0
                } else if (ferroLamellorArmor
                        && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING)
                        && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING_MISSILE)
                        && (hit.getGeneralDamageType() != HitData.DAMAGE_IGNORES_DMG_REDUCTION)) {
                    tmpDamageHold = damage;
                    damage = (int) Math.floor((((double) damage) * 4) / 5);
                    if (damage <= 0) {
                        isHeadHit = false;
                        crits = 0;
                    }
                    r = new Report(6073);
                    r.subject = te_n;
                    r.indent(3);
                    r.add(damage);
                    vDesc.addElement(r);
                } else if (ballisticArmor
                        && ((hit.getGeneralDamageType() == HitData.DAMAGE_ARMOR_PIERCING_MISSILE)
                        || (hit.getGeneralDamageType() == HitData.DAMAGE_ARMOR_PIERCING)
                        || (hit.getGeneralDamageType() == HitData.DAMAGE_BALLISTIC)
                        || (hit.getGeneralDamageType() == HitData.DAMAGE_MISSILE))) {
                    tmpDamageHold = damage;
                    damage = Math.max(1, damage / 2);
                    r = new Report(6088);
                    r.subject = te_n;
                    r.indent(3);
                    r.add(damage);
                    vDesc.addElement(r);
                } else if (impactArmor
                        && (hit.getGeneralDamageType() == HitData.DAMAGE_PHYSICAL)) {
                    tmpDamageHold = damage;
                    damage -= (int) Math.ceil((double) damage / 3);
                    damage = Math.max(1, damage);
                    r = new Report(6089);
                    r.subject = te_n;
                    r.indent(3);
                    r.add(damage);
                    vDesc.addElement(r);
                } else if (reflectiveArmor
                        && (hit.getGeneralDamageType() == HitData.DAMAGE_PHYSICAL)
                        && !isBattleArmor) { // BA reflec does not receive extra physical damage
                    tmpDamageHold = damage;
                    int currArmor = te.getArmor(hit);
                    int dmgToDouble = Math.min(damage, currArmor / 2);
                    damage += dmgToDouble;
                    r = new Report(6066);
                    r.subject = te_n;
                    r.indent(3);
                    r.add(currArmor);
                    r.add(tmpDamageHold);
                    r.add(dmgToDouble);
                    r.add(damage);
                    vDesc.addElement(r);
                } else if (reflectiveArmor && areaSatArty && !isBattleArmor) {
                    tmpDamageHold = damage; // BA reflec does not receive extra AE damage
                    int currArmor = te.getArmor(hit);
                    int dmgToDouble = Math.min(damage, currArmor / 2);
                    damage += dmgToDouble;
                    r = new Report(6087);
                    r.subject = te_n;
                    r.indent(3);
                    r.add(currArmor);
                    r.add(tmpDamageHold);
                    r.add(dmgToDouble);
                    r.add(damage);
                    vDesc.addElement(r);
                } else if (reflectiveArmor
                        && (hit.getGeneralDamageType() == HitData.DAMAGE_ENERGY)) {
                    tmpDamageHold = damage;
                    damage = (int) Math.floor(((double) damage) / 2);
                    if (tmpDamageHold == 1) {
                        damage = 1;
                    }
                    r = new Report(6067);
                    r.subject = te_n;
                    r.indent(3);
                    r.add(damage);
                    vDesc.addElement(r);
                } else if (reactiveArmor
                        && ((hit.getGeneralDamageType() == HitData.DAMAGE_MISSILE)
                        || (hit.getGeneralDamageType() == HitData.DAMAGE_ARMOR_PIERCING_MISSILE) ||
                        areaSatArty)) {
                    tmpDamageHold = damage;
                    damage = (int) Math.floor(((double) damage) / 2);
                    if (tmpDamageHold == 1) {
                        damage = 1;
                    }
                    r = new Report(6068);
                    r.subject = te_n;
                    r.indent(3);
                    r.add(damage);
                    vDesc.addElement(r);
                }

                // If we're using optional tank damage thresholds, setup our hit
                // effects now...
                if ((te instanceof Tank)
                        && game.getOptions()
                        .booleanOption(OptionsConstants.ADVCOMBAT_VEHICLES_THRESHOLD)
                        && !((te instanceof VTOL) || (te instanceof GunEmplacement))) {
                    int thresh = (int) Math.ceil(
                            (game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_VEHICLES_THRESHOLD_VARIABLE)
                                    ? te.getArmor(hit)
                                    : te.getOArmor(hit)) / (double) game.getOptions().intOption(
                                    OptionsConstants.ADVCOMBAT_VEHICLES_THRESHOLD_DIVISOR));

                    // adjust for hardened armor
                    if (hardenedArmor
                            && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING)
                            && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING_MISSILE)
                            && (hit.getGeneralDamageType() != HitData.DAMAGE_IGNORES_DMG_REDUCTION)) {
                        thresh *= 2;
                    }

                    if ((damage > thresh) || (te.getArmor(hit) < damage)) {
                        hit.setEffect(((Tank) te).getPotCrit());
                        ((Tank) te).setOverThresh(true);
                        // TACs from the hit location table
                        crits = ((hit.getEffect() & HitData.EFFECT_CRITICAL)
                                == HitData.EFFECT_CRITICAL) ? 1 : 0;
                    } else {
                        ((Tank) te).setOverThresh(false);
                        crits = 0;
                    }
                }

                // if there's a mast mount in the rotor, it and all other
                // equipment
                // on it get destroyed
                if ((te instanceof VTOL)
                        && (hit.getLocation() == VTOL.LOC_ROTOR)
                        && te.hasWorkingMisc(MiscType.F_MAST_MOUNT, -1,
                        VTOL.LOC_ROTOR)) {
                    r = new Report(6081);
                    r.subject = te_n;
                    r.indent(2);
                    vDesc.addElement(r);
                    for (Mounted mount : te.getMisc()) {
                        if (mount.getLocation() == VTOL.LOC_ROTOR) {
                            mount.setHit(true);
                        }
                    }
                }
                // Need to account for the possibility of hardened armor here
                int armorThreshold = te.getArmor(hit);
                if (hardenedArmor
                        && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING)
                        && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING_MISSILE)
                        && (hit.getGeneralDamageType() != HitData.DAMAGE_IGNORES_DMG_REDUCTION)) {
                    armorThreshold *= 2;
                    armorThreshold -= (te.isHardenedArmorDamaged(hit)) ? 1 : 0;
                    vDesc.lastElement().newlines = 0;
                    r = new Report(6069);
                    r.subject = te_n;
                    r.indent(3);
                    int reportedDamage = damage / 2;
                    if ((damage % 2) > 0) {
                        r.add(reportedDamage + ".5");
                    } else {
                        r.add(reportedDamage);
                    }

                    vDesc.addElement(r);
                }
                if (armorThreshold >= damage) {

                    // armor absorbs all damage
                    // Hardened armor deals with damage in its own fashion...
                    if (hardenedArmor
                            && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING)
                            && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING_MISSILE)
                            && (hit.getGeneralDamageType() != HitData.DAMAGE_IGNORES_DMG_REDUCTION)) {
                        armorThreshold -= damage;
                        te.setHardenedArmorDamaged(hit, (armorThreshold % 2) > 0);
                        te.setArmor((armorThreshold / 2) + (armorThreshold % 2), hit);
                    } else {
                        te.setArmor(te.getArmor(hit) - damage, hit);
                    }

                    // set "armor damage" flag for HarJel II/III
                    // we only care about this if there is armor remaining,
                    // so don't worry about the case where damage exceeds
                    // armorThreshold
                    if ((te instanceof Mech) && (damage > 0)) {
                        ((Mech) te).setArmorDamagedThisTurn(hit.getLocation(), true);
                    }

                    // if the armor is hardened, any penetrating crits are
                    // rolled at -2
                    if (hardenedArmor) {
                        critBonus -= 2;
                    }

                    if (tmpDamageHold >= 0) {
                        te.damageThisPhase += tmpDamageHold;
                    } else {
                        te.damageThisPhase += damage;
                    }
                    damage = 0;
                    if (!te.isHardenedArmorDamaged(hit)) {
                        r = new Report(6085);
                    } else {
                        r = new Report(6086);
                    }

                    r.subject = te_n;
                    r.indent(3);
                    r.add(te.getArmor(hit));
                    vDesc.addElement(r);

                    // telemissiles are destroyed if they lose all armor
                    if ((te instanceof TeleMissile)
                            && (te.getArmor(hit) == damage)) {
                        vDesc.addAll(entityActionManager.destroyEntity(te, "damage", false, this));
                    }

                } else {
                    // damage goes on to internal
                    int absorbed = Math.max(te.getArmor(hit), 0);
                    if (hardenedArmor
                            && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING)
                            && (hit.getGeneralDamageType() != HitData.DAMAGE_ARMOR_PIERCING_MISSILE)) {
                        absorbed = (absorbed * 2)
                                - ((te.isHardenedArmorDamaged(hit)) ? 1 : 0);
                    }
                    if (reflectiveArmor && (hit.getGeneralDamageType() == HitData.DAMAGE_PHYSICAL)
                            && !isBattleArmor) {
                        absorbed = (int) Math.ceil(absorbed / 2.0);
                        damage = tmpDamageHold;
                        tmpDamageHold = 0;
                    }
                    te.setArmor(IArmorState.ARMOR_DESTROYED, hit);
                    if (tmpDamageHold >= 0) {
                        te.damageThisPhase += 2 * absorbed;
                    } else {
                        te.damageThisPhase += absorbed;
                    }
                    damage -= absorbed;
                    r = new Report(6090);
                    r.subject = te_n;
                    r.indent(3);
                    vDesc.addElement(r);
                    if (te instanceof GunEmplacement) {
                        // gun emplacements have no internal,
                        // destroy the section
                        te.destroyLocation(hit.getLocation());
                        r = new Report(6115);
                        r.subject = te_n;
                        vDesc.addElement(r);

                        if (te.getTransferLocation(hit).getLocation() == Entity.LOC_DESTROYED) {
                            vDesc.addAll(entityActionManager.destroyEntity(te, "damage", false, this));
                        }
                    }
                }

                // targets with BAR armor get crits, depending on damage and BAR
                // rating
                if (te.hasBARArmor(hit.getLocation())) {
                    if (origDamage > te.getBARRating(hit.getLocation())) {
                        if (te.hasArmoredChassis()) {
                            // crit roll with -1 mod
                            vDesc.addAll(criticalEntity(te, hit.getLocation(),
                                    hit.isRear(), -1 + critBonus, damage_orig));
                        } else {
                            vDesc.addAll(criticalEntity(te, hit.getLocation(),
                                    hit.isRear(), critBonus, damage_orig));
                        }
                    }
                }

                if ((tmpDamageHold > 0) && isPlatoon) {
                    damage = tmpDamageHold;
                }
            }

            // For optional tank damage thresholds, the overthresh flag won't
            // be set if IS is damaged, so set it here.
            if ((te instanceof Tank)
                    && ((te.getArmor(hit) < 1) || damageIS)
                    && game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_VEHICLES_THRESHOLD)
                    && !((te instanceof VTOL)
                    || (te instanceof GunEmplacement))) {
                ((Tank) te).setOverThresh(true);
            }

            // is there damage remaining?
            if (damage > 0) {

                // if this is an Aero then I need to apply internal damage
                // to the SI after halving it. Return from here to prevent
                // further processing
                if (te instanceof Aero) {
                    Aero a = (Aero) te;

                    // check for large craft ammo explosions here: damage vented through armor, excess
                    // dissipating, much like Tank CASE.
                    if (ammoExplosion && te.isLargeCraft()) {
                        te.damageThisPhase += damage;
                        r = new Report(6128);
                        r.subject = te_n;
                        r.indent(2);
                        r.add(damage);
                        int loc = hit.getLocation();
                        //Roll for broadside weapons so fore/aft side armor facing takes the damage
                        if (loc == Warship.LOC_LBS) {
                            int locRoll = Compute.d6();
                            if (locRoll < 4) {
                                loc = Jumpship.LOC_FLS;
                            } else {
                                loc = Jumpship.LOC_ALS;
                            }
                        }
                        if (loc == Warship.LOC_RBS) {
                            int locRoll = Compute.d6();
                            if (locRoll < 4) {
                                loc = Jumpship.LOC_FRS;
                            } else {
                                loc = Jumpship.LOC_ARS;
                            }
                        }
                        r.add(te.getLocationAbbr(loc));
                        vDesc.add(r);
                        if (damage > te.getArmor(loc)) {
                            te.setArmor(IArmorState.ARMOR_DESTROYED, loc);
                            r = new Report(6090);
                        } else {
                            te.setArmor(te.getArmor(loc) - damage, loc);
                            r = new Report(6085);
                            r.add(te.getArmor(loc));
                        }
                        r.subject = te_n;
                        r.indent(3);
                        vDesc.add(r);
                        damage = 0;
                    }

                    // check for overpenetration
                    if (game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_STRATOPS_OVER_PENETRATE)) {
                        int opRoll = Compute.d6(1);
                        if (((te instanceof Jumpship) && !(te instanceof Warship) && (opRoll > 3))
                                || ((te instanceof Dropship) && (opRoll > 4))
                                || ((te instanceof Warship) && (a.get0SI() <= 30) && (opRoll > 5))) {
                            // over-penetration happened
                            r = new Report(9090);
                            r.subject = te_n;
                            r.newlines = 0;
                            vDesc.addElement(r);
                            int new_loc = a.getOppositeLocation(hit.getLocation());
                            damage = Math.min(damage, te.getArmor(new_loc));
                            // We don't want to deal negative damage
                            damage = Math.max(damage, 0);
                            r = new Report(6065);
                            r.subject = te_n;
                            r.indent(2);
                            r.newlines = 0;
                            r.addDesc(te);
                            r.add(damage);
                            r.add(te.getLocationAbbr(new_loc));
                            vDesc.addElement(r);
                            te.setArmor(te.getArmor(new_loc) - damage, new_loc);
                            if ((te instanceof Warship) || (te instanceof Dropship)) {
                                damage = 2;
                            } else {
                                damage = 0;
                            }
                        }
                    }

                    // divide damage in half
                    // do not divide by half if it is an ammo explosion
                    if (!ammoExplosion && !nukeS2S
                            && !game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_AERO_SANITY)) {
                        damage /= 2;
                    }

                    // this should result in a crit
                    // but only if it really did damage after rounding down
                    if (damage > 0) {
                        critSI = true;
                    }

                    // Now apply damage to the structural integrity
                    a.setSI(a.getSI() - damage);
                    te.damageThisPhase += damage;
                    // send the report
                    r = new Report(1210);
                    r.subject = te_n;
                    r.newlines = 1;
                    if (!ammoExplosion) {
                        r.messageId = 9005;
                    }
                    //Only for fighters
                    if (ammoExplosion && !a.isLargeCraft()) {
                        r.messageId = 9006;
                    }
                    r.add(damage);
                    r.add(Math.max(a.getSI(), 0));
                    vDesc.addElement(r);
                    // check to see if this would destroy the ASF
                    if (a.getSI() <= 0) {
                        // Lets auto-eject if we can!
                        if (a.isAutoEject()
                                && (!game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                || (game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                && a.isCondEjectSIDest()))) {
                            vDesc.addAll(ejectEntity(te, true, false));
                        } else {
                            vDesc.addAll(entityActionManager.destroyEntity(te,"Structural Integrity Collapse", this));
                        }
                        a.setSI(0);
                        if (hit.getAttackerId() != Entity.NONE) {
                            creditKill(a, game.getEntity(hit.getAttackerId()));
                        }
                    }
                    checkAeroCrits(vDesc, a, hit, damage_orig, critThresh, critSI, ammoExplosion, nukeS2S);
                    return vDesc;
                }

                // Check for CASE II right away. if so reduce damage to 1
                // and let it hit the IS.
                // Also remove as much of the rear armor as allowed by the
                // damage. If arm/leg/head
                // Then they lose all their armor if its less then the
                // explosion damage.
                if (ammoExplosion && te.hasCASEII(hit.getLocation())) {
                    // 1 point of damage goes to IS
                    damage--;
                    // Remaining damage prevented by CASE II
                    r = new Report(6126);
                    r.subject = te_n;
                    r.add(damage);
                    r.indent(3);
                    vDesc.addElement(r);
                    int loc = hit.getLocation();
                    if ((te instanceof Mech) && ((loc == Mech.LOC_HEAD) || ((Mech) te).isArm(loc)
                            || te.locationIsLeg(loc))) {
                        int half = (int) Math.ceil(te.getOArmor(loc, false) / 2.0);
                        if (damage > half) {
                            damage = half;
                        }
                        if (damage >= te.getArmor(loc, false)) {
                            te.setArmor(IArmorState.ARMOR_DESTROYED, loc, false);
                        } else {
                            te.setArmor(te.getArmor(loc, false) - damage, loc, false);
                        }
                    } else {
                        if (damage >= te.getArmor(loc, true)) {
                            te.setArmor(IArmorState.ARMOR_DESTROYED, loc, true);
                        } else {
                            te.setArmor(te.getArmor(loc, true) - damage, loc, true);
                        }
                    }

                    if (te.getInternal(hit) > 0) {
                        // Mek takes 1 point of IS damage
                        damage = 1;
                    } else {
                        damage = 0;
                    }

                    te.damageThisPhase += damage;

                    Roll diceRoll = Compute.rollD6(2);
                    r = new Report(6127);
                    r.subject = te.getId();
                    r.add(diceRoll);
                    vDesc.add(r);

                    if (diceRoll.getIntValue() >= 8) {
                        hit.setEffect(HitData.EFFECT_NO_CRITICALS);
                    }
                }
                // check for tank CASE here: damage to rear armor, excess
                // dissipating, and a crew stunned crit
                if (ammoExplosion && (te instanceof Tank)
                        && te.locationHasCase(Tank.LOC_BODY)) {
                    te.damageThisPhase += damage;
                    r = new Report(6124);
                    r.subject = te_n;
                    r.indent(2);
                    r.add(damage);
                    vDesc.add(r);
                    int loc = (te instanceof SuperHeavyTank) ? SuperHeavyTank.LOC_REAR
                            : (te instanceof LargeSupportTank) ? LargeSupportTank.LOC_REAR : Tank.LOC_REAR;
                    if (damage > te.getArmor(loc)) {
                        te.setArmor(IArmorState.ARMOR_DESTROYED, loc);
                        r = new Report(6090);
                    } else {
                        te.setArmor(te.getArmor(loc) - damage, loc);
                        r = new Report(6085);
                        r.add(te.getArmor(loc));
                    }
                    r.subject = te_n;
                    r.indent(3);
                    vDesc.add(r);
                    damage = 0;
                    int critIndex;
                    if (((Tank) te).isCommanderHit()
                            && ((Tank) te).isDriverHit()) {
                        critIndex = Tank.CRIT_CREW_KILLED;
                    } else {
                        critIndex = Tank.CRIT_CREW_STUNNED;
                    }
                    vDesc.addAll(applyCriticalHit(te, Entity.NONE, new CriticalSlot(0, critIndex), true, 0, false));
                }

                // is there internal structure in the location hit?
                if (te.getInternal(hit) > 0) {

                    // Now we need to consider alternate structure types!
                    int tmpDamageHold = -1;
                    if ((te instanceof Mech)
                            && ((Mech) te).hasCompositeStructure()) {
                        tmpDamageHold = damage;
                        damage *= 2;
                        r = new Report(6091);
                        r.subject = te_n;
                        r.indent(3);
                        vDesc.add(r);
                    }
                    if ((te instanceof Mech)
                            && ((Mech) te).hasReinforcedStructure()) {
                        tmpDamageHold = damage;
                        damage /= 2;
                        damage += tmpDamageHold % 2;
                        r = new Report(6092);
                        r.subject = te_n;
                        r.indent(3);
                        vDesc.add(r);
                    }
                    if ((te.getInternal(hit) > damage) && (damage > 0)) {
                        // internal structure absorbs all damage
                        te.setInternal(te.getInternal(hit) - damage, hit);
                        // Triggers a critical hit on Vehicles and Mechs.
                        if (!isPlatoon && !isBattleArmor) {
                            crits++;
                        }
                        tookInternalDamage = true;
                        // Alternate structures don't affect our damage total
                        // for later PSR purposes, so use the previously stored
                        // value here as necessary.
                        te.damageThisPhase += (tmpDamageHold > -1) ?
                                tmpDamageHold : damage;
                        damage = 0;
                        r = new Report(6100);
                        r.subject = te_n;
                        r.indent(3);
                        // Infantry platoons have men not "Internals".
                        if (isPlatoon) {
                            r.messageId = 6095;
                        }
                        r.add(te.getInternal(hit));
                        vDesc.addElement(r);
                    } else if (damage > 0) {
                        // Triggers a critical hit on Vehicles and Mechs.
                        if (!isPlatoon && !isBattleArmor) {
                            crits++;
                        }
                        // damage transfers, maybe
                        int absorbed = Math.max(te.getInternal(hit), 0);

                        // Handle ProtoMech pilot damage
                        // due to location destruction
                        if (te instanceof Protomech) {
                            int hits = Protomech.POSSIBLE_PILOT_DAMAGE[hit.getLocation()]
                                    - ((Protomech) te).getPilotDamageTaken(hit.getLocation());
                            if (hits > 0) {
                                vDesc.addAll(damageCrew(te, hits));
                                ((Protomech) te).setPilotDamageTaken(hit.getLocation(),
                                        Protomech.POSSIBLE_PILOT_DAMAGE[hit.getLocation()]);
                            }
                        }

                        // Platoon, Trooper, or Section destroyed message
                        r = new Report(1210);
                        r.subject = te_n;
                        if (isPlatoon) {
                            // Infantry have only one section, and
                            // are therefore destroyed.
                            if (((Infantry) te).isSquad()) {
                                r.messageId = 6106; // Squad Killed
                            } else {
                                r.messageId = 6105; // Platoon Killed
                            }
                        } else if (isBattleArmor) {
                            r.messageId = 6110;
                        } else {
                            r.messageId = 6115;
                        }
                        r.indent(3);
                        vDesc.addElement(r);

                        // If a sidetorso got destroyed, and the
                        // corresponding arm is not yet destroyed, add
                        // it as a club to that hex (p.35 BMRr)
                        if ((te instanceof Mech)
                                && (((hit.getLocation() == Mech.LOC_RT)
                                && (te.getInternal(Mech.LOC_RARM) > 0))
                                || ((hit.getLocation() == Mech.LOC_LT)
                                && (te.getInternal(Mech.LOC_LARM) > 0)))) {
                            int blownOffLocation;
                            if (hit.getLocation() == Mech.LOC_RT) {
                                blownOffLocation = Mech.LOC_RARM;
                            } else {
                                blownOffLocation = Mech.LOC_LARM;
                            }
                            te.destroyLocation(blownOffLocation, true);
                            r = new Report(6120);
                            r.subject = te_n;
                            r.add(te.getLocationName(blownOffLocation));
                            vDesc.addElement(r);
                            Hex h = game.getBoard().getHex(te.getPosition());
                            if (null != h) {
                                if (te instanceof BipedMech) {
                                    if (!h.containsTerrain(Terrains.ARMS)) {
                                        h.addTerrain(new Terrain(Terrains.ARMS, 1));
                                    } else {
                                        h.addTerrain(new Terrain(Terrains.ARMS, h.terrainLevel(Terrains.ARMS) + 1));
                                    }
                                } else if (!h.containsTerrain(Terrains.LEGS)) {
                                    h.addTerrain(new Terrain(Terrains.LEGS, 1));
                                } else {
                                    h.addTerrain(new Terrain(Terrains.LEGS, h.terrainLevel(Terrains.LEGS) + 1));
                                }
                                communicationManager.sendChangedHex(te.getPosition(), this);
                            }
                        }

                        // Troopers riding on a location
                        // all die when the location is destroyed.
                        if ((te instanceof Mech) || (te instanceof Tank)) {
                            Entity passenger = te.getExteriorUnitAt(
                                    hit.getLocation(), hit.isRear());
                            if ((null != passenger) && !passenger.isDoomed()) {
                                HitData passHit = passenger
                                        .getTrooperAtLocation(hit, te);
                                // ensures a kill
                                passHit.setEffect(HitData.EFFECT_CRITICAL);
                                if (passenger.getInternal(passHit) > 0) {
                                    vDesc.addAll(damageEntity(passenger,
                                            passHit, damage));
                                }
                                passHit = new HitData(hit.getLocation(),
                                        !hit.isRear());
                                passHit = passenger.getTrooperAtLocation(
                                        passHit, te);
                                // ensures a kill
                                passHit.setEffect(HitData.EFFECT_CRITICAL);
                                if (passenger.getInternal(passHit) > 0) {
                                    vDesc.addAll(damageEntity(passenger,
                                            passHit, damage));
                                }
                            }
                        }

                        // BA inferno explosions
                        if (te instanceof BattleArmor) {
                            int infernos = 0;
                            for (Mounted m : te.getEquipment()) {
                                if (m.getType() instanceof AmmoType) {
                                    AmmoType at = (AmmoType) m.getType();
                                    if (((at.getAmmoType() == AmmoType.T_SRM) || (at.getAmmoType() == AmmoType.T_MML))
                                            && (at.getMunitionType().contains(AmmoType.Munitions.M_INFERNO))) {
                                        infernos += at.getRackSize() * m.getHittableShotsLeft();
                                    }
                                } else if (m.getType().hasFlag(MiscType.F_FIRE_RESISTANT)) {
                                    // immune to inferno explosion
                                    infernos = 0;
                                    break;
                                }
                            }
                            if (infernos > 0) {
                                Roll diceRoll = Compute.rollD6(2);
                                r = new Report(6680);
                                r.add(diceRoll);
                                vDesc.add(r);

                                if (diceRoll.getIntValue() >= 8) {
                                    Coords c = te.getPosition();
                                    if (c == null) {
                                        Entity transport = game.getEntity(te.getTransportId());
                                        if (transport != null) {
                                            c = transport.getPosition();
                                        }
                                        vPhaseReport.addAll(environmentalEffectManager.deliverInfernoMissiles(te, te, infernos, this));
                                    }
                                    if (c != null) {
                                        vPhaseReport.addAll(environmentalEffectManager.deliverInfernoMissiles(te,
                                                new HexTarget(c, Targetable.TYPE_HEX_ARTILLERY),
                                                infernos, this));
                                    }
                                }
                            }
                        }

                        // Mark off the internal structure here, but *don't*
                        // destroy the location just yet -- there are checks
                        // still to run!
                        te.setInternal(0, hit);
                        te.damageThisPhase += absorbed;
                        damage -= absorbed;

                        // Now we need to consider alternate structure types!
                        if (tmpDamageHold > 0) {
                            if (((Mech) te).hasCompositeStructure()) {
                                // If there's a remainder, we can actually
                                // ignore it.
                                damage /= 2;
                            } else if (((Mech) te).hasReinforcedStructure()) {
                                damage *= 2;
                                damage -= tmpDamageHold % 2;
                            }
                        }
                    }
                }
                if (te.getInternal(hit) <= 0) {
                    // internal structure is gone, what are the transfer
                    // potentials?
                    nextHit = te.getTransferLocation(hit);
                    if (nextHit.getLocation() == Entity.LOC_DESTROYED) {
                        if (te instanceof Mech) {
                            // Start with the number of engine crits in this
                            // location, if any...
                            te.engineHitsThisPhase += te.getNumberOfCriticals(
                                    CriticalSlot.TYPE_SYSTEM,
                                    Mech.SYSTEM_ENGINE, hit.getLocation());
                            // ...then deduct the ones destroyed previously or
                            // critically
                            // hit this round already. That leaves the ones
                            // actually
                            // destroyed with the location.
                            te.engineHitsThisPhase -= te.getHitCriticals(
                                    CriticalSlot.TYPE_SYSTEM,
                                    Mech.SYSTEM_ENGINE, hit.getLocation());
                        }

                        boolean engineExploded = checkEngineExplosion(te,
                                vDesc, te.engineHitsThisPhase);

                        if (!engineExploded) {
                            // Entity destroyed. Ammo explosions are
                            // neither survivable nor salvageable.
                            // Only ammo explosions in the CT are devastating.
                            vDesc.addAll(entityActionManager.destroyEntity(te, "damage", !ammoExplosion,
                                    !((ammoExplosion || areaSatArty) && ((te instanceof Tank)
                                            || ((te instanceof Mech) && (hit.getLocation() == Mech.LOC_CT)))), this));
                            // If the head is destroyed, kill the crew.

                            if ((te instanceof Mech) && (hit.getLocation() == Mech.LOC_HEAD)
                                    && !te.getCrew().isDead() && !te.getCrew().isDoomed()
                                    && game.getOptions().booleanOption(
                                    OptionsConstants.ADVANCED_TACOPS_SKIN_OF_THE_TEETH_EJECTION)) {
                                Mech mech = (Mech) te;
                                if (mech.isAutoEject()
                                        && (!game.getOptions().booleanOption(
                                        OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                        || (game.getOptions().booleanOption(
                                        OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                        && mech.isCondEjectHeadshot()))) {
                                    autoEject = true;
                                    vDesc.addAll(ejectEntity(te, true, true));
                                }
                            }

                            if ((te instanceof Mech) && (hit.getLocation() == Mech.LOC_CT)
                                    && !te.getCrew().isDead() && !te.getCrew().isDoomed()) {
                                Mech mech = (Mech) te;
                                if (mech.isAutoEject()
                                        && game.getOptions().booleanOption(
                                        OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                        && mech.isCondEjectCTDest()) {
                                    if (mech.getCrew().getHits() < 5) {
                                        Report.addNewline(vDesc);
                                        mech.setDoomed(false);
                                        mech.setDoomed(true);
                                    }
                                    autoEject = true;
                                    vDesc.addAll(ejectEntity(te, true));
                                }
                            }

                            if ((hit.getLocation() == Mech.LOC_HEAD)
                                    || ((hit.getLocation() == Mech.LOC_CT)
                                    && ((ammoExplosion && !autoEject) || areaSatArty))) {
                                te.getCrew().setDoomed(true);
                            }
                            if (game.getOptions().booleanOption(
                                    OptionsConstants.ADVGRNDMOV_AUTO_ABANDON_UNIT)) {
                                vDesc.addAll(abandonEntity(te));
                            }
                        }

                        // nowhere for further damage to go
                        damage = 0;
                    } else if (nextHit.getLocation() == Entity.LOC_NONE) {
                        // Rest of the damage is wasted.
                        damage = 0;
                    } else if (ammoExplosion
                            && te.locationHasCase(hit.getLocation())) {
                        // Remaining damage prevented by CASE
                        r = new Report(6125);
                        r.subject = te_n;
                        r.add(damage);
                        r.indent(3);
                        vDesc.addElement(r);

                        // The target takes no more damage from the explosion.
                        damage = 0;
                    } else if (damage > 0) {
                        // remaining damage transfers
                        r = new Report(6130);
                        r.subject = te_n;
                        r.indent(2);
                        r.add(damage);
                        r.add(te.getLocationAbbr(nextHit));
                        vDesc.addElement(r);

                        // If there are split weapons in this location, mark it
                        // as hit, even if it took no criticals.
                        for (Mounted m : te.getWeaponList()) {
                            if (m.isSplit()) {
                                if ((m.getLocation() == hit.getLocation())
                                        || (m.getLocation() == nextHit
                                        .getLocation())) {
                                    te.setWeaponHit(m);
                                }
                            }
                        }
                        // if this is damage from a nail/rivet gun, and we
                        // transfer
                        // to a location that has armor, and BAR >=5, no damage
                        if ((damageType == DamageType.NAIL_RIVET)
                                && (te.getArmor(nextHit.getLocation()) > 0)
                                && (te.getBARRating(nextHit.getLocation()) >= 5)) {
                            damage = 0;
                            r = new Report(6065);
                            r.subject = te_n;
                            r.indent(2);
                            vDesc.add(r);
                        }
                    }
                }
            } else if (hit.getSpecCrit()) {
                // ok, we dealt damage but didn't go on to internal
                // we get a chance of a crit, using Armor Piercing.
                // but only if we don't have hardened, Ferro-Lamellor, or reactive armor
                if (!hardenedArmor && !ferroLamellorArmor && !reactiveArmor) {
                    specCrits++;
                }
            }
            // check for breaching
            vDesc.addAll(breachCheck(te, hit.getLocation(), null, underWater));

            // resolve special results
            if ((hit.getEffect() & HitData.EFFECT_VEHICLE_MOVE_DAMAGED) == HitData.EFFECT_VEHICLE_MOVE_DAMAGED) {
                vDesc.addAll(vehicleMotiveDamage((Tank) te, hit.getMotiveMod()));
            }
            // Damage from any source can break spikes
            if (te.hasWorkingMisc(MiscType.F_SPIKES, -1, hit.getLocation())) {
                vDesc.add(checkBreakSpikes(te, hit.getLocation()));
            }

            // roll all critical hits against this location
            // unless the section destroyed in a previous phase?
            // Cause a crit.
            if ((te.getInternal(hit) != IArmorState.ARMOR_DESTROYED)
                    && ((hit.getEffect() & HitData.EFFECT_NO_CRITICALS) != HitData.EFFECT_NO_CRITICALS)) {
                for (int i = 0; i < crits; i++) {
                    vDesc.addAll(criticalEntity(te, hit.getLocation(), hit.isRear(),
                            hit.glancingMod() + critBonus, damage_orig, damageType));
                }
                crits = 0;

                for (int i = 0; i < specCrits; i++) {
                    // against BAR or reflective armor, we get a +2 mod
                    int critMod = te.hasBARArmor(hit.getLocation()) ? 2 : 0;
                    critMod += (reflectiveArmor && !isBattleArmor) ? 2 : 0; // BA
                    // against impact armor, we get a +1 mod
                    critMod += impactArmor ? 1 : 0;
                    // hardened armour has no crit penalty
                    if (!hardenedArmor) {
                        // non-hardened armor gets modifiers
                        // the -2 for hardened is handled in the critBonus
                        // variable
                        critMod += hit.getSpecCritMod();
                        critMod += hit.glancingMod();
                    }
                    vDesc.addAll(criticalEntity(te, hit.getLocation(), hit.isRear(),
                            critMod + critBonus, damage_orig));
                }
                specCrits = 0;
            }

            // resolve Aero crits
            if (te instanceof Aero) {
                checkAeroCrits(vDesc, (Aero) te, hit, damage_orig, critThresh, critSI,
                        ammoExplosion, nukeS2S);
            }

            if (isHeadHit
                    && !te.hasAbility(OptionsConstants.MD_DERMAL_ARMOR)) {
                Report.addNewline(vDesc);
                vDesc.addAll(damageCrew(te, 1));
            }

            // If the location has run out of internal structure, finally
            // actually
            // destroy it here. *EXCEPTION:* Aero units have 0 internal
            // structure
            // in every location by default and are handled elsewhere, so they
            // get a bye.
            if (!(te instanceof Aero) && (te.getInternal(hit) <= 0)) {
                te.destroyLocation(hit.getLocation());

                // Check for possible engine destruction here
                if ((te instanceof Mech)
                        && ((hit.getLocation() == Mech.LOC_RT) || (hit.getLocation() == Mech.LOC_LT))) {

                    int numEngineHits = te.getEngineHits();
                    boolean engineExploded = checkEngineExplosion(te, vDesc, numEngineHits);

                    int hitsToDestroy = 3;
                    if ((te instanceof Mech) && te.isSuperHeavy() && te.hasEngine()
                            && (te.getEngine().getEngineType() == Engine.COMPACT_ENGINE)) {
                        hitsToDestroy = 2;
                    }

                    if (!engineExploded && (numEngineHits >= hitsToDestroy)) {
                        // third engine hit
                        vDesc.addAll(entityActionManager.destroyEntity(te, "engine destruction", this));
                        if (game.getOptions()
                                .booleanOption(OptionsConstants.ADVGRNDMOV_AUTO_ABANDON_UNIT)) {
                            vDesc.addAll(abandonEntity(te));
                        }
                        te.setSelfDestructing(false);
                        te.setSelfDestructInitiated(false);
                    }

                    // Torso destruction in airborne LAM causes immediate crash.
                    if ((te instanceof LandAirMech) && !te.isDestroyed() && !te.isDoomed()) {
                        r = new Report(9710);
                        r.subject = te.getId();
                        r.addDesc(te);
                        if (te.isAirborneVTOLorWIGE()) {
                            vDesc.add(r);
                            entityActionManager.crashAirMech(te, new PilotingRollData(te.getId(), TargetRoll.AUTOMATIC_FAIL,
                                    "side torso destroyed"), vDesc, this);
                        } else if (te.isAirborne() && te.isAero()) {
                            vDesc.add(r);
                            vDesc.addAll(entityActionManager.processCrash(te, ((IAero) te).getCurrentVelocity(), te.getPosition(), this));
                        }
                    }
                }

            }

            // If damage remains, loop to next location; if not, be sure to stop
            // here because we may need to refer back to the last *damaged*
            // location again later. (This is safe because at damage <= 0 the
            // loop terminates anyway.)
            if (damage > 0) {
                hit = nextHit;
                // Need to update armor status for the new location
                hardenedArmor = ((te instanceof Mech) || (te instanceof Tank))
                        && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_HARDENED);
                ferroLamellorArmor = ((te instanceof Mech) || (te instanceof Tank) || (te instanceof Aero))
                        && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_FERRO_LAMELLOR);
                reflectiveArmor = (((te instanceof Mech) || (te instanceof Tank) || (te instanceof Aero))
                        && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_REFLECTIVE))
                        || (isBattleArmor
                        && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_BA_REFLECTIVE));
                reactiveArmor = (((te instanceof Mech) || (te instanceof Tank) || (te instanceof Aero))
                        && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_REACTIVE))
                        || (isBattleArmor && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_BA_REACTIVE));
                ballisticArmor = ((te instanceof Mech) || (te instanceof Tank) || (te instanceof Aero))
                        && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_BALLISTIC_REINFORCED);
                impactArmor = (te instanceof Mech)
                        && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_IMPACT_RESISTANT);
            }
            if (damageIS) {
                wasDamageIS = true;
                damageIS = false;
            }
        }
        // Mechs using EI implants take pilot damage each time a hit
        // inflicts IS damage
        if (tookInternalDamage
                && ((te instanceof Mech) || (te instanceof Protomech))
                && te.hasActiveEiCockpit()) {
            Report.addNewline(vDesc);
            Roll diceRoll = Compute.rollD6(2);
            r = new Report(5075);
            r.subject = te.getId();
            r.addDesc(te);
            r.add(7);
            r.add(diceRoll);
            r.choose(diceRoll.getIntValue() >= 7);
            r.indent(2);
            vDesc.add(r);
            if (diceRoll.getIntValue() < 7) {
                vDesc.addAll(damageCrew(te, 1));
            }
        }

        // if using VDNI (but not buffered), check for damage on an internal hit
        if (tookInternalDamage
                && te.hasAbility(OptionsConstants.MD_VDNI)
                && !te.hasAbility(OptionsConstants.MD_BVDNI)
                && !te.hasAbility(OptionsConstants.MD_PAIN_SHUNT)) {
            Report.addNewline(vDesc);
            Roll diceRoll = Compute.rollD6(2);
            r = new Report(3580);
            r.subject = te.getId();
            r.addDesc(te);
            r.add(7);
            r.add(diceRoll);
            r.choose(diceRoll.getIntValue() >= 8);
            r.indent(2);
            vDesc.add(r);

            if (diceRoll.getIntValue() >= 8) {
                vDesc.addAll(damageCrew(te, 1));
            }
        }

        // TacOps p.78 Ammo booms can hurt other units in same and adjacent hexes
        // But, this does not apply to CASE'd units and it only applies if the
        // ammo explosion
        // destroyed the unit
        if (ammoExplosion && game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_AMMUNITION)
                // For 'Mechs we care whether there was CASE specifically in the
                // location that went boom...
                && !(te.locationHasCase(hit.getLocation()) || te.hasCASEII(hit.getLocation()))
                // ...but vehicles and ASFs just have one CASE item for the
                // whole unit, so we need to look whether there's CASE anywhere
                // at all.
                && !(((te instanceof Tank) || (te instanceof Aero)) && te
                .hasCase()) && (te.isDestroyed() || te.isDoomed())
                && (damage_orig > 0) && ((damage_orig / 10) > 0)) {
            Report.addNewline(vDesc);
            r = new Report(5068, Report.PUBLIC);
            r.subject = te.getId();
            r.addDesc(te);
            r.indent(2);
            vDesc.add(r);
            Report.addNewline(vDesc);
            r = new Report(5400, Report.PUBLIC);
            r.subject = te.getId();
            r.indent(2);
            vDesc.add(r);
            int[] damages = {(int) Math.floor(damage_orig / 10.0),
                    (int) Math.floor(damage_orig / 20.0)};
            doExplosion(damages, false, te.getPosition(), true, vDesc, null, 5,
                    te.getId(), false);
            Report.addNewline(vDesc);
            r = new Report(5410, Report.PUBLIC);
            r.subject = te.getId();
            r.indent(2);
            vDesc.add(r);
        }

        // This flag indicates the hit was directly to IS
        if (wasDamageIS) {
            Report.addNewline(vDesc);
        }
        return vDesc;
    }

    /**
     * Apply damage to an Entity carrying external Battle Armor or ProtoMech
     * when a location with a trooper present is hit.
     *
     * @param te             The carrying Entity
     * @param hit            The hit to resolve
     * @param damage         The amount of damage to be allocated
     * @param vDesc          The {@link Report} <code>Vector</code>
     * @param passenger      The BA squad
     * @return               The amount of damage remaining
     */
    protected int damageExternalPassenger(Entity te, HitData hit, int damage, Vector<Report> vDesc,
                                        Entity passenger) {
        Report r;
        int passengerDamage = damage;
        int avoidRoll = Compute.d6();
        HitData passHit = passenger.getTrooperAtLocation(hit, te);
        if (passenger.hasETypeFlag(Entity.ETYPE_PROTOMECH)) {
            passengerDamage -= damage / 2;
            passHit = passenger.rollHitLocation(ToHitData.HIT_SPECIAL_PROTO, ToHitData.SIDE_FRONT);
        } else if (avoidRoll < 5) {
            passengerDamage = 0;
        }
        passHit.setGeneralDamageType(hit.getGeneralDamageType());

        if (passengerDamage > 0) {
            // Yup. Roll up some hit data for that passenger.
            r = new Report(6075);
            r.subject = passenger.getId();
            r.indent(3);
            r.addDesc(passenger);
            vDesc.addElement(r);

            // How much damage will the passenger absorb?
            int absorb = 0;
            HitData nextPassHit = passHit;
            do {
                int armorType = passenger.getArmorType(nextPassHit.getLocation());
                boolean armorDamageReduction = false;
                if (((armorType == EquipmentType.T_ARMOR_BA_REACTIVE)
                        && ((hit.getGeneralDamageType() == HitData.DAMAGE_MISSILE)))
                        || (hit.getGeneralDamageType() == HitData.DAMAGE_ARMOR_PIERCING_MISSILE)) {
                    armorDamageReduction = true;
                }
                // Check for reflective armor
                if ((armorType == EquipmentType.T_ARMOR_BA_REFLECTIVE)
                        && (hit.getGeneralDamageType() == HitData.DAMAGE_ENERGY)) {
                    armorDamageReduction = true;
                }
                if (0 < passenger.getArmor(nextPassHit)) {
                    absorb += passenger.getArmor(nextPassHit);
                    if (armorDamageReduction) {
                        absorb *= 2;
                    }
                }
                if (0 < passenger.getInternal(nextPassHit)) {
                    absorb += passenger.getInternal(nextPassHit);
                    // Armor damage reduction, like for reflective or
                    // reactive armor will divide the whole damage
                    // total by 2 and round down. If we have an odd
                    // damage total, need to add 1 to make this
                    // evenly divisible by 2
                    if (((absorb % 2) != 0) && armorDamageReduction) {
                        absorb++;
                    }
                }
                nextPassHit = passenger.getTransferLocation(nextPassHit);
            } while ((damage > absorb) && (nextPassHit.getLocation() >= 0));

            // Damage the passenger.
            absorb = Math.min(passengerDamage, absorb);
            Vector<Report> newReports = damageEntity(passenger, passHit, absorb);
            for (Report newReport : newReports) {
                newReport.indent(2);
            }
            vDesc.addAll(newReports);

            // Did some damage pass on?
            if (damage > absorb) {
                // Yup. Remove the absorbed damage.
                damage -= absorb;
                r = new Report(6080);
                r.subject = te.getId();
                r.indent(2);
                r.add(damage);
                r.addDesc(te);
                vDesc.addElement(r);
            } else {
                // Nope. Return our description.
                return 0;
            }

        } else {
            // Report that a passenger that could've been missed
            // narrowly avoids damage
            r = new Report(6084);
            r.subject = passenger.getId();
            r.indent(3);
            r.addDesc(passenger);
            vDesc.addElement(r);
        } // End nLoc-has-exterior-passenger
        if (passenger.hasETypeFlag(Entity.ETYPE_PROTOMECH)
                && (passengerDamage > 0) && !passenger.isDoomed() && !passenger.isDestroyed()) {
            r = new Report(3850);
            r.subject = passenger.getId();
            r.indent(3);
            r.addDesc(passenger);
            vDesc.addElement(r);
            int facing = te.getFacing();
            // We're going to assume that it's mounted facing the mech
            Coords position = te.getPosition();
            if (!hit.isRear()) {
                facing = (facing + 3) % 6;
            }
            unloadUnit(te, passenger, position, facing, te.getElevation(), false, false);
            Entity violation = Compute.stackingViolation(game,
                    passenger.getId(), position, passenger.climbMode());
            if (violation != null) {
                Coords targetDest = Compute.getValidDisplacement(game, passenger.getId(), position,
                        Compute.d6() - 1);
                reportManager.addReport(utilityManager.doEntityDisplacement(violation, position, targetDest, null, this), this);
                // Update the violating entity's position on the client.
                entityUpdate(violation.getId());
            }
        }
        return damage;
    }

    /**
     * Check to see if the entity's engine explodes. Rules for ICE explosions
     * are different to fusion engines.
     *
     * @param en    - the <code>Entity</code> in question. This value must not be
     *              <code>null</code>.
     * @param vDesc - the <code>Vector</code> that this function should add its
     *              <code>Report<code>s to.  It may be empty, but not
     *              <code>null</code>.
     * @param hits  - the number of criticals on the engine
     * @return <code>true</code> if the unit's engine exploded,
     * <code>false</code> if not.
     */
    protected boolean checkEngineExplosion(Entity en, Vector<Report> vDesc, int hits) {
        if (!(en instanceof Mech) && !(en instanceof Aero) && !(en instanceof Tank)) {
            return false;
        }
        // If this method gets called for an entity that's already destroyed or
        // that hasn't taken any actual engine hits this phase yet, do nothing.
        if (en.isDestroyed() || (en.engineHitsThisPhase <= 0)
                || en.getSelfDestructedThisTurn() || !en.hasEngine()) {
            return false;
        }
        int explosionBTH = 10;
        int hitsPerRound = 4;
        Engine engine = en.getEngine();

        if (en instanceof Tank) {
            explosionBTH = 12;
            hitsPerRound = 1;
        } else if (!(en instanceof Mech)) {
            explosionBTH = 12;
            hitsPerRound = 1;
        }

        // Non mechs and mechs that already rolled are safe
        if (en.rolledForEngineExplosion || !(en instanceof Mech)) {
            return false;
        }
        // ICE can always explode and roll every time hit
        if (engine.isFusion()
                && (!game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_ENGINE_EXPLOSIONS)
                || (en.engineHitsThisPhase < hitsPerRound))) {
            return false;
        }
        if (!engine.isFusion()) {
            switch (hits) {
                case 0:
                    return false;
                case 1:
                    explosionBTH = 10;
                    break;
                case 2:
                    explosionBTH = 7;
                    break;
                case 3:
                default:
                    explosionBTH = 4;
                    break;
            }
        }
        Roll diceRoll = Compute.rollD6(2);
        boolean didExplode = diceRoll.getIntValue() >= explosionBTH;

        Report r;
        r = new Report(6150);
        r.subject = en.getId();
        r.indent(2);
        r.addDesc(en);
        r.add(en.engineHitsThisPhase);
        vDesc.addElement(r);
        r = new Report(6155);
        r.subject = en.getId();
        r.indent(2);
        r.add(explosionBTH);
        r.add(diceRoll);
        vDesc.addElement(r);

        if (!didExplode) {
            // whew!
            if (engine.isFusion()) {
                en.rolledForEngineExplosion = true;
            }
            // fusion engines only roll 1/phase but ICE roll every time damaged
            r = new Report(6160);
            r.subject = en.getId();
            r.indent(2);
            vDesc.addElement(r);
        } else {
            en.rolledForEngineExplosion = true;
            r = new Report(6165, Report.PUBLIC);
            r.subject = en.getId();
            r.indent(2);
            vDesc.addElement(r);
            vDesc.addAll(entityActionManager.destroyEntity(en, "engine explosion", false, false, this));
            // kill the crew
            en.getCrew().setDoomed(true);

            // This is a hack so MM.NET marks the mech as not salvageable
            en.destroyLocation(Mech.LOC_CT);

            // ICE explosions don't hurt anyone else, but fusion do
            if (engine.isFusion()) {
                int engineRating = en.getEngine().getRating();
                Report.addNewline(vDesc);
                r = new Report(5400, Report.PUBLIC);
                r.subject = en.getId();
                r.indent(2);
                vDesc.add(r);

                Mech mech = (Mech) en;
                if (mech.isAutoEject() && (!game.getOptions().booleanOption(
                        OptionsConstants.RPG_CONDITIONAL_EJECTION)
                        || (game.getOptions().booleanOption(
                        OptionsConstants.RPG_CONDITIONAL_EJECTION)
                        && mech.isCondEjectEngine()))) {
                    vDesc.addAll(ejectEntity(en, true));
                }

                doFusionEngineExplosion(engineRating, en.getPosition(), vDesc, null);
                Report.addNewline(vDesc);
                r = new Report(5410, Report.PUBLIC);
                r.subject = en.getId();
                r.indent(2);
                vDesc.add(r);

            }
        }

        return didExplode;
    }

    /**
     * Extract explosion functionality for generalized explosions in areas.
     */
    public void doFusionEngineExplosion(int engineRating, Coords position, Vector<Report> vDesc,
                                        Vector<Integer> vUnits) {
        int[] myDamages = { engineRating, (engineRating / 10), (engineRating / 20),
                (engineRating / 40) };
        doExplosion(myDamages, true, position, false, vDesc, vUnits, 5, -1, true);
    }

    /**
     * General function to cause explosions in areas.
     */
    public void doExplosion(int damage, int degradation, boolean autoDestroyInSameHex,
                            Coords position, boolean allowShelter, Vector<Report> vDesc,
                            Vector<Integer> vUnits, int excludedUnitId) {
        if (degradation < 1) {
            return;
        }

        int[] myDamages = new int[damage / degradation];

        if (myDamages.length < 1) {
            return;
        }

        myDamages[0] = damage;
        for (int x = 1; x < myDamages.length; x++) {
            myDamages[x] = myDamages[x - 1] - degradation;
        }
        doExplosion(myDamages, autoDestroyInSameHex, position, allowShelter, vDesc, vUnits,
                5, excludedUnitId, false);
    }

    /**
     * General function to cause explosions in areas.
     */
    public void doExplosion(int[] damages, boolean autoDestroyInSameHex, Coords position,
                            boolean allowShelter, Vector<Report> vDesc, Vector<Integer> vUnits,
                            int clusterAmt, int excludedUnitId, boolean engineExplosion) {
        if (vDesc == null) {
            vDesc = new Vector<>();
        }

        if (vUnits == null) {
            vUnits = new Vector<>();
        }

        Report r;
        HashSet<Entity> entitiesHit = new HashSet<>();

        // We need to damage buildings.
        Enumeration<Building> buildings = game.getBoard().getBuildings();
        while (buildings.hasMoreElements()) {
            final Building bldg = buildings.nextElement();

            // Lets find the closest hex from the building.
            Enumeration<Coords> hexes = bldg.getCoords();

            while (hexes.hasMoreElements()) {
                final Coords coords = hexes.nextElement();
                int dist = position.distance(coords);
                if (dist < damages.length) {
                    Vector<Report> buildingReport = damageBuilding(bldg, damages[dist], coords);
                    for (Report report : buildingReport) {
                        report.type = Report.PUBLIC;
                    }
                    vDesc.addAll(buildingReport);
                }
            }
        }

        // We need to damage terrain
        int maxDist = damages.length;
        Hex hex = game.getBoard().getHex(position);
        // Center hex starts on fire for engine explosions
        if (engineExplosion && (hex != null) && !hex.containsTerrain(Terrains.FIRE)) {
            r = new Report(5136);
            r.indent(2);
            r.type = Report.PUBLIC;
            r.add(position.getBoardNum());
            vDesc.add(r);
            Vector<Report> reports = new Vector<>();
            ignite(position, Terrains.FIRE_LVL_NORMAL, reports);
            for (Report report : reports) {
                report.indent();
            }
            vDesc.addAll(reports);
        }
        if ((hex != null) && hex.hasTerrainFactor()) {
            r = new Report(3384);
            r.indent(2);
            r.type = Report.PUBLIC;
            r.add(position.getBoardNum());
            r.add(damages[0]);
            vDesc.add(r);
        }
        Vector<Report> reports = environmentalEffectManager.tryClearHex(position, damages[0], Entity.NONE, this);
        for (Report report : reports) {
            report.indent(3);
        }
        vDesc.addAll(reports);

        // Handle surrounding coords
        for (int dist = 1; dist < maxDist; dist++) {
            List<Coords> coords = position.allAtDistance(dist);
            for (Coords c : coords) {
                hex = game.getBoard().getHex(c);
                if ((hex != null) && hex.hasTerrainFactor()) {
                    r = new Report(3384);
                    r.indent(2);
                    r.type = Report.PUBLIC;
                    r.add(c.getBoardNum());
                    r.add(damages[dist]);
                    vDesc.add(r);
                }
                reports = environmentalEffectManager.tryClearHex(c, damages[dist], Entity.NONE, this);
                for (Report report : reports) {
                    report.indent(3);
                }
                vDesc.addAll(reports);
            }
        }

        // Now we damage people near the explosion.
        List<Entity> loaded = new ArrayList<>();
        for (Iterator<Entity> ents = game.getEntities(); ents.hasNext();) {
            Entity entity = ents.next();

            if (entitiesHit.contains(entity)) {
                continue;
            }

            if (entity.getId() == excludedUnitId) {
                continue;
            }

            if (entity.isDestroyed() || !entity.isDeployed()) {
                // FIXME
                // IS this the behavior we want?
                // This means, incidentally, that salvage is never affected by
                // explosions
                // as long as it was destroyed before the explosion.
                continue;
            }

            // We are going to assume that explosions are on the ground here so
            // flying entities should be unaffected
            if (entity.isAirborne()) {
                continue;
            }

            if ((entity instanceof MechWarrior) && !((MechWarrior) entity).hasLanded()) {
                // MechWarrior is still up in the air ejecting hence safe
                // from this explosion.
                continue;
            }

            Coords entityPos = entity.getPosition();
            if (entityPos == null) {
                // maybe its loaded?
                Entity transport = game.getEntity(entity.getTransportId());
                if ((transport != null) && !transport.isAirborne()) {
                    loaded.add(entity);
                }
                continue;
            }
            int range = position.distance(entityPos);

            if (range >= damages.length) {
                // Yeah, this is fine. It's outside the blast radius.
                continue;
            }

            // We might need to nuke everyone in the explosion hex. If so...
            if ((range == 0) && autoDestroyInSameHex) {
                // Add the reports
                vDesc.addAll(entityActionManager.destroyEntity(entity, "explosion proximity", false, false, this));
                // Add it to the "blasted units" list
                vUnits.add(entity.getId());
                // Kill the crew
                entity.getCrew().setDoomed(true);

                entitiesHit.add(entity);
                continue;
            }

            int damage = damages[range];

            if (allowShelter && canShelter(entityPos, position, entity.relHeight())) {
                if (isSheltered()) {
                    r = new Report(6545);
                    r.addDesc(entity);
                    r.subject = entity.getId();
                    vDesc.addElement(r);
                    continue;
                }
                // If shelter is allowed but didn't work, report that.
                r = new Report(6546);
                r.subject = entity.getId();
                r.addDesc(entity);
                vDesc.addElement(r);
            }

            // Since it's taking damage, add it to the list of units hit.
            vUnits.add(entity.getId());

            AreaEffectHelper.applyExplosionClusterDamageToEntity(entity, damage, clusterAmt, position, vDesc, this);

            Report.addNewline(vDesc);
        }

        // now deal with loaded units...
        for (Entity e : loaded) {
            // This can be null, if the transport died from damage
            final Entity transporter = game.getEntity(e.getTransportId());
            if ((transporter == null) || transporter.getExternalUnits().contains(e)) {
                // Its external or transport was destroyed - hit it.
                final Coords entityPos = (transporter == null ? e.getPosition()
                        : transporter.getPosition());
                final int range = position.distance(entityPos);

                if (range >= damages.length) {
                    // Yeah, this is fine. It's outside the blast radius.
                    continue;
                }

                int damage = damages[range];
                if (allowShelter) {
                    final int absHeight = (transporter == null ? e.relHeight()
                            : transporter.relHeight());
                    if (canShelter(entityPos, position, absHeight)) {
                        if (isSheltered()) {
                            r = new Report(6545);
                            r.addDesc(e);
                            r.subject = e.getId();
                            vDesc.addElement(r);
                            continue;
                        }
                        // If shelter is allowed but didn't work, report that.
                        r = new Report(6546);
                        r.subject = e.getId();
                        r.addDesc(e);
                        vDesc.addElement(r);
                    }
                }
                // No shelter
                // Since it's taking damage, add it to the list of units hit.
                vUnits.add(e.getId());

                r = new Report(6175);
                r.subject = e.getId();
                r.indent(2);
                r.addDesc(e);
                r.add(damage);
                vDesc.addElement(r);

                while (damage > 0) {
                    int cluster = Math.min(5, damage);
                    int table = ToHitData.HIT_NORMAL;
                    if (e instanceof Protomech) {
                        table = ToHitData.HIT_SPECIAL_PROTO;
                    }
                    HitData hit = e.rollHitLocation(table, ToHitData.SIDE_FRONT);
                    vDesc.addAll(damageEntity(e, hit, cluster, false,
                            DamageType.IGNORE_PASSENGER, false, true));
                    damage -= cluster;
                }
                Report.addNewline(vDesc);
            }
        }
    }

    /**
     * Check if an Entity of the passed height can find shelter from a nuke blast
     *
     * @param entityPosition  the <code>Coords</code> the Entity is at
     * @param position        the <code>Coords</code> of the explosion
     * @param entityAbsHeight the <code>int</code> height of the entity
     * @return a <code>boolean</code> value indicating if the entity of the
     * given height can find shelter
     */
    public boolean canShelter(Coords entityPosition, Coords position, int entityAbsHeight) {
        // What is the next hex in the direction of the blast?
        Coords shelteringCoords = Coords.nextHex(entityPosition, position);
        Hex shelteringHex = game.getBoard().getHex(shelteringCoords);

        // This is an error condition. It really shouldn't ever happen.
        if (shelteringHex == null) {
            return false;
        }

        // Now figure out the height to which that hex will provide shelter.
        // It's worth noting, this assumes that any building in the hex has
        // already survived the bomb blast. In the case where a building
        // won't survive the blast but hasn't actually taken the damage
        // yet, this will be wrong.
        int shelterLevel = shelteringHex.floor();
        if (shelteringHex.containsTerrain(Terrains.BUILDING)) {
            shelterLevel = shelteringHex.ceiling();
        }

        // Get the absolute height of the unit relative to level 0.
        entityAbsHeight += game.getBoard().getHex(entityPosition).getLevel();

        // Now find the height that needs to be sheltered, and compare.
        return entityAbsHeight < shelterLevel;
    }

    /**
     * @return true if the unit succeeds a shelter roll
     */
    protected boolean isSheltered() {
        return Compute.d6(2) >= 9;
    }

    /**
     * add a nuke to be exploded in the next weapons attack phase
     *
     * @param nuke this is an int[] with i=0 and i=1 being X and Y coordinates respectively,
     *             If the input array is length 3, then i=2 is NukeType (from HS:3070)
     *             If the input array is length 6, then i=2 is the base damage dealt,
     *             i=3 is the degradation, i=4 is the secondary radius, and i=5 is the crater depth
     */
    public void addScheduledNuke(int[] nuke) {
        scheduledNukes.add(nuke);
    }

    /**
     * explode any scheduled nukes
     */
    void resolveScheduledNukes() {
        for (int[] nuke : scheduledNukes) {
            if (nuke.length == 3) {
                doNuclearExplosion(new Coords(nuke[0] - 1, nuke[1] - 1), nuke[2],
                        vPhaseReport);
            }
            if (nuke.length == 6) {
                doNuclearExplosion(new Coords(nuke[0] - 1, nuke[1] - 1), nuke[2], nuke[3],
                        nuke[4], nuke[5], vPhaseReport);
            }
        }
        scheduledNukes.clear();
    }

    /**
     * do a nuclear explosion
     *
     * @param position the position that will be hit by the nuke
     * @param nukeType the type of nuke
     * @param vDesc    a vector that contains the output report
     */
    public void doNuclearExplosion(Coords position, int nukeType, Vector<Report> vDesc) {
        AreaEffectHelper.NukeStats nukeStats = AreaEffectHelper.getNukeStats(nukeType);

        if (nukeStats == null) {
            LogManager.getLogger().error("Illegal nuke not listed in HS:3070");
        }

        doNuclearExplosion(position, nukeStats.baseDamage, nukeStats.degradation, nukeStats.secondaryRadius,
                nukeStats.craterDepth, vDesc);
    }

    /**
     * explode a nuke
     *
     * @param position          the position that will be hit by the nuke
     * @param baseDamage        the base damage from the blast
     * @param degradation       how fast the blast's power degrades
     * @param secondaryRadius   the secondary blast radius
     * @param craterDepth       the depth of the crater created by the blast
     * @param vDesc             a vector that contains the output report
     */
    public void doNuclearExplosion(Coords position, int baseDamage, int degradation,
                                   int secondaryRadius, int craterDepth, Vector<Report> vDesc) {
        // Just in case.
        if (vDesc == null) {
            vDesc = new Vector<>();
        }

        // First, crater the terrain.
        // All terrain, units, buildings... EVERYTHING in here is just gone.
        // Gotta love nukes.
        Report r = new Report(1215, Report.PUBLIC);

        r.indent();
        r.add(position.getBoardNum(), true);
        vDesc.add(r);

        int curDepth = craterDepth;
        int range = 0;
        while (range < (2 * craterDepth)) {
            // Get the set of hexes at this range.
            List<Coords> hexSet = position.allAtDistance(range);

            // Iterate through the hexes.
            for (Coords myHexCoords: hexSet) {
                // ignore out of bounds coordinates
                if (!game.getBoard().contains(myHexCoords)) {
                    continue;
                }

                Hex myHex = game.getBoard().getHex(myHexCoords);
                // In each hex, first, sink the terrain if necessary.
                myHex.setLevel((myHex.getLevel() - curDepth));

                // Then, remove ANY terrains here.
                // I mean ALL of them; they're all just gone.
                // No ruins, no water, no rough, no nothing.
                if (myHex.containsTerrain(Terrains.WATER)) {
                    myHex.setLevel(myHex.floor());
                }
                myHex.removeAllTerrains();
                myHex.clearExits();

                communicationManager.sendChangedHex(myHexCoords, this);
            }

            // Lastly, if the next distance is a multiple of 2...
            // The crater depth goes down one.
            if ((range > 0) && ((range % 2) == 0)) {
                curDepth--;
            }

            // Now that the hexes are dealt with, increment the distance.
            range++;
        }

        // This is technically part of cratering, but...
        // Now we destroy all the units inside the cratering range.
        for (Entity entity : game.getEntitiesVector()) {
            // loaded units and off board units don't have a position,
            // so we don't count 'em here
            if ((entity.getTransportId() != Entity.NONE) || (entity.getPosition() == null)) {
                continue;
            }

            // If it's too far away for this...
            if (position.distance(entity.getPosition()) >= range) {
                continue;
            }

            // If it's already destroyed...
            if (entity.isDestroyed()) {
                continue;
            }

            vDesc.addAll(entityActionManager.destroyEntity(entity, "nuclear explosion proximity",
                    false, false, this));
            // Kill the crew
            entity.getCrew().setDoomed(true);
        }

        // Then, do actual blast damage.
        // Use the standard blast function for this.
        Vector<Report> tmpV = new Vector<>();
        Vector<Integer> blastedUnitsVec = new Vector<>();
        doExplosion(baseDamage, degradation, true, position, true, tmpV,
                blastedUnitsVec, -1);
        Report.indentAll(tmpV, 2);
        vDesc.addAll(tmpV);

        // Everything that was blasted by the explosion has to make a piloting
        // check at +6.
        for (int i : blastedUnitsVec) {
            Entity o = game.getEntity(i);
            if (o.canFall()) {
                // Needs a piloting check at +6 to avoid falling over.
                game.addPSR(new PilotingRollData(o.getId(), 6,
                        "hit by nuclear blast"));
            } else if (o instanceof VTOL) {
                // Needs a piloting check at +6 to avoid crashing.
                // Wheeeeee!
                VTOL vt = (VTOL) o;

                // Check only applies if it's in the air.
                // FIXME: is this actually correct? What about
                // buildings/bridges?
                if (vt.getElevation() > 0) {
                    game.addPSR(new PilotingRollData(vt.getId(), 6,
                            "hit by nuclear blast"));
                }
            } else if (o instanceof Tank) {
                // As per official answer on the rules questions board...
                // Needs a piloting check at +6 to avoid a 1-level fall...
                // But ONLY if a hover-tank.
                // TODO : Fix me
            }
        }

        // This ISN'T part of the blast, but if there's ANYTHING in the ground
        // zero hex, destroy it.
        Building tmpB = game.getBoard().getBuildingAt(position);
        if (tmpB != null) {
            r = new Report(2415);
            r.add(tmpB.getName());
            addReport(r);
            tmpB.setCurrentCF(0, position);
        }
        Hex gzHex = game.getBoard().getHex(position);
        if (gzHex.containsTerrain(Terrains.WATER)) {
            gzHex.setLevel(gzHex.floor());
        }
        gzHex.removeAllTerrains();

        // Next, for whatever's left, do terrain effects
        // such as clearing, roughing, and boiling off water.
        boolean damageFlag = true;
        int damageAtRange = baseDamage - (degradation * range);
        if (damageAtRange > 0) {
            for (int x = range; damageFlag; x++) {
                // Damage terrain as necessary.
                // Get all the hexes, and then iterate through them.
                List<Coords> hexSet = position.allAtDistance(x);

                // Iterate through the hexes.
                for (Coords myHexCoords : hexSet) {
                    // ignore out of bounds coordinates
                    if (!game.getBoard().contains(myHexCoords)) {
                        continue;
                    }

                    Hex myHex = game.getBoard().getHex(myHexCoords);

                    // For each 3000 damage, water level is reduced by 1.
                    if ((damageAtRange >= 3000) && (myHex.containsTerrain(Terrains.WATER))) {
                        int numCleared = damageAtRange / 3000;
                        int oldLevel = myHex.terrainLevel(Terrains.WATER);
                        myHex.removeTerrain(Terrains.WATER);
                        if (oldLevel > numCleared) {
                            myHex.setLevel(myHex.getLevel() - numCleared);
                            myHex.addTerrain(new Terrain(Terrains.WATER, oldLevel - numCleared));
                        } else {
                            myHex.setLevel(myHex.getLevel() - oldLevel);
                        }
                    }

                    // ANY non-water hex that takes 200 becomes rough.
                    if ((damageAtRange >= 200) && (!myHex.containsTerrain(Terrains.WATER))) {
                        myHex.removeAllTerrains();
                        myHex.clearExits();
                        myHex.addTerrain(new Terrain(Terrains.ROUGH, 1));
                    } else if ((damageAtRange >= 20)
                            && ((myHex.containsTerrain(Terrains.WOODS))
                            || (myHex.containsTerrain(Terrains.JUNGLE)))) {
                        // Each 20 clears woods by 1 level.
                        int numCleared = damageAtRange / 20;
                        int terrainType = (myHex.containsTerrain(Terrains.WOODS)
                                ? Terrains.WOODS : Terrains.JUNGLE);
                        int oldLevel = myHex.terrainLevel(terrainType);
                        int oldEl = myHex.terrainLevel(Terrains.FOLIAGE_ELEV);
                        myHex.removeTerrain(terrainType);
                        if (oldLevel > numCleared) {
                            myHex.addTerrain(new Terrain(terrainType, oldLevel - numCleared));
                            if (oldEl != 1) {
                                myHex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV,
                                        oldLevel - numCleared == 3 ? 3 : 2));
                            }
                        } else {
                            myHex.removeTerrain(Terrains.FOLIAGE_ELEV);
                        }
                    }

                    communicationManager.sendChangedHex(myHexCoords, this);
                }

                // Initialize for the next iteration.
                damageAtRange = baseDamage - ((degradation * x) + 1);

                // If the damage is less than 20, it has no terrain effect.
                if (damageAtRange < 20) {
                    damageFlag = false;
                }
            }
        }

        // Lastly, do secondary effects.
        for (Entity entity : game.getEntitiesVector()) {
            // loaded units and off board units don't have a position,
            // so we don't count 'em here
            if ((entity.getTransportId() != Entity.NONE) || (entity.getPosition() == null)) {
                continue;
            }

            // If it's already destroyed...
            if ((entity.isDoomed()) || (entity.isDestroyed())) {
                continue;
            }

            // If it's too far away for this...
            if (position.distance(entity.getPosition()) > secondaryRadius) {
                continue;
            }

            // Actually do secondary effects against it.
            // Since the effects are unit-dependant, we'll just define it in the
            // entity.
            applySecondaryNuclearEffects(entity, position, vDesc);
        }

        // All right. We're done.
        r = new Report(1216, Report.PUBLIC);
        r.indent();
        r.newlines = 2;
        vDesc.add(r);
    }

    /**
     * Handles secondary effects from nuclear blasts against all units in range.
     *
     * @param entity   The entity to affect.
     * @param position The coordinates of the nuclear blast, for to-hit directions.
     * @param vDesc    a description vector to use for reports.
     */
    public void applySecondaryNuclearEffects(Entity entity, Coords position, Vector<Report> vDesc) {
        // If it's already destroyed, give up. We really don't care.
        if (entity.isDestroyed()) {
            return;
        }

        // Check to see if the infantry is in a protective structure.
        boolean inHardenedBuilding = (Compute.isInBuilding(game, entity)
                && (game.getBoard().getHex(entity.getPosition()).terrainLevel(Terrains.BUILDING) == 4));

        // Roll 2d6.
        Roll diceRoll = Compute.rollD6(2);
        int rollValue = diceRoll.getIntValue();

        Report r = new Report(6555);
        r.subject = entity.getId();
        r.add(entity.getDisplayName());
        r.add(diceRoll);

        // If they are in protective structure, add 2 to the roll.
        if (inHardenedBuilding) {
            rollValue += 2;
            r.add(" + 2 (unit is in hardened building)");
        } else {
            r.add("");
        }

        // Also, if the entity is "hardened" against EMI, it gets a +2.
        // For these purposes, I'm going to hand this off to the Entity itself
        // to tell us.
        // Right now, it IS based purely on class, but I won't rule out the idea
        // of
        // "nuclear hardening" as equipment for a support vehicle, for example.
        if (entity.isNuclearHardened()) {
            rollValue += 2;
            r.add(" + 2 (unit is hardened against EMI)");
        } else {
            r.add("");
        }

        r.indent(2);
        vDesc.add(r);

        // Now, compare it to the table, and apply the effects.
        if (rollValue <= 4) {
            // The unit is destroyed.
            // Sucks, doesn't it?
            // This applies to all units.
            // Yup, just sucks.
            vDesc.addAll(entityActionManager.destroyEntity(entity,
                    "nuclear explosion secondary effects", false, false, this));
            // Kill the crew
            entity.getCrew().setDoomed(true);
        } else if (rollValue <= 6) {
            if (entity instanceof BattleArmor) {
                // It takes 50% casualties, rounded up.
                BattleArmor myBA = (BattleArmor) entity;
                int numDeaths = (int) (Math.ceil((myBA.getNumberActiverTroopers())) / 2.0);
                for (int x = 0; x < numDeaths; x++) {
                    vDesc.addAll(applyCriticalHit(entity, 0, null, false,
                            0, false));
                }
            } else if (entity instanceof Infantry) {
                // Standard infantry are auto-killed in this band, unless
                // they're in a building.
                if (game.getBoard().getHex(entity.getPosition()).containsTerrain(Terrains.BUILDING)) {
                    // 50% casualties, rounded up.
                    int damage = (int) (Math.ceil((entity.getInternal(Infantry.LOC_INFANTRY)) / 2.0));
                    vDesc.addAll(damageEntity(entity, new HitData(
                            Infantry.LOC_INFANTRY), damage, true));
                } else {
                    vDesc.addAll(entityActionManager.destroyEntity(entity,
                            "nuclear explosion secondary effects", false, false, this));
                    entity.getCrew().setDoomed(true);
                }
            } else if (entity instanceof Tank) {
                // All vehicles suffer two critical hits...
                HitData hd = entity.rollHitLocation(ToHitData.HIT_NORMAL, entity.sideTable(position));
                vDesc.addAll(oneCriticalEntity(entity, hd.getLocation(), hd.isRear(), 0));
                hd = entity.rollHitLocation(ToHitData.HIT_NORMAL, entity.sideTable(position));
                vDesc.addAll(oneCriticalEntity(entity, hd.getLocation(), hd.isRear(), 0));

                // ...and a Crew Killed hit.
                vDesc.addAll(applyCriticalHit(entity, 0, new CriticalSlot(0,
                        Tank.CRIT_CREW_KILLED), false, 0, false));
            } else if ((entity instanceof Mech) || (entity instanceof Protomech)) {
                // 'Mechs suffer two critical hits...
                HitData hd = entity.rollHitLocation(ToHitData.HIT_NORMAL, entity.sideTable(position));
                vDesc.addAll(oneCriticalEntity(entity, hd.getLocation(), hd.isRear(), 0));
                hd = entity.rollHitLocation(ToHitData.HIT_NORMAL, entity.sideTable(position));
                vDesc.addAll(oneCriticalEntity(entity, hd.getLocation(), hd.isRear(), 0));

                // and four pilot hits.
                vDesc.addAll(damageCrew(entity, 4));
            }
            // Buildings and gun emplacements and such are only affected by the EMI.
            // No auto-crits or anything.
        } else if (rollValue <= 10) {
            if (entity instanceof BattleArmor) {
                // It takes 25% casualties, rounded up.
                BattleArmor myBA = (BattleArmor) entity;
                int numDeaths = (int) (Math.ceil(((myBA.getNumberActiverTroopers())) / 4.0));
                for (int x = 0; x < numDeaths; x++) {
                    vDesc.addAll(applyCriticalHit(entity, 0, null, false, 0, false));
                }
            } else if (entity instanceof Infantry) {
                if (game.getBoard().getHex(entity.getPosition()).containsTerrain(Terrains.BUILDING)) {
                    // 25% casualties, rounded up.
                    int damage = (int) (Math.ceil((entity.getInternal(Infantry.LOC_INFANTRY)) / 4.0));
                    vDesc.addAll(damageEntity(entity, new HitData(Infantry.LOC_INFANTRY), damage, true));
                } else {
                    // 50% casualties, rounded up.
                    int damage = (int) (Math.ceil((entity.getInternal(Infantry.LOC_INFANTRY)) / 2.0));
                    vDesc.addAll(damageEntity(entity, new HitData(Infantry.LOC_INFANTRY), damage, true));
                }
            } else if (entity instanceof Tank) {
                // It takes one crit...
                HitData hd = entity.rollHitLocation(ToHitData.HIT_NORMAL, entity.sideTable(position));
                vDesc.addAll(oneCriticalEntity(entity, hd.getLocation(), hd.isRear(), 0));

                // Plus a Crew Stunned critical.
                vDesc.addAll(applyCriticalHit(entity, 0, new CriticalSlot(0,
                        Tank.CRIT_CREW_STUNNED), false, 0, false));
            } else if ((entity instanceof Mech) || (entity instanceof Protomech)) {
                // 'Mechs suffer a critical hit...
                HitData hd = entity.rollHitLocation(ToHitData.HIT_NORMAL, entity.sideTable(position));
                vDesc.addAll(oneCriticalEntity(entity, hd.getLocation(), hd.isRear(), 0));

                // and two pilot hits.
                vDesc.addAll(damageCrew(entity, 2));
            }
            // Buildings and gun emplacements and such are only affected by
            // the EMI.
            // No auto-crits or anything.
        }
        // If it's 11+, there are no secondary effects beyond EMI.
        // Lucky bastards.

        // And lastly, the unit is now affected by electromagnetic interference.
        entity.setEMI(true);
    }

    /**
     * Apply a single critical hit. The following protected member of Server are
     * accessed from this function, preventing it from being factored out of the
     * Server class: destroyEntity() destroyLocation() checkEngineExplosion()
     * damageCrew() explodeEquipment() game
     *
     * @param en               the <code>Entity</code> that is being damaged. This value may
     *                         not be <code>null</code>.
     * @param loc              the <code>int</code> location of critical hit. This value may
     *                         be <code>Entity.NONE</code> for hits to <code>Tank</code>s and
     *                         for hits to a <code>Protomech</code> torso weapon.
     * @param cs               the <code>CriticalSlot</code> being damaged. This value may
     *                         not be <code>null</code>. For critical hits on a
     *                         <code>Tank</code>, the index of the slot should be the index
     *                         of the critical hit table.
     * @param secondaryEffects the <code>boolean</code> flag that indicates whether to allow
     *                         critical hits to cause secondary effects (such as triggering
     *                         an ammo explosion, sending hovercraft to watery graves, or
     *                         damaging ProtoMech torso weapons). This value is normally
     *                         <code>true</code>, but it will be <code>false</code> when the
     *                         hit is being applied from a saved game or scenario.
     * @param damageCaused     the amount of damage causing this critical.
     * @param isCapital        whether it was capital scale damage that caused critical
     */
    public Vector<Report> applyCriticalHit(Entity en, int loc, CriticalSlot cs,
                                           boolean secondaryEffects, int damageCaused,
                                           boolean isCapital) {
        Vector<Report> vDesc = new Vector<>();
        Report r;

        if (en instanceof Tank) {
            vDesc.addAll(applyTankCritical((Tank) en, loc, cs, damageCaused));
        } else if (en instanceof Aero) {
            vDesc.addAll(applyAeroCritical((Aero) en, loc, cs, damageCaused, isCapital));
        } else if (en instanceof BattleArmor) {
            // We might as well handle this here.
            // However, we're considering a crit against BA as a "crew kill".
            BattleArmor ba = (BattleArmor) en;
            r = new Report(6111);
            int randomTrooper = ba.getRandomTrooper();
            ba.destroyLocation(randomTrooper);
            r.add(randomTrooper);
            r.newlines = 1;
            vDesc.add(r);
        } else if (CriticalSlot.TYPE_SYSTEM == cs.getType()) {
            // Handle critical hits on system slots.
            cs.setHit(true);
            if (en instanceof Protomech) {
                vDesc.addAll(applyProtomechCritical((Protomech) en, loc, cs, secondaryEffects, damageCaused, isCapital));
            } else {
                vDesc.addAll(applyMechSystemCritical(en, loc, cs));
            }
        } else if (CriticalSlot.TYPE_EQUIPMENT == cs.getType()) {
            vDesc.addAll(applyEquipmentCritical(en, loc, cs, secondaryEffects));
        } // End crit-on-equipment-slot

        // if using buffered VDNI then a possible pilot hit
        if (en.hasAbility(OptionsConstants.MD_BVDNI) && !en.hasAbility(OptionsConstants.MD_PAIN_SHUNT)) {
            Report.addNewline(vDesc);
            Roll diceRoll = Compute.rollD6(2);
            r = new Report(3580);
            r.subject = en.getId();
            r.addDesc(en);
            r.add(7);
            r.add(diceRoll);
            r.choose(diceRoll.getIntValue() >= 8);
            r.indent(2);
            vDesc.add(r);
            if (diceRoll.getIntValue() >= 8) {
                vDesc.addAll(damageCrew(en, 1));
            }
        }

        // Return the results of the damage.
        return vDesc;
    }

    /**
     * Apply a single critical hit to an equipment slot.
     *
     * @param en               the <code>Entity</code> that is being damaged. This value may
     *                         not be <code>null</code>.
     * @param loc              the <code>int</code> location of critical hit.
     * @param cs               the <code>CriticalSlot</code> being damaged.
     * @param secondaryEffects the <code>boolean</code> flag that indicates whether to allow
     *                         critical hits to cause secondary effects (such as triggering
     *                         an ammo explosion, sending hovercraft to watery graves, or
     *                         damaging ProtoMech torso weapons). This value is normally
     *                         <code>true</code>, but it will be <code>false</code> when the
     *                         hit is being applied from a saved game or scenario.
     */
    protected Vector<Report> applyEquipmentCritical(Entity en, int loc, CriticalSlot cs,
                                                  boolean secondaryEffects) {
        Vector<Report> reports = new Vector<>();
        Report r;
        cs.setHit(true);
        Mounted mounted = cs.getMount();
        EquipmentType eqType = mounted.getType();
        boolean hitBefore = mounted.isHit();

        r = new Report(6225);
        r.subject = en.getId();
        r.indent(3);
        r.add(mounted.getDesc());
        reports.addElement(r);

        // Shield objects are not useless when they take one crit.
        if ((eqType instanceof MiscType) && ((MiscType) eqType).isShield()) {
            mounted.setHit(false);
        } else if (mounted.is(EquipmentTypeLookup.SCM)) {
            // Super-Cooled Myomer remains functional until all its slots have been hit
            if (en.damagedSCMCritCount() >= 6) {
                mounted.setHit(true);
            }
        } else {
            mounted.setHit(true);
        }

        if ((eqType instanceof MiscType) && eqType.hasFlag(MiscType.F_EMERGENCY_COOLANT_SYSTEM)) {
            ((Mech) en).setHasDamagedCoolantSystem(true);
        }

        if ((eqType instanceof MiscType) && eqType.hasFlag(MiscType.F_HARJEL)) {
            reports.addAll(breachLocation(en, loc, null, true));
        }

        // HarJel II/III hits trigger another possible critical hit on
        // the same location
        // it's like an ammunition explosion---a secondary effect
        if (secondaryEffects && (eqType instanceof MiscType)
                && (eqType.hasFlag(MiscType.F_HARJEL_II) || eqType.hasFlag(MiscType.F_HARJEL_III))
                && !hitBefore) {
            r = new Report(9852);
            r.subject = en.getId();
            r.indent(2);
            reports.addElement(r);
            reports.addAll(criticalEntity(en, loc, false, 0, 0));
        }

        // If the item is the ECM suite of a Mek Stealth system
        // then it's destruction turns off the stealth.
        if (!hitBefore && (eqType instanceof MiscType)
                && eqType.hasFlag(MiscType.F_ECM)
                && (mounted.getLinkedBy() != null)) {
            Mounted stealth = mounted.getLinkedBy();
            r = new Report(6255);
            r.subject = en.getId();
            r.indent(2);
            r.add(stealth.getType().getName());
            reports.addElement(r);
            stealth.setMode("Off");
        }

        // Handle equipment explosions.
        // Equipment explosions are secondary effects and
        // do not occur when loading from a scenario.
        if (((secondaryEffects && eqType.isExplosive(mounted))
                || mounted.isHotLoaded() || (mounted.hasChargedCapacitor() != 0))
                && !hitBefore) {
            reports.addAll(entityActionManager.explodeEquipment(en, loc, mounted, this));
        }

        // Make sure that ammo in this slot is exhausted.
        if (mounted.getBaseShotsLeft() > 0) {
            mounted.setShotsLeft(0);
        }

        // LAMs that are part of a fighter squadron will need to have the squadron recalculate
        // the bomb load out on a bomb bay critical.
        if (en.isPartOfFighterSquadron() && (mounted.getType() instanceof MiscType)
                && mounted.getType().hasFlag(MiscType.F_BOMB_BAY)) {
            Entity squadron = game.getEntity(en.getTransportId());
            if (squadron instanceof FighterSquadron) {
                ((FighterSquadron) squadron).computeSquadronBombLoadout();
            }
        }
        return reports;
    }

    /**
     * Apply a single critical hit to a Mech system.
     *
     * @param en   the <code>Entity</code> that is being damaged. This value may
     *             not be <code>null</code>.
     * @param loc  the <code>int</code> location of critical hit.
     * @param cs   the <code>CriticalSlot</code> being damaged. This value may
     *             not be <code>null</code>.
     */
    protected Vector<Report> applyMechSystemCritical(Entity en, int loc, CriticalSlot cs) {
        Vector<Report> reports = new Vector<>();
        Report r;
        r = new Report(6225);
        r.subject = en.getId();
        r.indent(3);
        r.add(((Mech) en).getSystemName(cs.getIndex()));
        reports.addElement(r);
        switch (cs.getIndex()) {
            case Mech.SYSTEM_COCKPIT:
                //First check whether this hit takes out the whole crew; for multi-crew cockpits
                //we need to check the other critical positions (if any).
                boolean allDead = true;
                int crewSlot = ((Mech) en).getCrewForCockpitSlot(loc, cs);
                if (crewSlot >= 0) {
                    for (int i = 0; i < en.getCrew().getSlotCount(); i++) {
                        if (i != crewSlot && !en.getCrew().isDead(i) && !en.getCrew().isMissing(i)) {
                            allDead = false;
                        }
                    }
                }
                if (allDead) {
                    // Don't kill a pilot multiple times.
                    if (Crew.DEATH > en.getCrew().getHits()) {
                        // Single pilot or tripod cockpit; all crew are killed.
                        en.getCrew().setDoomed(true);
                        Report.addNewline(reports);
                        reports.addAll(entityActionManager.destroyEntity(en, "pilot death", true, this));
                    }
                } else if (!en.getCrew().isMissing(crewSlot)) {
                    boolean wasPilot = en.getCrew().getCurrentPilotIndex() == crewSlot;
                    boolean wasGunner = en.getCrew().getCurrentGunnerIndex() == crewSlot;
                    en.getCrew().setDead(true, crewSlot);
                    r = new Report(6027);
                    r.subject = en.getId();
                    r.indent(2);
                    r.add(en.getCrew().getCrewType().getRoleName(crewSlot));
                    r.addDesc(en);
                    r.add(en.getCrew().getName(crewSlot));
                    reports.addElement(r);
                    r = createCrewTakeoverReport(en, crewSlot, wasPilot, wasGunner);
                    if (null != r) {
                        reports.add(r);
                    }
                }

                break;
            case Mech.SYSTEM_ENGINE:
                // if the slot is missing, the location was previously
                // destroyed and the engine hit was then counted already
                if (!cs.isMissing()) {
                    en.engineHitsThisPhase++;
                }
                int numEngineHits = en.getEngineHits();
                boolean engineExploded = checkEngineExplosion(en, reports, numEngineHits);
                int hitsToDestroy = 3;
                if (en.isSuperHeavy() && en.hasEngine()
                        && (en.getEngine().getEngineType() == Engine.COMPACT_ENGINE)) {
                    hitsToDestroy = 2;
                }

                if (!engineExploded && (numEngineHits >= hitsToDestroy)) {
                    // third engine hit
                    reports.addAll(entityActionManager.destroyEntity(en, "engine destruction", this));
                    if (game.getOptions()
                            .booleanOption(OptionsConstants.ADVGRNDMOV_AUTO_ABANDON_UNIT)) {
                        reports.addAll(abandonEntity(en));
                    }
                    en.setSelfDestructing(false);
                    en.setSelfDestructInitiated(false);
                }
                break;
            case Mech.SYSTEM_GYRO:
                int gyroHits = en.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_GYRO, loc);
                if (en.getGyroType() != Mech.GYRO_HEAVY_DUTY) {
                    gyroHits++;
                }
                // Automatically falls in AirMech mode, which it seems would indicate a crash if airborne.
                if (gyroHits == 3 && en instanceof LandAirMech && en.isAirborneVTOLorWIGE()) {
                    entityActionManager.crashAirMech(en, new PilotingRollData(en.getId(),
                            TargetRoll.AUTOMATIC_FAIL, 1, "gyro destroyed"), reports, this);
                    break;
                }
                //No PSR for Mechs in non-leg mode
                if (!en.canFall(true)) {
                    break;
                }
                switch (gyroHits) {
                    case 3:
                        // HD 3 hits, standard 2 hits
                        game.addPSR(new PilotingRollData(en.getId(), TargetRoll.AUTOMATIC_FAIL,
                                1, "gyro destroyed"));
                        // Gyro destroyed entities may not be hull down
                        en.setHullDown(false);
                        break;
                    case 2:
                        // HD 2 hits, standard 1 hit
                        game.addPSR(new PilotingRollData(en.getId(), 3, "gyro hit"));
                        break;
                    case 1:
                        // HD 1 hit
                        game.addPSR(new PilotingRollData(en.getId(), 2, "gyro hit"));
                        break;
                    default:
                        // ignore if >4 hits (don't over do it, the auto fail
                        // already happened.)
                }
                break;
            case Mech.ACTUATOR_UPPER_LEG:
            case Mech.ACTUATOR_LOWER_LEG:
            case Mech.ACTUATOR_FOOT:
                if (en.canFall(true)) {
                    // leg/foot actuator piloting roll
                    game.addPSR(new PilotingRollData(en.getId(), 1, "leg/foot actuator hit"));
                }
                break;
            case Mech.ACTUATOR_HIP:
                if (en.canFall(true)) {
                    // hip piloting roll
                    game.addPSR(new PilotingRollData(en.getId(), 2, "hip actuator hit"));
                }
                break;
            case LandAirMech.LAM_AVIONICS:
                if (en.getConversionMode() == LandAirMech.CONV_MODE_FIGHTER) {
                    if (en.isPartOfFighterSquadron()) {
                        game.addControlRoll(new PilotingRollData(
                                en.getTransportId(), 1, "avionics hit"));
                    } else if (en.isCapitalFighter()) {
                        game.addControlRoll(new PilotingRollData(en.getId(), 1,
                                "avionics hit"));
                    } else {
                        game.addControlRoll(new PilotingRollData(en.getId(), 0,
                                "avionics hit"));
                    }
                }
                break;
        }
        return reports;
    }

    /**
     * Apply a single critical hit to a ProtoMech.
     *
     * @param pm               the <code>Protomech</code> that is being damaged. This value may
     *                         not be <code>null</code>.
     * @param loc              the <code>int</code> location of critical hit. This value may
     *                         be <code>Entity.NONE</code> for hits to a <code>Protomech</code>
     *                         torso weapon.
     * @param cs               the <code>CriticalSlot</code> being damaged. This value may
     *                         not be <code>null</code>.
     * @param secondaryEffects the <code>boolean</code> flag that indicates whether to allow
     *                         critical hits to cause secondary effects (such as damaging
     *                         ProtoMech torso weapons). This value is normally
     *                         <code>true</code>, but it will be <code>false</code> when the
     *                         hit is being applied from a saved game or scenario.
     * @param damageCaused     the amount of damage causing this critical.
     * @param isCapital        whether it was capital scale damage that caused critical
     */
    protected Vector<Report> applyProtomechCritical(Protomech pm, int loc, CriticalSlot cs,
                                                  boolean secondaryEffects, int damageCaused,
                                                  boolean isCapital) {
        Vector<Report> reports = new Vector<>();
        Report r;
        int numHit = pm.getCritsHit(loc);
        if ((cs.getIndex() != Protomech.SYSTEM_TORSO_WEAPON_A)
                && (cs.getIndex() != Protomech.SYSTEM_TORSO_WEAPON_B)
                && (cs.getIndex() != Protomech.SYSTEM_TORSO_WEAPON_C)
                && (cs.getIndex() != Protomech.SYSTEM_TORSO_WEAPON_D)
                && (cs.getIndex() != Protomech.SYSTEM_TORSO_WEAPON_E)
                && (cs.getIndex() != Protomech.SYSTEM_TORSO_WEAPON_F)) {
            r = new Report(6225);
            r.subject = pm.getId();
            r.indent(3);
            r.add(Protomech.systemNames[cs.getIndex()]);
            reports.addElement(r);
        }
        switch (cs.getIndex()) {
            case Protomech.SYSTEM_HEADCRIT:
                if (2 == numHit) {
                    r = new Report(6230);
                    r.subject = pm.getId();
                    reports.addElement(r);
                    pm.destroyLocation(loc);
                }
                break;
            case Protomech.SYSTEM_ARMCRIT:
                if (2 == numHit) {
                    r = new Report(6235);
                    r.subject = pm.getId();
                    reports.addElement(r);
                    pm.destroyLocation(loc);
                }
                break;
            case Protomech.SYSTEM_LEGCRIT:
                if (3 == numHit) {
                    r = new Report(6240);
                    r.subject = pm.getId();
                    r.newlines = 0;
                    reports.addElement(r);
                    pm.destroyLocation(loc);
                }
                break;
            case Protomech.SYSTEM_TORSOCRIT:
                if (3 == numHit) {
                    reports.addAll(entityActionManager.destroyEntity(pm, "torso destruction", this));
                }
                // Torso weapon hits are secondary effects and
                // do not occur when loading from a scenario.
                else if (secondaryEffects) {
                    int tweapRoll = Compute.d6(1);
                    CriticalSlot newSlot;

                    switch (tweapRoll) {
                        case 1:
                            if (pm.isQuad()) {
                                newSlot = new CriticalSlot(CriticalSlot.TYPE_SYSTEM,
                                        Protomech.SYSTEM_TORSO_WEAPON_A);
                                reports.addAll(applyCriticalHit(pm, Entity.NONE, newSlot,
                                        secondaryEffects, damageCaused, isCapital));
                                break;
                            }
                        case 2:
                            if (pm.isQuad()) {
                                newSlot = new CriticalSlot(CriticalSlot.TYPE_SYSTEM,
                                        Protomech.SYSTEM_TORSO_WEAPON_B);
                                reports.addAll(applyCriticalHit(pm, Entity.NONE, newSlot,
                                        secondaryEffects, damageCaused, isCapital));
                                break;
                            }
                            newSlot = new CriticalSlot(CriticalSlot.TYPE_SYSTEM,
                                    Protomech.SYSTEM_TORSO_WEAPON_A);
                            reports.addAll(applyCriticalHit(pm, Entity.NONE, newSlot,
                                    secondaryEffects, damageCaused, isCapital));
                            break;
                        case 3:
                            if (pm.isQuad()) {
                                newSlot = new CriticalSlot(CriticalSlot.TYPE_SYSTEM,
                                        Protomech.SYSTEM_TORSO_WEAPON_C);
                                reports.addAll(applyCriticalHit(pm, Entity.NONE, newSlot,
                                        secondaryEffects, damageCaused, isCapital));
                                break;
                            }
                        case 4:
                            if (pm.isQuad()) {
                                newSlot = new CriticalSlot(CriticalSlot.TYPE_SYSTEM,
                                        Protomech.SYSTEM_TORSO_WEAPON_D);
                                reports.addAll(applyCriticalHit(pm, Entity.NONE, newSlot,
                                        secondaryEffects, damageCaused, isCapital));
                                break;
                            }
                            newSlot = new CriticalSlot(CriticalSlot.TYPE_SYSTEM,
                                    Protomech.SYSTEM_TORSO_WEAPON_B);
                            reports.addAll(applyCriticalHit(pm, Entity.NONE, newSlot,
                                    secondaryEffects, damageCaused, isCapital));
                            break;
                        case 5:
                            if (pm.getWeight() > 9) {
                                if (pm.isQuad()) {
                                    newSlot = new CriticalSlot(CriticalSlot.TYPE_SYSTEM,
                                            Protomech.SYSTEM_TORSO_WEAPON_E);
                                    reports.addAll(applyCriticalHit(pm, Entity.NONE, newSlot,
                                            secondaryEffects, damageCaused, isCapital));
                                    break;
                                }
                                newSlot = new CriticalSlot(
                                        CriticalSlot.TYPE_SYSTEM,
                                        Protomech.SYSTEM_TORSO_WEAPON_C);
                                reports.addAll(applyCriticalHit(pm, Entity.NONE, newSlot,
                                        secondaryEffects, damageCaused, isCapital));
                                break;
                            }
                        case 6:
                            if (pm.getWeight() > 9) {
                                if (pm.isQuad()) {
                                    newSlot = new CriticalSlot(CriticalSlot.TYPE_SYSTEM,
                                            Protomech.SYSTEM_TORSO_WEAPON_F);
                                    reports.addAll(applyCriticalHit(pm, Entity.NONE, newSlot,
                                            secondaryEffects, damageCaused, isCapital));
                                    break;
                                }
                                newSlot = new CriticalSlot(CriticalSlot.TYPE_SYSTEM,
                                        Protomech.SYSTEM_TORSO_WEAPON_C);
                                reports.addAll(applyCriticalHit(pm, Entity.NONE, newSlot,
                                        secondaryEffects, damageCaused, isCapital));
                                break;
                            }
                    }
                    // A magnetic clamp system is destroyed by any torso critical.
                    Mounted magClamp = pm.getMisc().stream().filter(m -> m.getType()
                            .hasFlag(MiscType.F_MAGNETIC_CLAMP)).findFirst().orElse(null);
                    if ((magClamp != null) && !magClamp.isHit()) {
                        magClamp.setHit(true);
                        r = new Report(6252);
                        r.subject = pm.getId();
                        reports.addElement(r);
                    }
                }
                break;
            case Protomech.SYSTEM_TORSO_WEAPON_A:
                Mounted weaponA = pm.getTorsoWeapon(cs.getIndex());
                if (null != weaponA) {
                    weaponA.setHit(true);
                    r = new Report(6245);
                    r.subject = pm.getId();
                    r.newlines = 0;
                    reports.addElement(r);
                }
                break;
            case Protomech.SYSTEM_TORSO_WEAPON_B:
                Mounted weaponB = pm.getTorsoWeapon(cs.getIndex());
                if (null != weaponB) {
                    weaponB.setHit(true);
                    r = new Report(6246);
                    r.subject = pm.getId();
                    r.newlines = 0;
                    reports.addElement(r);
                }
                break;
            case Protomech.SYSTEM_TORSO_WEAPON_C:
                Mounted weaponC = pm.getTorsoWeapon(cs.getIndex());
                if (null != weaponC) {
                    weaponC.setHit(true);
                    r = new Report(6247);
                    r.subject = pm.getId();
                    r.newlines = 0;
                    reports.addElement(r);
                }
                break;
            case Protomech.SYSTEM_TORSO_WEAPON_D:
                Mounted weaponD = pm.getTorsoWeapon(cs.getIndex());
                if (null != weaponD) {
                    weaponD.setHit(true);
                    r = new Report(6248);
                    r.subject = pm.getId();
                    r.newlines = 0;
                    reports.addElement(r);
                }
                break;
            case Protomech.SYSTEM_TORSO_WEAPON_E:
                Mounted weaponE = pm.getTorsoWeapon(cs.getIndex());
                if (null != weaponE) {
                    weaponE.setHit(true);
                    r = new Report(6249);
                    r.subject = pm.getId();
                    r.newlines = 0;
                    reports.addElement(r);
                }
                break;
            case Protomech.SYSTEM_TORSO_WEAPON_F:
                Mounted weaponF = pm.getTorsoWeapon(cs.getIndex());
                if (null != weaponF) {
                    weaponF.setHit(true);
                    r = new Report(6250);
                    r.subject = pm.getId();
                    r.newlines = 0;
                    reports.addElement(r);
                }
                break;
        }

        // Shaded hits cause pilot damage.
        if (pm.shaded(loc, numHit)) {
            // Destroyed ProtoMech sections have
            // already damaged the pilot.
            int pHits = Protomech.POSSIBLE_PILOT_DAMAGE[loc]
                    - pm.getPilotDamageTaken(loc);
            if (Math.min(1, pHits) > 0) {
                Report.addNewline(reports);
                reports.addAll(damageCrew(pm, 1));
                pHits = 1 + pm.getPilotDamageTaken(loc);
                pm.setPilotDamageTaken(loc, pHits);
            }
        }
        return reports;
    }

    /**
     * Apply a single critical hit to an aerospace unit.
     *
     * @param aero             the <code>Aero</code> that is being damaged. This value may
     *                         not be <code>null</code>.
     * @param loc              the <code>int</code> location of critical hit.
     * @param cs               the <code>CriticalSlot</code> being damaged. This value may
     *                         not be <code>null</code>.
     * @param damageCaused     the amount of damage causing this critical.
     * @param isCapital        whether it was capital scale damage that caused critical
     */
    protected Vector<Report> applyAeroCritical(Aero aero, int loc, CriticalSlot cs, int damageCaused, boolean isCapital) {
        Vector<Report> reports = new Vector<>();
        Report r;
        Jumpship js = null;
        if (aero instanceof Jumpship) {
            js = (Jumpship) aero;
        }

        switch (cs.getIndex()) {
            case Aero.CRIT_NONE:
                // no effect
                r = new Report(6005);
                r.subject = aero.getId();
                reports.add(r);
                break;
            case Aero.CRIT_FCS:
                // Fire control system
                r = new Report(9105);
                r.subject = aero.getId();
                reports.add(r);
                aero.setFCSHits(aero.getFCSHits() + 1);
                break;
            case Aero.CRIT_SENSOR:
                // sensors
                r = new Report(6620);
                r.subject = aero.getId();
                reports.add(r);
                aero.setSensorHits(aero.getSensorHits() + 1);
                break;
            case Aero.CRIT_AVIONICS:
                // avionics
                r = new Report(9110);
                r.subject = aero.getId();
                reports.add(r);
                aero.setAvionicsHits(aero.getAvionicsHits() + 1);
                if (aero.isPartOfFighterSquadron()) {
                    game.addControlRoll(new PilotingRollData(
                            aero.getTransportId(), 1, "avionics hit"));
                } else if (aero.isCapitalFighter()) {
                    game.addControlRoll(new PilotingRollData(aero.getId(), 1,
                            "avionics hit"));
                } else {
                    game.addControlRoll(new PilotingRollData(aero.getId(), 0,
                            "avionics hit"));
                }
                break;
            case Aero.CRIT_CONTROL:
                // force control roll
                r = new Report(9115);
                r.subject = aero.getId();
                reports.add(r);
                if (aero.isPartOfFighterSquadron()) {
                    game.addControlRoll(new PilotingRollData(
                            aero.getTransportId(), 1, "critical hit"));
                } else if (aero.isCapitalFighter()) {
                    game.addControlRoll(new PilotingRollData(aero.getId(), 1,
                            "critical hit"));
                } else {
                    game.addControlRoll(new PilotingRollData(aero.getId(), 0,
                            "critical hit"));
                }
                break;
            case Aero.CRIT_FUEL_TANK:
                // fuel tank
                int boomTarget = 10;
                if (aero.hasQuirk(OptionsConstants.QUIRK_NEG_FRAGILE_FUEL)) {
                    boomTarget = 8;
                }
                if (aero.isLargeCraft() && aero.isClan()
                        && game.getOptions().booleanOption(
                        OptionsConstants.ADVAERORULES_STRATOPS_HARJEL)) {
                    boomTarget = 12;
                }
                // check for possible explosion
                int fuelroll = Compute.d6(2);
                r = new Report(9120);
                r.subject = aero.getId();
                if (fuelroll >= boomTarget) {
                    // A chance to reroll the explosion with edge
                    if (aero.getCrew().hasEdgeRemaining()
                            && aero.getCrew().getOptions().booleanOption(
                            OptionsConstants.EDGE_WHEN_AERO_EXPLOSION)) {
                        // Reporting this is funky because 9120 only has room for 2 choices. Replace it.
                        r = new Report(9123);
                        r.subject = aero.getId();
                        r.newlines = 0;
                        reports.add(r);
                        aero.getCrew().decreaseEdge();
                        fuelroll = Compute.d6(2);
                        // To explode, or not to explode
                        if (fuelroll >= boomTarget) {
                            r = new Report(9124);
                            r.subject = aero.getId();
                        } else {
                            r = new Report(9122);
                            r.subject = aero.getId();
                            reports.add(r);
                            break;
                        }
                    }
                    r.choose(true);
                    reports.add(r);
                    // Lets auto-eject if we can!
                    if (aero.isFighter()) {
                        if (aero.isAutoEject()
                                && (!game.getOptions().booleanOption(
                                OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                || (game.getOptions().booleanOption(
                                OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                && aero.isCondEjectFuel()))) {
                            reports.addAll(ejectEntity(aero, true, false));
                        }
                    }
                    reports.addAll(entityActionManager.destroyEntity(aero, "fuel explosion", false, false, this));
                } else {
                    r.choose(false);
                    reports.add(r);
                }

                aero.setFuelTankHit(true);
                break;
            case Aero.CRIT_CREW:
                // pilot hit
                r = new Report(6650);
                if (aero.hasAbility(OptionsConstants.MD_DERMAL_ARMOR)) {
                    r = new Report(6651);
                    r.subject = aero.getId();
                    reports.add(r);
                    break;
                } else if (aero.hasAbility(OptionsConstants.MD_TSM_IMPLANT)) {
                    r = new Report(6652);
                    r.subject = aero.getId();
                    reports.add(r);
                    break;
                }
                if ((aero instanceof SmallCraft) || (aero instanceof Jumpship)) {
                    r = new Report(9197);
                }
                if (aero.isLargeCraft() && aero.isClan()
                        && game.getOptions().booleanOption(
                        OptionsConstants.ADVAERORULES_STRATOPS_HARJEL)
                        && (aero.getIgnoredCrewHits() < 2)) {
                    aero.setIgnoredCrewHits(aero.getIgnoredCrewHits() + 1);
                    r = new Report(9198);
                    r.subject = aero.getId();
                    reports.add(r);
                    break;
                }
                r.subject = aero.getId();
                reports.add(r);
                reports.addAll(damageCrew(aero, 1));
                // The pilot may have just expired.
                if ((aero.getCrew().isDead() || aero.getCrew().isDoomed())
                        && !aero.getCrew().isEjected()) {
                    reports.addAll(entityActionManager.destroyEntity(aero, "pilot death", true, true, this));
                }
                break;
            case Aero.CRIT_GEAR:
                // landing gear
                r = new Report(9125);
                r.subject = aero.getId();
                reports.add(r);
                aero.setGearHit(true);
                break;
            case Aero.CRIT_BOMB:
                // bomb destroyed
                // go through bomb list and choose one (internal bay munitions are handled separately)
                List<Mounted> bombs = new ArrayList<>();
                for (Mounted bomb : aero.getBombs()) {
                    if (bomb.getType().isHittable() && (bomb.getHittableShotsLeft() > 0) && !bomb.isInternalBomb()) {
                        bombs.add(bomb);
                    }
                }
                if (!bombs.isEmpty()) {
                    Mounted hitbomb = bombs.get(Compute.randomInt(bombs.size()));
                    hitbomb.setShotsLeft(0);
                    hitbomb.setDestroyed(true);
                    r = new Report(9130);
                    r.subject = aero.getId();
                    r.add(hitbomb.getDesc());
                    reports.add(r);
                    // If we are part of a squadron, we should recalculate
                    // the bomb salvo for the squadron
                    if (aero.getTransportId() != Entity.NONE) {
                        Entity e = game.getEntity(aero.getTransportId());
                        if (e instanceof FighterSquadron) {
                            ((FighterSquadron) e).computeSquadronBombLoadout();
                        }
                    }
                } else {
                    r = new Report(9131);
                    r.subject = aero.getId();
                    reports.add(r);
                }
                break;
            case Aero.CRIT_HEATSINK:
                // heat sink hit
                int sinksLost = 1;
                if (isCapital) {
                    sinksLost = 10;
                }
                r = new Report(9135);
                r.subject = aero.getId();
                r.add(sinksLost);
                reports.add(r);
                aero.setHeatSinks(Math.max(0, aero.getHeatSinks() - sinksLost));
                break;
            case Aero.CRIT_WEAPON_BROAD:
                if (aero instanceof Warship) {
                    if ((loc == Jumpship.LOC_ALS) || (loc == Jumpship.LOC_FLS)) {
                        loc = Warship.LOC_LBS;
                    } else if ((loc == Jumpship.LOC_ARS)
                            || (loc == Jumpship.LOC_FRS)) {
                        loc = Warship.LOC_RBS;
                    }
                }
            case Aero.CRIT_WEAPON:
                if (aero.isCapitalFighter()) {
                    FighterSquadron cf = (FighterSquadron) aero;
                    boolean destroyAll = false;
                    // CRIT_WEAPON damages the capital fighter/squadron's weapon groups
                    // Go ahead and map damage for the fighter's weapon criticals for MHQ
                    // resolution.
                    cf.damageCapFighterWeapons(loc);
                    if ((loc == Aero.LOC_NOSE) || (loc == Aero.LOC_AFT)) {
                        destroyAll = true;
                    }

                    // Convert L/R wing location to wings, else wing weapons never get hit
                    if (loc == Aero.LOC_LWING || loc == Aero.LOC_RWING) {
                        loc = Aero.LOC_WINGS;
                    }

                    if (loc == Aero.LOC_WINGS) {
                        if (cf.areWingsHit()) {
                            destroyAll = true;
                        } else {
                            cf.setWingsHit(true);
                        }
                    }
                    for (Mounted weapon : cf.getWeaponList()) {
                        if (weapon.getLocation() == loc) {
                            if (destroyAll) {
                                weapon.setHit(true);
                            } else {
                                weapon.setNWeapons(weapon.getNWeapons() / 2);
                            }
                        }
                    }
                    // also destroy any ECM or BAP in the location hit
                    for (Mounted misc : cf.getMisc()) {
                        if ((misc.getType().hasFlag(MiscType.F_ECM)
                                || misc.getType().hasFlag(MiscType.F_ANGEL_ECM)
                                || misc.getType().hasFlag(MiscType.F_BAP))
                                && misc.getLocation() == loc) {
                            misc.setHit(true);
                            //Taharqa: We should also damage the critical slot, or
                            //MM and MHQ won't remember that this weapon is damaged on the MUL
                            //file
                            for (int i = 0; i < cf.getNumberOfCriticals(loc); i++) {
                                CriticalSlot slot1 = cf.getCritical(loc, i);
                                if ((slot1 == null) ||
                                        (slot1.getType() == CriticalSlot.TYPE_SYSTEM)) {
                                    continue;
                                }
                                Mounted mounted = slot1.getMount();
                                if (mounted.equals(misc)) {
                                    cf.hitAllCriticals(loc, i);
                                    break;
                                }
                            }
                        }
                    }
                    r = new Report(9152);
                    r.subject = cf.getId();
                    r.add(cf.getLocationName(loc));
                    reports.add(r);
                    break;
                }
                r = new Report(9150);
                r.subject = aero.getId();
                List<Mounted> weapons = new ArrayList<>();
                // Ignore internal bomb bay-mounted weapons
                for (Mounted weapon : aero.getWeaponList()) {
                    if ((weapon.getLocation() == loc) && !weapon.isDestroyed() && !weapon.isInternalBomb()
                            && weapon.getType().isHittable()) {
                        weapons.add(weapon);
                    }
                }
                // add in hittable misc equipment; internal bay munitions are handled separately.
                for (Mounted misc : aero.getMisc()) {
                    if (misc.getType().isHittable()
                            && (misc.getLocation() == loc)
                            && !misc.isDestroyed()
                            && !misc.isInternalBomb()) {
                        weapons.add(misc);
                    }
                }

                if (!weapons.isEmpty()) {
                    Mounted weapon = weapons.get(Compute.randomInt(weapons.size()));
                    // possibly check for an ammo explosion
                    // don't allow ammo explosions on fighter squadrons
                    if (game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_STRATOPS_AMMO_EXPLOSIONS)
                            && !(aero instanceof FighterSquadron)
                            && (weapon.getType() instanceof WeaponType)) {
                        //Bay Weapons
                        if (aero.usesWeaponBays()) {
                            //Finish reporting(9150) a hit on the bay
                            r.add(weapon.getName());
                            reports.add(r);
                            //Pick a random weapon in the bay and get the stats
                            int wId = weapon.getBayWeapons().get(Compute.randomInt(weapon.getBayWeapons().size()));
                            Mounted bayW = aero.getEquipment(wId);
                            Mounted bayWAmmo = bayW.getLinked();
                            if (bayWAmmo != null && bayWAmmo.getType().isExplosive(bayWAmmo)) {
                                r = new Report(9156);
                                r.subject = aero.getId();
                                r.newlines = 1;
                                r.indent(2);
                                //On a roll of 10+, the ammo bin explodes
                                int ammoRoll = Compute.d6(2);
                                boomTarget = 10;
                                r.choose(ammoRoll >= boomTarget);
                                // A chance to reroll an explosion with edge
                                if (aero.getCrew().hasEdgeRemaining()
                                        && aero.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_AERO_EXPLOSION)
                                        && ammoRoll >= boomTarget) {
                                    // Report 9156 doesn't offer the right choices. Replace it.
                                    r = new Report(9158);
                                    r.subject = aero.getId();
                                    r.newlines = 0;
                                    r.indent(2);
                                    reports.add(r);
                                    aero.getCrew().decreaseEdge();
                                    ammoRoll = Compute.d6(2);
                                    // To explode, or not to explode
                                    if (ammoRoll >= boomTarget) {
                                        reports.addAll(entityActionManager.explodeEquipment(aero, loc, bayWAmmo, this));
                                    } else {
                                        r = new Report(9157);
                                        r.subject = aero.getId();
                                        reports.add(r);
                                    }
                                } else {
                                    // Finish handling report 9156
                                    reports.add(r);
                                    if (ammoRoll >= boomTarget) {
                                        reports.addAll(entityActionManager.explodeEquipment(aero, loc, bayWAmmo, this));
                                    }
                                }
                            }
                            // Hit the weapon then also hit all the other weapons in the bay
                            weapon.setHit(true);
                            for (int next : weapon.getBayWeapons()) {
                                Mounted bayWeap = aero.getEquipment(next);
                                if (null != bayWeap) {
                                    bayWeap.setHit(true);
                                    // Taharqa : We should also damage the critical slot, or MM and
                                    // MHQ won't remember that this weapon is damaged on the MUL file
                                    for (int i = 0; i < aero.getNumberOfCriticals(loc); i++) {
                                        CriticalSlot slot1 = aero.getCritical(loc, i);
                                        if ((slot1 == null) ||
                                                (slot1.getType() == CriticalSlot.TYPE_SYSTEM)) {
                                            continue;
                                        }
                                        Mounted mounted = slot1.getMount();
                                        if (mounted.equals(bayWeap)) {
                                            aero.hitAllCriticals(loc, i);
                                            break;
                                        }
                                    }
                                }
                            }
                            break;
                        }
                        // does it use Ammo?
                        WeaponType wtype = (WeaponType) weapon.getType();
                        if (wtype.getAmmoType() != AmmoType.T_NA) {
                            Mounted m = weapon.getLinked();
                            int ammoroll = Compute.d6(2);
                            if (ammoroll >= 10) {
                                // A chance to reroll an explosion with edge
                                if (aero.getCrew().hasEdgeRemaining()
                                        && aero.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_AERO_EXPLOSION)) {
                                    aero.getCrew().decreaseEdge();
                                    r = new Report(6530);
                                    r.subject = aero.getId();
                                    r.add(aero.getCrew().getOptions().intOption(OptionsConstants.EDGE));
                                    reports.add(r);
                                    ammoroll = Compute.d6(2);
                                    if (ammoroll >= 10) {
                                        reports.addAll(entityActionManager.explodeEquipment(aero, loc, m, this));
                                        break;
                                    } else {
                                        // Crisis averted, set report 9150 back up
                                        r = new Report(9150);
                                        r.subject = aero.getId();
                                    }
                                } else {
                                    r = new Report(9151);
                                    r.subject = aero.getId();
                                    r.add(m.getName());
                                    r.newlines = 0;
                                    reports.add(r);
                                    reports.addAll(entityActionManager.explodeEquipment(aero, loc, m, this));
                                    break;
                                }
                            }
                        }
                    }
                    // If the weapon is explosive, use edge to roll up a new one
                    if (aero.getCrew().hasEdgeRemaining()
                            && aero.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_AERO_EXPLOSION)
                            && (weapon.getType().isExplosive(weapon) && !weapon.isHit()
                            && !weapon.isDestroyed())) {
                        aero.getCrew().decreaseEdge();
                        // Try something new for an interrupting report. r is still 9150.
                        Report r1 = new Report(6530);
                        r1.subject = aero.getId();
                        r1.add(aero.getCrew().getOptions().intOption(OptionsConstants.EDGE));
                        reports.add(r1);
                        weapon = weapons.get(Compute.randomInt(weapons.size()));
                    }
                    r.add(weapon.getName());
                    reports.add(r);
                    // explosive weapons e.g. gauss now explode
                    if (weapon.getType().isExplosive(weapon) && !weapon.isHit()
                            && !weapon.isDestroyed()) {
                        reports.addAll(entityActionManager.explodeEquipment(aero, loc, weapon, this));
                    }
                    weapon.setHit(true);
                    // Taharqa : We should also damage the critical slot, or MM and MHQ won't
                    // remember that this weapon is damaged on the MUL file
                    for (int i = 0; i < aero.getNumberOfCriticals(loc); i++) {
                        CriticalSlot slot1 = aero.getCritical(loc, i);
                        if ((slot1 == null) || (slot1.getType() == CriticalSlot.TYPE_SYSTEM)) {
                            continue;
                        }
                        Mounted mounted = slot1.getMount();
                        if (mounted.equals(weapon)) {
                            aero.hitAllCriticals(loc, i);
                            break;
                        }
                    }
                    // if this is a weapons bay then also hit all the other weapons
                    for (int wId : weapon.getBayWeapons()) {
                        Mounted bayWeap = aero.getEquipment(wId);
                        if (null != bayWeap) {
                            bayWeap.setHit(true);
                            // Taharqa : We should also damage the critical slot, or MM and MHQ
                            // won't remember that this weapon is damaged on the MUL file
                            for (int i = 0; i < aero.getNumberOfCriticals(loc); i++) {
                                CriticalSlot slot1 = aero.getCritical(loc, i);
                                if ((slot1 == null)
                                        || (slot1.getType() == CriticalSlot.TYPE_SYSTEM)) {
                                    continue;
                                }
                                Mounted mounted = slot1.getMount();
                                if (mounted.equals(bayWeap)) {
                                    aero.hitAllCriticals(loc, i);
                                    break;
                                }
                            }
                        }
                    }
                } else {
                    r = new Report(9155);
                    r.subject = aero.getId();
                    reports.add(r);
                }
                break;
            case Aero.CRIT_ENGINE:
                // engine hit
                r = new Report(9140);
                r.subject = aero.getId();
                reports.add(r);
                aero.engineHitsThisPhase++;
                boolean engineExploded = checkEngineExplosion(aero, reports, 1);
                aero.setEngineHits(aero.getEngineHits() + 1);
                if ((aero.getEngineHits() >= aero.getMaxEngineHits())
                        || engineExploded) {
                    // this engine hit puts the ASF out of commission
                    reports.addAll(entityActionManager.destroyEntity(aero, "engine destruction", true, true, this));
                    aero.setSelfDestructing(false);
                    aero.setSelfDestructInitiated(false);
                }
                break;
            case Aero.CRIT_LEFT_THRUSTER:
                // thruster hit
                r = new Report(9160);
                r.subject = aero.getId();
                reports.add(r);
                aero.setLeftThrustHits(aero.getLeftThrustHits() + 1);
                break;
            case Aero.CRIT_RIGHT_THRUSTER:
                // thruster hit
                r = new Report(9160);
                r.subject = aero.getId();
                reports.add(r);
                aero.setRightThrustHits(aero.getRightThrustHits() + 1);
                break;
            case Aero.CRIT_CARGO:
                applyCargoCritical(aero, damageCaused, reports);
                break;
            case Aero.CRIT_DOOR:
                // door hit
                // choose a random bay
                String bayType = aero.damageBayDoor();
                if (!bayType.equals("none")) {
                    r = new Report(9170);
                    r.subject = aero.getId();
                    r.add(bayType);
                    reports.add(r);
                } else {
                    r = new Report(9171);
                    r.subject = aero.getId();
                    reports.add(r);
                }
                break;
            case Aero.CRIT_DOCK_COLLAR:
                // docking collar hit
                // different effect for DropShips and JumpShips
                if (aero instanceof Dropship) {
                    ((Dropship) aero).setDamageDockCollar(true);
                    r = new Report(9175);
                    r.subject = aero.getId();
                    reports.add(r);
                }
                if (aero instanceof Jumpship) {
                    // damage a random docking collar
                    if (aero.damageDockCollar()) {
                        r = new Report(9176);
                        r.subject = aero.getId();
                        reports.add(r);
                    } else {
                        r = new Report(9177);
                        r.subject = aero.getId();
                        reports.add(r);
                    }
                }
                break;
            case Aero.CRIT_KF_BOOM:
                // KF boom hit
                // no real effect yet
                if (aero instanceof Dropship) {
                    ((Dropship) aero).setDamageKFBoom(true);
                    r = new Report(9180);
                    r.subject = aero.getId();
                    reports.add(r);
                }
                break;
            case Aero.CRIT_CIC:
                if (js == null) {
                    break;
                }
                // CIC hit
                r = new Report(9185);
                r.subject = aero.getId();
                reports.add(r);
                js.setCICHits(js.getCICHits() + 1);
                break;
            case Aero.CRIT_KF_DRIVE:
                //Per SO construction rules, stations have no KF drive, therefore they can't take a hit to it...
                if (js == null || js instanceof SpaceStation) {
                    break;
                }
                // KF Drive hit - damage the drive integrity
                js.setKFIntegrity(Math.max(0, (js.getKFIntegrity() - 1)));
                if (game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_EXPANDED_KF_DRIVE_DAMAGE)) {
                    // Randomize the component struck - probabilities taken from the old BattleSpace record sheets
                    switch (Compute.d6(2)) {
                        case 2:
                            // Drive Coil Hit
                            r = new Report(9186);
                            r.subject = aero.getId();
                            reports.add(r);
                            js.setKFDriveCoilHit(true);
                            break;
                        case 3:
                        case 11:
                            // Charging System Hit
                            r = new Report(9187);
                            r.subject = aero.getId();
                            reports.add(r);
                            js.setKFChargingSystemHit(true);
                            break;
                        case 5:
                            // Field Initiator Hit
                            r = new Report(9190);
                            r.subject = aero.getId();
                            reports.add(r);
                            js.setKFFieldInitiatorHit(true);
                            break;
                        case 4:
                        case 6:
                        case 7:
                        case 8:
                            // Helium Tank Hit
                            r = new Report(9189);
                            r.subject = aero.getId();
                            reports.add(r);
                            js.setKFHeliumTankHit(true);
                            break;
                        case 9:
                            // Drive Controller Hit
                            r = new Report(9191);
                            r.subject = aero.getId();
                            reports.add(r);
                            js.setKFDriveControllerHit(true);
                            break;
                        case 10:
                        case 12:
                            // LF Battery Hit - if you don't have one, treat as helium tank
                            if (js.hasLF()) {
                                r = new Report(9188);
                                r.subject = aero.getId();
                                reports.add(r);
                                js.setLFBatteryHit(true);
                            } else {
                                r = new Report(9189);
                                r.subject = aero.getId();
                                reports.add(r);
                                js.setKFHeliumTankHit(true);
                            }
                            break;
                    }
                } else {
                    // Just report the standard KF hit, per SO rules
                    r = new Report(9194);
                    r.subject = aero.getId();
                    reports.add(r);
                }
                break;
            case Aero.CRIT_GRAV_DECK:
                if (js == null) {
                    break;
                } else if (js.getTotalGravDeck() <= 0) {
                    LogManager.getLogger().error("Cannot handle a grav deck crit for a JumpShip with no grav decks");
                    break;
                }
                int choice = Compute.randomInt(js.getTotalGravDeck());
                // Grav Deck hit
                r = new Report(9195);
                r.subject = aero.getId();
                reports.add(r);
                js.setGravDeckDamageFlag(choice, 1);
                break;
            case Aero.CRIT_LIFE_SUPPORT:
                // Life Support hit
                aero.setLifeSupport(false);
                r = new Report(9196);
                r.subject = aero.getId();
                reports.add(r);
                break;
        }
        return reports;
    }

    /**
     * Selects random undestroyed bay and applies damage, destroying loaded units where applicable.
     *
     * @param aero         The unit that received the cargo critical.
     * @param damageCaused The amount of damage applied by the hit that resulted in the cargo critical.
     * @param reports      Used to return any report generated while applying the critical.
     */
    protected void applyCargoCritical(Aero aero, int damageCaused, Vector<Report> reports) {
        Report r;
        // cargo hit
        // First what percentage of the cargo did the hit destroy?
        double percentDestroyed = 0.0;
        double mult = 2.0;
        if (aero.isLargeCraft() && aero.isClan()
                && game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_STRATOPS_HARJEL)) {
            mult = 4.0;
        }
        if (damageCaused > 0) {
            percentDestroyed = Math.min(
                    damageCaused / (mult * aero.getSI()), 1.0);
        }
        List<Bay> bays;
        double destroyed = 0;
        // did it hit cargo or units
        int roll = Compute.d6(1);
        // A hit on a bay filled with transported units is devastating
        // allow a reroll with edge
        if (aero.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_AERO_UNIT_CARGO_LOST)
                && aero.getCrew().hasEdgeRemaining() && roll > 3) {
            aero.getCrew().decreaseEdge();
            r = new Report(9172);
            r.subject = aero.getId();
            r.add(aero.getCrew().getOptions().intOption(OptionsConstants.EDGE));
            reports.add(r);
            // Reroll. Maybe we'll hit cargo.
            roll = Compute.d6(1);
        }
        if (roll < 4) {
            bays = aero.getTransportBays().stream().filter(Bay::isCargo).collect(Collectors.toList());
        } else {
            bays = aero.getTransportBays().stream()
                    .filter(b -> !b.isCargo() && !b.isQuarters()).collect(Collectors.toList());
        }
        Bay hitBay = null;
        while ((null == hitBay) && !bays.isEmpty()) {
            hitBay = bays.remove(Compute.randomInt(bays.size()));
            if (hitBay.getBayDamage() < hitBay.getCapacity()) {
                if (hitBay.isCargo()) {
                    destroyed = (hitBay.getCapacity() * percentDestroyed * 2.0) / 2.0;
                } else {
                    destroyed = Math.ceil(hitBay.getCapacity() * percentDestroyed);
                }
            } else {
                hitBay = null;
            }
        }
        if (null != hitBay) {
            destroyed = Math.min(destroyed, hitBay.getCapacity() - hitBay.getBayDamage());
            if (hitBay.isCargo()) {
                r = new Report(9165);
            } else {
                r = new Report(9166);
            }
            r.subject = aero.getId();
            r.add(hitBay.getBayNumber());
            if (destroyed == (int) destroyed) {
                r.add((int) destroyed);
            } else {
                r.add(String.valueOf(Math.ceil(destroyed * 2.0) / 2.0));
            }
            reports.add(r);
            if (!hitBay.isCargo()) {
                List<Entity> units = new ArrayList<>(hitBay.getLoadedUnits());
                List<Entity> toRemove = new ArrayList<>();
                // We're letting destroyed units stay in the bay now, but take them off the targets list
                for (Entity en : units) {
                    if (en.isDestroyed() || en.isDoomed()) {
                        toRemove.add(en);
                    }
                }
                units.removeAll(toRemove);
                while ((destroyed > 0) && !units.isEmpty()) {
                    Entity target = units.remove(Compute.randomInt(units.size()));
                    reports.addAll(entityActionManager.destroyEntity(target, "cargo damage",
                            false, true, this));
                    destroyed--;
                }
            } else {
                // TODO: handle critical hit on internal bomb bay (cargo bay when internal bombs are loaded)
                // Ruling: calculate % of cargo space destroyed; user chooses that many bombs to destroy.
                if(aero.hasQuirk(OptionsConstants.QUIRK_POS_INTERNAL_BOMB)) {
                    // Prompt user, but just randomize bot's bombs to lose.
                    destroyed = (int) percentDestroyed * aero.getMaxIntBombPoints();
                    r = new Report(5605);
                    r.subject = aero.getId();
                    r.addDesc(aero);
                    r.choose(!aero.getOwner().isBot());
                    r.add((int) destroyed);
                    reports.add(r);
                    int bombsDestroyed = (int) (aero.getInternalBombsDamageTotal() / destroyed);
                    if (destroyed >= aero.getBombPoints()) {
                        // Actually, no prompt or randomization if all bombs will be destroyed; just do it.
                        r = new Report(5608);
                        r.subject = aero.getId();
                        r.addDesc(aero);
                        reports.add(r);
                        for (Mounted bomb: ((IBomber) aero).getBombs()) {
                            damageBomb(bomb);
                        }
                        aero.applyDamage();
                    } else if (!aero.getOwner().isBot()) {
                        // handle person choosing bombs to remove.
                        // This will require firing an event to the End Phase to display a dialog;
                        // for now just randomly dump bombs just like bots'.
                        // TODO: fire event here to display dialog in end phase.
                        for (Mounted bomb:randomlySubSelectList(((IBomber) aero).getBombs(), bombsDestroyed)) {
                            damageBomb(bomb);
                        }
                        aero.applyDamage();
                    } else {
                        // This should always use the random method.
                        for (Mounted bomb:randomlySubSelectList(((IBomber) aero).getBombs(), bombsDestroyed)) {
                            damageBomb(bomb);
                        }
                        aero.applyDamage();
                    }
                }
            }

        } else {
            r = new Report(9167);
            r.subject = aero.getId();
            r.choose(roll < 4); // cargo or transport
            reports.add(r);
        }
    }

    protected void damageBomb(Mounted bomb) {
        bomb.setShotsLeft(0);
        bomb.setHit(true);
        if (bomb.getLinked() != null && (bomb.getLinked().getUsableShotsLeft() > 0)) {
            bomb.getLinked().setHit(true);
        }
    }

    // Randomly select subset of Mounted items.
    protected ArrayList<Mounted> randomlySubSelectList(List<Mounted> list, int size) {
        ArrayList<Mounted> subset = new ArrayList<>();
        Random random_method = new Random();
        for (int i = 0; i < size; i++) {
            subset.add(list.get(random_method.nextInt(list.size())));
        }
        return subset;
    }

    /**
     * Apply a single critical hit to a vehicle.
     *
     * @param tank             the <code>Tank</code> that is being damaged. This value may
     *                         not be <code>null</code>.
     * @param loc              the <code>int</code> location of critical hit. This value may
     *                         be <code>Entity.NONE</code> for hits to <code>Tank</code>s and
     *                         for hits to a <code>Protomech</code> torso weapon.
     * @param cs               the <code>CriticalSlot</code> being damaged. This value may
     *                         not be <code>null</code>. The index of the slot should be the index
     *                         of the critical hit table.
     * @param damageCaused     the amount of damage causing this critical.
     */
    protected Vector<Report> applyTankCritical(Tank tank, int loc, CriticalSlot cs, int damageCaused) {
        Vector<Report> reports = new Vector<>();
        Report r;
        HitData hit;
        switch (cs.getIndex()) {
            case Tank.CRIT_NONE:
                // no effect
                r = new Report(6005);
                r.subject = tank.getId();
                reports.add(r);
                break;
            case Tank.CRIT_AMMO:
                // ammo explosion
                r = new Report(6610);
                r.subject = tank.getId();
                reports.add(r);
                int damage = 0;
                for (Mounted m : tank.getAmmo()) {
                    // Don't include ammo of one-shot weapons.
                    if (m.getLocation() == Entity.LOC_NONE) {
                        continue;
                    }
                    m.setHit(true);
                    int tmp = m.getHittableShotsLeft()
                            * ((AmmoType) m.getType()).getDamagePerShot()
                            * ((AmmoType) m.getType()).getRackSize();
                    m.setShotsLeft(0);
                    // non-explosive ammo can't explode
                    if (!m.getType().isExplosive(m)) {
                        continue;
                    }
                    damage += tmp;
                    r = new Report(6390);
                    r.subject = tank.getId();
                    r.add(m.getName());
                    r.add(tmp);
                    reports.add(r);
                }
                hit = new HitData(loc);
                reports.addAll(damageEntity(tank, hit, damage, true));
                break;
            case Tank.CRIT_CARGO:
                // Cargo/infantry damage
                r = new Report(6615);
                r.subject = tank.getId();
                reports.add(r);
                List<Entity> passengers = tank.getLoadedUnits();
                if (!passengers.isEmpty()) {
                    Entity target = passengers.get(Compute.randomInt(passengers.size()));
                    hit = target.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                    reports.addAll(damageEntity(target, hit, damageCaused));
                }
                break;
            case Tank.CRIT_COMMANDER:
                if (tank.hasAbility(OptionsConstants.MD_VDNI)
                        || tank.hasAbility(OptionsConstants.MD_BVDNI)) {
                    r = new Report(6191);
                    r.subject = tank.getId();
                    reports.add(r);
                    reports.addAll(damageCrew(tank, 1));
                } else {
                    if (tank.hasAbility(OptionsConstants.MD_PAIN_SHUNT)
                            && !tank.isCommanderHitPS()) {
                        r = new Report(6606);
                        r.subject = tank.getId();
                        reports.add(r);
                        tank.setCommanderHitPS(true);
                    } else if (tank.hasWorkingMisc(MiscType.F_COMMAND_CONSOLE)
                            && !tank.isUsingConsoleCommander()) {
                        r = new Report(6607);
                        r.subject = tank.getId();
                        reports.add(r);
                        tank.setUsingConsoleCommander(true);
                    } else {
                        r = new Report(6605);
                        r.subject = tank.getId();
                        reports.add(r);
                        tank.setCommanderHit(true);
                    }
                }
                // fall through here, because effects of crew stunned also
                // apply
            case Tank.CRIT_CREW_STUNNED:
                if (tank.hasAbility(OptionsConstants.MD_VDNI)
                        || tank.hasAbility(OptionsConstants.MD_BVDNI)) {
                    r = new Report(6191);
                    r.subject = tank.getId();
                    reports.add(r);
                    reports.addAll(damageCrew(tank, 1));
                } else {
                    if (tank.hasAbility(OptionsConstants.MD_PAIN_SHUNT)
                            || tank.hasAbility(OptionsConstants.MD_DERMAL_ARMOR)) {
                        r = new Report(6186);
                    } else {
                        tank.stunCrew();
                        r = new Report(6185);
                        r.add(tank.getStunnedTurns() - 1);
                    }
                    r.subject = tank.getId();
                    reports.add(r);
                }
                break;
            case Tank.CRIT_DRIVER:
                if (tank.hasAbility(OptionsConstants.MD_VDNI)
                        || tank.hasAbility(OptionsConstants.MD_BVDNI)) {
                    r = new Report(6191);
                    r.subject = tank.getId();
                    reports.add(r);
                    reports.addAll(damageCrew(tank, 1));
                } else {
                    if (tank.hasAbility(OptionsConstants.MD_PAIN_SHUNT)
                            && !tank.isDriverHitPS()) {
                        r = new Report(6601);
                        r.subject = tank.getId();
                        reports.add(r);
                        tank.setDriverHitPS(true);
                    } else {
                        r = new Report(6600);
                        r.subject = tank.getId();
                        reports.add(r);
                        tank.setDriverHit(true);
                    }
                }
                break;
            case Tank.CRIT_CREW_KILLED:
                if (tank.hasAbility(OptionsConstants.MD_VDNI)
                        || tank.hasAbility(OptionsConstants.MD_BVDNI)) {
                    r = new Report(6191);
                    r.subject = tank.getId();
                    reports.add(r);
                    reports.addAll(damageCrew(tank, 1));
                } else {
                    if (tank.hasAbility(OptionsConstants.MD_PAIN_SHUNT) && !tank.isCrewHitPS()) {
                        r = new Report(6191);
                        r.subject = tank.getId();
                        reports.add(r);
                        tank.setCrewHitPS(true);
                    } else {
                        r = new Report(6190);
                        r.subject = tank.getId();
                        reports.add(r);
                        tank.getCrew().setDoomed(true);
                        if (tank.isAirborneVTOLorWIGE()) {
                            reports.addAll(entityActionManager.crashVTOLorWiGE(tank, this));
                        }
                    }
                }
                break;
            case Tank.CRIT_ENGINE:
                r = new Report(6210);
                r.subject = tank.getId();
                reports.add(r);
                tank.engineHit();
                tank.engineHitsThisPhase++;
                boolean engineExploded = checkEngineExplosion(tank, reports, 1);
                if (engineExploded) {
                    reports.addAll(entityActionManager.destroyEntity(tank, "engine destruction", true, true, this));
                    tank.setSelfDestructing(false);
                    tank.setSelfDestructInitiated(false);
                }
                if (tank.isAirborneVTOLorWIGE()
                        && !(tank.isDestroyed() || tank.isDoomed())) {
                    tank.immobilize();
                    reports.addAll(entityActionManager.forceLandVTOLorWiGE(tank, this));
                }
                break;
            case Tank.CRIT_FUEL_TANK:
                r = new Report(6215);
                r.subject = tank.getId();
                reports.add(r);
                reports.addAll(entityActionManager.destroyEntity(tank, "fuel explosion", false, false, this));
                break;
            case Tank.CRIT_SENSOR:
                r = new Report(6620);
                r.subject = tank.getId();
                reports.add(r);
                tank.setSensorHits(tank.getSensorHits() + 1);
                break;
            case Tank.CRIT_STABILIZER:
                r = new Report(6625);
                r.subject = tank.getId();
                reports.add(r);
                tank.setStabiliserHit(loc);
                break;
            case Tank.CRIT_TURRET_DESTROYED:
                r = new Report(6630);
                r.subject = tank.getId();
                reports.add(r);
                tank.destroyLocation(tank.getLocTurret());
                reports.addAll(entityActionManager.destroyEntity(tank, "turret blown off", true, true, this));
                break;
            case Tank.CRIT_TURRET_JAM:
                if (tank.isTurretEverJammed(loc)) {
                    r = new Report(6640);
                    r.subject = tank.getId();
                    reports.add(r);
                    tank.lockTurret(loc);
                    break;
                }
                r = new Report(6635);
                r.subject = tank.getId();
                reports.add(r);
                tank.jamTurret(loc);
                break;
            case Tank.CRIT_TURRET_LOCK:
                r = new Report(6640);
                r.subject = tank.getId();
                reports.add(r);
                tank.lockTurret(loc);
                break;
            case Tank.CRIT_WEAPON_DESTROYED: {
                r = new Report(6305);
                r.subject = tank.getId();
                List<Mounted> weapons = new ArrayList<>();
                for (Mounted weapon : tank.getWeaponList()) {
                    if ((weapon.getLocation() == loc) && !weapon.isHit() && !weapon.isDestroyed()) {
                        weapons.add(weapon);
                    }
                }
                // sort weapons by BV
                weapons.sort(new WeaponComparatorBV());
                int roll = Compute.d6();
                Mounted weapon;
                if (roll < 4) {
                    // defender should choose, we'll just use the lowest BV
                    // weapon
                    weapon = weapons.get(weapons.size() - 1);
                } else {
                    // attacker chooses, we'll use the highest BV weapon
                    weapon = weapons.get(0);
                }
                r.add(weapon.getName());
                reports.add(r);
                // explosive weapons e.g. gauss now explode
                if (weapon.getType().isExplosive(weapon) && !weapon.isHit()
                        && !weapon.isDestroyed()) {
                    reports.addAll(entityActionManager.explodeEquipment(tank, loc, weapon, this));
                }
                weapon.setHit(true);
                //Taharqa: We should also damage the critical slot, or
                //MM and MHQ won't remember that this weapon is damaged on the MUL
                //file
                for (int i = 0; i < tank.getNumberOfCriticals(loc); i++) {
                    CriticalSlot slot1 = tank.getCritical(loc, i);
                    if ((slot1 == null) || (slot1.getType() == CriticalSlot.TYPE_SYSTEM)) {
                        continue;
                    }
                    Mounted mounted = slot1.getMount();
                    if (mounted.equals(weapon)) {
                        tank.hitAllCriticals(loc, i);
                        break;
                    }
                }
                break;
            }
            case Tank.CRIT_WEAPON_JAM: {
                r = new Report(6645);
                r.subject = tank.getId();
                ArrayList<Mounted> weapons = new ArrayList<>();
                for (Mounted weapon : tank.getWeaponList()) {
                    if ((weapon.getLocation() == loc) && !weapon.isJammed()
                            && !weapon.jammedThisPhase() && !weapon.isHit()
                            && !weapon.isDestroyed()) {
                        weapons.add(weapon);
                    }
                }

                if (!weapons.isEmpty()) {
                    Mounted weapon = weapons.get(Compute.randomInt(weapons.size()));
                    weapon.setJammed(true);
                    tank.addJammedWeapon(weapon);
                    r.add(weapon.getName());
                    reports.add(r);
                }
                break;
            }
            case VTOL.CRIT_PILOT:
                r = new Report(6650);
                r.subject = tank.getId();
                reports.add(r);
                tank.setDriverHit(true);
                PilotingRollData psr = tank.getBasePilotingRoll();
                psr.addModifier(0, "pilot injury");
                if (!utilityManager.doSkillCheckInPlace(tank, psr, this)) {
                    r = new Report(6675);
                    r.subject = tank.getId();
                    r.addDesc(tank);
                    reports.add(r);
                    boolean crash = true;
                    if (tank.canGoDown()) {
                        tank.setElevation(tank.getElevation() - 1);
                        crash = !tank.canGoDown();
                    }
                    if (crash) {
                        reports.addAll(entityActionManager.crashVTOLorWiGE(tank, this));
                    }
                }
                break;
            case VTOL.CRIT_COPILOT:
                r = new Report(6655);
                r.subject = tank.getId();
                reports.add(r);
                tank.setCommanderHit(true);
                break;
            case VTOL.CRIT_ROTOR_DAMAGE: {
                // Only resolve rotor crits if the rotor was actually still
                // there.
                if (!(tank.isLocationBad(VTOL.LOC_ROTOR) || tank.isLocationDoomed(VTOL.LOC_ROTOR))) {
                    r = new Report(6660);
                    r.subject = tank.getId();
                    reports.add(r);
                    tank.setMotiveDamage(tank.getMotiveDamage() + 1);
                    if (tank.getMotiveDamage() >= tank.getOriginalWalkMP()) {
                        tank.immobilize();
                        if (tank.isAirborneVTOLorWIGE()
                                // Don't bother with forcing a landing if
                                // we're already otherwise destroyed.
                                && !(tank.isDestroyed() || tank.isDoomed())) {
                            reports.addAll(entityActionManager.forceLandVTOLorWiGE(tank, this));
                        }
                    }
                }
                break;
            }
            case VTOL.CRIT_ROTOR_DESTROYED:
                // Only resolve rotor crits if the rotor was actually still
                // there. Note that despite the name this critical hit does
                // not in itself physically destroy the rotor *location*
                // (which would simply kill the VTOL).
                if (!(tank.isLocationBad(VTOL.LOC_ROTOR) || tank.isLocationDoomed(VTOL.LOC_ROTOR))) {
                    r = new Report(6670);
                    r.subject = tank.getId();
                    reports.add(r);
                    tank.immobilize();
                    reports.addAll(entityActionManager.crashVTOLorWiGE(tank, true, this));
                }
                break;
            case VTOL.CRIT_FLIGHT_STABILIZER:
                // Only resolve rotor crits if the rotor was actually still
                // there.
                if (!(tank.isLocationBad(VTOL.LOC_ROTOR) || tank.isLocationDoomed(VTOL.LOC_ROTOR))) {
                    r = new Report(6665);
                    r.subject = tank.getId();
                    reports.add(r);
                    tank.setStabiliserHit(VTOL.LOC_ROTOR);
                }
                break;
        }
        return reports;
    }

    /**
     * Rolls and resolves critical hits with a die roll modifier.
     */

    public Vector<Report> criticalEntity(Entity en, int loc, boolean isRear, int critMod, int damage) {
        return criticalEntity(en, loc, isRear, critMod, true, false, damage);
    }

    /**
     * Rolls and resolves critical hits with a die roll modifier.
     */

    public Vector<Report> criticalEntity(Entity en, int loc, boolean isRear, int critMod, int damage,
                                         DamageType damageType) {
        return criticalEntity(en, loc, isRear, critMod, true, false, damage, damageType);
    }

    /**
     * Rolls one critical hit
     */
    public Vector<Report> oneCriticalEntity(Entity en, int loc, boolean isRear, int damage) {
        return criticalEntity(en, loc, isRear, 0, false, false, damage);
    }

    /**
     * rolls and resolves one tank critical hit
     *
     * @param t       the <code>Tank</code> to be critted
     * @param loc     the <code>int</code> location of the Tank to be critted
     * @param critMod the <code>int</code> modifier to the crit roll
     * @return a <code>Vector<Report></code> containing the phase reports
     */
    protected Vector<Report> criticalTank(Tank t, int loc, int critMod, int damage, boolean damagedByFire) {
        Vector<Report> vDesc = new Vector<>();
        Report r;

        // roll the critical
        r = new Report(6305);
        r.subject = t.getId();
        r.indent(3);
        r.add(t.getLocationAbbr(loc));
        r.newlines = 0;
        vDesc.add(r);
        int roll = Compute.d6(2);
        r = new Report(6310);
        r.subject = t.getId();
        String rollString = "";
        if (critMod != 0) {
            rollString = "(" + roll;
            if (critMod > 0) {
                rollString += "+";
            }
            rollString += critMod + ") = ";
            roll += critMod;
        }
        rollString += roll;
        r.add(rollString);
        r.newlines = 0;
        vDesc.add(r);

        // now look up on vehicle crits table
        int critType = t.getCriticalEffect(roll, loc, damagedByFire);
        if ((critType == Tank.CRIT_NONE)
                && game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_VEHICLES_THRESHOLD)
                && !((t instanceof VTOL) || (t instanceof GunEmplacement))
                && !t.getOverThresh()) {
            r = new Report(6006);
            r.subject = t.getId();
            r.newlines = 0;
            vDesc.add(r);
        }
        vDesc.addAll(applyCriticalHit(t, loc, new CriticalSlot(0, critType),
                true, damage, false));
        if ((critType != Tank.CRIT_NONE) && t.hasEngine() && !t.getEngine().isFusion()
                && t.hasQuirk(OptionsConstants.QUIRK_NEG_FRAGILE_FUEL) && (Compute.d6(2) > 9)) {
            // BOOM!!
            vDesc.addAll(applyCriticalHit(t, loc, new CriticalSlot(0,
                    Tank.CRIT_FUEL_TANK), true, damage, false));
        }
        return vDesc;
    }

    /**
     * Checks for aero criticals
     *
     * @param vDesc         - {@link Report} <code>Vector</code>
     * @param a             - the entity being critted
     * @param hit           - the hitdata for the attack
     * @param damage_orig   - the original damage of the attack
     * @param critThresh    - did the attack go over the damage threshold
     * @param critSI        - did the attack damage SI
     * @param ammoExplosion - was the damage from an ammo explosion
     * @param nukeS2S       - was this a ship 2 ship nuke attack
     */
    protected void checkAeroCrits(Vector<Report> vDesc, Aero a, HitData hit,
                                int damage_orig, boolean critThresh, boolean critSI,
                                boolean ammoExplosion, boolean nukeS2S) {

        Report r;

        boolean isCapital = hit.isCapital();
        // get any capital missile critical mods
        int capitalMissile = hit.getCapMisCritMod();

        // check for nuclear critical
        if (nukeS2S) {
            // add a control roll
            PilotingRollData nukePSR = new PilotingRollData(a.getId(), 4,
                    "Nuclear attack", false);
            game.addControlRoll(nukePSR);

            Report.addNewline(vDesc);
            // need some kind of report
            Roll diceRoll = Compute.rollD6(2);
            r = new Report(9145);
            r.subject = a.getId();
            r.indent(3);
            r.add(capitalMissile);
            r.add(diceRoll);
            vDesc.add(r);

            if (diceRoll.getIntValue() >= capitalMissile) {
                // Allow a reroll with edge
                if (a.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_AERO_NUKE_CRIT)
                        && a.getCrew().hasEdgeRemaining()) {
                    a.getCrew().decreaseEdge();
                    r = new Report(9148);
                    r.subject = a.getId();
                    r.indent(3);
                    r.add(a.getCrew().getOptions().intOption(OptionsConstants.EDGE));
                    vDesc.add(r);
                    // Reroll
                    Roll diceRoll2 = Compute.rollD6(2);
                    // and report the new results
                    r = new Report(9149);
                    r.subject = a.getId();
                    r.indent(3);
                    r.add(capitalMissile);
                    r.add(diceRoll2);
                    r.choose(diceRoll2.getIntValue() >= capitalMissile);
                    vDesc.add(r);

                    if (diceRoll2.getIntValue() < capitalMissile) {
                        // We might be vaporized by the damage itself, but no additional effect
                        return;
                    }
                }
                a.setSI(a.getSI() - (damage_orig * 10));
                a.damageThisPhase += (damage_orig * 10);
                r = new Report(9146);
                r.subject = a.getId();
                r.add((damage_orig * 10));
                r.indent(4);
                r.add(Math.max(a.getSI(), 0));
                vDesc.addElement(r);
                if (a.getSI() <= 0) {
                    //No auto-ejection chance here. Nuke would vaporize the pilot.
                    vDesc.addAll(entityActionManager.destroyEntity(a, "Structural Integrity Collapse", this));
                    a.setSI(0);
                    if (hit.getAttackerId() != Entity.NONE) {
                        creditKill(a, game.getEntity(hit.getAttackerId()));
                    }
                } else if (!critSI) {
                    critSI = true;
                }
            } else {
                r = new Report(9147);
                r.subject = a.getId();
                r.indent(4);
                vDesc.addElement(r);
            }
        }

        // apply crits
        if (hit.rolledBoxCars()) {
            if (hit.isFirstHit()) {
                // Allow edge use to ignore the critical roll
                if (a.getCrew().getOptions().booleanOption(OptionsConstants.EDGE_WHEN_AERO_LUCKY_CRIT)
                        && a.getCrew().hasEdgeRemaining()) {
                    a.getCrew().decreaseEdge();
                    r = new Report(9103);
                    r.subject = a.getId();
                    r.indent(3);
                    r.add(a.getCrew().getOptions().intOption(OptionsConstants.EDGE));
                    vDesc.addElement(r);
                    // Skip the critical roll
                    return;
                }
                vDesc.addAll(criticalAero(a, hit.getLocation(), hit.glancingMod(), "12 to hit",
                        8, damage_orig, isCapital));
            } else { // Let the user know why the lucky crit doesn't apply
                r = new Report(9102);
                r.subject = a.getId();
                r.indent(3);
                vDesc.addElement(r);
            }
        }
        // ammo explosions shouldn't affect threshold because they
        // go right to SI
        if (critThresh && !ammoExplosion) {
            vDesc.addAll(criticalAero(a, hit.getLocation(), hit.glancingMod(),
                    "Damage threshold exceeded", 8, damage_orig, isCapital));
        }
        if (critSI && !ammoExplosion) {
            vDesc.addAll(criticalAero(a, hit.getLocation(), hit.glancingMod(),
                    "SI damaged", 8, damage_orig, isCapital));
        }
        if ((capitalMissile > 0) && !nukeS2S) {
            vDesc.addAll(criticalAero(a, hit.getLocation(), hit.glancingMod(),
                    "Capital Missile", capitalMissile, damage_orig, isCapital));
        }
    }

    protected Vector<Report> criticalAero(Aero a, int loc, int critMod,
                                        String reason, int target, int damage, boolean isCapital) {
        Vector<Report> vDesc = new Vector<>();
        Report r;

        //Telemissiles don't take critical hits
        if (a instanceof TeleMissile) {
            return vDesc;
        }

        // roll the critical
        r = new Report(9100);
        r.subject = a.getId();
        r.add(reason);
        r.indent(3);
        r.newlines = 0;
        vDesc.add(r);
        int roll = Compute.d6(2);
        r = new Report(9101);
        r.subject = a.getId();
        r.add(target);
        String rollString = "";
        if (critMod != 0) {
            rollString = "(" + roll;
            if (critMod > 0) {
                rollString += "+";
            }
            rollString += critMod + ") = ";
            roll += critMod;
        }
        rollString += roll;
        r.add(rollString);
        r.newlines = 0;
        vDesc.add(r);

        // now look up on vehicle crits table
        int critType = a.getCriticalEffect(roll, target);
        vDesc.addAll(applyCriticalHit(a, loc, new CriticalSlot(0, critType),
                true, damage, isCapital));
        return vDesc;
    }

    /**
     * Rolls and resolves critical hits on mechs or vehicles. if rollNumber is
     * false, a single hit is applied - needed for MaxTech Heat Scale rule.
     */
    public Vector<Report> criticalEntity(Entity en, int loc, boolean isRear,
                                         int critMod, boolean rollNumber, boolean isCapital, int damage) {
        return criticalEntity(en, loc, isRear, critMod, rollNumber, isCapital,
                damage, DamageType.NONE);
    }

    /**
     * Rolls and resolves critical hits on mechs or vehicles. if rollNumber is
     * false, a single hit is applied - needed for MaxTech Heat Scale rule.
     */
    public Vector<Report> criticalEntity(Entity en, int loc, boolean isRear,
                                         int critMod, boolean rollNumber, boolean isCapital, int damage,
                                         DamageType damageType) {

        if (en.hasQuirk("poor_work")) {
            critMod += 1;
        }
        if (en.hasQuirk(OptionsConstants.QUIRK_NEG_PROTOTYPE)) {
            critMod += 2;
        }

        // Apply modifiers for Anti-penetrative ablation armor
        if ((en.getArmor(loc, isRear) > 0)
                && (en.getArmorType(loc) == EquipmentType.T_ARMOR_ANTI_PENETRATIVE_ABLATION)) {
            critMod -= 2;
        }

        if (en instanceof Tank) {
            return criticalTank((Tank) en, loc, critMod, damage, damageType.equals(DamageType.INFERNO));
        }

        if (en instanceof Aero) {
            return criticalAero((Aero) en, loc, critMod, "unknown", 8, damage,
                    isCapital);
        }
        CriticalSlot slot;
        Vector<Report> vDesc = new Vector<>();
        Report r;
        Coords coords = en.getPosition();
        Hex hex = null;
        int hits;
        if (rollNumber) {
            if (null != coords) {
                hex = game.getBoard().getHex(coords);
            }
            r = new Report(6305);
            r.subject = en.getId();
            r.indent(3);
            r.add(en.getLocationAbbr(loc));
            r.newlines = 0;
            vDesc.addElement(r);
            hits = 0;
            int roll = Compute.d6(2);
            r = new Report(6310);
            r.subject = en.getId();
            String rollString = "";
            // industrials get a +2 bonus on the roll
            if ((en instanceof Mech) && ((Mech) en).isIndustrial()) {
                critMod += 2;
            }
            // reinforced structure gets a -1 mod
            if ((en instanceof Mech) && ((Mech) en).hasReinforcedStructure()) {
                critMod -= 1;
            }
            if (critMod != 0) {
                rollString = "(" + roll;
                if (critMod > 0) {
                    rollString += "+";
                }
                rollString += critMod + ") = ";
                roll += critMod;
            }
            rollString += roll;
            r.add(rollString);
            r.newlines = 0;
            vDesc.addElement(r);
            boolean advancedCrit = game.getOptions().booleanOption(
                    OptionsConstants.ADVCOMBAT_TACOPS_CRIT_ROLL);
            if ((!advancedCrit && (roll <= 7)) || (advancedCrit && (roll <= 8))) {
                // no effect
                r = new Report(6005);
                r.subject = en.getId();
                vDesc.addElement(r);
                return vDesc;
            } else if ((!advancedCrit && (roll >= 8) && (roll <= 9))
                    || (advancedCrit && (roll >= 9) && (roll <= 10))) {
                hits = 1;
                r = new Report(6315);
                r.subject = en.getId();
                vDesc.addElement(r);
            } else if ((!advancedCrit && (roll >= 10) && (roll <= 11))
                    || (advancedCrit && (roll >= 11) && (roll <= 12))) {
                hits = 2;
                r = new Report(6320);
                r.subject = en.getId();
                vDesc.addElement(r);
            } else if (advancedCrit && (roll >= 13) && (roll <= 14)) {
                hits = 3;
                r = new Report(6325);
                r.subject = en.getId();
                vDesc.addElement(r);
            } else if ((!advancedCrit && (roll >= 12)) || (advancedCrit && (roll >= 15))) {
                if (en instanceof Protomech) {
                    hits = 3;
                    r = new Report(6325);
                    r.subject = en.getId();
                    vDesc.addElement(r);
                } else if (en.locationIsLeg(loc)) {
                    CriticalSlot cs = en.getCritical(loc, 0);
                    if ((cs != null) && cs.isArmored()) {
                        r = new Report(6700);
                        r.subject = en.getId();
                        r.add(en.getLocationName(loc));
                        r.newlines = 0;
                        vDesc.addElement(r);
                        cs.setArmored(false);
                        return vDesc;
                    }
                    // limb blown off
                    r = new Report(6120);
                    r.subject = en.getId();
                    r.add(en.getLocationName(loc));
                    vDesc.addElement(r);
                    if (en.getInternal(loc) > 0) {
                        en.destroyLocation(loc, true);
                    }
                    if (null != hex) {
                        if (!hex.containsTerrain(Terrains.LEGS)) {
                            hex.addTerrain(new Terrain(Terrains.LEGS, 1));
                        } else {
                            hex.addTerrain(new Terrain(Terrains.LEGS, hex.terrainLevel(Terrains.LEGS) + 1));
                        }
                    }
                    communicationManager.sendChangedHex(en.getPosition(), this);
                    return vDesc;
                } else if ((loc == Mech.LOC_RARM) || (loc == Mech.LOC_LARM)) {
                    CriticalSlot cs = en.getCritical(loc, 0);
                    if ((cs != null) && cs.isArmored()) {
                        r = new Report(6700);
                        r.subject = en.getId();
                        r.add(en.getLocationName(loc));
                        r.newlines = 0;
                        vDesc.addElement(r);
                        cs.setArmored(false);
                        return vDesc;
                    }

                    // limb blown off
                    r = new Report(6120);
                    r.subject = en.getId();
                    r.add(en.getLocationName(loc));
                    vDesc.addElement(r);
                    en.destroyLocation(loc, true);
                    if (null != hex) {
                        if (!hex.containsTerrain(Terrains.ARMS)) {
                            hex.addTerrain(new Terrain(Terrains.ARMS, 1));
                        } else {
                            hex.addTerrain(new Terrain(Terrains.ARMS, hex.terrainLevel(Terrains.ARMS) + 1));
                        }
                    }
                    communicationManager.sendChangedHex(en.getPosition(), this);
                    return vDesc;
                } else if (loc == Mech.LOC_HEAD) {
                    // head blown off
                    r = new Report(6330);
                    r.subject = en.getId();
                    r.add(en.getLocationName(loc));
                    vDesc.addElement(r);
                    en.destroyLocation(loc, true);
                    if (((Mech) en).getCockpitType() != Mech.COCKPIT_TORSO_MOUNTED) {
                        // Don't kill a pilot multiple times.
                        if (Crew.DEATH > en.getCrew().getHits()) {
                            en.getCrew().setDoomed(true);
                            Report.addNewline(vDesc);
                            vDesc.addAll(entityActionManager.destroyEntity(en, "pilot death", true, this));
                        }
                    }
                    return vDesc;
                } else {
                    // torso hit
                    hits = 3;
                    // industrials get 4 crits on a modified result of 14
                    if ((roll >= 14) && (en instanceof Mech) && ((Mech) en).isIndustrial()) {
                        hits = 4;
                    }
                    r = new Report(6325);
                    r.subject = en.getId();
                    vDesc.addElement(r);
                }
            }
            if (damageType.equals(DamageType.ANTI_TSM) && (en instanceof Mech)
                    && en.antiTSMVulnerable()) {
                r = new Report(6430);
                r.subject = en.getId();
                r.indent(2);
                r.addDesc(en);
                vDesc.addElement(r);
                hits++;
            }
        } else {
            hits = 1;
        }

        // Check if there is the potential for a reactive armor crit
        // Because reactive armor isn't hittable, the transfer check doesn't
        // consider it
        boolean possibleReactiveCrit = (en.getArmor(loc) > 0)
                && (en.getArmorType(loc) == EquipmentType.T_ARMOR_REACTIVE);
        boolean locContainsReactiveArmor = false;
        for (int i = 0; (i < en.getNumberOfCriticals(loc)) && possibleReactiveCrit; i++) {
            CriticalSlot crit = en.getCritical(loc, i);
            if ((crit != null) && (crit.getType() == CriticalSlot.TYPE_EQUIPMENT)
                    && (crit.getMount() != null)
                    && crit.getMount().getType().hasFlag(MiscType.F_REACTIVE)) {
                locContainsReactiveArmor = true;
                break;
            }
        }
        possibleReactiveCrit &= locContainsReactiveArmor;

        // transfer criticals, if needed
        while ((en.canTransferCriticals(loc) && !possibleReactiveCrit)
                && (en.getTransferLocation(loc) != Entity.LOC_DESTROYED)
                && (en.getTransferLocation(loc) != Entity.LOC_NONE)) {
            loc = en.getTransferLocation(loc);
            r = new Report(6335);
            r.subject = en.getId();
            r.indent(3);
            r.add(en.getLocationAbbr(loc));
            vDesc.addElement(r);
        }

        // Roll critical hits in this location.
        while (hits > 0) {

            // Have we hit all available slots in this location?
            if (en.getHittableCriticals(loc) <= 0) {
                r = new Report(6340);
                r.subject = en.getId();
                r.indent(3);
                vDesc.addElement(r);
                break;
            }

            // Randomly pick a slot to be hit.
            int slotIndex = Compute.randomInt(en.getNumberOfCriticals(loc));
            slot = en.getCritical(loc, slotIndex);

            // There are certain special cases, like reactive armor
            // some crits aren't normally hittable, except in certain cases
            boolean reactiveArmorCrit = false;
            if ((slot != null) && (slot.getType() == CriticalSlot.TYPE_EQUIPMENT)
                    && (slot.getMount() != null)) {
                Mounted eq = slot.getMount();
                if (eq.getType().hasFlag(MiscType.F_REACTIVE) && (en.getArmor(loc) > 0)) {
                    reactiveArmorCrit = true;
                }
            }

            // Ignore empty or unhitable slots (this
            // includes all previously hit slots).
            if ((slot != null) && (slot.isHittable() || reactiveArmorCrit)) {

                if (slot.isArmored()) {
                    r = new Report(6710);
                    r.subject = en.getId();
                    if (slot.getType() == CriticalSlot.TYPE_SYSTEM) {
                        // Pretty sure that only 'mechs have system crits,
                        // but just in case....
                        if (en instanceof Mech) {
                            r.add(((Mech) en).getSystemName(slot.getIndex()));
                        }
                    } else {
                        // Shouldn't be null, but we'll be careful...
                        if (slot.getMount() != null) {
                            r.add(slot.getMount().getName());
                        }
                    }
                    vDesc.addElement(r);
                    slot.setArmored(false);
                    hits--;
                    continue;
                }
                // if explosive use edge
                if ((en instanceof Mech)
                        && (en.getCrew().hasEdgeRemaining() && en.getCrew().getOptions()
                        .booleanOption(OptionsConstants.EDGE_WHEN_EXPLOSION))
                        && (slot.getType() == CriticalSlot.TYPE_EQUIPMENT)
                        && slot.getMount().getType().isExplosive(slot.getMount())) {
                    en.getCrew().decreaseEdge();
                    r = new Report(6530);
                    r.subject = en.getId();
                    r.indent(3);
                    r.add(en.getCrew().getOptions().intOption(OptionsConstants.EDGE));
                    vDesc.addElement(r);
                    continue;
                }

                // check for reactive armor exploding
                if (reactiveArmorCrit) {
                    Mounted mount = slot.getMount();
                    if ((mount != null) && mount.getType().hasFlag(MiscType.F_REACTIVE)) {
                        Roll diceRoll = Compute.rollD6(2);
                        r = new Report(6082);
                        r.subject = en.getId();
                        r.indent(3);
                        r.add(diceRoll);
                        vDesc.addElement(r);

                        // big budda boom
                        if (diceRoll.getIntValue() == 2) {
                            r = new Report(6083);
                            r.subject = en.getId();
                            r.indent(4);
                            vDesc.addElement(r);
                            Vector<Report> newReports = new Vector<>(damageEntity(en,
                                    new HitData(loc), en.getArmor(loc)));
                            if (en.hasRearArmor(loc)) {
                                newReports.addAll(damageEntity(en, new HitData(loc, true),
                                        en.getArmor(loc, true)));
                            }
                            newReports.addAll(damageEntity(en, new HitData(loc), 1));
                            for (Report rep : newReports) {
                                rep.indent(4);
                            }
                            vDesc.addAll(newReports);
                        } else {
                            // If only hittable crits are reactive,
                            // this crit is absorbed
                            boolean allHittableCritsReactive = true;
                            for (int i = 0; i < en.getNumberOfCriticals(loc); i++) {
                                CriticalSlot crit = en.getCritical(loc, i);
                                if (crit.isHittable()) {
                                    allHittableCritsReactive = false;
                                    break;
                                }
                                // We must have reactive crits to get to this
                                // point, so if nothing else is hittable, we
                                // must only have reactive crits
                            }
                            if (allHittableCritsReactive) {
                                hits--;
                            }
                            continue;
                        }
                    }
                }
                vDesc.addAll(applyCriticalHit(en, loc, slot, true, damage, isCapital));
                hits--;
            }
        } // Hit another slot in this location.

        return vDesc;
    }

    /**
     * Checks for location breach and returns phase logging.
     * <p>
     *
     * @param entity the <code>Entity</code> that needs to be checked.
     * @param loc    the <code>int</code> location on the entity that needs to be
     *               checked for a breach.
     * @param hex    the <code>Hex</code> the entity occupies when checking. This
     *               value will be <code>null</code> if the check is the result of
     *               an attack, and non-null if it occurs during movement.
     */
    protected Vector<Report> breachCheck(Entity entity, int loc, Hex hex) {
        return breachCheck(entity, loc, hex, false);
    }

    /**
     * Checks for location breach and returns phase logging.
     *
     * @param entity     the <code>Entity</code> that needs to be checked.
     * @param loc        the <code>int</code> location on the entity that needs to be
     *                   checked for a breach.
     * @param hex        the <code>Hex</code> the entity occupies when checking. This
     *                   value will be <code>null</code> if the check is the result of
     *                   an attack, and non-null if it occurs during movement.
     * @param underWater Is the breach check a result of an underwater attack?
     */
    protected Vector<Report> breachCheck(Entity entity, int loc, Hex hex, boolean underWater) {
        Vector<Report> vDesc = new Vector<>();
        Report r;

        // Infantry do not suffer breaches, nor do Telemissiles
        // VTOLs can't operate in vacuum or underwater, so no breaches
        if (entity instanceof Infantry || entity instanceof TeleMissile || entity instanceof VTOL) {
            return vDesc;
        }

        boolean dumping = false;
        for (Mounted m : entity.getAmmo()) {
            if (m.isDumping()) {
                // dumping ammo underwater is very stupid thing to do
                dumping = true;
                break;
            }
        }
        // This handles both water and vacuum breaches.
        // Also need to account for hull breaches on surface naval vessels which
        // are technically not "wet"
        if ((entity.getLocationStatus(loc) > ILocationExposureStatus.NORMAL)
                || (entity.isSurfaceNaval() && (loc != ((Tank) entity).getLocTurret()))) {
            // Does the location have armor (check rear armor on Mek)
            // and is the check due to damage?
            int breachroll = 0;
            // set the target roll for the breach
            int target = 10;
            // if this is a vacuum check and we are in trace atmosphere then
            // adjust target
            if ((entity.getLocationStatus(loc) == ILocationExposureStatus.VACUUM)
                    && (game.getPlanetaryConditions().getAtmosphere() == PlanetaryConditions.ATMO_TRACE)) {
                target = 12;
            }
            // if this is a surface naval vessel and the attack is not from
            // underwater
            // then the breach should only occur on a roll of 12
            if (entity.isSurfaceNaval() && !underWater) {
                target = 12;
            }
            if ((entity.getArmor(loc) > 0)
                    && (!(entity instanceof Mech) || entity.getArmor(loc, true) > 0) && (null == hex)) {
                // functional HarJel prevents breach
                if (entity.hasHarJelIn(loc)) {
                    r = new Report(6342);
                    r.subject = entity.getId();
                    r.indent(3);
                    vDesc.addElement(r);
                    return vDesc;
                }
                if ((entity instanceof Mech) && (((Mech) entity).hasHarJelIIIn(loc)
                        || ((Mech) entity).hasHarJelIIIIn(loc))) {
                    r = new Report(6343);
                    r.subject = entity.getId();
                    r.indent(3);
                    vDesc.addElement(r);
                    target -= 2;
                }
                // Impact-resistant armor easier to breach
                if ((entity.getArmorType(loc) == EquipmentType.T_ARMOR_IMPACT_RESISTANT)) {
                    r = new Report(6344);
                    r.subject = entity.getId();
                    r.indent(3);
                    vDesc.addElement(r);
                    target += 1;
                }
                Roll diceRoll = Compute.rollD6(2);
                breachroll = diceRoll.getIntValue();
                r = new Report(6345);
                r.subject = entity.getId();
                r.indent(3);
                r.add(entity.getLocationAbbr(loc));
                r.add(diceRoll);
                r.newlines = 0;

                if (breachroll >= target) {
                    r.choose(false);
                } else {
                    r.choose(true);
                }
                vDesc.addElement(r);
            }
            // Breach by damage or lack of armor.
            if ((breachroll >= target) || !(entity.getArmor(loc) > 0)
                    || (dumping && (!(entity instanceof Mech)
                    || (loc == Mech.LOC_CT) || (loc == Mech.LOC_RT) || (loc == Mech.LOC_LT)))
                    || !(!(entity instanceof Mech) || entity.getArmor(loc, true) > 0)) {
                // Functional HarJel prevents breach as long as armor remains
                // (and, presumably, as long as you don't open your chassis on
                // purpose, say to dump ammo...).
                if ((entity.hasHarJelIn(loc)) && (entity.getArmor(loc) > 0)
                        && (!(entity instanceof Mech) || entity.getArmor(loc, true) > 0) && !dumping) {
                    r = new Report(6342);
                    r.subject = entity.getId();
                    r.indent(3);
                    vDesc.addElement(r);
                    return vDesc;
                }
                vDesc.addAll(breachLocation(entity, loc, hex, false));
            }
        }
        return vDesc;
    }

    /**
     * Marks all equipment in a location on an entity as useless.
     *
     * @param entity the <code>Entity</code> that needs to be checked.
     * @param loc    the <code>int</code> location on the entity that needs to be
     *               checked for a breach.
     * @param hex    the <code>Hex</code> the entity occupies when checking. This
     *               value will be <code>null</code> if the check is the result of
     *               an attack, and non-null if it occurs during movement.
     * @param harJel a <code>boolean</code> value indicating if the uselessness is
     *               the cause of a critically hit HarJel system
     */
    protected Vector<Report> breachLocation(Entity entity, int loc, Hex hex, boolean harJel) {
        Vector<Report> vDesc = new Vector<>();
        Report r;

        if ((entity.getInternal(loc) < 0)
                || (entity.getLocationStatus(loc) < ILocationExposureStatus.NORMAL)) {
            // already destroyed or breached? don't bother
            return vDesc;
        }

        r = new Report(6350);
        if (harJel) {
            r.messageId = 6351;
        }
        r.subject = entity.getId();
        r.add(entity.getShortName());
        r.add(entity.getLocationAbbr(loc));
        vDesc.addElement(r);

        if (entity instanceof Tank) {
            vDesc.addAll(entityActionManager.destroyEntity(entity, "hull breach", true, true, this));
            return vDesc;
        }
        if (entity instanceof Mech) {
            Mech mech = (Mech) entity;
            // equipment and crits will be marked in applyDamage?

            // equipment marked missing
            for (Mounted mounted : entity.getEquipment()) {
                if (mounted.getLocation() == loc) {
                    mounted.setBreached(true);
                }
            }
            // all critical slots set as useless
            for (int i = 0; i < entity.getNumberOfCriticals(loc); i++) {
                final CriticalSlot cs = entity.getCritical(loc, i);
                if (cs != null) {
                    // for every undamaged actuator destroyed by breaching,
                    // we make a PSR (see bug 1040858)
                    if (entity.locationIsLeg(loc) && entity.canFall(true)) {
                        if (cs.isHittable()) {
                            switch (cs.getIndex()) {
                                case Mech.ACTUATOR_UPPER_LEG:
                                case Mech.ACTUATOR_LOWER_LEG:
                                case Mech.ACTUATOR_FOOT:
                                    // leg/foot actuator piloting roll
                                    game.addPSR(new PilotingRollData(entity.getId(), 1,
                                            "leg/foot actuator hit"));
                                    break;
                                case Mech.ACTUATOR_HIP:
                                    // hip piloting roll at +0, because we get the +2 anyway
                                    // because the location is breached.
                                    // The phase report will look a bit weird, but the
                                    // roll is correct
                                    game.addPSR(new PilotingRollData(entity.getId(), 0,
                                            "hip actuator hit"));
                                    break;
                            }
                        }
                    }
                    cs.setBreached(true);
                }
            }

            // Check location for engine/cockpit breach and report accordingly
            if (loc == Mech.LOC_CT) {
                vDesc.addAll(entityActionManager.destroyEntity(entity, "hull breach", this));
                if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_AUTO_ABANDON_UNIT)) {
                    vDesc.addAll(abandonEntity(entity));
                }
            }
            if (loc == Mech.LOC_HEAD) {
                entity.getCrew().setDoomed(true);
                vDesc.addAll(entityActionManager.destroyEntity(entity, "hull breach", this));
                if (entity.getLocationStatus(loc) == ILocationExposureStatus.WET) {
                    r = new Report(6355);
                } else {
                    r = new Report(6360);
                }
                r.subject = entity.getId();
                r.addDesc(entity);
                vDesc.addElement(r);
            }

            // Set the status of the location.
            // N.B. if we set the status before rolling water PSRs, we get a
            // "LEG DESTROYED" modifier; setting the status after gives a hip
            // actuator modifier.
            entity.setLocationStatus(loc, ILocationExposureStatus.BREACHED);

            // Did the hull breach destroy the engine?
            int hitsToDestroy = 3;
            if (mech.isSuperHeavy() && mech.hasEngine()
                    && (mech.getEngine().getEngineType() == Engine.COMPACT_ENGINE)) {
                hitsToDestroy = 2;
            }
            if ((entity.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_ENGINE, Mech.LOC_LT)
                    + entity.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_ENGINE, Mech.LOC_CT)
                    + entity.getHitCriticals(CriticalSlot.TYPE_SYSTEM, Mech.SYSTEM_ENGINE, Mech.LOC_RT))
                    >= hitsToDestroy) {
                vDesc.addAll(entityActionManager.destroyEntity(entity, "engine destruction", this));
                if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_AUTO_ABANDON_UNIT)) {
                    vDesc.addAll(abandonEntity(entity));
                }
            }

            if (loc == Mech.LOC_LT) {
                vDesc.addAll(breachLocation(entity, Mech.LOC_LARM, hex, false));
            }
            if (loc == Mech.LOC_RT) {
                vDesc.addAll(breachLocation(entity, Mech.LOC_RARM, hex, false));
            }
        }

        return vDesc;
    }

    /**
     * Makes one slot of ammo, determined by certain rules, explode on a mech.
     */
    public Vector<Report> explodeAmmoFromHeat(Entity entity) {
        int damage = 0;
        int rack = 0;
        int boomloc = -1;
        int boomslot = -1;
        Vector<Report> vDesc = new Vector<>();

        for (int j = 0; j < entity.locations(); j++) {
            for (int k = 0; k < entity.getNumberOfCriticals(j); k++) {
                CriticalSlot cs = entity.getCritical(j, k);
                if ((cs == null) || cs.isDestroyed() || cs.isHit()
                        || (cs.getType() != CriticalSlot.TYPE_EQUIPMENT)) {
                    continue;
                }
                Mounted mounted = cs.getMount();
                if ((mounted == null) || (!(mounted.getType() instanceof AmmoType))) {
                    continue;
                }
                AmmoType atype = (AmmoType) mounted.getType();
                if (!atype.isExplosive(mounted)) {
                    continue;
                }
                // coolant pods and flamer coolant ammo don't explode from heat
                if ((atype.getAmmoType() == AmmoType.T_COOLANT_POD)
                        || (((atype.getAmmoType() == AmmoType.T_VEHICLE_FLAMER)
                        || (atype.getAmmoType() == AmmoType.T_HEAVY_FLAMER))
                        && (atype.getMunitionType().contains(AmmoType.Munitions.M_COOLANT)))) {
                    continue;
                }
                // ignore empty, destroyed, or missing bins
                if (mounted.getHittableShotsLeft() == 0) {
                    continue;
                }
                // TW page 160, compare one rack's
                // damage. Ties go to most rounds.
                int newRack = atype.getDamagePerShot() * atype.getRackSize();
                int newDamage = mounted.getExplosionDamage();
                Mounted mount2 = cs.getMount2();
                if ((mount2 != null) && (mount2.getType() instanceof AmmoType)
                        && (mount2.getHittableShotsLeft() > 0)) {
                    // must be for same weaponType, so rackSize stays
                    atype = (AmmoType) mount2.getType();
                    newRack += atype.getDamagePerShot() * atype.getRackSize();
                    newDamage += mount2.getExplosionDamage();
                }
                if (!mounted.isHit()
                        && ((rack < newRack) || ((rack == newRack) && (damage < newDamage)))) {
                    rack = newRack;
                    damage = newDamage;
                    boomloc = j;
                    boomslot = k;
                }
            }
        }
        if ((boomloc != -1) && (boomslot != -1)) {
            CriticalSlot slot = entity.getCritical(boomloc, boomslot);
            slot.setHit(true);
            slot.getMount().setHit(true);
            if (slot.getMount2() != null) {
                slot.getMount2().setHit(true);
            }
            vDesc.addAll(entityActionManager.explodeEquipment(entity, boomloc, boomslot, this));
        } else {
            // Luckily, there is no ammo to explode.
            Report r = new Report(5105);
            r.subject = entity.getId();
            r.indent();
            vDesc.addElement(r);
        }
        return vDesc;
    }

    /**
     * Makes a mech fall.
     *
     * @param entity
     *            The Entity that is falling. It is expected that the Entity's
     *            position and elevation reflect the state prior to the fall
     * @param fallPos
     *            The location that the Entity is falling into.
     * @param fallHeight
     *            The height that Entity is falling.
     * @param facing
     *            The facing of the fall. Used to determine the hit location
     *            and also determines facing after the fall (used as an offset
     *            of the Entity's current facing).
     * @param roll
     *            The PSR required to avoid damage to the pilot/crew.
     * @param intoBasement
     *            Flag that determines whether this is a fall into a basement or
     *            not.
     */
    protected Vector<Report> doEntityFall(Entity entity, Coords fallPos, int fallHeight, int facing,
                                        PilotingRollData roll, boolean intoBasement, boolean fromCliff) {
        entity.setFallen(true);

        Vector<Report> vPhaseReport = new Vector<>();
        Report r;

        Hex fallHex = game.getBoard().getHex(fallPos);

        boolean handlingBasement = false;
        int damageTable = ToHitData.HIT_NORMAL;

        // we don't need to deal damage yet, if the entity is doing DFA
        if (entity.isMakingDfa()) {
            r = new Report(2305);
            r.subject = entity.getId();
            vPhaseReport.add(r);
            entity.setProne(true);
            return vPhaseReport;
        }

        // facing after fall
        String side;
        int table;
        switch (facing) {
            case 1:
            case 2:
                side = "right side";
                table = ToHitData.SIDE_RIGHT;
                break;
            case 3:
                side = "rear";
                table = ToHitData.SIDE_REAR;
                break;
            case 4:
            case 5:
                side = "left side";
                table = ToHitData.SIDE_LEFT;
                break;
            case 0:
            default:
                side = "front";
                table = ToHitData.SIDE_FRONT;
        }

        int waterDepth = 0;
        if (fallHex.containsTerrain(Terrains.WATER)) {
            // *Only* use this if there actually is water in the hex, otherwise
            // we get Terrain.LEVEL_NONE, i.e. Integer.minValue...
            waterDepth = fallHex.terrainLevel(Terrains.WATER);
        }
        boolean fallOntoBridge = false;
        // only fall onto the bridge if we were in the hex and on it,
        // or we fell from a hex that the bridge exits to
        if ((entity.climbMode() && (entity.getPosition() != fallPos)
                && fallHex.containsTerrain(Terrains.BRIDGE)
                && fallHex.containsTerrainExit(Terrains.BRIDGE, fallPos.direction(entity.getPosition())))
                || (entity.getElevation() == fallHex.terrainLevel(Terrains.BRIDGE_ELEV))) {
            fallOntoBridge = true;
        }
        int bridgeElev = fallHex.terrainLevel(Terrains.BRIDGE_ELEV);
        int buildingElev = fallHex.terrainLevel(Terrains.BLDG_ELEV);
        int damageHeight = fallHeight;
        int newElevation = 0;

        // we might have to check if the building/bridge we are falling onto
        // collapses
        boolean checkCollapse = false;

        if ((entity.getElevation() >= buildingElev) && (buildingElev >= 0)) {
            // fallHeight should already reflect this
            newElevation = buildingElev;
            checkCollapse = true;
        } else if (fallOntoBridge && (entity.getElevation() >= bridgeElev) && (bridgeElev >= 0)) {
            // fallHeight should already reflect this
            waterDepth = 0;
            newElevation = fallHex.terrainLevel(Terrains.BRIDGE_ELEV);
            checkCollapse = true;
        } else if (fallHex.containsTerrain(Terrains.ICE) && (entity.getElevation() == 0)) {
            waterDepth = 0;
            newElevation = 0;
            // If we are in a basement, we are at a negative elevation, and so
            // setting newElevation = 0 will cause us to "fall up"
        } else if ((entity.getMovementMode() != EntityMovementMode.VTOL)
                && (game.getBoard().getBuildingAt(fallPos) != null)) {
            newElevation = entity.getElevation();
        }
        // HACK: if the destination hex is water, assume that the fall height given is
        // to the floor of the hex, and modify it so that it's to the surface
        else if (waterDepth > 0) {
            damageHeight = fallHeight - waterDepth;
            newElevation = -waterDepth;
        }
        // only do these basement checks if we didn't fall onto the building
        // from above
        if (intoBasement) {
            Building bldg = game.getBoard().getBuildingAt(fallPos);
            BasementType basement = bldg.getBasement(fallPos);
            if (!basement.isNone() && !basement.isOneDeepNormalInfantryOnly()
                    && (entity.getElevation() == 0) && (bldg.getBasementCollapsed(fallPos))) {

                if (fallHex.depth(true) == 0) {
                    LogManager.getLogger().error("Entity " + entity.getDisplayName() + " is falling into a depth "
                            + fallHex.depth(true) + " basement -- not allowed!!");
                    return vPhaseReport;
                }
                damageHeight = basement.getDepth();

                newElevation = newElevation - damageHeight;

                handlingBasement = true;
                // May have to adjust hit table for 'mechs
                if (entity instanceof Mech) {
                    switch (basement) {
                        case TWO_DEEP_FEET:
                        case ONE_DEEP_FEET:
                            damageTable = ToHitData.HIT_KICK;
                            break;
                        case ONE_DEEP_HEAD:
                        case TWO_DEEP_HEAD:
                            damageTable = ToHitData.HIT_PUNCH;
                            break;
                        default:
                            damageTable = ToHitData.HIT_NORMAL;
                            break;
                    }
                }
            }
        }

        if (entity instanceof Protomech) {
            damageTable = ToHitData.HIT_SPECIAL_PROTO;
        }
        // Falling into water instantly destroys most non-mechs
        if ((waterDepth > 0)
                && !(entity instanceof Mech)
                && !(entity instanceof Protomech)
                && !((entity.getRunMP() > 0) && (entity.getMovementMode() == EntityMovementMode.HOVER))
                && (entity.getMovementMode() != EntityMovementMode.HYDROFOIL)
                && (entity.getMovementMode() != EntityMovementMode.NAVAL)
                && (entity.getMovementMode() != EntityMovementMode.SUBMARINE)
                && (entity.getMovementMode() != EntityMovementMode.WIGE)
                && (entity.getMovementMode() != EntityMovementMode.INF_UMU)) {
            vPhaseReport.addAll(entityActionManager.destroyEntity(entity, "a watery grave", false, this));
            return vPhaseReport;
        }

        // set how deep the mech has fallen
        if (entity instanceof Mech) {
            Mech mech = (Mech) entity;
            mech.setLevelsFallen(damageHeight + waterDepth + 1);
            // an industrial mech now needs to check for a crit at the end of
            // the turn
            if (mech.isIndustrial()) {
                mech.setCheckForCrit(true);
            }
        }

        // calculate damage for hitting the surface
        int damage = (int) Math.round(entity.getWeight() / 10.0)
                * (damageHeight + 1);
        // different rules (pg. 151 of TW) for battle armor and infantry
        if (entity instanceof Infantry) {
            damage = (int) Math.ceil(damageHeight / 2.0);
            // no damage for fall from less than 2 levels
            if (damageHeight < 2) {
                damage = 0;
            }
            if (!(entity instanceof BattleArmor)) {
                int dice = 3;
                if (entity.getMovementMode() == EntityMovementMode.INF_MOTORIZED) {
                    dice = 2;
                } else if ((entity.getMovementMode() == EntityMovementMode.INF_JUMP)
                        || ((Infantry) entity).isMechanized()) {
                    dice = 1;
                }
                damage = damage * Compute.d6(dice);
            }
        }
        // Different rules (pg 62/63/152 of TW) for Tanks
        if (entity instanceof Tank) {
            // Falls from less than 2 levels don't damage combat vehicles
            // except if they fall off a sheer cliff
            if (damageHeight < 2 && !fromCliff) {
                damage = 0;
            }
            // Falls from >= 2 elevations damage like crashing VTOLs
            // Ends up being the regular damage: weight / 10 * (height + 1)
            // And this was already computed
        }
        // calculate damage for hitting the ground, but only if we actually fell
        // into water
        // if we fell onto the water surface, that damage is halved.
        int waterDamage = 0;
        if (waterDepth > 0) {
            damage /= 2;
            waterDamage = ((int) Math.round(entity.getWeight() / 10.0) * (waterDepth + 1)) / 2;
        }

        // If the waterDepth is larger than the fall height, we fell underwater
        if ((waterDepth >= fallHeight) && ((waterDepth != 0) || (fallHeight != 0))) {
            damage = 0;
            waterDamage = ((int) Math.round(entity.getWeight() / 10.0) * (fallHeight + 1)) / 2;
        }
        // adjust damage for gravity
        damage = Math
                .round(damage * game.getPlanetaryConditions().getGravity());
        waterDamage = Math.round(waterDamage
                * game.getPlanetaryConditions().getGravity());

        // report falling
        if (waterDamage == 0) {
            r = new Report(2310);
            r.subject = entity.getId();
            r.indent();
            r.addDesc(entity);
            r.add(side);
            r.add(damage);
        } else if (damage > 0) {
            r = new Report(2315);
            r.subject = entity.getId();
            r.indent();
            r.addDesc(entity);
            r.add(side);
            r.add(damage);
            r.add(waterDamage);
        } else {
            r = new Report(2310);
            r.subject = entity.getId();
            r.indent();
            r.addDesc(entity);
            r.add(side);
            r.add(waterDamage);
        }
        vPhaseReport.add(r);

        // Any swarming infantry will be dislodged, but we don't want to
        // interrupt the fall's report. We have to get the ID now because
        // the fall may kill the entity which will reset the attacker ID.
        final int swarmerId = entity.getSwarmAttackerId();

        // Positioning must be prior to damage for proper handling of breaches
        // Only Mechs can fall prone.
        if (entity instanceof Mech) {
            entity.setProne(true);
        }
        entity.setPosition(fallPos);
        entity.setElevation(newElevation);
        // Only 'mechs change facing when they fall
        if (entity instanceof Mech) {
            entity.setFacing((entity.getFacing() + (facing)) % 6);
            entity.setSecondaryFacing(entity.getFacing());
        }

        // if falling into a bog-down hex, the entity automatically gets stuck (except when on a bridge or building)
        // but avoid reporting this twice in the case of DFAs
        if (!entity.isStuck() && (entity.getElevation() == 0)) {
            if (fallHex.getBogDownModifier(entity.getMovementMode(),
                    entity instanceof LargeSupportTank) != TargetRoll.AUTOMATIC_SUCCESS) {
                entity.setStuck(true);
                r = new Report(2081);
                r.subject = entity.getId();
                r.add(entity.getDisplayName(), true);
                vPhaseReport.add(r);
                // check for quicksand
                vPhaseReport.addAll(checkQuickSand(fallPos));
            }
        }

        // standard damage loop
        if ((entity instanceof Infantry) && (damage > 0)) {
            if (entity instanceof BattleArmor) {
                for (int i = 1; i < entity.locations(); i++) {
                    HitData h = new HitData(i);
                    vPhaseReport.addAll(damageEntity(entity, h, damage));
                    addNewLines();
                }
            } else {
                HitData h = new HitData(Infantry.LOC_INFANTRY);
                vPhaseReport.addAll(damageEntity(entity, h, damage));
            }
        } else {
            while (damage > 0) {
                int cluster = Math.min(5, damage);
                HitData hit = entity.rollHitLocation(damageTable, table);
                hit.makeFallDamage(true);
                vPhaseReport.addAll(damageEntity(entity, hit, cluster));
                damage -= cluster;
            }
        }

        if (waterDepth > 0) {
            for (int loop = 0; loop < entity.locations(); loop++) {
                entity.setLocationStatus(loop, ILocationExposureStatus.WET);
            }
        }
        // Water damage
        while (waterDamage > 0) {
            int cluster = Math.min(5, waterDamage);
            HitData hit = entity.rollHitLocation(damageTable, table);
            hit.makeFallDamage(true);
            vPhaseReport.addAll(damageEntity(entity, hit, cluster));
            waterDamage -= cluster;
        }

        // check for location exposure
        vPhaseReport.addAll(utilityManager.doSetLocationsExposure(entity, fallHex, false,
                -waterDepth, this));

        // only mechs should roll to avoid pilot damage
        // vehicles may fall due to sideslips
        if (entity instanceof Mech) {
            vPhaseReport.addAll(checkPilotAvoidFallDamage(entity, fallHeight, roll));
        }

        // Now dislodge any swarming infantry.
        if (Entity.NONE != swarmerId) {
            final Entity swarmer = game.getEntity(swarmerId);
            entity.setSwarmAttackerId(Entity.NONE);
            swarmer.setSwarmTargetId(Entity.NONE);
            // Did the infantry fall into water?
            if ((waterDepth > 0)
                    && (swarmer.getMovementMode() != EntityMovementMode.INF_UMU)) {
                // Swarming infantry die.
                swarmer.setPosition(fallPos);
                r = new Report(2330);
                r.newlines = 0;
                r.subject = swarmer.getId();
                r.addDesc(swarmer);
                vPhaseReport.add(r);
                vPhaseReport.addAll(entityActionManager.destroyEntity(swarmer, "a watery grave",
                        false, this));
            } else {
                // Swarming infantry take a 2d6 point hit.
                // ASSUMPTION : damage should not be doubled.
                r = new Report(2335);
                r.newlines = 0;
                r.subject = swarmer.getId();
                r.addDesc(swarmer);
                vPhaseReport.add(r);
                vPhaseReport.addAll(damageEntity(swarmer, swarmer
                        .rollHitLocation(ToHitData.HIT_NORMAL,
                                ToHitData.SIDE_FRONT), Compute.d6(2)));
                Report.addNewline(vPhaseReport);
            }
            swarmer.setPosition(fallPos);
            entityUpdate(swarmerId);
            if (!swarmer.isDone()) {
                game.removeTurnFor(swarmer);
                swarmer.setDone(true);
                communicationManager.send(packetManager.createTurnVectorPacket(this));
            }
        } // End dislodge-infantry

        // clear all PSRs after a fall -- the Mek has already failed ONE and
        // fallen, it'd be cruel to make it fail some more!
        game.resetPSRs(entity);

        // if there is a minefield in this hex, then the mech may set it off
        if (game.containsMinefield(fallPos)
                && environmentalEffectManager.enterMinefield(entity, fallPos, newElevation, true,
                vPhaseReport, 12, this)) {
            environmentalEffectManager.resetMines(this);
        }
        // if we have to, check if the building/bridge we fell on collapses -
        // unless it's a fall into a basement,
        // then we're already gonna check that in building collapse, where we
        // came from
        if (checkCollapse && !handlingBasement) {

            checkForCollapse(game.getBoard().getBuildingAt(fallPos),
                    game.getPositionMap(), fallPos, false, vPhaseReport);
        }

        return vPhaseReport;
    }

    protected Vector<Report> checkPilotAvoidFallDamage(Entity entity, int fallHeight, PilotingRollData roll) {
        Vector<Report> reports = new Vector<>();

        if (entity.hasAbility(OptionsConstants.MD_DERMAL_ARMOR)
                || entity.hasAbility(OptionsConstants.MD_TSM_IMPLANT)) {
            return reports;
        }
        // we want to be able to avoid pilot damage even when it was
        // an automatic fall, only unconsciousness should cause auto-damage
        roll.removeAutos();

        if (fallHeight > 1) {
            roll.addModifier(fallHeight - 1, "height of fall");
        }

        if (entity.getCrew().getSlotCount() > 1) {
            //Extract the base from the list of modifiers so we can replace it with the piloting
            //skill of each crew member.
            List<TargetRollModifier> modifiers = new ArrayList<>(roll.getModifiers());
            if (!modifiers.isEmpty()) {
                modifiers.remove(0);
            }
            for (int pos = 0; pos < entity.getCrew().getSlotCount(); pos++) {
                if (entity.getCrew().isMissing(pos) || entity.getCrew().isDead(pos)) {
                    continue;
                }
                PilotingRollData prd;
                if (entity.getCrew().isDead(pos)) {
                    continue;
                } else if (entity.getCrew().isUnconscious(pos)) {
                    prd = new PilotingRollData(entity.getId(), TargetRoll.AUTOMATIC_FAIL,
                            "Crew member unconscious");
                } else {
                    prd = new PilotingRollData(entity.getId(),
                            entity.getCrew().getPiloting(pos), "Base piloting skill");
                    modifiers.forEach(prd::addModifier);
                }
                reports.addAll(resolvePilotDamageFromFall(entity, prd, pos));
            }
        } else {
            reports.addAll(resolvePilotDamageFromFall(entity, roll, 0));
        }
        return reports;
    }

    protected Vector<Report> resolvePilotDamageFromFall(Entity entity, PilotingRollData roll, int crewPos) {
        Vector<Report> reports = new Vector<>();
        Report r;
        if (roll.getValue() == TargetRoll.IMPOSSIBLE) {
            r = new Report(2320);
            r.subject = entity.getId();
            r.add(entity.getCrew().getCrewType().getRoleName(crewPos));
            r.addDesc(entity);
            r.add(entity.getCrew().getName(crewPos));
            r.indent();
            reports.add(r);
            reports.addAll(damageCrew(entity, 1, crewPos));
        } else {
            Roll diceRoll = entity.getCrew().rollPilotingSkill();
            r = new Report(2325);
            r.subject = entity.getId();
            r.add(entity.getCrew().getCrewType().getRoleName(crewPos));
            r.addDesc(entity);
            r.add(entity.getCrew().getName(crewPos));
            r.add(roll.getValueAsString());
            r.add(diceRoll);

            if (diceRoll.getIntValue() >= roll.getValue()) {
                r.choose(true);
                reports.add(r);
            } else {
                r.choose(false);
                reports.add(r);
                reports.addAll(damageCrew(entity, 1, crewPos));
            }
        }
        Report.addNewline(reports);
        return reports;
    }

    /**
     * The mech falls into an unoccupied hex from the given height above
     */
    protected Vector<Report> doEntityFall(Entity entity, Coords fallPos,
                                        int height, PilotingRollData roll) {
        return doEntityFall(entity, fallPos, height, Compute.d6(1) - 1, roll,
                false, false);
    }

    /**
     * The mech falls down in place
     */
    protected Vector<Report> doEntityFall(Entity entity, PilotingRollData roll) {
        boolean fallToSurface = false;
        // on ice
        int toSubtract = 0;
        Hex currHex = game.getBoard().getHex(entity.getPosition());
        if (currHex.containsTerrain(Terrains.ICE)
                && (entity.getElevation() != -currHex.depth())) {
            fallToSurface = true;
            toSubtract = 0;
        }
        // on a bridge
        if (currHex.containsTerrain(Terrains.BRIDGE_ELEV)
                && (entity.getElevation() >= currHex
                .terrainLevel(Terrains.BRIDGE_ELEV))) {
            fallToSurface = true;
            toSubtract = currHex.terrainLevel(Terrains.BRIDGE_ELEV);
        }
        // on a building
        if (currHex.containsTerrain(Terrains.BLDG_ELEV)
                && (entity.getElevation() >= currHex
                .terrainLevel(Terrains.BLDG_ELEV))) {
            fallToSurface = true;
            toSubtract = currHex.terrainLevel(Terrains.BLDG_ELEV);
        }
        return doEntityFall(entity, entity.getPosition(), entity.getElevation()
                + (!fallToSurface ? currHex.depth(true) : -toSubtract), roll);
    }

    /**
     * Report: - Any ammo dumps beginning the following round. - Any ammo dumps
     * that have ended with the end of this round.
     */
    protected void resolveAmmoDumps() {
        Report r;
        for (Entity entity : game.getEntitiesVector()) {
            for (Mounted m : entity.getAmmo()) {
                if (m.isPendingDump()) {
                    // report dumping next round
                    r = new Report(5110);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    r.add(m.getName());
                    addReport(r);
                    // update status
                    m.setPendingDump(false);
                    m.setDumping(true);
                } else if (m.isDumping()) {
                    // report finished dumping
                    r = new Report(5115);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    r.add(m.getName());
                    addReport(r);
                    // update status
                    m.setDumping(false);
                    m.setShotsLeft(0);
                }
            }
            // also do DWP dumping
            if (entity instanceof BattleArmor) {
                for (Mounted m : entity.getWeaponList()) {
                    if (m.isDWPMounted() && m.isPendingDump()) {
                        m.setMissing(true);
                        r = new Report(5116);
                        r.subject = entity.getId();
                        r.addDesc(entity);
                        r.add(m.getName());
                        addReport(r);
                        m.setPendingDump(false);
                        // Also dump all of the ammo in the DWP
                        for (Mounted ammo : entity.getAmmo()) {
                            if (m.equals(ammo.getLinkedBy())) {
                                ammo.setMissing(true);
                            }
                        }
                        // Check for jettisoning missiles
                    } else if (m.isBodyMounted() && m.isPendingDump()
                            && m.getType().hasFlag(WeaponType.F_MISSILE)
                            && (m.getLinked() != null)
                            && (m.getLinked().getUsableShotsLeft() > 0)) {
                        m.setMissing(true);
                        r = new Report(5116);
                        r.subject = entity.getId();
                        r.addDesc(entity);
                        r.add(m.getName());
                        addReport(r);
                        m.setPendingDump(false);
                        // Dump all ammo related to this launcher
                        // BA burdened is based on whether the launcher has
                        // ammo left
                        while ((m.getLinked() != null)
                                && (m.getLinked().getUsableShotsLeft() > 0)) {
                            m.getLinked().setMissing(true);
                            entity.loadWeapon(m);
                        }
                    }
                }
            }
            entity.reloadEmptyWeapons();
        }
    }

    /**
     * Checks for fire ignition based on a given target roll. If successful,
     * lights a fire also checks to see that fire is possible in the specified
     * hex.
     *
     * @param c        - the <code>Coords</code> to be lit.
     * @param roll     - the <code>TargetRoll</code> for the ignition roll
     * @param bInferno - <code>true</code> if the fire is an inferno fire. If this
     *                 value is <code>false</code> the hex will be lit only if it
     *                 contains Woods, jungle or a Building.
     * @param entityId - the entityId responsible for the ignite attempt. If the
     *                 value is Entity.NONE, then the roll attempt will not be
     *                 included in the report.
     */
    public boolean checkIgnition(Coords c, TargetRoll roll, boolean bInferno, int entityId,
                                 Vector<Report> vPhaseReport) {

        Hex hex = game.getBoard().getHex(c);

        // The hex might be null due to spreadFire translation
        // goes outside of the board limit.
        if (null == hex) {
            return false;
        }

        // The hex may already be on fire.
        if (hex.containsTerrain(Terrains.FIRE)) {
            return false;
        }

        if (!bInferno && !hex.isIgnitable()) {
            return false;
        }

        Roll diceRoll = Compute.rollD6(2);
        Report r;

        if (entityId != Entity.NONE) {
            r = new Report(3430);
            r.indent(2);
            r.subject = entityId;
            r.add(roll.getValueAsString());
            r.add(roll.getDesc());
            r.add(diceRoll);
            vPhaseReport.add(r);
        }

        if (diceRoll.getIntValue() >= roll.getValue()) {
            ignite(c, Terrains.FIRE_LVL_NORMAL, vPhaseReport);
            return true;
        }
        return false;
    }

    /**
     * Returns true if the hex is set on fire with the specified roll. Of
     * course, also checks to see that fire is possible in the specified hex.
     * This version of the method will not report the attempt roll.
     *
     * @param c        - the <code>Coords</code> to be lit.
     * @param roll     - the <code>int</code> target number for the ignition roll
     * @param bInferno - <code>true</code> if the fire can be lit in any terrain. If
     *                 this value is <code>false</code> the hex will be lit only if
     *                 it contains Woods, jungle or a Building.
     */
    public boolean checkIgnition(Coords c, TargetRoll roll, boolean bInferno) {
        return checkIgnition(c, roll, bInferno, Entity.NONE, null);
    }

    /**
     * Returns true if the hex is set on fire with the specified roll. Of
     * course, also checks to see that fire is possible in the specified hex.
     * This version of the method will not report the attempt roll.
     *
     * @param c    - the <code>Coords</code> to be lit.
     * @param roll - the <code>int</code> target number for the ignition roll
     */
    public boolean checkIgnition(Coords c, TargetRoll roll) {
        // default signature, assuming only woods can burn
        return checkIgnition(c, roll, false, Entity.NONE, null);
    }

    /**
     * add fire to a hex
     *
     * @param c         - the <code>Coords</code> of the hex to be set on fire
     * @param fireLevel - The level of fire, see Terrains
     */
    public void ignite(Coords c, int fireLevel, Vector<Report> vReport) {
        // you can't start fires in some planetary conditions!
        if (null != game.getPlanetaryConditions().cannotStartFire()) {
            if (null != vReport) {
                Report r = new Report(3007);
                r.indent(2);
                r.add(game.getPlanetaryConditions().cannotStartFire());
                r.type = Report.PUBLIC;
                vReport.add(r);
            }
            return;
        }

        if (!game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_START_FIRE)) {
            if (null != vReport) {
                Report r = new Report(3008);
                r.indent(2);
                r.type = Report.PUBLIC;
                vReport.add(r);
            }
            return;
        }

        Hex hex = game.getBoard().getHex(c);
        if (null == hex) {
            return;
        }

        Report r = new Report(3005);
        r.indent(2);
        r.add(c.getBoardNum());
        r.type = Report.PUBLIC;

        // Adjust report message for inferno types
        switch (fireLevel) {
            case Terrains.FIRE_LVL_INFERNO:
                r.messageId = 3006;
                break;
            case Terrains.FIRE_LVL_INFERNO_BOMB:
                r.messageId = 3003;
                break;
            case Terrains.FIRE_LVL_INFERNO_IV:
                r.messageId = 3004;
                break;
        }

        // report it
        if (null != vReport) {
            vReport.add(r);
        }
        hex.addTerrain(new Terrain(Terrains.FIRE, fireLevel));
        communicationManager.sendChangedHex(c, this);
    }

    /**
     * remove fire from a hex
     *
     * @param fireCoords
     * @param reason
     */
    public void removeFire(Coords fireCoords, String reason) {
        Hex hex = game.getBoard().getHex(fireCoords);
        if (null == hex) {
            return;
        }
        hex.removeTerrain(Terrains.FIRE);
        hex.resetFireTurn();
        communicationManager.sendChangedHex(fireCoords, this);
        // fire goes out
        Report r = new Report(5170, Report.PUBLIC);
        r.add(fireCoords.getBoardNum());
        r.add(reason);
        addReport(r);
    }

    /**
     * Called when a fire is burning. Called 3 times per fire hex.
     *
     * @param coords The <code>Coords</code> x-coordinate of the hex
     */
    public void addSmoke(ArrayList<Coords> coords, int windDir, boolean bInferno) {
        // if a tornado, then no smoke!
        if (game.getPlanetaryConditions().getWindStrength() > PlanetaryConditions.WI_STORM) {
            return;
        }

        int smokeLevel = 0;
        for (Coords smokeCoords : coords) {
            Hex smokeHex = game.getBoard().getHex(smokeCoords);
            Report r;
            if (smokeHex == null) {
                continue;
            }
            // Have to check if it's inferno smoke or from a heavy/hardened
            // building
            // - heavy smoke from those
            if (bInferno || (Building.MEDIUM < smokeHex.terrainLevel(Terrains.FUEL_TANK))
                    || (Building.MEDIUM < smokeHex.terrainLevel(Terrains.BUILDING))) {
                if (smokeHex.terrainLevel(Terrains.SMOKE) == SmokeCloud.SMOKE_HEAVY) {
                    // heavy smoke fills hex
                    r = new Report(5180, Report.PUBLIC);
                } else {
                    r = new Report(5185, Report.PUBLIC);
                }
                smokeLevel = SmokeCloud.SMOKE_HEAVY;
                r.add(smokeCoords.getBoardNum());
                addReport(r);
            } else {
                if (smokeHex.terrainLevel(Terrains.SMOKE) == SmokeCloud.SMOKE_HEAVY) {
                    // heavy smoke overpowers light
                    r = new Report(5190, Report.PUBLIC);
                    r.add(smokeCoords.getBoardNum());
                    smokeLevel = Math.max(smokeLevel, SmokeCloud.SMOKE_LIGHT);
                    addReport(r);
                } else if (smokeHex.terrainLevel(Terrains.SMOKE) == SmokeCloud.SMOKE_LIGHT) {
                    // light smoke continue to fill hex
                    r = new Report(5195, Report.PUBLIC);
                    r.add(smokeCoords.getBoardNum());
                    addReport(r);
                    smokeLevel = Math.max(smokeLevel, SmokeCloud.SMOKE_LIGHT);
                } else {
                    smokeLevel = Math.max(smokeLevel, SmokeCloud.SMOKE_LIGHT);
                    // light smoke fills hex
                    r = new Report(5200, Report.PUBLIC);
                    r.add(smokeCoords.getBoardNum());
                    addReport(r);
                }
            }
        }
        createSmoke(coords, smokeLevel, 0);
    }

    /**
     * Recursively scan the specified path to determine the board sizes
     * available.
     *
     * @param searchDir The directory to search below this path (may be null for all
     *                  in base path).
     * @param sizes     Where to store the discovered board sizes
     */
    protected void getBoardSizesInDir(final File searchDir, TreeSet<BoardDimensions> sizes) {
        if (searchDir == null) {
            throw new IllegalArgumentException("must provide searchDir");
        }

        if (sizes == null) {
            throw new IllegalArgumentException("must provide sizes");
        }

        String[] file_list = searchDir.list();

        if (file_list != null) {
            for (String filename : file_list) {
                File query_file = new File(searchDir, filename);

                if (query_file.isDirectory()) {
                    getBoardSizesInDir(query_file, sizes);
                } else {
                    try {
                        if (filename.endsWith(".board")) {
                            BoardDimensions size = Board.getSize(query_file);
                            if (size == null) {
                                throw new Exception();
                            }
                            sizes.add(Board.getSize(query_file));
                        }
                    } catch (Exception e) {
                        LogManager.getLogger().error("Error parsing board: " + query_file.getAbsolutePath(), e);
                    }
                }
            }
        }
    }

    /**
     * Get a list of the available board sizes from the boards data directory.
     *
     * @return A Set containing all the available board sizes.
     */
    protected Set<BoardDimensions> getBoardSizes() {
        TreeSet<BoardDimensions> board_sizes = new TreeSet<>();

        File boards_dir = Configuration.boardsDir();
        // Slightly overkill sanity check...
        if (boards_dir.isDirectory()) {
            getBoardSizesInDir(boards_dir, board_sizes);
        }
        boards_dir = new File(Configuration.userdataDir(), Configuration.boardsDir().toString());
        if (boards_dir.isDirectory()) {
            getBoardSizesInDir(boards_dir, board_sizes);
        }

        return board_sizes;
    }

    /**
     * @return whether this game is double blind or not and we should be blind in
     * the current phase
     */
    boolean doBlind() {
        return game.getOptions().booleanOption(OptionsConstants.ADVANCED_DOUBLE_BLIND)
                && game.getPhase().isDuringOrAfter(GamePhase.DEPLOYMENT);
    }

    boolean suppressBlindBV() {
        return game.getOptions().booleanOption(OptionsConstants.ADVANCED_SUPPRESS_DB_BV);
    }

    /**
     * In a double-blind game, update only visible entities. Otherwise, update
     * everyone
     */
    public void entityUpdate(int nEntityID) {
        entityUpdate(nEntityID, new Vector<>(), true, null);
    }

    /**
     * In a double-blind game, update only visible entities. Otherwise, update
     * everyone
     *
     * @param updateVisibility Flag that determines if whoCanSee needs to be
     *                         called to update who can see the entity for
     *                         double-blind games.
     */
    public void entityUpdate(int nEntityID, Vector<UnitLocation> movePath, boolean updateVisibility,
                             Map<EntityTargetPair, LosEffects> losCache) {
        Entity eTarget = game.getEntity(nEntityID);
        if (eTarget == null) {
            if (game.getOutOfGameEntity(nEntityID) != null) {
                LogManager.getLogger().error("S: attempted to send entity update for out of game entity, id was " + nEntityID);
            } else {
                LogManager.getLogger().error("S: attempted to send entity update for null entity, id was " + nEntityID);
            }

            return; // do not send the update it will crash the client
        }

        // If we're doing double blind, be careful who can see it...
        if (doBlind()) {
            Vector<Player> playersVector = game.getPlayersVector();
            Vector<Player> vCanSee;
            if (updateVisibility) {
                vCanSee = whoCanSee(eTarget, true, losCache);
            } else {
                vCanSee = eTarget.getWhoCanSee();
            }

            // If this unit has ECM, players with units affected by the ECM will
            //  need to know about this entity, even if they can't see it.
            //  Otherwise, the client can't properly report things like to-hits.
            if ((eTarget.getECMRange() > 0) && (eTarget.getPosition() != null)) {
                int ecmRange = eTarget.getECMRange();
                Coords pos = eTarget.getPosition();
                for (Entity ent : game.getEntitiesVector()) {
                    if ((ent.getPosition() != null)
                            && (pos.distance(ent.getPosition()) <= ecmRange)) {
                        if (!vCanSee.contains(ent.getOwner())) {
                            vCanSee.add(ent.getOwner());
                        }
                    }
                }
            }

            // send an entity update to everyone who can see
            Packet pack = communicationManager.packetManager.createEntityPacket(nEntityID, movePath, this);
            for (int x = 0; x < vCanSee.size(); x++) {
                Player p = vCanSee.elementAt(x);
                communicationManager.send(p.getId(), pack);
            }
            // send an entity delete to everyone else
            pack = packetManager.createRemoveEntityPacket(nEntityID, eTarget.getRemovalCondition(), this);
            for (int x = 0; x < playersVector.size(); x++) {
                if (!vCanSee.contains(playersVector.elementAt(x))) {
                    Player p = playersVector.elementAt(x);
                    communicationManager.send(p.getId(), pack);
                }
            }

            entityUpdateLoadedUnits(eTarget, vCanSee, playersVector);
        } else {
            // But if we're not, then everyone can see.
            communicationManager.send(communicationManager.packetManager.createEntityPacket(nEntityID, movePath, this));
        }
    }

    /**
     * Whenever updating an Entity, we also need to update all of its loaded
     * Entity's, otherwise it could cause issues with Clients.
     *
     * @param loader        An Entity being updated that is transporting units that should
     *                      also send an update
     * @param vCanSee       The list of Players who can see the loader.
     * @param playersVector The list of all Players
     */
    protected void entityUpdateLoadedUnits(Entity loader, Vector<Player> vCanSee,
                                         Vector<Player> playersVector) {
        // In double-blind, the client may not know about the loaded units,
        // so we need to send them.
        for (Entity eLoaded : loader.getLoadedUnits()) {
            // send an entity update to everyone who can see
            Packet pack = communicationManager.packetManager.createEntityPacket(eLoaded.getId(), null, this);
            for (int x = 0; x < vCanSee.size(); x++) {
                Player p = vCanSee.elementAt(x);
                communicationManager.send(p.getId(), pack);
            }
            // send an entity delete to everyone else
            pack = packetManager.createRemoveEntityPacket(eLoaded.getId(), eLoaded.getRemovalCondition(), this);
            for (int x = 0; x < playersVector.size(); x++) {
                if (!vCanSee.contains(playersVector.elementAt(x))) {
                    Player p = playersVector.elementAt(x);
                    communicationManager.send(p.getId(), pack);
                }
            }
            entityUpdateLoadedUnits(eLoaded, vCanSee, playersVector);
        }
    }

    /**
     * Returns a vector of which players can see this entity, always allowing
     * for sensor detections.
     */
    protected Vector<Player> whoCanSee(Entity entity) {
        return whoCanSee(entity, true, null);
    }

    /**
     * Returns a vector of which players can see the given entity, optionally
     * allowing for sensors to count.
     *
     * @param entity     The entity to check visibility for
     * @param useSensors A flag that determines whether sensors are allowed
     * @return A vector of the players who can see the entity
     */
    protected Vector<Player> whoCanSee(Entity entity, boolean useSensors,
                                     Map<EntityTargetPair, LosEffects> losCache) {
        if (losCache == null) {
            losCache = new HashMap<>();
        }
        // Some times Null entities are sent to this
        if (entity == null) {
            return new Vector<>();
        }

        List<ECMInfo> allECMInfo = null;
        if (game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_SENSORS) && useSensors) {
            allECMInfo = ComputeECM.computeAllEntitiesECMInfo(game
                    .getEntitiesVector());
        }

        boolean bTeamVision = game.getOptions().booleanOption(OptionsConstants.ADVANCED_TEAM_VISION);
        List<Entity> vEntities = game.getEntitiesVector();

        Vector<Player> vCanSee = new Vector<>();
        vCanSee.addElement(entity.getOwner());
        if (bTeamVision) {
            addTeammates(vCanSee, entity.getOwner());
        }

        // Deal with players who can see all.
        for (Enumeration<Player> p = game.getPlayers(); p.hasMoreElements();) {
            Player player = p.nextElement();
            if (player.canIgnoreDoubleBlind() && !vCanSee.contains(player)) {
                vCanSee.addElement(player);
            }
        }

        // If the entity is hidden, skip; no one else will be able to see it.
        if (entity.isHidden()) {
            return vCanSee;
        }
        for (Entity spotter : vEntities) {
            // Certain conditions make the spotter ineligible
            if (!spotter.isActive() || spotter.isOffBoard()
                    || vCanSee.contains(spotter.getOwner())) {
                continue;
            }
            // See if the LosEffects is cached, and if not cache it
            EntityTargetPair etp = new EntityTargetPair(spotter, entity);
            LosEffects los = losCache.get(etp);
            if (los == null) {
                los = LosEffects.calculateLOS(game, spotter, entity);
                losCache.put(etp, los);
            }
            if (Compute.canSee(game, spotter, entity, useSensors, los,
                    allECMInfo)) {
                if (!vCanSee.contains(spotter.getOwner())) {
                    vCanSee.addElement(spotter.getOwner());
                }
                if (bTeamVision) {
                    addTeammates(vCanSee, spotter.getOwner());
                }
                addObservers(vCanSee);
            }
        }
        return vCanSee;
    }

    /**
     * Determine which players can detect the given entity with sensors.
     * Because recomputing ECM and LosEffects frequently can get expensive, this
     * data can be cached and passed in.
     *
     * @param entity        The Entity being detected.
     * @param allECMInfo    Cached ECMInfo for all Entities in the game.
     * @param losCache      Cached LosEffects for particular Entity/Targetable
     *                      pairs.  Can be passed in null.
     * @return
     */
    protected Vector<Player> whoCanDetect(Entity entity,
                                        List<ECMInfo> allECMInfo,
                                        Map<EntityTargetPair, LosEffects> losCache) {
        if (losCache == null) {
            losCache = new HashMap<>();
        }

        boolean bTeamVision = game.getOptions().booleanOption(OptionsConstants.ADVANCED_TEAM_VISION);
        List<Entity> vEntities = game.getEntitiesVector();

        Vector<Player> vCanDetect = new Vector<>();

        // If the entity is hidden, skip; no one else will be able to detect it
        if (entity.isHidden() || entity.isOffBoard()) {
            return vCanDetect;
        }

        for (Entity spotter : vEntities) {
            if (!spotter.isActive() || spotter.isOffBoard()
                    || vCanDetect.contains(spotter.getOwner())) {
                continue;
            }
            // See if the LosEffects is cached, and if not cache it
            EntityTargetPair etp = new EntityTargetPair(spotter, entity);
            LosEffects los = losCache.get(etp);
            if (los == null) {
                los = LosEffects.calculateLOS(game, spotter, entity);
                losCache.put(etp, los);
            }
            if (Compute.inSensorRange(game, los, spotter, entity, allECMInfo)) {
                if (!vCanDetect.contains(spotter.getOwner())) {
                    vCanDetect.addElement(spotter.getOwner());
                }
                if (bTeamVision) {
                    addTeammates(vCanDetect, spotter.getOwner());
                }
                addObservers(vCanDetect);
            }
        }

        return vCanDetect;
    }

    /**
     * Adds teammates of a player to the Vector. Utility function for whoCanSee.
     */
    protected void addTeammates(Vector<Player> vector, Player player) {
        Vector<Player> playersVector = game.getPlayersVector();
        for (int j = 0; j < playersVector.size(); j++) {
            Player p = playersVector.elementAt(j);
            if (!player.isEnemyOf(p) && !vector.contains(p)) {
                vector.addElement(p);
            }
        }
    }

    /**
     * Adds observers to the Vector. Utility function for whoCanSee.
     */
    protected void addObservers(Vector<Player> vector) {
        Vector<Player> playersVector = game.getPlayersVector();
        for (int j = 0; j < playersVector.size(); j++) {
            Player p = playersVector.elementAt(j);
            if (p.isObserver() && !vector.contains(p)) {
                vector.addElement(p);
            }
        }
    }

    /**
     * Send the complete list of entities to the players. If double_blind is in
     * effect, enforce it by filtering the entities
     */
    protected void entityAllUpdate() {
        // If double-blind is in effect, filter each players' list individually,
        // and then quit out...
        if (doBlind()) {
            Vector<Player> playersVector = game.getPlayersVector();
            for (int x = 0; x < playersVector.size(); x++) {
                Player p = playersVector.elementAt(x);
                communicationManager.send(p.getId(), packetManager.createFilteredFullEntitiesPacket(p, null, this));
            }
            return;
        }

        // Otherwise, send the full list.
        communicationManager.send(packetManager.createEntitiesPacket(this));
    }

    /**
     * Filters an entity vector according to LOS
     */
    protected List<Entity> filterEntities(Player pViewer,
                                        List<Entity> vEntities,
                                        Map<EntityTargetPair, LosEffects> losCache) {
        if (losCache == null) {
            losCache = new HashMap<>();
        }
        Vector<Entity> vCanSee = new Vector<>();
        Vector<Entity> vMyEntities = new Vector<>();
        boolean bTeamVision = game.getOptions().booleanOption(OptionsConstants.ADVANCED_TEAM_VISION);

        // If they can see all, return the input list
        if (pViewer.canIgnoreDoubleBlind()) {
            return vEntities;
        }

        List<ECMInfo> allECMInfo = null;
        if (game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_SENSORS)) {
            allECMInfo = ComputeECM.computeAllEntitiesECMInfo(game.getEntitiesVector());
        }

        // If they're an observer, they can see anything seen by any enemy.
        if (pViewer.isObserver()) {
            vMyEntities.addAll(vEntities);
            for (Entity a : vMyEntities) {
                for (Entity b : vMyEntities) {
                    if (a.isEnemyOf(b)
                            && Compute.canSee(game, b, a, true, null, allECMInfo)) {
                        addVisibleEntity(vCanSee, a);
                        break;
                    }
                }
            }
            return vCanSee;
        }

        // If they aren't an observer and can't see all, create the list of
        // "friendly" units.
        for (Entity e : vEntities) {
            if ((e.getOwner() == pViewer) || (bTeamVision && !e.getOwner().isEnemyOf(pViewer))) {
                vMyEntities.addElement(e);
            }
        }

        // Then, break down the list by whether they're friendly,
        // or whether or not any friendly unit can see them.
        for (Entity e : vEntities) {
            // If it's their own unit, obviously, they can see it.
            if (vMyEntities.contains(e)) {
                addVisibleEntity(vCanSee, e);
                continue;
            } else if (e.isHidden()) {
                // If it's NOT friendly and is hidden, they can't see it, period.
                // LOS doesn't matter.
                continue;
            } else if (e.isOffBoardObserved(pViewer.getTeam())) {
                // if it's hostile and has been observed for counter-battery fire, we can "see" it
                addVisibleEntity(vCanSee, e);
                continue;
            }

            for (Entity spotter : vMyEntities) {

                // If they're off-board, skip it; they can't see anything.
                if (spotter.isOffBoard()) {
                    continue;
                }

                // See if the LosEffects is cached, and if not cache it
                EntityTargetPair etp = new EntityTargetPair(spotter, e);
                LosEffects los = losCache.get(etp);
                if (los == null) {
                    los = LosEffects.calculateLOS(game, spotter, e);
                    losCache.put(etp, los);
                }
                // Otherwise, if they can see the entity in question
                if (Compute.canSee(game, spotter, e, true, los, allECMInfo)) {
                    addVisibleEntity(vCanSee, e);
                    break;
                }

                // If this unit has ECM, players with units affected by the ECM
                //  will need to know about this entity, even if they can't see
                //  it.  Otherwise, the client can't properly report things
                //  like to-hits.
                if ((e.getECMRange() > 0) && (e.getPosition() != null) &&
                        (spotter.getPosition() != null)) {
                    int ecmRange = e.getECMRange();
                    Coords pos = e.getPosition();
                    if (pos.distance(spotter.getPosition()) <= ecmRange) {
                        addVisibleEntity(vCanSee, e);
                    }
                }
            }
        }

        return vCanSee;
    }

    /**
     * Recursive method to add an <code>Entity</code> and all of its transported
     * units to the list of units visible to a particular player. It is
     * important to ensure that if a unit is in the list of visible units then
     * all of its transported units (and their transported units, and so on) are
     * also considered visible, otherwise it can lead to issues. This method
     * also ensures that no duplicate Entities are added.
     *
     * @param vCanSee A collection of units that can be see
     * @param e       An Entity that is seen and needs to be added to the collection
     *                of seen entities. All of
     */
    protected void addVisibleEntity(Vector<Entity> vCanSee, Entity e) {
        if (!vCanSee.contains(e)) {
            vCanSee.add(e);
        }
        for (Entity transported : e.getLoadedUnits()) {
            addVisibleEntity(vCanSee, transported);
        }
    }

    /**
     * Filter a {@link Report} <code>Vector</code> for double blind.
     *
     * @param originalReportVector the original <code>Vector<Report></code>
     * @param p                    the <code>Player</code> who should see stuff only visible to
     *                             him
     * @return the <code>Vector<Report></code> with stuff only Player p can see
     */
    protected Vector<Report> filterReportVector(Vector<Report> originalReportVector, Player p) {
        // If no double blind, no filtering to do
        if (!doBlind()) {
            return new Vector<>(originalReportVector);
        }
        // But if it is, then filter everything properly.
        Vector<Report> filteredReportVector = new Vector<>();
        for (Report r : originalReportVector) {
            Report filteredReport = filterReport(r, p, false);
            if (filteredReport != null) {
                filteredReportVector.addElement(filteredReport);
            }
        }
        return filteredReportVector;
    }

    /**
     * Filter a single report so that the correct double-blind obscuration takes
     * place. To mark a message as "this should be visible to anyone seeing this
     * entity" set r.subject to the entity id to mark a message as "only visible
     * to the player" set r.player to that player's id and set r.type to
     * Report.PLAYER to mark a message as visible to all, set r.type to
     * Report.PUBLIC
     *
     * @param r         the Report to filter
     * @param p         the Player that we are going to send the filtered report to
     * @param omitCheck boolean indicating that this report happened in the past, so we
     *                  no longer have access to the Player
     * @return a new Report, which has possibly been obscured
     */
    protected Report filterReport(Report r, Player p, boolean omitCheck) {
        if ((r.subject == Entity.NONE) && (r.type != Report.PLAYER) && (r.type != Report.PUBLIC)) {
            // Reports that don't have a subject should be public.
            LogManager.getLogger().error("Attempting to filter a Report object that is not public yet "
                    + "but has no subject.\n\t\tmessageId: " + r.messageId);
            return r;
        }
        if ((r.type == Report.PUBLIC) || ((p == null) && !omitCheck)) {
            return r;
        }
        Entity entity = game.getEntity(r.subject);
        if (entity == null) {
            entity = game.getOutOfGameEntity(r.subject);
        }
        Player owner = null;
        if (entity != null) {
            owner = entity.getOwner();
            // off board (Artillery) units get treated as public messages
            if (entity.isOffBoard()) {
                return r;
            }
        }

        if ((r.type != Report.PLAYER) && !omitCheck
                && ((entity == null) || (owner == null))) {
            LogManager.getLogger().error("Attempting to filter a report object that is not public but has a subject ("
                    + entity + ") with owner (" + owner + ").\n\tmessageId: " + r.messageId);
            return r;
        }

        boolean shouldObscure = omitCheck
                || ((entity != null) && !entity.hasSeenEntity(p))
                || ((r.type == Report.PLAYER) && (p.getId() != r.player));
        // If suppressing double blind messages, don't send this report at all.
        if (game.getOptions()
                .booleanOption(OptionsConstants.ADVANCED_SUPRESS_ALL_DB_MESSAGES)
                && shouldObscure) {
            // Mark the original report to indicate it was filtered
            if (p != null) {
                r.addObscuredRecipient(p.getName());
            }
            return null;
        }
        Report copy = new Report(r);
        // Otherwise, obscure data in the report
        for (int j = 0; j < copy.dataCount(); j++) {
            if (shouldObscure) {
                // This report should be obscured
                if (r.isValueObscured(j)) {
                    copy.hideData(j);
                    // Mark the original report to indicate which players
                    // received an obscured version of it.
                    if (p != null) {
                        r.addObscuredRecipient(p.getName());
                    }
                }
            }
        }

        if (shouldObscure) {
            copy.obsureImg();
        }

        return copy;
    }

    /**
     *
     * @return a vector which has as its keys the round number and as its
     *         elements vectors that contain all the reports for the specified player
     *         that round. The reports returned this way are properly filtered for
     *         double blind.
     */
    protected Vector<Vector<Report>> filterPastReports(
            Vector<Vector<Report>> pastReports, Player p) {
        // Only actually bother with the filtering if double-blind is in effect.
        if (!doBlind()) {
            return pastReports;
        }
        // Perform filtering
        Vector<Vector<Report>> filteredReports = new Vector<>();
        for (Vector<Report> roundReports : pastReports) {
            Vector<Report> filteredRoundReports = new Vector<>();
            for (Report r : roundReports) {
                if (r.isObscuredRecipient(p.getName())) {
                    r = filterReport(r, null, true);
                }
                if (r != null) {
                    filteredRoundReports.addElement(r);
                }
            }
            filteredReports.addElement(filteredRoundReports);
        }
        return filteredReports;
    }

    /**
     * Updates entities graphical "visibility indications" which are used in
     * double-blind games.
     *
     * @param losCache  It can be expensive to have to recompute LoSEffects
     *                  again and again, so in some cases where this may happen,
     *                  the LosEffects are cached.   This can safely be null.
     */
    protected void updateVisibilityIndicator(Map<EntityTargetPair, LosEffects> losCache) {
        if (losCache == null) {
            losCache = new HashMap<>();
        }
        List<ECMInfo> allECMInfo = null;
        if (game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_SENSORS)) {
            allECMInfo = ComputeECM.computeAllEntitiesECMInfo(game
                    .getEntitiesVector());
        }

        List<Entity> vAllEntities = game.getEntitiesVector();
        for (Entity e : vAllEntities) {
            Vector<Player> whoCouldSee = new Vector<>(e.getWhoCanSee());
            Vector<Player> whoCouldDetect = new Vector<>(e.getWhoCanDetect());
            e.setVisibleToEnemy(false);
            e.setDetectedByEnemy(false);
            e.clearSeenBy();
            e.clearDetectedBy();
            Vector<Player> vCanSee = whoCanSee(e, false, losCache);
            // Who can See this unit?
            for (Player p : vCanSee) {
                if (e.getOwner().isEnemyOf(p) && !p.isObserver()) {
                    e.setVisibleToEnemy(true);
                    e.setEverSeenByEnemy(true);
                    // If we can see it, it's detected
                    e.setDetectedByEnemy(true);
                }
                e.addBeenSeenBy(p);
            }
            // Who can Detect this unit?
            Vector<Player> vCanDetect = whoCanDetect(e, allECMInfo, losCache);
            for (Player p : vCanDetect) {
                if (e.getOwner().isEnemyOf(p) && !p.isObserver()) {
                    e.setDetectedByEnemy(true);
                }
                e.addBeenDetectedBy(p);
            }

            // If a client can now see/detect this entity, but couldn't before,
            // then the client needs to be updated with the Entity
            boolean hasClientWithoutEntity = false;
            for (Player p : vCanSee) {
                if (!whoCouldSee.contains(p) && !whoCouldDetect.contains(p)) {
                    hasClientWithoutEntity = true;
                    break;
                }
            }

            if (!hasClientWithoutEntity) {
                for (Player p : vCanDetect) {
                    if (!whoCouldSee.contains(p) && !whoCouldDetect.contains(p)) {
                        hasClientWithoutEntity = true;
                        break;
                    }
                }
            }

            if (hasClientWithoutEntity) {
                entityUpdate(e.getId(), new Vector<>(), false, losCache);
            } else {
                communicationManager.sendVisibilityIndicator(e, this);
            }
        }
    }

    /**
     * Makes one slot of inferno ammo, determined by certain rules, explode on a
     * mech.
     *
     * @param entity
     *            The <code>Entity</code> that should suffer an inferno ammo
     *            explosion.
     */
    protected Vector<Report> explodeInfernoAmmoFromHeat(Entity entity) {
        int damage = 0;
        int rack = 0;
        int boomloc = -1;
        int boomslot = -1;
        Vector<Report> vDesc = new Vector<>();
        Report r;

        // Find the most destructive Inferno ammo.
        for (int j = 0; j < entity.locations(); j++) {
            for (int k = 0; k < entity.getNumberOfCriticals(j); k++) {
                CriticalSlot cs = entity.getCritical(j, k);
                // Ignore empty, destroyed, hit, and structure slots.
                if ((cs == null) || cs.isDestroyed() || cs.isHit()
                        || (cs.getType() != CriticalSlot.TYPE_EQUIPMENT)) {
                    continue;
                }
                // Ignore everything but ammo or LAM bomb bay slots.
                Mounted mounted = cs.getMount();
                int newRack;
                int newDamage;
                if (mounted.getType() instanceof AmmoType) {
                    AmmoType atype = (AmmoType) mounted.getType();
                    if (!atype.isExplosive(mounted)
                            || (!(atype.getMunitionType().contains(AmmoType.Munitions.M_INFERNO))
                            && !(atype.getMunitionType().contains(AmmoType.Munitions.M_IATM_IIW)))) {
                        continue;
                    }
                    // ignore empty, destroyed, or missing bins
                    if (mounted.getHittableShotsLeft() == 0) {
                        continue;
                    }
                    // Find the most destructive undamaged ammo.
                    // TW page 160, compare one rack's
                    // damage. Ties go to most rounds.
                    newRack = atype.getDamagePerShot() * atype.getRackSize();
                    newDamage = mounted.getExplosionDamage();
                    Mounted mount2 = cs.getMount2();
                    if ((mount2 != null) && (mount2.getType() instanceof AmmoType)
                            && (mount2.getHittableShotsLeft() > 0)) {
                        // must be for same weaponType, so rackSize stays
                        atype = (AmmoType) mount2.getType();
                        newRack += atype.getDamagePerShot() * atype.getRackSize();
                        newDamage += mount2.getExplosionDamage();
                    }
                } else if ((mounted.getType() instanceof MiscType)
                        && mounted.getType().hasFlag(MiscType.F_BOMB_BAY)) {
                    while (mounted.getLinked() != null) {
                        mounted = mounted.getLinked();
                    }
                    if (mounted.getExplosionDamage() == 0) {
                        continue;
                    }
                    newRack = 1;
                    newDamage = mounted.getExplosionDamage();
                } else {
                    continue;
                }

                if (!mounted.isHit()
                        && ((rack < newRack) || ((rack == newRack) && (damage < newDamage)))) {
                    rack = newRack;
                    damage = newDamage;
                    boomloc = j;
                    boomslot = k;
                }
            }
        }
        // Did we find anything to explode?
        if ((boomloc != -1) && (boomslot != -1)) {
            CriticalSlot slot = entity.getCritical(boomloc, boomslot);
            slot.setHit(true);
            Mounted equip = slot.getMount();
            equip.setHit(true);
            // We've allocated heatBuildup to heat in resolveHeat(),
            // so need to add to the entity's heat instead.
            if ((equip.getType() instanceof AmmoType)
                    || (equip.getLinked() != null
                    && equip.getLinked().getType() instanceof BombType
                    && ((BombType) equip.getLinked().getType()).getBombType() == BombType.B_INFERNO)) {
                entity.heat += Math.min(equip.getExplosionDamage(), 30);
            }
            vDesc.addAll(entityActionManager.explodeEquipment(entity, boomloc, boomslot, this));
            r = new Report(5155);
            r.indent();
            r.subject = entity.getId();
            r.add(entity.heat);
            vDesc.addElement(r);
            entity.heatBuildup = 0;
        } else { // no ammo to explode
            r = new Report(5160);
            r.indent();
            r.subject = entity.getId();
            vDesc.addElement(r);
        }
        return vDesc;
    }

    /**
     * checks for unintended explosion of heavy industrial zone hex and applies
     * damage to entities occupying the hex
     */
    public void checkExplodeIndustrialZone(Coords c, Vector<Report> vDesc) {
        Report r;
        Hex hex = game.getBoard().getHex(c);
        if (null == hex) {
            return;
        }

        if (!hex.containsTerrain(Terrains.INDUSTRIAL)) {
            return;
        }

        r = new Report(3590, Report.PUBLIC);
        r.add(c.getBoardNum());
        r.indent(2);
        Roll diceRoll = Compute.rollD6(2);
        r.add(8);
        r.add(diceRoll);

        if (diceRoll.getIntValue() > 7) {
            r.choose(true);
            r.newlines = 0;
            vDesc.add(r);
            boolean onFire = false;
            boolean powerLine = false;
            boolean minorExp = false;
            boolean elecExp = false;
            boolean majorExp = false;
            if (diceRoll.getIntValue() == 8) {
                onFire = true;
                r = new Report(3600, Report.PUBLIC);
                r.newlines = 0;
                vDesc.add(r);
            } else if (diceRoll.getIntValue() == 9) {
                powerLine = true;
                r = new Report(3605, Report.PUBLIC);
                r.newlines = 0;
                vDesc.add(r);
            } else if (diceRoll.getIntValue() == 10) {
                minorExp = true;
                onFire = true;
                r = new Report(3610, Report.PUBLIC);
                r.newlines = 0;
                vDesc.add(r);
            } else if (diceRoll.getIntValue() == 11) {
                elecExp = true;
                r = new Report(3615, Report.PUBLIC);
                r.newlines = 0;
                vDesc.add(r);
            } else {
                onFire = true;
                majorExp = true;
                r = new Report(3620, Report.PUBLIC);
                r.newlines = 0;
                vDesc.add(r);
            }
            // apply damage here
            if (powerLine || minorExp || elecExp || majorExp) {
                // cycle through the entities in the hex and apply damage
                for (Entity en : game.getEntitiesVector(c)) {
                    int damage = 3;
                    if (minorExp) {
                        damage = 5;
                    }
                    if (elecExp) {
                        damage = Compute.d6(1) + 3;
                    }
                    if (majorExp) {
                        damage = Compute.d6(2);
                    }
                    HitData hit = en.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                    if (en instanceof BattleArmor) {
                        // ugly - I have to apply damage to each trooper
                        // separately
                        for (int loc = 0; loc < en.locations(); loc++) {
                            if ((IArmorState.ARMOR_NA != en.getInternal(loc))
                                    && (IArmorState.ARMOR_DESTROYED != en.getInternal(loc))
                                    && (IArmorState.ARMOR_DOOMED != en.getInternal(loc))) {
                                vDesc.addAll(damageEntity(en, new HitData(loc), damage));
                            }
                        }
                    } else {
                        vDesc.addAll(damageEntity(en, hit, damage));
                    }
                    if (majorExp) {
                        // lets pretend that the infernos came from the entity
                        // itself (should give us side_front)
                        vDesc.addAll(environmentalEffectManager.deliverInfernoMissiles(en, en, Compute.d6(2), this));
                    }
                }
            }
            Report.addNewline(vDesc);
            if (onFire && !hex.containsTerrain(Terrains.FIRE)) {
                ignite(c, Terrains.FIRE_LVL_NORMAL, vDesc);
            }
        } else {
            // report no explosion
            r.choose(false);
            vDesc.add(r);
        }
    }

    /**
     * Determine the results of an entity moving through a wall of a building
     * after having moved a certain distance. This gets called when a Mech or a
     * Tank enters a building, leaves a building, or travels from one hex to
     * another inside a multi-hex building.
     *
     * @param entity
     *            - the <code>Entity</code> that passed through a wall. Don't
     *            pass <code>Infantry</code> units to this method.
     * @param bldg
     *            - the <code>Building</code> the entity is passing through.
     * @param lastPos
     *            - the <code>Coords</code> of the hex the entity is exiting.
     * @param curPos
     *            - the <code>Coords</code> of the hex the entity is entering
     * @param distance
     *            - the <code>int</code> number of hexes the entity has moved
     *            already this phase.
     * @param why
     *            - the <code>String</code> explanation for this action.
     * @param backwards
     *            - the <code>boolean</code> indicating if the entity is
     *            entering the hex backwards
     * @param entering
     *            - a <code>boolean</code> if the entity is entering or exiting
     *            a building
     */
    protected void passBuildingWall(Entity entity, Building bldg, Coords lastPos, Coords curPos,
                                  int distance, String why, boolean backwards,
                                  EntityMovementType overallMoveType, boolean entering) {
        Report r;

        if (entity instanceof Protomech) {
            Vector<Report> vBuildingReport = damageBuilding(bldg, 1, curPos);
            for (Report report : vBuildingReport) {
                report.subject = entity.getId();
            }
            reportManager.addReport(vBuildingReport, this);
        } else {
            // Need to roll based on building type.
            PilotingRollData psr = entity.rollMovementInBuilding(bldg, distance, why, overallMoveType);

            // Did the entity make the roll?
            if (0 < utilityManager.doSkillCheckWhileMoving(entity, entity.getElevation(), lastPos, curPos, psr, false, this)) {

                // Divide the building's current CF by 10, round up.
                int damage = (int) Math.floor(bldg.getDamageFromScale()
                        * Math.ceil(bldg.getCurrentCF(entering ? curPos : lastPos) / 10.0));

                // Infantry and Battle armor take different amounts of damage
                // then Meks and vehicles.
                if (entity instanceof Infantry) {
                    damage = bldg.getType() + 1;
                }
                // It is possible that the unit takes no damage.
                if (damage == 0) {
                    r = new Report(6440);
                    r.add(entity.getDisplayName());
                    r.subject = entity.getId();
                    r.indent(2);
                    addReport(r);
                } else {
                    // TW, pg. 268: if unit moves forward, damage from front,
                    // if backwards, damage from rear.
                    int side = ToHitData.SIDE_FRONT;
                    if (backwards) {
                        side = ToHitData.SIDE_REAR;
                    }
                    HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, side);
                    hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                    reportManager.addReport(damageEntity(entity, hit, damage), this);
                }
            }

            // Damage the building. The CF can never drop below 0.
            int toBldg;
            // Infantry and BA are damaged by buildings but do not damage them, except large beast-mounted infantry
            if (entity instanceof Infantry) {
                InfantryMount mount = ((Infantry) entity).getMount();
                if ((mount != null) && (mount.getSize().buildingDamage() > 0)) {
                    toBldg = mount.getSize().buildingDamage();
                } else {
                    return;
                }
            } else {
                toBldg = (int) Math.floor(bldg.getDamageToScale()
                        * Math.ceil(entity.getWeight() / 10.0));
            }
            int curCF = bldg.getCurrentCF(entering ? curPos : lastPos);
            curCF -= Math.min(curCF, toBldg);
            bldg.setCurrentCF(curCF, entering ? curPos : lastPos);

            // Apply the correct amount of damage to infantry in the building.
            // ASSUMPTION: We inflict toBldg damage to infantry and
            // not the amount to bring building to 0 CF.
            reportManager.addReport(damageInfantryIn(bldg, toBldg, entering ? curPos : lastPos), this);
        }
    }

    /**
     * check if a building collapses because of a moving entity
     *
     * @param bldg
     *            the <code>Building</code>
     * @param entity
     *            the <code>Entity</code>
     * @param curPos
     *            the <code>Coords</code> of the position of the entity
     * @return a <code>boolean</code> value indicating if the building collapses
     */
    protected boolean checkBuildingCollapseWhileMoving(Building bldg, Entity entity, Coords curPos) {
        Coords oldPos = entity.getPosition();
        // Count the moving entity in its current position, not
        // its pre-move position. Be sure to handle nulls.
        entity.setPosition(curPos);

        // Get the position map of all entities in the game.
        Hashtable<Coords, Vector<Entity>> positionMap = game.getPositionMap();

        // Check for collapse of this building due to overloading, and return.
        boolean rv = checkForCollapse(bldg, positionMap, curPos, true, vPhaseReport);

        // If the entity was not displaced and didn't fall, move it back where it was
        if (curPos.equals(entity.getPosition()) && !entity.isProne()) {
            entity.setPosition(oldPos);
        }
        return rv;
    }

    public Vector<Report> damageInfantryIn(Building bldg, int damage, Coords hexCoords) {
        return damageInfantryIn(bldg, damage, hexCoords, WeaponType.WEAPON_NA);
    }

    /**
     * Apply the correct amount of damage that passes on to any infantry unit in
     * the given building, based upon the amount of damage the building just
     * sustained. This amount is a percentage dictated by pg. 172 of TW.
     *
     * @param bldg   - the <code>Building</code> that sustained the damage.
     * @param damage - the <code>int</code> amount of damage.
     */
    public Vector<Report> damageInfantryIn(Building bldg, int damage, Coords hexCoords,
                                           int infDamageClass) {
        Vector<Report> vDesc = new Vector<>();

        if (bldg == null) {
            return vDesc;
        }
        // Calculate the amount of damage the infantry will sustain.
        float percent = bldg.getDamageReductionFromOutside();
        Report r;

        // Round up at .5 points of damage.
        int toInf = Math.round(damage * percent);

        // some buildings scale remaining damage
        toInf = (int) Math.floor(bldg.getDamageToScale() * toInf);

        // Walk through the entities in the game.
        for (Entity entity : game.getEntitiesVector()) {
            final Coords coords = entity.getPosition();

            // If the entity is infantry in the affected hex?
            if ((entity instanceof Infantry) && bldg.isIn(coords) && coords.equals(hexCoords)) {
                // Is the entity is inside of the building
                // (instead of just on top of it)?
                if (Compute.isInBuilding(game, entity, coords)) {

                    // Report if the infantry receive no points of damage.
                    if (toInf == 0) {
                        r = new Report(6445);
                        r.indent(3);
                        r.subject = entity.getId();
                        r.add(entity.getDisplayName());
                        vDesc.addElement(r);
                    } else {
                        // Yup. Damage the entity.
                        r = new Report(6450);
                        r.indent(3);
                        r.subject = entity.getId();
                        r.add(toInf);
                        r.add(entity.getDisplayName());
                        vDesc.addElement(r);
                        // need to adjust damage to conventional infantry
                        // TW page 217 says left over damage gets treated as
                        // direct fire ballistic damage
                        if (!(entity instanceof BattleArmor)) {
                            toInf = Compute.directBlowInfantryDamage(toInf, 0,
                                    WeaponType.WEAPON_DIRECT_FIRE, false, false);
                        }
                        int remaining = toInf;
                        int cluster = toInf;
                        // Battle Armor units use 5 point clusters.
                        if (entity instanceof BattleArmor) {
                            cluster = 5;
                        }
                        while (remaining > 0) {
                            int next = Math.min(cluster, remaining);
                            HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                            vDesc.addAll((damageEntity(entity, hit, next)));
                            remaining -= next;
                        }
                    }

                    Report.addNewline(vDesc);
                } // End infantry-inside-building
            } // End entity-is-infantry-in-building-hex
        } // Handle the next entity

        return vDesc;
    } // End protected void damageInfantryIn( Building, int )

    /**
     * Determine if the given building should collapse. If so, inflict the
     * appropriate amount of damage on each entity in the building and update
     * the clients. If the building does not collapse, determine if any entities
     * crash through its floor into its basement. Again, apply appropriate
     * damage.
     *
     * @param bldg
     *            - the <code>Building</code> being checked. This value should
     *            not be <code>null</code>.
     * @param positionMap
     *            - a <code>Hashtable</code> that maps the <code>Coords</code>
     *            positions or each unit in the game to a <code>Vector</code> of
     *            <code>Entity</code>s at that position. This value should not
     *            be <code>null</code>.
     * @param coords
     *            - the <code>Coords</code> of the building hex to be checked
     * @return <code>true</code> if the building collapsed.
     */
    public boolean checkForCollapse(Building bldg, Hashtable<Coords, Vector<Entity>> positionMap,
                                    Coords coords, boolean checkBecauseOfDamage,
                                    Vector<Report> vPhaseReport) {

        // If the input is meaningless, do nothing and throw no exception.
        if ((bldg == null) || (positionMap == null) || positionMap.isEmpty()
                || (coords == null) || !bldg.isIn(coords) || !bldg.hasCFIn(coords)) {
            return false;
        }

        // Get the building's current CF.
        int currentCF = bldg.getCurrentCF(coords);

        // Track all units that fall into the building's basement by Coords.
        Hashtable<Coords, Vector<Entity>> basementMap = new Hashtable<>();

        // look for a collapse.
        boolean collapse = false;

        boolean basementCollapse = false;

        boolean topFloorCollapse = false;

        if (checkBecauseOfDamage && (currentCF <= 0)) {
            collapse = true;
        }

        // Get the Vector of Entities at these coordinates.
        final Vector<Entity> vector = positionMap.get(coords);

        // Are there any Entities at these coords?
        if (vector != null) {
            // How many levels does this building have in this hex?
            final Hex curHex = game.getBoard().getHex(coords);
            final int numFloors = Math.max(0, curHex.terrainLevel(Terrains.BLDG_ELEV));
            final int bridgeEl = curHex.terrainLevel(Terrains.BRIDGE_ELEV);
            int numLoads = numFloors;
            if (bridgeEl != Terrain.LEVEL_NONE) {
                numLoads++;
            }
            if (numLoads < 1) {
                LogManager.getLogger().error("Check for collapse: hex " + coords
                        + " has no bridge or building");
                return false;
            }

            // Track the load of each floor (and of the roof) separately.
            // Track all units that fall into the basement in this hex.
            // track all floors, ground at index 0, the first floor is at
            // index 1, the second is at index 1, etc., and the roof is
            // at index (numFloors).
            // if bridge is present, bridge will be numFloors+1
            double[] loads = new double[numLoads + 1];
            // WiGEs flying over the building are also tracked, but can only collapse the top floor
            // and only count 25% of their tonnage.
            double wigeLoad = 0;
            // track all units that might fall into the basement
            Vector<Entity> basement = new Vector<>();

            boolean recheckLoop = true;
            for (int i = 0; (i < 2) && recheckLoop; i++) {
                recheckLoop = false;
                Arrays.fill(loads, 0);

                // Walk through the entities in this position.
                Enumeration<Entity> entities = vector.elements();
                while (!collapse && entities.hasMoreElements()) {
                    final Entity entity = entities.nextElement();
                    // WiGEs can collapse the top floor of a building by flying over it.
                    final int entityElev = entity.getElevation();
                    final boolean wigeFlyover = entity.getMovementMode() == EntityMovementMode.WIGE
                            && entityElev == numFloors + 1;

                    if (entityElev != bridgeEl && !wigeFlyover) {
                        // Ignore entities not *inside* the building
                        if (entityElev > numFloors) {
                            continue;
                        }
                    }

                    // if we're under a bridge, we can't collapse the bridge
                    if (entityElev < bridgeEl) {
                        continue;
                    }

                    if ((entity.getMovementMode() == EntityMovementMode.HYDROFOIL)
                            || (entity.getMovementMode() == EntityMovementMode.NAVAL)
                            || (entity.getMovementMode() == EntityMovementMode.SUBMARINE)
                            || (entity.getMovementMode() == EntityMovementMode.INF_UMU)
                            || entity.hasWorkingMisc(MiscType.F_FULLY_AMPHIBIOUS)) {
                        continue; // under the bridge even at same level
                    }

                    if (entityElev == 0) {
                        basement.add(entity);
                    }

                    // units already in the basement
                    if (entityElev < 0) {
                        continue;
                    }

                    // Add the weight to the correct floor.
                    double load = entity.getWeight();
                    int floor = entityElev;
                    if (floor == bridgeEl) {
                        floor = numLoads;
                    }
                    // Entities on the roof fall to the previous top floor/new roof
                    if (topFloorCollapse && floor == numFloors) {
                        floor--;
                    }

                    if (wigeFlyover) {
                        wigeLoad += load;
                        if (wigeLoad > currentCF * 4) {
                            topFloorCollapse = true;
                            loads[numFloors - 1] += loads[numFloors];
                            loads[numFloors] = 0;
                        }
                    } else {
                        loads[floor] += load;
                        if (loads[floor] > currentCF) {
                            // If the load on any floor but the ground floor
                            // exceeds the building's current CF it collapses.
                            if (floor != 0) {
                                collapse = true;
                            } else if (!bldg.getBasementCollapsed(coords)) {
                                basementCollapse = true;
                            }
                        }
                    } // End increase-load
                } // Handle the next entity.

                // Track all entities that fell into the basement.
                if (basementCollapse) {
                    basementMap.put(coords, basement);
                }

                // did anyone fall into the basement?
                if (!basementMap.isEmpty() && !bldg.getBasement(coords).isNone() && !collapse) {
                    collapseBasement(bldg, basementMap, coords, vPhaseReport);
                    if (currentCF == 0) {
                        collapse = true;
                        recheckLoop = false;
                    } else {
                        recheckLoop = true; // basement collapse might cause a further collapse
                    }
                } else {
                    recheckLoop = false; // don't check again, we didn't change the CF
                }
                if (collapse) {
                    recheckLoop = false;
                    // recheck if the basement collapsed since the basement falls
                    // might trigger a greater collapse.
                }
            } // End have-entities-here
        }

        // Collapse the building if the flag is set.
        if (collapse) {
            Report r = new Report(2375, Report.PUBLIC);
            r.add(bldg.getName());
            vPhaseReport.add(r);

            collapseBuilding(bldg, positionMap, coords, false, vPhaseReport);
        } else if (topFloorCollapse) {
            Report r = new Report(2376, Report.PUBLIC);
            r.add(bldg.getName());
            vPhaseReport.add(r);

            collapseBuilding(bldg, positionMap, coords, false, true, vPhaseReport);
        }

        // Return true if the building collapsed.
        return collapse || topFloorCollapse;

    } // End protected boolean checkForCollapse( Building, Hashtable )

    public void collapseBuilding(Building bldg, Hashtable<Coords, Vector<Entity>> positionMap,
                                 Coords coords, Vector<Report> vPhaseReport) {
        collapseBuilding(bldg, positionMap, coords, true, false, vPhaseReport);
    }

    public void collapseBuilding(Building bldg, Hashtable<Coords, Vector<Entity>> positionMap,
                                 Coords coords, boolean collapseAll, Vector<Report> vPhaseReport) {
        collapseBuilding(bldg, positionMap, coords, collapseAll, false, vPhaseReport);
    }

    /**
     * Collapse a building basement. Inflict the appropriate amount of damage on
     * all entities that fell to the basement. Update all clients.
     *
     * @param bldg
     *            - the <code>Building</code> that has collapsed.
     * @param positionMap
     *            - a <code>Hashtable</code> that maps the <code>Coords</code>
     *            positions or each unit in the game to a <code>Vector</code> of
     *            <code>Entity</code>s at that position. This value should not
     *            be <code>null</code>.
     * @param coords
     *            - The <code>Coords</code> of the building basement hex that
     *            has collapsed
     */
    public void collapseBasement(Building bldg, Hashtable<Coords, Vector<Entity>> positionMap,
                                 Coords coords, Vector<Report> vPhaseReport) {
        if (!bldg.hasCFIn(coords)) {
            return;
        }
        int runningCFTotal = bldg.getCurrentCF(coords);

        // Get the Vector of Entities at these coordinates.
        final Vector<Entity> entities = positionMap.get(coords);

        if (bldg.getBasement(coords).isNone()) {
            return;
        } else {
            bldg.collapseBasement(coords, game.getBoard(), vPhaseReport);
        }

        // Are there any Entities at these coords?
        if (entities != null) {

            // Sort in elevation order
            entities.sort((a, b) -> {
                if (a.getElevation() > b.getElevation()) {
                    return -1;
                } else if (a.getElevation() > b.getElevation()) {
                    return 1;
                }
                return 0;
            });
            // Walk through the entities in this position.
            for (Entity entity : entities) {

                // int floor = entity.getElevation();

                int cfDamage = (int) Math.ceil(Math.round(entity.getWeight() / 10.0));

                // all entities should fall
                // ASSUMPTION: PSR to avoid pilot damage
                PilotingRollData psr = entity.getBasePilotingRoll();
                entity.addPilotingModifierForTerrain(psr, coords);

                // fall into basement
                switch (bldg.getBasement(coords)) {
                    case NONE:
                    case ONE_DEEP_NORMAL_INFANTRY_ONLY:
                        LogManager.getLogger().error(entity.getDisplayName() + " is not falling into " + coords.toString());
                        break;
                    case TWO_DEEP_HEAD:
                    case TWO_DEEP_FEET:
                        LogManager.getLogger().info(entity.getDisplayName() + " is falling 2 floors into " + coords.toString());
                        // Damage is determined by the depth of the basement, so a fall of 0
                        // elevation is correct in this case
                        vPhaseReport.addAll(doEntityFall(entity, coords, 0, Compute.d6(), psr,
                                true, false));
                        runningCFTotal -= cfDamage * 2;
                        break;
                    default:
                        LogManager.getLogger().info(entity.getDisplayName() + " is falling 1 floor into " + coords.toString());
                        // Damage is determined by the depth of the basement, so a fall of 0
                        // elevation is correct in this case
                        vPhaseReport.addAll(doEntityFall(entity, coords, 0, Compute.d6(), psr,
                                true, false));
                        runningCFTotal -= cfDamage;
                        break;
                }

                // Update this entity.
                // ASSUMPTION: this is the correct thing to do.
                entityUpdate(entity.getId());
            } // Handle the next entity.
        }

        // Update the building
        if (runningCFTotal < 0) {
            bldg.setCurrentCF(0, coords);
            bldg.setPhaseCF(0, coords);
        } else {
            bldg.setCurrentCF(runningCFTotal, coords);
            bldg.setPhaseCF(runningCFTotal, coords);
        }
        communicationManager.sendChangedHex(coords, this);
        Vector<Building> buildings = new Vector<>();
        buildings.add(bldg);
        communicationManager.sendChangedBuildings(buildings, this);
    }

    /**
     * Collapse a building hex. Inflict the appropriate amount of damage on all
     * entities in the building. Update all clients.
     *
     * @param bldg
     *            - the <code>Building</code> that has collapsed.
     * @param positionMap
     *            - a <code>Hashtable</code> that maps the <code>Coords</code>
     *            positions or each unit in the game to a <code>Vector</code> of
     *            <code>Entity</code>s at that position. This value should not
     *            be <code>null</code>.
     * @param coords
     *            - The <code>Coords</code> of the building hex that has
     *            collapsed
     * @param collapseAll
     *            - A <code>boolean</code> indicating whether or not this
     *            collapse of a hex should be able to collapse the whole
     *            building
     * @param topFloor
     *            - A <code>boolean</code> indicating that only the top floor collapses
     *              (from a WiGE flying over the top).
     *
     */
    public void collapseBuilding(Building bldg, Hashtable<Coords, Vector<Entity>> positionMap,
                                 Coords coords, boolean collapseAll, boolean topFloor,
                                 Vector<Report> vPhaseReport) {
        // sometimes, buildings that reach CF 0 decide against collapsing
        // but we want them to go away anyway, as a building with CF 0 cannot stand
        final int phaseCF = bldg.hasCFIn(coords) ? bldg.getPhaseCF(coords) : 0;

        // Loop through the hexes in the building, and apply
        // damage to all entities inside or on top of the building.
        Report r;

        // Get the Vector of Entities at these coordinates.
        final Vector<Entity> vector = positionMap.get(coords);

        // Are there any Entities at these coords?
        if (vector != null) {
            // How many levels does this building have in this hex?
            final Hex curHex = game.getBoard().getHex(coords);
            final int bridgeEl = curHex.terrainLevel(Terrains.BRIDGE_ELEV);
            final int numFloors = Math.max(bridgeEl,
                    curHex.terrainLevel(Terrains.BLDG_ELEV));

            // Now collapse the building in this hex, so entities fall to
            // the ground
            if (topFloor && numFloors > 1) {
                curHex.removeTerrain(Terrains.BLDG_ELEV);
                curHex.addTerrain(new Terrain(Terrains.BLDG_ELEV, numFloors - 1));
                communicationManager.sendChangedHex(coords, this);
            } else {
                bldg.setCurrentCF(0, coords);
                bldg.setPhaseCF(0, coords);
                communicationManager.send(packetManager.createCollapseBuildingPacket(coords, this));
                game.getBoard().collapseBuilding(coords);
            }

            // Sort in elevation order
            vector.sort((a, b) -> {
                if (a.getElevation() > b.getElevation()) {
                    return -1;
                } else if (a.getElevation() > b.getElevation()) {
                    return 1;
                }
                return 0;
            });
            // Walk through the entities in this position.
            Enumeration<Entity> entities = vector.elements();
            while (entities.hasMoreElements()) {
                final Entity entity = entities.nextElement();
                // all gun emplacements are simply destroyed
                if (entity instanceof GunEmplacement) {
                    vPhaseReport.addAll(entityActionManager.destroyEntity(entity, "building collapse", this));
                    addNewLines();
                    continue;
                }

                int floor = entity.getElevation();
                // If only the top floor collapses, we only care about units on the top level
                // or on the roof.
                if (topFloor && floor < numFloors - 1) {
                    continue;
                }
                // units trapped in a basement under a collapsing building are
                // destroyed
                if (floor < 0) {
                    vPhaseReport.addAll(entityActionManager.destroyEntity(entity,
                            "Crushed under building rubble", false, false, this));
                }

                // Ignore units above the building / bridge.
                if (floor > numFloors) {
                    continue;
                }

                // Treat units on the roof like
                // they were in the top floor.
                if (floor == numFloors) {
                    floor--;
                }

                // Calculate collapse damage for this entity.
                int damage = (int) Math.floor(bldg.getDamageFromScale()
                        * Math.ceil((phaseCF * (numFloors - floor)) / 10.0));

                // Infantry suffer more damage.
                if (entity instanceof Infantry) {
                    if ((entity instanceof BattleArmor) || ((Infantry) entity).isMechanized()) {
                        damage *= 2;
                    } else {
                        damage *= 3;
                    }
                }

                // Apply collapse damage the entity.
                r = new Report(6455);
                r.indent();
                r.subject = entity.getId();
                r.add(entity.getDisplayName());
                r.add(damage);
                vPhaseReport.add(r);
                int remaining = damage;
                int cluster = damage;
                if ((entity instanceof BattleArmor) || (entity instanceof Mech)
                        || (entity instanceof Tank)) {
                    cluster = 5;
                }
                while (remaining > 0) {
                    int next = Math.min(cluster, remaining);
                    int table;
                    if (entity instanceof Protomech) {
                        table = ToHitData.HIT_SPECIAL_PROTO;
                    } else if (entity.getElevation() == numFloors) {
                        table = ToHitData.HIT_NORMAL;
                    } else {
                        table = ToHitData.HIT_PUNCH;
                    }
                    HitData hit = entity.rollHitLocation(table, ToHitData.SIDE_FRONT);
                    hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                    vPhaseReport.addAll(damageEntity(entity, hit, next));
                    remaining -= next;
                }
                vPhaseReport.add(new Report(1210, Report.PUBLIC));

                // all entities should fall
                floor = entity.getElevation();
                if ((floor > 0) || (floor == bridgeEl)) {
                    // ASSUMPTION: PSR to avoid pilot damage
                    // should use mods for entity damage and
                    // 20+ points of collapse damage (if any).
                    PilotingRollData psr = entity.getBasePilotingRoll();
                    entity.addPilotingModifierForTerrain(psr, coords);
                    if (damage >= 20) {
                        psr.addModifier(1, "20+ damage");
                    }
                    vPhaseReport.addAll(utilityManager.doEntityFallsInto(entity, coords, psr,
                            true, this));
                }
                // Update this entity.
                // ASSUMPTION: this is the correct thing to do.
                entityUpdate(entity.getId());
            }
        } else {
            // Update the building.
            bldg.setCurrentCF(0, coords);
            bldg.setPhaseCF(0, coords);
            communicationManager.send(packetManager.createCollapseBuildingPacket(coords, this));
            game.getBoard().collapseBuilding(coords);
        }
        // if more than half of the hexes are gone, collapse all
        if (bldg.getCollapsedHexCount() > (bldg.getOriginalHexCount() / 2)) {
            for (Enumeration<Coords> coordsEnum = bldg.getCoords(); coordsEnum.hasMoreElements();) {
                coords = coordsEnum.nextElement();
                collapseBuilding(bldg, game.getPositionMap(), coords, false, vPhaseReport);
            }
        }
    }

    /**
     * Apply this phase's damage to all buildings. Buildings may collapse due to
     * damage.
     */
    void applyBuildingDamage() {

        // Walk through the buildings in the game.
        // Build the collapse and update vectors as you go.
        // N.B. never, NEVER, collapse buildings while you are walking through
        // the Enumeration from megamek.common.Board#getBuildings.
        Map<Building, Vector<Coords>> collapse = new HashMap<>();
        Map<Building, Vector<Coords>> update = new HashMap<>();
        Enumeration<Building> buildings = game.getBoard().getBuildings();
        while (buildings.hasMoreElements()) {
            Building bldg = buildings.nextElement();
            Vector<Coords> collapseCoords = new Vector<>();
            Vector<Coords> updateCoords = new Vector<>();
            Enumeration<Coords> buildingCoords = bldg.getCoords();
            while (buildingCoords.hasMoreElements()) {
                Coords coords = buildingCoords.nextElement();
                // If the CF is zero, the building should fall.
                if (bldg.getCurrentCF(coords) == 0) {
                    collapseCoords.addElement(coords);
                }
                // If the building took damage this round, update it.
                else if (bldg.getPhaseCF(coords) != bldg.getCurrentCF(coords)) {
                    bldg.setPhaseCF(bldg.getCurrentCF(coords), coords);
                    updateCoords.addElement(coords);
                }
            }
            collapse.put(bldg, collapseCoords);
            update.put(bldg, updateCoords);
        } // Handle the next building

        // If we have any buildings to collapse, collapse them now.
        if (!collapse.isEmpty()) {

            // Get the position map of all entities in the game.
            Hashtable<Coords, Vector<Entity>> positionMap = game
                    .getPositionMap();

            // Walk through the hexes that have collapsed.
            for (Building bldg : collapse.keySet()) {
                Vector<Coords> coordsVector = collapse.get(bldg);
                for (Coords coords : coordsVector) {
                    Report r = new Report(6460, Report.PUBLIC);
                    r.add(bldg.getName());
                    addReport(r);
                    collapseBuilding(bldg, positionMap, coords, vPhaseReport);
                }
            }
        }

        // check for buildings which should collapse due to being overloaded now
        // CF is reduced
        if (!update.isEmpty()) {
            Hashtable<Coords, Vector<Entity>> positionMap = game.getPositionMap();
            for (Building bldg : update.keySet()) {
                Vector<Coords> updateCoords = update.get(bldg);
                Vector<Coords> coordsToRemove = new Vector<>();
                for (Coords coords : updateCoords) {
                    if (checkForCollapse(bldg, positionMap, coords, false,
                            vPhaseReport)) {
                        coordsToRemove.add(coords);
                    }
                }
                updateCoords.removeAll(coordsToRemove);
                update.put(bldg, updateCoords);
            }
        }

        // If we have any buildings to update, send the message.
        if (!update.isEmpty()) {
            communicationManager.sendChangedBuildings(new Vector<>(update.keySet()), this);
        }
    }

    /**
     * Apply the given amount of damage to the building. Please note, this
     * method does <b>not</b> apply any damage to units inside the building,
     * update the clients, or check for the building's collapse.
     * <p>
     * A default message will be used to describe why the building took the
     * damage.
     *
     * @param bldg   - the <code>Building</code> that has been damaged. This value
     *               should not be <code>null</code>, but no exception will occur.
     * @param damage - the <code>int</code> amount of damage.
     * @param coords - the <code>Coords</code> of the building hex to be damaged
     * @return a <code>Report</code> to be shown to the players.
     */
    public Vector<Report> damageBuilding(Building bldg, int damage,
                                         Coords coords) {
        final String defaultWhy = " absorbs ";
        return damageBuilding(bldg, damage, defaultWhy, coords);
    }

    /**
     * Apply the given amount of damage to the building. Please note, this
     * method does <b>not</b> apply any damage to units inside the building,
     * update the clients, or check for the building's collapse.
     *
     * @param bldg   - the <code>Building</code> that has been damaged. This value
     *               should not be <code>null</code>, but no exception will occur.
     * @param damage - the <code>int</code> amount of damage.
     * @param why    - the <code>String</code> message that describes why the
     *               building took the damage.
     * @param coords - the <code>Coords</code> of the building hex to be damaged
     * @return a <code>Report</code> to be shown to the players.
     */
    public Vector<Report> damageBuilding(Building bldg, int damage, String why, Coords coords) {
        Vector<Report> vPhaseReport = new Vector<>();
        Report r = new Report(1210, Report.PUBLIC);

        // Do nothing if no building or no damage was passed.
        if ((bldg != null) && (damage > 0)) {
            r.messageId = 3435;
            r.add(bldg.toString());
            r.add(why);
            r.add(damage);
            vPhaseReport.add(r);
            int curArmor = bldg.getArmor(coords);
            if (curArmor >= damage) {
                curArmor -= Math.min(curArmor, damage);
                bldg.setArmor(curArmor, coords);
                r = new Report(3436, Report.PUBLIC);
                r.indent(0);
                r.add(damage);
                r.add(curArmor);
                vPhaseReport.add(r);
            } else {
                r.add(damage);
                if (curArmor > 0) {
                    bldg.setArmor(0, coords);
                    damage = damage - curArmor;
                    r = new Report(3436, Report.PUBLIC);
                    r.indent(0);
                    r.add(curArmor);
                    r.add(0);
                    vPhaseReport.add(r);
                }
                damage = (int) Math.floor(bldg.getDamageToScale() * damage);
                if (bldg.getDamageToScale() < 1.0) {
                    r = new Report(3437, Report.PUBLIC);
                    r.indent(0);
                    r.add(damage);
                    vPhaseReport.add(r);
                }
                if (bldg.getDamageToScale() > 1.0) {
                    r = new Report(3438, Report.PUBLIC);
                    r.indent(0);
                    r.add(damage);
                    vPhaseReport.add(r);
                }
                int curCF = bldg.getCurrentCF(coords);
                final int startingCF = curCF;
                curCF -= Math.min(curCF, damage);
                bldg.setCurrentCF(curCF, coords);

                r = new Report(6436, Report.PUBLIC);
                r.indent(1);
                if (curCF <= 0) {
                    r.add(r.warning(String.valueOf(curCF)));
                } else {
                    r.add(curCF);
                }
                vPhaseReport.add(r);

                final int damageThresh = (int) Math.ceil(bldg.getPhaseCF(coords) / 10.0);

                // If the CF is zero, the building should fall.
                if ((curCF == 0) && (startingCF != 0)) {
                    if (bldg instanceof FuelTank) {
                        // If this is a fuel tank, we'll give it its own
                        // message.
                        r = new Report(3441);
                        r.type = Report.PUBLIC;
                        r.indent(0);
                        vPhaseReport.add(r);
                        // ...But we ALSO need to blow up everything nearby.
                        // Bwahahahahaha...
                        r = new Report(3560);
                        r.type = Report.PUBLIC;
                        r.newlines = 1;
                        vPhaseReport.add(r);
                        Vector<Report> vRep = new Vector<>();
                        doExplosion(((FuelTank) bldg).getMagnitude(), 10,
                                false, bldg.getCoords().nextElement(), true,
                                vRep, null, -1);
                        Report.indentAll(vRep, 2);
                        vPhaseReport.addAll(vRep);
                        return vPhaseReport;
                    }
                    if (bldg.getType() == Building.WALL) {
                        r = new Report(3442);
                        r.type = Report.PUBLIC;
                        r.indent(0);
                        vPhaseReport.add(r);
                    } else {
                        r = new Report(3440);
                        r.type = Report.PUBLIC;
                        r.indent(0);
                        vPhaseReport.add(r);
                    }
                } else if ((curCF < startingCF) && (damage > damageThresh)) {
                    // need to check for crits
                    // don't bother unless we have some gun emplacements
                    Vector<GunEmplacement> guns = game.getGunEmplacements(coords);
                    if (!guns.isEmpty()) {
                        vPhaseReport.addAll(criticalGunEmplacement(guns, bldg, coords));
                    }
                }
            }
        }
        Report.indentAll(vPhaseReport, 2);
        return vPhaseReport;
    }

    protected Vector<Report> criticalGunEmplacement(Vector<GunEmplacement> guns, Building bldg,
                                                  Coords coords) {
        Vector<Report> vDesc = new Vector<>();
        Report r;
        r = new Report(3800);
        r.type = Report.PUBLIC;
        r.indent(0);
        vDesc.add(r);

        int critRoll = Compute.d6(2);
        if (critRoll < 6) {
            r = new Report(3805);
            r.type = Report.PUBLIC;
            r.indent(1);
            vDesc.add(r);
        } else if (critRoll == 6) {
            // weapon malfunction
            // lets just randomly determine which weapon gets hit
            Vector<Mounted> wpns = new Vector<>();
            for (GunEmplacement gun : guns) {
                for (Mounted wpn : gun.getWeaponList()) {
                    if (!wpn.isHit() && !wpn.isJammed()
                            && !wpn.jammedThisPhase()) {
                        wpns.add(wpn);
                    }
                }
            }

            if (!wpns.isEmpty()) {
                Mounted weapon = wpns.elementAt(Compute.randomInt(wpns.size()));
                weapon.setJammed(true);
                ((GunEmplacement) weapon.getEntity()).addJammedWeapon(weapon);
                r = new Report(3845);
                r.type = Report.PUBLIC;
                r.indent(1);
                r.add(weapon.getDesc());
            } else {
                r = new Report(3846);
                r.type = Report.PUBLIC;
                r.indent(1);
            }
            vDesc.add(r);
        } else if (critRoll == 7) {
            // gunners stunned
            for (GunEmplacement gun : guns) {
                gun.stunCrew();
                r = new Report(3810);
                r.type = Report.PUBLIC;
                r.indent(1);
                vDesc.add(r);
            }
        } else if (critRoll == 8) {
            // weapon destroyed
            // lets just randomly determine which weapon gets hit
            Vector<Mounted> wpns = new Vector<>();
            for (GunEmplacement gun : guns) {
                for (Mounted wpn : gun.getWeaponList()) {
                    if (!wpn.isHit()) {
                        wpns.add(wpn);
                    }
                }
            }

            if (!wpns.isEmpty()) {
                Mounted weapon = wpns.elementAt(Compute.randomInt(wpns.size()));
                weapon.setHit(true);
                r = new Report(3840);
                r.type = Report.PUBLIC;
                r.indent(1);
                r.add(weapon.getDesc());
            } else {
                r = new Report(3841);
                r.type = Report.PUBLIC;
                r.indent(1);
            }
            vDesc.add(r);
        } else if (critRoll == 9) {
            // gunners killed
            r = new Report(3815);
            r.type = Report.PUBLIC;
            r.indent(1);
            vDesc.add(r);
            for (GunEmplacement gun : guns) {
                gun.getCrew().setDoomed(true);
            }
        } else if (critRoll == 10) {
            if (Compute.d6() > 3) {
                // turret lock
                r = new Report(3820);
                r.type = Report.PUBLIC;
                r.indent(1);
                vDesc.add(r);
                for (GunEmplacement gun : guns) {
                    gun.lockTurret(gun.getLocTurret());
                }
            } else {
                // turret jam
                r = new Report(3825);
                r.type = Report.PUBLIC;
                r.indent(1);
                vDesc.add(r);
                for (GunEmplacement gun : guns) {
                    if (gun.isTurretEverJammed(gun.getLocTurret())) {
                        gun.lockTurret(gun.getLocTurret());
                    } else {
                        gun.jamTurret(gun.getLocTurret());
                    }
                }
            }
        } else if (critRoll == 11) {
            r = new Report(3830);
            r.type = Report.PUBLIC;
            r.indent(1);
            r.add(bldg.getName());
            int boom = 0;
            for (GunEmplacement gun : guns) {
                for (Mounted ammo : gun.getAmmo()) {
                    ammo.setHit(true);
                    if (ammo.getType().isExplosive(ammo)) {
                        boom += ammo.getHittableShotsLeft()
                                * ((AmmoType) ammo.getType())
                                .getDamagePerShot()
                                * ((AmmoType) ammo.getType()).getRackSize();
                    }
                }
            }
            boom = (int) Math.floor(bldg.getDamageToScale() * boom);

            if (boom == 0) {
                Report rNoAmmo = new Report(3831);
                rNoAmmo.type = Report.PUBLIC;
                rNoAmmo.indent(1);
                vDesc.add(rNoAmmo);
                return vDesc;
            }

            r.add(boom);
            int curCF = bldg.getCurrentCF(coords);
            curCF -= Math.min(curCF, boom);
            bldg.setCurrentCF(curCF, coords);
            r.add(bldg.getCurrentCF(coords));
            vDesc.add(r);
            // If the CF is zero, the building should fall.
            if ((curCF == 0) && (bldg.getPhaseCF(coords) != 0)) {

                // when a building collapses due to an ammo explosion, we can consider
                // that turret annihilated for the purposes of salvage.
                for (GunEmplacement gun : guns) {
                    vDesc.addAll(entityActionManager.destroyEntity(gun, "ammo explosion", false, false, this));
                }

                if (bldg instanceof FuelTank) {
                    // If this is a fuel tank, we'll give it its own
                    // message.
                    r = new Report(3441);
                    r.type = Report.PUBLIC;
                    r.indent(0);
                    vDesc.add(r);
                    // ...But we ALSO need to blow up everything nearby.
                    // Bwahahahahaha...
                    r = new Report(3560);
                    r.type = Report.PUBLIC;
                    r.newlines = 1;
                    vDesc.add(r);
                    Vector<Report> vRep = new Vector<>();
                    doExplosion(((FuelTank) bldg).getMagnitude(), 10, false,
                            bldg.getCoords().nextElement(), true, vRep, null,
                            -1);
                    Report.indentAll(vRep, 2);
                    vDesc.addAll(vRep);
                    return vPhaseReport;
                }
                if (bldg.getType() == Building.WALL) {
                    r = new Report(3442);
                } else {
                    r = new Report(3440);
                }
                r.type = Report.PUBLIC;
                r.indent(0);
                vDesc.add(r);
            }
        } else if (critRoll == 12) {
            // non-weapon equipment is hit
            Vector<Mounted> equipmentList = new Vector<>();
            for (GunEmplacement gun : guns) {
                for (Mounted equipment : gun.getMisc()) {
                    if (!equipment.isHit()) {
                        equipmentList.add(equipment);
                    }
                }
            }

            if (!equipmentList.isEmpty()) {
                Mounted equipment = equipmentList.elementAt(Compute.randomInt(equipmentList.size()));
                equipment.setHit(true);
                r = new Report(3840);
                r.type = Report.PUBLIC;
                r.indent(1);
                r.add(equipment.getDesc());
            } else {
                r = new Report(3835);
                r.type = Report.PUBLIC;
                r.indent(1);
            }
            vDesc.add(r);
        }

        return vDesc;
    }

    /**
     * For all current artillery attacks in the air from this entity with this
     * weapon, clear the list of spotters. Needed because firing another round
     * before first lands voids spotting.
     *
     * @param entityID the <code>int</code> id of the entity
     * @param weaponID the <code>int</code> id of the weapon
     */
    protected void clearArtillerySpotters(int entityID, int weaponID) {
        for (Enumeration<AttackHandler> i = game.getAttacks(); i.hasMoreElements(); ) {
            WeaponHandler wh = (WeaponHandler) i.nextElement();
            if ((wh.waa instanceof ArtilleryAttackAction)
                    && (wh.waa.getEntityId() == entityID)
                    && (wh.waa.getWeaponId() == weaponID)) {
                ArtilleryAttackAction aaa = (ArtilleryAttackAction) wh.waa;
                aaa.setSpotterIds(null);
            }
        }
    }

    /**
     * Credits a Kill for an entity, if the target got killed.
     *
     * @param target   The <code>Entity</code> that got killed.
     * @param attacker The <code>Entity</code> that did the killing, which may be null
     */
    public void creditKill(final Entity target, @Nullable Entity attacker) {
        // Kills should be credited for each individual fighter, instead of the squadron
        if (target instanceof FighterSquadron) {
            return;
        }

        // If a squadron scores a kill, assign it randomly to one of the member fighters... provided
        // one still lives that is.
        if (attacker instanceof FighterSquadron) {
            attacker = attacker.getLoadedUnits().isEmpty() ? null
                    : attacker.getLoadedUnits().get(Compute.randomInt(attacker.getLoadedUnits().size()));
        }

        if ((attacker != null) && (target.isDoomed() || target.getCrew().isDoomed())
                && !target.getGaveKillCredit()) {
            attacker.addKill(target);
        }
    }

    /**
     * pre-treats a physical attack
     *
     * @param aaa The <code>AbstractAttackAction</code> of the physical attack
     *            to pre-treat
     * @return The <code>PhysicalResult</code> of that action, including
     * possible damage.
     */
    protected PhysicalResult preTreatPhysicalAttack(AbstractAttackAction aaa) {
        final Entity ae = game.getEntity(aaa.getEntityId());
        int damage = 0;
        PhysicalResult pr = new PhysicalResult();
        ToHitData toHit = new ToHitData();
        if (aaa instanceof PhysicalAttackAction && ae.getCrew() != null) {
            pr.roll = ae.getCrew().rollPilotingSkill();
        } else {
            pr.roll = Compute.rollD6(2);
        }
        pr.aaa = aaa;
        if (aaa instanceof BrushOffAttackAction) {
            BrushOffAttackAction baa = (BrushOffAttackAction) aaa;
            int arm = baa.getArm();
            baa.setArm(BrushOffAttackAction.LEFT);
            toHit = BrushOffAttackAction.toHit(game, aaa.getEntityId(),
                    aaa.getTarget(game), BrushOffAttackAction.LEFT);
            baa.setArm(BrushOffAttackAction.RIGHT);
            pr.toHitRight = BrushOffAttackAction.toHit(game, aaa.getEntityId(),
                    aaa.getTarget(game), BrushOffAttackAction.RIGHT);
            damage = BrushOffAttackAction.getDamageFor(ae, BrushOffAttackAction.LEFT);
            pr.damageRight = BrushOffAttackAction.getDamageFor(ae, BrushOffAttackAction.RIGHT);
            baa.setArm(arm);
            if (ae.getCrew() != null) {
                pr.rollRight = ae.getCrew().rollPilotingSkill();
            } else {
                pr.rollRight = Compute.rollD6(2);
            }
        } else if (aaa instanceof ChargeAttackAction) {
            ChargeAttackAction caa = (ChargeAttackAction) aaa;
            toHit = caa.toHit(game);
            Entity target = (Entity) caa.getTarget(game);

            if (target != null ) {
                if (caa.getTarget(game) instanceof Entity) {
                    damage = ChargeAttackAction.getDamageFor(ae, target, game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_CHARGE_DAMAGE), toHit.getMoS());
                } else {
                    damage = ChargeAttackAction.getDamageFor(ae);
                }
            }
            else {
                damage = 0;
            }
        } else if (aaa instanceof AirmechRamAttackAction) {
            AirmechRamAttackAction raa = (AirmechRamAttackAction) aaa;
            toHit = raa.toHit(game);
            damage = AirmechRamAttackAction.getDamageFor(ae);
        } else if (aaa instanceof ClubAttackAction) {
            ClubAttackAction caa = (ClubAttackAction) aaa;
            toHit = caa.toHit(game);
            damage = ClubAttackAction.getDamageFor(ae, caa.getClub(),
                    caa.getTarget(game).isConventionalInfantry(), caa.isZweihandering());
            if (caa.getTargetType() == Targetable.TYPE_BUILDING) {
                EquipmentType clubType = caa.getClub().getType();
                if (clubType.hasSubType(MiscType.S_BACKHOE)
                        || clubType.hasSubType(MiscType.S_CHAINSAW)
                        || clubType.hasSubType(MiscType.S_MINING_DRILL)
                        || clubType.hasSubType(MiscType.S_PILE_DRIVER)) {
                    damage += Compute.d6(1);
                } else if (clubType.hasSubType(MiscType.S_DUAL_SAW)) {
                    damage += Compute.d6(2);
                } else if (clubType.hasSubType(MiscType.S_ROCK_CUTTER)) {
                    damage += Compute.d6(3);
                }
                else if (clubType.hasSubType(MiscType.S_WRECKING_BALL)) {
                    damage += Compute.d6(4);
                }
            }
        } else if (aaa instanceof DfaAttackAction) {
            DfaAttackAction daa = (DfaAttackAction) aaa;
            toHit = daa.toHit(game);
            Entity target = (Entity) daa.getTarget(game);

            if (target != null) {
                damage = DfaAttackAction.getDamageFor(ae, daa.getTarget(game).isConventionalInfantry());
            }
            else {
                damage = 0;
            }
        } else if (aaa instanceof KickAttackAction) {
            KickAttackAction kaa = (KickAttackAction) aaa;
            toHit = kaa.toHit(game);
            damage = KickAttackAction.getDamageFor(ae, kaa.getLeg(),
                    kaa.getTarget(game).isConventionalInfantry());
        } else if (aaa instanceof ProtomechPhysicalAttackAction) {
            ProtomechPhysicalAttackAction paa = (ProtomechPhysicalAttackAction) aaa;
            toHit = paa.toHit(game);
            damage = ProtomechPhysicalAttackAction.getDamageFor(ae, paa.getTarget(game));
        } else if (aaa instanceof PunchAttackAction) {
            PunchAttackAction paa = (PunchAttackAction) aaa;
            int arm = paa.getArm();
            int damageRight;
            paa.setArm(PunchAttackAction.LEFT);
            toHit = paa.toHit(game);
            paa.setArm(PunchAttackAction.RIGHT);
            ToHitData toHitRight = paa.toHit(game);
            damage = PunchAttackAction.getDamageFor(ae, PunchAttackAction.LEFT,
                    paa.getTarget(game).isConventionalInfantry(), paa.isZweihandering());
            damageRight = PunchAttackAction.getDamageFor(ae, PunchAttackAction.RIGHT,
                    paa.getTarget(game).isConventionalInfantry(), paa.isZweihandering());
            paa.setArm(arm);
            // If we're punching while prone (at a Tank,
            // duh), then we can only use one arm.
            if (ae.isProne()) {
                double oddsLeft = Compute.oddsAbove(toHit.getValue(),
                        ae.hasAbility(OptionsConstants.PILOT_APTITUDE_PILOTING));
                double oddsRight = Compute.oddsAbove(toHitRight.getValue(),
                        ae.hasAbility(OptionsConstants.PILOT_APTITUDE_PILOTING));
                // Use the best attack.
                if ((oddsLeft * damage) > (oddsRight * damageRight)) {
                    paa.setArm(PunchAttackAction.LEFT);
                } else {
                    paa.setArm(PunchAttackAction.RIGHT);
                }
            }
            pr.damageRight = damageRight;
            pr.toHitRight = toHitRight;
            if (ae.getCrew() != null) {
                pr.rollRight = ae.getCrew().rollPilotingSkill();
            } else {
                pr.rollRight = Compute.rollD6(2);
            }
        } else if (aaa instanceof PushAttackAction) {
            PushAttackAction paa = (PushAttackAction) aaa;
            toHit = paa.toHit(game);
        } else if (aaa instanceof TripAttackAction) {
            TripAttackAction paa = (TripAttackAction) aaa;
            toHit = paa.toHit(game);
        } else if (aaa instanceof LayExplosivesAttackAction) {
            LayExplosivesAttackAction leaa = (LayExplosivesAttackAction) aaa;
            toHit = leaa.toHit(game);
            damage = LayExplosivesAttackAction.getDamageFor(ae);
        } else if (aaa instanceof ThrashAttackAction) {
            ThrashAttackAction taa = (ThrashAttackAction) aaa;
            toHit = taa.toHit(game);
            damage = ThrashAttackAction.getDamageFor(ae);
        } else if (aaa instanceof JumpJetAttackAction) {
            JumpJetAttackAction jaa = (JumpJetAttackAction) aaa;
            toHit = jaa.toHit(game);
            if (jaa.getLeg() == JumpJetAttackAction.BOTH) {
                damage = JumpJetAttackAction.getDamageFor(ae, JumpJetAttackAction.LEFT);
                pr.damageRight = JumpJetAttackAction.getDamageFor(ae, JumpJetAttackAction.LEFT);
            } else {
                damage = JumpJetAttackAction.getDamageFor(ae, jaa.getLeg());
                pr.damageRight = 0;
            }
            ae.heatBuildup += (damage + pr.damageRight) / 3;
        } else if (aaa instanceof GrappleAttackAction) {
            GrappleAttackAction taa = (GrappleAttackAction) aaa;
            toHit = taa.toHit(game);
        } else if (aaa instanceof BreakGrappleAttackAction) {
            BreakGrappleAttackAction taa = (BreakGrappleAttackAction) aaa;
            toHit = taa.toHit(game);
        } else if (aaa instanceof RamAttackAction) {
            RamAttackAction raa = (RamAttackAction) aaa;
            toHit = raa.toHit(game);
            damage = RamAttackAction.getDamageFor((IAero) ae, (Entity) aaa.getTarget(game));
        } else if (aaa instanceof TeleMissileAttackAction) {
            TeleMissileAttackAction taa = (TeleMissileAttackAction) aaa;
            utilityManager.assignTeleMissileAMS(taa, this);
            taa.calcCounterAV(game, taa.getTarget(game));
            toHit = taa.toHit(game);
            damage = TeleMissileAttackAction.getDamageFor(ae);
        } else if (aaa instanceof BAVibroClawAttackAction) {
            BAVibroClawAttackAction bvca = (BAVibroClawAttackAction) aaa;
            toHit = bvca.toHit(game);
            damage = BAVibroClawAttackAction.getDamageFor(ae);
        }
        pr.toHit = toHit;
        pr.damage = damage;
        return pr;
    }

    /**
     * Add any extreme gravity PSRs the entity gets due to its movement
     *
     * @param entity
     *            The <code>Entity</code> to check.
     * @param step
     *            The last <code>MoveStep</code> of this entity
     * @param moveType
     *            The movement type for the MovePath the supplied MoveStep comes
     *            from. This generally comes from the last step in the move
     *            path.
     * @param curPos
     *            The current <code>Coords</code> of this entity
     * @param cachedMaxMPExpenditure
     *            Server checks run/jump MP at start of move, as appropriate,
     *            caches to avoid mid-move change in MP causing erroneous grav
     *            check
     */
    protected void checkExtremeGravityMovement(Entity entity, MoveStep step,
                                             EntityMovementType moveType, Coords curPos,
                                             int cachedMaxMPExpenditure) {
        PilotingRollData rollTarget;
        if (game.getPlanetaryConditions().getGravity() != 1) {
            if ((entity instanceof Mech) || (entity instanceof Tank)) {
                if ((moveType == EntityMovementType.MOVE_WALK)
                        || (moveType == EntityMovementType.MOVE_VTOL_WALK)
                        || (moveType == EntityMovementType.MOVE_RUN)
                        || (moveType == EntityMovementType.MOVE_SPRINT)
                        || (moveType == EntityMovementType.MOVE_VTOL_RUN)
                        || (moveType == EntityMovementType.MOVE_VTOL_SPRINT)) {
                    int limit = cachedMaxMPExpenditure;
                    if (step.isOnlyPavement() && entity.isEligibleForPavementBonus()) {
                        limit++;
                    }
                    if (step.getMpUsed() > limit) {
                        // We moved too fast, let's make PSR to see if we get
                        // damage
                        game.addExtremeGravityPSR(entity.checkMovedTooFast(
                                step, moveType));
                    }
                } else if (moveType == EntityMovementType.MOVE_JUMP) {
                    LogManager.getLogger().debug("Gravity move check jump: "
                            + step.getMpUsed() + "/" + cachedMaxMPExpenditure);
                    int origWalkMP = entity.getWalkMP(MPCalculationSetting.NO_GRAVITY);
                    int gravWalkMP = entity.getWalkMP();
                    if (step.getMpUsed() > cachedMaxMPExpenditure) {
                        // Jumped too far, make PSR to see if we get damaged
                        game.addExtremeGravityPSR(entity.checkMovedTooFast(
                                step, moveType));
                    } else if ((game.getPlanetaryConditions().getGravity() > 1)
                            && ((origWalkMP - gravWalkMP) > 0)) {
                        // jumping in high g is bad for your legs
                        // Damage dealt = 1 pt for each MP lost due to gravity
                        // Ignore this if no damage would be dealt
                        rollTarget = entity.getBasePilotingRoll(moveType);
                        entity.addPilotingModifierForTerrain(rollTarget, step);
                        int gravMod = game.getPlanetaryConditions()
                                .getGravityPilotPenalty();
                        if ((gravMod != 0) && !game.getBoard().inSpace()) {
                            rollTarget.addModifier(gravMod, game
                                    .getPlanetaryConditions().getGravity()
                                    + "G gravity");
                        }
                        rollTarget.append(new PilotingRollData(entity.getId(),
                                0, "jumped in high gravity"));
                        game.addExtremeGravityPSR(rollTarget);
                    }
                }
            }
        }
    }

    /**
     * Damage the inner structure of a mech's leg / a tank's front. This only
     * happens when the Entity fails an extreme Gravity PSR.
     *
     * @param entity The <code>Entity</code> to damage.
     * @param damage The <code>int</code> amount of damage.
     */
    protected Vector<Report> doExtremeGravityDamage(Entity entity, int damage) {
        Vector<Report> vPhaseReport = new Vector<>();
        HitData hit;
        if (entity instanceof BipedMech) {
            for (int i = 6; i <= 7; i++) {
                hit = new HitData(i);
                vPhaseReport.addAll(damageEntity(entity, hit, damage, false,
                        DamageType.NONE, true));
            }
        }
        if (entity instanceof QuadMech) {
            for (int i = 4; i <= 7; i++) {
                hit = new HitData(i);
                vPhaseReport.addAll(damageEntity(entity, hit, damage, false,
                        DamageType.NONE, true));
            }
        } else if (entity instanceof Tank) {
            hit = new HitData(Tank.LOC_FRONT);
            vPhaseReport.addAll(damageEntity(entity, hit, damage, false,
                    DamageType.NONE, true));
            vPhaseReport.addAll(vehicleMotiveDamage((Tank) entity, 0));
        }
        return vPhaseReport;
    }

    /**
     * Eject an Entity.
     *
     * @param entity    The <code>Entity</code> to eject.
     * @param autoEject The <code>boolean</code> state of the entity's auto- ejection
     *                  system
     * @return a <code>Vector</code> of report objects for the game log.
     */
    public Vector<Report> ejectEntity(Entity entity, boolean autoEject) {
        return ejectEntity(entity, autoEject, false);
    }

    /**
     * Eject an Entity.
     *
     * @param entity            The <code>Entity</code> to eject.
     * @param autoEject         The <code>boolean</code> state of the entity's auto- ejection
     *                          system
     * @param skin_of_the_teeth Perform a skin of the teeth ejection
     * @return a <code>Vector</code> of report objects for the game log.
     */
    public Vector<Report> ejectEntity(Entity entity, boolean autoEject,
                                      boolean skin_of_the_teeth) {
        Vector<Report> vDesc = new Vector<>();
        Report r;

        // An entity can only eject it's crew once.
        if (entity.getCrew().isEjected()) {
            return vDesc;
        }

        // If the crew are already dead, don't bother
        if (entity.isCarcass()) {
            return vDesc;
        }

        // Mek and fighter pilots may get hurt during ejection,
        // and run around the board afterwards.
        if (entity instanceof Mech || entity.isFighter()) {
            int facing = entity.getFacing();
            Coords targetCoords = (null != entity.getPosition())
                    ? entity.getPosition().translated((facing + 3) % 6) : null;
            if (entity.isSpaceborne() && entity.getPosition() != null) {
                //Pilots in space should eject into the fighter's hex, not behind it
                targetCoords = entity.getPosition();
            }

            if (autoEject) {
                r = new Report(6395);
                r.subject = entity.getId();
                r.addDesc(entity);
                r.indent(2);
                vDesc.addElement(r);
            }

            // okay, print the info
            PilotingRollData rollTarget = getEjectModifiers(game, entity,
                    entity.getCrew().getCurrentPilotIndex(), autoEject);
            r = new Report(2180);
            r.subject = entity.getId();
            r.addDesc(entity);
            r.add(rollTarget.getLastPlainDesc(), true);
            r.indent();
            vDesc.addElement(r);
            for (int crewPos = 0; crewPos < entity.getCrew().getSlotCount(); crewPos++) {
                if (entity.getCrew().isMissing(crewPos)) {
                    continue;
                }
                rollTarget = getEjectModifiers(game, entity, crewPos,
                        autoEject);
                // roll
                final Roll diceRoll = entity.getCrew().rollPilotingSkill();

                if (entity.getCrew().getSlotCount() > 1) {
                    r = new Report(2193);
                    r.add(entity.getCrew().getNameAndRole(crewPos));
                } else {
                    r = new Report(2190);
                }

                r.subject = entity.getId();
                r.add(rollTarget.getValueAsString());
                r.add(rollTarget.getDesc());
                r.add(diceRoll);
                r.indent();

                if (diceRoll.getIntValue() < rollTarget.getValue()) {
                    r.choose(false);
                    vDesc.addElement(r);
                    Report.addNewline(vDesc);
                    if ((rollTarget.getValue() - diceRoll.getIntValue()) > 1) {
                        // Pilots take damage based on ejection roll MoF
                        int damage = (rollTarget.getValue() - diceRoll.getIntValue());
                        if (entity instanceof Mech) {
                            // MechWarriors only take 1 damage per 2 points of MoF
                            damage /= 2;
                        }
                        if (entity.hasQuirk(OptionsConstants.QUIRK_NEG_DIFFICULT_EJECT)) {
                            damage++;
                        }
                        vDesc.addAll(damageCrew(entity, damage, crewPos));
                    }

                    // If this is a skin of the teeth ejection...
                    if (skin_of_the_teeth && (entity.getCrew().getHits(crewPos) < 6)) {
                        Report.addNewline(vDesc);
                        vDesc.addAll(damageCrew(entity, 6 - entity.getCrew()
                                .getHits(crewPos)));
                    }
                } else {
                    r.choose(true);
                    vDesc.addElement(r);
                }
            }
            // create the MechWarrior in any case, for campaign tracking
            MechWarrior pilot = new MechWarrior(entity);
            pilot.setDeployed(true);
            pilot.setId(game.getNextEntityId());
            pilot.setLanded(false);
            if (entity.isSpaceborne()) {
                //In space, ejected pilots retain the heading and velocity of the unit they eject from
                pilot.setVectors(entity.getVectors());
                pilot.setFacing(entity.getFacing());
                pilot.setCurrentVelocity(entity.getVelocity());
                //If the pilot ejects, he should no longer be accelerating
                pilot.setNextVelocity(entity.getVelocity());
            } else if (entity.isAirborne()) {
                pilot.setAltitude(entity.getAltitude());
            }
            //Pilot flight suits are vacuum-rated. MechWarriors wear shorts...
            pilot.setSpaceSuit(entity.isAero());
            game.addEntity(pilot);
            communicationManager.send(packetManager.createAddEntityPacket(pilot.getId(), this));
            // make him not get a move this turn
            pilot.setDone(true);
            int living = 0;
            for (int i = 0; i < entity.getCrew().getSlotCount(); i++) {
                if (!entity.getCrew().isDead(i) && entity.getCrew().getHits(i) < Crew.DEATH) {
                    living++;
                }
            }
            pilot.setInternal(living, MechWarrior.LOC_INFANTRY);
            if (entity.getCrew().isDead() || entity.getCrew().getHits() >= Crew.DEATH) {
                pilot.setDoomed(true);
            }

            if (entity.getCrew().isDoomed()) {
                vDesc.addAll(entityActionManager.destroyEntity(pilot, "deadly ejection", false,
                        false, this));
            } else {
                // Add the pilot as an infantry unit on the battlefield.
                if (game.getBoard().contains(targetCoords)) {
                    pilot.setPosition(targetCoords);
                    // report safe ejection
                    r = new Report(6400);
                    r.subject = entity.getId();
                    r.indent(3);
                    vDesc.addElement(r);
                    // Update the entity
                    entityUpdate(pilot.getId());
                    // check if the pilot lands in a minefield
                    if (!entity.isAirborne()) {
                        vDesc.addAll(utilityManager.doEntityDisplacementMinefieldCheck(pilot,
                                entity.getPosition(), targetCoords,
                                entity.getElevation(), this));
                    }
                } else {
                    // ejects safely
                    r = new Report(6410);
                    r.subject = entity.getId();
                    r.indent(3);
                    vDesc.addElement(r);
                    game.removeEntity(pilot.getId(),
                            IEntityRemovalConditions.REMOVE_IN_RETREAT);
                    communicationManager.send(packetManager.createRemoveEntityPacket(pilot.getId(),
                            IEntityRemovalConditions.REMOVE_IN_RETREAT, this));
                    // }
                }
                if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_EJECTED_PILOTS_FLEE)
                        // Don't create a pilot entity on low-atmospheric maps
                        || game.getBoard().inAtmosphere()) {
                    game.removeEntity(pilot.getId(),
                            IEntityRemovalConditions.REMOVE_IN_RETREAT);
                    communicationManager.send(packetManager.createRemoveEntityPacket(pilot.getId(),
                            IEntityRemovalConditions.REMOVE_IN_RETREAT, this));
                }

                // If this is a skin of the teeth ejection...
                if (skin_of_the_teeth && (pilot.getCrew().getHits() < 5)) {
                    Report.addNewline(vDesc);
                    vDesc.addAll(damageCrew(pilot, 5 - pilot.getCrew()
                            .getHits()));
                }
            } // Crew safely ejects.

            // ejection damages the cockpit
            // kind of irrelevant in stand-alone games, but important for MekHQ
            if (entity instanceof Mech) {
                Mech mech = (Mech) entity;
                // in case of mechs with 'full head ejection', the head is treated as blown off
                if (mech.hasFullHeadEject()) {
                    entity.destroyLocation(Mech.LOC_HEAD, true);
                } else {
                    for (CriticalSlot slot : (mech.getCockpit())) {
                        slot.setDestroyed(true);
                    }
                }
            }
        } // End entity-is-Mek or fighter
        else if (game.getBoard().contains(entity.getPosition())
                && (entity instanceof Tank)) {
            EjectedCrew crew = new EjectedCrew(entity);
            // Need to set game manually; since game.addEntity not called yet
            // Don't want to do this yet, as Entity may not be added
            crew.setGame(game);
            crew.setDeployed(true);
            crew.setId(game.getNextEntityId());
            // Make them not get a move this turn
            crew.setDone(true);
            // Place on board
            // Vehicles don't have ejection systems, so crew must abandon into
            // a legal hex
            Coords legalPosition = null;
            if (!crew.isLocationProhibited(entity.getPosition())) {
                legalPosition = entity.getPosition();
            } else {
                for (int dir = 0; (dir < 6) && (legalPosition == null); dir++) {
                    Coords adjCoords = entity.getPosition().translated(dir);
                    if (!crew.isLocationProhibited(adjCoords)) {
                        legalPosition = adjCoords;
                    }
                }
            }
            // Cannot abandon if there is no legal hex. This shouldn't have been allowed
            if (legalPosition == null) {
                LogManager.getLogger().error("Vehicle crews cannot abandon if there is no legal hex!");
                return vDesc;
            }
            crew.setPosition(legalPosition);
            // Add Entity to game
            game.addEntity(crew);
            // Tell clients about new entity
            communicationManager.send(packetManager.createAddEntityPacket(crew.getId(), this));
            // Sent entity info to clients
            entityUpdate(crew.getId());
            // Check if the crew lands in a minefield
            vDesc.addAll(utilityManager.doEntityDisplacementMinefieldCheck(crew,
                    entity.getPosition(), entity.getPosition(),
                    entity.getElevation(), this));
            if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_EJECTED_PILOTS_FLEE)) {
                game.removeEntity(crew.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
                communicationManager.send(packetManager.createRemoveEntityPacket(crew.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT, this));
            }
        } //End ground vehicles

        // Mark the entity's crew as "ejected".
        entity.getCrew().setEjected(true);
        if (entity instanceof VTOL) {
            vDesc.addAll(entityActionManager.crashVTOLorWiGE((VTOL) entity, this));
        }
        vDesc.addAll(entityActionManager.destroyEntity(entity, "ejection", true, true, this));

        // only remove the unit that ejected manually
        if (!autoEject) {
            game.removeEntity(entity.getId(),
                    IEntityRemovalConditions.REMOVE_EJECTED);
            communicationManager.send(packetManager.createRemoveEntityPacket(entity.getId(),
                    IEntityRemovalConditions.REMOVE_EJECTED, this));
        }
        return vDesc;
    }

    /**
     * Abandon a spacecraft (large or small).
     *
     * @param entity  The <code>Aero</code> to eject.
     * @param inSpace Is this ship spaceborne?
     * @param airborne Is this ship in atmospheric flight?
     * @param pos The coords of this ejection. Needed when abandoning a grounded ship
     * @return a <code>Vector</code> of report objects for the gamelog.
     */
    public Vector<Report> ejectSpacecraft(Aero entity, boolean inSpace, boolean airborne, Coords pos) {
        Vector<Report> vDesc = new Vector<>();
        Report r;

        // An entity can only eject it's crew once.
        if (entity.getCrew().isEjected()) {
            return vDesc;
        }

        // If the crew are already dead, don't bother
        if (entity.isCarcass()) {
            return vDesc;
        }

        // Try to launch some escape pods and lifeboats, if any are left
        if ((inSpace && (entity.getPodsLeft() > 0 || entity.getLifeBoatsLeft() > 0))
                || (airborne && entity.getPodsLeft() > 0)) {
            // Report the ejection
            PilotingRollData rollTarget = getEjectModifiers(game, entity,
                    entity.getCrew().getCurrentPilotIndex(), false);
            r = new Report(2180);
            r.subject = entity.getId();
            r.addDesc(entity);
            r.add(rollTarget.getLastPlainDesc(), true);
            r.indent();
            vDesc.addElement(r);
            Roll diceRoll = Compute.rollD6(2);
            int MOS = (diceRoll.getIntValue() - Math.max(2, rollTarget.getValue()));
            //Report the roll
            r = new Report(2190);
            r.subject = entity.getId();
            r.add(rollTarget.getValueAsString());
            r.add(rollTarget.getDesc());
            r.add(diceRoll);
            r.indent();
            r.choose(diceRoll.getIntValue() >= rollTarget.getValue());
            vDesc.addElement(r);
            //Per SO p27, you get a certain number of escape pods away per turn per 100k tons of ship
            int escapeMultiplier = (int) (entity.getWeight() / 100000);
            //Set up the maximum number that CAN launch
            int toLaunch = 0;

            if (diceRoll.getIntValue() < rollTarget.getValue()) {
                toLaunch = 1;
            } else {
                toLaunch = (1 + MOS) * Math.max(1, escapeMultiplier);
            }
            //And now modify it based on what the unit actually has TO launch
            int launchCounter = toLaunch;
            int totalLaunched = 0;
            boolean isPod = false;
            while (launchCounter > 0) {
                int launched = 0;
                if (entity.getPodsLeft() > 0 && (airborne || entity.getPodsLeft() >= entity.getLifeBoatsLeft())) {
                    //Entity has more escape pods than lifeboats (or equal numbers)
                    launched = Math.min(launchCounter, entity.getPodsLeft());
                    entity.setLaunchedEscapePods(entity.getLaunchedEscapePods() + launched);
                    totalLaunched += launched;
                    launchCounter -= launched;
                    isPod = true;
                } else if (inSpace && entity.getLifeBoatsLeft() > 0 && (entity.getLifeBoatsLeft() > entity.getPodsLeft())) {
                    //Entity has more lifeboats left
                    launched = Math.min(launchCounter, entity.getLifeBoatsLeft());
                    entity.setLaunchedLifeBoats(entity.getLaunchedLifeBoats() + launched);
                    totalLaunched += launched;
                    launchCounter -= launched;
                } else {
                    //We've run out of both. End the loop
                    break;
                }
            }
            int nEscaped = Math.min((entity.getCrew().getCurrentSize() + entity.getNPassenger()), (totalLaunched * 6));
            //Report how many pods launched and how many escaped
            if (totalLaunched > 0) {
                r = new Report(6401);
                r.subject = entity.getId();
                r.indent();
                r.add(totalLaunched);
                r.add(nEscaped);
                vDesc.addElement(r);
            }
            EscapePods pods = new EscapePods(entity, totalLaunched, isPod);
            entity.addEscapeCraft(pods.getExternalIdAsString());
            //Update the personnel numbers

            //If there are passengers aboard, get them out first
            if (entity.getNPassenger() > 0) {
                int change = Math.min(entity.getNPassenger(), nEscaped);
                entity.setNPassenger(Math.max(entity.getNPassenger() - nEscaped, 0));
                pods.addPassengers(entity.getExternalIdAsString(), change);
                nEscaped -= change;
            }
            //Now get the crew out with such space as is left
            if (nEscaped > 0) {
                entity.setNCrew(entity.getNCrew() - nEscaped);
                entity.getCrew().setCurrentSize(Math.max(0, entity.getCrew().getCurrentSize() - nEscaped));
                pods.addNOtherCrew(entity.getExternalIdAsString(), nEscaped);
                //*Damage* the host ship's crew to account for the people that left
                vDesc.addAll(damageCrew(entity, entity.getCrew().calculateHits()));
                if (entity.getCrew().getHits() >= Crew.DEATH) {
                    //Then we've finished ejecting
                    entity.getCrew().setEjected(true);
                }
            }
            // Need to set game manually; since game.addEntity not called yet
            // Don't want to do this yet, as Entity may not be added
            pods.setPosition(entity.getPosition());
            pods.setGame(game);
            pods.setDeployed(true);
            pods.setId(game.getNextEntityId());
            //Escape craft retain the heading and velocity of the unit they eject from
            pods.setVectors(entity.getVectors());
            pods.setFacing(entity.getFacing());
            pods.setCurrentVelocity(entity.getCurrentVelocity());
            //If the crew ejects, they should no longer be accelerating
            pods.setNextVelocity(entity.getVelocity());
            if (entity.isAirborne()) {
                pods.setAltitude(entity.getAltitude());
            }
            // Add Entity to game
            game.addEntity(pods);
            // No movement this turn
            pods.setDone(true);
            // Tell clients about new entity
            communicationManager.send(packetManager.createAddEntityPacket(pods.getId(), this));
            // Sent entity info to clients
            entityUpdate(pods.getId());
            if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_EJECTED_PILOTS_FLEE)) {
                game.removeEntity(pods.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
                communicationManager.send(packetManager.createRemoveEntityPacket(pods.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT, this));
            }
        } // End Escape Pod/Lifeboat Ejection
        else {
            if (airborne) {
                // Can't abandon in atmosphere with no escape pods
                r = new Report(6402);
                r.subject = entity.getId();
                r.addDesc(entity);
                r.indent();
                vDesc.addElement(r);
                return vDesc;
            }

            // Eject up to 50 spacesuited crewmen out the nearest airlock!
            // This only works in space or on the ground
            int nEscaped = Math.min(entity.getNPassenger() + entity.getCrew().getCurrentSize(), 50);
            EjectedCrew crew = new EjectedCrew(entity, nEscaped);
            entity.addEscapeCraft(crew.getExternalIdAsString());

            //Report the escape
            r = new Report(6403);
            r.subject = entity.getId();
            r.addDesc(entity);
            r.add(nEscaped);
            r.indent();
            vDesc.addElement(r);

            //If there are passengers aboard, get them out first
            if (entity.getNPassenger() > 0) {
                int change = Math.min(entity.getNPassenger(), nEscaped);
                entity.setNPassenger(Math.max(entity.getNPassenger() - nEscaped, 0));
                crew.addPassengers(entity.getExternalIdAsString(), change);
                nEscaped -= change;
            }
            //Now get the crew out with such airlock space as is left
            if (nEscaped > 0) {
                entity.setNCrew(entity.getNCrew() - nEscaped);
                entity.getCrew().setCurrentSize(Math.max(0, entity.getCrew().getCurrentSize() - nEscaped));
                crew.addNOtherCrew(entity.getExternalIdAsString(), nEscaped);
                //*Damage* the host ship's crew to account for the people that left
                vDesc.addAll(damageCrew(entity, entity.getCrew().calculateHits()));
                if (entity.getCrew().getHits() >= Crew.DEATH) {
                    //Then we've finished ejecting
                    entity.getCrew().setEjected(true);
                }
            }

            // Need to set game manually; since game.addEntity not called yet
            // Don't want to do this yet, as Entity may not be added
            crew.setGame(game);
            crew.setDeployed(true);
            crew.setId(game.getNextEntityId());
            if (inSpace) {
                //In space, ejected pilots retain the heading and velocity of the unit they eject from
                crew.setVectors(entity.getVectors());
                crew.setFacing(entity.getFacing());
                crew.setCurrentVelocity(entity.getVelocity());
                //If the crew ejects, they should no longer be accelerating
                crew.setNextVelocity(entity.getVelocity());
                // We're going to be nice and assume a ship has enough spacesuits for everyone aboard...
                crew.setSpaceSuit(true);
                crew.setPosition(entity.getPosition());
            } else {
                // On the ground, crew must abandon into a legal hex
                Coords legalPosition = null;
                //Small Craft can just abandon into the hex they occupy
                if (!entity.isLargeCraft() && !crew.isLocationProhibited(entity.getPosition())) {
                    legalPosition = entity.getPosition();
                } else {
                    //Use the passed in coords. We already calculated whether they're legal or not
                    legalPosition = pos;
                }
                // Cannot abandon if there is no legal hex.  This shoudln't have
                // been allowed
                if (legalPosition == null) {
                    LogManager.getLogger().error("Spacecraft crews cannot abandon if there is no legal hex!");
                    return vDesc;
                }
                crew.setPosition(legalPosition);
            }
            // Add Entity to game
            game.addEntity(crew);
            // No movement this turn
            crew.setDone(true);
            // Tell clients about new entity
            communicationManager.send(packetManager.createAddEntityPacket(crew.getId(), this));
            // Sent entity info to clients
            entityUpdate(crew.getId());
            // Check if the crew lands in a minefield
            vDesc.addAll(utilityManager.doEntityDisplacementMinefieldCheck(crew, entity.getPosition(),
                    entity.getPosition(), entity.getElevation(), this));
            if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_EJECTED_PILOTS_FLEE)) {
                game.removeEntity(crew.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
                communicationManager.send(packetManager.createRemoveEntityPacket(crew.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT, this));
            }
        }
        // If we get here, end movement and return the report
        entity.setDone(true);
        entityUpdate(entity.getId());
        return vDesc;
    }

    public static PilotingRollData getEjectModifiers(Game game, Entity entity, int crewPos,
                                                     boolean autoEject) {
        int facing = entity.getFacing();
        if (entity.isPartOfFighterSquadron()) {
            // Because the components of a squadron have no position and will pass the next test
            Entity squadron = game.getEntity(entity.getTransportId());
            return getEjectModifiers(game, entity, crewPos, autoEject, squadron.getPosition(),
                    "ejecting");
        }
        if (null == entity.getPosition()) {
            // Off-board unit?
            return new PilotingRollData(entity.getId(), entity.getCrew().getPiloting(), "ejecting");
        }
        Coords targetCoords = entity.getPosition().translated((facing + 3) % 6);
        return getEjectModifiers(game, entity, crewPos, autoEject, targetCoords, "ejecting");
    }

    public static PilotingRollData getEjectModifiers(Game game, Entity entity, int crewPos,
                                                     boolean autoEject, Coords targetCoords, String desc) {
        PilotingRollData rollTarget = new PilotingRollData(entity.getId(),
                entity.getCrew().getPiloting(crewPos), desc);
        // Per SO p26, fighters can eject as per TO rules on 196 with some exceptions
        if (entity.isProne()) {
            rollTarget.addModifier(5, "Mech is prone");
        }
        if (entity.getCrew().isUnconscious(crewPos)) {
            rollTarget.addModifier(3, "pilot unconscious");
        }
        if (autoEject) {
            rollTarget.addModifier(1, "automatic ejection");
        }
        // Per SO p27, Large Craft roll too, to see how many escape pods launch successfully
        if ((entity.isAero() && ((IAero) entity).isOutControl())
                || (entity.isPartOfFighterSquadron() && ((IAero) game.getEntity(entity.getTransportId())).isOutControl())) {
            rollTarget.addModifier(5, "Out of Control");
        }
        // A decreased large craft crew makes it harder to eject large numbers of pods
        if (entity.isLargeCraft() && entity.getCrew().getHits() > 0) {
            rollTarget.addModifier(entity.getCrew().getHits(), "Crew hits");
        }
        if ((entity instanceof Mech)
                && (entity.getInternal(Mech.LOC_HEAD) < entity.getOInternal(Mech.LOC_HEAD))) {
            rollTarget.addModifier(entity.getOInternal(Mech.LOC_HEAD) - entity.getInternal(Mech.LOC_HEAD),
                    "Head Internal Structure Damage");
        }
        Hex targetHex = game.getBoard().getHex(targetCoords);
        //Terrain modifiers should only apply if the unit is on the ground...
        if (!entity.isSpaceborne() && !entity.isAirborne()) {
            if (targetHex != null) {
                if ((targetHex.terrainLevel(Terrains.WATER) > 0)
                        && !targetHex.containsTerrain(Terrains.ICE)) {
                    rollTarget.addModifier(-1, "landing in water");
                } else if (targetHex.containsTerrain(Terrains.ROUGH)) {
                    rollTarget.addModifier(0, "landing in rough");
                } else if (targetHex.containsTerrain(Terrains.RUBBLE)) {
                    rollTarget.addModifier(0, "landing in rubble");
                } else if (targetHex.terrainLevel(Terrains.WOODS) == 1) {
                    rollTarget.addModifier(2, "landing in light woods");
                } else if (targetHex.terrainLevel(Terrains.WOODS) == 2) {
                    rollTarget.addModifier(3, "landing in heavy woods");
                } else if (targetHex.terrainLevel(Terrains.WOODS) == 3) {
                    rollTarget.addModifier(4, "landing in ultra heavy woods");
                } else if (targetHex.terrainLevel(Terrains.JUNGLE) == 1) {
                    rollTarget.addModifier(3, "landing in light jungle");
                } else if (targetHex.terrainLevel(Terrains.JUNGLE) == 2) {
                    rollTarget.addModifier(5, "landing in heavy jungle");
                } else if (targetHex.terrainLevel(Terrains.JUNGLE) == 3) {
                    rollTarget.addModifier(7, "landing in ultra heavy jungle");
                } else if (targetHex.terrainLevel(Terrains.BLDG_ELEV) > 0) {
                    rollTarget.addModifier(
                            targetHex.terrainLevel(Terrains.BLDG_ELEV),
                            "landing in a building");
                } else {
                    rollTarget.addModifier(-2, "landing in clear terrain");
                }
            } else {
                rollTarget.addModifier(-2, "landing off the board");
            }
        }
        if (!entity.isSpaceborne()) {
            // At present, the UI lets you set these atmospheric conditions for a space battle, but it shouldn't
            // That's a fix for another day, probably when I get around to space terrain and 'weather'
            if (game.getPlanetaryConditions().getGravity() == 0) {
                rollTarget.addModifier(3, "Zero-G");
            } else if (game.getPlanetaryConditions().getGravity() < 0.8) {
                rollTarget.addModifier(2, "Low-G");
            } else if (game.getPlanetaryConditions().getGravity() > 1.2) {
                rollTarget.addModifier(2, "High-G");
            }

            //Vacuum shouldn't apply to ASF ejection since they're designed for it, but the rules don't specify
            //High and low pressures make more sense to apply to all
            if (game.getPlanetaryConditions().getAtmosphere() == PlanetaryConditions.ATMO_VACUUM) {
                rollTarget.addModifier(3, "Vacuum");
            } else if (game.getPlanetaryConditions().getAtmosphere() == PlanetaryConditions.ATMO_VHIGH) {
                rollTarget.addModifier(2, "Very High Atmosphere Pressure");
            } else if (game.getPlanetaryConditions().getAtmosphere() == PlanetaryConditions.ATMO_TRACE) {
                rollTarget.addModifier(2, "Trace atmosphere");
            }
        }

        if ((game.getPlanetaryConditions().getWeather() == PlanetaryConditions.WE_HEAVY_SNOW)
                || (game.getPlanetaryConditions().getWeather() == PlanetaryConditions.WE_ICE_STORM)
                || (game.getPlanetaryConditions().getWeather() == PlanetaryConditions.WE_DOWNPOUR)
                || (game.getPlanetaryConditions().getWindStrength() == PlanetaryConditions.WI_STRONG_GALE)) {
            rollTarget.addModifier(2, "Bad Weather");
        }

        if ((game.getPlanetaryConditions().getWindStrength() >= PlanetaryConditions.WI_STORM)
                || ((game.getPlanetaryConditions().getWeather() == PlanetaryConditions.WE_HEAVY_SNOW) && (game
                .getPlanetaryConditions().getWindStrength() == PlanetaryConditions.WI_STRONG_GALE))) {
            rollTarget.addModifier(3, "Really Bad Weather");
        }
        return rollTarget;
    }

    /**
     * Creates a new Ballistic Infantry unit at the end of the movement phase
     */
    public void resolveCallSupport() {
        for (Entity e : game.getEntitiesVector()) {
            if ((e instanceof Infantry) && ((Infantry) e).getIsCallingSupport()) {

                // Now lets create a new foot platoon
                Infantry guerrilla = new Infantry();
                guerrilla.setChassis("Insurgents");
                guerrilla.setModel("(Rifle)");
                guerrilla.setSquadCount(4);
                guerrilla.setSquadSize(7);
                guerrilla.autoSetInternal();
                guerrilla.getCrew().setGunnery(5, 0);
                try {
                    guerrilla.addEquipment(EquipmentType.get(EquipmentTypeLookup.INFANTRY_ASSAULT_RIFLE),
                            Infantry.LOC_INFANTRY);
                    guerrilla.setPrimaryWeapon((InfantryWeapon) InfantryWeapon
                            .get(EquipmentTypeLookup.INFANTRY_ASSAULT_RIFLE));
                } catch (Exception ex) {
                    LogManager.getLogger().error("", ex);
                }
                guerrilla.setDeployed(true);
                guerrilla.setDone(true);
                guerrilla.setId(game.getNextEntityId());
                guerrilla.setOwner(e.getOwner());
                game.addEntity(guerrilla);

                // Add the infantry unit on the battlefield. Should spawn within 3 hexes
                // First get coords then loop over some targets
                Coords tmpCoords = e.getPosition();
                Coords targetCoords = null;
                while (!game.getBoard().contains(targetCoords)) {
                    targetCoords = Compute.scatter(tmpCoords, (Compute.d6(1) / 2));
                    if (game.getBoard().contains(targetCoords)) {
                        guerrilla.setPosition(targetCoords);
                        break;
                    }
                }
                communicationManager.send(packetManager.createAddEntityPacket(guerrilla.getId(), this));
                ((Infantry) e).setIsCallingSupport(false);
                /*
                // Update the entity
                entityUpdate(guerrilla.getId());
                Report r = new Report(5535, Report.PUBLIC);
                r.subject = e.getId();
                r.addDesc(e);
                addReport(r);*/
            }
        }
    }

    /**
     * Abandon an Entity.
     *
     * @param entity The <code>Entity</code> to abandon.
     * @return a <code>Vector</code> of report objects for the game log.
     */
    public Vector<Report> abandonEntity(Entity entity) {
        Vector<Report> vDesc = new Vector<>();
        Report r;

        // An entity can only eject it's crew once.
        if (entity.getCrew().isEjected()) {
            return vDesc;
        }

        if (entity.getCrew().isDoomed() || entity.getCrew().isDead()) {
            return vDesc;
        }

        Coords targetCoords = entity.getPosition();

        if (entity instanceof Mech || (entity.isAero() && !entity.isAirborne())) {
            // okay, print the info
            r = new Report(2027);
            r.subject = entity.getId();
            r.add(entity.getCrew().getName());
            r.addDesc(entity);
            r.indent(3);
            vDesc.addElement(r);
            // Don't make ill-equipped pilots abandon into vacuum
            if (game.getPlanetaryConditions().isVacuum() && !entity.isAero()) {
                return vDesc;
            }

            // create the MechWarrior in any case, for campaign tracking
            MechWarrior pilot = new MechWarrior(entity);
            pilot.getCrew().setUnconscious(entity.getCrew().isUnconscious());
            pilot.setDeployed(true);
            pilot.setId(game.getNextEntityId());
            //Pilot flight suits are vacuum-rated. MechWarriors wear shorts...
            pilot.setSpaceSuit(entity.isAero());
            if (entity.isSpaceborne()) {
                //In space, ejected pilots retain the heading and velocity of the unit they eject from
                pilot.setVectors(entity.getVectors());
                pilot.setFacing(entity.getFacing());
                pilot.setCurrentVelocity(entity.getVelocity());
                //If the pilot ejects, he should no longer be accelerating
                pilot.setNextVelocity(entity.getVelocity());
            }
            game.addEntity(pilot);
            communicationManager.send(packetManager.createAddEntityPacket(pilot.getId(), this));
            // make him not get a move this turn
            pilot.setDone(true);
            // Add the pilot as an infantry unit on the battlefield.
            if (game.getBoard().contains(targetCoords)) {
                pilot.setPosition(targetCoords);
            }
            pilot.setCommander(entity.isCommander());
            // Update the entity
            entityUpdate(pilot.getId());
            // check if the pilot lands in a minefield
            vDesc.addAll(utilityManager.doEntityDisplacementMinefieldCheck(pilot, entity.getPosition(),
                    targetCoords, entity.getElevation(), this));
            if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_EJECTED_PILOTS_FLEE)) {
                game.removeEntity(pilot.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
                communicationManager.send(packetManager.createRemoveEntityPacket(pilot.getId(),
                        IEntityRemovalConditions.REMOVE_IN_RETREAT, this));
            }
        } // End entity-is-Mek or Aero
        else if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_VEHICLES_CAN_EJECT)
                && (entity instanceof Tank)) {
            // Don't make them abandon into vacuum
            if (game.getPlanetaryConditions().isVacuum()) {
                return vDesc;
            }
            EjectedCrew crew = new EjectedCrew(entity);
            crew.setDeployed(true);
            crew.setId(game.getNextEntityId());
            game.addEntity(crew);
            communicationManager.send(packetManager.createAddEntityPacket(crew.getId(), this));
            // Make them not get a move this turn
            crew.setDone(true);
            // Place on board
            if (game.getBoard().contains(entity.getPosition())) {
                crew.setPosition(entity.getPosition());
            }
            // Update the entity
            entityUpdate(crew.getId());
            // Check if the crew lands in a minefield
            vDesc.addAll(utilityManager.doEntityDisplacementMinefieldCheck(crew, entity.getPosition(),
                    entity.getPosition(), entity.getElevation(), this));
            if (game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_EJECTED_PILOTS_FLEE)) {
                game.removeEntity(crew.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
                communicationManager.send(packetManager.createRemoveEntityPacket(crew.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT, this));
            }
        }

        // Mark the entity's crew as "ejected".
        entity.getCrew().setEjected(true);

        return vDesc;
    }

    /**
     * Checks if ejected MechWarriors are eligible to be picked up, and if so,
     * captures them or picks them up
     */
    protected void resolveMechWarriorPickUp() {
        Report r;

        // fetch all mechWarriors that are not picked up
        Iterator<Entity> mechWarriors = game.getSelectedEntities(entity -> {
            if (entity instanceof MechWarrior) {
                MechWarrior mw = (MechWarrior) entity;
                return (mw.getPickedUpById() == Entity.NONE)
                        && !mw.isDoomed()
                        && (mw.getTransportId() == Entity.NONE);
            }
            return false;
        });
        // loop through them, check if they are in a hex occupied by another
        // unit
        while (mechWarriors.hasNext()) {
            boolean pickedUp = false;
            MechWarrior e = (MechWarrior) mechWarriors.next();
            // Check for owner entities first...
            for (Entity pe : game.getEntitiesVector(e.getPosition())) {
                if (pe.isDoomed() || pe.isShutDown() || pe.getCrew().isUnconscious()
                        || (pe.isAirborne() && !pe.isSpaceborne())
                        || (pe.getElevation() != e.getElevation())
                        || (pe.getOwnerId() != e.getOwnerId())
                        || (pe.getId() == e.getId())) {
                    continue;
                }
                if (pe instanceof MechWarrior) {
                    // MWs have a beer together
                    r = new Report(6415, Report.PUBLIC);
                    r.add(pe.getDisplayName());
                    addReport(r);
                    continue;
                }
                // Pick up the unit.
                pe.pickUp(e);
                // The picked unit is being carried by the loader.
                e.setPickedUpById(pe.getId());
                e.setPickedUpByExternalId(pe.getExternalIdAsString());
                pickedUp = true;
                r = new Report(6420, Report.PUBLIC);
                r.add(e.getDisplayName());
                r.addDesc(pe);
                addReport(r);
                break;
            }
            // Check for allied entities next...
            if (!pickedUp) {
                for (Entity pe : game.getEntitiesVector(e.getPosition())) {
                    if (pe.isDoomed() || pe.isShutDown() || pe.getCrew().isUnconscious()
                            || (pe.isAirborne() && !pe.isSpaceborne())
                            || (pe.getElevation() != e.getElevation())
                            || (pe.getOwnerId() == e.getOwnerId()) || (pe.getId() == e.getId())
                            || (pe.getOwner().getTeam() == Player.TEAM_NONE)
                            || (pe.getOwner().getTeam() != e.getOwner().getTeam())) {
                        continue;
                    }
                    if (pe instanceof MechWarrior) {
                        // MWs have a beer together
                        r = new Report(6416, Report.PUBLIC);
                        r.add(pe.getDisplayName());
                        addReport(r);
                        continue;
                    }
                    // Pick up the unit.
                    pe.pickUp(e);
                    // The picked unit is being carried by the loader.
                    e.setPickedUpById(pe.getId());
                    e.setPickedUpByExternalId(pe.getExternalIdAsString());
                    pickedUp = true;
                    r = new Report(6420, Report.PUBLIC);
                    r.add(e.getDisplayName());
                    r.addDesc(pe);
                    addReport(r);
                    break;
                }
            }
            // Now check for anyone else...
            if (!pickedUp) {
                Iterator<Entity> pickupEnemyEntities = game.getEnemyEntities(e.getPosition(), e);
                while (pickupEnemyEntities.hasNext()) {
                    Entity pe = pickupEnemyEntities.next();
                    if (pe.isDoomed() || pe.isShutDown() || pe.getCrew().isUnconscious()
                            || pe.isAirborne() || (pe.getElevation() != e.getElevation())) {
                        continue;
                    }
                    if (pe instanceof MechWarrior) {
                        // MWs have a beer together
                        r = new Report(6417, Report.PUBLIC);
                        r.add(pe.getDisplayName());
                        addReport(r);
                        continue;
                    }
                    // Capture the unit.
                    pe.pickUp(e);
                    // The captured unit is being carried by the loader.
                    e.setCaptured(true);
                    e.setPickedUpById(pe.getId());
                    e.setPickedUpByExternalId(pe.getExternalIdAsString());
                    pickedUp = true;
                    r = new Report(6420, Report.PUBLIC);
                    r.add(e.getDisplayName());
                    r.addDesc(pe);
                    addReport(r);
                    break;
                }
            }
            if (pickedUp) {
                // Remove the picked-up unit from the screen.
                e.setPosition(null);
                // Update the loaded unit.
                entityUpdate(e.getId());
            }
        }
    }

    /**
     * destroy all wheeled and tracked Tanks that got displaced into water
     */
    void resolveSinkVees() {
        Iterator<Entity> sinkableTanks = game.getSelectedEntities(entity -> {
            if (entity.isOffBoard() || (entity.getPosition() == null)
                    || !(entity instanceof Tank)) {
                return false;
            }
            final Hex hex = game.getBoard().getHex(entity.getPosition());
            final boolean onBridge = (hex.terrainLevel(Terrains.BRIDGE) > 0)
                    && (entity.getElevation() == hex.terrainLevel(Terrains.BRIDGE_ELEV));
            return ((entity.getMovementMode() == EntityMovementMode.TRACKED)
                    || (entity.getMovementMode() == EntityMovementMode.WHEELED)
                    || ((entity.getMovementMode() == EntityMovementMode.HOVER)))
                    && entity.isImmobile() && (hex.terrainLevel(Terrains.WATER) > 0)
                    && !onBridge && !(entity.hasWorkingMisc(MiscType.F_FULLY_AMPHIBIOUS))
                    && !(entity.hasWorkingMisc(MiscType.F_FLOTATION_HULL));
        });
        while (sinkableTanks.hasNext()) {
            Entity e = sinkableTanks.next();
            reportManager.addReport(entityActionManager.destroyEntity(e, "a watery grave", false, this), this);
        }
    }

    /**
     * let all Entities make their "break-free-of-swamp-stickyness" PSR
     */
    protected void doTryUnstuck() {
        if (!getGame().getPhase().isMovement()) {
            return;
        }

        Report r;

        Iterator<Entity> stuckEntities = game.getSelectedEntities(Entity::isStuck);
        PilotingRollData rollTarget;
        while (stuckEntities.hasNext()) {
            Entity entity = stuckEntities.next();
            if (entity.getPosition() == null) {
                if (entity.isDeployed()) {
                    LogManager.getLogger().info("Entity #" + entity.getId() + " does not know its position.");
                } else { // If the Entity isn't deployed, then something goofy
                    // happened.  We'll just unstuck the Entity
                    entity.setStuck(false);
                    LogManager.getLogger().info("Entity #" + entity.getId() + " was stuck in a swamp, but not deployed. Stuck state reset");
                }
                continue;
            }
            rollTarget = entity.getBasePilotingRoll();
            entity.addPilotingModifierForTerrain(rollTarget);
            // apart from swamp & liquid magma, -1 modifier
            Hex hex = game.getBoard().getHex(entity.getPosition());
            hex.getUnstuckModifier(entity.getElevation(), rollTarget);
            // okay, print the info
            r = new Report(2340);
            r.subject = entity.getId();
            r.addDesc(entity);
            addReport(r);

            // roll
            final Roll diceRoll = entity.getCrew().rollPilotingSkill();
            r = new Report(2190);
            r.subject = entity.getId();
            r.add(rollTarget.getValueAsString());
            r.add(rollTarget.getDesc());
            r.add(diceRoll);

            if (diceRoll.getIntValue() < rollTarget.getValue()) {
                r.choose(false);
            } else {
                r.choose(true);
                entity.setStuck(false);
                entity.setCanUnstickByJumping(false);
                entity.setElevation(0);
                entityUpdate(entity.getId());
            }
            addReport(r);
        }
    }

    /**
     * Remove all iNarc pods from all vehicles that did not move and shoot this
     * round NOTE: this is not quite what the rules say, the player should be
     * able to choose whether or not to remove all iNarc Pods that are attached.
     */
    protected void resolveVeeINarcPodRemoval() {
        Iterator<Entity> vees = game.getSelectedEntities(
                entity -> (entity instanceof Tank) && (entity.mpUsed == 0));
        boolean canSwipePods;
        while (vees.hasNext()) {
            canSwipePods = true;
            Entity entity = vees.next();
            for (int i = 0; i <= 5; i++) {
                if (entity.weaponFiredFrom(i)) {
                    canSwipePods = false;
                }
            }
            if (((Tank) entity).getStunnedTurns() > 0) {
                canSwipePods = false;
            }
            if (canSwipePods && entity.hasINarcPodsAttached()
                    && entity.getCrew().isActive()) {
                entity.removeAllINarcPods();
                Report r = new Report(2345);
                r.addDesc(entity);
                addReport(r);
            }
        }
    }

    /**
     * remove Ice in the hex that's at the passed coords, and let entities fall
     * into water below it, if there is water
     *
     * @param c the <code>Coords</code> of the hex where ice should be removed
     * @return a <code>Vector<Report></code> for the phase report
     */
    protected Vector<Report> resolveIceBroken(Coords c) {
        Vector<Report> vPhaseReport = new Vector<>();
        Hex hex = game.getBoard().getHex(c);
        hex.removeTerrain(Terrains.ICE);
        communicationManager.sendChangedHex(c, this);
        // if there is water below the ice
        if (hex.terrainLevel(Terrains.WATER) > 0) {
            // drop entities on the surface into the water
            for (Entity e : game.getEntitiesVector(c)) {
                // If the unit is on the surface, and is no longer allowed in
                // the hex
                boolean isHoverOrWiGE = (e.getMovementMode() == EntityMovementMode.HOVER)
                        || (e.getMovementMode() == EntityMovementMode.WIGE);
                if ((e.getElevation() == 0)
                        && !(hex.containsTerrain(Terrains.BLDG_ELEV, 0))
                        && !(isHoverOrWiGE && (e.getRunMP() >= 0))
                        && (e.getMovementMode() != EntityMovementMode.INF_UMU)
                        && !e.hasUMU()
                        && !(e instanceof QuadVee && e.getConversionMode() == QuadVee.CONV_MODE_VEHICLE)) {
                    vPhaseReport.addAll(utilityManager.doEntityFallsInto(e, c,
                            new PilotingRollData(TargetRoll.AUTOMATIC_FAIL),
                            true, this));
                }
            }
        }
        return vPhaseReport;
    }

    /**
     * melt any snow or ice in a hex, including checking for the effects of
     * breaking through ice
     */
    protected Vector<Report> meltIceAndSnow(Coords c, int entityId) {
        Vector<Report> vDesc = new Vector<>();
        Report r;
        Hex hex = game.getBoard().getHex(c);
        r = new Report(3069);
        r.indent(2);
        r.subject = entityId;
        vDesc.add(r);
        if (hex.containsTerrain(Terrains.SNOW)) {
            hex.removeTerrain(Terrains.SNOW);
            communicationManager.sendChangedHex(c, this);
        }
        if (hex.containsTerrain(Terrains.ICE)) {
            vDesc.addAll(resolveIceBroken(c));
        }
        // if we were not in water, then add mud
        if (!hex.containsTerrain(Terrains.MUD) && !hex.containsTerrain(Terrains.WATER)) {
            hex.addTerrain(new Terrain(Terrains.MUD, 1));
            communicationManager.sendChangedHex(c, this);
        }
        return vDesc;
    }

    /**
     * check to see if a swamp hex becomes quicksand
     */
    protected Vector<Report> checkQuickSand(Coords c) {
        Vector<Report> vDesc = new Vector<>();
        Report r;
        Hex hex = game.getBoard().getHex(c);
        if (hex.terrainLevel(Terrains.SWAMP) == 1) {
            if (Compute.d6(2) == 12) {
                // better find a rope
                hex.removeTerrain(Terrains.SWAMP);
                hex.addTerrain(new Terrain(Terrains.SWAMP, 2));
                communicationManager.sendChangedHex(c, this);
                r = new Report(2440);
                r.indent(1);
                vDesc.add(r);
            }
        }
        return vDesc;
    }

    protected Vector<Report> resolveVehicleFire(Tank tank, boolean existingStatus) {
        Vector<Report> vPhaseReport = new Vector<>();
        if (existingStatus && !tank.isOnFire()) {
            return vPhaseReport;
        }
        for (int i = 0; i < tank.locations(); i++) {
            if ((i == Tank.LOC_BODY) || ((tank instanceof VTOL) && (i == VTOL.LOC_ROTOR))) {
                continue;
            }
            if (existingStatus && !tank.isLocationBurning(i)) {
                continue;
            }
            HitData hit = new HitData(i);
            int damage = Compute.d6(1);
            vPhaseReport.addAll(damageEntity(tank, hit, damage));
            if ((damage == 1) && existingStatus) {
                tank.extinguishLocation(i);
            }
        }
        return vPhaseReport;
    }

    public Vector<Report> vehicleMotiveDamage(Tank te, int modifier) {
        return vehicleMotiveDamage(te, modifier, false, -1, false);
    }

    protected Vector<Report> vehicleMotiveDamage(Tank te, int modifier, boolean noRoll,
                                               int damageType) {
        return vehicleMotiveDamage(te, modifier, noRoll, damageType, false);
    }

    /**
     * do vehicle movement damage
     *
     * @param te         the Tank to damage
     * @param modifier   the modifier to the roll
     * @param noRoll     don't roll, immediately deal damage
     * @param damageType the type to deal (1 = minor, 2 = moderate, 3 = heavy
     * @param jumpDamage is this a movement damage roll from using vehicular JJs
     * @return a <code>Vector<Report></code> containing what to add to the turn log
     */
    protected Vector<Report> vehicleMotiveDamage(Tank te, int modifier, boolean noRoll,
                                               int damageType, boolean jumpDamage) {
        Vector<Report> vDesc = new Vector<>();
        Report r;
        switch (te.getMovementMode()) {
            case HOVER:
            case HYDROFOIL:
                if (jumpDamage) {
                    modifier -= 1;
                } else {
                    modifier += 3;
                }
                break;
            case WHEELED:
                if (jumpDamage) {
                    modifier += 1;
                } else {
                    modifier += 2;
                }
                break;
            case WIGE:
                if (jumpDamage) {
                    modifier -= 2;
                } else {
                    modifier += 4;
                }
                break;
            case TRACKED:
                if (jumpDamage) {
                    modifier += 2;
                }
                break;
            case VTOL:
                // VTOL don't roll, auto -1 MP as long as the rotor location
                // still exists (otherwise don't bother reporting).
                if (!(te.isLocationBad(VTOL.LOC_ROTOR) || te.isLocationDoomed(VTOL.LOC_ROTOR))) {
                    te.setMotiveDamage(te.getMotiveDamage() + 1);
                    if (te.getOriginalWalkMP() > te.getMotiveDamage()) {
                        r = new Report(6660);
                        r.indent(3);
                        r.subject = te.getId();
                        vDesc.add(r);
                    } else {
                        r = new Report(6670);
                        r.subject = te.getId();
                        vDesc.add(r);
                        te.immobilize();
                        // Being reduced to 0 MP by rotor damage forces a
                        // landing
                        // like an engine hit...
                        if (te.isAirborneVTOLorWIGE()
                                // ...but don't bother to resolve that if we're
                                // already otherwise destroyed.
                                && !(te.isDestroyed() || te.isDoomed())) {
                            vDesc.addAll(entityActionManager.forceLandVTOLorWiGE(te, this));
                        }
                    }
                }
                // This completes our handling of VTOLs; the rest of the method
                // doesn't need to worry about them anymore.
                return vDesc;
            default:
                break;
        }
        // Apply vehicle effectiveness...except for hits from jumps.
        if (game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_VEHICLE_EFFECTIVE)
                && !jumpDamage) {
            modifier = Math.max(modifier - 1, 0);
        }

        if (te.hasWorkingMisc(MiscType.F_ARMORED_MOTIVE_SYSTEM)) {
            modifier -= 2;
        }

        Roll diceRoll = Compute.rollD6(2);
        int rollValue = diceRoll.getIntValue() + modifier;
        String rollCalc = rollValue + " [" + diceRoll.getIntValue() + " + " + modifier + "]";
        r = new Report(6306);
        r.subject = te.getId();
        r.newlines = 0;
        r.indent(3);
        vDesc.add(r);

        if (!noRoll) {
            r = new Report(6310);
            r.subject = te.getId();
            if (modifier != 0) {
                r.addDataWithTooltip(rollCalc, diceRoll.getReport());
            } else {
                r.add(diceRoll);
            }
            r.newlines = 0;
            vDesc.add(r);
            r = new Report(3340);
            r.add(modifier);
            r.subject = te.getId();
            vDesc.add(r);
        }

        if ((noRoll && (damageType == 0)) || (!noRoll && (rollValue <= 5))) {
            // no effect
            r = new Report(6005);
            r.subject = te.getId();
            r.indent(3);
            vDesc.add(r);
        } else if ((noRoll && (damageType == 1)) || (!noRoll && (rollValue <= 7))) {
            // minor damage
            r = new Report(6470);
            r.subject = te.getId();
            r.indent(3);
            vDesc.add(r);
            te.addMovementDamage(1);
        } else if ((noRoll && (damageType == 2)) || (!noRoll && (rollValue <= 9))) {
            // moderate damage
            r = new Report(6471);
            r.subject = te.getId();
            r.indent(3);
            vDesc.add(r);
            te.addMovementDamage(2);
        } else if ((noRoll && (damageType == 3)) || (!noRoll && (rollValue <= 11))) {
            // heavy damage
            r = new Report(6472);
            r.subject = te.getId();
            r.indent(3);
            vDesc.add(r);
            te.addMovementDamage(3);
        } else {
            r = new Report(6473);
            r.subject = te.getId();
            r.indent(3);
            vDesc.add(r);
            te.addMovementDamage(4);
        }
        // These checks should perhaps be moved to Tank.applyDamage(), but I'm
        // unsure how to *report* any outcomes from there. Note that these treat
        // being reduced to 0 MP and being actually immobilized as the same thing,
        // which for these particular purposes may or may not be the intent of
        // the rules in all cases (for instance, motive-immobilized CVs can still jump).
        // Immobile hovercraft on water sink...
        if (!te.isOffBoard() && (te.getMovementMode() == EntityMovementMode.HOVER
                && (te.isMovementHitPending() || (te.getWalkMP() <= 0))
                // HACK: Have to check for *pending* hit here and below.
                && (game.getBoard().getHex(te.getPosition()).terrainLevel(Terrains.WATER) > 0)
                && !game.getBoard().getHex(te.getPosition()).containsTerrain(Terrains.ICE))) {
            vDesc.addAll(entityActionManager.destroyEntity(te, "a watery grave", false, this));
        }
        // ...while immobile WiGEs crash.
        if (((te.getMovementMode() == EntityMovementMode.WIGE) && (te.isAirborneVTOLorWIGE()))
                && (te.isMovementHitPending() || (te.getWalkMP() <= 0))) {
            // report problem: add tab
            vDesc.addAll(entityActionManager.crashVTOLorWiGE(te, this));
        }
        return vDesc;
    }

    /**
     * Add a single report to the report queue of all players and the master
     * vPhaseReport queue
     */
    @Override
    public void addReport(Report report) {
        vPhaseReport.addElement(report);
    }

    /**
     * make sure all the new lines that were added to the old vPhaseReport get
     * added to all of the players filters
     */
    protected void addNewLines() {
        Report.addNewline(vPhaseReport);
    }

    /**
     * resolve the landing of an assault drop
     *
     * @param entity the <code>Entity</code> for which to resolve it
     */
    public void doAssaultDrop(Entity entity) {
        //resolve according to SO p.22

        Report r = new Report(2380);

        // whatever else happens, this entity is on the ground now
        entity.setAltitude(0);

        PilotingRollData psr;
        // LAMs that convert to fighter mode on the landing turn are processed as crashes
        if ((entity instanceof LandAirMech)
                && (entity.getConversionMode() == LandAirMech.CONV_MODE_FIGHTER)) {
            reportManager.addReport(entityActionManager.processCrash(entity, 0, entity.getPosition(), this), this);
            return;
        }
        if ((entity instanceof Protomech) || (entity instanceof BattleArmor)) {
            psr = new PilotingRollData(entity.getId(), 5, "landing assault drop");
        } else if (entity instanceof Infantry) {
            psr = new PilotingRollData(entity.getId(), 4, "landing assault drop");
        } else {
            psr = entity.getBasePilotingRoll();
        }
        Roll diceRoll = Compute.rollD6(2);
        // check for a safe landing
        addNewLines();
        r.subject = entity.getId();
        r.add(entity.getDisplayName(), true);
        r.add(psr);
        r.add(diceRoll);
        r.newlines = 1;
        r.choose(diceRoll.getIntValue() >= psr.getValue());
        addReport(r);

        // if we are on an atmospheric map or the entity is off the map for some reason
        if (game.getBoard().inAtmosphere() || entity.getPosition() == null) {
            // then just remove the entity
            // TODO : for this and when the unit scatters off the board, we should really still
            // TODO : apply damage before we remove, but this causes all kinds of problems for
            // TODO : doEntityFallsInto and related methods which expect a coord on the board
            // TODO : - need to make those more robust
            r = new Report(2388);
            addReport(r);
            r.subject = entity.getId();
            r.add(entity.getDisplayName(), true);
            game.removeEntity(entity.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
            return;
        }

        if (diceRoll.getIntValue() < psr.getValue()) {
            int fallHeight = psr.getValue() - diceRoll.getIntValue();

            // if you fail by more than 7, you automatically fail
            if (fallHeight > 7) {
                reportManager.addReport(entityActionManager.destroyEntity(entity, "failed assault drop", false, false, this), this);
                entityUpdate(entity.getId());
                return;
            }

            // determine where we really land
            Coords c = Compute.scatterAssaultDrop(entity.getPosition(), fallHeight);
            int distance = entity.getPosition().distance(c);
            r = new Report(2385);
            r.subject = entity.getId();
            r.add(distance);
            r.indent();
            r.newlines = 0;
            addReport(r);
            if (!game.getBoard().contains(c)) {
                r = new Report(2386);
                r.subject = entity.getId();
                addReport(r);
                game.removeEntity(entity.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
                return;
            } else {
                r = new Report(2387);
                r.subject = entity.getId();
                r.add(c.getBoardNum());
                addReport(r);
            }
            entity.setPosition(c);

            // do fall damage from accidental fall
            //set elevation to fall height above ground or building roof
            Hex hex = game.getBoard().getHex(entity.getPosition());
            int bldgElev = hex.containsTerrain(Terrains.BLDG_ELEV)
                    ? hex.terrainLevel(Terrains.BLDG_ELEV) : 0;
            entity.setElevation(fallHeight + bldgElev);
            if (entity.isConventionalInfantry()) {
                HitData hit = new HitData(Infantry.LOC_INFANTRY);
                reportManager.addReport(damageEntity(entity, hit, 1), this);
                // LAMs that convert to fighter mode on the landing turn are processed as crashes regardless of roll
            } else {
                reportManager.addReport(utilityManager.doEntityFallsInto(entity, c, psr, true, this), this);
            }
        } else {
            // set entity to expected elevation
            Hex hex = game.getBoard().getHex(entity.getPosition());
            int bldgElev = hex.containsTerrain(Terrains.BLDG_ELEV)
                    ? hex.terrainLevel(Terrains.BLDG_ELEV) : 0;
            entity.setElevation(bldgElev);

            Building bldg = game.getBoard().getBuildingAt(entity.getPosition());
            if (bldg != null) {
                // whoops we step on the roof
                checkBuildingCollapseWhileMoving(bldg, entity, entity.getPosition());
            }

            // finally, check for any stacking violations
            Entity violated = Compute.stackingViolation(game, entity, entity.getPosition(), null, entity.climbMode());
            if (violated != null) {
                // StratOps explicitly says that this is not treated as an accident
                // fall from above
                // so we just need to displace the violating unit
                // check to see if the violating unit is a DropShip and if so, then
                // displace the unit dropping instead
                if (violated instanceof Dropship) {
                    violated = entity;
                }
                Coords targetDest = Compute.getValidDisplacement(game, violated.getId(),
                        violated.getPosition(), Compute.d6() - 1);
                if (null != targetDest) {
                    utilityManager.doEntityDisplacement(violated, violated.getPosition(), targetDest, null, this);
                    entityUpdate(violated.getId());
                } else {
                    // ack! automatic death! Tanks
                    // suffer an ammo/power plant hit.
                    // TODO : a Mech suffers a Head Blown Off crit.
                    vPhaseReport.addAll(entityActionManager.destroyEntity(entity, "impossible displacement",
                            entity instanceof Mech, entity instanceof Mech, this));
                }
            }
        }
    }

    /**
     * resolve assault drops for all entities
     */
    void doAllAssaultDrops() {
        for (Entity e : game.getEntitiesVector()) {
            if (e.isAssaultDropInProgress() && e.isDeployed()) {
                doAssaultDrop(e);
                e.setLandedAssaultDrop();
            }
        }
    }

    /**
     * do damage from magma
     *
     * @param en       the affected <code>Entity</code>
     * @param eruption <code>boolean</code> indicating whether or not this is because
     *                 of an eruption
     */
    public void doMagmaDamage(Entity en, boolean eruption) {
        if ((((en.getMovementMode() == EntityMovementMode.VTOL) && (en.getElevation() > 0))
                || (en.getMovementMode() == EntityMovementMode.HOVER)
                || ((en.getMovementMode() == EntityMovementMode.WIGE)
                && (en.getOriginalWalkMP() > 0) && !eruption)) && !en.isImmobile()) {
            return;
        }
        Report r;
        boolean isMech = en instanceof Mech;
        if (isMech) {
            r = new Report(2405);
        } else {
            r = new Report(2400);
        }
        r.addDesc(en);
        r.subject = en.getId();
        addReport(r);
        if (isMech) {
            HitData h;
            for (int i = 0; i < en.locations(); i++) {
                if (eruption || en.locationIsLeg(i) || en.isProne()) {
                    h = new HitData(i);
                    reportManager.addReport(damageEntity(en, h, Compute.d6(2)), this);
                }
            }
        } else {
            reportManager.addReport(entityActionManager.destroyEntity(en, "fell into magma", false, false, this), this);
        }
        addNewLines();
    }

    /**
     * Applies damage to any eligible unit hit by anti-TSM missiles or entering
     * a hex with green smoke.
     *
     * @param entity An entity subject to anti-TSM damage
     * @return The damage reports
     */
    public Vector<Report> doGreenSmokeDamage(Entity entity) {
        Vector<Report> reports = new Vector<>();
        // ignore if we're flying over the smoke or we're already toast
        if ((entity.getElevation() >= 2) || entity.isDestroyed() || entity.isDoomed()) {
            return reports;
        }
        Report r = new Report(6432);
        r.subject = entity.getId();
        r.addDesc(entity);
        reports.add(r);
        if (entity.isConventionalInfantry()) {
            reports.addAll(damageEntity(entity, new HitData(Infantry.LOC_INFANTRY), Compute.d6()));
        } else {
            for (int loc = 0; loc < entity.locations(); loc++) {
                if ((entity.getArmor(loc) <= 0 || (entity.hasRearArmor(loc) && (entity.getArmor(loc, true) < 0)))
                        && !entity.isLocationBlownOff(loc)) {
                    r = new Report(6433);
                    r.subject = entity.getId();
                    r.add(entity.getLocationName(loc));
                    r.indent(1);
                    reports.add(r);
                    reports.addAll(damageEntity(entity, new HitData(loc), 6, false,
                            DamageType.ANTI_TSM, true));
                }
            }
        }
        // Only report if the exposure has some effect
        if (reports.size() == 1) {
            reports.clear();
        }
        return reports;
    }

    /**
     * sink any entities in quicksand in the current hex
     */
    public void doSinkEntity(Entity en) {
        Report r;
        r = new Report(2445);
        r.addDesc(en);
        r.subject = en.getId();
        addReport(r);
        en.setElevation(en.getElevation() - 1);
        // if this means the entity is below the ground, then bye-bye!
        if (Math.abs(en.getElevation()) > en.getHeight()) {
            reportManager.addReport(entityActionManager.destroyEntity(en, "quicksand", this), this);
        }
    }

    /**
     * deal area saturation damage to an individual hex
     *
     * @param coords         The hex being hit
     * @param attackSource   The location the attack came from. For hit table resolution
     * @param damage         Amount of damage to deal to each entity
     * @param ammo           The ammo type being used
     * @param subjectId      Subject for reports
     * @param killer         Who should be credited with kills
     * @param exclude        Entity that should take no damage (used for homing splash)
     * @param flak           Flak, hits flying units only, instead of flyers being immune
     * @param altitude       Absolute altitude for flak attack
     * @param vPhaseReport   The Vector of Reports for the phase report
     * @param asfFlak        Is this flak against ASF?
     * @param alreadyHit     a vector of unit ids for units that have already been hit that
     *                       will be ignored
     * @param variableDamage if true, treat damage as the number of six-sided dice to roll
     */
    public Vector<Integer> artilleryDamageHex(Coords coords,
                                              Coords attackSource, int damage, AmmoType ammo, int subjectId,
                                              Entity killer, Entity exclude, boolean flak, int altitude,
                                              Vector<Report> vPhaseReport, boolean asfFlak,
                                              Vector<Integer> alreadyHit, boolean variableDamage) {

        Hex hex = game.getBoard().getHex(coords);
        if (hex == null) {
            return alreadyHit; // not on board.
        }

        Report r;

        // Non-flak artillery damages terrain
        if (!flak) {
            // Report that damage applied to terrain, if there's TF to damage
            Hex h = game.getBoard().getHex(coords);
            if ((h != null) && h.hasTerrainFactor()) {
                r = new Report(3384);
                r.indent(2);
                r.subject = subjectId;
                r.add(coords.getBoardNum());
                r.add(damage * 2);
                vPhaseReport.addElement(r);
            }
            // Update hex and report any changes
            Vector<Report> newReports = environmentalEffectManager.tryClearHex(coords, damage * 2, subjectId, this);
            for (Report nr : newReports) {
                nr.indent(3);
            }
            vPhaseReport.addAll(newReports);
        }

        boolean isFuelAirBomb =
                ammo != null &&
                        (BombType.getBombTypeFromInternalName(ammo.getInternalName()) == BombType.B_FAE_SMALL ||
                                BombType.getBombTypeFromInternalName(ammo.getInternalName()) == BombType.B_FAE_LARGE);

        Building bldg = game.getBoard().getBuildingAt(coords);
        int bldgAbsorbs = 0;
        if ((bldg != null)
                && !(flak && (((altitude > hex.terrainLevel(Terrains.BLDG_ELEV))
                || (altitude > hex.terrainLevel(Terrains.BRIDGE_ELEV)))))) {
            bldgAbsorbs = bldg.getAbsorbtion(coords);
            if (!((ammo != null) && (ammo.getMunitionType().contains(AmmoType.Munitions.M_FLECHETTE)))) {
                int actualDamage = damage;

                if (isFuelAirBomb) {
                    // light buildings take 1.5x damage from fuel-air bombs
                    if (bldg.getType() == Building.LIGHT) {
                        actualDamage = (int) Math.ceil(actualDamage * 1.5);

                        r = new Report(9991);
                        r.indent(1);
                        r.subject = killer.getId();
                        r.newlines = 1;
                        vPhaseReport.addElement(r);
                    }

                    // armored and "castle brian" buildings take .5 damage from fuel-air bombs
                    // but I have no idea how to determine if a building is a castle or a brian
                    // note that being armored and being "light" are not mutually exclusive
                    if (bldg.getArmor(coords) > 0) {
                        actualDamage = (int) Math.floor(actualDamage * .5);

                        r = new Report(9992);
                        r.indent(1);
                        r.subject = killer.getId();
                        r.newlines = 1;
                        vPhaseReport.addElement(r);
                    }
                }


                // damage the building
                Vector<Report> buildingReport = damageBuilding(bldg, actualDamage, coords);
                for (Report report : buildingReport) {
                    report.subject = subjectId;
                }
                vPhaseReport.addAll(buildingReport);
            }
        }

        if (flak && ((altitude <= 0)
                || (altitude <= hex.terrainLevel(Terrains.BLDG_ELEV))
                || (altitude == hex.terrainLevel(Terrains.BRIDGE_ELEV)))) {
            // Flak in this hex would only hit landed units
            return alreadyHit;
        }

        // get units in hex
        for (Entity entity : game.getEntitiesVector(coords)) {
            // Check: is entity excluded?
            if ((entity == exclude) || alreadyHit.contains(entity.getId())) {
                continue;
            } else {
                alreadyHit.add(entity.getId());
            }

            AreaEffectHelper.artilleryDamageEntity(entity, damage, bldg, bldgAbsorbs,
                    variableDamage, asfFlak, flak, altitude,
                    attackSource, ammo, coords, isFuelAirBomb,
                    killer, hex, subjectId, vPhaseReport, this);
        }

        return alreadyHit;
    }

    /**
     * deal area saturation damage to the map, used for artillery
     *
     * @param centre       The hex on which damage is centred
     * @param attackSource The position the attack came from
     * @param ammo         The ammo type doing the damage
     * @param subjectId    Subject for reports
     * @param killer       Who should be credited with kills
     * @param flak         Flak, hits flying units only, instead of flyers being immune
     * @param altitude     Absolute altitude for flak attack
     * @param mineClear    Does this clear mines?
     * @param vPhaseReport The Vector of Reports for the phase report
     * @param asfFlak      Is this flak against ASF?
     * @param attackingBA  How many BA suits are in the squad if this is a BA Tube arty
     *                     attack, -1 otherwise
     */
    public void artilleryDamageArea(Coords centre, Coords attackSource,
                                    AmmoType ammo, int subjectId, Entity killer, boolean flak,
                                    int altitude, boolean mineClear, Vector<Report> vPhaseReport,
                                    boolean asfFlak, int attackingBA) {
        AreaEffectHelper.DamageFalloff damageFalloff = AreaEffectHelper.calculateDamageFallOff(ammo, attackingBA, mineClear);

        int damage = damageFalloff.damage;
        int falloff = damageFalloff.falloff;
        if (damageFalloff.clusterMunitionsFlag) {
            attackSource = centre;
        }

        artilleryDamageArea(centre, attackSource, ammo, subjectId, killer,
                damage, falloff, flak, altitude, vPhaseReport, asfFlak);
    }

    /**
     * Deals area-saturation damage to an area of the board. Used for artillery,
     * bombs, or anything else with linear decrease in damage
     *
     * @param centre
     *            The hex on which damage is centred
     * @param attackSource
     *            The position the attack came from
     * @param ammo
     *            The ammo type doing the damage
     * @param subjectId
     *            Subject for reports
     * @param killer
     *            Who should be credited with kills
     * @param damage
     *            Damage at ground zero
     * @param falloff
     *            Reduction in damage for each hex of distance
     * @param flak
     *            Flak, hits flying units only, instead of flyers being immune
     * @param altitude
     *            Absolute altitude for flak attack
     * @param vPhaseReport
     *            The Vector of Reports for the phase report
     * @param asfFlak
     *            Is this flak against ASF?
     */
    public void artilleryDamageArea(Coords centre, Coords attackSource, AmmoType ammo, int subjectId,
                                    Entity killer, int damage, int falloff, boolean flak, int altitude,
                                    Vector<Report> vPhaseReport, boolean asfFlak) {
        Vector<Integer> alreadyHit = new Vector<>();
        for (int ring = 0; damage > 0; ring++, damage -= falloff) {
            List<Coords> hexes = centre.allAtDistance(ring);
            for (Coords c : hexes) {
                alreadyHit = artilleryDamageHex(c, attackSource, damage, ammo,
                        subjectId, killer, null, flak, altitude, vPhaseReport,
                        asfFlak, alreadyHit, false);
            }
            attackSource = centre; // all splash comes from ground zero
        }
    }

    public void deliverBombDamage(Coords centre, int type, int subjectId, Entity killer,
                                  Vector<Report> vPhaseReport) {
        int range = 0;
        int damage = 10;
        if (type == BombType.B_CLUSTER) {
            range = 1;
            damage = 5;
        }
        Vector<Integer> alreadyHit = new Vector<>();

        // We need the actual ammo type in order to handle certain bomb issues correctly.
        BombType ammo = BombType.createBombByType(type);

        alreadyHit = artilleryDamageHex(centre, centre, damage, ammo,
                subjectId, killer, null, false, 0, vPhaseReport, false,
                alreadyHit, false);
        if (range > 0) {
            List<Coords> hexes = centre.allAtDistance(range);
            for (Coords c : hexes) {
                alreadyHit = artilleryDamageHex(c, centre, damage, ammo,
                        subjectId, killer, null, false, 0, vPhaseReport, false,
                        alreadyHit, false);
            }
        }
    }

    /**
     * deliver inferno bomb
     *
     * @param coords    the <code>Coords</code> where to deliver
     * @param ae        the attacking <code>entity</code>
     * @param subjectId the <code>int</code> id of the target
     */
    public void deliverBombInferno(Coords coords, Entity ae, int subjectId,
                                   Vector<Report> vPhaseReport) {
        Hex h = game.getBoard().getHex(coords);
        Report r;
        // Unless there is a fire in the hex already, start one.
        if (h.terrainLevel(Terrains.FIRE) < Terrains.FIRE_LVL_INFERNO_BOMB) {
            ignite(coords, Terrains.FIRE_LVL_INFERNO_BOMB, vPhaseReport);
        }
        // possibly melt ice and snow
        if (h.containsTerrain(Terrains.ICE) || h.containsTerrain(Terrains.SNOW)) {
            vPhaseReport.addAll(meltIceAndSnow(coords, subjectId));
        }
        for (Entity entity : game.getEntitiesVector(coords)) {
            if (entity.isAirborne() || entity.isAirborneVTOLorWIGE()) {
                continue;
            }
            // TacOps, p. 359 - treat as if hit by 5 inferno missiles
            r = new Report(6696);
            r.indent(3);
            r.add(entity.getDisplayName());
            r.subject = entity.getId();
            r.newlines = 0;
            vPhaseReport.add(r);
            if (entity instanceof Tank) {
                Report.addNewline(vPhaseReport);
            }
            Vector<Report> vDamageReport = environmentalEffectManager.deliverInfernoMissiles(ae, entity, 5, this);
            Report.indentAll(vDamageReport, 2);
            vPhaseReport.addAll(vDamageReport);
        }
    }

    /**
     * Resolve any Infantry units which are fortifying hexes
     */
    void resolveFortify() {
        Report r;
        for (Entity ent : game.getEntitiesVector()) {
            if (ent instanceof Infantry) {
                Infantry inf = (Infantry) ent;
                int dig = inf.getDugIn();
                if (dig == Infantry.DUG_IN_WORKING) {
                    r = new Report(5300);
                    r.addDesc(inf);
                    r.subject = inf.getId();
                    addReport(r);
                } else if (dig == Infantry.DUG_IN_FORTIFYING3) {
                    Coords c = inf.getPosition();
                    r = new Report(5305);
                    r.addDesc(inf);
                    r.add(c.getBoardNum());
                    r.subject = inf.getId();
                    addReport(r);
                    // fortification complete - add to map
                    Hex hex = game.getBoard().getHex(c);
                    hex.addTerrain(new Terrain(Terrains.FORTIFIED, 1));
                    communicationManager.sendChangedHex(c, this);
                    // Clear the dig in for any units in same hex, since they
                    // get it for free by fort
                    for (Entity ent2 : game.getEntitiesVector(c)) {
                        if (ent2 instanceof Infantry) {
                            Infantry inf2 = (Infantry) ent2;
                            inf2.setDugIn(Infantry.DUG_IN_NONE);
                        }
                    }
                }
            }

            if (ent instanceof Tank) {
                Tank tnk = (Tank) ent;
                int dig = tnk.getDugIn();
                if (dig == Tank.DUG_IN_FORTIFYING3) {
                    Coords c = tnk.getPosition();
                    r = new Report(5305);
                    r.addDesc(tnk);
                    r.add(c.getBoardNum());
                    r.subject = tnk.getId();
                    addReport(r);
                    // Fort complete, now add it to the map
                    Hex hex = game.getBoard().getHex(c);
                    hex.addTerrain(new Terrain(Terrains.FORTIFIED, 1));
                    communicationManager.sendChangedHex(c, this);
                    tnk.setDugIn(Tank.DUG_IN_NONE);
                    // Clear the dig in for any units in same hex, since they
                    // get it for free by fort
                    for (Entity ent2 : game.getEntitiesVector(c)) {
                        if (ent2 instanceof Infantry) {
                            Infantry inf2 = (Infantry) ent2;
                            inf2.setDugIn(Infantry.DUG_IN_NONE);
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if spikes get broken in the given location
     *
     * @param e   The {@link Entity} to check
     * @param loc The location index
     * @return    A report showing the results of the roll
     */
    protected Report checkBreakSpikes(Entity e, int loc) {
        Roll diceRoll = Compute.rollD6(2);
        Report r;

        if (diceRoll.getIntValue() < 9) {
            r = new Report(4445);
            r.indent(2);
            r.add(diceRoll);
            r.subject = e.getId();
        } else {
            r = new Report(4440);
            r.indent(2);
            r.add(diceRoll);
            r.subject = e.getId();

            for (Mounted m : e.getMisc()) {
                if (m.getType().hasFlag(MiscType.F_SPIKES)
                        && (m.getLocation() == loc)) {
                    m.setHit(true);
                }
            }
        }
        return r;
    }

    /**
     * Loops through all the attacks the game has. Checks if they care about
     * current phase, if so, runs them, and removes them if they don't want to
     * stay. TODO : Refactor the new entity announcement out of here.
     */
    void handleAttacks() {
        handleAttacks(false);
    }

    protected void handleAttacks(boolean pointblankShot) {
        Report r;
        int lastAttackerId = -1;
        Vector<AttackHandler> currentAttacks, keptAttacks;
        currentAttacks = game.getAttacksVector();
        keptAttacks = new Vector<>();
        Vector<Report> handleAttackReports = new Vector<>();
        // first, do any TAGs, so homing arty will have TAG
        for (AttackHandler ah : currentAttacks) {
            if (!(ah instanceof TAGHandler)) {
                continue;
            }
            if (ah.cares(game.getPhase())) {
                int aId = ah.getAttackerId();
                if ((aId != lastAttackerId) && !ah.announcedEntityFiring()) {
                    // report who is firing
                    if (pointblankShot) {
                        r = new Report(3102);
                    } else {
                        r = new Report(3100);
                    }
                    r.subject = aId;
                    r.addDesc(ah.getAttacker());
                    handleAttackReports.addElement(r);
                    ah.setAnnouncedEntityFiring(true);
                    lastAttackerId = aId;
                }
                boolean keep = ah.handle(game.getPhase(), handleAttackReports);
                if (keep) {
                    keptAttacks.add(ah);
                }
                Report.addNewline(handleAttackReports);
            }
        }
        // now resolve everything but TAG
        for (AttackHandler ah : currentAttacks) {
            if (ah instanceof TAGHandler) {
                continue;
            }
            if (ah.cares(game.getPhase())) {
                int aId = ah.getAttackerId();
                if ((aId != lastAttackerId) && !ah.announcedEntityFiring()) {
                    // if this is a new attacker then resolve any
                    // standard-to-cap damage
                    // from previous
                    handleAttackReports.addAll(checkFatalThresholds(aId,
                            lastAttackerId));
                    // report who is firing
                    if (pointblankShot) {
                        r = new Report(3102);
                    } else if (ah.isStrafing()) {
                        r = new Report(3101);
                    } else {
                        r = new Report(3100);
                    }
                    r.subject = aId;
                    r.addDesc(ah.getAttacker());
                    handleAttackReports.addElement(r);
                    ah.setAnnouncedEntityFiring(true);
                    lastAttackerId = aId;
                }
                boolean keep = ah.handle(game.getPhase(), handleAttackReports);
                if (keep) {
                    keptAttacks.add(ah);
                }
                Report.addNewline(handleAttackReports);
            } else {
                keptAttacks.add(ah);
            }
        }

        // resolve standard to capital one more time
        handleAttackReports.addAll(checkFatalThresholds(lastAttackerId, lastAttackerId));
        Report.addNewline(handleAttackReports);
        reportManager.addReport(handleAttackReports, this);
        // HACK, but anything else seems to run into weird problems.
        game.setAttacksVector(keptAttacks);
    }

    /**
     * create a <code>SmokeCloud</code> object and add it to the server list
     *
     * @param coords   the location to create the smoke
     * @param level    1=Light 2=Heavy Smoke 3:light LI smoke 4: Heavy LI smoke
     * @param duration How long the smoke will last.
     */
    public void createSmoke(Coords coords, int level, int duration) {
        SmokeCloud cloud = new SmokeCloud(coords, level, duration, game.getRoundCount());
        game.addSmokeCloud(cloud);
        communicationManager.sendSmokeCloudAdded(cloud, this);
    }

    /**
     * create a <code>SmokeCloud</code> object and add it to the server list
     *
     * @param coords   the location to create the smoke
     * @param level    1=Light 2=Heavy Smoke 3:light LI smoke 4: Heavy LI smoke
     * @param duration duration How long the smoke will last.
     */
    public void createSmoke(ArrayList<Coords> coords, int level, int duration) {
        SmokeCloud cloud = new SmokeCloud(coords, level, duration, game.getRoundCount());
        game.addSmokeCloud(cloud);
        communicationManager.sendSmokeCloudAdded(cloud, this);
    }

    /**
     * remove a cloud from the map
     *
     * @param cloud the location to remove the smoke from
     */
    public void removeSmokeTerrain(SmokeCloud cloud) {
        for (Coords coords : cloud.getCoordsList()) {
            Hex hex = game.getBoard().getHex(coords);
            if ((hex != null) && hex.containsTerrain(Terrains.SMOKE)) {
                hex.removeTerrain(Terrains.SMOKE);
                communicationManager.sendChangedHex(coords, this);
            }
        }
    }

    public List<SmokeCloud> getSmokeCloudList() {
        return game.getSmokeCloudList();
    }

    /**
     * Check to see if blowing sand caused damage to airborne VTOL/WIGEs
     */
    protected Vector<Report> resolveBlowingSandDamage() {
        Vector<Report> vFullReport = new Vector<>();
        vFullReport.add(new Report(5002, Report.PUBLIC));
        int damage_bonus = Math.max(0, game.getPlanetaryConditions().getWindStrength()
                - PlanetaryConditions.WI_MOD_GALE);
        // cycle through each team and damage 1d6 airborne VTOL/WiGE
        for (Team team : game.getTeams()) {
            Vector<Integer> airborne = getAirborneVTOL(team);
            if (!airborne.isEmpty()) {
                // how many units are affected
                int unitsAffected = Math.min(Compute.d6(), airborne.size());
                while ((unitsAffected > 0) && !airborne.isEmpty()) {
                    int loc = Compute.randomInt(airborne.size());
                    Entity en = game.getEntity(airborne.get(loc));
                    int damage = Math.max(1, Compute.d6() / 2) + damage_bonus;
                    while (damage > 0) {
                        HitData hit = en.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_RANDOM);
                        vFullReport.addAll(damageEntity(en, hit, 1));
                        damage--;
                    }
                    unitsAffected--;
                    airborne.remove(loc);
                }
            }
        }
        Report.addNewline(vPhaseReport);
        return vFullReport;
    }

    /**
     * cycle through entities on team and collect all the airborne VTOL/WIGE
     *
     * @return a vector of relevant entity ids
     */
    public Vector<Integer> getAirborneVTOL(Team team) {
        Vector<Integer> units = new Vector<>();
        for (Entity entity : game.getEntitiesVector()) {
            for (Player player : team.players()) {
                if (entity.getOwner().equals(player)) {
                    if (((entity instanceof VTOL)
                            || (entity.getMovementMode() == EntityMovementMode.WIGE)) &&
                            (!entity.isDestroyed()) &&
                            (entity.getElevation() > 0)) {
                        units.add(entity.getId());
                    }
                }
            }
        }
        return units;
    }

    /**
     * let an entity lay a mine
     *
     * @param entity the <code>Entity</code> that should lay a mine
     * @param mineId an <code>int</code> pointing to the mine
     */
    protected void layMine(Entity entity, int mineId, Coords coords) {
        Mounted mine = entity.getEquipment(mineId);
        Report r;
        if (!mine.isMissing()) {
            int reportId = 0;
            switch (mine.getMineType()) {
                case Mounted.MINE_CONVENTIONAL:
                    environmentalEffectManager.deliverThunderMinefield(coords, entity.getOwnerId(), 10,
                            entity.getId(), this);
                    reportId = 3500;
                    break;
                case Mounted.MINE_VIBRABOMB:
                    environmentalEffectManager.deliverThunderVibraMinefield(coords, entity.getOwnerId(), 10,
                            mine.getVibraSetting(), entity.getId(), this);
                    reportId = 3505;
                    break;
                case Mounted.MINE_ACTIVE:
                    environmentalEffectManager.deliverThunderActiveMinefield(coords, entity.getOwnerId(), 10,
                            entity.getId(), this);
                    reportId = 3510;
                    break;
                case Mounted.MINE_INFERNO:
                    environmentalEffectManager.deliverThunderInfernoMinefield(coords, entity.getOwnerId(), 10,
                            entity.getId(), this);
                    reportId = 3515;
                    break;
                // TODO : command-detonated mines
                // case 2:
            }
            mine.setShotsLeft(mine.getUsableShotsLeft() - 1);
            if (mine.getUsableShotsLeft() <= 0) {
                mine.setMissing(true);
            }
            r = new Report(reportId);
            r.subject = entity.getId();
            r.addDesc(entity);
            r.add(coords.getBoardNum());
            addReport(r);
            entity.setLayingMines(true);
        }
    }

    public Set<Coords> getHexUpdateSet() {
        return hexUpdateSet;

    }
}
