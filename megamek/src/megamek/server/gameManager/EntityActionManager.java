package megamek.server.gameManager;

import megamek.common.*;
import megamek.common.actions.*;
import megamek.common.enums.GamePhase;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.preference.PreferenceManager;
import megamek.common.weapons.AttackHandler;
import megamek.common.weapons.WeaponHandler;
import megamek.server.SmokeCloud;
import org.apache.logging.log4j.LogManager;

import java.util.*;

public class EntityActionManager {
    /**
     * Receives an entity movement packet, and if valid, executes it and ends
     * the current turn.
     * @param packet
     * @param connId
     * @param gameManager
     */
    protected void receiveMovement(Packet packet, int connId, GameManager gameManager) {
        Map<EntityTargetPair, LosEffects> losCache = new HashMap<>();
        Entity entity = gameManager.game.getEntity(packet.getIntValue(0));
        MovePath md = (MovePath) packet.getObject(1);
        md.setGame(gameManager.getGame());
        md.setEntity(entity);

        // is this the right phase?
        if (!gameManager.getGame().getPhase().isMovement()) {
            LogManager.getLogger().error("Server got movement packet in wrong phase");
            return;
        }

        // can this player/entity act right now?
        GameTurn turn = gameManager.game.getTurn();
        if (gameManager.getGame().getPhase().isSimultaneous(gameManager.getGame())) {
            turn = gameManager.game.getTurnForPlayer(connId);
        }

        if ((turn == null) || !turn.isValid(connId, entity, gameManager.game)) {
            String msg = "error: server got invalid movement packet from " + "connection " + connId;
            if (entity != null) {
                msg += ", Entity: " + entity.getShortName();
            } else {
                msg += ", Entity was null!";
            }
            LogManager.getLogger().error(msg);
            return;
        }

        // looks like mostly everything's okay
        gameManager.processMovement(entity, md, losCache);

        // The attacker may choose to break a chain whip grapple by expending MP
        if ((entity.getGrappled() != Entity.NONE)
                && entity.isChainWhipGrappled() && entity.isGrappleAttacker()
                && (md.getMpUsed() > 0)) {

            Entity te = gameManager.game.getEntity(entity.getGrappled());
            Report r = new Report(4316);
            r.subject = entity.getId();
            r.addDesc(entity);
            r.addDesc(te);
            gameManager.addReport(r);

            entity.setGrappled(Entity.NONE, false);
            te.setGrappled(Entity.NONE, false);

            gameManager.entityUpdate(entity.getId());
            gameManager.entityUpdate(te.getId());
        }

        // check the LOS of any telemissiles owned by this entity
        for (int missileId : entity.getTMTracker().getMissiles()) {
            Entity tm = gameManager.game.getEntity(missileId);
            if ((null != tm) && !tm.isDestroyed()
                    && (tm instanceof TeleMissile)) {
                if (LosEffects.calculateLOS(gameManager.game, entity, tm).canSee()) {
                    ((TeleMissile) tm).setOutContact(false);
                } else {
                    ((TeleMissile) tm).setOutContact(true);
                }
                gameManager.entityUpdate(tm.getId());
            }
        }

        // Notify the clients about any building updates.
        gameManager.applyAffectedBldgs();

        // Unit movement may detect hidden units
        gameManager.detectHiddenUnits();

        // Update visibility indications if using double blind.
        if (gameManager.doBlind()) {
            gameManager.updateVisibilityIndicator(losCache);
        }

        // An entity that is not vulnerable to anti-TSM green smoke that has stayed in a smoke-filled
        // hex takes damage.
        if ((md.getHexesMoved() == 0)
                && gameManager.game.getBoard().contains(md.getFinalCoords())
                && (gameManager.game.getBoard().getHex(md.getFinalCoords()).terrainLevel(Terrains.SMOKE) == SmokeCloud.SMOKE_GREEN)
                && entity.antiTSMVulnerable()) {
            gameManager.reportManager.addReport(gameManager.doGreenSmokeDamage(entity), gameManager);
        }

        // This entity's turn is over.
        // N.B. if the entity fell, a *new* turn has already been added.
        gameManager.gameStateManager.endCurrentTurn(entity, gameManager);
    }

    /**
     * Check a list of entity Ids for doomed entities and destroy those.
     * @param entityIds
     * @param gameManager
     */
    protected void destroyDoomedEntities(Vector<Integer> entityIds, GameManager gameManager) {
        Vector<Entity> toRemove = new Vector<>(0, 10);
        for (Integer entityId : entityIds) {
            Entity entity = gameManager.game.getEntity(entityId);
            if (entity.isDoomed()) {
                entity.setDestroyed(true);

                // Is this unit swarming somebody? Better let go before
                // it's too late.
                final int swarmedId = entity.getSwarmTargetId();
                if (Entity.NONE != swarmedId) {
                    final Entity swarmed = gameManager.game.getEntity(swarmedId);
                    swarmed.setSwarmAttackerId(Entity.NONE);
                    entity.setSwarmTargetId(Entity.NONE);
                    Report r = new Report(5165);
                    r.subject = swarmedId;
                    r.addDesc(swarmed);
                    gameManager.addReport(r);
                    gameManager.entityUpdate(swarmedId);
                }
            }

            if (entity.isDestroyed()) {
                toRemove.addElement(entity);
            }
        }

        // actually remove all flagged entities
        for (Entity entity : toRemove) {
            int condition = IEntityRemovalConditions.REMOVE_SALVAGEABLE;
            if (!entity.isSalvage()) {
                condition = IEntityRemovalConditions.REMOVE_DEVASTATED;
            }

            // If we removed a unit during the movement phase that hasn't moved, remove its turn.
            if (gameManager.getGame().getPhase().isMovement() && entity.isSelectableThisTurn()) {
                gameManager.getGame().removeTurnFor(entity);
                gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));
            }
            gameManager.entityUpdate(entity.getId());
            gameManager.game.removeEntity(entity.getId(), condition);
            gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(entity.getId(), condition, gameManager));
        }
    }

    /**
     * Deploys elligible offboard entities.
     * @param gameManager
     */
    protected void deployOffBoardEntities(GameManager gameManager) {
        // place off board entities actually off-board
        Iterator<Entity> entities = gameManager.game.getEntities();
        while (entities.hasNext()) {
            Entity en = entities.next();
            if (en.isOffBoard() && !en.isDeployed()) {
                en.deployOffBoard(gameManager.game.getRoundCount());
            }
        }
    }

    /**
     * Called at the beginning of each phase. Sets and resets any entity
     * parameters that need to be reset.
     * @param phase
     * @param gameManager
     */
    void resetEntityPhase(GamePhase phase, GameManager gameManager) {
        // first, mark doomed entities as destroyed and flag them
        Vector<Entity> toRemove = new Vector<>(0, 10);

        for (Entity entity : gameManager.game.getEntitiesVector()) {
            entity.newPhase(phase);
            if (entity.isDoomed()) {
                entity.setDestroyed(true);

                // Is this unit swarming somebody? Better let go before it's too late.
                final int swarmedId = entity.getSwarmTargetId();
                if (Entity.NONE != swarmedId) {
                    final Entity swarmed = gameManager.game.getEntity(swarmedId);
                    swarmed.setSwarmAttackerId(Entity.NONE);
                    entity.setSwarmTargetId(Entity.NONE);
                    Report r = new Report(5165);
                    r.subject = swarmedId;
                    r.addDesc(swarmed);
                    gameManager.addReport(r);
                    gameManager.entityUpdate(swarmedId);
                }
            }

            if (entity.isDestroyed()) {
                if (gameManager.game.getEntity(entity.getTransportId()) != null
                        && gameManager.game.getEntity(entity.getTransportId()).isLargeCraft()) {
                    // Leaving destroyed entities in DropShip bays alone here
                } else {
                    toRemove.addElement(entity);
                }
            }
        }

        // actually remove all flagged entities
        for (Entity entity : toRemove) {
            int condition = IEntityRemovalConditions.REMOVE_SALVAGEABLE;
            if (!entity.isSalvage()) {
                condition = IEntityRemovalConditions.REMOVE_DEVASTATED;
            }

            gameManager.entityUpdate(entity.getId());
            gameManager.game.removeEntity(entity.getId(), condition);
            gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(entity.getId(), condition, gameManager));
        }

        // do some housekeeping on all the remaining
        for (Entity entity : gameManager.game.getEntitiesVector()) {
            entity.applyDamage();
            entity.reloadEmptyWeapons();

            // reset damage this phase
            // telemissiles need a record of damage last phase
            entity.damageThisRound += entity.damageThisPhase;
            entity.damageThisPhase = 0;
            entity.engineHitsThisPhase = 0;
            entity.rolledForEngineExplosion = false;
            entity.dodging = false;
            entity.setShutDownThisPhase(false);
            entity.setStartupThisPhase(false);

            // reset done to false
            if (phase.isDeployment()) {
                entity.setDone(!entity.shouldDeploy(gameManager.game.getRoundCount()));
            } else {
                entity.setDone(false);
            }

            // reset spotlights
            // If deployment phase, set Searchlight state based on startSearchLightsOn;
            if (phase.isDeployment()) {
                boolean startSLOn = PreferenceManager.getClientPreferences().getStartSearchlightsOn()
                        && gameManager.game.getPlanetaryConditions().isIlluminationEffective();
                entity.setSearchlightState(startSLOn);
                entity.setIlluminated(startSLOn);
            }
            entity.setIlluminated(false);
            entity.setUsedSearchlight(false);

            entity.setCarefulStand(false);

            // this flag is relevant only within the context of a single phase, but not between phases
            entity.setTurnInterrupted(false);

            if (entity instanceof MechWarrior) {
                ((MechWarrior) entity).setLanded(true);
            }
        }

        // flag deployed and doomed, but not destroyed out of game enities
        for (Entity entity : gameManager.game.getOutOfGameEntitiesVector()) {
            if (entity.isDeployed() && entity.isDoomed() && !entity.isDestroyed()) {
                entity.setDestroyed(true);
            }
        }

        gameManager.game.clearIlluminatedPositions();
        gameManager.communicationManager.send(new Packet(PacketCommand.CLEAR_ILLUM_HEXES));
    }

    /**
     * Called during the end phase. Checks each entity for ASEW effects counters and decrements them by 1 if greater than 0
     * @param gameManager
     */
    public void decrementASEWTurns(GameManager gameManager) {
        for (Iterator<Entity> e = gameManager.game.getEntities(); e.hasNext(); ) {
            final Entity entity = e.next();
            // Decrement ASEW effects
            if ((entity.getEntityType() & Entity.ETYPE_DROPSHIP) == Entity.ETYPE_DROPSHIP) {
                Dropship d = (Dropship) entity;
                for (int loc = 0; loc < d.locations(); loc++) {
                    if (d.getASEWAffected(loc) > 0) {
                        d.setASEWAffected(loc, d.getASEWAffected(loc) - 1);
                    }
                }
            } else if ((entity.getEntityType() & Entity.ETYPE_JUMPSHIP) != 0) {
                Jumpship j = (Jumpship) entity;
                for (int loc = 0; loc < j.locations(); loc++) {
                    if (j.getASEWAffected(loc) > 0) {
                        j.setASEWAffected(loc, j.getASEWAffected(loc) - 1);
                    }
                }
            } else {
                if (entity.getASEWAffected() > 0) {
                    entity.setASEWAffected(entity.getASEWAffected() - 1);
                }
            }
        }
    }

    /**
     * Handle all physical attacks for the round
     * @param gameManager
     */
    void resolvePhysicalAttacks(GameManager gameManager) {
        // Physical phase header
        gameManager.addReport(new Report(4000, Report.PUBLIC));

        // add any pending charges
        for (Enumeration<AttackAction> i = gameManager.game.getCharges(); i.hasMoreElements(); ) {
            gameManager.game.addAction(i.nextElement());
        }
        gameManager.game.resetCharges();

        // add any pending rams
        for (Enumeration<AttackAction> i = gameManager.game.getRams(); i.hasMoreElements(); ) {
            gameManager.game.addAction(i.nextElement());
        }
        gameManager.game.resetRams();

        // add any pending Tele Missile Attacks
        for (Enumeration<AttackAction> i = gameManager.game.getTeleMissileAttacks(); i.hasMoreElements(); ) {
            gameManager.game.addAction(i.nextElement());
        }
        gameManager.game.resetTeleMissileAttacks();

        // remove any duplicate attack declarations
        gameManager.cleanupPhysicalAttacks();

        // loop thru received attack actions
        for (Enumeration<EntityAction> i = gameManager.game.getActions(); i.hasMoreElements(); ) {
            Object o = i.nextElement();
            // verify that the attacker is still active
            AttackAction aa = (AttackAction) o;
            if (!gameManager.game.getEntity(aa.getEntityId()).isActive()
                    && !(o instanceof DfaAttackAction)) {
                continue;
            }
            AbstractAttackAction aaa = (AbstractAttackAction) o;
            // do searchlights immediately
            if (aaa instanceof SearchlightAttackAction) {
                SearchlightAttackAction saa = (SearchlightAttackAction) aaa;
                gameManager.reportManager.addReport(saa.resolveAction(gameManager.game), gameManager);
            } else {
                gameManager.physicalResults.addElement(gameManager.preTreatPhysicalAttack(aaa));
            }
        }
        int cen = Entity.NONE;
        for (PhysicalResult pr : gameManager.physicalResults) {
            gameManager.resolvePhysicalAttack(pr, cen);
            cen = pr.aaa.getEntityId();
        }
        gameManager.physicalResults.removeAllElements();
    }

    /**
     * Gets a bunch of entity attacks from the packet. If valid, processes them
     * and ends the current turn.
     * @param packet
     * @param connId
     * @param gameManager
     */
    @SuppressWarnings("unchecked")
    protected void receiveAttack(Packet packet, int connId, GameManager gameManager) {
        Entity entity = gameManager.game.getEntity(packet.getIntValue(0));
        Vector<EntityAction> vector = (Vector<EntityAction>) packet.getObject(1);

        // is this the right phase?
        if (!gameManager.getGame().getPhase().isFiring() && !gameManager.getGame().getPhase().isPhysical()
                && !gameManager.getGame().getPhase().isTargeting() && !gameManager.getGame().getPhase().isOffboard()) {
            LogManager.getLogger().error("Server got attack packet in wrong phase");
            return;
        }

        // can this player/entity act right now?
        GameTurn turn = gameManager.game.getTurn();
        if (gameManager.getGame().getPhase().isSimultaneous(gameManager.getGame())) {
            turn = gameManager.game.getTurnForPlayer(connId);
        }
        if ((turn == null) || !turn.isValid(connId, entity, gameManager.game)) {
            LogManager.getLogger().error(String.format(
                    "Server got invalid attack packet from Connection %s, Entity %s, %s Turn",
                    connId, ((entity == null) ? "null" : entity.getShortName()),
                    ((turn == null) ? "null" : "invalid")));
            gameManager.communicationManager.send(connId, gameManager.packetManager.createTurnVectorPacket(gameManager));
            gameManager.communicationManager.send(connId, gameManager.packetManager.createTurnIndexPacket((turn == null) ? Player.PLAYER_NONE : turn.getPlayerNum(), gameManager));
            return;
        }

        // looks like mostly everything's okay
        gameManager.entityActionManager.processAttack(entity, vector, gameManager);

        // Update visibility indications if using double blind.
        if (gameManager.doBlind()) {
            gameManager.updateVisibilityIndicator(null);
        }

        gameManager.gameStateManager.endCurrentTurn(entity, gameManager);
    }

    /**
     * Process a batch of entity attack (or twist) actions by adding them to the
     * proper list to be processed later.
     * @param entity
     * @param vector
     * @param gameManager
     */
    protected void processAttack(Entity entity, Vector<EntityAction> vector, GameManager gameManager) {
        // Convert any null vectors to empty vectors to avoid NPEs.
        if (vector == null) {
            vector = new Vector<>(0);
        }

        // Not **all** actions take up the entity's turn.
        boolean setDone = !((gameManager.game.getTurn() instanceof GameTurn.TriggerAPPodTurn)
                || (gameManager.game.getTurn() instanceof GameTurn.TriggerBPodTurn));
        for (EntityAction ea : vector) {
            // is this the right entity?
            if (ea.getEntityId() != entity.getId()) {
                LogManager.getLogger().error("Attack packet has wrong attacker");
                continue;
            }
            if (ea instanceof PushAttackAction) {
                // push attacks go the end of the displacement attacks
                PushAttackAction paa = (PushAttackAction) ea;
                entity.setDisplacementAttack(paa);
                gameManager.game.addCharge(paa);
            } else if (ea instanceof DodgeAction) {
                entity.dodging = true;
            } else if (ea instanceof SpotAction) {
                entity.setSpotting(true);
                entity.setSpotTargetId(((SpotAction) ea).getTargetId());
            } else {
                // add to the normal attack list.
                gameManager.game.addAction(ea);
            }

            // Anti-mech and pointblank attacks from
            // hiding may allow the target to respond.
            if (ea instanceof WeaponAttackAction) {
                final WeaponAttackAction waa = (WeaponAttackAction) ea;
                final String weaponName = entity.getEquipment(waa.getWeaponId()).getType()
                        .getInternalName();

                if (Infantry.SWARM_MEK.equals(weaponName) || Infantry.LEG_ATTACK.equals(weaponName)) {

                    // Does the target have any AP Pods available?
                    final Entity target = gameManager.game.getEntity(waa.getTargetId());
                    for (Mounted equip : target.getMisc()) {
                        if (equip.getType().hasFlag(MiscType.F_AP_POD) && equip.canFire()) {

                            // Yup. Insert a game turn to handle AP pods.
                            // ASSUMPTION : AP pod declarations come
                            // immediately after the attack declaration.
                            gameManager.game.insertNextTurn(new GameTurn.TriggerAPPodTurn(target.getOwnerId(),
                                    target.getId()));
                            gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));

                            // We can stop looking.
                            break;

                        } // end found-available-ap-pod

                    } // Check the next piece of equipment on the target.

                    for (Mounted weapon : target.getWeaponList()) {
                        if (weapon.getType().hasFlag(WeaponType.F_B_POD) && weapon.canFire()) {

                            // Yup. Insert a game turn to handle B pods.
                            // ASSUMPTION : B pod declarations come
                            // immediately after the attack declaration.
                            gameManager.game.insertNextTurn(new GameTurn.TriggerBPodTurn(target.getOwnerId(),
                                    target.getId(), weaponName));
                            gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));

                            // We can stop looking.
                            break;

                        } // end found-available-b-pod
                    } // Check the next piece of equipment on the target.
                } // End check-for-available-ap-pod

                // Keep track of altitude loss for weapon attacks
                if (entity.isAero()) {
                    IAero aero = (IAero) entity;
                    if (waa.getAltitudeLoss(gameManager.game) > aero.getAltLoss()) {
                        aero.setAltLoss(waa.getAltitudeLoss(gameManager.game));
                    }
                }
            }

            // If attacker breaks grapple, defender may counter
            if (ea instanceof BreakGrappleAttackAction) {
                final BreakGrappleAttackAction bgaa = (BreakGrappleAttackAction) ea;
                final Entity att = (gameManager.game.getEntity(bgaa.getEntityId()));
                if (att.isGrappleAttacker()) {
                    final Entity def = (gameManager.game.getEntity(bgaa.getTargetId()));
                    // Remove existing break grapple by defender (if exists)
                    if (def.isDone()) {
                        gameManager.game.removeActionsFor(def.getId());
                    } else {
                        gameManager.game.removeTurnFor(def);
                        def.setDone(true);
                    }
                    // If defender is able, add a turn to declare counterattack
                    if (!def.isImmobile()) {
                        gameManager.game.insertNextTurn(new GameTurn.CounterGrappleTurn(def.getOwnerId(), def.getId()));
                        gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));
                    }
                }
            }
            if (ea instanceof ArtilleryAttackAction) {
                boolean firingAtNewHex = false;
                final ArtilleryAttackAction aaa = (ArtilleryAttackAction) ea;
                final Entity firingEntity = gameManager.game.getEntity(aaa.getEntityId());
                Targetable attackTarget = aaa.getTarget(gameManager.game);

                for (Enumeration<AttackHandler> j = gameManager.game.getAttacks(); !firingAtNewHex
                        && j.hasMoreElements(); ) {
                    WeaponHandler wh = (WeaponHandler) j.nextElement();
                    if (wh.waa instanceof ArtilleryAttackAction) {
                        ArtilleryAttackAction oaaa = (ArtilleryAttackAction) wh.waa;

                        if ((oaaa.getEntityId() == aaa.getEntityId())
                                && !Targetable.areAtSamePosition(oaaa.getTarget(gameManager.game), attackTarget)) {
                            firingAtNewHex = true;
                        }
                    }
                }
                if (firingAtNewHex) {
                    gameManager.clearArtillerySpotters(firingEntity.getId(), aaa.getWeaponId());
                }
                Iterator<Entity> spotters = gameManager.game.getSelectedEntities(new EntitySelector() {
                    public int player = firingEntity.getOwnerId();
                    public Targetable target = aaa.getTarget(gameManager.game);

                    @Override
                    public boolean accept(Entity entity) {
                        LosEffects los = LosEffects.calculateLOS(gameManager.game, entity, target);
                        return ((player == entity.getOwnerId()) && !(los.isBlocked())
                                && entity.isActive());
                    }
                });
                Vector<Integer> spotterIds = new Vector<>();
                while (spotters.hasNext()) {
                    Integer id = spotters.next().getId();
                    spotterIds.addElement(id);
                }
                aaa.setSpotterIds(spotterIds);
            }

            // The equipment type of a club needs to be restored.
            if (ea instanceof ClubAttackAction) {
                ClubAttackAction caa = (ClubAttackAction) ea;
                Mounted club = caa.getClub();
                club.restore();
            }

            // Mark any AP Pod as used in this turn.
            if (ea instanceof TriggerAPPodAction) {
                TriggerAPPodAction tapa = (TriggerAPPodAction) ea;
                Mounted pod = entity.getEquipment(tapa.getPodId());
                pod.setUsedThisRound(true);
            }
            // Mark any B Pod as used in this turn.
            if (ea instanceof TriggerBPodAction) {
                TriggerBPodAction tba = (TriggerBPodAction) ea;
                Mounted pod = entity.getEquipment(tba.getPodId());
                pod.setUsedThisRound(true);
            }

            // Mark illuminated hexes, so they can be displayed
            if (ea instanceof SearchlightAttackAction) {
                boolean hexesAdded =
                        ((SearchlightAttackAction) ea).setHexesIlluminated(gameManager.game);
                // If we added new hexes, send them to all players.
                // These are spotlights at night, you know they're there.
                if (hexesAdded) {
                    gameManager.communicationManager.send(gameManager.packetManager.createIlluminatedHexesPacket(gameManager));
                }
            }
        }

        // Apply altitude loss
        if (entity.isAero()) {
            IAero aero = (IAero) entity;
            if (aero.getAltLoss() > 0) {
                Report r = new Report(9095);
                r.subject = entity.getId();
                r.addDesc(entity);
                r.add(aero.getAltLoss());
                gameManager.addReport(r);
                entity.setAltitude(entity.getAltitude() - aero.getAltLoss());
                aero.setAltLossThisRound(aero.getAltLoss());
                aero.resetAltLoss();
                gameManager.entityUpdate(entity.getId());
            }
        }

        // Unless otherwise stated,
        // this entity is done for the round.
        if (setDone) {
            entity.setDone(true);
        }
        gameManager.entityUpdate(entity.getId());

        Packet p = gameManager.packetManager.createAttackPacket(vector, 0);
        if (gameManager.getGame().getPhase().isSimultaneous(gameManager.getGame())) {
            // Update attack only to player who declared it & observers
            for (Player player : gameManager.game.getPlayersVector()) {
                if (player.canIgnoreDoubleBlind() || player.isObserver()
                        || (entity.getOwnerId() == player.getId())) {
                    gameManager.communicationManager.send(player.getId(), p);
                }
            }
        } else {
            // update all players on the attacks. Don't worry about pushes being
            // a "charge" attack. It doesn't matter to the client.
            gameManager.communicationManager.send(p);
        }
    }

    /**
     * Receive a deployment packet. If valid, execute it and end the current turn.
     * @param packet
     * @param connId
     * @param gameManager
     */
    protected void receiveDeployment(Packet packet, int connId, GameManager gameManager) {
        Entity entity = gameManager.game.getEntity(packet.getIntValue(0));
        Coords coords = (Coords) packet.getObject(1);
        int nFacing = packet.getIntValue(2);
        int elevation = packet.getIntValue(3);

        // Handle units that deploy loaded with other units.
        int loadedCount = packet.getIntValue(4);
        Vector<Entity> loadVector = new Vector<>();
        for (int i = 0; i < loadedCount; i++) {
            int loadedId = packet.getIntValue(6 + i);
            loadVector.addElement(gameManager.game.getEntity(loadedId));
        }

        // is this the right phase?
        if (!gameManager.game.getPhase().isDeployment()) {
            LogManager.getLogger().error("Server got deployment packet in wrong phase");
            return;
        }

        // can this player/entity act right now?
        final boolean assaultDrop = packet.getBooleanValue(5);
        // can this player/entity act right now?
        GameTurn turn = gameManager.game.getTurn();
        if (gameManager.getGame().getPhase().isSimultaneous(gameManager.getGame())) {
            turn = gameManager.game.getTurnForPlayer(connId);
        }
        if ((turn == null) || !turn.isValid(connId, entity, gameManager.game)
                || !(gameManager.game.getBoard().isLegalDeployment(coords, entity)
                || (assaultDrop && gameManager.game.getOptions().booleanOption(OptionsConstants.ADVANCED_ASSAULT_DROP)
                && entity.canAssaultDrop()))) {
            String msg = "server got invalid deployment packet from "
                    + "connection " + connId;
            if (entity != null) {
                msg += ", Entity: " + entity.getShortName();
            } else {
                msg += ", Entity was null!";
            }
            LogManager.getLogger().error(msg);
            gameManager.communicationManager.send(connId, gameManager.packetManager.createTurnVectorPacket(gameManager));
            gameManager.communicationManager.send(connId, gameManager.packetManager.createTurnIndexPacket(turn.getPlayerNum(), gameManager));
            return;
        }

        // looks like mostly everything's okay
        gameManager.entityActionManager.processDeployment(entity, coords, nFacing, elevation, loadVector, assaultDrop, gameManager);

        //Update Aero sensors for a space or atmospheric game
        if (entity.isAero()) {
            IAero a = (IAero) entity;
            a.updateSensorOptions();
        }

        // Update visibility indications if using double blind.
        if (gameManager.doBlind()) {
            gameManager.updateVisibilityIndicator(null);
        }

        gameManager.gameStateManager.endCurrentTurn(entity, gameManager);
    }

    /**
     * Used when an Entity that was loaded in another Entity in the Lounge is
     * unloaded during deployment.
     * @param packet the packet to be processed
     * @param connId the id for connection that received the packet.
     * @param gameManager
     */
    protected void receiveDeploymentUnload(Packet packet, int connId, GameManager gameManager) {
        Entity loader = gameManager.game.getEntity(packet.getIntValue(0));
        Entity loaded = gameManager.game.getEntity(packet.getIntValue(1));

        if (!gameManager.game.getPhase().isDeployment()) {
            String msg = "server received deployment unload packet "
                    + "outside of deployment phase from connection " + connId;
            if (loader != null) {
                msg += ", Entity: " + loader.getShortName();
            } else {
                msg += ", Entity was null!";
            }
            LogManager.getLogger().error(msg);
            return;
        }

        // can this player/entity act right now?
        GameTurn turn = gameManager.game.getTurn();
        if (gameManager.getGame().getPhase().isSimultaneous(gameManager.getGame())) {
            turn = gameManager.game.getTurnForPlayer(connId);
        }

        if ((turn == null) || !turn.isValid(connId, loader, gameManager.game)) {
            String msg = "server got invalid deployment unload packet from connection " + connId;
            if (loader != null) {
                msg += ", Entity: " + loader.getShortName();
            } else {
                msg += ", Entity was null!";
            }
            LogManager.getLogger().error(msg);
            gameManager.communicationManager.send(connId, gameManager.packetManager.createTurnVectorPacket(gameManager));
            gameManager.communicationManager.send(connId, gameManager.packetManager.createTurnIndexPacket(connId, gameManager));
            return;
        }

        // Unload and call entityUpdate
        gameManager.unloadUnit(loader, loaded, null, 0, 0, false, true);

        // Need to update the loader
        gameManager.entityUpdate(loader.getId());

        // Now need to add a turn for the unloaded unit, to be taken immediately
        // Turn forced to be immediate to avoid messy turn ordering issues
        // (aka, how do we add the turn with individual initiative?)
        gameManager.game.insertTurnAfter(new GameTurn.SpecificEntityTurn(
                loaded.getOwnerId(), loaded.getId()), gameManager.game.getTurnIndex() - 1);
        //game.insertNextTurn(new GameTurn.SpecificEntityTurn(
        //        loaded.getOwnerId(), loaded.getId()));
        gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));
    }

    /**
     * Process a deployment packet by... deploying the entity! We load any other
     * specified entities inside of it too. Also, check that the deployment is
     * valid.
     * @param entity
     * @param coords
     * @param nFacing
     * @param elevation
     * @param loadVector
     * @param assaultDrop
     * @param gameManager
     */
    protected void processDeployment(Entity entity, Coords coords, int nFacing, int elevation, Vector<Entity> loadVector,
                                     boolean assaultDrop, GameManager gameManager) {
        for (Entity loaded : loadVector) {
            if (loaded.getTransportId() != Entity.NONE) {
                // we probably already loaded this unit in the chat lounge
                continue;
            }
            if (loaded.getPosition() != null) {
                // Something is fishy in Denmark.
                LogManager.getLogger().error(entity + " can not load entity #" + loaded);
                break;
            }
            // Have the deployed unit load the indicated unit.
            gameManager.loadUnit(entity, loaded, loaded.getTargetBay());
        }

        /*
         * deal with starting velocity for advanced movement. Probably not the
         * best place to do it, but what are you going to do
         */
        if (entity.isAero() && gameManager.game.useVectorMove()) {
            IAero a = (IAero) entity;
            int[] v = {0, 0, 0, 0, 0, 0};

            // if this is the entity's first time deploying, we want to respect the "velocity" setting from the lobby
            if (entity.wasNeverDeployed()) {
                if (a.getCurrentVelocityActual() > 0) {
                    v[nFacing] = a.getCurrentVelocityActual();
                    entity.setVectors(v);
                }
                // this means the entity is coming back from off board, so we'll rotate the velocity vector by 180
                // and set it to 1/2 the magnitude
            } else {
                for (int x = 0; x < 6; x++) {
                    v[(x + 3) % 6] = entity.getVector(x) / 2;
                }

                entity.setVectors(v);
            }
        }

        entity.setPosition(coords);
        entity.setFacing(nFacing);
        entity.setSecondaryFacing(nFacing);
        Hex hex = gameManager.game.getBoard().getHex(coords);
        if (assaultDrop) {
            entity.setAltitude(1);
            // from the sky!
            entity.setAssaultDropInProgress(true);
        } else if ((entity instanceof VTOL) && (entity.getExternalUnits().size() <= 0)) {
            // We should let players pick, but this simplifies a lot.
            // Only do it for VTOLs, though; assume everything else is on the
            // ground.
            entity.setElevation((hex.ceiling() - hex.getLevel()) + 1);
            while ((Compute.stackingViolation(gameManager.game, entity, coords, null, entity.climbMode()) != null)
                    && (entity.getElevation() <= 50)) {
                entity.setElevation(entity.getElevation() + 1);
            }
            if (entity.getElevation() > 50) {
                throw new IllegalStateException("Entity #" + entity.getId()
                        + " appears to be in an infinite loop trying to get a legal elevation.");
            }
        } else if (entity.isAero()) {
            // if the entity is airborne, then we don't want to set its
            // elevation below, because that will
            // default to 999
            if (entity.isAirborne()) {
                entity.setElevation(0);
                elevation = 0;
            }
            if (!gameManager.game.getBoard().inSpace()) {
                // all spheroid craft should have velocity of zero in atmosphere
                // regardless of what was entered
                IAero a = (IAero) entity;
                if (a.isSpheroid() || gameManager.game.getPlanetaryConditions().isVacuum()) {
                    a.setCurrentVelocity(0);
                    a.setNextVelocity(0);
                }
                // make sure that entity is above the level of the hex if in
                // atmosphere
                if (gameManager.game.getBoard().inAtmosphere()
                        && (entity.getAltitude() <= hex.ceiling(true))) {
                    // you can't be grounded on low atmosphere map
                    entity.setAltitude(hex.ceiling(true) + 1);
                }
            }
        } else if (entity.getMovementMode() == EntityMovementMode.SUBMARINE) {
            // TODO : Submarines should have a selectable height.
            // TODO : For now, pretend they're regular naval.
            entity.setElevation(0);
        } else if ((entity.getMovementMode() == EntityMovementMode.HOVER)
                || (entity.getMovementMode() == EntityMovementMode.WIGE)
                || (entity.getMovementMode() == EntityMovementMode.NAVAL)
                || (entity.getMovementMode() == EntityMovementMode.HYDROFOIL)) {
            // For now, assume they're on the surface.
            // entity elevation is relative to hex surface
            entity.setElevation(0);
        } else if (hex.containsTerrain(Terrains.ICE)) {
            entity.setElevation(0);
        } else {
            Building bld = gameManager.game.getBoard().getBuildingAt(entity.getPosition());
            if ((bld != null) && (bld.getType() == Building.WALL)) {
                entity.setElevation(hex.terrainLevel(Terrains.BLDG_ELEV));
            }

        }
        // add the elevation that was passed into this method
        // TODO : currently only used for building placement, we should do this
        // TODO : more systematically with up/down buttons in the deployment display
        entity.setElevation(entity.getElevation() + elevation);
        boolean wigeFlyover = entity.getMovementMode() == EntityMovementMode.WIGE
                && hex.containsTerrain(Terrains.BLDG_ELEV)
                && entity.getElevation() > hex.terrainLevel(Terrains.BLDG_ELEV);


        // when first entering a building, we need to roll what type
        // of basement it has
        Building bldg = gameManager.game.getBoard().getBuildingAt(entity.getPosition());
        if ((bldg != null)) {
            if (bldg.rollBasement(entity.getPosition(), gameManager.game.getBoard(), gameManager.vPhaseReport)) {
                gameManager.communicationManager.sendChangedHex(entity.getPosition(), gameManager);
                Vector<Building> buildings = new Vector<>();
                buildings.add(bldg);
                gameManager.communicationManager.sendChangedBuildings(buildings, gameManager);
            }
            boolean collapse = gameManager.checkBuildingCollapseWhileMoving(bldg, entity, entity.getPosition());
            if (collapse) {
                gameManager.addAffectedBldg(bldg, true);
                if (wigeFlyover) {
                    // If the building is collapsed by a WiGE flying over it, the WiGE drops one level of elevation.
                    entity.setElevation(entity.getElevation() - 1);
                }
            }
        }

        entity.setDone(true);
        entity.setDeployed(true);
        gameManager.entityUpdate(entity.getId());
        gameManager.reportManager.addReport(gameManager.doSetLocationsExposure(entity, hex, false, entity.getElevation()), gameManager);
    }
}
