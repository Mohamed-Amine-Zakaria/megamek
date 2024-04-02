package megamek.server.gameManager;

import megamek.MMConstants;
import megamek.MegaMek;
import megamek.common.*;
import megamek.common.enums.GamePhase;
import megamek.common.event.GameVictoryEvent;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.preference.PreferenceManager;
import megamek.common.util.BoardUtilities;
import megamek.common.util.EmailService;
import megamek.common.util.SerializationHelper;
import megamek.common.util.StringUtil;
import megamek.common.util.fileUtils.MegaMekFile;
import megamek.common.weapons.AttackHandler;
import megamek.common.weapons.WeaponHandler;
import megamek.server.DynamicTerrainProcessor;
import megamek.server.Server;
import megamek.server.ServerBoardHelper;
import megamek.server.ServerHelper;
import megamek.server.victory.VictoryResult;
import org.apache.logging.log4j.LogManager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
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
                gameManager.utilityManager.resolveWhatPlayersCanSeeWhatUnits(gameManager);
                gameManager.utilityManager.detectSpacecraft(gameManager);
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
                gameManager.utilityManager.detectHiddenUnits(gameManager);
                ServerHelper.detectMinefields(gameManager.game, gameManager.vPhaseReport, gameManager);
                gameManager.utilityManager.updateSpacecraftDetection(gameManager);
                gameManager.utilityManager.detectSpacecraft(gameManager);
                gameManager.utilityManager.resolveWhatPlayersCanSeeWhatUnits(gameManager);
                gameManager.environmentalEffectManager.doAllAssaultDrops(gameManager);
                gameManager.utilityManager.addMovementHeat(gameManager);
                gameManager.environmentalEffectManager.applyBuildingDamage(gameManager);
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
                gameManager.utilityManager.resolveWhatPlayersCanSeeWhatUnits(gameManager);
                gameManager.resolveAllButWeaponAttacks();
                gameManager.combatManager.resolveSelfDestructions(gameManager);
                gameManager.reportGhostTargetRolls();
                gameManager.reportManager.reportLargeCraftECCMRolls(gameManager);
                gameManager.combatManager.resolveOnlyWeaponAttacks(gameManager);
                gameManager.utilityManager.assignAMS(gameManager);
                gameManager.handleAttacks();
                gameManager.resolveScheduledNukes();
                gameManager.environmentalEffectManager.applyBuildingDamage(gameManager);
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
                gameManager.utilityManager.resolveWhatPlayersCanSeeWhatUnits(gameManager);
                gameManager.entityActionManager.resolvePhysicalAttacks(gameManager);
                gameManager.environmentalEffectManager.applyBuildingDamage(gameManager);
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
                gameManager.combatManager.resolveOnlyWeaponAttacks(gameManager);
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
                gameManager.combatManager.resolveOnlyWeaponAttacks(gameManager); // should only be TAG at this point
                gameManager.handleAttacks();
                for (Enumeration<Player> i = gameManager.game.getPlayers(); i.hasMoreElements(); ) {
                    Player player = i.nextElement();
                    int connId = player.getId();
                    gameManager.communicationManager.send(connId, gameManager.packetManager.createArtilleryPacket(player, gameManager));
                }
                gameManager.environmentalEffectManager.applyBuildingDamage(gameManager);
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
                boolean victory = gameManager.gameStateManager.victory(gameManager); // note this may add reports
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
                if (gameManager.gameStateManager.victory(gameManager)) {
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
                gameManager.playerManager.resetPlayersDone(gameManager);
                // Update initial BVs, as things may have been modified in lounge
                for (Entity e : gameManager.game.getEntitiesVector()) {
                    e.setInitialBV(e.calculateBattleValue(false, false));
                }
                gameManager.gameStateManager.calculatePlayerInitialCounts(gameManager);
                // Build teams vector
                gameManager.game.setupTeams();
                gameManager.gameStateManager.applyBoardSettings(gameManager);
                gameManager.game.getPlanetaryConditions().determineWind();
                gameManager.communicationManager.send(gameManager.packetManager.createPlanetaryConditionsPacket(gameManager));
                // transmit the board to everybody
                gameManager.communicationManager.send(gameManager.packetManager.createBoardPacket(gameManager));
                gameManager.game.setupRoundDeployment();
                gameManager.game.setVictoryContext(new HashMap<>());
                gameManager.game.createVictoryConditions();
                // some entities may need to be checked and updated
                gameManager.gameStateManager.checkEntityExchange(gameManager);
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
                gameManager.gameStateManager.changeToNextTurn(-1, gameManager);
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
                gameManager.reportManager.clearReports(gameManager);
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
                gameManager.reportManager.clearReports(gameManager);
                gameManager.entityActionManager.resetEntityRound(gameManager);
                gameManager.entityActionManager.resetEntityPhase(phase, gameManager);
                gameManager.playerManager.checkForObservers(gameManager);
                gameManager.communicationManager.transmitAllPlayerUpdates(gameManager);

                // roll 'em
                gameManager.playerManager.resetActivePlayersDone(gameManager);
                gameManager.gameStateManager.rollInitiative(gameManager);
                //Cockpit command consoles that switched crew on the previous round are ineligible for force
                // commander initiative bonus. Now that initiative is rolled, clear the flag.
                gameManager.game.getEntities().forEachRemaining(e -> e.getCrew().resetActedFlag());

                if (!gameManager.game.shouldDeployThisRound()) {
                    gameManager.gameStateManager.incrementAndSendGameRound(gameManager);
                }

                // setIneligible(phase);
                gameManager.gameStateManager.determineTurnOrder(phase, gameManager);
                gameManager.reportManager.writeInitiativeReport(false, gameManager);

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
                gameManager.playerManager.resetActivePlayersDone(gameManager);
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
                gameManager.playerManager.resetActivePlayersDone(gameManager);
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
                if (gameManager.environmentalEffectManager.doBlind(gameManager)) {
                    gameManager.updateVisibilityIndicator(null);
                }
                gameManager.entityActionManager.resetEntityPhase(phase, gameManager);
                gameManager.playerManager.checkForObservers(gameManager);
                gameManager.communicationManager.transmitAllPlayerUpdates(gameManager);
                gameManager.playerManager.resetActivePlayersDone(gameManager);
                gameManager.setIneligible(phase);
                gameManager.gameStateManager.determineTurnOrder(phase, gameManager);
                gameManager.entityActionManager.entityAllUpdate(gameManager);
                gameManager.reportManager.clearReports(gameManager);
                gameManager.entityActionManager.doTryUnstuck(gameManager);
                break;
            case END:
                gameManager.entityActionManager.resetEntityPhase(phase, gameManager);
                gameManager.reportManager.clearReports(gameManager);
                gameManager.resolveHeat();
                if (gameManager.game.getPlanetaryConditions().isSandBlowing()
                        && (gameManager.game.getPlanetaryConditions().getWindStrength() > PlanetaryConditions.WI_LIGHT_GALE)) {
                    gameManager.reportManager.addReport(gameManager.environmentalEffectManager.resolveBlowingSandDamage(gameManager), gameManager);
                }
                gameManager.reportManager.addReport(gameManager.resolveControlRolls(), gameManager);
                gameManager.reportManager.addReport(gameManager.checkForTraitors(), gameManager);
                // write End Phase header
                gameManager.addReport(new Report(5005, Report.PUBLIC));
                gameManager.reportManager.addReport(gameManager.resolveInternalBombHits(), gameManager);
                gameManager.checkLayExplosives();
                gameManager.resolveHarJelRepairs();
                gameManager.resolveEmergencyCoolantSystem();
                gameManager.checkForSuffocation();
                gameManager.game.getPlanetaryConditions().determineWind();
                gameManager.communicationManager.send(gameManager.packetManager.createPlanetaryConditionsPacket(gameManager));

                gameManager.environmentalEffectManager.applyBuildingDamage(gameManager);
                gameManager.reportManager.addReport(gameManager.game.ageFlares(), gameManager);
                gameManager.communicationManager.send(gameManager.packetManager.createFlarePacket(gameManager));
                gameManager.resolveAmmoDumps();
                gameManager.resolveCrewWakeUp();
                gameManager.resolveConsoleCrewSwaps();
                gameManager.resolveSelfDestruct();
                gameManager.resolveShutdownCrashes();
                gameManager.checkForIndustrialEndOfTurn();
                gameManager.entityActionManager.resolveMechWarriorPickUp(gameManager);
                gameManager.entityActionManager.resolveVeeINarcPodRemoval(gameManager);
                gameManager.environmentalEffectManager.resolveFortify(gameManager);

                gameManager.reportManager.entityStatusReport(gameManager);

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
                gameManager.entityActionManager.entityAllUpdate(gameManager);
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
                gameManager.playerManager.resetActivePlayersDone(gameManager);
                gameManager.communicationManager.sendReport(gameManager);
                gameManager.entityActionManager.entityAllUpdate(gameManager);
                if (gameManager.game.getOptions().booleanOption(OptionsConstants.BASE_PARANOID_AUTOSAVE)) {
                    gameManager.gameStateManager.autoSave(gameManager);
                }
                break;
            case VICTORY:
                gameManager.ratingManager.updatePlayersRating(gameManager);
                gameManager.playerManager.resetPlayersDone(gameManager);
                gameManager.reportManager.clearReports(gameManager);
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

    void _setGame(IGame g, GameManager gameManager) {
        if (!(g instanceof Game)) {
            LogManager.getLogger().error("Attempted to set game to incorrect class.");
            return;
        }
        gameManager.game = (Game) g;

        List<Integer> orphanEntities = new ArrayList<>();

        // reattach the transient fields and ghost the players
        for (Entity entity : gameManager.game.getEntitiesVector()) {
            entity.setGame(gameManager.game);

            if (entity.getOwner() == null) {
                orphanEntities.add(entity.getId());
                continue;
            }

            if (entity instanceof Mech) {
                ((Mech) entity).setBAGrabBars();
                ((Mech) entity).setProtomechClampMounts();
            }
            if (entity instanceof Tank) {
                ((Tank) entity).setBAGrabBars();
            }
        }

        gameManager.game.removeEntities(orphanEntities, IEntityRemovalConditions.REMOVE_UNKNOWN);

        gameManager.game.setOutOfGameEntitiesVector(gameManager.game.getOutOfGameEntitiesVector());
        for (Player player : gameManager.game.getPlayersList()) {
            player.setGame(gameManager.game);
            player.setGhost(true);
        }

        // might need to restore weapon type for some attacks that take multiple
        // turns (like artillery)
        for (AttackHandler handler : gameManager.game.getAttacksVector()) {
            if (handler instanceof WeaponHandler) {
                ((WeaponHandler) handler).restore();
            }
        }

        gameManager.game.getForces().setGame(gameManager.game);
    }

    void _resetGame(GameManager gameManager) {
        // remove all entities
        gameManager.getGame().reset();
        gameManager.communicationManager.send(gameManager.packetManager.createEntitiesPacket(gameManager));
        gameManager.communicationManager.send(new Packet(PacketCommand.SENDING_MINEFIELDS, new Vector<>()));

        // remove ghosts
        List<Player> ghosts = new ArrayList<>();
        for (Enumeration<Player> players = gameManager.getGame().getPlayers(); players.hasMoreElements(); ) {
            Player p = players.nextElement();
            if (p.isGhost()) {
                ghosts.add(p);
            } else {
                // non-ghosts set their starting positions to any
                p.setStartingPos(Board.START_ANY);
                gameManager.communicationManager.transmitPlayerUpdate(p);
            }
        }

        for (Player p : ghosts) {
            gameManager.getGame().removePlayer(p.getId());
            gameManager.communicationManager.send(new Packet(PacketCommand.PLAYER_REMOVE, p.getId()));
        }

        // reset all players
        gameManager.playerManager.resetPlayersDone(gameManager);

        // Write end of game to stdout so controlling scripts can rotate logs.
        LogManager.getLogger().info(LocalDateTime.now() + " END OF GAME");

        if (Server.getServerInstance().getEmailService() != null) {
            Server.getServerInstance().getEmailService().reset();
        }

        changePhase(GamePhase.LOUNGE, gameManager);
    }

    /**
     * Tries to change to the next turn. If there are no more turns, ends the
     * current phase. If the player whose turn it is next is not connected, we
     * allow the other players to skip that player.
     * @param prevPlayerId
     * @param gameManager
     */
    void changeToNextTurn(int prevPlayerId, GameManager gameManager) {
        boolean minefieldPhase = gameManager.game.getPhase().isDeployMinefields();
        boolean artyPhase = gameManager.game.getPhase().isSetArtilleryAutohitHexes();

        GameTurn nextTurn = null;
        Entity nextEntity = null;
        while (gameManager.game.hasMoreTurns() && (null == nextEntity)) {
            nextTurn = gameManager.game.changeToNextTurn();
            nextEntity = gameManager.game.getEntity(gameManager.game.getFirstEntityNum(nextTurn));
            if (minefieldPhase || artyPhase) {
                break;
            }
        }

        // if there aren't any more valid turns, end the phase
        // note that some phases don't use entities
        if (((null == nextEntity) && !minefieldPhase) || ((null == nextTurn) && minefieldPhase)) {
            endCurrentPhase(gameManager);
            return;
        }

        Player player = gameManager.game.getPlayer(nextTurn.getPlayerNum());

        if ((player != null) && (gameManager.game.getEntitiesOwnedBy(player) == 0)) {
            gameManager.gameStateManager.endCurrentTurn(null, gameManager);
            return;
        }

        if (prevPlayerId != -1) {
            gameManager.communicationManager.send(gameManager.packetManager.createTurnIndexPacket(prevPlayerId, gameManager));
        } else {
            gameManager.communicationManager.send(gameManager.packetManager.createTurnIndexPacket(player != null ? player.getId() : Player.PLAYER_NONE, gameManager));
        }

        if ((null != player) && player.isGhost()) {
            gameManager.communicationManager.sendGhostSkipMessage(player, gameManager);
        } else if ((null == gameManager.game.getFirstEntity()) && (null != player) && !minefieldPhase && !artyPhase) {
            gameManager.packetManager.sendTurnErrorSkipMessage(player, gameManager);
        }
    }

    /**
     * Skips the current turn. This only makes sense in phases that have turns.
     * Operates by finding an entity to move and then doing nothing with it.
     * @param gameManager
     */
    public void skipCurrentTurn(GameManager gameManager) {
        // find an entity to skip...
        Entity toSkip = gameManager.game.getFirstEntity();

        switch (gameManager.game.getPhase()) {
            case DEPLOYMENT:
                // allow skipping during deployment,
                // we need that when someone removes a unit.
                gameManager.gameStateManager.endCurrentTurn(null, gameManager);
                break;
            case MOVEMENT:
                if (toSkip != null) {
                    gameManager.entityActionManager.processMovement(toSkip, new MovePath(gameManager.game, toSkip), null, gameManager);
                }
                gameManager.gameStateManager.endCurrentTurn(toSkip, gameManager);
                break;
            case FIRING:
            case PHYSICAL:
            case TARGETING:
            case OFFBOARD:
                if (toSkip != null) {
                    gameManager.entityActionManager.processAttack(toSkip, new Vector<>(0), gameManager);
                }
                gameManager.gameStateManager.endCurrentTurn(toSkip, gameManager);
                break;
            case PREMOVEMENT:
            case PREFIRING:
                gameManager.gameStateManager.endCurrentTurn(toSkip, gameManager);
            default:
                break;
        }
    }

    /**
     * Returns true if the current turn may be skipped. Ghost players' turns are
     * skippable, and a turn should be skipped if there's nothing to move.
     * @param gameManager
     */
    public boolean isTurnSkippable(GameManager gameManager) {
        GameTurn turn = gameManager.game.getTurn();
        if (null == turn) {
            return false;
        }
        Player player = gameManager.game.getPlayer(turn.getPlayerNum());
        return (null == player) || player.isGhost() || (gameManager.game.getFirstEntity() == null);
    }

    /**
     * Returns true if victory conditions have been met. Victory conditions are
     * when there is only one player left with mechs or only one team. will also
     * add some reports to reporting
     * @param gameManager
     */
    public boolean victory(GameManager gameManager) {
        VictoryResult vr = gameManager.game.getVictoryResult();
        for (Report r : vr.processVictory(gameManager.game)) {
            gameManager.addReport(r);
        }
        return vr.victory();
    }// end victory

    protected boolean isPlayerForcedVictory(GameManager gameManager) {
        // check game options
        if (!gameManager.game.getOptions().booleanOption(OptionsConstants.VICTORY_SKIP_FORCED_VICTORY)) {
            return false;
        }

        if (!gameManager.game.isForceVictory()) {
            return false;
        }

        for (Player player : gameManager.game.getPlayersVector()) {
            if ((player.getId() == gameManager.game.getVictoryPlayerId()) || ((player.getTeam() == gameManager.game.getVictoryTeam())
                    && (gameManager.game.getVictoryTeam() != Player.TEAM_NONE))) {
                continue;
            }

            if (!player.admitsDefeat()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Increment's the server's game round and send it to all the clients
     * @param gameManager
     */
    protected void incrementAndSendGameRound(GameManager gameManager) {
        gameManager.game.incrementRoundCount();
        gameManager.communicationManager.send(new Packet(PacketCommand.ROUND_UPDATE, gameManager.getGame().getRoundCount()));
    }

    /**
     * Calculates the initial count and BV for all players, and thus should only be called at the
     * start of a game
     * @param gameManager
     */
    public void calculatePlayerInitialCounts(GameManager gameManager) {
        for (final Enumeration<Player> players = gameManager.game.getPlayers(); players.hasMoreElements(); ) {
            final Player player = players.nextElement();
            player.setInitialEntityCount(Math.toIntExact(gameManager.game.getPlayerEntities(player, false).stream()
                    .filter(entity -> !entity.isDestroyed() && !entity.isTrapped()).count()));
            player.setInitialBV(player.getBV());
        }
    }

    /**
     * loop through entities in the exchange phase (i.e. after leaving
     * chat lounge) and do any actions that need to be done
     * @param gameManager
     */
    public void checkEntityExchange(GameManager gameManager) {
        for (Iterator<Entity> entities = gameManager.game.getEntities(); entities.hasNext(); ) {
            Entity entity = entities.next();
            // apply bombs
            if (entity.isBomber()) {
                ((IBomber) entity).applyBombs();
            }

            if (entity.isAero()) {
                IAero a = (IAero) entity;
                if (a.isSpaceborne()) {
                    // altitude and elevation don't matter in space
                    a.liftOff(0);
                } else {
                    // check for grounding
                    if (gameManager.game.getBoard().inAtmosphere() && !entity.isAirborne()) {
                        // you have to be airborne on the atmospheric map
                        a.liftOff(entity.getAltitude());
                    }
                }

                if (entity.isFighter()) {
                    a.updateWeaponGroups();
                    entity.loadAllWeapons();
                }
            }

            // if units were loaded in the chat lounge, I need to keep track of
            // it here because they can get dumped in the deployment phase
            if (!entity.getLoadedUnits().isEmpty()) {
                Vector<Integer> v = new Vector<>();
                for (Entity en : entity.getLoadedUnits()) {
                    v.add(en.getId());
                }
                entity.setLoadedKeepers(v);
            }

            if (gameManager.game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_AERO_SANITY)
                    && (entity.isAero())) {
                Aero a = null;
                if (entity instanceof Aero) {
                    a = (Aero) entity;
                }
                if (entity.isCapitalScale()) {
                    if (a != null) {
                        int currentSI = a.getSI() * 20;
                        a.initializeSI(a.get0SI() * 20);
                        a.setSI(currentSI);
                    }
                    if (entity.isCapitalFighter()) {
                        ((IAero) entity).autoSetCapArmor();
                        ((IAero) entity).autoSetFatalThresh();
                    } else {
                        // all armor and SI is going to be at standard scale, so
                        // we need to adjust
                        for (int loc = 0; loc < entity.locations(); loc++) {
                            if (entity.getArmor(loc) > 0) {
                                int currentArmor = entity.getArmor(loc) * 10;
                                entity.initializeArmor(entity.getOArmor(loc) * 10, loc);
                                entity.setArmor(currentArmor, loc);

                            }
                        }
                    }
                } else if (a != null) {
                    int currentSI = a.getSI() * 2;
                    a.initializeSI(a.get0SI() * 2);
                    a.setSI(currentSI);
                }
            }
            if (entity.getsAutoExternalSearchlight()) {
                entity.setExternalSearchlight(true);
            }
            gameManager.entityActionManager.entityUpdate(entity.getId(), gameManager);

            // Remove hot-loading some from LRMs for meks
            if (!gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_HOTLOAD_IN_GAME)) {
                for (Entity e : gameManager.game.getEntitiesVector()) {
                    // Vehicles are allowed to hot load, just meks cannot
                    if (!(e instanceof Mech)) {
                        continue;
                    }
                    for (Mounted weapon : e.getWeaponList()) {
                        weapon.getType().removeMode("HotLoad");
                    }
                    for (Mounted ammo : e.getAmmo()) {
                        ammo.getType().removeMode("HotLoad");
                    }
                }
            }
        }
    }

    /**
     * Rolls initiative for all the players.
     * @param gameManager
     */
    protected void rollInitiative(GameManager gameManager) {
        if (gameManager.game.getOptions().booleanOption(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE)) {
            TurnOrdered.rollInitiative(gameManager.game.getEntitiesVector(), false);
        } else {
            // Roll for initiative on the teams.
            TurnOrdered.rollInitiative(gameManager.game.getTeams(),
                    gameManager.game.getOptions().booleanOption(OptionsConstants.INIT_INITIATIVE_STREAK_COMPENSATION)
                            && !gameManager.game.shouldDeployThisRound());
        }

        gameManager.communicationManager.transmitAllPlayerUpdates(gameManager);
    }

    /**
     * Determines the turn oder for a given phase (with individual init)
     *
     * @param phase the <code>int</code> id of the phase
     * @param gameManager
     */
    protected void determineTurnOrderIUI(GamePhase phase, GameManager gameManager) {
        for (Iterator<Entity> loop = gameManager.game.getEntities(); loop.hasNext();) {
            final Entity entity = loop.next();
            entity.resetOtherTurns();
            if (entity.isSelectableThisTurn()) {
                entity.incrementOtherTurns();
            }
        }

        List<Entity> entities;
        // If the protos move multi option isn't on, protos move as a unit
        // Need to adjust entities vector otherwise we'll have too many turns
        // when first proto in a unit moves, new turns get added so rest of the
        // unit will move
        boolean protosMoveMulti = gameManager.game.getOptions().booleanOption(
                OptionsConstants.INIT_PROTOS_MOVE_MULTI);
        if (!protosMoveMulti) {
            entities = new ArrayList<>(gameManager.game.getEntitiesVector().size());
            Set<Short> movedUnits = new HashSet<>();
            for (Entity e : gameManager.game.getEntitiesVector()) {
                // This only effects Protos for the time being
                if (!(e instanceof Protomech)) {
                    entities.add(e);
                    continue;
                }
                short unitNumber = e.getUnitNumber();
                if ((unitNumber == Entity.NONE)
                        || !movedUnits.contains(unitNumber)) {
                    entities.add(e);
                    if (unitNumber != Entity.NONE) {
                        movedUnits.add(unitNumber);
                    }
                }
            }
        } else {
            entities = gameManager.game.getEntitiesVector();
        }

        String noInitiative = entities.stream()
                .filter(e -> e.getInitiative().size() == 0)
                .map(Object::toString)
                .collect(Collectors.joining(";"));

        if (!noInitiative.isEmpty()) {
            LogManager.getLogger().error("No Initiative rolled for: " + noInitiative);
        }

        // Now, generate the global order of all teams' turns.
        TurnVectors team_order = TurnOrdered.generateTurnOrder(entities, gameManager.game);

        // Now, we collect everything into a single vector.
        Vector<GameTurn> turns = gameManager.gameStateManager.initGameTurnsWithStranded(team_order, gameManager);

        // add the turns (this is easy)
        while (team_order.hasMoreElements()) {
            Entity e = (Entity) team_order.nextElement();
            if (e.isSelectableThisTurn()) {
                if (!protosMoveMulti && (e instanceof Protomech) && (e.getUnitNumber() != Entity.NONE)) {
                    turns.addElement(new GameTurn.UnitNumberTurn(e.getOwnerId(), e.getUnitNumber()));
                } else {
                    turns.addElement(new GameTurn.SpecificEntityTurn(e.getOwnerId(), e.getId()));
                }
            }
        }

        // set fields in game
        gameManager.game.setTurnVector(turns);
        gameManager.game.resetTurnIndex();

        // send turns to all players
        gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));
    }

    /**
     * Determines the turn order for a given phase
     *
     * @param phase the <code>int</code> id of the phase
     * @param gameManager
     */
    protected void determineTurnOrder(GamePhase phase, GameManager gameManager) {
        if (gameManager.game.getOptions().booleanOption(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE)) {
            determineTurnOrderIUI(phase, gameManager);
            return;
        }
        // and/or deploy even according to game options.
        boolean infMoveEven = (gameManager.game.getOptions().booleanOption(OptionsConstants.INIT_INF_MOVE_EVEN)
                && (gameManager.game.getPhase().isInitiative() || gameManager.game.getPhase().isMovement()))
                || (gameManager.game.getOptions().booleanOption(OptionsConstants.INIT_INF_DEPLOY_EVEN)
                        && gameManager.game.getPhase().isDeployment());
        boolean infMoveMulti = gameManager.game.getOptions().booleanOption(OptionsConstants.INIT_INF_MOVE_MULTI)
                && (gameManager.game.getPhase().isInitiative() || gameManager.game.getPhase().isMovement()
                        || gameManager.game.getPhase().isDeployment());
        boolean protosMoveEven = (gameManager.game.getOptions().booleanOption(OptionsConstants.INIT_PROTOS_MOVE_EVEN)
                && (gameManager.game.getPhase().isInitiative() || gameManager.game.getPhase().isMovement()
                        || gameManager.game.getPhase().isDeployment()))
                || (gameManager.game.getOptions().booleanOption(OptionsConstants.INIT_PROTOS_MOVE_EVEN)
                        && gameManager.game.getPhase().isDeployment());
        boolean protosMoveMulti = gameManager.game.getOptions().booleanOption(OptionsConstants.INIT_PROTOS_MOVE_MULTI);
        boolean protosMoveByPoint = !protosMoveMulti;
        boolean tankMoveByLance = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_VEHICLE_LANCE_MOVEMENT)
                && (gameManager.game.getPhase().isInitiative() || gameManager.game.getPhase().isMovement()
                        || gameManager.game.getPhase().isDeployment());
        boolean mekMoveByLance = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_MEK_LANCE_MOVEMENT)
                && (gameManager.game.getPhase().isInitiative() || gameManager.game.getPhase().isMovement()
                        || gameManager.game.getPhase().isDeployment());

        int evenMask = 0;
        if (infMoveEven) {
            evenMask += GameTurn.CLASS_INFANTRY;
        }

        if (protosMoveEven) {
            evenMask += GameTurn.CLASS_PROTOMECH;
        }
        // Reset all of the Players' turn category counts
        for (Enumeration<Player> loop = gameManager.game.getPlayers(); loop.hasMoreElements(); ) {
            final Player player = loop.nextElement();
            player.resetEvenTurns();
            player.resetMultiTurns();
            player.resetOtherTurns();
            player.resetSpaceStationTurns();
            player.resetJumpshipTurns();
            player.resetWarshipTurns();
            player.resetDropshipTurns();
            player.resetSmallCraftTurns();
            player.resetAeroTurns();

            // Add turns for ProtoMechs weapons declaration.
            if (protosMoveByPoint) {

                // How many ProtoMechs does the player have?
                Iterator<Entity> playerProtos = gameManager.game.getSelectedEntities(new EntitySelector() {
                    protected final int ownerId = player.getId();

                    @Override
                    public boolean accept(Entity entity) {
                        return (entity instanceof Protomech)
                                && (ownerId == entity.getOwnerId())
                                && entity.isSelectableThisTurn();
                    }
                });
                HashSet<Integer> points = new HashSet<>();
                int numPlayerProtos = 0;
                for (; playerProtos.hasNext(); ) {
                    Entity proto = playerProtos.next();
                    numPlayerProtos++;
                    points.add((int) proto.getUnitNumber());
                }
                int numProtoUnits = (int) Math.ceil(numPlayerProtos / 5.0);
                if (!protosMoveEven) {
                    numProtoUnits = points.size();
                }
                for (int unit = 0; unit < numProtoUnits; unit++) {
                    if (protosMoveEven) {
                        player.incrementEvenTurns();
                    } else {
                        player.incrementOtherTurns();
                    }
                }

            } // End handle-proto-firing-turns

        } // Handle the next player

        // Go through all entities, and update the turn categories of the
        // entity's player. The teams get their totals from their players.
        // N.B. ProtoMechs declare weapons fire based on their point.
        for (Iterator<Entity> loop = gameManager.game.getEntities(); loop.hasNext();) {
            final Entity entity = loop.next();
            if (entity.isSelectableThisTurn()) {
                final Player player = entity.getOwner();
                if ((entity instanceof SpaceStation)
                        && (gameManager.game.getPhase().isMovement() || gameManager.game.getPhase().isDeployment())) {
                    player.incrementSpaceStationTurns();
                } else if ((entity instanceof Warship)
                        && (gameManager.game.getPhase().isMovement() || gameManager.game.getPhase().isDeployment())) {
                    player.incrementWarshipTurns();
                } else if ((entity instanceof Jumpship)
                        && (gameManager.game.getPhase().isMovement() || gameManager.game.getPhase().isDeployment())) {
                    player.incrementJumpshipTurns();
                } else if ((entity instanceof Dropship) && entity.isAirborne()
                        && (gameManager.game.getPhase().isMovement() || gameManager.game.getPhase().isDeployment())) {
                    player.incrementDropshipTurns();
                } else if ((entity instanceof SmallCraft) && entity.isAirborne()
                        && (gameManager.game.getPhase().isMovement() || gameManager.game.getPhase().isDeployment())) {
                    player.incrementSmallCraftTurns();
                } else if (entity.isAirborne()
                        && (gameManager.game.getPhase().isMovement() || gameManager.game.getPhase().isDeployment())) {
                    player.incrementAeroTurns();
                } else if ((entity instanceof Infantry)) {
                    if (infMoveEven) {
                        player.incrementEvenTurns();
                    } else if (infMoveMulti) {
                        player.incrementMultiTurns(GameTurn.CLASS_INFANTRY);
                    } else {
                        player.incrementOtherTurns();
                    }
                } else if (entity instanceof Protomech) {
                    if (!protosMoveByPoint) {
                        if (protosMoveEven) {
                            player.incrementEvenTurns();
                        } else if (protosMoveMulti) {
                            player.incrementMultiTurns(GameTurn.CLASS_PROTOMECH);
                        } else {
                            player.incrementOtherTurns();
                        }
                    }
                } else if ((entity instanceof Tank) && tankMoveByLance) {
                    player.incrementMultiTurns(GameTurn.CLASS_TANK);
                } else if ((entity instanceof Mech) && mekMoveByLance) {
                    player.incrementMultiTurns(GameTurn.CLASS_MECH);
                } else {
                    player.incrementOtherTurns();
                }
            }
        }

        // Generate the turn order for the Players *within*
        // each Team. Map the teams to their turn orders.
        // Count the number of teams moving this turn.
        int nTeams = gameManager.game.getNoOfTeams();
        Hashtable<Team, TurnVectors> allTeamTurns = new Hashtable<>(nTeams);
        Hashtable<Team, int[]> evenTrackers = new Hashtable<>(nTeams);
        int numTeamsMoving = 0;
        for (Team team : gameManager.game.getTeams()) {
            allTeamTurns.put(team, team.determineTeamOrder(gameManager.game));

            // Track both the number of times we've checked the team for
            // "leftover" turns, and the number of "leftover" turns placed.
            int[] evenTracker = new int[2];
            evenTrackers.put(team, evenTracker);

            // Count this team if it has any "normal" moves.
            if (team.getNormalTurns(gameManager.game) > 0) {
                numTeamsMoving++;
            }
        }

        // Now, generate the global order of all teams' turns.
        TurnVectors team_order = TurnOrdered.generateTurnOrder(gameManager.game.getTeams(), gameManager.game);

        // Now, we collect everything into a single vector.
        Vector<GameTurn> turns = gameManager.gameStateManager.initGameTurnsWithStranded(team_order, gameManager);

        // Walk through the global order, assigning turns
        // for individual players to the single vector.
        // Keep track of how many turns we've added to the vector.
        Team prevTeam = null;
        int min = team_order.getMin();
        for (int numTurn = 0; team_order.hasMoreElements(); numTurn++) {
            Team team = (Team) team_order.nextElement();
            TurnVectors withinTeamTurns = allTeamTurns.get(team);

            int[] evenTracker = evenTrackers.get(team);
            float teamEvenTurns = team.getEvenTurns();

            // Calculate the number of "even" turns to add for this team.
            int numEven = 0;
            if (1 == numTeamsMoving) {
                // If there's only one team moving, we don't need to bother
                // with the evenTracker, just make sure the even turns are
                // evenly distributed
                numEven += (int) Math.round(teamEvenTurns / min);
            } else if (prevTeam == null) {
                // Increment the number of times we've checked for "leftovers".
                evenTracker[0]++;

                // The first team to move just adds the "baseline" turns.
                numEven += Math.round(teamEvenTurns / min);
            } else if (!team.equals(prevTeam)) {
                // Increment the number of times we've checked for "leftovers".
                evenTracker[0]++;

                // This weird equation attempts to spread the "leftover"
                // turns across the turn's moves in a "fair" manner.
                // It's based on the number of times we've checked for
                // "leftovers" the number of "leftovers" we started with,
                // the number of times we've added a turn for a "leftover",
                // and the total number of times we're going to check.
                numEven += (int) Math.ceil(((evenTracker[0] * (teamEvenTurns % min)) / min) - 0.5)
                        - evenTracker[1];

                // Update the number of turns actually added for "leftovers".
                evenTracker[1] += numEven;

                // Add the "baseline" number of turns.
                numEven += Math.round(teamEvenTurns / min);
            }

            // Record this team for the next move.
            prevTeam = team;

            int aeroMask = GameTurn.CLASS_AERO + GameTurn.CLASS_SMALL_CRAFT
                    + GameTurn.CLASS_DROPSHIP + GameTurn.CLASS_JUMPSHIP
                    + GameTurn.CLASS_WARSHIP + GameTurn.CLASS_SPACE_STATION;
            GameTurn turn;
            Player player;
            if (withinTeamTurns.hasMoreNormalElements()) {
                // Not a placeholder... get the player who moves next.
                player = (Player) withinTeamTurns.nextNormalElement();

                // If we've added all "normal" turns, allocate turns
                // for the infantry and/or ProtoMechs moving even.
                if (numTurn >= team_order.getTotalTurns()) {
                    turn = new GameTurn.EntityClassTurn(player.getId(), evenMask);
                }
                // If either Infantry or ProtoMechs move even, only allow
                // the other classes to move during the "normal" turn.
                else if (infMoveEven || protosMoveEven) {
                    int newMask = evenMask;
                    // if this is the movement phase, then don't allow Aeros on normal turns
                    if (gameManager.getGame().getPhase().isMovement() || gameManager.getGame().getPhase().isDeployment()) {
                        newMask += aeroMask;
                    }
                    turn = new GameTurn.EntityClassTurn(player.getId(), ~newMask);
                } else {
                    // Otherwise, let anyone move... well, almost anybody; Aero don't get normal
                    // turns during the movement phase
                    if (gameManager.getGame().getPhase().isMovement() || gameManager.getGame().getPhase().isDeployment()) {
                        turn = new GameTurn.EntityClassTurn(player.getId(), ~aeroMask);
                    } else if (gameManager.getGame().getPhase().isPremovement() || gameManager.getGame().getPhase().isPrefiring()){
                        turn = new GameTurn.PrephaseTurn(player.getId());
                    } else {
                        turn = new GameTurn(player.getId());
                    }
                }
                turns.addElement(turn);
            } else if (withinTeamTurns.hasMoreSpaceStationElements()) {
                player = (Player) withinTeamTurns.nextSpaceStationElement();
                turn = new GameTurn.EntityClassTurn(player.getId(), GameTurn.CLASS_SPACE_STATION);
                turns.addElement(turn);
            } else if (withinTeamTurns.hasMoreJumpshipElements()) {
                player = (Player) withinTeamTurns.nextJumpshipElement();
                turn = new GameTurn.EntityClassTurn(player.getId(), GameTurn.CLASS_JUMPSHIP);
                turns.addElement(turn);
            } else if (withinTeamTurns.hasMoreWarshipElements()) {
                player = (Player) withinTeamTurns.nextWarshipElement();
                turn = new GameTurn.EntityClassTurn(player.getId(), GameTurn.CLASS_WARSHIP);
                turns.addElement(turn);
            } else if (withinTeamTurns.hasMoreDropshipElements()) {
                player = (Player) withinTeamTurns.nextDropshipElement();
                turn = new GameTurn.EntityClassTurn(player.getId(), GameTurn.CLASS_DROPSHIP);
                turns.addElement(turn);
            } else if (withinTeamTurns.hasMoreSmallCraftElements()) {
                player = (Player) withinTeamTurns.nextSmallCraftElement();
                turn = new GameTurn.EntityClassTurn(player.getId(), GameTurn.CLASS_SMALL_CRAFT);
                turns.addElement(turn);
            } else if (withinTeamTurns.hasMoreAeroElements()) {
                player = (Player) withinTeamTurns.nextAeroElement();
                turn = new GameTurn.EntityClassTurn(player.getId(), GameTurn.CLASS_AERO);
                turns.addElement(turn);
            }

            // Add the calculated number of "even" turns.
            // Allow the player at least one "normal" turn before the
            // "even" turns to help with loading infantry in deployment.
            while ((numEven > 0) && withinTeamTurns.hasMoreEvenElements()) {
                Player evenPlayer = (Player) withinTeamTurns.nextEvenElement();
                turns.addElement(new GameTurn.EntityClassTurn(evenPlayer.getId(), evenMask));
                numEven--;
            }
        }

        // set fields in game
        gameManager.game.setTurnVector(turns);
        gameManager.game.resetTurnIndex();

        // send turns to all players
        gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));
    }

    /**
     * Applies board settings. This loads and combines all the boards that were
     * specified into one mega-board and sets that board as current.
     * @param gameManager
     */
    public void applyBoardSettings(GameManager gameManager) {
        MapSettings mapSettings = gameManager.game.getMapSettings();
        mapSettings.chooseSurpriseBoards();
        Board[] sheetBoards = new Board[mapSettings.getMapWidth() * mapSettings.getMapHeight()];
        List<Boolean> rotateBoard = new ArrayList<>();
        for (int i = 0; i < (mapSettings.getMapWidth() * mapSettings.getMapHeight()); i++) {
            sheetBoards[i] = new Board();
            String name = mapSettings.getBoardsSelectedVector().get(i);
            boolean isRotated = false;
            if (name.startsWith(Board.BOARD_REQUEST_ROTATION)) {
                // only rotate boards with an even width
                if ((mapSettings.getBoardWidth() % 2) == 0) {
                    isRotated = true;
                }
                name = name.substring(Board.BOARD_REQUEST_ROTATION.length());
            }
            if (name.startsWith(MapSettings.BOARD_GENERATED)
                    || (mapSettings.getMedium() == MapSettings.MEDIUM_SPACE)) {
                sheetBoards[i] = BoardUtilities.generateRandom(mapSettings);
            } else {
                sheetBoards[i].load(new MegaMekFile(Configuration.boardsDir(), name + ".board").getFile());
                BoardUtilities.flip(sheetBoards[i], isRotated, isRotated);
            }
            rotateBoard.add(isRotated);
        }
        Board newBoard = BoardUtilities.combine(mapSettings.getBoardWidth(),
                mapSettings.getBoardHeight(), mapSettings.getMapWidth(),
                mapSettings.getMapHeight(), sheetBoards, rotateBoard,
                mapSettings.getMedium());
        if (gameManager.game.getOptions().getOption(OptionsConstants.BASE_BRIDGECF).intValue() > 0) {
            newBoard.setBridgeCF(gameManager.game.getOptions().getOption(OptionsConstants.BASE_BRIDGECF).intValue());
        }
        if (!gameManager.game.getOptions().booleanOption(OptionsConstants.BASE_RANDOM_BASEMENTS)) {
            newBoard.setRandomBasementsOff();
        }
        if (gameManager.game.getPlanetaryConditions().isTerrainAffected()) {
            BoardUtilities.addWeatherConditions(newBoard, gameManager.game.getPlanetaryConditions().getWeather(),
                    gameManager.game.getPlanetaryConditions().getWindStrength());
        }
        gameManager.game.setBoard(newBoard);
    }

    protected Vector<GameTurn> initGameTurnsWithStranded(TurnVectors team_order, GameManager gameManager) {
        Vector<GameTurn> turns = new Vector<>(team_order.getTotalTurns()
                + team_order.getEvenTurns());

        // Stranded units only during movement phases, rebuild the turns vector
        // TODO maybe move this to Premovemnt?
        if (gameManager.game.getPhase().isMovement()) {
            // See if there are any loaded units stranded on immobile transports.
            Iterator<Entity> strandedUnits = gameManager.game.getSelectedEntities(
                    entity -> gameManager.game.isEntityStranded(entity));
            if (strandedUnits.hasNext()) {
                // Add a game turn to unload stranded units, if this
                // is the movement phase.
                turns = new Vector<>(team_order.getTotalTurns()
                        + team_order.getEvenTurns() + 1);
                turns.addElement(new GameTurn.UnloadStrandedTurn(strandedUnits));
            }
        }
        return turns;
    }

    /**
     * Called when a player declares that he is "done." Checks to see if all
     * players are done, and if so, moves on to the next phase.
     * @param gameManager
     */
    protected void checkReady(GameManager gameManager) {
        // check if all active players are done
        for (Player player : gameManager.game.getPlayersList()) {
            if (!player.isGhost() && !player.isObserver() && !player.isDone()) {
                return;
            }
        }

        // Tactical Genius pilot special ability (lvl 3)
        if (gameManager.game.getNoOfInitiativeRerollRequests() > 0) {
            gameManager.playerManager.resetActivePlayersDone(gameManager);
            gameManager.game.rollInitAndResolveTies();

            determineTurnOrder(GamePhase.INITIATIVE, gameManager);
            gameManager.reportManager.clearReports(gameManager);
            gameManager.reportManager.writeInitiativeReport(true, gameManager);
            gameManager.communicationManager.sendReport(true, gameManager);
            return; // don't end the phase yet, players need to see new report
        }

        // need at least one entity in the game for the lounge phase to end
        if (!gameManager.getGame().getPhase().hasTurns()
                && (!gameManager.getGame().getPhase().isLounge() || (gameManager.getGame().getNoOfEntities() > 0))) {
            endCurrentPhase(gameManager);
        }
    }

    /**
     * Called when the current player has done his current turn and the turn
     * counter needs to be advanced. Also enforces the "protos_move_multi" and
     * the "protos_move_multi" option. If the player has just moved
     * infantry/protos with a "normal" turn, adds up to
     * Game.INF_AND_PROTOS_MOVE_MULTI - 1 more infantry/proto-specific turns
     * after the current turn.
     * @param entityUsed
     * @param gameManager
     */
    protected void endCurrentTurn(Entity entityUsed, GameManager gameManager) {
        // Enforce "inf_move_multi" and "protos_move_multi" options.
        // The "isNormalTurn" flag is checking to see if any non-Infantry
        // or non-ProtoMech units can move during the current turn.
        boolean turnsChanged = false;
        boolean outOfOrder = false;
        GameTurn turn = gameManager.game.getTurn();
        if (gameManager.getGame().getPhase().isSimultaneous(gameManager.getGame())
                && (entityUsed != null)
                && !turn.isValid(entityUsed.getOwnerId(), gameManager.game)
                && !entityUsed.turnWasInterrupted()) {
            // turn played out of order
            outOfOrder = true;
            entityUsed.setDone(false);
            GameTurn removed = null;
            try {
                removed = gameManager.game.removeFirstTurnFor(entityUsed);
            } catch (Exception e) {
                LogManager.getLogger().error("", e);
            }
            entityUsed.setDone(true);
            turnsChanged = true;
            if (removed != null) {
                turn = removed;
            }
        }
        final GamePhase currPhase = gameManager.game.getPhase();
        final GameOptions gameOpts = gameManager.game.getOptions();
        final int playerId = null == entityUsed ? Player.PLAYER_NONE : entityUsed.getOwnerId();
        boolean infMoved = entityUsed instanceof Infantry;
        boolean infMoveMulti = gameOpts.booleanOption(OptionsConstants.INIT_INF_MOVE_MULTI)
                && (currPhase.isMovement() || currPhase.isDeployment() || currPhase.isInitiative());
        boolean protosMoved = entityUsed instanceof Protomech;
        boolean protosMoveMulti = gameOpts.booleanOption(OptionsConstants.INIT_PROTOS_MOVE_MULTI);
        boolean tanksMoved = entityUsed instanceof Tank;
        boolean tanksMoveMulti = gameOpts.booleanOption(OptionsConstants.ADVGRNDMOV_VEHICLE_LANCE_MOVEMENT)
                && (currPhase.isMovement() || currPhase.isDeployment() || currPhase.isInitiative());
        boolean meksMoved = entityUsed instanceof Mech;
        boolean meksMoveMulti = gameOpts.booleanOption(OptionsConstants.ADVGRNDMOV_MEK_LANCE_MOVEMENT)
                && (currPhase.isMovement() || currPhase.isDeployment() || currPhase.isInitiative());

        // If infantry or protos move multi see if any
        // other unit types can move in the current turn.
        int multiMask = 0;
        if (infMoveMulti && infMoved) {
            multiMask = GameTurn.CLASS_INFANTRY;
        } else if (protosMoveMulti && protosMoved) {
            multiMask = GameTurn.CLASS_PROTOMECH;
        } else if (tanksMoveMulti && tanksMoved) {
            multiMask = GameTurn.CLASS_TANK;
        } else if (meksMoveMulti && meksMoved) {
            multiMask = GameTurn.CLASS_MECH;
        }

        // In certain cases, a new SpecificEntityTurn could have been added for
        // the Entity whose turn we are ending as the next turn. If this has
        // happened, the remaining entity count will be off and we must ensure
        // that the SpecificEntityTurn for this unit remains the next turn
        List<GameTurn> turnVector = gameManager.game.getTurnVector();
        int turnIndex = gameManager.game.getTurnIndex();
        boolean usedEntityNotDone = false;
        if ((turnIndex + 1) < turnVector.size()) {
            GameTurn nextTurn = turnVector.get(turnIndex + 1);
            if (nextTurn instanceof GameTurn.SpecificEntityTurn) {
                GameTurn.SpecificEntityTurn seTurn = (GameTurn.SpecificEntityTurn) nextTurn;
                if ((entityUsed != null) && (seTurn.getEntityNum() == entityUsed.getId())) {
                    turnIndex++;
                    usedEntityNotDone = true;
                }
            }
        }

        // Was the turn we just took added as part of a multi-turn?
        //  This determines if we should add more multi-turns
        boolean isMultiTurn = turn.isMultiTurn();

        // Unless overridden by the "protos_move_multi" option, all ProtoMechs
        // in a unit declare fire, and they don't mix with infantry.
        if (protosMoved && !protosMoveMulti && !isMultiTurn) {
            // What's the unit number and ID of the entity used?
            final short movingUnit = entityUsed.getUnitNumber();
            final int movingId = entityUsed.getId();

            // How many other ProtoMechs are in the unit that can fire?
            int protoTurns = gameManager.game.getSelectedEntityCount(new EntitySelector() {
                protected final int ownerId = playerId;

                protected final int entityId = movingId;

                protected final short unitNum = movingUnit;

                @Override
                public boolean accept(Entity entity) {
                    return (entity instanceof Protomech)
                            && entity.isSelectableThisTurn()
                            && (ownerId == entity.getOwnerId())
                            && (entityId != entity.getId())
                            && (unitNum == entity.getUnitNumber());
                }
            });

            // Add the correct number of turns for the ProtoMech unit number.
            for (int i = 0; i < protoTurns; i++) {
                GameTurn newTurn = new GameTurn.UnitNumberTurn(playerId, movingUnit);
                newTurn.setMultiTurn(true);
                gameManager.game.insertTurnAfter(newTurn, turnIndex);
                turnsChanged = true;
            }
        }
        // Otherwise, we may need to add turns for the "*_move_multi" options.
        else if (((infMoved && infMoveMulti) || (protosMoved && protosMoveMulti)) && !isMultiTurn) {
            int remaining = 0;

            // Calculate the number of EntityClassTurns need to be added.
            if (infMoveMulti && infMoved) {
                remaining += gameManager.getGame().getInfantryLeft(playerId);
            }

            if (protosMoveMulti && protosMoved) {
                remaining += gameManager.getGame().getProtoMeksLeft(playerId);
            }

            if (usedEntityNotDone) {
                remaining--;
            }

            int moreInfAndProtoTurns = Math.min(
                    gameOpts.intOption(OptionsConstants.INIT_INF_PROTO_MOVE_MULTI) - 1, remaining);

            // Add the correct number of turns for the right unit classes.
            for (int i = 0; i < moreInfAndProtoTurns; i++) {
                GameTurn newTurn = new GameTurn.EntityClassTurn(playerId, multiMask);
                newTurn.setMultiTurn(true);
                gameManager.game.insertTurnAfter(newTurn, turnIndex);
                turnsChanged = true;
            }
        }

        if (tanksMoved && tanksMoveMulti && !isMultiTurn) {
            int remaining = gameManager.game.getVehiclesLeft(playerId);
            if (usedEntityNotDone) {
                remaining--;
            }
            int moreVeeTurns = Math.min(
                    gameOpts.intOption(OptionsConstants.ADVGRNDMOV_VEHICLE_LANCE_MOVEMENT_NUMBER) - 1,
                    remaining);

            // Add the correct number of turns for the right unit classes.
            for (int i = 0; i < moreVeeTurns; i++) {
                GameTurn newTurn = new GameTurn.EntityClassTurn(playerId, multiMask);
                newTurn.setMultiTurn(true);
                gameManager.game.insertTurnAfter(newTurn, turnIndex);
                turnsChanged = true;
            }
        }

        if (meksMoved && meksMoveMulti && !isMultiTurn) {
            int remaining = gameManager.game.getMechsLeft(playerId);
            if (usedEntityNotDone) {
                remaining--;
            }
            int moreMekTurns = Math.min(
                    gameOpts.intOption(OptionsConstants.ADVGRNDMOV_MEK_LANCE_MOVEMENT_NUMBER) - 1,
                    remaining);

            // Add the correct number of turns for the right unit classes.
            for (int i = 0; i < moreMekTurns; i++) {
                GameTurn newTurn = new GameTurn.EntityClassTurn(playerId, multiMask);
                newTurn.setMultiTurn(true);
                gameManager.game.insertTurnAfter(newTurn, turnIndex);
                turnsChanged = true;
            }
        }

        // brief everybody on the turn update, if they changed
        if (turnsChanged) {
            gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));
        }

        // move along
        if (outOfOrder) {
            gameManager.communicationManager.send(gameManager.packetManager.createTurnIndexPacket(playerId, gameManager));
        } else {
            changeToNextTurn(playerId, gameManager);
        }
    }

    /**
     * Forces victory for the specified player, or his/her team at the end of the round.
     * @param victor
     * @param gameManager
     */
    public void forceVictory(Player victor, GameManager gameManager) {
        gameManager.game.setForceVictory(true);
        if (victor.getTeam() == Player.TEAM_NONE) {
            gameManager.game.setVictoryPlayerId(victor.getId());
            gameManager.game.setVictoryTeam(Player.TEAM_NONE);
        } else {
            gameManager.game.setVictoryPlayerId(Player.PLAYER_NONE);
            gameManager.game.setVictoryTeam(victor.getTeam());
        }

        Vector<Player> playersVector = gameManager.game.getPlayersVector();
        for (int i = 0; i < playersVector.size(); i++) {
            Player player = playersVector.elementAt(i);
            player.setAdmitsDefeat(false);
        }
    }
}
