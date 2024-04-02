package megamek.server.gameManager;

import megamek.MMConstants;
import megamek.MegaMek;
import megamek.common.*;
import megamek.common.enums.GamePhase;
import megamek.common.event.GameVictoryEvent;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.preference.PreferenceManager;
import megamek.common.util.EmailService;
import megamek.common.util.SerializationHelper;
import megamek.common.util.StringUtil;
import megamek.server.DynamicTerrainProcessor;
import megamek.server.Server;
import megamek.server.ServerBoardHelper;
import megamek.server.ServerHelper;
import org.apache.logging.log4j.LogManager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;
import java.util.zip.GZIPOutputStream;

public class GameStateManager {
    /**
     * Changes the current phase, does some bookkeeping and then tells the
     * players.
     *
     * @param phase the <code>int</code> id of the phase to change to
     * @param gameManager
     */
    void changePhase(GamePhase phase, GameManager gameManager) {
        gameManager.game.setLastPhase(gameManager.game.getPhase());
        gameManager.game.setPhase(phase);

        // prepare for the phase
        gameManager.gameStateManager.prepareForPhase(phase, gameManager);

        if (phase.isPlayable(gameManager.getGame())) {
            // tell the players about the new phase
            gameManager.communicationManager.send(new Packet(PacketCommand.PHASE_CHANGE, phase));

            // post phase change stuff
            gameManager.gameStateManager.executePhase(phase, gameManager);
        } else {
            gameManager.gameStateManager.endCurrentPhase(gameManager);
        }
    }

    /**
     * Ends this phase and moves on to the next.
     * @param gameManager
     */
    void endCurrentPhase(GameManager gameManager) {
        switch (gameManager.game.getPhase()) {
            case LOUNGE:
                gameManager.game.addReports(gameManager.vPhaseReport);
                changePhase(GamePhase.EXCHANGE, gameManager);
                break;
            case EXCHANGE:
            case STARTING_SCENARIO:
                gameManager.game.addReports(gameManager.vPhaseReport);
                changePhase(GamePhase.SET_ARTILLERY_AUTOHIT_HEXES, gameManager);
                break;
            case SET_ARTILLERY_AUTOHIT_HEXES:
                gameManager.communicationManager.sendSpecialHexDisplayPackets(gameManager);
                Enumeration<Player> e = gameManager.game.getPlayers();
                boolean mines = false;
                while (e.hasMoreElements() && !mines) {
                    Player p = e.nextElement();
                    if (p.hasMinefields()) {
                        mines = true;
                    }
                }
                gameManager.game.addReports(gameManager.vPhaseReport);
                if (mines) {
                    changePhase(GamePhase.DEPLOY_MINEFIELDS, gameManager);
                } else {
                    changePhase(GamePhase.INITIATIVE, gameManager);
                }
                break;
            case DEPLOY_MINEFIELDS:
                changePhase(GamePhase.INITIATIVE, gameManager);
                break;
            case DEPLOYMENT:
                gameManager.game.clearDeploymentThisRound();
                gameManager.game.checkForCompleteDeployment();
                Enumeration<Player> pls = gameManager.game.getPlayers();
                while (pls.hasMoreElements()) {
                    Player p = pls.nextElement();
                    p.adjustStartingPosForReinforcements();
                }

                if (gameManager.game.getRoundCount() < 1) {
                    changePhase(GamePhase.INITIATIVE, gameManager);
                } else {
                    changePhase(GamePhase.TARGETING, gameManager);
                }
                break;
            case INITIATIVE:
                gameManager.resolveWhatPlayersCanSeeWhatUnits();
                gameManager.detectSpacecraft();
                gameManager.game.addReports(gameManager.vPhaseReport);
                changePhase(GamePhase.INITIATIVE_REPORT, gameManager);
                break;
            case INITIATIVE_REPORT:
                // NOTE: now that aeros can come and go from the battlefield, I
                // need
                // to update the
                // deployment table every round. I think this it is OK to go
                // here.
                // (Taharqa)
                gameManager.game.setupRoundDeployment();
                // boolean doDeploy = game.shouldDeployThisRound() &&
                // (game.getLastPhase() != Game.Phase.DEPLOYMENT);
                if (gameManager.game.shouldDeployThisRound()) {
                    changePhase(GamePhase.DEPLOYMENT, gameManager);
                } else {
                    changePhase(GamePhase.TARGETING, gameManager);
                }
                break;
            case PREMOVEMENT:
                changePhase(GamePhase.MOVEMENT, gameManager);
                break;
            case MOVEMENT:
                gameManager.detectHiddenUnits();
                ServerHelper.detectMinefields(gameManager.game, gameManager.vPhaseReport, gameManager);
                gameManager.updateSpacecraftDetection();
                gameManager.detectSpacecraft();
                gameManager.resolveWhatPlayersCanSeeWhatUnits();
                gameManager.doAllAssaultDrops();
                gameManager.addMovementHeat();
                gameManager.applyBuildingDamage();
                gameManager.checkForPSRFromDamage();
                gameManager.addReport((Report) gameManager.resolvePilotingRolls()); // Skids cause damage in
                // movement phase
                gameManager.checkForFlamingDamage();
                gameManager.checkForTeleMissileAttacks();
                gameManager.cleanupDestroyedNarcPods();
                gameManager.checkForFlawedCooling();
                gameManager.resolveCallSupport();
                // check phase report
                if (gameManager.vPhaseReport.size() > 1) {
                    gameManager.game.addReports(gameManager.vPhaseReport);
                    changePhase(GamePhase.MOVEMENT_REPORT, gameManager);
                } else {
                    // just the header, so we'll add the <nothing> label
                    gameManager.addReport(new Report(1205, Report.PUBLIC));
                    gameManager.game.addReports(gameManager.vPhaseReport);
                    gameManager.communicationManager.sendReport(gameManager);
                    changePhase(GamePhase.OFFBOARD, gameManager);
                }
                break;
            case MOVEMENT_REPORT:
                changePhase(GamePhase.OFFBOARD, gameManager);
                break;
            case PREFIRING:
                changePhase(GamePhase.FIRING, gameManager);
                break;
            case FIRING:
                // write Weapon Attack Phase header
                gameManager.addReport(new Report(3000, Report.PUBLIC));
                gameManager.resolveWhatPlayersCanSeeWhatUnits();
                gameManager.resolveAllButWeaponAttacks();
                gameManager.resolveSelfDestructions();
                gameManager.reportGhostTargetRolls();
                gameManager.reportLargeCraftECCMRolls();
                gameManager.resolveOnlyWeaponAttacks();
                gameManager.assignAMS();
                gameManager.handleAttacks();
                gameManager.resolveScheduledNukes();
                gameManager.applyBuildingDamage();
                gameManager.checkForPSRFromDamage();
                gameManager.cleanupDestroyedNarcPods();
                gameManager.addReport((Report) gameManager.resolvePilotingRolls());
                gameManager.checkForFlawedCooling();
                // check phase report
                if (gameManager.vPhaseReport.size() > 1) {
                    gameManager.game.addReports(gameManager.vPhaseReport);
                    changePhase(GamePhase.FIRING_REPORT, gameManager);
                } else {
                    // just the header, so we'll add the <nothing> label
                    gameManager.addReport(new Report(1205, Report.PUBLIC));
                    gameManager.communicationManager.sendReport(gameManager);
                    gameManager.game.addReports(gameManager.vPhaseReport);
                    changePhase(GamePhase.PHYSICAL, gameManager);
                }
                break;
            case FIRING_REPORT:
                changePhase(GamePhase.PHYSICAL, gameManager);
                break;
            case PHYSICAL:
                gameManager.resolveWhatPlayersCanSeeWhatUnits();
                gameManager.entityActionManager.resolvePhysicalAttacks(gameManager);
                gameManager.applyBuildingDamage();
                gameManager.checkForPSRFromDamage();
                gameManager.addReport((Report) gameManager.resolvePilotingRolls());
                gameManager.resolveSinkVees();
                gameManager.cleanupDestroyedNarcPods();
                gameManager.checkForFlawedCooling();
                gameManager.checkForChainWhipGrappleChecks();
                // check phase report
                if (gameManager.vPhaseReport.size() > 1) {
                    gameManager.game.addReports(gameManager.vPhaseReport);
                    changePhase(GamePhase.PHYSICAL_REPORT, gameManager);
                } else {
                    // just the header, so we'll add the <nothing> label
                    gameManager.addReport(new Report(1205, Report.PUBLIC));
                    gameManager.game.addReports(gameManager.vPhaseReport);
                    gameManager.communicationManager.sendReport(gameManager);
                    changePhase(GamePhase.END, gameManager);
                }
                break;
            case PHYSICAL_REPORT:
                changePhase(GamePhase.END, gameManager);
                break;
            case TARGETING:
                gameManager.vPhaseReport.addElement(new Report(1035, Report.PUBLIC));
                gameManager.resolveAllButWeaponAttacks();
                gameManager.resolveOnlyWeaponAttacks();
                gameManager.handleAttacks();
                // check reports
                if (gameManager.vPhaseReport.size() > 1) {
                    gameManager.game.addReports(gameManager.vPhaseReport);
                    changePhase(GamePhase.TARGETING_REPORT, gameManager);
                } else {
                    // just the header, so we'll add the <nothing> label
                    gameManager.vPhaseReport.addElement(new Report(1205, Report.PUBLIC));
                    gameManager.game.addReports(gameManager.vPhaseReport);
                    gameManager.communicationManager.sendReport(gameManager);
                    changePhase(GamePhase.PREMOVEMENT, gameManager);
                }

                gameManager.communicationManager.sendSpecialHexDisplayPackets(gameManager);
                for (Enumeration<Player> i = gameManager.game.getPlayers(); i.hasMoreElements(); ) {
                    Player player = i.nextElement();
                    int connId = player.getId();
                    gameManager.communicationManager.send(connId, gameManager.packetManager.createArtilleryPacket(player, gameManager));
                }

                break;
            case OFFBOARD:
                // write Offboard Attack Phase header
                gameManager.addReport(new Report(1100, Report.PUBLIC));
                gameManager.resolveAllButWeaponAttacks(); // torso twist or flip arms
                // possible
                gameManager.resolveOnlyWeaponAttacks(); // should only be TAG at this point
                gameManager.handleAttacks();
                for (Enumeration<Player> i = gameManager.game.getPlayers(); i.hasMoreElements(); ) {
                    Player player = i.nextElement();
                    int connId = player.getId();
                    gameManager.communicationManager.send(connId, gameManager.packetManager.createArtilleryPacket(player, gameManager));
                }
                gameManager.applyBuildingDamage();
                gameManager.checkForPSRFromDamage();
                gameManager.addReport((Report) gameManager.resolvePilotingRolls());

                gameManager.cleanupDestroyedNarcPods();
                gameManager.checkForFlawedCooling();

                gameManager.communicationManager.sendSpecialHexDisplayPackets(gameManager);
                gameManager.communicationManager.sendTagInfoUpdates(gameManager);

                // check reports
                if (gameManager.vPhaseReport.size() > 1) {
                    gameManager.game.addReports(gameManager.vPhaseReport);
                    changePhase(GamePhase.OFFBOARD_REPORT, gameManager);
                } else {
                    // just the header, so we'll add the <nothing> label
                    gameManager.addReport(new Report(1205, Report.PUBLIC));
                    gameManager.game.addReports(gameManager.vPhaseReport);
                    gameManager.communicationManager.sendReport(gameManager);
                    changePhase(GamePhase.PREFIRING, gameManager);
                }
                break;
            case OFFBOARD_REPORT:
                gameManager.communicationManager.sendSpecialHexDisplayPackets(gameManager);
                changePhase(GamePhase.PREFIRING, gameManager);
                break;
            case TARGETING_REPORT:
                changePhase(GamePhase.PREMOVEMENT, gameManager);
                break;
            case END:
                // remove any entities that died in the heat/end phase before
                // checking for victory
                gameManager.entityActionManager.resetEntityPhase(GamePhase.END, gameManager);
                boolean victory = gameManager.victory(); // note this may add reports
                // check phase report
                // HACK: hardcoded message ID check
                if ((gameManager.vPhaseReport.size() > 3) || ((gameManager.vPhaseReport.size() > 1)
                        && (gameManager.vPhaseReport.elementAt(1).messageId != 1205))) {
                    gameManager.game.addReports(gameManager.vPhaseReport);
                    changePhase(GamePhase.END_REPORT, gameManager);
                } else {
                    // just the heat and end headers, so we'll add
                    // the <nothing> label
                    gameManager.addReport(new Report(1205, Report.PUBLIC));
                    gameManager.game.addReports(gameManager.vPhaseReport);
                    gameManager.communicationManager.sendReport(gameManager);
                    if (victory) {
                        changePhase(GamePhase.VICTORY, gameManager);
                    } else {
                        changePhase(GamePhase.INITIATIVE, gameManager);
                    }
                }
                // Decrement the ASEWAffected counter
                gameManager.entityActionManager.decrementASEWTurns(gameManager);

                break;
            case END_REPORT:
                if (gameManager.changePlayersTeam) {
                    gameManager.playerManager.processTeamChangeRequest(gameManager);
                }
                if (gameManager.victory()) {
                    changePhase(GamePhase.VICTORY, gameManager);
                } else {
                    changePhase(GamePhase.INITIATIVE, gameManager);
                }
                break;
            case VICTORY:
                GameVictoryEvent gve = new GameVictoryEvent(gameManager, gameManager.game);
                gameManager.game.processGameEvent(gve);
                gameManager.communicationManager.transmitGameVictoryEventToAll(gameManager);
                gameManager.resetGame();
                break;
            default:
                break;
        }

        // Any hidden units that activated this phase, should clear their
        // activating phase
        for (Entity ent : gameManager.game.getEntitiesVector()) {
            if (ent.getHiddenActivationPhase() == gameManager.game.getPhase()) {
                ent.setHiddenActivationPhase(GamePhase.UNKNOWN);
            }
        }
    }

    /**
     * Do anything we seed to start the new phase, such as give a turn to the
     * first player to play.
     * @param phase
     * @param gameManager
     */
    void executePhase(GamePhase phase, GameManager gameManager) {
        switch (phase) {
            case EXCHANGE:
                gameManager.resetPlayersDone();
                // Update initial BVs, as things may have been modified in lounge
                for (Entity e : gameManager.game.getEntitiesVector()) {
                    e.setInitialBV(e.calculateBattleValue(false, false));
                }
                gameManager.calculatePlayerInitialCounts();
                // Build teams vector
                gameManager.game.setupTeams();
                gameManager.applyBoardSettings();
                gameManager.game.getPlanetaryConditions().determineWind();
                gameManager.communicationManager.send(gameManager.packetManager.createPlanetaryConditionsPacket(gameManager));
                // transmit the board to everybody
                gameManager.communicationManager.send(gameManager.packetManager.createBoardPacket(gameManager));
                gameManager.game.setupRoundDeployment();
                gameManager.game.setVictoryContext(new HashMap<>());
                gameManager.game.createVictoryConditions();
                // some entities may need to be checked and updated
                gameManager.checkEntityExchange();
                break;
            case MOVEMENT:
                // write Movement Phase header to report
                gameManager.addReport(new Report(2000, Report.PUBLIC));
            case PREMOVEMENT:
            case SET_ARTILLERY_AUTOHIT_HEXES:
            case DEPLOY_MINEFIELDS:
            case DEPLOYMENT:
            case PREFIRING:
            case FIRING:
            case PHYSICAL:
            case TARGETING:
            case OFFBOARD:
                gameManager.changeToNextTurn(-1);
                if (gameManager.game.getOptions().booleanOption(OptionsConstants.BASE_PARANOID_AUTOSAVE)) {
                    gameManager.gameStateManager.autoSave(gameManager);
                }
                break;
            default:
                break;
        }
    }

    /**
     * Prepares for, presumably, the next phase. This typically involves
     * resetting the states of entities in the game and making sure the client
     * has the information it needs for the new phase.
     *
     * @param phase the <code>int</code> id of the phase to prepare for
     * @param gameManager
     */
    void prepareForPhase(GamePhase phase, GameManager gameManager) {
        switch (phase) {
            case LOUNGE:
                gameManager.clearReports();
                MapSettings mapSettings = gameManager.game.getMapSettings();
                mapSettings.setBoardsAvailableVector(ServerBoardHelper.scanForBoards(mapSettings));
                mapSettings.setNullBoards(GameManager.DEFAULT_BOARD);
                gameManager.communicationManager.send(gameManager.communicationManager.packetManager.createMapSettingsPacket(gameManager));
                gameManager.communicationManager.send(gameManager.packetManager.createMapSizesPacket(gameManager));
                gameManager.playerManager.checkForObservers(gameManager);
                gameManager.communicationManager.transmitAllPlayerUpdates(gameManager);
                break;
            case INITIATIVE:
                // remove the last traces of last round
                gameManager.game.handleInitiativeCompensation();
                gameManager.game.resetActions();
                gameManager.game.resetTagInfo();
                gameManager.communicationManager.sendTagInfoReset(gameManager);
                gameManager.clearReports();
                gameManager.resetEntityRound();
                gameManager.entityActionManager.resetEntityPhase(phase, gameManager);
                gameManager.playerManager.checkForObservers(gameManager);
                gameManager.communicationManager.transmitAllPlayerUpdates(gameManager);

                // roll 'em
                gameManager.resetActivePlayersDone();
                gameManager.rollInitiative();
                //Cockpit command consoles that switched crew on the previous round are ineligible for force
                // commander initiative bonus. Now that initiative is rolled, clear the flag.
                gameManager.game.getEntities().forEachRemaining(e -> e.getCrew().resetActedFlag());

                if (!gameManager.game.shouldDeployThisRound()) {
                    gameManager.incrementAndSendGameRound();
                }

                // setIneligible(phase);
                gameManager.determineTurnOrder(phase);
                gameManager.writeInitiativeReport(false);

                // checks for environmental survival
                gameManager.checkForConditionDeath();

                gameManager.checkForBlueShieldDamage();
                if (gameManager.game.getBoard().inAtmosphere()) {
                    gameManager.checkForAtmosphereDeath();
                }
                if (gameManager.game.getBoard().inSpace()) {
                    gameManager.checkForSpaceDeath();
                }

                gameManager.bvCount.bvReports(true, gameManager);

                LogManager.getLogger().info("Round " + gameManager.game.getRoundCount() + " memory usage: " + MegaMek.getMemoryUsed());
                break;
            case DEPLOY_MINEFIELDS:
                gameManager.playerManager.checkForObservers(gameManager);
                gameManager.communicationManager.transmitAllPlayerUpdates(gameManager);
                gameManager.resetActivePlayersDone();
                gameManager.setIneligible(phase);

                Enumeration<Player> e = gameManager.game.getPlayers();
                Vector<GameTurn> turns = new Vector<>();
                while (e.hasMoreElements()) {
                    Player p = e.nextElement();
                    if (p.hasMinefields() && gameManager.game.getBoard().onGround()) {
                        GameTurn gt = new GameTurn(p.getId());
                        turns.addElement(gt);
                    }
                }
                gameManager.game.setTurnVector(turns);
                gameManager.game.resetTurnIndex();

                // send turns to all players
                gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));
                break;
            case SET_ARTILLERY_AUTOHIT_HEXES:
                gameManager.entityActionManager.deployOffBoardEntities(gameManager);
                gameManager.playerManager.checkForObservers(gameManager);
                gameManager.communicationManager.transmitAllPlayerUpdates(gameManager);
                gameManager.resetActivePlayersDone();
                gameManager.setIneligible(phase);

                Enumeration<Player> players = gameManager.game.getPlayers();
                Vector<GameTurn> turn = new Vector<>();

                // Walk through the players of the game, and add
                // a turn for all players with artillery weapons.
                while (players.hasMoreElements()) {
                    // Get the next player.
                    final Player p = players.nextElement();

                    // Does the player have any artillery-equipped units?
                    EntitySelector playerArtySelector = new EntitySelector() {
                        private Player owner = p;

                        @Override
                        public boolean accept(Entity entity) {
                            return owner.equals(entity.getOwner()) && entity.isEligibleForArtyAutoHitHexes();
                        }
                    };

                    if (gameManager.game.getSelectedEntities(playerArtySelector).hasNext()) {
                        // Yes, the player has arty-equipped units.
                        GameTurn gt = new GameTurn(p.getId());
                        turn.addElement(gt);
                    }
                }
                gameManager.game.setTurnVector(turn);
                gameManager.game.resetTurnIndex();

                // send turns to all players
                gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));
                break;
            case PREMOVEMENT:
            case MOVEMENT:
            case DEPLOYMENT:
            case PREFIRING:
            case FIRING:
            case PHYSICAL:
            case TARGETING:
            case OFFBOARD:
                gameManager.entityActionManager.deployOffBoardEntities(gameManager);

                // Check for activating hidden units
                if (gameManager.game.getOptions().booleanOption(OptionsConstants.ADVANCED_HIDDEN_UNITS)) {
                    for (Entity ent : gameManager.game.getEntitiesVector()) {
                        if (ent.getHiddenActivationPhase() == phase) {
                            ent.setHidden(false);
                        }
                    }
                }
                // Update visibility indications if using double blind.
                if (gameManager.doBlind()) {
                    gameManager.updateVisibilityIndicator(null);
                }
                gameManager.entityActionManager.resetEntityPhase(phase, gameManager);
                gameManager.playerManager.checkForObservers(gameManager);
                gameManager.communicationManager.transmitAllPlayerUpdates(gameManager);
                gameManager.resetActivePlayersDone();
                gameManager.setIneligible(phase);
                gameManager.determineTurnOrder(phase);
                gameManager.entityAllUpdate();
                gameManager.clearReports();
                gameManager.doTryUnstuck();
                break;
            case END:
                gameManager.entityActionManager.resetEntityPhase(phase, gameManager);
                gameManager.clearReports();
                gameManager.resolveHeat();
                if (gameManager.game.getPlanetaryConditions().isSandBlowing()
                        && (gameManager.game.getPlanetaryConditions().getWindStrength() > PlanetaryConditions.WI_LIGHT_GALE)) {
                    gameManager.addReport(gameManager.resolveBlowingSandDamage());
                }
                gameManager.addReport(gameManager.resolveControlRolls());
                gameManager.addReport(gameManager.checkForTraitors());
                // write End Phase header
                gameManager.addReport(new Report(5005, Report.PUBLIC));
                gameManager.addReport(gameManager.resolveInternalBombHits());
                gameManager.checkLayExplosives();
                gameManager.resolveHarJelRepairs();
                gameManager.resolveEmergencyCoolantSystem();
                gameManager.checkForSuffocation();
                gameManager.game.getPlanetaryConditions().determineWind();
                gameManager.communicationManager.send(gameManager.packetManager.createPlanetaryConditionsPacket(gameManager));

                gameManager.applyBuildingDamage();
                gameManager.addReport(gameManager.game.ageFlares());
                gameManager.communicationManager.send(gameManager.packetManager.createFlarePacket(gameManager));
                gameManager.resolveAmmoDumps();
                gameManager.resolveCrewWakeUp();
                gameManager.resolveConsoleCrewSwaps();
                gameManager.resolveSelfDestruct();
                gameManager.resolveShutdownCrashes();
                gameManager.checkForIndustrialEndOfTurn();
                gameManager.resolveMechWarriorPickUp();
                gameManager.resolveVeeINarcPodRemoval();
                gameManager.resolveFortify();

                gameManager.entityStatusReport();

                // Moved this to the very end because it makes it difficult to see
                // more important updates when you have 300+ messages of smoke filling
                // whatever hex. Please don't move it above the other things again.
                // Thanks! Ralgith - 2018/03/15
                gameManager.hexUpdateSet.clear();
                for (DynamicTerrainProcessor tp : gameManager.terrainProcessors) {
                    tp.doEndPhaseChanges(gameManager.vPhaseReport);
                }
                gameManager.communicationManager.sendChangedHexes(gameManager.hexUpdateSet, gameManager);

                gameManager.playerManager.checkForObservers(gameManager);
                gameManager.communicationManager.transmitAllPlayerUpdates(gameManager);
                gameManager.entityAllUpdate();
                break;
            case INITIATIVE_REPORT: {
                gameManager.gameStateManager.autoSave(gameManager);
            }
            case TARGETING_REPORT:
            case MOVEMENT_REPORT:
            case OFFBOARD_REPORT:
            case FIRING_REPORT:
            case PHYSICAL_REPORT:
            case END_REPORT:
                gameManager.resetActivePlayersDone();
                gameManager.communicationManager.sendReport(gameManager);
                gameManager.entityAllUpdate();
                if (gameManager.game.getOptions().booleanOption(OptionsConstants.BASE_PARANOID_AUTOSAVE)) {
                    gameManager.gameStateManager.autoSave(gameManager);
                }
                break;
            case VICTORY:
                gameManager.ratingManager.updatePlayersRating(gameManager);
                gameManager.resetPlayersDone();
                gameManager.clearReports();
                gameManager.communicationManager.send(gameManager.packetManager.createAllReportsPacket(gameManager));
                gameManager.reportManager.prepareVictoryReport(gameManager);
                gameManager.game.addReports(gameManager.vPhaseReport);
                // Before we send the full entities packet we need to loop
                // through the fighters in squadrons and damage them.
                for (Iterator<Entity> ents = gameManager.game.getEntities(); ents.hasNext(); ) {
                    Entity entity = ents.next();
                    if ((entity.isFighter()) && !(entity instanceof FighterSquadron)) {
                        if (entity.isPartOfFighterSquadron() || entity.isCapitalFighter()) {
                            ((IAero) entity).doDisbandDamage();
                        }
                    }
                    // fix the armor and SI of aeros if using aero sanity rules for
                    // the MUL
                    if (gameManager.game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_AERO_SANITY)
                            && (entity instanceof Aero)) {
                        // need to rescale SI and armor
                        int scale = 1;
                        if (entity.isCapitalScale()) {
                            scale = 10;
                        }
                        Aero a = (Aero) entity;
                        int currentSI = a.getSI() / (2 * scale);
                        a.set0SI(a.get0SI() / (2 * scale));
                        if (currentSI > 0) {
                            a.setSI(currentSI);
                        }
                        //Fix for #587. MHQ tracks fighters at standard scale and doesn't (currently)
                        //track squadrons. Squadrons don't save to MUL either, so... only convert armor for JS/WS/SS?
                        //Do we ever need to save capital fighter armor to the final MUL or entityStatus?
                        if (!entity.hasETypeFlag(Entity.ETYPE_JUMPSHIP)) {
                            scale = 1;
                        }
                        if (scale > 1) {
                            for (int loc = 0; loc < entity.locations(); loc++) {
                                int currentArmor = entity.getArmor(loc) / scale;
                                if (entity.getOArmor(loc) > 0) {
                                    entity.initializeArmor(entity.getOArmor(loc) / scale, loc);
                                }
                                if (entity.getArmor(loc) > 0) {
                                    entity.setArmor(currentArmor, loc);
                                }
                            }
                        }
                    }
                }
                EmailService mailer = Server.getServerInstance().getEmailService();
                if (mailer != null) {
                    for (var player: mailer.getEmailablePlayers(gameManager.game)) {
                        try {
                            var message = mailer.newReportMessage(
                                    gameManager.game, gameManager.vPhaseReport, player
                            );
                            mailer.send(message);
                        } catch (Exception ex) {
                            LogManager.getLogger().error("Error sending email" + ex);
                        }
                    }
                }
                gameManager.communicationManager.send(gameManager.packetManager.createFullEntitiesPacket(gameManager));
                gameManager.communicationManager.send(gameManager.communicationManager.packetManager.createReportPacket(null, gameManager));
                gameManager.communicationManager.send(gameManager.packetManager.createEndOfGamePacket(gameManager));
                break;
            default:
                break;
        }
    }

    /**
     * automatically save the game
     * @param gameManager
     */
    public void autoSave(GameManager gameManager) {
        String fileName = "autosave";
        if (PreferenceManager.getClientPreferences().stampFilenames()) {
            fileName = StringUtil.addDateTimeStamp(fileName);
        }
        gameManager.gameStateManager.saveGame(fileName, gameManager.getGame().getOptions().booleanOption(OptionsConstants.BASE_AUTOSAVE_MSG), gameManager);
    }

    /**
     * save the game
     *  @param sFile    The <code>String</code> filename to use
     * @param sendChat A <code>boolean</code> value whether or not to announce the
     * @param gameManager
     */
    public void saveGame(String sFile, boolean sendChat, GameManager gameManager) {
        // We need to strip the .gz if it exists,
        // otherwise we'll double up on it.
        if (sFile.endsWith(".gz")) {
            sFile = sFile.replace(".gz", "");
        }

        String sFinalFile = sFile;
        if (!sFinalFile.endsWith(MMConstants.SAVE_FILE_EXT)) {
            sFinalFile = sFile + MMConstants.SAVE_FILE_EXT;
        }
        File sDir = new File(MMConstants.SAVEGAME_DIR);
        if (!sDir.exists()) {
            sDir.mkdir();
        }

        sFinalFile = sDir + File.separator + sFinalFile;

        try (OutputStream os = new FileOutputStream(sFinalFile + ".gz");
             OutputStream gzo = new GZIPOutputStream(os);
             Writer writer = new OutputStreamWriter(gzo, StandardCharsets.UTF_8)) {
            SerializationHelper.getSaveGameXStream().toXML(gameManager.getGame(), writer);
        } catch (Exception e) {
            LogManager.getLogger().error("Unable to save file: " + sFinalFile, e);
        }

        if (sendChat) {
            gameManager.communicationManager.sendChat("MegaMek", "Game saved to " + sFinalFile);
        }
    }
}
