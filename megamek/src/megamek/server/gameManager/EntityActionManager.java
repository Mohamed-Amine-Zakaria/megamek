package megamek.server.gameManager;

import megamek.MMConstants;
import megamek.common.*;
import megamek.common.actions.*;
import megamek.common.enums.GamePhase;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.preference.PreferenceManager;
import megamek.common.weapons.AttackHandler;
import megamek.common.weapons.Weapon;
import megamek.common.weapons.WeaponHandler;
import megamek.server.Server;
import megamek.server.ServerHelper;
import megamek.server.SmokeCloud;
import org.apache.logging.log4j.LogManager;

import java.util.*;
import java.util.stream.Collectors;

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
        gameManager.entityActionManager.processMovement(entity, md, losCache, gameManager);

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
        gameManager.environmentalEffectManager.applyAffectedBldgs(gameManager);

        // Unit movement may detect hidden units
        gameManager.utilityManager.detectHiddenUnits(gameManager);

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
        gameManager.environmentalEffectManager.cleanupPhysicalAttacks(gameManager);

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
            gameManager.combatManager.resolvePhysicalAttack(pr, cen, gameManager);
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
                gameManager.environmentalEffectManager.addAffectedBldg(bldg, true, gameManager);
                if (wigeFlyover) {
                    // If the building is collapsed by a WiGE flying over it, the WiGE drops one level of elevation.
                    entity.setElevation(entity.getElevation() - 1);
                }
            }
        }

        entity.setDone(true);
        entity.setDeployed(true);
        gameManager.entityUpdate(entity.getId());
        gameManager.reportManager.addReport(gameManager.utilityManager.doSetLocationsExposure(entity, hex, false, entity.getElevation(), gameManager), gameManager);
    }

    protected void resetEntityRound(GameManager gameManager) {
        for (Iterator<Entity> e = gameManager.game.getEntities(); e.hasNext(); ) {
            Entity entity = e.next();
            entity.newRound(gameManager.game.getRoundCount());
        }
    }

    /**
     * Steps through an entity movement packet, executing it.
     *  @param entity   The Entity that is moving
     * @param md       The MovePath that defines how the Entity moves
     * @param losCache A cache that stores Los between various Entities and
     *                 targets.  In double blind games, we may need to compute a
     *                 lot of LosEffects, so caching them can really speed
     * @param gameManager
     */
    protected void processMovement(Entity entity, MovePath md, Map<EntityTargetPair,
            LosEffects> losCache, GameManager gameManager) {
        // Make sure the cache isn't null
        if (losCache == null) {
            losCache = new HashMap<>();
        }
        Report r;
        boolean sideslipped = false; // for VTOL side slipping
        PilotingRollData rollTarget;

        // check for fleeing
        if (md.contains(MovePath.MoveStepType.FLEE)) {
            gameManager.reportManager.addReport(gameManager.entityActionManager.processLeaveMap(md, false, -1, gameManager), gameManager);
            return;
        }

        if (md.contains(MovePath.MoveStepType.EJECT)) {
            if (entity.isLargeCraft() && !entity.isCarcass()) {
                r = new Report(2026);
                r.subject = entity.getId();
                r.addDesc(entity);
                gameManager.addReport(r);
                Aero ship = (Aero) entity;
                ship.setEjecting(true);
                gameManager.entityUpdate(ship.getId());
                Coords legalPos = entity.getPosition();
                //Get the step so we can pass it in and get the abandon coords from it
                for (final Enumeration<MoveStep> i = md.getSteps(); i
                        .hasMoreElements();) {
                    final MoveStep step = i.nextElement();
                    if (step.getType() == MovePath.MoveStepType.EJECT) {
                        legalPos = step.getTargetPosition();
                    }
                }
                gameManager.reportManager.addReport(gameManager.ejectSpacecraft(ship, ship.isSpaceborne(), (ship.isAirborne() && !ship.isSpaceborne()), legalPos), gameManager);
                //If we're grounded or destroyed by crew loss, end movement
                if (entity.isDoomed() || (!entity.isSpaceborne() && !entity.isAirborne())) {
                    return;
                }
            } else if ((entity instanceof Mech) || (entity instanceof Aero)) {
                r = new Report(2020);
                r.subject = entity.getId();
                r.add(entity.getCrew().getName());
                r.addDesc(entity);
                gameManager.addReport(r);
                gameManager.reportManager.addReport(gameManager.ejectEntity(entity, false), gameManager);
                return;
            } else if ((entity instanceof Tank) && !entity.isCarcass()) {
                r = new Report(2025);
                r.subject = entity.getId();
                r.addDesc(entity);
                gameManager.addReport(r);
                gameManager.reportManager.addReport(gameManager.ejectEntity(entity, false), gameManager);
                return;
            }
        }

        if (md.contains(MovePath.MoveStepType.CAREFUL_STAND)) {
            entity.setCarefulStand(true);
        }
        if (md.contains(MovePath.MoveStepType.BACKWARDS)) {
            entity.setMovedBackwards(true);
            if (md.getMpUsed() > entity.getWalkMP()) {
                entity.setPowerReverse(true);
            }
        }

        if (md.contains(MovePath.MoveStepType.TAKEOFF) && entity.isAero()) {
            IAero a = (IAero) entity;
            a.setCurrentVelocity(1);
            a.liftOff(1);
            if (entity instanceof Dropship) {
                gameManager.environmentalEffectManager.applyDropShipProximityDamage(md.getFinalCoords(), true, md.getFinalFacing(), entity, gameManager);
            }
            gameManager.checkForTakeoffDamage(a);
            entity.setPosition(entity.getPosition().translated(entity.getFacing(), a.getTakeOffLength()));
            entity.setDone(true);
            gameManager.entityUpdate(entity.getId());
            return;
        }

        if (md.contains(MovePath.MoveStepType.VTAKEOFF) && entity.isAero()) {
            IAero a = (IAero) entity;
            rollTarget = a.checkVerticalTakeOff();
            if (gameManager.utilityManager.doVerticalTakeOffCheck(entity, rollTarget, gameManager)) {
                a.setCurrentVelocity(0);
                a.liftOff(1);
                if (entity instanceof Dropship) {
                    gameManager.environmentalEffectManager.applyDropShipProximityDamage(md.getFinalCoords(), (Dropship) a, gameManager);
                }
                gameManager.checkForTakeoffDamage(a);
            }
            entity.setDone(true);
            gameManager.entityUpdate(entity.getId());
            return;
        }

        if (md.contains(MovePath.MoveStepType.LAND) && entity.isAero()) {
            IAero a = (IAero) entity;
            rollTarget = a.checkLanding(md.getLastStepMovementType(), md.getFinalVelocity(),
                    md.getFinalCoords(), md.getFinalFacing(), false);
            gameManager.attemptLanding(entity, rollTarget);
            gameManager.environmentalEffectManager.checkLandingTerrainEffects(a, true, md.getFinalCoords(),
                    md.getFinalCoords().translated(md.getFinalFacing(), a.getLandingLength()), md.getFinalFacing(), gameManager);
            a.land();
            entity.setPosition(md.getFinalCoords().translated(md.getFinalFacing(),
                    a.getLandingLength()));
            entity.setDone(true);
            gameManager.entityUpdate(entity.getId());
            return;
        }

        if (md.contains(MovePath.MoveStepType.VLAND) && entity.isAero()) {
            IAero a = (IAero) entity;
            rollTarget = a.checkLanding(md.getLastStepMovementType(),
                    md.getFinalVelocity(), md.getFinalCoords(),
                    md.getFinalFacing(), true);
            gameManager.attemptLanding(entity, rollTarget);
            if (entity instanceof Dropship) {
                gameManager.environmentalEffectManager.applyDropShipLandingDamage(md.getFinalCoords(), (Dropship) a, gameManager);
            }
            gameManager.environmentalEffectManager.checkLandingTerrainEffects(a, true, md.getFinalCoords(), md.getFinalCoords(), md.getFinalFacing(), gameManager);
            a.land();
            entity.setPosition(md.getFinalCoords());
            entity.setDone(true);
            gameManager.entityUpdate(entity.getId());
            return;
        }

        // okay, proceed with movement calculations
        Coords lastPos = entity.getPosition();
        Coords curPos = entity.getPosition();
        Hex firstHex = gameManager.game.getBoard().getHex(curPos); // Used to check for start/end magma damage
        int curFacing = entity.getFacing();
        int curVTOLElevation = entity.getElevation();
        int curElevation;
        int lastElevation = entity.getElevation();
        int curAltitude = entity.getAltitude();
        boolean curClimbMode = entity.climbMode();
        // if the entity already used some MPs,
        // it previously tried to get up and fell,
        // and then got another turn. set moveType
        // and overallMoveType accordingly
        // (these are all cleared by Entity.newRound)
        int distance = entity.delta_distance;
        int mpUsed = entity.mpUsed;
        EntityMovementType moveType = entity.moved;
        EntityMovementType overallMoveType;
        boolean firstStep;
        boolean wasProne = entity.isProne();
        boolean fellDuringMovement = false;
        boolean crashedDuringMovement = false;
        boolean dropshipStillUnloading = false;
        boolean detectedHiddenHazard = false;
        boolean turnOver;
        int prevFacing = curFacing;
        Hex prevHex = gameManager.game.getBoard().getHex(curPos);
        final boolean isInfantry = entity instanceof Infantry;
        AttackAction charge = null;
        RamAttackAction ram = null;
        // cache this here, otherwise changing MP in the turn causes
        // erroneous gravity PSRs
        int cachedGravityLimit = -1;
        int thrustUsed = 0;
        int j = 0;
        boolean didMove;
        boolean recovered = false;
        Entity loader = null;
        boolean continueTurnFromPBS = false;
        boolean continueTurnFromFishtail = false;
        boolean continueTurnFromLevelDrop = false;
        boolean continueTurnFromCliffAscent = false;

        // get a list of coordinates that the unit passed through this turn
        // so that I can later recover potential bombing targets
        // it may already have some values
        Vector<Coords> passedThrough = entity.getPassedThrough();
        passedThrough.add(curPos);
        List<Integer> passedThroughFacing = entity.getPassedThroughFacing();
        passedThroughFacing.add(curFacing);

        // Compile the move - don't clip
        // Clipping could affect hidden units; illegal steps aren't processed
        md.compile(gameManager.game, entity, false);

        // if advanced movement is being used then set the new vectors based on
        // move path
        entity.setVectors(md.getFinalVectors());

        overallMoveType = md.getLastStepMovementType();

        // check for starting in liquid magma
        if ((gameManager.game.getBoard().getHex(entity.getPosition())
                .terrainLevel(Terrains.MAGMA) == 2)
                && (entity.getElevation() == 0)) {
            gameManager.doMagmaDamage(entity, false);
        }

        // set acceleration used to default
        if (entity.isAero()) {
            ((IAero) entity).setAccLast(false);
        }

        // check for dropping troops and drop them
        if (entity.isDropping() && !md.contains(MovePath.MoveStepType.HOVER)) {
            entity.setAltitude(entity.getAltitude() - gameManager.game.getPlanetaryConditions().getDropRate());
            // they may have changed their facing
            if (md.length() > 0) {
                entity.setFacing(md.getFinalFacing());
            }
            passedThrough.add(entity.getPosition());
            entity.setPassedThrough(passedThrough);
            passedThroughFacing.add(entity.getFacing());
            entity.setPassedThroughFacing(passedThroughFacing);
            // We may still need to process any conversions for dropping LAMs
            if (entity instanceof LandAirMech && md.contains(MovePath.MoveStepType.CONVERT_MODE)) {
                entity.setMovementMode(md.getFinalConversionMode());
                entity.setConvertingNow(true);
                r = new Report(1210);
                r.subject = entity.getId();
                r.addDesc(entity);
                if (entity.getMovementMode() == EntityMovementMode.WIGE) {
                    r.messageId = 2452;
                } else if (entity.getMovementMode() == EntityMovementMode.AERODYNE) {
                    r.messageId = 2453;
                } else {
                    r.messageId = 2450;
                }
                gameManager.addReport(r);
            }
            entity.setDone(true);
            gameManager.entityUpdate(entity.getId());
            return;
        }

        // iterate through steps
        firstStep = true;
        turnOver = false;
        /* Bug 754610: Revert fix for bug 702735. */
        MoveStep prevStep = null;

        List<Entity> hiddenEnemies = new ArrayList<>();
        if (gameManager.game.getOptions().booleanOption(OptionsConstants.ADVANCED_HIDDEN_UNITS)) {
            for (Entity e : gameManager.game.getEntitiesVector()) {
                if (e.isHidden() && e.isEnemyOf(entity) && (e.getPosition() != null)) {
                    hiddenEnemies.add(e);
                }
            }
        }

        Vector<UnitLocation> movePath = new Vector<>();
        EntityMovementType lastStepMoveType = md.getLastStepMovementType();
        for (final Enumeration<MoveStep> i = md.getSteps(); i.hasMoreElements();) {
            final MoveStep step = i.nextElement();
            EntityMovementType stepMoveType = step.getMovementType(md.isEndStep(step));
            wasProne = entity.isProne();
            boolean isPavementStep = step.isPavementStep();
            entity.inReverse = step.isThisStepBackwards();
            boolean entityFellWhileAttemptingToStand = false;
            boolean isOnGround = !i.hasMoreElements();
            isOnGround |= stepMoveType != EntityMovementType.MOVE_JUMP;
            isOnGround &= step.getElevation() < 1;

            // Check for hidden units point blank shots
            if (gameManager.game.getOptions().booleanOption(OptionsConstants.ADVANCED_HIDDEN_UNITS)) {
                for (Entity e : hiddenEnemies) {
                    int dist = e.getPosition().distance(step.getPosition());
                    // Checking for same hex and stacking violation
                    if ((dist == 0) && !continueTurnFromPBS
                            && (Compute.stackingViolation(gameManager.game, entity.getId(),
                            step.getPosition(), entity.climbMode()) != null)) {
                        // Moving into hex of a hidden unit detects the unit
                        e.setHidden(false);
                        gameManager.entityUpdate(e.getId());
                        r = new Report(9960);
                        r.addDesc(entity);
                        r.subject = entity.getId();
                        r.add(e.getPosition().getBoardNum());
                        gameManager.vPhaseReport.addElement(r);
                        // Report the block
                        if (gameManager.doBlind()) {
                            r = new Report(9961);
                            r.subject = e.getId();
                            r.addDesc(e);
                            r.addDesc(entity);
                            r.add(step.getPosition().getBoardNum());
                            gameManager.addReport(r);
                        }
                        // Report halted movement
                        r = new Report(9962);
                        r.subject = entity.getId();
                        r.addDesc(entity);
                        r.add(step.getPosition().getBoardNum());
                        gameManager.addReport(r);
                        gameManager.addNewLines();
                        Report.addNewline(gameManager.vPhaseReport);
                        // If we aren't at the end, send a special report
                        if ((gameManager.game.getTurnIndex() + 1) < gameManager.game.getTurnVector().size()) {
                            gameManager.communicationManager.send(e.getOwner().getId(), gameManager.packetManager.createSpecialReportPacket(gameManager));
                            gameManager.communicationManager.send(entity.getOwner().getId(), gameManager.packetManager.createSpecialReportPacket(gameManager));
                        }
                        entity.setDone(true);
                        gameManager.entityUpdate(entity.getId(), movePath, true, losCache);
                        return;
                        // Potential point-blank shot
                    } else if ((dist == 1) && !e.madePointblankShot()) {
                        entity.setPosition(step.getPosition());
                        entity.setFacing(step.getFacing());
                        // If not set, BV icons could have wrong facing
                        entity.setSecondaryFacing(step.getFacing());
                        // Update entity position on client
                        gameManager.communicationManager.send(e.getOwnerId(), gameManager.communicationManager.packetManager.createEntityPacket(entity.getId(), null, gameManager));
                        boolean tookPBS = gameManager.entityActionManager.processPointblankShotCFR(e, entity, gameManager);
                        // Movement should be interrupted
                        if (tookPBS) {
                            // Attacking reveals hidden unit
                            e.setHidden(false);
                            gameManager.entityUpdate(e.getId());
                            r = new Report(9960);
                            r.addDesc(entity);
                            r.subject = entity.getId();
                            r.add(e.getPosition().getBoardNum());
                            gameManager.vPhaseReport.addElement(r);
                            continueTurnFromPBS = true;

                            curFacing = entity.getFacing();
                            curPos = entity.getPosition();
                            mpUsed = step.getMpUsed();
                            break;
                        }
                    }
                }
            }

            // stop for illegal movement
            if (stepMoveType == EntityMovementType.MOVE_ILLEGAL) {
                break;
            }

            // Extra damage if first and last hex are magma
            if (firstStep) {
                firstHex = gameManager.game.getBoard().getHex(curPos);
            }
            // stop if the entity already killed itself
            if (entity.isDestroyed() || entity.isDoomed()) {
                break;
            }

            if (entity.getMovementMode() == EntityMovementMode.WIGE) {
                if (step.getType() == MovePath.MoveStepType.UP && !entity.isAirborneVTOLorWIGE()) {
                    entity.setWigeLiftoffHover(true);
                } else if (step.getType() == MovePath.MoveStepType.HOVER) {
                    entity.setWigeLiftoffHover(true);
                    entity.setAssaultDropInProgress(false);
                } else if (step.getType() == MovePath.MoveStepType.DOWN && step.getClearance() == 0) {
                    // If this is the first step, use the Entity's starting elevation
                    int elevation = (prevStep == null) ? entity.getElevation() : prevStep.getElevation();
                    if (entity instanceof LandAirMech) {
                        gameManager.reportManager.addReport(gameManager.entityActionManager.landAirMech((LandAirMech) entity, step.getPosition(), elevation,
                                distance, gameManager), gameManager);
                    } else if (entity instanceof Protomech) {
                        gameManager.reportManager.addReport(gameManager.entityActionManager.landGliderPM((Protomech) entity, step.getPosition(), elevation,
                                distance, gameManager), gameManager);
                    }
                    // landing always ends movement whether successful or not
                }
            }

            // check for MASC failure on first step
            // also check Tanks because they can have superchargers that act
            // like MASc
            if (firstStep) {
                if (entity instanceof VTOL) {
                    // No roll for failure, but +3 on rolls to avoid sideslip.
                    entity.setMASCUsed(md.hasActiveMASC());
                } else if ((entity instanceof Mech) || (entity instanceof Tank)) {
                    // Not necessarily a fall, but we need to give them a new turn to plot movement with
                    // likely reduced MP.
                    fellDuringMovement = gameManager.checkMASCFailure(entity, md) || gameManager.checkSuperchargerFailure(entity, md);
                }
            }

            if (firstStep) {
                rollTarget = entity.checkGunningIt(overallMoveType);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    int mof = gameManager.utilityManager.doSkillCheckWhileMoving(entity, lastElevation, lastPos,
                            curPos, rollTarget, false, gameManager);
                    if (mof > 0) {
                        // Since this is the first step, we don't have a previous step so we'll pass
                        // this one in case it's needed to process a skid.
                        if (gameManager.entityActionManager.processFailedVehicleManeuver(entity, curPos, 0, step,
                                step.isThisStepBackwards(), lastStepMoveType, distance, 2, mof, gameManager)) {
                            if (md.hasActiveMASC() || md.hasActiveSupercharger()) {
                                mpUsed = entity.getRunMP();
                            } else {
                                mpUsed = entity.getRunMPwithoutMASC();
                            }

                            turnOver = true;
                            distance = entity.delta_distance;
                            curFacing = entity.getFacing();
                            entity.setSecondaryFacing(curFacing);
                            break;
                        } else if (entity.getFacing() != curFacing) {
                            // If the facing doesn't change we had a minor fishtail that doesn't require
                            // stopping movement.
                            continueTurnFromFishtail = true;
                            curFacing = entity.getFacing();
                            entity.setSecondaryFacing(curFacing);
                            break;
                        }
                    }
                }
            }

            // Check for failed maneuver for overdrive on first step. The rules for overdrive do not
            // state this explicitly, but since combining overdrive with gunning it requires two rolls
            // and gunning does state explicitly that the roll is made before movement, this
            // implies the same for overdrive.
            if (firstStep && (overallMoveType == EntityMovementType.MOVE_SPRINT
                    || overallMoveType == EntityMovementType.MOVE_VTOL_SPRINT)) {
                rollTarget = entity.checkUsingOverdrive(EntityMovementType.MOVE_SPRINT);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    int mof = gameManager.utilityManager.doSkillCheckWhileMoving(entity, lastElevation, lastPos,
                            curPos, rollTarget, false, gameManager);
                    if (mof > 0) {
                        if (gameManager.entityActionManager.processFailedVehicleManeuver(entity, curPos, 0, step, step.isThisStepBackwards(),
                                lastStepMoveType, distance, 2, mof, gameManager)) {
                            if (md.hasActiveMASC() || md.hasActiveSupercharger()) {
                                mpUsed = entity.getRunMP();
                            } else {
                                mpUsed = entity.getRunMPwithoutMASC();
                            }

                            turnOver = true;
                            distance = entity.delta_distance;
                            curFacing = entity.getFacing();
                            entity.setSecondaryFacing(curFacing);
                            break;
                        } else if (entity.getFacing() != curFacing) {
                            // If the facing doesn't change we had a minor fishtail that doesn't require
                            // stopping movement.
                            continueTurnFromFishtail = true;
                            curFacing = entity.getFacing();
                            entity.setSecondaryFacing(curFacing);
                            break;
                        }
                    }
                }
            }

            if (step.getType() == MovePath.MoveStepType.CONVERT_MODE) {
                entity.setConvertingNow(true);

                // Non-omni QuadVees converting to vehicle mode dump any riding BA in the
                // starting hex if they fail to make an anti-mech check.
                // http://bg.battletech.com/forums/index.php?topic=55263.msg1271423#msg1271423
                if (entity instanceof QuadVee && entity.getConversionMode() == QuadVee.CONV_MODE_MECH
                        && !entity.isOmni()) {
                    for (Entity rider : entity.getExternalUnits()) {
                        gameManager.reportManager.addReport(gameManager.checkDropBAFromConverting(entity, rider, curPos, curFacing,
                                false, false, false), gameManager);
                    }
                } else if ((entity.getEntityType() & Entity.ETYPE_LAND_AIR_MECH) != 0) {
                    //External units on LAMs, including swarmers, fall automatically and take damage,
                    // and the LAM itself may take one or more criticals.
                    for (Entity rider : entity.getExternalUnits()) {
                        gameManager.reportManager.addReport(gameManager.checkDropBAFromConverting(entity, rider, curPos, curFacing, true, true, true), gameManager);
                    }
                    final int swarmerId = entity.getSwarmAttackerId();
                    if (Entity.NONE != swarmerId) {
                        gameManager.reportManager.addReport(gameManager.checkDropBAFromConverting(entity, gameManager.game.getEntity(swarmerId),
                                curPos, curFacing, true, true, true), gameManager);
                    }
                }

                continue;
            }

            // did the entity move?
            didMove = step.getDistance() > distance;

            // check for aero stuff
            if (entity.isAirborne() && entity.isAero()) {
                IAero a = (IAero) entity;
                j++;

                // increment straight moves (can't do it at end, because not all
                // steps may be processed)
                a.setStraightMoves(step.getNStraight());

                // TODO : change the way this check is made
                if (!didMove && (md.length() != j)) {
                    thrustUsed += step.getMp();
                } else {
                    // if this was the last move and distance was zero, then add
                    // thrust
                    if (!didMove && (md.length() == j)) {
                        thrustUsed += step.getMp();
                    }
                    // then we moved to a new hex or the last step so check
                    // conditions
                    // structural damage
                    rollTarget = a.checkThrustSI(thrustUsed, overallMoveType);
                    if ((rollTarget.getValue() != TargetRoll.CHECK_FALSE)
                            && !(entity instanceof FighterSquadron) && !gameManager.game.useVectorMove()) {
                        if (!gameManager.utilityManager.doSkillCheckInSpace(entity, rollTarget, gameManager)) {
                            a.setSI(a.getSI() - 1);
                            if (entity instanceof LandAirMech) {
                                gameManager.reportManager.addReport(gameManager.criticalEntity(entity, Mech.LOC_CT, false, 0, 1), gameManager);
                            }
                            // check for destruction
                            if (a.getSI() == 0) {
                                // Lets auto-eject if we can!
                                if (a instanceof LandAirMech) {
                                    // LAMs eject if the CT destroyed switch is on
                                    LandAirMech lam = (LandAirMech) a;
                                    if (lam.isAutoEject()
                                            && (!gameManager.game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                            || (gameManager.game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                            && lam.isCondEjectCTDest()))) {
                                        gameManager.reportManager.addReport(gameManager.ejectEntity(entity, true, false), gameManager);
                                    }
                                } else {
                                    // Aeros eject if the SI Destroyed switch is on
                                    Aero aero = (Aero) a;
                                    if (aero.isAutoEject()
                                            && (!gameManager.game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                            || (gameManager.game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                            && aero.isCondEjectSIDest()))) {
                                        gameManager.reportManager.addReport(gameManager.ejectEntity(entity, true, false), gameManager);
                                    }
                                }
                                gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity(entity, "Structural Integrity Collapse",
                                        false, gameManager), gameManager);
                            }
                        }
                    }

                    // check for pilot damage
                    int hits = entity.getCrew().getHits();
                    int health = 6 - hits;

                    if ((thrustUsed > (2 * health)) && !gameManager.game.useVectorMove()
                            && !(entity instanceof TeleMissile)) {
                        int targetRoll = 2 + (thrustUsed - (2 * health))
                                + (2 * hits);
                        gameManager.utilityManager.resistGForce(entity, targetRoll, gameManager);
                    }

                    thrustUsed = 0;
                }

                if (step.getType() == MovePath.MoveStepType.RETURN) {
                    a.setCurrentVelocity(md.getFinalVelocity());
                    entity.setAltitude(curAltitude);
                    gameManager.entityActionManager.processLeaveMap(md, true, Compute.roundsUntilReturn(gameManager.game, entity), gameManager);
                    return;
                }

                if (step.getType() == MovePath.MoveStepType.OFF) {
                    a.setCurrentVelocity(md.getFinalVelocity());
                    entity.setAltitude(curAltitude);
                    gameManager.entityActionManager.processLeaveMap(md, true, -1, gameManager);
                    return;
                }

                rollTarget = a.checkRolls(step, overallMoveType);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    gameManager.game.addControlRoll(new PilotingRollData(entity.getId(), 0, "excess roll"));
                }

                rollTarget = a.checkManeuver(step, overallMoveType);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    if (!gameManager.utilityManager.doSkillCheckManeuver(entity, rollTarget, gameManager)) {
                        a.setFailedManeuver(true);
                        int forward = Math.max(step.getVelocityLeft() / 2, 1);
                        if (forward < step.getVelocityLeft()) {
                            fellDuringMovement = true;
                        }
                        // multiply forward by 16 when on ground hexes
                        if (gameManager.game.getBoard().onGround()) {
                            forward *= 16;
                        }
                        while (forward > 0) {
                            curPos = curPos.translated(step.getFacing());
                            forward--;
                            distance++;
                            a.setStraightMoves(a.getStraightMoves() + 1);
                            // make sure it didn't fly off the map
                            if (!gameManager.game.getBoard().contains(curPos)) {
                                a.setCurrentVelocity(md.getFinalVelocity());
                                gameManager.entityActionManager.processLeaveMap(md, true, Compute.roundsUntilReturn(gameManager.game, entity), gameManager);
                                return;
                                // make sure it didn't crash
                            } else if (gameManager.entityActionManager.checkCrash(entity, curPos, step.getAltitude(), gameManager)) {
                                gameManager.reportManager.addReport(gameManager.entityActionManager.processCrash(entity, step.getVelocity(), curPos, gameManager), gameManager);
                                forward = 0;
                                fellDuringMovement = false;
                                crashedDuringMovement = true;
                            }
                        }
                        break;
                    }
                }

                // if out of control, check for possible collision
                if (didMove && a.isOutControlTotal()) {
                    Iterator<Entity> targets = gameManager.game.getEntities(step.getPosition());
                    if (targets.hasNext()) {
                        // Somebody here so check to see if there is a collision
                        int checkRoll = Compute.d6(2);
                        // TODO : change this to 11 for Large Craft
                        int targetRoll = 11;
                        if ((a instanceof Dropship) || (entity instanceof Jumpship)) {
                            targetRoll = 10;
                        }
                        if (checkRoll >= targetRoll) {
                            // this gets complicated, I need to check for each
                            // unit type
                            // by order of movement sub-phase
                            Vector<Integer> potentialSpaceStation = new Vector<>();
                            Vector<Integer> potentialWarShip = new Vector<>();
                            Vector<Integer> potentialJumpShip = new Vector<>();
                            Vector<Integer> potentialDropShip = new Vector<>();
                            Vector<Integer> potentialSmallCraft = new Vector<>();
                            Vector<Integer> potentialASF = new Vector<>();

                            while (targets.hasNext()) {
                                int id = targets.next().getId();
                                Entity ce = gameManager.game.getEntity(id);
                                // if we are in atmosphere and not the same altitude
                                // then skip
                                if (!gameManager.game.getBoard().inSpace() && (ce.getAltitude() != curAltitude)) {
                                    continue;
                                }
                                // you can't collide with yourself
                                if (ce.equals(a)) {
                                    continue;
                                }
                                if (ce instanceof SpaceStation) {
                                    potentialSpaceStation.addElement(id);
                                } else if (ce instanceof Warship) {
                                    potentialWarShip.addElement(id);
                                } else if (ce instanceof Jumpship) {
                                    potentialJumpShip.addElement(id);
                                } else if (ce instanceof Dropship) {
                                    potentialDropShip.addElement(id);
                                } else if (ce instanceof SmallCraft) {
                                    potentialSmallCraft.addElement(id);
                                } else {
                                    // ASF can actually include anything,
                                    // because we might
                                    // have combat dropping troops
                                    potentialASF.addElement(id);
                                }
                            }

                            // ok now go through and see if these have anybody in them
                            int chosen;
                            Entity target;
                            Coords destination;

                            if (!potentialSpaceStation.isEmpty()) {
                                chosen = Compute.randomInt(potentialSpaceStation.size());
                                target = gameManager.game.getEntity(potentialSpaceStation.elementAt(chosen));
                                destination = target.getPosition();
                                if (gameManager.entityActionManager.processCollision(entity, target, lastPos, gameManager)) {
                                    curPos = destination;
                                    break;
                                }
                            } else if (!potentialWarShip.isEmpty()) {
                                chosen = Compute.randomInt(potentialWarShip.size());
                                target = gameManager.game.getEntity(potentialWarShip.elementAt(chosen));
                                destination = target.getPosition();
                                if (gameManager.entityActionManager.processCollision(entity, target, lastPos, gameManager)) {
                                    curPos = destination;
                                    break;
                                }
                            } else if (!potentialJumpShip.isEmpty()) {
                                chosen = Compute.randomInt(potentialJumpShip.size());
                                target = gameManager.game.getEntity(potentialJumpShip.elementAt(chosen));
                                destination = target.getPosition();
                                if (gameManager.entityActionManager.processCollision(entity, target, lastPos, gameManager)) {
                                    curPos = destination;
                                    break;
                                }
                            } else if (!potentialDropShip.isEmpty()) {
                                chosen = Compute.randomInt(potentialDropShip.size());
                                target = gameManager.game.getEntity(potentialDropShip.elementAt(chosen));
                                destination = target.getPosition();
                                if (gameManager.entityActionManager.processCollision(entity, target, lastPos, gameManager)) {
                                    curPos = destination;
                                    break;
                                }
                            } else if (!potentialSmallCraft.isEmpty()) {
                                chosen = Compute.randomInt(potentialSmallCraft.size());
                                target = gameManager.game.getEntity(potentialSmallCraft.elementAt(chosen));
                                destination = target.getPosition();
                                if (gameManager.entityActionManager.processCollision(entity, target, lastPos, gameManager)) {
                                    curPos = destination;
                                    break;
                                }
                            } else if (!potentialASF.isEmpty()) {
                                chosen = Compute.randomInt(potentialASF.size());
                                target = gameManager.game.getEntity(potentialASF.elementAt(chosen));
                                destination = target.getPosition();
                                if (gameManager.entityActionManager.processCollision(entity, target, lastPos, gameManager)) {
                                    curPos = destination;
                                    break;
                                }
                            }
                        }
                    }
                }

                // if in the atmosphere, check for a potential crash
                if (gameManager.entityActionManager.checkCrash(entity, step.getPosition(), step.getAltitude(), gameManager)) {
                    gameManager.reportManager.addReport(gameManager.entityActionManager.processCrash(entity, md.getFinalVelocity(), curPos, gameManager), gameManager);
                    crashedDuringMovement = true;
                    // don't do the rest
                    break;
                }

                // handle fighter launching
                if (step.getType() == MovePath.MoveStepType.LAUNCH) {
                    TreeMap<Integer, Vector<Integer>> launched = step.getLaunched();
                    Set<Integer> bays = launched.keySet();
                    Iterator<Integer> bayIter = bays.iterator();
                    Bay currentBay;
                    while (bayIter.hasNext()) {
                        int bayId = bayIter.next();
                        currentBay = entity.getFighterBays().elementAt(bayId);
                        Vector<Integer> launches = launched.get(bayId);
                        int nLaunched = launches.size();
                        // need to make some decisions about how to handle the
                        // distribution
                        // of fighters to doors beyond the launch rate. The most
                        // sensible thing
                        // is probably to distribute them evenly.
                        int doors = currentBay.getCurrentDoors();
                        int[] distribution = new int[doors];
                        for (int l = 0; l < nLaunched; l++) {
                            distribution[l % doors] = distribution[l % doors] + 1;
                        }
                        // ok, now lets launch them
                        r = new Report(9380);
                        r.add(entity.getDisplayName());
                        r.subject = entity.getId();
                        r.add(nLaunched);
                        r.add("bay " + currentBay.getBayNumber() + " (" + doors + " doors)");
                        gameManager.addReport(r);
                        int currentDoor = 0;
                        int fighterCount = 0;
                        boolean doorDamage = false;
                        for (int fighterId : launches) {
                            // check to see if we are in the same door
                            fighterCount++;

                            // check for door damage
                            Report doorReport = null;
                            if (!doorDamage && (distribution[currentDoor] > 2) && (fighterCount > 2)) {
                                doorReport = new Report(9378);
                                doorReport.subject = entity.getId();
                                doorReport.indent(2);
                                Roll diceRoll = Compute.rollD6(2);
                                doorReport.add(diceRoll);

                                if (diceRoll.getIntValue() == 2) {
                                    doorDamage = true;
                                    doorReport.choose(true);
                                    currentBay.destroyDoorNext();
                                } else {
                                    doorReport.choose(false);
                                }
                                doorReport.newlines++;
                            }

                            if (fighterCount > distribution[currentDoor]) {
                                // move to a new door
                                currentDoor++;
                                fighterCount = 0;
                                doorDamage = false;
                            }
                            int bonus = Math.max(0,
                                    distribution[currentDoor] - 2);

                            Entity fighter = gameManager.game.getEntity(fighterId);
                            if (!gameManager.environmentalEffectManager.launchUnit(entity, fighter, curPos, curFacing, step.getVelocity(),
                                    step.getAltitude(), step.getVectors(), bonus, gameManager)) {
                                LogManager.getLogger().error("Server was told to unload "
                                        + fighter.getDisplayName() + " from " + entity.getDisplayName()
                                        + " into " + curPos.getBoardNum());
                            }
                            if (doorReport != null) {
                                gameManager.addReport(doorReport);
                            }
                        }
                    }
                    // now apply any damage to bay doors
                    entity.resetBayDoors();
                }

                // handle DropShip undocking
                if (step.getType() == MovePath.MoveStepType.UNDOCK) {
                    TreeMap<Integer, Vector<Integer>> launched = step.getLaunched();
                    Set<Integer> collars = launched.keySet();
                    Iterator<Integer> collarIter = collars.iterator();
                    while (collarIter.hasNext()) {
                        int collarId = collarIter.next();
                        Vector<Integer> launches = launched.get(collarId);
                        int nLaunched = launches.size();
                        // ok, now lets launch them
                        r = new Report(9380);
                        r.add(entity.getDisplayName());
                        r.subject = entity.getId();
                        r.add(nLaunched);
                        r.add("collar " + collarId);
                        gameManager.addReport(r);
                        for (int dropShipId : launches) {
                            // check to see if we are in the same door
                            Entity ds = gameManager.game.getEntity(dropShipId);
                            if (!gameManager.environmentalEffectManager.launchUnit(entity, ds, curPos, curFacing,
                                    step.getVelocity(), step.getAltitude(),
                                    step.getVectors(), 0, gameManager)) {
                                LogManager.getLogger().error("Error! Server was told to unload "
                                        + ds.getDisplayName() + " from "
                                        + entity.getDisplayName() + " into "
                                        + curPos.getBoardNum());
                            }
                        }
                    }
                }

                // handle combat drops
                if (step.getType() == MovePath.MoveStepType.DROP) {
                    TreeMap<Integer, Vector<Integer>> dropped = step.getLaunched();
                    Set<Integer> bays = dropped.keySet();
                    Iterator<Integer> bayIter = bays.iterator();
                    Bay currentBay;
                    while (bayIter.hasNext()) {
                        int bayId = bayIter.next();
                        currentBay = entity.getTransportBays().elementAt(bayId);
                        Vector<Integer> drops = dropped.get(bayId);
                        int nDropped = drops.size();
                        // ok, now lets drop them
                        r = new Report(9386);
                        r.add(entity.getDisplayName());
                        r.subject = entity.getId();
                        r.add(nDropped);
                        gameManager.addReport(r);
                        for (int unitId : drops) {
                            if (Compute.d6(2) == 2) {
                                r = new Report(9390);
                                r.subject = entity.getId();
                                r.indent(1);
                                r.add(currentBay.getType());
                                gameManager.addReport(r);
                                currentBay.destroyDoorNext();
                            }
                            Entity drop = gameManager.game.getEntity(unitId);
                            gameManager.environmentalEffectManager.dropUnit(drop, entity, curPos, step.getAltitude(), gameManager);
                        }
                    }
                    // now apply any damage to bay doors
                    entity.resetBayDoors();
                }
            }

            // check piloting skill for getting up
            rollTarget = entity.checkGetUp(step, overallMoveType);

            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                // Unless we're an ICE- or fuel cell-powered IndustrialMech,
                // standing up builds heat.
                if ((entity instanceof Mech) && entity.hasEngine() && !(((Mech) entity).isIndustrial()
                        && ((entity.getEngine().getEngineType() == Engine.COMBUSTION_ENGINE)
                        || (entity.getEngine().getEngineType() == Engine.FUEL_CELL)))) {
                    entity.heatBuildup += 1;
                }
                entity.setProne(false);
                // entity.setHullDown(false);
                wasProne = false;
                gameManager.game.resetPSRs(entity);
                entityFellWhileAttemptingToStand = !gameManager.utilityManager.doSkillCheckInPlace(entity, rollTarget, gameManager);
            }
            // did the entity just fall?
            if (entityFellWhileAttemptingToStand) {
                moveType = stepMoveType;
                curFacing = entity.getFacing();
                curPos = entity.getPosition();
                mpUsed = step.getMpUsed();
                fellDuringMovement = true;
                if (!entity.isCarefulStand()) {
                    break;
                }
            } else if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                entity.setHullDown(false);
            }

            if (step.getType() == MovePath.MoveStepType.UNJAM_RAC) {
                entity.setUnjammingRAC(true);
                gameManager.game.addAction(new UnjamAction(entity.getId()));

                // for Aeros this will end movement prematurely
                // if we break
                if (!(entity.isAirborne())) {
                    break;
                }
            }

            if (step.getType() == MovePath.MoveStepType.LAY_MINE) {
                gameManager.layMine(entity, step.getMineToLay(), step.getPosition());
                continue;
            }

            if (step.getType() == MovePath.MoveStepType.CLEAR_MINEFIELD) {
                ClearMinefieldAction cma = new ClearMinefieldAction(entity.getId(), step.getMinefield());
                entity.setClearingMinefield(true);
                gameManager.game.addAction(cma);
                break;
            }

            if ((step.getType() == MovePath.MoveStepType.SEARCHLIGHT)
                    && entity.hasSearchlight()) {
                final boolean SearchOn = !entity.isUsingSearchlight();
                entity.setSearchlightState(SearchOn);
                if (gameManager.doBlind()) { // if double blind, we may need to filter the
                    // players that receive this message
                    Vector<Player> playersVector = gameManager.game.getPlayersVector();
                    Vector<Player> vCanSee = gameManager.whoCanSee(entity);
                    for (Player p : playersVector) {
                        if (vCanSee.contains(p)) { // Player sees the unit
                            gameManager.communicationManager.sendServerChat(p.getId(),
                                    entity.getDisplayName()
                                            + " switched searchlight "
                                            + (SearchOn ? "on" : "off") + '.');
                        } else {
                            gameManager.communicationManager.sendServerChat(p.getId(),
                                    "An unseen unit" + " switched searchlight "
                                            + (SearchOn ? "on" : "off") + '.');
                        }
                    }
                } else { // No double blind, everyone can see this
                    gameManager.communicationManager.sendServerChat(
                            entity.getDisplayName() + " switched searchlight "
                                    + (SearchOn ? "on" : "off") + '.');
                }
            }

            // set most step parameters
            moveType = stepMoveType;
            distance = step.getDistance();
            mpUsed = step.getMpUsed();

            if (cachedGravityLimit < 0) {
                cachedGravityLimit = EntityMovementType.MOVE_JUMP == moveType
                        ? entity.getJumpMP(MPCalculationSetting.NO_GRAVITY)
                        : entity.getRunningGravityLimit();
            }
            // check for charge
            if (step.getType() == MovePath.MoveStepType.CHARGE) {
                if (entity.canCharge()) {
                    gameManager.checkExtremeGravityMovement(entity, step, lastStepMoveType,
                            curPos, cachedGravityLimit);
                    Targetable target = step.getTarget(gameManager.game);
                    if (target != null) {
                        ChargeAttackAction caa = new ChargeAttackAction(
                                entity.getId(), target.getTargetType(),
                                target.getId(), target.getPosition());
                        entity.setDisplacementAttack(caa);
                        gameManager.game.addCharge(caa);
                        charge = caa;
                    } else {
                        String message = "Illegal charge!! " + entity.getDisplayName() +
                                " is attempting to charge a null target!";
                        LogManager.getLogger().info(message);
                        gameManager.communicationManager.sendServerChat(message);
                        return;
                    }
                } else if (entity.isAirborneVTOLorWIGE() && entity.canRam()) {
                    gameManager.checkExtremeGravityMovement(entity, step, lastStepMoveType,
                            curPos, cachedGravityLimit);
                    Targetable target = step.getTarget(gameManager.game);
                    if (target != null) {
                        AirmechRamAttackAction raa = new AirmechRamAttackAction(
                                entity.getId(), target.getTargetType(),
                                target.getId(), target.getPosition());
                        entity.setDisplacementAttack(raa);
                        entity.setRamming(true);
                        gameManager.game.addCharge(raa);
                        charge = raa;
                    } else {
                        String message = "Illegal charge!! " + entity.getDisplayName() + " is attempting to charge a null target!";
                        LogManager.getLogger().info(message);
                        gameManager.communicationManager.sendServerChat(message);
                        return;
                    }
                } else {
                    gameManager.communicationManager.sendServerChat("Illegal charge!! I don't think "
                            + entity.getDisplayName()
                            + " should be allowed to charge,"
                            + " but the client of "
                            + entity.getOwner().getName() + " disagrees.");
                    gameManager.communicationManager.sendServerChat("Please make sure "
                            + entity.getOwner().getName()
                            + " is running MegaMek " + MMConstants.VERSION
                            + ", or if that is already the case, submit a bug report at https://github.com/MegaMek/megamek/issues");
                    return;
                }
                break;
            }

            // check for dfa
            if (step.getType() == MovePath.MoveStepType.DFA) {
                if (entity.canDFA()) {
                    gameManager.checkExtremeGravityMovement(entity, step, lastStepMoveType, curPos, cachedGravityLimit);
                    Targetable target = step.getTarget(gameManager.game);

                    int targetType;
                    int targetID;

                    // if it's a valid target, then simply pass along the type and ID
                    if (target != null) {
                        targetID = target.getId();
                        targetType = target.getTargetType();
                        // if the target has become invalid somehow, or was incorrectly declared in the first place
                        // log the error, then put some defaults in for the DFA and proceed as if the target had been moved/destroyed
                    } else {
                        String errorMessage = "Illegal DFA by " + entity.getDisplayName() + " against non-existent entity at " + step.getTargetPosition();
                        gameManager.communicationManager.sendServerChat(errorMessage);
                        LogManager.getLogger().error(errorMessage);
                        targetID = Entity.NONE;
                        // doesn't really matter, DFA processing will cut out early if target resolves as null
                        targetType = Targetable.TYPE_ENTITY;
                    }

                    DfaAttackAction daa = new DfaAttackAction(entity.getId(),
                            targetType, targetID,
                            step.getPosition());
                    entity.setDisplacementAttack(daa);
                    entity.setElevation(step.getElevation());
                    gameManager.game.addCharge(daa);
                    charge = daa;

                } else {
                    gameManager.communicationManager.sendServerChat("Illegal DFA!! I don't think "
                            + entity.getDisplayName()
                            + " should be allowed to DFA,"
                            + " but the client of "
                            + entity.getOwner().getName() + " disagrees.");
                    gameManager.communicationManager.sendServerChat("Please make sure "
                            + entity.getOwner().getName()
                            + " is running MegaMek " + MMConstants.VERSION
                            + ", or if that is already the case, submit a bug report at https://github.com/MegaMek/megamek/issues");
                    return;
                }

                break;
            }

            // check for ram
            if (step.getType() == MovePath.MoveStepType.RAM) {
                if (entity.canRam()) {
                    Targetable target = step.getTarget(gameManager.game);
                    RamAttackAction raa = new RamAttackAction(entity.getId(),
                            target.getTargetType(), target.getId(),
                            target.getPosition());
                    entity.setRamming(true);
                    gameManager.game.addRam(raa);
                    ram = raa;
                } else {
                    gameManager.communicationManager.sendServerChat("Illegal ram!! I don't think "
                            + entity.getDisplayName()
                            + " should be allowed to charge,"
                            + " but the client of "
                            + entity.getOwner().getName() + " disagrees.");
                    gameManager.communicationManager.sendServerChat("Please make sure "
                            + entity.getOwner().getName()
                            + " is running MegaMek " + MMConstants.VERSION
                            + ", or if that is already the case, submit a bug report at https://github.com/MegaMek/megamek/issues");
                    return;
                }
                break;
            }

            if (step.isVTOLBombingStep()) {
                ((IBomber) entity).setVTOLBombTarget(step.getTarget(gameManager.game));
            } else if (step.isStrafingStep() && (entity instanceof VTOL)) {
                ((VTOL) entity).getStrafingCoords().add(step.getPosition());
            }

            if ((step.getType() == MovePath.MoveStepType.ACC) || (step.getType() == MovePath.MoveStepType.ACCN)) {
                if (entity.isAero()) {
                    IAero a = (IAero) entity;
                    if (step.getType() == MovePath.MoveStepType.ACCN) {
                        a.setAccLast(true);
                    } else {
                        a.setAccDecNow(true);
                        a.setCurrentVelocity(a.getCurrentVelocity() + 1);
                    }
                    a.setNextVelocity(a.getNextVelocity() + 1);
                }
            }

            if ((step.getType() == MovePath.MoveStepType.DEC) || (step.getType() == MovePath.MoveStepType.DECN)) {
                if (entity.isAero()) {
                    IAero a = (IAero) entity;
                    if (step.getType() == MovePath.MoveStepType.DECN) {
                        a.setAccLast(true);
                    } else {
                        a.setAccDecNow(true);
                        a.setCurrentVelocity(a.getCurrentVelocity() - 1);
                    }
                    a.setNextVelocity(a.getNextVelocity() - 1);
                }
            }

            if (step.getType() == MovePath.MoveStepType.EVADE) {
                entity.setEvading(true);
            }

            if (step.getType() == MovePath.MoveStepType.BRACE) {
                entity.setBraceLocation(step.getBraceLocation());
            }

            if (step.getType() == MovePath.MoveStepType.SHUTDOWN) {
                entity.performManualShutdown();
                gameManager.communicationManager.sendServerChat(entity.getDisplayName() + " has shutdown.");
            }

            if (step.getType() == MovePath.MoveStepType.STARTUP) {
                entity.performManualStartup();
                gameManager.communicationManager.sendServerChat(entity.getDisplayName() + " has started up.");
            }

            if (step.getType() == MovePath.MoveStepType.SELF_DESTRUCT) {
                entity.setSelfDestructing(true);
            }

            if (step.getType() == MovePath.MoveStepType.ROLL) {
                if (entity.isAero()) {
                    IAero a = (IAero) entity;
                    a.setRolled(!a.isRolled());
                }
            }

            // check for dig in or fortify
            if (entity instanceof Infantry) {
                Infantry inf = (Infantry) entity;
                if (step.getType() == MovePath.MoveStepType.DIG_IN) {
                    inf.setDugIn(Infantry.DUG_IN_WORKING);
                    continue;
                } else if (step.getType() == MovePath.MoveStepType.FORTIFY) {
                    if (!inf.hasWorkingMisc(MiscType.F_TRENCH_CAPABLE)) {
                        gameManager.communicationManager.sendServerChat(entity.getDisplayName()
                                + " failed to fortify because it is missing suitable equipment");
                    }
                    inf.setDugIn(Infantry.DUG_IN_FORTIFYING1);
                    continue;
                } else if ((step.getType() != MovePath.MoveStepType.TURN_LEFT)
                        && (step.getType() != MovePath.MoveStepType.TURN_RIGHT)) {
                    // other movement clears dug in status
                    inf.setDugIn(Infantry.DUG_IN_NONE);
                }

                if (step.getType() == MovePath.MoveStepType.TAKE_COVER) {
                    if (Infantry.hasValidCover(gameManager.game, step.getPosition(),
                            step.getElevation())) {
                        inf.setTakingCover(true);
                    } else {
                        gameManager.communicationManager.sendServerChat(entity.getDisplayName()
                                + " failed to take cover: "
                                + "no valid unit found in "
                                + step.getPosition());
                    }
                }
            }

            // check for tank fortify
            if (entity instanceof Tank) {
                Tank tnk = (Tank) entity;
                if (step.getType() == MovePath.MoveStepType.FORTIFY) {
                    if (!tnk.hasWorkingMisc(MiscType.F_TRENCH_CAPABLE)) {
                        gameManager.communicationManager.sendServerChat(entity.getDisplayName()
                                + " failed to fortify because it is missing suitable equipment");
                    }
                    tnk.setDugIn(Tank.DUG_IN_FORTIFYING1);
                }
            }

            // If we have turned, check whether we have fulfilled any turn mode requirements.
            if ((step.getType() == MovePath.MoveStepType.TURN_LEFT || step.getType() == MovePath.MoveStepType.TURN_RIGHT)
                    && entity.usesTurnMode()) {
                int straight = 0;
                if (prevStep != null) {
                    straight = prevStep.getNStraight();
                }
                rollTarget = entity.checkTurnModeFailure(overallMoveType, straight,
                        md.getMpUsed(), step.getPosition());
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    int mof = gameManager.utilityManager.doSkillCheckWhileMoving(entity, lastElevation, lastPos,
                            curPos, rollTarget, false, gameManager);
                    if (mof > 0) {
                        if (gameManager.entityActionManager.processFailedVehicleManeuver(entity, curPos,
                                step.getFacing() - curFacing,
                                (null == prevStep) ?step : prevStep,
                                step.isThisStepBackwards(),
                                lastStepMoveType, distance, mof, mof, gameManager)) {
                            if (md.hasActiveMASC() || md.hasActiveSupercharger()) {
                                mpUsed = entity.getRunMP();
                            } else {
                                mpUsed = entity.getRunMPwithoutMASC();
                            }

                            turnOver = true;
                            distance = entity.delta_distance;
                        } else {
                            continueTurnFromFishtail = true;
                        }
                        curFacing = entity.getFacing();
                        curPos = entity.getPosition();
                        entity.setSecondaryFacing(curFacing);
                        break;
                    }
                }
            }

            if (step.getType() == MovePath.MoveStepType.BOOTLEGGER) {
                rollTarget = entity.getBasePilotingRoll();
                entity.addPilotingModifierForTerrain(rollTarget);
                rollTarget.addModifier(0, "bootlegger maneuver");
                int mof = gameManager.utilityManager.doSkillCheckWhileMoving(entity, lastElevation,
                        curPos, curPos, rollTarget, false, gameManager);
                if (mof > 0) {
                    // If the bootlegger maneuver fails, we treat it as a turn in a random direction.
                    gameManager.entityActionManager.processFailedVehicleManeuver(entity, curPos, Compute.d6() < 4 ? -1 : 1,
                            (null == prevStep) ? step : prevStep,
                            step.isThisStepBackwards(), lastStepMoveType, distance, 2, mof, gameManager);
                    curFacing = entity.getFacing();
                    curPos = entity.getPosition();
                    break;
                }
            }

            // set last step parameters
            curPos = step.getPosition();
            if (!((entity.getJumpType() == Mech.JUMP_BOOSTER) && step.isJumping())) {
                curFacing = step.getFacing();
            }
            // check if a building PSR will be needed later, before setting the
            // new elevation
            int buildingMove = entity.checkMovementInBuilding(step, prevStep, curPos, lastPos);
            curVTOLElevation = step.getElevation();
            curAltitude = step.getAltitude();
            curElevation = step.getElevation();
            curClimbMode = step.climbMode();
            // set elevation in case of collapses
            entity.setElevation(step.getElevation());
            // set climb mode in case of skid
            entity.setClimbMode(curClimbMode);

            Hex curHex = gameManager.game.getBoard().getHex(curPos);

            // when first entering a building, we need to roll what type
            // of basement it has
            if (isOnGround && curHex.containsTerrain(Terrains.BUILDING)) {
                Building bldg = gameManager.game.getBoard().getBuildingAt(curPos);
                if (bldg.rollBasement(curPos, gameManager.game.getBoard(), gameManager.vPhaseReport)) {
                    gameManager.communicationManager.sendChangedHex(curPos, gameManager);
                    Vector<Building> buildings = new Vector<>();
                    buildings.add(bldg);
                    gameManager.communicationManager.sendChangedBuildings(buildings, gameManager);
                }
            }

            // check for automatic unstick
            if (entity.canUnstickByJumping() && entity.isStuck()
                    && (moveType == EntityMovementType.MOVE_JUMP)) {
                entity.setStuck(false);
                entity.setCanUnstickByJumping(false);
            }

            // check for leap
            if (!lastPos.equals(curPos)
                    && (stepMoveType != EntityMovementType.MOVE_JUMP) && (entity instanceof Mech)
                    && !entity.isAirborne() && (step.getClearance() <= 0)  // Don't check airborne LAMs
                    && gameManager.game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_TACOPS_LEAPING)) {
                int leapDistance = (lastElevation
                        + gameManager.game.getBoard().getHex(lastPos).getLevel())
                        - (curElevation + curHex.getLevel());
                if (leapDistance > 2) {
                    // skill check for leg damage
                    rollTarget = entity.getBasePilotingRoll(stepMoveType);
                    entity.addPilotingModifierForTerrain(rollTarget, curPos);
                    rollTarget.append(new PilotingRollData(entity.getId(),
                            2 * leapDistance, "leaping (leg damage)"));
                    if (0 < gameManager.utilityManager.doSkillCheckWhileMoving(entity, lastElevation,
                            lastPos, curPos, rollTarget, false, gameManager)) {
                        // do leg damage
                        gameManager.reportManager.addReport(gameManager.damageEntity(entity, new HitData(Mech.LOC_LLEG), leapDistance), gameManager);
                        gameManager.reportManager.addReport(gameManager.damageEntity(entity, new HitData(Mech.LOC_RLEG), leapDistance), gameManager);
                        gameManager.addNewLines();
                        gameManager.reportManager.addReport(gameManager.criticalEntity(entity, Mech.LOC_LLEG, false, 0, 0), gameManager);
                        gameManager.addNewLines();
                        gameManager.reportManager.addReport(gameManager.criticalEntity(entity, Mech.LOC_RLEG, false, 0, 0), gameManager);
                        if (entity instanceof QuadMech) {
                            gameManager.reportManager.addReport(gameManager.damageEntity(entity, new HitData(Mech.LOC_LARM), leapDistance), gameManager);
                            gameManager.reportManager.addReport(gameManager.damageEntity(entity, new HitData(Mech.LOC_RARM), leapDistance), gameManager);
                            gameManager.addNewLines();
                            gameManager.reportManager.addReport(gameManager.criticalEntity(entity, Mech.LOC_LARM, false, 0, 0), gameManager);
                            gameManager.addNewLines();
                            gameManager.reportManager.addReport(gameManager.criticalEntity(entity, Mech.LOC_RARM, false, 0, 0), gameManager);
                        }
                    }
                    // skill check for fall
                    rollTarget = entity.getBasePilotingRoll(stepMoveType);
                    entity.addPilotingModifierForTerrain(rollTarget, curPos);
                    rollTarget.append(new PilotingRollData(entity.getId(),
                            leapDistance, "leaping (fall)"));
                    if (0 < gameManager.utilityManager.doSkillCheckWhileMoving(entity, lastElevation,
                            lastPos, curPos, rollTarget, false, gameManager)) {
                        entity.setElevation(lastElevation);
                        gameManager.reportManager.addReport(gameManager.utilityManager.doEntityFallsInto(entity, lastElevation,
                                lastPos, curPos,
                                entity.getBasePilotingRoll(overallMoveType), false, gameManager), gameManager);
                    }
                }
            }

            // Check for skid.
            rollTarget = entity.checkSkid(moveType, prevHex, overallMoveType,
                    prevStep, step, prevFacing, curFacing, lastPos, curPos,
                    isInfantry, distance - 1);
            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                // Have an entity-meaningful PSR message.
                boolean psrFailed;
                int startingFacing = entity.getFacing();
                if (entity instanceof Mech) {
                    // We need to ensure that falls will happen from the proper
                    // facing
                    entity.setFacing(curFacing);
                    psrFailed = (0 < gameManager.utilityManager.doSkillCheckWhileMoving(entity,
                            lastElevation, lastPos, lastPos, rollTarget, true, gameManager));
                } else {
                    psrFailed = (0 < gameManager.utilityManager.doSkillCheckWhileMoving(entity,
                            lastElevation, lastPos, lastPos, rollTarget, false, gameManager));
                }

                // Does the entity skid?
                if (psrFailed) {

                    if (entity instanceof Tank) {
                        gameManager.reportManager.addReport(gameManager.vehicleMotiveDamage((Tank) entity, 0), gameManager);
                    }

                    curPos = lastPos;
                    int skidDistance = (int) Math.round((double) (distance - 1) / 2);
                    int skidDirection = prevFacing;

                    // All charge damage is based upon
                    // the pre-skid move distance.
                    entity.delta_distance = distance - 1;

                    // Attacks against a skidding target have additional +2.
                    moveType = EntityMovementType.MOVE_SKID;

                    // What is the first hex in the skid?
                    if (step.isThisStepBackwards()) {
                        skidDirection = (skidDirection + 3) % 6;
                    }

                    if (gameManager.entityActionManager.processSkid(entity, curPos, prevStep.getElevation(),
                            skidDirection, skidDistance, prevStep,
                            lastStepMoveType, gameManager)) {
                        return;
                    }

                    // set entity parameters
                    curFacing = entity.getFacing();
                    curPos = entity.getPosition();
                    entity.setSecondaryFacing(curFacing);

                    // skid consumes all movement
                    if (md.hasActiveMASC() || md.hasActiveSupercharger()) {
                        mpUsed = entity.getRunMP();
                    } else {
                        mpUsed = entity.getRunMPwithoutMASC();
                    }

                    entity.moved = moveType;
                    fellDuringMovement = true;
                    turnOver = true;
                    distance = entity.delta_distance;
                    break;

                } else { // End failed-skid-psr
                    // If the check succeeded, restore the facing we had before
                    // if it failed, the fall will have changed facing
                    entity.setFacing(startingFacing);
                }

            } // End need-skid-psr

            // check sideslip
            if ((entity instanceof VTOL)
                    || (entity.getMovementMode() == EntityMovementMode.HOVER)
                    || (entity.getMovementMode() == EntityMovementMode.WIGE
                    && step.getClearance() > 0)) {
                rollTarget = entity.checkSideSlip(moveType, prevHex,
                        overallMoveType, prevStep, prevFacing, curFacing,
                        lastPos, curPos, distance, md.hasActiveMASC());
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    int moF = gameManager.utilityManager.doSkillCheckWhileMoving(entity, lastElevation,
                            lastPos, curPos, rollTarget, false, gameManager);
                    if (moF > 0) {
                        int elev;
                        int sideslipDistance;
                        int skidDirection;
                        Coords start;
                        if (step.getType() == MovePath.MoveStepType.LATERAL_LEFT
                                || step.getType() == MovePath.MoveStepType.LATERAL_RIGHT
                                || step.getType() == MovePath.MoveStepType.LATERAL_LEFT_BACKWARDS
                                || step.getType() == MovePath.MoveStepType.LATERAL_RIGHT_BACKWARDS) {
                            // A failed controlled sideslip always results in moving one additional hex
                            // in the direction of the intentional sideslip.
                            elev = step.getElevation();
                            sideslipDistance = 1;
                            skidDirection = lastPos.direction(curPos);
                            start = curPos;
                        } else {
                            elev = (null == prevStep) ? curElevation : prevStep.getElevation();
                            // maximum distance is hexes moved / 2
                            sideslipDistance = Math.min(moF, distance / 2);
                            skidDirection = prevFacing;
                            start = lastPos;
                        }
                        if (sideslipDistance > 0) {
                            sideslipped = true;
                            r = new Report(2100);
                            r.subject = entity.getId();
                            r.addDesc(entity);
                            r.add(sideslipDistance);
                            gameManager.addReport(r);

                            if (gameManager.entityActionManager.processSkid(entity, start, elev, skidDirection,
                                    sideslipDistance, (null == prevStep) ? step : prevStep,
                                    lastStepMoveType, gameManager)) {
                                return;
                            }

                            if (!entity.isDestroyed() && !entity.isDoomed()
                                    && (mpUsed < entity.getRunMP())) {
                                fellDuringMovement = true; // No, but it should
                                // work...
                            }

                            if ((entity.getElevation() == 0)
                                    && ((entity.getMovementMode() == EntityMovementMode.VTOL)
                                    || (entity.getMovementMode() == EntityMovementMode.WIGE))) {
                                turnOver = true;
                            }
                            // set entity parameters
                            curFacing = step.getFacing();
                            curPos = entity.getPosition();
                            entity.setSecondaryFacing(curFacing);
                            break;
                        }
                    }
                }
            }

            // check if we've moved into rubble
            boolean isLastStep = step.equals(md.getLastStep());
            rollTarget = entity.checkRubbleMove(step, overallMoveType, curHex,
                    lastPos, curPos, isLastStep, isPavementStep);
            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                gameManager.utilityManager.doSkillCheckWhileMoving(entity, lastElevation, lastPos, curPos,
                        rollTarget, true, gameManager);
            }

            // check if we are using reckless movement
            rollTarget = entity.checkRecklessMove(step, overallMoveType, curHex,
                    lastPos, curPos, prevHex);
            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                if (entity instanceof Mech) {
                    gameManager.utilityManager.doSkillCheckWhileMoving(entity, lastElevation, lastPos,
                            curPos, rollTarget, true, gameManager);
                } else if (entity instanceof Tank) {
                    if (0 < gameManager.utilityManager.doSkillCheckWhileMoving(entity, lastElevation,
                            lastPos, curPos, rollTarget, false, gameManager)) {
                        // assume VTOLs in flight are always in clear terrain
                        if ((0 == curHex.terrainsPresent())
                                || (step.getClearance() > 0)) {
                            if (entity instanceof VTOL) {
                                r = new Report(2208);
                            } else {
                                r = new Report(2206);
                            }
                            r.addDesc(entity);
                            r.subject = entity.getId();
                            gameManager.addReport(r);
                            mpUsed = step.getMpUsed() + 1;
                            fellDuringMovement = true;
                            break;
                        }
                        r = new Report(2207);
                        r.addDesc(entity);
                        r.subject = entity.getId();
                        gameManager.addReport(r);
                        // until we get a rules clarification assume that the
                        // entity is both giver and taker
                        // for charge damage
                        HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                        gameManager.reportManager.addReport(gameManager.damageEntity(entity, hit, ChargeAttackAction
                                .getDamageTakenBy(entity, entity)), gameManager);
                        turnOver = true;
                        break;
                    }
                }
            }

            // check for breaking magma crust unless we are jumping over the hex
            if (stepMoveType != EntityMovementType.MOVE_JUMP) {
                if (!curPos.equals(lastPos)) {
                    ServerHelper.checkAndApplyMagmaCrust(curHex, step.getElevation(), entity, curPos, false, gameManager.vPhaseReport, gameManager);
                    ServerHelper.checkEnteringMagma(curHex, step.getElevation(), entity, gameManager);
                }
            }

            if (step.getType() == MovePath.MoveStepType.CHAFF) {
                List<Mounted> chaffDispensers = entity.getMiscEquipment(MiscType.F_CHAFF_POD)
                        .stream().filter(dispenser -> dispenser.isReady())
                        .collect(Collectors.toList());
                if (chaffDispensers.size() > 0) {
                    chaffDispensers.get(0).setFired(true);
                    gameManager.createSmoke(curPos, SmokeCloud.SMOKE_CHAFF_LIGHT, 1);
                    Hex hex = gameManager.game.getBoard().getHex(curPos);
                    hex.addTerrain(new Terrain(Terrains.SMOKE, SmokeCloud.SMOKE_CHAFF_LIGHT));
                    gameManager.communicationManager.sendChangedHex(curPos, gameManager);
                    r = new Report(2512)
                            .addDesc(entity)
                            .subject(entity.getId());

                    gameManager.addReport(r);
                }
            }

            // check for last move ending in magma TODO: build report for end of move
            if (!i.hasMoreElements() && curHex.terrainLevel(Terrains.MAGMA) == 2
                    && firstHex.terrainLevel(Terrains.MAGMA) == 2) {
                r = new Report(2404);
                r.addDesc(entity);
                r.subject = entity.getId();
                gameManager.addReport(r);
                gameManager.doMagmaDamage(entity, false);
            }

            // check if we've moved into a swamp
            rollTarget = entity.checkBogDown(step, lastStepMoveType, curHex,
                    lastPos, curPos, lastElevation, isPavementStep);
            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                if (0 < gameManager.utilityManager.doSkillCheckWhileMoving(entity, lastElevation, lastPos,
                        curPos, rollTarget, false, gameManager)) {
                    entity.setStuck(true);
                    entity.setCanUnstickByJumping(true);
                    r = new Report(2081);
                    r.add(entity.getDisplayName());
                    r.subject = entity.getId();
                    gameManager.addReport(r);
                    // check for quicksand
                    gameManager.reportManager.addReport(gameManager.checkQuickSand(curPos), gameManager);
                    // check for accidental stacking violation
                    Entity violation = Compute.stackingViolation(gameManager.game,
                            entity.getId(), curPos, entity.climbMode());
                    if (violation != null) {
                        // target gets displaced, because of low elevation
                        int direction = lastPos.direction(curPos);
                        Coords targetDest = Compute.getValidDisplacement(gameManager.game,
                                entity.getId(), curPos, direction);
                        gameManager.reportManager.addReport(gameManager.utilityManager.doEntityDisplacement(violation, curPos,
                                targetDest,
                                new PilotingRollData(violation.getId(), 0,
                                        "domino effect"), gameManager), gameManager);
                        // Update the violating entity's position on the client.
                        gameManager.entityUpdate(violation.getId());
                    }
                    break;
                }
            }

            // check to see if we are a mech and we've moved OUT of fire
            Hex lastHex = gameManager.game.getBoard().getHex(lastPos);
            if (entity.tracksHeat() && !entity.isAirborne()) {
                if (!lastPos.equals(curPos) && (prevStep != null)
                        && ((lastHex.containsTerrain(Terrains.FIRE) && (prevStep.getElevation() <= 1))
                        || (lastHex.containsTerrain(Terrains.MAGMA) && (prevStep.getElevation() == 0)))
                        && ((stepMoveType != EntityMovementType.MOVE_JUMP)
                        // Bug #828741 -- jumping bypasses fire, but not on the first step
                        // getMpUsed -- total MP used to this step
                        // getMp -- MP used in this step
                        // the difference will always be 0 on the "first step" of a jump,
                        // and >0 on a step in the midst of a jump
                        || (0 == (step.getMpUsed() - step.getMp())))) {
                    int heat = 0;
                    if (lastHex.containsTerrain(Terrains.FIRE)) {
                        heat += 2;
                    }
                    if (lastHex.terrainLevel(Terrains.MAGMA) == 1) {
                        heat += 2;
                    } else if (lastHex.terrainLevel(Terrains.MAGMA) == 2) {
                        heat += 5;
                    }
                    boolean isMekWithHeatDissipatingArmor = (entity instanceof Mech)
                            && ((Mech) entity).hasIntactHeatDissipatingArmor();
                    if (isMekWithHeatDissipatingArmor) {
                        heat /= 2;
                    }
                    entity.heatFromExternal += heat;
                    r = new Report(2115);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    r.add(heat);
                    gameManager.addReport(r);
                    if (isMekWithHeatDissipatingArmor) {
                        r = new Report(5550);
                        gameManager.addReport(r);
                    }
                }
            }

            // check to see if we are not a mech and we've moved INTO fire
            if (!(entity instanceof Mech)) {
                boolean underwater = gameManager.game.getBoard().getHex(curPos)
                        .containsTerrain(Terrains.WATER)
                        && (gameManager.game.getBoard().getHex(curPos).depth() > 0)
                        && (step.getElevation() < gameManager.game.getBoard().getHex(curPos).getLevel());
                if (gameManager.game.getBoard().getHex(curPos).containsTerrain(
                        Terrains.FIRE) && !lastPos.equals(curPos)
                        && (stepMoveType != EntityMovementType.MOVE_JUMP)
                        && (step.getElevation() <= 1) && !underwater) {
                    gameManager.doFlamingDamage(entity, curPos);
                }
            }

            if ((gameManager.game.getBoard().getHex(curPos).terrainLevel(Terrains.SMOKE) == SmokeCloud.SMOKE_GREEN)
                    && !stepMoveType.equals(EntityMovementType.MOVE_JUMP) && entity.antiTSMVulnerable()) {
                gameManager.reportManager.addReport(gameManager.doGreenSmokeDamage(entity), gameManager);
            }

            // check for extreme gravity movement
            if (!i.hasMoreElements() && !firstStep) {
                gameManager.checkExtremeGravityMovement(entity, step, lastStepMoveType, curPos, cachedGravityLimit);
            }

            // check for revealed minefields;
            // unless we get errata about it, we assume that the check is done
            // every time we enter a new hex
            if (gameManager.game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_BAP)
                    && !lastPos.equals(curPos)) {
                if (ServerHelper.detectMinefields(gameManager.game, entity, curPos, gameManager.vPhaseReport, gameManager) ||
                        ServerHelper.detectHiddenUnits(gameManager.game, entity, curPos, gameManager.vPhaseReport, gameManager)) {
                    detectedHiddenHazard = true;

                    if (i.hasMoreElements() && (stepMoveType != EntityMovementType.MOVE_JUMP)) {
                        md.clear();
                    }
                }
            }

            // check for minefields. have to check both new hex and new elevation
            // VTOLs may land and submarines may rise or lower into a minefield
            // jumping units may end their movement with a turn but should still check at end of movement
            if (!lastPos.equals(curPos) || (lastElevation != curElevation) ||
                    ((stepMoveType == EntityMovementType.MOVE_JUMP) && !i.hasMoreElements())) {
                boolean boom = false;
                if (isOnGround) {
                    boom = gameManager.checkVibrabombs(entity, curPos, false, lastPos, curPos, gameManager.vPhaseReport);
                }
                if (gameManager.game.containsMinefield(curPos)) {
                    // set the new position temporarily, because
                    // infantry otherwise would get double damage
                    // when moving from clear into mined woods
                    entity.setPosition(curPos);
                    if (gameManager.environmentalEffectManager.enterMinefield(entity, curPos, step.getElevation(),
                            isOnGround, gameManager.vPhaseReport, gameManager)) {
                        // resolve any piloting rolls from damage unless unit
                        // was jumping
                        if (stepMoveType != EntityMovementType.MOVE_JUMP) {
                            gameManager.reportManager.addReport(gameManager.resolvePilotingRolls(entity), gameManager);
                            gameManager.game.resetPSRs(entity);
                        }
                        boom = true;
                    }
                    if (wasProne || !entity.isProne()) {
                        entity.setPosition(lastPos);
                    }
                }
                // did anything go boom?
                if (boom) {
                    // set fell during movement so that entity will get another
                    // chance to move with any motive damage
                    // taken account of (functions the same as MASC failure)
                    // only do this if they had more steps (and they were not
                    // jumping
                    if (i.hasMoreElements() && (stepMoveType != EntityMovementType.MOVE_JUMP)) {
                        md.clear();
                        fellDuringMovement = true;
                    }
                    // reset mines if anything detonated
                    gameManager.environmentalEffectManager.resetMines(gameManager);
                }
            }

            // infantry discovers minefields if they end their move
            // in a minefield.
            if (!lastPos.equals(curPos) && !i.hasMoreElements() && isInfantry) {
                if (gameManager.game.containsMinefield(curPos)) {
                    Player owner = entity.getOwner();
                    for (Minefield mf : gameManager.game.getMinefields(curPos)) {
                        if (!owner.containsMinefield(mf)) {
                            r = new Report(2120);
                            r.subject = entity.getId();
                            r.add(entity.getShortName(), true);
                            gameManager.addReport(r);
                            gameManager.environmentalEffectManager.revealMinefield(owner, mf, gameManager);
                        }
                    }
                }
            }

            // check if we've moved into water
            rollTarget = entity.checkWaterMove(step, lastStepMoveType, curHex,
                    lastPos, curPos, isPavementStep);
            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                // Swarmers need special handling.
                final int swarmerId = entity.getSwarmAttackerId();
                boolean swarmerDone = true;
                Entity swarmer = null;
                if (Entity.NONE != swarmerId) {
                    swarmer = gameManager.game.getEntity(swarmerId);
                    swarmerDone = swarmer.isDone();
                }

                // Now do the skill check.
                entity.setFacing(curFacing);
                gameManager.utilityManager.doSkillCheckWhileMoving(entity, lastElevation, lastPos, curPos, rollTarget, true, gameManager);

                // Swarming infantry platoons may drown.
                if (curHex.terrainLevel(Terrains.WATER) > 1) {
                    gameManager.utilityManager.drownSwarmer(entity, curPos, gameManager);
                }

                // Do we need to remove a game turn for the swarmer
                if (!swarmerDone && (swarmer != null)
                        && (swarmer.isDoomed() || swarmer.isDestroyed())) {
                    // We have to diddle with the swarmer's
                    // status to get its turn removed.
                    swarmer.setDone(false);
                    swarmer.setUnloaded(false);

                    // Dead entities don't take turns.
                    gameManager.game.removeTurnFor(swarmer);
                    gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));

                    // Return the original status.
                    swarmer.setDone(true);
                    swarmer.setUnloaded(true);
                }

                // check for inferno wash-off
                gameManager.utilityManager.checkForWashedInfernos(entity, curPos, gameManager);
            }

            // In water, may or may not be a new hex, necessary to
            // check during movement, for breach damage, and always
            // set dry if appropriate
            // TODO : possibly make the locations local and set later
            gameManager.reportManager.addReport(gameManager.utilityManager.doSetLocationsExposure(entity, curHex,
                    stepMoveType == EntityMovementType.MOVE_JUMP,
                    step.getElevation(), gameManager), gameManager);

            // check for breaking ice by breaking through from below
            if ((lastElevation < 0) && (step.getElevation() == 0)
                    && lastHex.containsTerrain(Terrains.ICE)
                    && lastHex.containsTerrain(Terrains.WATER)
                    && (stepMoveType != EntityMovementType.MOVE_JUMP)
                    && !lastPos.equals(curPos)) {
                // need to temporarily reset entity's position so it doesn't
                // fall in the ice
                entity.setPosition(curPos);
                r = new Report(2410);
                r.addDesc(entity);
                gameManager.addReport(r);
                gameManager.reportManager.addReport(gameManager.resolveIceBroken(lastPos), gameManager);
                // ok now set back
                entity.setPosition(lastPos);
            }
            // check for breaking ice by stepping on it
            if (curHex.containsTerrain(Terrains.ICE)
                    && curHex.containsTerrain(Terrains.WATER)
                    && (stepMoveType != EntityMovementType.MOVE_JUMP)
                    && !lastPos.equals(curPos) && !(entity instanceof Infantry)
                    && !(isPavementStep && curHex.containsTerrain(Terrains.BRIDGE))) {
                if (step.getElevation() == 0) {
                    Roll diceRoll = Compute.rollD6(1);
                    r = new Report(2118);
                    r.addDesc(entity);
                    r.add(diceRoll);
                    r.subject = entity.getId();
                    gameManager.addReport(r);

                    if (diceRoll.getIntValue() == 6) {
                        entity.setPosition(curPos);
                        gameManager.reportManager.addReport(gameManager.resolveIceBroken(curPos), gameManager);
                        curPos = entity.getPosition();
                    }
                }
                // or intersecting it
                else if ((step.getElevation() + entity.height()) == 0) {
                    r = new Report(2410);
                    r.addDesc(entity);
                    gameManager.addReport(r);
                    gameManager.reportManager.addReport(gameManager.resolveIceBroken(curPos), gameManager);
                }
            }

            // Check for black ice
            int minTemp = -30;
            boolean useBlackIce = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVANCED_BLACK_ICE);
            boolean goodTemp = gameManager.game.getPlanetaryConditions().getTemperature() <= minTemp;
            boolean goodWeather = gameManager.game.getPlanetaryConditions().getWeather() == PlanetaryConditions.WE_ICE_STORM;
            if (isPavementStep && ((useBlackIce && goodTemp) || goodWeather)) {
                if (!curHex.containsTerrain(Terrains.BLACK_ICE)) {
                    int blackIceChance = Compute.d6(1);
                    if (blackIceChance > 4) {
                        curHex.addTerrain(new Terrain(Terrains.BLACK_ICE, 1));
                        gameManager.communicationManager.sendChangedHex(curPos, gameManager);
                    }
                }
            }

            // Handle loading units.
            if (step.getType() == MovePath.MoveStepType.LOAD) {

                // Find the unit being loaded.
                Entity loaded = null;
                Iterator<Entity> entities = gameManager.game.getEntities(curPos);
                while (entities.hasNext()) {

                    // Is the other unit friendly and not the current entity?
                    loaded = entities.next();

                    // This should never ever happen, but just in case...
                    if (loaded.equals(null)) {
                        continue;
                    }

                    if (!entity.isEnemyOf(loaded) && !entity.equals(loaded)) {
                        // The moving unit should be able to load the other
                        // unit and the other should be able to have a turn.
                        if (!entity.canLoad(loaded) || !loaded.isLoadableThisTurn()) {
                            // Something is fishy in Denmark.
                            LogManager.getLogger().error(entity.getShortName() + " can not load " + loaded.getShortName());
                            loaded = null;
                        } else {
                            // Have the deployed unit load the indicated unit.
                            gameManager.loadUnit(entity, loaded, loaded.getTargetBay());

                            // Stop looking.
                            break;
                        }

                    } else {
                        // Nope. Discard it.
                        loaded = null;
                    }

                } // Handle the next entity in this hex.

                // We were supposed to find someone to load.
                if (loaded == null) {
                    LogManager.getLogger().error("Could not find unit for " + entity.getShortName() + " to load in " + curPos);
                }

            } // End STEP_LOAD

            // Handle towing units.
            if (step.getType() == MovePath.MoveStepType.TOW) {

                // Find the unit being loaded.
                Entity loaded;
                loaded = gameManager.game.getEntity(entity.getTowing());

                // This should never ever happen, but just in case...
                if (loaded == null) {
                    LogManager.getLogger().error("Could not find unit for " + entity.getShortName() + " to tow.");
                    continue;
                }

                // The moving unit should be able to tow the other
                // unit and the other should be able to have a turn.
                //FIXME: I know this check duplicates functions already performed when enabling the Tow button.
                //This code made more sense as borrowed from "Load" where we actually rechecked the hex for the target unit.
                //Do we need it here for safety, client/server sync or can this be further streamlined?
                if (!entity.canTow(loaded.getId())) {
                    // Something is fishy in Denmark.
                    LogManager.getLogger().error(entity.getShortName() + " can not tow " + loaded.getShortName());
                } else {
                    // Have the deployed unit load the indicated unit.
                    gameManager.towUnit(entity, loaded);
                }
            } // End STEP_TOW

            // Handle mounting units to small craft/DropShip
            if (step.getType() == MovePath.MoveStepType.MOUNT) {
                Targetable mountee = step.getTarget(gameManager.game);
                if (mountee instanceof Entity) {
                    Entity dropShip = (Entity) mountee;
                    if (!dropShip.canLoad(entity)) {
                        // Something is fishy in Denmark.
                        LogManager.getLogger().error(dropShip.getShortName() + " can not load " + entity.getShortName());
                    } else {
                        // Have the indicated unit load this unit.
                        entity.setDone(true);
                        gameManager.loadUnit(dropShip, entity, entity.getTargetBay());
                        Bay currentBay = dropShip.getBay(entity);
                        if ((null != currentBay) && (Compute.d6(2) == 2)) {
                            r = new Report(9390);
                            r.subject = entity.getId();
                            r.indent(1);
                            r.add(currentBay.getType());
                            gameManager.addReport(r);
                            currentBay.destroyDoorNext();
                        }
                        // Stop looking.
                        gameManager.entityUpdate(dropShip.getId());
                        return;
                    }
                }
            } // End STEP_MOUNT

            // handle fighter recovery, and also DropShip docking with another large craft
            if (step.getType() == MovePath.MoveStepType.RECOVER) {

                loader = gameManager.game.getEntity(step.getRecoveryUnit());
                boolean isDS = (entity instanceof Dropship);

                rollTarget = entity.getBasePilotingRoll(overallMoveType);
                if (loader.mpUsed > 0) {
                    rollTarget.addModifier(5, "carrier used thrust");
                }
                if (entity.getPartialRepairs().booleanOption("aero_collar_crit")) {
                    rollTarget.addModifier(2, "misrepaired docking collar");
                }
                if (isDS && (((Dropship) entity).getCollarType() == Dropship.COLLAR_PROTOTYPE)) {
                    rollTarget.addModifier(2, "prototype kf-boom");
                }
                Roll diceRoll = Compute.rollD6(2);

                if (isDS) {
                    r = new Report(9388);
                } else {
                    r = new Report(9381);
                }

                r.subject = entity.getId();
                r.add(entity.getDisplayName());
                r.add(loader.getDisplayName());
                r.add(rollTarget);
                r.add(diceRoll);
                r.newlines = 0;
                r.indent(1);

                if (diceRoll.getIntValue() < rollTarget.getValue()) {
                    r.choose(false);
                    gameManager.addReport(r);
                    // damage unit
                    HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                    gameManager.reportManager.addReport(gameManager.damageEntity(entity, hit, 2 * (rollTarget.getValue() - diceRoll.getIntValue())), gameManager);
                } else {
                    r.choose(true);
                    gameManager.addReport(r);
                    recovered = true;
                }
                // check for door damage
                if (diceRoll.getIntValue() == 2) {
                    loader.damageDoorRecovery(entity);
                    r = new Report(9384);
                    r.subject = entity.getId();
                    r.indent(0);
                    r.add(loader.getDisplayName());
                    gameManager.addReport(r);
                }
            }

            // handle fighter squadron joining
            if (step.getType() == MovePath.MoveStepType.JOIN) {
                loader = gameManager.game.getEntity(step.getRecoveryUnit());
                recovered = true;
            }

            // Handle unloading units.
            if (step.getType() == MovePath.MoveStepType.UNLOAD) {
                Targetable unloaded = step.getTarget(gameManager.game);
                Coords unloadPos = curPos;
                int unloadFacing = curFacing;
                if (null != step.getTargetPosition()) {
                    unloadPos = step.getTargetPosition();
                    unloadFacing = curPos.direction(unloadPos);
                }
                if (!gameManager.unloadUnit(entity, unloaded, unloadPos, unloadFacing,
                        step.getElevation())) {
                    LogManager.getLogger().error("Server was told to unload "
                            + unloaded.getDisplayName() + " from "
                            + entity.getDisplayName() + " into "
                            + curPos.getBoardNum());
                }
                // some additional stuff to take care of for small
                // craft/DropShip unloading
                if ((entity instanceof SmallCraft) && (unloaded instanceof Entity)) {
                    Bay currentBay = entity.getBay((Entity) unloaded);
                    if ((null != currentBay) && (Compute.d6(2) == 2)) {
                        r = new Report(9390);
                        r.subject = entity.getId();
                        r.indent(1);
                        r.add(currentBay.getType());
                        gameManager.addReport(r);
                        currentBay.destroyDoorNext();
                    }
                    // now apply any damage to bay doors
                    entity.resetBayDoors();
                    gameManager.entityUpdate(entity.getId());
                    // ok now add another turn for the transport so it can
                    // continue to unload units
                    if (!entity.getUnitsUnloadableFromBays().isEmpty()) {
                        dropshipStillUnloading = true;
                        GameTurn newTurn = new GameTurn.SpecificEntityTurn(
                                entity.getOwner().getId(), entity.getId());
                        // Need to set the new turn's multiTurn state
                        newTurn.setMultiTurn(true);
                        gameManager.game.insertNextTurn(newTurn);
                    }
                    // ok add another turn for the unloaded entity so that it can move
                    if (!(unloaded instanceof Infantry)) {
                        GameTurn newTurn = new GameTurn.SpecificEntityTurn(
                                ((Entity) unloaded).getOwner().getId(),
                                ((Entity) unloaded).getId());
                        // Need to set the new turn's multiTurn state
                        newTurn.setMultiTurn(true);
                        gameManager.game.insertNextTurn(newTurn);
                    }
                    // brief everybody on the turn update
                    gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));
                }
            }

            // Handle disconnecting trailers.
            if (step.getType() == MovePath.MoveStepType.DISCONNECT) {
                Targetable unloaded = step.getTarget(gameManager.game);
                Coords unloadPos = curPos;
                if (null != step.getTargetPosition()) {
                    unloadPos = step.getTargetPosition();
                }
                if (!gameManager.disconnectUnit(entity, unloaded, unloadPos)) {
                    LogManager.getLogger().error(String.format(
                            "Server was told to disconnect %s from %s into %s",
                            unloaded.getDisplayName(), entity.getDisplayName(), curPos.getBoardNum()));
                }
            }

            // moving backwards over elevation change
            if (((step.getType() == MovePath.MoveStepType.BACKWARDS)
                    || (step.getType() == MovePath.MoveStepType.LATERAL_LEFT_BACKWARDS)
                    || (step.getType() == MovePath.MoveStepType.LATERAL_RIGHT_BACKWARDS))
                    && !(md.isJumping()
                    && (entity.getJumpType() == Mech.JUMP_BOOSTER))
                    && (lastHex.getLevel() + lastElevation != curHex.getLevel() + step.getElevation())
                    && !(entity instanceof VTOL)
                    && !(curClimbMode
                    && curHex.containsTerrain(Terrains.BRIDGE)
                    && ((curHex.terrainLevel(Terrains.BRIDGE_ELEV) + curHex.getLevel())
                    == (prevHex.getLevel()
                    + (prevHex.containsTerrain(Terrains.BRIDGE)
                    ? prevHex.terrainLevel(Terrains.BRIDGE_ELEV)
                    : 0))))) {

                // per TacOps, if the mech is walking backwards over an elevation change and falls
                // it falls into the lower hex. The caveat is if it already fell from some other PSR in this
                // invocation of processMovement, then it can't fall again.
                if ((entity instanceof Mech)
                        && (curHex.getLevel() < gameManager.game.getBoard().getHex(lastPos).getLevel())
                        && !entity.hasFallen()) {
                    rollTarget = entity.getBasePilotingRoll(overallMoveType);
                    rollTarget.addModifier(0, "moving backwards over an elevation change");
                    gameManager.utilityManager.doSkillCheckWhileMoving(entity, entity.getElevation(),
                            curPos, curPos, rollTarget, true, gameManager);
                } else if ((entity instanceof Mech) && !entity.hasFallen()) {
                    rollTarget = entity.getBasePilotingRoll(overallMoveType);
                    rollTarget.addModifier(0, "moving backwards over an elevation change");
                    gameManager.utilityManager.doSkillCheckWhileMoving(entity, lastElevation, lastPos, lastPos, rollTarget, true, gameManager);
                } else if (entity instanceof Tank) {
                    rollTarget = entity.getBasePilotingRoll(overallMoveType);
                    rollTarget.addModifier(0, "moving backwards over an elevation change");
                    if (gameManager.utilityManager.doSkillCheckWhileMoving(entity, entity.getElevation(), curPos, lastPos,
                            rollTarget, false, gameManager) < 0) {
                        curPos = lastPos;
                    }
                }
            }

            // Handle non-infantry moving into a building.
            if (buildingMove > 0) {
                // Get the building being exited.
                Building bldgExited = null;
                if ((buildingMove & 1) == 1) {
                    bldgExited = gameManager.game.getBoard().getBuildingAt(lastPos);
                }

                // Get the building being entered.
                Building bldgEntered = null;
                if ((buildingMove & 2) == 2) {
                    bldgEntered = gameManager.game.getBoard().getBuildingAt(curPos);
                }

                // ProtoMechs changing levels within a building cause damage
                if (((buildingMove & 8) == 8) && (entity instanceof Protomech)) {
                    Building bldg = gameManager.game.getBoard().getBuildingAt(curPos);
                    Vector<Report> vBuildingReport = gameManager.damageBuilding(bldg, 1, curPos);
                    for (Report report : vBuildingReport) {
                        report.subject = entity.getId();
                    }
                    gameManager.reportManager.addReport(vBuildingReport, gameManager);
                }

                boolean collapsed = false;
                if ((bldgEntered != null)) {
                    String reason;
                    if (bldgExited == null) {
                        // If we're not leaving a building, just handle the "entered".
                        reason = "entering";
                    } else if (bldgExited.equals(bldgEntered) && !(entity instanceof Protomech)
                            && !(entity instanceof Infantry)) {
                        // If we're moving within the same building, just handle the "within".
                        reason = "moving in";
                    } else {
                        // If we have different buildings, roll for each.
                        reason = "entering";
                    }

                    gameManager.passBuildingWall(entity, bldgEntered, lastPos, curPos, distance, reason,
                            step.isThisStepBackwards(), lastStepMoveType, true);
                    gameManager.environmentalEffectManager.addAffectedBldg(bldgEntered, collapsed, gameManager);
                }

                // Clean up the entity if it has been destroyed.
                if (entity.isDoomed()) {
                    entity.setDestroyed(true);
                    gameManager.game.moveToGraveyard(entity.getId());
                    gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(entity.getId(), gameManager));

                    // The entity's movement is completed.
                    return;
                }

                // TODO : what if a building collapses into rubble?
            }

            if (stepMoveType != EntityMovementType.MOVE_JUMP
                    && (step.getClearance() == 0
                    || (entity.getMovementMode().isWiGE() && (step.getClearance() == 1))
                    || curElevation == curHex.terrainLevel(Terrains.BLDG_ELEV)
                    || curElevation == curHex.terrainLevel(Terrains.BRIDGE_ELEV))) {
                Building bldg = gameManager.game.getBoard().getBuildingAt(curPos);
                if ((bldg != null) && (entity.getElevation() >= 0)) {
                    boolean wigeFlyingOver = entity.getMovementMode() == EntityMovementMode.WIGE
                            && ((curHex.containsTerrain(Terrains.BLDG_ELEV)
                            && curElevation > curHex.terrainLevel(Terrains.BLDG_ELEV)) ||
                            (curHex.containsTerrain(Terrains.BRIDGE_ELEV)
                                    && curElevation > curHex.terrainLevel(Terrains.BRIDGE_ELEV)));
                    boolean collapse = gameManager.checkBuildingCollapseWhileMoving(bldg, entity, curPos);
                    gameManager.environmentalEffectManager.addAffectedBldg(bldg, collapse, gameManager);
                    // If the building is collapsed by a WiGE flying over it, the WiGE drops one level of elevation.
                    // This could invalidate the remainder of the movement path, so we will send it back to the client.
                    if (collapse && wigeFlyingOver) {
                        curElevation--;
                        r = new Report(2378);
                        r.subject = entity.getId();
                        r.addDesc(entity);
                        gameManager.addReport(r);
                        continueTurnFromLevelDrop = true;
                        entity.setPosition(curPos);
                        entity.setFacing(curFacing);
                        entity.setSecondaryFacing(curFacing);
                        entity.setElevation(curElevation);
                        break;
                    }
                }
            }

            // Sheer Cliffs, TO p.39
            boolean vehicleAffectedByCliff = entity instanceof Tank
                    && !entity.isAirborneVTOLorWIGE();
            boolean quadveeVehMode = entity instanceof QuadVee
                    && ((QuadVee) entity).getConversionMode() == QuadVee.CONV_MODE_VEHICLE;
            boolean mechAffectedByCliff = (entity instanceof Mech || entity instanceof Protomech)
                    && moveType != EntityMovementType.MOVE_JUMP
                    && !entity.isAero();
            // Cliffs should only exist towards 1 or 2 level drops, check just to make sure
            // Everything that does not have a 1 or 2 level drop shouldn't be handled as a cliff
            int stepHeight = curElevation + curHex.getLevel()
                    - (lastElevation + prevHex.getLevel());
            boolean isUpCliff = !lastPos.equals(curPos)
                    && curHex.hasCliffTopTowards(prevHex)
                    && (stepHeight == 1 || stepHeight == 2);
            boolean isDownCliff = !lastPos.equals(curPos)
                    && prevHex.hasCliffTopTowards(curHex)
                    && (stepHeight == -1 || stepHeight == -2);

            // Vehicles (exc. WIGE/VTOL) moving down a cliff
            if (vehicleAffectedByCliff && isDownCliff && !isPavementStep) {
                rollTarget = entity.getBasePilotingRoll(stepMoveType);
                rollTarget.append(new PilotingRollData(entity.getId(), 0, "moving down a sheer cliff"));
                if (gameManager.utilityManager.doSkillCheckWhileMoving(entity, lastElevation,
                        lastPos, curPos, rollTarget, false, gameManager) > 0) {
                    gameManager.reportManager.addReport(gameManager.vehicleMotiveDamage((Tank) entity, 0), gameManager);
                    gameManager.addNewLines();
                    turnOver = true;
                    break;
                }
            }

            // Mechs and Protomechs moving down a cliff
            // Quadvees in vee mode ignore PSRs to avoid falls, IO p.133
            if (mechAffectedByCliff && !quadveeVehMode && isDownCliff && !isPavementStep) {
                rollTarget = entity.getBasePilotingRoll(moveType);
                rollTarget.append(new PilotingRollData(entity.getId(), -stepHeight - 1, "moving down a sheer cliff"));
                if (gameManager.utilityManager.doSkillCheckWhileMoving(entity, lastElevation,
                        lastPos, curPos, rollTarget, true, gameManager) > 0) {
                    gameManager.addNewLines();
                    turnOver = true;
                    break;
                }
            }

            // Mechs moving up a cliff
            if (mechAffectedByCliff && !quadveeVehMode && isUpCliff && !isPavementStep) {
                rollTarget = entity.getBasePilotingRoll(moveType);
                rollTarget.append(new PilotingRollData(entity.getId(), stepHeight, "moving up a sheer cliff"));
                if (gameManager.utilityManager.doSkillCheckWhileMoving(entity, lastElevation,
                        lastPos, lastPos, rollTarget, false, gameManager) > 0) {
                    r = new Report(2209);
                    r.addDesc(entity);
                    r.subject = entity.getId();
                    gameManager.addReport(r);
                    gameManager.addNewLines();
                    curPos = entity.getPosition();
                    mpUsed = step.getMpUsed();
                    continueTurnFromCliffAscent = true;
                    break;
                }
            }

            // did the entity just fall?
            if (!wasProne && entity.isProne()) {
                curFacing = entity.getFacing();
                curPos = entity.getPosition();
                mpUsed = step.getMpUsed();
                fellDuringMovement = true;
                break;
            }

            // dropping prone intentionally?
            if (step.getType() == MovePath.MoveStepType.GO_PRONE) {
                mpUsed = step.getMpUsed();
                rollTarget = entity.checkDislodgeSwarmers(step, overallMoveType);
                if (rollTarget.getValue() == TargetRoll.CHECK_FALSE) {
                    // Not being swarmed
                    entity.setProne(true);
                    // check to see if we washed off infernos
                    gameManager.utilityManager.checkForWashedInfernos(entity, curPos, gameManager);
                } else {
                    // Being swarmed
                    entity.setPosition(curPos);
                    if (gameManager.utilityManager.doDislodgeSwarmerSkillCheck(entity, rollTarget, curPos, gameManager)) {
                        // Entity falls
                        curFacing = entity.getFacing();
                        curPos = entity.getPosition();
                        fellDuringMovement = true;
                        break;
                    }
                    // roll failed, go prone but don't dislodge swarmers
                    entity.setProne(true);
                    // check to see if we washed off infernos
                    gameManager.utilityManager.checkForWashedInfernos(entity, curPos, gameManager);
                    break;
                }
            }

            // going hull down
            if (step.getType() == MovePath.MoveStepType.HULL_DOWN) {
                mpUsed = step.getMpUsed();
                entity.setHullDown(true);
            }

            // Check for crushing buildings by Dropships/Mobile Structures
            for (Coords pos : step.getCrushedBuildingLocs()) {
                Building bldg = gameManager.game.getBoard().getBuildingAt(pos);
                Hex hex = gameManager.game.getBoard().getHex(pos);

                r = new Report(3443);
                r.subject = entity.getId();
                r.addDesc(entity);
                r.add(bldg.getName());
                gameManager.vPhaseReport.add(r);

                final int cf = bldg.getCurrentCF(pos);
                final int numFloors = Math.max(0, hex.terrainLevel(Terrains.BLDG_ELEV));
                gameManager.vPhaseReport.addAll(gameManager.damageBuilding(bldg, 150, " is crushed for ", pos));
                int damage = (int) Math.round((cf / 10.0) * numFloors);
                HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                gameManager.vPhaseReport.addAll(gameManager.damageEntity(entity, hit, damage));
            }

            // Track this step's location.
            movePath.addElement(new UnitLocation(entity.getId(), curPos,
                    curFacing, step.getElevation()));

            // if the lastpos is not the same as the current position
            // then add the current position to the list of places passed
            // through
            if (!curPos.equals(lastPos)) {
                passedThrough.add(curPos);
                passedThroughFacing.add(curFacing);
            }

            // update lastPos, prevStep, prevFacing & prevHex
            if (!curPos.equals(lastPos)) {
                prevFacing = curFacing;
            }
            lastPos = curPos;
            lastElevation = curElevation;
            prevStep = step;
            prevHex = curHex;

            firstStep = false;

            // if we moved at all, we are no longer bracing "for free", except for when
            // the current step IS bracing
            if ((mpUsed > 0) && (step.getType() != MovePath.MoveStepType.BRACE)) {
                entity.setBraceLocation(Entity.LOC_NONE);
            }
        }

        // set entity parameters
        entity.setPosition(curPos);
        entity.setFacing(curFacing);
        entity.setSecondaryFacing(curFacing);
        entity.delta_distance = distance;
        entity.moved = moveType;
        entity.mpUsed = mpUsed;
        if (md.isAllUnderwater(gameManager.game)) {
            entity.underwaterRounds++;
            if ((entity instanceof Infantry) && (((Infantry) entity).getMount() != null)
                    && entity.getMovementMode().isSubmarine()
                    && entity.underwaterRounds > ((Infantry) entity).getMount().getUWEndurance()) {
                r = new Report(2412);
                r.addDesc(entity);
                gameManager.addReport(r);
                gameManager.entityActionManager.destroyEntity(entity, "mount drowned", gameManager);
            }
        } else {
            entity.underwaterRounds = 0;
        }
        entity.setClimbMode(curClimbMode);
        if (!sideslipped && !fellDuringMovement && !crashedDuringMovement
                && (entity.getMovementMode() == EntityMovementMode.VTOL)) {
            entity.setElevation(curVTOLElevation);
        }
        entity.setAltitude(curAltitude);
        entity.setClimbMode(curClimbMode);

        // add a list of places passed through
        entity.setPassedThrough(passedThrough);
        entity.setPassedThroughFacing(passedThroughFacing);

        // if we ran with destroyed hip or gyro, we need a psr
        rollTarget = entity.checkRunningWithDamage(overallMoveType);
        if (rollTarget.getValue() != TargetRoll.CHECK_FALSE && entity.canFall()) {
            gameManager.utilityManager.doSkillCheckInPlace(entity, rollTarget, gameManager);
        }

        // if we sprinted with MASC or a supercharger, then we need a PSR
        rollTarget = entity.checkSprintingWithMASCXorSupercharger(overallMoveType,
                entity.mpUsed);
        if (rollTarget.getValue() != TargetRoll.CHECK_FALSE && entity.canFall()) {
            gameManager.utilityManager.doSkillCheckInPlace(entity, rollTarget, gameManager);
        }

        // if we used ProtoMech myomer booster, roll 2d6
        // pilot damage on a 2
        if ((entity instanceof Protomech) && ((Protomech) entity).hasMyomerBooster()
                && (md.getMpUsed() > entity.getRunMP(MPCalculationSetting.NO_MYOMERBOOSTER))) {
            r = new Report(2373);
            r.addDesc(entity);
            r.subject = entity.getId();
            Roll diceRoll = Compute.rollD6(2);
            r.add(diceRoll);

            if (diceRoll.getIntValue() > 2) {
                r.choose(true);
                gameManager.addReport(r);
            } else {
                r.choose(false);
                gameManager.addReport(r);
                gameManager.reportManager.addReport(gameManager.damageCrew(entity, 1), gameManager);
            }
        }

        rollTarget = entity.checkSprintingWithMASCAndSupercharger(overallMoveType, entity.mpUsed);
        if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
            gameManager.utilityManager.doSkillCheckInPlace(entity, rollTarget, gameManager);
        }
        if ((md.getLastStepMovementType() == EntityMovementType.MOVE_SPRINT)
                && (md.hasActiveMASC() || md.hasActiveSupercharger()) && entity.canFall()) {
            gameManager.utilityManager.doSkillCheckInPlace(entity, entity.getBasePilotingRoll(EntityMovementType.MOVE_SPRINT), gameManager);
        }

        if (entity.isAirborne() && entity.isAero()) {

            IAero a = (IAero) entity;
            int thrust = md.getMpUsed();

            // consume fuel
            if (((entity.isAero())
                    && gameManager.game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_FUEL_CONSUMPTION))
                    || (entity instanceof TeleMissile)) {
                int fuelUsed = ((IAero) entity).getFuelUsed(thrust);

                // if we're a gas hog, aerospace fighter and going faster than walking, then use 2x fuel
                if (((overallMoveType == EntityMovementType.MOVE_RUN) ||
                        (overallMoveType == EntityMovementType.MOVE_SPRINT) ||
                        (overallMoveType == EntityMovementType.MOVE_OVER_THRUST)) &&
                        entity.hasQuirk(OptionsConstants.QUIRK_NEG_GAS_HOG)) {
                    fuelUsed *= 2;
                }

                a.useFuel(fuelUsed);
            }

            // JumpShips and space stations need to reduce accumulated thrust if
            // they spend some
            if (entity instanceof Jumpship) {
                Jumpship js = (Jumpship) entity;
                double penalty = 0.0;
                // JumpShips do not accumulate thrust when they make a turn or
                // change velocity
                if (md.contains(MovePath.MoveStepType.TURN_LEFT) || md.contains(MovePath.MoveStepType.TURN_RIGHT)) {
                    // I need to subtract the station keeping thrust from their
                    // accumulated thrust
                    // because they did not actually use it
                    penalty = js.getStationKeepingThrust();
                }
                if (thrust > 0) {
                    penalty = thrust;
                }
                if (penalty > 0.0) {
                    js.setAccumulatedThrust(Math.max(0, js.getAccumulatedThrust() - penalty));
                }
            }

            // check to see if thrust exceeded SI

            rollTarget = a.checkThrustSITotal(thrust, overallMoveType);
            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                gameManager.game.addControlRoll(new PilotingRollData(entity.getId(), 0,
                        "Thrust spent during turn exceeds SI"));
            }

            if (!gameManager.game.getBoard().inSpace()) {
                rollTarget = a.checkVelocityDouble(md.getFinalVelocity(),
                        overallMoveType);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    gameManager.game.addControlRoll(new PilotingRollData(entity.getId(), 0,
                            "Velocity greater than 2x safe thrust"));
                }

                rollTarget = a.checkDown(md.getFinalNDown(), overallMoveType);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    gameManager.game.addControlRoll(
                            new PilotingRollData(entity.getId(), md.getFinalNDown(),
                                    "descended more than two altitudes"));
                }

                // check for hovering
                rollTarget = a.checkHover(md);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    gameManager.game.addControlRoll(
                            new PilotingRollData(entity.getId(), 0, "hovering"));
                }

                // check for aero stall
                rollTarget = a.checkStall(md);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    r = new Report(9391);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    gameManager.addReport(r);
                    gameManager.game.addControlRoll(new PilotingRollData(entity.getId(), 0,
                            "stalled out"));
                    entity.setAltitude(entity.getAltitude() - 1);
                    // check for crash
                    if (gameManager.entityActionManager.checkCrash(entity, entity.getPosition(), entity.getAltitude(), gameManager)) {
                        gameManager.reportManager.addReport(gameManager.entityActionManager.processCrash(entity, 0, entity.getPosition(), gameManager), gameManager);
                    }
                }

                // check to see if spheroids should lose one altitude
                if (a.isSpheroid() && !a.isSpaceborne()
                        && a.isAirborne() && (md.getFinalNDown() == 0) && (md.getMpUsed() == 0)) {
                    r = new Report(9392);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    gameManager.addReport(r);
                    entity.setAltitude(entity.getAltitude() - 1);
                    // check for crash
                    if (gameManager.entityActionManager.checkCrash(entity, entity.getPosition(), entity.getAltitude(), gameManager)) {
                        gameManager.reportManager.addReport(gameManager.entityActionManager.processCrash(entity, 0, entity.getPosition(), gameManager), gameManager);
                    }
                } else if (entity instanceof EscapePods && entity.isAirborne() && md.getFinalVelocity() < 2) {
                    //Atmospheric Escape Pods that drop below velocity 2 lose altitude as dropping units
                    entity.setAltitude(entity.getAltitude()
                            - gameManager.game.getPlanetaryConditions().getDropRate());
                    r = new Report(6676);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    r.add(gameManager.game.getPlanetaryConditions().getDropRate());
                    gameManager.addReport(r);
                }
            }
        }

        // We need to check for the removal of hull-down for tanks.
        // Tanks can just drive out of hull-down: if the tank was hull-down
        // and doesn't end hull-down we can remove the hull-down status
        if (entity.isHullDown() && !md.getFinalHullDown()
                && (entity instanceof Tank
                || (entity instanceof QuadVee && entity.getConversionMode() == QuadVee.CONV_MODE_VEHICLE))) {
            entity.setHullDown(false);
        }

        // If the entity is being swarmed, erratic movement may dislodge the
        // fleas.
        final int swarmerId = entity.getSwarmAttackerId();
        if ((Entity.NONE != swarmerId) && md.contains(MovePath.MoveStepType.SHAKE_OFF_SWARMERS)) {
            final Entity swarmer = gameManager.game.getEntity(swarmerId);
            rollTarget = entity.getBasePilotingRoll(overallMoveType);

            entity.addPilotingModifierForTerrain(rollTarget);

            // Add a +4 modifier.
            if (md.getLastStepMovementType() == EntityMovementType.MOVE_VTOL_RUN) {
                rollTarget.addModifier(2,
                        "dislodge swarming infantry with VTOL movement");
            } else {
                rollTarget.addModifier(4, "dislodge swarming infantry");
            }

            // If the swarmer has Assault claws, give a 1 modifier.
            // We can stop looking when we find our first match.
            for (Mounted mount : swarmer.getMisc()) {
                EquipmentType equip = mount.getType();
                if (equip.hasFlag(MiscType.F_MAGNET_CLAW)) {
                    rollTarget.addModifier(1, "swarmer has magnetic claws");
                    break;
                }
            }

            // okay, print the info
            r = new Report(2125);
            r.subject = entity.getId();
            r.addDesc(entity);
            gameManager.addReport(r);

            // roll
            final Roll diceRoll = Compute.rollD6(2);
            r = new Report(2130);
            r.subject = entity.getId();
            r.add(rollTarget.getValueAsString());
            r.add(rollTarget.getDesc());
            r.add(diceRoll);

            if (diceRoll.getIntValue() < rollTarget.getValue()) {
                r.choose(false);
                gameManager.addReport(r);
            } else {
                // Dislodged swarmers don't get turns.
                gameManager.game.removeTurnFor(swarmer);
                gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));

                // Update the report and the swarmer's status.
                r.choose(true);
                gameManager.addReport(r);
                entity.setSwarmAttackerId(Entity.NONE);
                swarmer.setSwarmTargetId(Entity.NONE);

                Hex curHex = gameManager.game.getBoard().getHex(curPos);

                // Did the infantry fall into water?
                if (curHex.terrainLevel(Terrains.WATER) > 0) {
                    // Swarming infantry die.
                    swarmer.setPosition(curPos);
                    r = new Report(2135);
                    r.subject = entity.getId();
                    r.indent();
                    r.addDesc(swarmer);
                    gameManager.addReport(r);
                    gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity(swarmer, "a watery grave", false, gameManager), gameManager);
                } else {
                    // Swarming infantry take a 3d6 point hit.
                    // ASSUMPTION : damage should not be doubled.
                    r = new Report(2140);
                    r.subject = entity.getId();
                    r.indent();
                    r.addDesc(swarmer);
                    r.add("3d6");
                    gameManager.addReport(r);
                    gameManager.reportManager.addReport(gameManager.damageEntity(swarmer,
                            swarmer.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT),
                            Compute.d6(3)), gameManager);
                    gameManager.addNewLines();
                    swarmer.setPosition(curPos);
                }
                gameManager.entityUpdate(swarmerId);
            } // End successful-PSR

        } // End try-to-dislodge-swarmers

        // but the danger isn't over yet! landing from a jump can be risky!
        if ((overallMoveType == EntityMovementType.MOVE_JUMP) && !entity.isMakingDfa()) {
            final Hex curHex = gameManager.game.getBoard().getHex(curPos);

            // check for damaged criticals
            rollTarget = entity.checkLandingWithDamage(overallMoveType);
            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                gameManager.utilityManager.doSkillCheckInPlace(entity, rollTarget, gameManager);
            }

            // check for prototype JJs
            rollTarget = entity.checkLandingWithPrototypeJJ(overallMoveType);
            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                gameManager.utilityManager.doSkillCheckInPlace(entity, rollTarget, gameManager);
            }

            // check for jumping into heavy woods
            if (gameManager.game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_PSR_JUMP_HEAVY_WOODS)) {
                rollTarget = entity.checkLandingInHeavyWoods(overallMoveType, curHex);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    gameManager.utilityManager.doSkillCheckInPlace(entity, rollTarget, gameManager);
                }
            }

            // Mechanical jump boosters fall damage
            if (md.shouldMechanicalJumpCauseFallDamage()) {
                gameManager.vPhaseReport.addAll(gameManager.utilityManager.doEntityFallsInto(entity,
                        entity.getElevation(), md.getJumpPathHighestPoint(),
                        curPos, entity.getBasePilotingRoll(overallMoveType),
                        false, entity.getJumpMP(), gameManager));
            }

            // jumped into water?
            int waterLevel = curHex.terrainLevel(Terrains.WATER);
            if (curHex.containsTerrain(Terrains.ICE) && (waterLevel > 0)) {
                if (!(entity instanceof Infantry)) {
                    // check for breaking ice
                    Roll diceRoll = Compute.rollD6(1);
                    r = new Report(2122);
                    r.add(entity.getDisplayName(), true);
                    r.add(diceRoll);
                    r.subject = entity.getId();
                    gameManager.addReport(r);

                    if (diceRoll.getIntValue() >= 4) {
                        // oops!
                        entity.setPosition(curPos);
                        gameManager.reportManager.addReport(gameManager.resolveIceBroken(curPos), gameManager);
                        curPos = entity.getPosition();
                    } else {
                        // TacOps: immediate PSR with +4 for terrain. If you
                        // fall then may break the ice after all
                        rollTarget = entity.checkLandingOnIce(overallMoveType, curHex);
                        if (!gameManager.utilityManager.doSkillCheckInPlace(entity, rollTarget, gameManager)) {
                            // apply damage now, or it will show up as a
                            // possible breach, if ice is broken
                            entity.applyDamage();
                            Roll diceRoll2 = Compute.rollD6(1);
                            r = new Report(2118);
                            r.addDesc(entity);
                            r.add(diceRoll2);
                            r.subject = entity.getId();
                            gameManager.addReport(r);

                            if (diceRoll2.getIntValue() == 6) {
                                entity.setPosition(curPos);
                                gameManager.reportManager.addReport(gameManager.resolveIceBroken(curPos), gameManager);
                                curPos = entity.getPosition();
                            }
                        }
                    }
                }
            } else if (!(prevStep.climbMode() && curHex.containsTerrain(Terrains.BRIDGE))
                    && !(entity.getMovementMode().isHoverOrWiGE())) {
                rollTarget = entity.checkWaterMove(waterLevel, overallMoveType);
                if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                    // For falling elevation, Entity must not on hex surface
                    int currElevation = entity.getElevation();
                    entity.setElevation(0);
                    boolean success = gameManager.utilityManager.doSkillCheckInPlace(entity, rollTarget, gameManager);
                    if (success) {
                        entity.setElevation(currElevation);
                    }
                }
                if (waterLevel > 1) {
                    // Any swarming infantry will be destroyed.
                    gameManager.utilityManager.drownSwarmer(entity, curPos, gameManager);
                }
            }

            // check for black ice
            boolean useBlackIce = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVANCED_BLACK_ICE);
            boolean goodTemp = gameManager.game.getPlanetaryConditions().getTemperature() <= PlanetaryConditions.BLACK_ICE_TEMP;
            boolean goodWeather = gameManager.game.getPlanetaryConditions().getWeather() == PlanetaryConditions.WE_ICE_STORM;
            if ((useBlackIce && goodTemp) || goodWeather) {
                if (ServerHelper.checkEnteringBlackIce(gameManager, curPos, curHex, useBlackIce, goodTemp, goodWeather)) {
                    rollTarget = entity.checkLandingOnBlackIce(overallMoveType, curHex);
                    if (!gameManager.utilityManager.doSkillCheckInPlace(entity, rollTarget, gameManager)) {
                        entity.applyDamage();
                    }
                }
            }

            // check for building collapse
            Building bldg = gameManager.game.getBoard().getBuildingAt(curPos);
            if (bldg != null) {
                gameManager.checkForCollapse(bldg, gameManager.game.getPositionMap(), curPos, true,
                        gameManager.vPhaseReport);
            }

            // Don't interact with terrain when jumping onto a building or a bridge
            if (entity.getElevation() == 0) {
                ServerHelper.checkAndApplyMagmaCrust(curHex, entity.getElevation(), entity, curPos, true, gameManager.vPhaseReport, gameManager);
                ServerHelper.checkEnteringMagma(curHex, entity.getElevation(), entity, gameManager);

                // jumped into swamp? maybe stuck!
                if (curHex.getBogDownModifier(entity.getMovementMode(),
                        entity instanceof LargeSupportTank) != TargetRoll.AUTOMATIC_SUCCESS) {
                    if (entity instanceof Mech) {
                        entity.setStuck(true);
                        r = new Report(2121);
                        r.add(entity.getDisplayName(), true);
                        r.subject = entity.getId();
                        gameManager.addReport(r);
                        // check for quicksand
                        gameManager.reportManager.addReport(gameManager.checkQuickSand(curPos), gameManager);
                    } else if (!entity.hasETypeFlag(Entity.ETYPE_INFANTRY)) {
                        rollTarget = new PilotingRollData(entity.getId(),
                                5, "entering boggy terrain");
                        rollTarget.append(new PilotingRollData(entity.getId(),
                                curHex.getBogDownModifier(entity.getMovementMode(),
                                        entity instanceof LargeSupportTank),
                                "avoid bogging down"));
                        if (0 < gameManager.utilityManager.doSkillCheckWhileMoving(entity, entity.getElevation(), curPos, curPos,
                                rollTarget, false, gameManager)) {
                            entity.setStuck(true);
                            r = new Report(2081);
                            r.add(entity.getDisplayName());
                            r.subject = entity.getId();
                            gameManager.addReport(r);
                            // check for quicksand
                            gameManager.reportManager.addReport(gameManager.checkQuickSand(curPos), gameManager);
                        }
                    }
                }
            }

            // If the entity is being swarmed, jumping may dislodge the fleas.
            if (Entity.NONE != swarmerId) {
                final Entity swarmer = gameManager.game.getEntity(swarmerId);
                rollTarget = entity.getBasePilotingRoll(overallMoveType);

                entity.addPilotingModifierForTerrain(rollTarget);

                // Add a +4 modifier.
                rollTarget.addModifier(4, "dislodge swarming infantry");

                // If the swarmer has Assault claws, give a 1 modifier.
                // We can stop looking when we find our first match.
                if (swarmer.hasWorkingMisc(MiscType.F_MAGNET_CLAW, -1)) {
                    rollTarget.addModifier(1, "swarmer has magnetic claws");
                }

                // okay, print the info
                r = new Report(2125);
                r.subject = entity.getId();
                r.addDesc(entity);
                gameManager.addReport(r);

                // roll
                final Roll diceRoll = Compute.rollD6(2);
                r = new Report(2130);
                r.subject = entity.getId();
                r.add(rollTarget.getValueAsString());
                r.add(rollTarget.getDesc());
                r.add(diceRoll);

                if (diceRoll.getIntValue() < rollTarget.getValue()) {
                    r.choose(false);
                    gameManager.addReport(r);
                } else {
                    // Dislodged swarmers don't get turns.
                    gameManager.game.removeTurnFor(swarmer);
                    gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));

                    // Update the report and the swarmer's status.
                    r.choose(true);
                    gameManager.addReport(r);
                    entity.setSwarmAttackerId(Entity.NONE);
                    swarmer.setSwarmTargetId(Entity.NONE);

                    // Did the infantry fall into water?
                    if (curHex.terrainLevel(Terrains.WATER) > 0) {
                        // Swarming infantry die.
                        swarmer.setPosition(curPos);
                        r = new Report(2135);
                        r.subject = entity.getId();
                        r.indent();
                        r.addDesc(swarmer);
                        gameManager.addReport(r);
                        gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity(swarmer, "a watery grave", false, gameManager), gameManager);
                    } else {
                        // Swarming infantry take a 3d6 point hit.
                        // ASSUMPTION : damage should not be doubled.
                        r = new Report(2140);
                        r.subject = entity.getId();
                        r.indent();
                        r.addDesc(swarmer);
                        r.add("3d6");
                        gameManager.addReport(r);
                        gameManager.reportManager.addReport(gameManager.damageEntity(swarmer,
                                swarmer.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT),
                                Compute.d6(3)), gameManager);
                        gameManager.addNewLines();
                        swarmer.setPosition(curPos);
                    }
                    gameManager.entityUpdate(swarmerId);
                } // End successful-PSR

            } // End try-to-dislodge-swarmers

            // one more check for inferno wash-off
            gameManager.utilityManager.checkForWashedInfernos(entity, curPos, gameManager);

            // a jumping tank needs to roll for movement damage
            if (entity instanceof Tank) {
                int modifier = 0;
                if (curHex.containsTerrain(Terrains.ROUGH)
                        || curHex.containsTerrain(Terrains.WOODS)
                        || curHex.containsTerrain(Terrains.JUNGLE)) {
                    modifier = 1;
                }
                r = new Report(2126);
                r.subject = entity.getId();
                r.addDesc(entity);
                gameManager.vPhaseReport.add(r);
                gameManager.vPhaseReport.addAll(gameManager.vehicleMotiveDamage((Tank) entity, modifier,
                        false, -1, true));
                Report.addNewline(gameManager.vPhaseReport);
            }

        } // End entity-is-jumping

        //If converting to another mode, set the final movement mode and report it
        if (entity.isConvertingNow()) {
            r = new Report(1210);
            r.subject = entity.getId();
            r.addDesc(entity);
            if (entity instanceof QuadVee && entity.isProne()
                    && entity.getConversionMode() == QuadVee.CONV_MODE_MECH) {
                //Fall while converting to vehicle mode cancels conversion.
                entity.setConvertingNow(false);
                r.messageId = 2454;
            } else {
                // LAMs converting from fighter mode need to have the elevation set properly.
                if (entity.isAero()) {
                    if (md.getFinalConversionMode() == EntityMovementMode.WIGE
                            && entity.getAltitude() > 0 && entity.getAltitude() <= 3) {
                        entity.setElevation(entity.getAltitude() * 10);
                        entity.setAltitude(0);
                    } else {
                        Hex hex = gameManager.game.getBoard().getHex(entity.getPosition());
                        if (hex.containsTerrain(Terrains.BLDG_ELEV)) {
                            entity.setElevation(hex.terrainLevel(Terrains.BLDG_ELEV));
                        } else {
                            entity.setElevation(0);
                        }
                    }
                }
                entity.setMovementMode(md.getFinalConversionMode());
                if (entity instanceof Mech && ((Mech) entity).hasTracks()) {
                    r.messageId = 2455;
                    r.choose(entity.getMovementMode() == EntityMovementMode.TRACKED);
                } else if (entity.getMovementMode() == EntityMovementMode.TRACKED
                        || entity.getMovementMode() == EntityMovementMode.WHEELED) {
                    r.messageId = 2451;
                } else if (entity.getMovementMode() == EntityMovementMode.WIGE) {
                    r.messageId = 2452;
                } else if (entity.getMovementMode() == EntityMovementMode.AERODYNE) {
                    r.messageId = 2453;
                } else {
                    r.messageId = 2450;
                }
                if (entity.isAero()) {
                    int altitude = entity.getAltitude();
                    if (altitude == 0 && md.getFinalElevation() >= 8) {
                        altitude = 1;
                    }
                    if (altitude == 0) {
                        ((IAero) entity).land();
                    } else {
                        ((IAero) entity).liftOff(altitude);
                    }
                }
            }
            gameManager.addReport(r);
        }

        // update entity's locations' exposure
        gameManager.vPhaseReport.addAll(gameManager.utilityManager.doSetLocationsExposure(entity,
                gameManager.game.getBoard().getHex(curPos), false, entity.getElevation(), gameManager));

        // Check the falls_end_movement option to see if it should be able to
        // move on.
        // Need to check here if the 'Mech actually went from non-prone to prone
        // here because 'fellDuringMovement' is sometimes abused just to force
        // another turn and so doesn't reliably tell us.
        boolean continueTurnFromFall = !(gameManager.game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_FALLS_END_MOVEMENT)
                && (entity instanceof Mech) && !wasProne && entity.isProne())
                && (fellDuringMovement && !entity.isCarefulStand()) // Careful standing takes up the whole turn
                && !turnOver && (entity.mpUsed < entity.getRunMP())
                && (overallMoveType != EntityMovementType.MOVE_JUMP);
        if ((continueTurnFromFall || continueTurnFromPBS || continueTurnFromFishtail || continueTurnFromLevelDrop || continueTurnFromCliffAscent
                || detectedHiddenHazard)
                && entity.isSelectableThisTurn() && !entity.isDoomed()) {
            entity.applyDamage();
            entity.setDone(false);
            entity.setTurnInterrupted(true);

            GameTurn newTurn = new GameTurn.SpecificEntityTurn(entity.getOwner().getId(), entity.getId());
            // Need to set the new turn's multiTurn state
            newTurn.setMultiTurn(true);
            gameManager.game.insertNextTurn(newTurn);
            // brief everybody on the turn update
            gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));

            // let everyone know about what just happened
            if (gameManager.vPhaseReport.size() > 1) {
                gameManager.communicationManager.send(entity.getOwner().getId(), gameManager.packetManager.createSpecialReportPacket(gameManager));
            }
        } else {
            if (entity.getMovementMode() == EntityMovementMode.WIGE) {
                Hex hex = gameManager.game.getBoard().getHex(curPos);
                if (md.automaticWiGELanding(false)) {
                    // try to land safely; LAMs require a psr when landing with gyro or leg actuator
                    // damage and ProtoMechs always require a roll
                    int elevation = (null == prevStep) ? entity.getElevation() : prevStep.getElevation();
                    if (entity.hasETypeFlag(Entity.ETYPE_LAND_AIR_MECH)) {
                        gameManager.reportManager.addReport(gameManager.entityActionManager.landAirMech((LandAirMech) entity, entity.getPosition(), elevation,
                                entity.delta_distance, gameManager), gameManager);
                    } else if (entity.hasETypeFlag(Entity.ETYPE_PROTOMECH)) {
                        gameManager.vPhaseReport.addAll(gameManager.entityActionManager.landGliderPM((Protomech) entity, entity.getPosition(),
                                elevation, entity.delta_distance, gameManager));
                    } else {
                        r = new Report(2123);
                        r.addDesc(entity);
                        r.subject = entity.getId();
                        gameManager.vPhaseReport.add(r);
                    }

                    if (hex.containsTerrain(Terrains.BLDG_ELEV)) {
                        Building bldg = gameManager.game.getBoard().getBuildingAt(entity.getPosition());
                        entity.setElevation(hex.terrainLevel(Terrains.BLDG_ELEV));
                        gameManager.environmentalEffectManager.addAffectedBldg(bldg, gameManager.checkBuildingCollapseWhileMoving(bldg,
                                entity, entity.getPosition()), gameManager);
                    } else if (entity.isLocationProhibited(entity.getPosition(), 0)
                            && !hex.hasPavement()) {
                        // crash
                        r = new Report(2124);
                        r.addDesc(entity);
                        r.subject = entity.getId();
                        gameManager.vPhaseReport.add(r);
                        gameManager.vPhaseReport.addAll(gameManager.entityActionManager.crashVTOLorWiGE((Tank) entity, gameManager));
                    } else {
                        entity.setElevation(0);
                    }

                    // Check for stacking violations in the target hex
                    Entity violation = Compute.stackingViolation(gameManager.game,
                            entity.getId(), entity.getPosition(), entity.climbMode());
                    if (violation != null) {
                        PilotingRollData prd = new PilotingRollData(
                                violation.getId(), 2, "fallen on");
                        if (violation instanceof Dropship) {
                            violation = entity;
                            prd = null;
                        }
                        Coords targetDest = Compute.getValidDisplacement(gameManager.game,
                                violation.getId(), entity.getPosition(), 0);
                        if (targetDest != null) {
                            gameManager.vPhaseReport.addAll(gameManager.utilityManager.doEntityDisplacement(violation,
                                    entity.getPosition(), targetDest, prd, gameManager));
                            // Update the violating entity's position on the
                            // client.
                            gameManager.entityUpdate(violation.getId());
                        } else {
                            // ack! automatic death! Tanks
                            // suffer an ammo/power plant hit.
                            // TODO : a Mech suffers a Head Blown Off crit.
                            gameManager.vPhaseReport.addAll(gameManager.entityActionManager.destroyEntity(violation,
                                    "impossible displacement",
                                    violation instanceof Mech,
                                    violation instanceof Mech, gameManager));
                        }
                    }
                } else if (!entity.hasETypeFlag(Entity.ETYPE_LAND_AIR_MECH)
                        && !entity.hasETypeFlag(Entity.ETYPE_PROTOMECH)) {

                    // we didn't land, so we go to elevation 1 above the terrain
                    // features
                    // it might have been higher than one due to the extra MPs
                    // it can spend to stay higher during movement, but should
                    // end up at one

                    entity.setElevation(Math.min(entity.getElevation(),
                            1 + hex.maxTerrainFeatureElevation(
                                    gameManager.game.getBoard().inAtmosphere())));
                }
            }

            // If we've somehow gotten here as an airborne LAM with a destroyed side torso
            // (such as conversion while dropping), crash now.
            if (entity instanceof LandAirMech
                    && (entity.isLocationBad(Mech.LOC_RT) || entity.isLocationBad(Mech.LOC_LT))) {
                r = new Report(9710);
                r.subject = entity.getId();
                r.addDesc(entity);
                if (entity.isAirborneVTOLorWIGE()) {
                    gameManager.addReport(r);
                    gameManager.entityActionManager.crashAirMech(entity, new PilotingRollData(entity.getId(), TargetRoll.AUTOMATIC_FAIL,
                            "side torso destroyed"), gameManager.vPhaseReport, gameManager);
                } else if (entity.isAirborne() && entity.isAero()) {
                    gameManager.addReport(r);
                    gameManager.reportManager.addReport(gameManager.entityActionManager.processCrash(entity, ((IAero) entity).getCurrentVelocity(), entity.getPosition(), gameManager), gameManager);
                }
            }

            entity.setDone(true);
        }

        if (dropshipStillUnloading) {
            // turns should have already been inserted but we need to set the
            // entity as not done
            entity.setDone(false);
        }

        // If the entity is being swarmed, update the attacker's position.
        if (Entity.NONE != swarmerId) {
            final Entity swarmer = gameManager.game.getEntity(swarmerId);
            swarmer.setPosition(curPos);
            // If the hex is on fire, and the swarming infantry is
            // *not* Battle Armor, it drops off.
            if (!(swarmer instanceof BattleArmor) && gameManager.game.getBoard()
                    .getHex(curPos).containsTerrain(Terrains.FIRE)) {
                swarmer.setSwarmTargetId(Entity.NONE);
                entity.setSwarmAttackerId(Entity.NONE);
                r = new Report(2145);
                r.subject = entity.getId();
                r.indent();
                r.add(swarmer.getShortName(), true);
                gameManager.addReport(r);
            }
            gameManager.entityUpdate(swarmerId);
        }

        // Update the entity's position,
        // unless it is off the game map.
        if (!gameManager.game.isOutOfGame(entity)) {
            gameManager.entityUpdate(entity.getId(), movePath, true, losCache);
            if (entity.isDoomed()) {
                gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(entity.getId(),
                        entity.getRemovalCondition(), gameManager));
            }
        }

        //If the entity is towing trailers, update the position of those trailers
        if (!entity.getAllTowedUnits().isEmpty()) {
            List<Integer> reversedTrailers = new ArrayList<>(entity.getAllTowedUnits()); // initialize with a copy (no need to initialize to an empty list first)
            Collections.reverse(reversedTrailers); // reverse in-place
            List<Coords> trailerPath = gameManager.entityActionManager.initializeTrailerCoordinates(entity, reversedTrailers, gameManager); // no need to initialize to an empty list first
            gameManager.entityActionManager.processTrailerMovement(entity, trailerPath, gameManager);
        }

        // recovered units should now be recovered and dealt with
        if (entity.isAero() && recovered && (loader != null)) {

            if (loader.isCapitalFighter()) {
                if (!(loader instanceof FighterSquadron)) {
                    // this is a solo capital fighter so we need to add a new
                    // squadron and load both the loader and loadee
                    FighterSquadron fs = new FighterSquadron();
                    fs.setDeployed(true);
                    fs.setId(gameManager.game.getNextEntityId());
                    fs.setCurrentVelocity(((Aero) loader).getCurrentVelocity());
                    fs.setNextVelocity(((Aero) loader).getNextVelocity());
                    fs.setVectors(loader.getVectors());
                    fs.setFacing(loader.getFacing());
                    fs.setOwner(entity.getOwner());
                    // set velocity and heading the same as parent entity
                    gameManager.game.addEntity(fs);
                    gameManager.communicationManager.send(gameManager.packetManager.createAddEntityPacket(fs.getId(), gameManager));
                    // make him not get a move this turn
                    fs.setDone(true);
                    // place on board
                    fs.setPosition(loader.getPosition());
                    gameManager.loadUnit(fs, loader, -1);
                    loader = fs;
                    gameManager.entityUpdate(fs.getId());
                }
                loader.load(entity);
            } else {
                loader.recover(entity);
                entity.setRecoveryTurn(5);
            }

            // The loaded unit is being carried by the loader.
            entity.setTransportId(loader.getId());

            // Remove the loaded unit from the screen.
            entity.setPosition(null);

            // Update the loaded unit.
            gameManager.entityUpdate(entity.getId());
        }

        // even if load was unsuccessful, I may need to update the loader
        if (null != loader) {
            gameManager.entityUpdate(loader.getId());
        }

        // if using double blind, update the player on new units he might see
        if (gameManager.doBlind()) {
            gameManager.communicationManager.send(entity.getOwner().getId(), gameManager.packetManager.createFilteredFullEntitiesPacket(entity.getOwner(), losCache, gameManager));
        }

        // if we generated a charge attack, report it now
        if (charge != null) {
            gameManager.communicationManager.send(gameManager.packetManager.createAttackPacket(charge, 1));
        }

        // if we generated a ram attack, report it now
        if (ram != null) {
            gameManager.communicationManager.send(gameManager.packetManager.createAttackPacket(ram, 1));
        }
        if ((entity instanceof Mech) && entity.hasEngine() && ((Mech) entity).isIndustrial()
                && !entity.hasEnvironmentalSealing()
                && (entity.getEngine().getEngineType() == Engine.COMBUSTION_ENGINE)) {
            if ((!entity.isProne()
                    && (gameManager.game.getBoard().getHex(entity.getPosition())
                    .terrainLevel(Terrains.WATER) >= 2))
                    || (entity.isProne()
                    && (gameManager.game.getBoard().getHex(entity.getPosition())
                    .terrainLevel(Terrains.WATER) == 1))) {
                ((Mech) entity).setJustMovedIntoIndustrialKillingWater(true);

            } else {
                ((Mech) entity).setJustMovedIntoIndustrialKillingWater(false);
                ((Mech) entity).setShouldDieAtEndOfTurnBecauseOfWater(false);
            }
        }
    }

    /**
     * Updates the position of any towed trailers.
     *  @param tractor    The Entity that is moving
     * @param trainPath  The path all trailers are following?
     * @param gameManager
     */
    protected void processTrailerMovement(Entity tractor, List<Coords> trainPath, GameManager gameManager) {
        for (int eId : tractor.getAllTowedUnits()) {
            Entity trailer = gameManager.game.getEntity(eId);
            // if the Tractor didn't move anywhere, stay where we are
            if (tractor.delta_distance == 0) {
                trailer.delta_distance = tractor.delta_distance;
                trailer.moved = tractor.moved;
                trailer.setSecondaryFacing(trailer.getFacing());
                trailer.setDone(true);
                gameManager.entityUpdate(eId);
                continue;
            }
            int stepNumber; // The Coords in trainPath that this trailer should move to
            Coords trailerPos;
            int trailerNumber = tractor.getAllTowedUnits().indexOf(eId);
            double trailerPositionOffset = (trailerNumber + 1); //Offset so we get the right position index
            // Unless the tractor is superheavy, put the first trailer in its hex.
            // Technically this would be true for a superheavy trailer too, but only a superheavy tractor can tow one.
            if (trailerNumber == 0 && !tractor.isSuperHeavy()) {
                trailer.setPosition(tractor.getPosition());
                trailer.setFacing(tractor.getFacing());
            } else {
                // If the trailer is superheavy, place it in a hex by itself
                if (trailer.isSuperHeavy()) {
                    trailerPositionOffset ++;
                    stepNumber = (trainPath.size() - (int) trailerPositionOffset);
                    trailerPos = trainPath.get(stepNumber);
                    trailer.setPosition(trailerPos);
                    if ((tractor.getPassedThroughFacing().size() - trailerPositionOffset) >= 0) {
                        trailer.setFacing(tractor.getPassedThroughFacing().get(tractor.getPassedThroughFacing().size() - (int) trailerPositionOffset));
                    }
                } else if (tractor.isSuperHeavy()) {
                    // If the tractor is superheavy, we can put two trailers in each hex
                    // starting trailer 0 in the hex behind the tractor
                    trailerPositionOffset = (Math.ceil((trailerPositionOffset / 2.0)) + 1);
                    stepNumber = (trainPath.size() - (int) trailerPositionOffset);
                    trailerPos = trainPath.get(stepNumber);
                    trailer.setPosition(trailerPos);
                    if ((tractor.getPassedThroughFacing().size() - trailerPositionOffset) >= 0) {
                        trailer.setFacing(tractor.getPassedThroughFacing().get(tractor.getPassedThroughFacing().size() - (int) trailerPositionOffset));
                    }
                } else {
                    // Otherwise, we can put two trailers in each hex
                    // starting trailer 1 in the hex behind the tractor
                    trailerPositionOffset ++;
                    trailerPositionOffset = Math.ceil((trailerPositionOffset / 2.0));
                    stepNumber = (trainPath.size() - (int) trailerPositionOffset);
                    trailerPos = trainPath.get(stepNumber);
                    trailer.setPosition(trailerPos);
                    if ((tractor.getPassedThroughFacing().size() - trailerPositionOffset) >= 0) {
                        trailer.setFacing(tractor.getPassedThroughFacing().get(tractor.getPassedThroughFacing().size() - (int) trailerPositionOffset));
                    }
                }
            }
            // trailers are immobile by default. Match the tractor's movement here
            trailer.delta_distance = tractor.delta_distance;
            trailer.moved = tractor.moved;
            trailer.setSecondaryFacing(trailer.getFacing());
            trailer.setDone(true);
            gameManager.entityUpdate(eId);
        }
    }

    /**
     * Flips the order of a tractor's towed trailers list by index and
     * adds their starting coordinates to a list of hexes the tractor passed through
     *
     * @return  Returns the properly sorted list of all train coordinates
     * @param tractor
     * @param allTowedTrailers
     * @param gameManager
     */
    public List<Coords> initializeTrailerCoordinates(Entity tractor, List<Integer> allTowedTrailers, GameManager gameManager) {
        List<Coords> trainCoords = new ArrayList<>();
        for (int trId : allTowedTrailers) {
            Entity trailer = gameManager.game.getEntity(trId);
            Coords position = trailer.getPosition();
            //Duplicates foul up the works...
            if (!trainCoords.contains(position)) {
                trainCoords.add(position);
            }
        }
        for (Coords c : tractor.getPassedThrough()) {
            if (!trainCoords.contains(c)) {
                trainCoords.add(c);
            }
        }
        return trainCoords;
    }

    /**
     * process deployment of minefields
     *
     * @param minefields
     * @param gameManager
     */
    protected void processDeployMinefields(Vector<Minefield> minefields, GameManager gameManager) {
        int playerId = Player.PLAYER_NONE;
        for (int i = 0; i < minefields.size(); i++) {
            Minefield mf = minefields.elementAt(i);
            playerId = mf.getPlayerId();

            gameManager.game.addMinefield(mf);
            if (mf.getType() == Minefield.TYPE_VIBRABOMB) {
                gameManager.game.addVibrabomb(mf);
            }
        }

        Player player = gameManager.game.getPlayer(playerId);
        if (null != player) {
            int teamId = player.getTeam();

            if (teamId != Player.TEAM_NONE) {
                for (Team team : gameManager.game.getTeams()) {
                    if (team.getId() == teamId) {
                        for (Player teamPlayer : team.players()) {
                            if (teamPlayer.getId() != player.getId()) {
                                gameManager.communicationManager.send(teamPlayer.getId(), new Packet(PacketCommand.DEPLOY_MINEFIELDS,
                                        minefields));
                            }
                            teamPlayer.addMinefields(minefields);
                        }
                        break;
                    }
                }
            } else {
                player.addMinefields(minefields);
            }
        }
    }

    /**
     * Handles a pointblank shot for hidden units, which must request feedback
     * from the client of the player who owns the hidden unit.
     * @return Returns true if a point-blank shot was taken, otherwise false
     * @param hidden
     * @param target
     * @param gameManager
     */
    protected boolean processPointblankShotCFR(Entity hidden, Entity target, GameManager gameManager) {
        gameManager.communicationManager.sendPointBlankShotCFR(hidden, target, gameManager);
        boolean firstPacket = true;
        // Keep processing until we get a response
        while (true) {
            synchronized (gameManager.cfrPacketQueue) {
                try {
                    while (gameManager.cfrPacketQueue.isEmpty()) {
                        gameManager.cfrPacketQueue.wait();
                    }
                } catch (InterruptedException e) {
                    return false;
                }
                // Get the packet, if there's something to get
                Server.ReceivedPacket rp;
                if (!gameManager.cfrPacketQueue.isEmpty()) {
                    rp = gameManager.cfrPacketQueue.poll();
                    final PacketCommand cfrType = (PacketCommand) rp.getPacket().getObject(0);
                    // Make sure we got the right type of response
                    if (!cfrType.isCFRHiddenPBS()) {
                        LogManager.getLogger().error("Expected a CFR_HIDDEN_PBS CFR packet, received: " + cfrType);
                        continue;
                    }
                    // Check packet came from right ID
                    if (rp.getConnectionId() != hidden.getOwnerId()) {
                        LogManager.getLogger().error(String.format(
                                "Expected a CFR_HIDDEN_PBS CFR packet from player %d, but instead it came from player %d",
                                hidden.getOwnerId(), rp.getConnectionId()));
                        continue;
                    }
                } else {
                    // If no packets, wait again
                    continue;
                }
                // First packet indicates whether the PBS is taken or declined
                if (firstPacket) {
                    // Check to see if the client declined the PBS
                    if (rp.getPacket().getObject(1) == null) {
                        return false;
                    } else {
                        firstPacket = false;
                        // Notify other clients, so they can display a message
                        for (Player p : gameManager.game.getPlayersVector()) {
                            if (p.getId() == hidden.getOwnerId()) {
                                continue;
                            }
                            gameManager.communicationManager.send(p.getId(), new Packet(PacketCommand.CLIENT_FEEDBACK_REQUEST,
                                    PacketCommand.CFR_HIDDEN_PBS, Entity.NONE, Entity.NONE));
                        }
                        // Update all clients with the position of the PBS
                        gameManager.entityUpdate(target.getId());
                        continue;
                    }
                }

                // The second packet contains the attacks to process
                Vector<EntityAction> attacks = (Vector<EntityAction>) rp.getPacket().getObject(1);
                // Mark the hidden unit as having taken a PBS
                hidden.setMadePointblankShot(true);
                // Process the Actions
                for (EntityAction ea : attacks) {
                    Entity entity = gameManager.game.getEntity(ea.getEntityId());
                    if (ea instanceof TorsoTwistAction) {
                        TorsoTwistAction tta = (TorsoTwistAction) ea;
                        if (entity.canChangeSecondaryFacing()) {
                            entity.setSecondaryFacing(tta.getFacing());
                        }
                    } else if (ea instanceof FlipArmsAction) {
                        FlipArmsAction faa = (FlipArmsAction) ea;
                        entity.setArmsFlipped(faa.getIsFlipped());
                    } else if (ea instanceof SearchlightAttackAction) {
                        boolean hexesAdded = ((SearchlightAttackAction) ea).setHexesIlluminated(gameManager.game);
                        // If we added new hexes, send them to all players.
                        // These are spotlights at night, you know they're there.
                        if (hexesAdded) {
                            gameManager.communicationManager.send(gameManager.packetManager.createIlluminatedHexesPacket(gameManager));
                        }
                        SearchlightAttackAction saa = (SearchlightAttackAction) ea;
                        gameManager.reportManager.addReport(saa.resolveAction(gameManager.game), gameManager);
                    } else if (ea instanceof WeaponAttackAction) {
                        WeaponAttackAction waa = (WeaponAttackAction) ea;
                        Entity ae = gameManager.game.getEntity(waa.getEntityId());
                        Mounted m = ae.getEquipment(waa.getWeaponId());
                        Weapon w = (Weapon) m.getType();
                        // Track attacks original target, for things like swarm LRMs
                        waa.setOriginalTargetId(waa.getTargetId());
                        waa.setOriginalTargetType(waa.getTargetType());
                        AttackHandler ah = w.fire(waa, gameManager.game, gameManager);
                        if (ah != null) {
                            ah.setStrafing(waa.isStrafing());
                            ah.setStrafingFirstShot(waa.isStrafingFirstShot());
                            gameManager.game.addAttack(ah);
                        }
                    }
                }
                // Now handle the attacks
                // Set to the firing phase, so the attacks handle
                GamePhase currentPhase = gameManager.game.getPhase();
                gameManager.game.setPhase(GamePhase.FIRING);
                // Handle attacks
                gameManager.handleAttacks(true);
                // Restore Phase
                gameManager.game.setPhase(currentPhase);
                return true;
            }
        }
    }

    public int processTeleguidedMissileCFR(int playerId, List<Integer> targetIds,
                                           List<Integer> toHitValues, GameManager gameManager) {
        gameManager.communicationManager.sendTeleguidedMissileCFR(playerId, targetIds, toHitValues, gameManager);
        while (true) {
            synchronized (gameManager.cfrPacketQueue) {
                try {
                    while (gameManager.cfrPacketQueue.isEmpty()) {
                        gameManager.cfrPacketQueue.wait();
                    }
                } catch (InterruptedException e) {
                    return 0;
                }

                // Get the packet, if there's something to get
                Server.ReceivedPacket rp = gameManager.cfrPacketQueue.poll();
                final PacketCommand cfrType = (PacketCommand) rp.getPacket().getObject(0);
                // Make sure we got the right type of response
                if (!cfrType.isCFRTeleguidedTarget()) {
                    LogManager.getLogger().error("Expected a CFR_TELEGUIDED_TARGET CFR packet, received: " + cfrType);
                    continue;
                }
                // Check packet came from right ID
                if (rp.getConnectionId() != playerId) {
                    LogManager.getLogger().error(String.format(
                            "Expected a CFR_TELEGUIDED_TARGET CFR packet from player %d, but instead it came from player %d",
                            playerId, rp.getConnectionId()));
                    continue;
                }
                return (int) rp.getPacket().getData()[1];
            }
        }
    }

    public int processTAGTargetCFR(int playerId, List<Integer> targetIds, List<Integer> targetTypes, GameManager gameManager) {
        gameManager.communicationManager.sendTAGTargetCFR(playerId, targetIds, targetTypes, gameManager);
        while (true) {
            synchronized (gameManager.cfrPacketQueue) {
                try {
                    while (gameManager.cfrPacketQueue.isEmpty()) {
                        gameManager.cfrPacketQueue.wait();
                    }
                } catch (InterruptedException e) {
                    return 0;
                }
                // Get the packet, if there's something to get
                Server.ReceivedPacket rp = gameManager.cfrPacketQueue.poll();
                final PacketCommand cfrType = (PacketCommand) rp.getPacket().getObject(0);
                // Make sure we got the right type of response
                if (!cfrType.isCFRTagTarget()) {
                    LogManager.getLogger().error("Expected a CFR_TAG_TARGET CFR packet, received: " + cfrType);
                    continue;
                }
                // Check packet came from right ID
                if (rp.getConnectionId() != playerId) {
                    LogManager.getLogger().error(String.format(
                            "Expected a CFR_TAG_TARGET CFR packet from player %d but instead it came from player %d",
                            playerId, rp.getConnectionId()));
                    continue;
                }
                return (int) rp.getPacket().getData()[1];
            }
        }
    }

    /**
     * makes a unit skid or sideslip on the board
     *
     * @param entity    the unit which should skid
     * @param start     the coordinates of the hex the unit was in prior to skidding
     * @param elevation the elevation of the unit
     * @param direction the direction of the skid
     * @param distance  the number of hexes skidded
     * @param step      the MoveStep which caused the skid
     * @param moveType
     * @param flip      whether the skid resulted from a failure maneuver result of major skid
     * @param gameManager
     * @return true if the entity was removed from play
     */
    protected boolean processSkid(Entity entity, Coords start, int elevation,
                                  int direction, int distance, MoveStep step,
                                  EntityMovementType moveType, boolean flip, GameManager gameManager) {
        Coords nextPos = start;
        Coords curPos = nextPos;
        Hex curHex = gameManager.game.getBoard().getHex(start);
        Report r;
        int skidDistance = 0; // actual distance moved
        // Flipping vehicles take tonnage/10 points of damage for every hex they enter.
        int flipDamage = (int) Math.ceil(entity.getWeight() / 10.0);
        while (!entity.isDoomed() && (distance > 0)) {
            nextPos = curPos.translated(direction);
            // Is the next hex off the board?
            if (!gameManager.game.getBoard().contains(nextPos)) {

                // Can the entity skid off the map?
                if (gameManager.game.getOptions().booleanOption(OptionsConstants.BASE_PUSH_OFF_BOARD)) {
                    // Yup. One dead entity.
                    gameManager.game.removeEntity(entity.getId(), IEntityRemovalConditions.REMOVE_PUSHED);
                    gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(entity.getId(), IEntityRemovalConditions.REMOVE_PUSHED, gameManager));
                    r = new Report(2030, Report.PUBLIC);
                    r.addDesc(entity);
                    gameManager.addReport(r);

                    for (Entity e : entity.getLoadedUnits()) {
                        gameManager.game.removeEntity(e.getId(), IEntityRemovalConditions.REMOVE_PUSHED);
                        gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(e.getId(), IEntityRemovalConditions.REMOVE_PUSHED, gameManager));
                    }
                    Entity swarmer = gameManager.game.getEntity(entity.getSwarmAttackerId());
                    if (swarmer != null) {
                        if (!swarmer.isDone()) {
                            gameManager.game.removeTurnFor(swarmer);
                            swarmer.setDone(true);
                            gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));
                        }
                        gameManager.game.removeEntity(swarmer.getId(), IEntityRemovalConditions.REMOVE_PUSHED);
                        gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(swarmer.getId(), IEntityRemovalConditions.REMOVE_PUSHED, gameManager));
                    }
                    // The entity's movement is completed.
                    return true;

                }
                // Nope. Update the report.
                r = new Report(2035);
                r.subject = entity.getId();
                r.indent();
                gameManager.addReport(r);
                // Stay in the current hex and stop skidding.
                break;
            }

            Hex nextHex = gameManager.game.getBoard().getHex(nextPos);
            distance -= nextHex.movementCost(entity) + 1;
            // By default, the unit is going to fall to the floor of the next
            // hex
            int curAltitude = elevation + curHex.getLevel();
            int nextAltitude = nextHex.floor();

            // but VTOL keep altitude
            if (entity.getMovementMode() == EntityMovementMode.VTOL) {
                nextAltitude = Math.max(nextAltitude, curAltitude);
            } else {
                // Is there a building to "catch" the unit?
                if (nextHex.containsTerrain(Terrains.BLDG_ELEV)) {
                    // unit will land on the roof, if at a higher level,
                    // otherwise it will skid through the wall onto the same
                    // floor.
                    // don't change this if the building starts at an elevation
                    // higher than the unit
                    // (e.g. the building is on a hill). Otherwise, we skid into
                    // solid earth.
                    if (curAltitude >= nextHex.floor()) {
                        nextAltitude = Math.min(curAltitude,
                                nextHex.getLevel() + nextHex.terrainLevel(Terrains.BLDG_ELEV));
                    }
                }
                // Is there a bridge to "catch" the unit?
                if (nextHex.containsTerrain(Terrains.BRIDGE)) {
                    // unit will land on the bridge, if at a higher level,
                    // and the bridge exits towards the current hex,
                    // otherwise the bridge has no effect
                    int exitDir = (direction + 3) % 6;
                    exitDir = 1 << exitDir;
                    if ((nextHex.getTerrain(Terrains.BRIDGE).getExits() & exitDir) == exitDir) {
                        nextAltitude = Math.min(curAltitude,
                                Math.max(nextAltitude,
                                        nextHex.getLevel() + nextHex.terrainLevel(Terrains.BRIDGE_ELEV)));
                    }
                }
                if ((nextAltitude <= nextHex.getLevel())
                        && (curAltitude >= curHex.getLevel())) {
                    // Hovercraft and WiGEs can "skid" over water.
                    // all units can skid over ice.
                    if ((entity.getMovementMode().equals(EntityMovementMode.HOVER)
                            || entity.getMovementMode().equals(EntityMovementMode.WIGE))
                            && nextHex.containsTerrain(Terrains.WATER)) {
                        nextAltitude = nextHex.getLevel();
                    } else {
                        if (nextHex.containsTerrain(Terrains.ICE)) {
                            nextAltitude = nextHex.getLevel();
                        }
                    }
                }
                if (entity.getMovementMode() == EntityMovementMode.WIGE
                        && elevation > 0 && nextAltitude < curAltitude) {
                    // Airborne WiGEs drop to one level above the surface
                    if (entity.climbMode()) {
                        nextAltitude = curAltitude;
                    } else {
                        nextAltitude++;
                    }
                }
            }

            // The elevation the skidding unit will occupy in next hex
            int nextElevation = nextAltitude - nextHex.getLevel();

            boolean crashedIntoTerrain = curAltitude < nextAltitude;
            if (entity.getMovementMode() == EntityMovementMode.VTOL
                    && (nextHex.containsTerrain(Terrains.WOODS)
                    || nextHex.containsTerrain(Terrains.JUNGLE))
                    && nextElevation <= nextHex.terrainLevel(Terrains.FOLIAGE_ELEV)) {
                crashedIntoTerrain = true;

            }

            if (nextHex.containsTerrain(Terrains.BLDG_ELEV)) {
                Building bldg = gameManager.game.getBoard().getBuildingAt(nextPos);

                if (bldg.getType() == Building.WALL) {
                    crashedIntoTerrain = true;
                }

                if (bldg.getBldgClass() == Building.GUN_EMPLACEMENT) {
                    crashedIntoTerrain = true;
                }
            }

            // however WiGE can gain 1 level to avoid crashing into the terrain.
            if (entity.getMovementMode() == EntityMovementMode.WIGE && (elevation > 0)) {
                if (curAltitude == nextHex.floor()) {
                    nextElevation = 1;
                    crashedIntoTerrain = false;
                } else if ((entity instanceof LandAirMech) && (curAltitude + 1 == nextHex.floor())) {
                    // LAMs in AirMech mode skid across terrain that is two levels higher rather than crashing,
                    // Reset the skid distance for skid damage calculations.
                    nextElevation = 0;
                    skidDistance = 0;
                    crashedIntoTerrain = false;
                    r = new Report(2102);
                    r.subject = entity.getId();
                    r.indent();
                    gameManager.addReport(r);
                }
            }

            Entity crashDropShip = null;
            for (Entity en : gameManager.game.getEntitiesVector(nextPos)) {
                if ((en instanceof Dropship) && !en.isAirborne()
                        && (nextAltitude <= (en.relHeight()))) {
                    crashDropShip = en;
                }
            }

            if (crashedIntoTerrain) {
                if (nextHex.containsTerrain(Terrains.BLDG_ELEV)) {
                    Building bldg = gameManager.game.getBoard().getBuildingAt(nextPos);

                    // If you crash into a wall you want to stop in the hex
                    // before the wall not in the wall
                    // Like a building.
                    if (bldg.getType() == Building.WALL) {
                        r = new Report(2047);
                    } else if (bldg.getBldgClass() == Building.GUN_EMPLACEMENT) {
                        r = new Report(2049);
                    } else {
                        r = new Report(2045);
                    }

                } else {
                    r = new Report(2045);
                }

                r.subject = entity.getId();
                r.indent();
                r.add(nextPos.getBoardNum(), true);
                gameManager.addReport(r);

                if ((entity.getMovementMode() == EntityMovementMode.WIGE)
                        || (entity.getMovementMode() == EntityMovementMode.VTOL)) {
                    int hitSide = (step.getFacing() - direction) + 6;
                    hitSide %= 6;
                    int table = 0;
                    switch (hitSide) { // quite hackish... I think it ought to work, though.
                        case 0: // can this happen?
                            table = ToHitData.SIDE_FRONT;
                            break;
                        case 1:
                        case 2:
                            table = ToHitData.SIDE_LEFT;
                            break;
                        case 3:
                            table = ToHitData.SIDE_REAR;
                            break;
                        case 4:
                        case 5:
                            table = ToHitData.SIDE_RIGHT;
                            break;
                    }
                    elevation = nextElevation;
                    if (entity instanceof Tank) {
                        gameManager.reportManager.addReport(gameManager.entityActionManager.crashVTOLorWiGE((Tank) entity, false, true,
                                distance, curPos, elevation, table, gameManager), gameManager);
                    }

                    if ((nextHex.containsTerrain(Terrains.WATER) && !nextHex
                            .containsTerrain(Terrains.ICE))
                            || nextHex.containsTerrain(Terrains.WOODS)
                            || nextHex.containsTerrain(Terrains.JUNGLE)) {
                        gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity(entity, "could not land in crash site", gameManager), gameManager);
                    } else if (elevation < nextHex.terrainLevel(Terrains.BLDG_ELEV)) {
                        Building bldg = gameManager.game.getBoard().getBuildingAt(nextPos);

                        // If you crash into a wall you want to stop in the hex
                        // before the wall not in the wall
                        // Like a building.
                        if (bldg.getType() == Building.WALL) {
                            gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity(entity, "crashed into a wall", gameManager), gameManager);
                            break;
                        }
                        if (bldg.getBldgClass() == Building.GUN_EMPLACEMENT) {
                            gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity(entity, "crashed into a gun emplacement", gameManager), gameManager);
                            break;
                        }

                        gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity(entity, "crashed into building", gameManager), gameManager);
                    } else {
                        entity.setPosition(nextPos);
                        entity.setElevation(0);
                        gameManager.reportManager.addReport(gameManager.utilityManager.doEntityDisplacementMinefieldCheck(entity,
                                curPos, nextPos, nextElevation, gameManager), gameManager);
                    }
                    break;

                }
                // skidding into higher terrain does weight/20
                // damage in 5pt clusters to front.
                int damage = ((int) entity.getWeight() + 19) / 20;
                while (damage > 0) {
                    int table = ToHitData.HIT_NORMAL;
                    int side = entity.sideTable(nextPos);
                    if (entity instanceof Protomech) {
                        table = ToHitData.HIT_SPECIAL_PROTO;
                    }
                    HitData hitData = entity.rollHitLocation(table, side);
                    hitData.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                    gameManager.reportManager.addReport(gameManager.damageEntity(entity, hitData, Math.min(5, damage)), gameManager);
                    damage -= 5;
                }
                // Stay in the current hex and stop skidding.
                break;
            }

            // did we hit a DropShip. Oww!
            // Taharqa: The rules on how to handle this are completely missing, so I am assuming
            // we assign damage as per an accidental charge, but do not displace
            // the DropShip and end the skid
            else if (null != crashDropShip) {
                r = new Report(2050);
                r.subject = entity.getId();
                r.indent();
                r.add(crashDropShip.getShortName(), true);
                r.add(nextPos.getBoardNum(), true);
                gameManager.addReport(r);
                ChargeAttackAction caa = new ChargeAttackAction(entity.getId(),
                        crashDropShip.getTargetType(),
                        crashDropShip.getId(),
                        crashDropShip.getPosition());
                ToHitData toHit = caa.toHit(gameManager.game, true);
                gameManager.combatManager.resolveChargeDamage(entity, crashDropShip, toHit, direction, gameManager);
                if ((entity.getMovementMode() == EntityMovementMode.WIGE)
                        || (entity.getMovementMode() == EntityMovementMode.VTOL)) {
                    int hitSide = (step.getFacing() - direction) + 6;
                    hitSide %= 6;
                    int table = 0;
                    switch (hitSide) { // quite hackish... I think it ought to work, though.
                        case 0: // can this happen?
                            table = ToHitData.SIDE_FRONT;
                            break;
                        case 1:
                        case 2:
                            table = ToHitData.SIDE_LEFT;
                            break;
                        case 3:
                            table = ToHitData.SIDE_REAR;
                            break;
                        case 4:
                        case 5:
                            table = ToHitData.SIDE_RIGHT;
                            break;
                    }
                    elevation = nextElevation;
                    gameManager.reportManager.addReport(gameManager.entityActionManager.crashVTOLorWiGE((VTOL) entity, false, true,
                            distance, curPos, elevation, table, gameManager), gameManager);
                    break;
                }
                if (!crashDropShip.isDoomed() && !crashDropShip.isDestroyed()
                        && !gameManager.game.isOutOfGame(crashDropShip)) {
                    break;
                }
            }

            // Have skidding units suffer falls (off a cliff).
            else if ( (curAltitude > (nextAltitude + entity.getMaxElevationChange())
                    || (curHex.hasCliffTopTowards(nextHex) && curAltitude > nextAltitude) )
                    && !(entity.getMovementMode() == EntityMovementMode.WIGE && elevation > curHex.ceiling())) {
                gameManager.reportManager.addReport(gameManager.utilityManager.doEntityFallsInto(entity, entity.getElevation(), curPos, nextPos,
                        entity.getBasePilotingRoll(moveType), true, gameManager), gameManager);
                gameManager.reportManager.addReport(gameManager.utilityManager.doEntityDisplacementMinefieldCheck(entity, curPos, nextPos, nextElevation, gameManager), gameManager);
                // Stay in the current hex and stop skidding.
                break;
            }

            // Get any building in the hex.
            Building bldg = null;
            if (nextElevation < nextHex.terrainLevel(Terrains.BLDG_ELEV)) {
                // We will only run into the building if its at a higher level,
                // otherwise we skid over the roof
                bldg = gameManager.game.getBoard().getBuildingAt(nextPos);
            }
            boolean bldgSuffered = false;
            boolean stopTheSkid = false;
            // Does the next hex contain an entities?
            // ASSUMPTION: hurt EVERYONE in the hex.
            Iterator<Entity> targets = gameManager.game.getEntities(nextPos);
            if (targets.hasNext()) {
                List<Entity> avoidedChargeUnits = new ArrayList<>();
                boolean skidChargeHit = false;
                while (targets.hasNext()) {
                    Entity target = targets.next();

                    if ((target.getElevation() > (nextElevation + entity.getHeight()))
                            || (target.relHeight() < nextElevation)) {
                        // target is not in the way
                        continue;
                    }

                    // Can the target avoid the skid?
                    if (!target.isDone()) {
                        if (target instanceof Infantry) {
                            r = new Report(2420);
                            r.subject = target.getId();
                            r.addDesc(target);
                            gameManager.addReport(r);
                            continue;
                        } else if (target instanceof Protomech) {
                            if (target != Compute.stackingViolation(gameManager.game, entity, nextPos, null, entity.climbMode())) {
                                r = new Report(2420);
                                r.subject = target.getId();
                                r.addDesc(target);
                                gameManager.addReport(r);
                                continue;
                            }
                        } else {
                            PilotingRollData psr = target.getBasePilotingRoll();
                            psr.addModifier(0, "avoiding collision");
                            if (psr.getValue() == TargetRoll.AUTOMATIC_FAIL
                                    || psr.getValue() == TargetRoll.IMPOSSIBLE) {
                                r = new Report(2426);
                                r.subject = target.getId();
                                r.addDesc(target);
                                r.add(psr.getDesc());
                                gameManager.addReport(r);
                            } else {
                                Roll diceRoll = Compute.rollD6(2);
                                r = new Report(2425);
                                r.subject = target.getId();
                                r.addDesc(target);
                                r.add(psr);
                                r.add(psr.getDesc());
                                r.add(diceRoll);
                                gameManager.addReport(r);

                                if (diceRoll.getIntValue() >= psr.getValue()) {
                                    gameManager.game.removeTurnFor(target);
                                    avoidedChargeUnits.add(target);
                                    continue;
                                    // TODO : the charge should really be suspended
                                    // and resumed after the target moved.
                                }
                            }
                        }
                    }

                    // Mechs and vehicles get charged,
                    // but need to make a to-hit roll
                    if ((target instanceof Mech) || (target instanceof Tank)
                            || (target instanceof Aero)) {
                        ChargeAttackAction caa = new ChargeAttackAction(
                                entity.getId(), target.getTargetType(),
                                target.getId(), target.getPosition());
                        ToHitData toHit = caa.toHit(gameManager.game, true);

                        // roll
                        Roll diceRoll = Compute.rollD6(2);
                        int rollValue = diceRoll.getIntValue();
                        // Update report.
                        r = new Report(2050);
                        r.subject = entity.getId();
                        r.indent();
                        r.add(target.getShortName(), true);
                        r.add(nextPos.getBoardNum(), true);
                        r.newlines = 0;
                        gameManager.addReport(r);

                        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
                            rollValue = -12;
                            r = new Report(2055);
                            r.subject = entity.getId();
                            r.add(toHit.getDesc());
                            r.newlines = 0;
                            gameManager.addReport(r);
                        } else if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
                            r = new Report(2060);
                            r.subject = entity.getId();
                            r.add(toHit.getDesc());
                            r.newlines = 0;
                            gameManager.addReport(r);
                            rollValue = Integer.MAX_VALUE;
                        } else {
                            // report the roll
                            r = new Report(2065);
                            r.subject = entity.getId();
                            r.add(toHit);
                            r.add(diceRoll);
                            r.newlines = 0;
                            gameManager.addReport(r);
                        }

                        // Resolve a charge against the target.
                        // ASSUMPTION: buildings block damage for
                        // *EACH* entity charged.
                        if (rollValue < toHit.getValue()) {
                            r = new Report(2070);
                            r.subject = entity.getId();
                            gameManager.addReport(r);
                        } else {
                            // Resolve the charge.
                            gameManager.combatManager.resolveChargeDamage(entity, target, toHit, direction, gameManager);
                            // HACK: set the entity's location
                            // to the original hex again, for the other targets
                            if (targets.hasNext()) {
                                entity.setPosition(curPos);
                            }
                            bldgSuffered = true;
                            skidChargeHit = true;
                            // The skid ends here if the target lives.
                            if (!target.isDoomed() && !target.isDestroyed()
                                    && !gameManager.game.isOutOfGame(target)) {
                                stopTheSkid = true;
                            }
                        }

                        // if we don't do this here,
                        // we can have a mech without a leg
                        // standing on the field and moving
                        // as if it still had his leg after
                        // getting skid-charged.
                        if (!target.isDone()) {
                            gameManager.reportManager.addReport(gameManager.resolvePilotingRolls(target), gameManager);
                            gameManager.game.resetPSRs(target);
                            target.applyDamage();
                            gameManager.addNewLines();
                        }

                    }

                    // Resolve "move-through" damage on infantry.
                    // Infantry inside of a building don't get a
                    // move-through, but suffer "bleed through"
                    // from the building.
                    else if ((target instanceof Infantry) && (bldg != null)) {
                        // Update report.
                        r = new Report(2075);
                        r.subject = entity.getId();
                        r.indent();
                        r.add(target.getShortName(), true);
                        r.add(nextPos.getBoardNum(), true);
                        r.newlines = 0;
                        gameManager.addReport(r);

                        // Infantry don't have different
                        // tables for punches and kicks
                        HitData hit = target.rollHitLocation(ToHitData.HIT_NORMAL,
                                Compute.targetSideTable(entity, target));
                        hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                        // Damage equals tonnage, divided by 5.
                        // ASSUMPTION: damage is applied in one hit.
                        gameManager.reportManager.addReport(gameManager.damageEntity(target, hit, (int) Math.round(entity.getWeight() / 5)), gameManager);
                        gameManager.addNewLines();
                    }

                    // Has the target been destroyed?
                    if (target.isDoomed()) {
                        // Has the target taken a turn?
                        if (!target.isDone()) {
                            // Dead entities don't take turns.
                            gameManager.game.removeTurnFor(target);
                            gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));
                        } // End target-still-to-move

                        // Clean out the entity.
                        target.setDestroyed(true);
                        gameManager.game.moveToGraveyard(target.getId());
                        gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(target.getId(), gameManager));
                    }
                    // Update the target's position,
                    // unless it is off the game map.
                    if (!gameManager.game.isOutOfGame(target)) {
                        gameManager.entityUpdate(target.getId());
                    }
                } // Check the next entity in the hex.

                if (skidChargeHit) {
                    // HACK: set the entities position to that
                    // hex's coords, because we had to move the entity
                    // back earlier for the other targets
                    entity.setPosition(nextPos);
                }
                for (Entity e : avoidedChargeUnits) {
                    GameTurn newTurn = new GameTurn.SpecificEntityTurn(e.getOwner().getId(), e.getId());
                    // Prevents adding extra turns for multi-turns
                    newTurn.setMultiTurn(true);
                    gameManager.game.insertNextTurn(newTurn);
                    gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));
                }
            }

            // Handle the building in the hex.
            if (bldg != null) {
                // Report that the entity has entered the bldg.
                r = new Report(2080);
                r.subject = entity.getId();
                r.indent();
                r.add(bldg.getName());
                r.add(nextPos.getBoardNum(), true);
                gameManager.addReport(r);

                // If the building hasn't already suffered
                // damage, then apply charge damage to the
                // building and displace the entity inside.
                // ASSUMPTION: you don't charge the building
                // if Tanks or Mechs were charged.
                int chargeDamage = ChargeAttackAction.getDamageFor(entity, gameManager.game
                                .getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_CHARGE_DAMAGE),
                        entity.delta_distance);
                if (!bldgSuffered) {
                    Vector<Report> reports = gameManager.damageBuilding(bldg, chargeDamage, nextPos);
                    for (Report report : reports) {
                        report.subject = entity.getId();
                    }
                    gameManager.reportManager.addReport(reports, gameManager);

                    // Apply damage to the attacker.
                    int toAttacker = ChargeAttackAction.getDamageTakenBy(entity, bldg, nextPos);
                    HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, entity.sideTable(nextPos));
                    hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                    gameManager.reportManager.addReport(gameManager.damageEntity(entity, hit, toAttacker), gameManager);
                    gameManager.addNewLines();

                    entity.setPosition(nextPos);
                    entity.setElevation(nextElevation);
                    gameManager.reportManager.addReport(gameManager.utilityManager.doEntityDisplacementMinefieldCheck(entity, curPos, nextPos, nextElevation, gameManager), gameManager);
                    curPos = nextPos;
                } // End buildings-suffer-too

                // Any infantry in the building take damage
                // equal to the building being charged.
                // ASSUMPTION: infantry take no damage from the
                // building absorbing damage from
                // Tanks and Mechs being charged.
                gameManager.reportManager.addReport(gameManager.damageInfantryIn(bldg, chargeDamage, nextPos), gameManager);

                // If a building still stands, then end the skid,
                // and add it to the list of affected buildings.
                if (bldg.getCurrentCF(nextPos) > 0) {
                    stopTheSkid = true;
                    if (bldg.rollBasement(nextPos, gameManager.game.getBoard(), gameManager.vPhaseReport)) {
                        gameManager.communicationManager.sendChangedHex(nextPos, gameManager);
                        Vector<Building> buildings = new Vector<>();
                        buildings.add(bldg);
                        gameManager.communicationManager.sendChangedBuildings(buildings, gameManager);
                    }
                    gameManager.environmentalEffectManager.addAffectedBldg(bldg, gameManager.checkBuildingCollapseWhileMoving(bldg, entity, nextPos), gameManager);
                } else {
                    // otherwise it collapses immediately on our head
                    gameManager.checkForCollapse(bldg, gameManager.game.getPositionMap(), nextPos, true, gameManager.vPhaseReport);
                }
            } // End handle-building.

            // Do we stay in the current hex and stop skidding?
            if (stopTheSkid) {
                break;
            }

            // Update entity position and elevation
            entity.setPosition(nextPos);
            entity.setElevation(nextElevation);
            gameManager.reportManager.addReport(gameManager.utilityManager.doEntityDisplacementMinefieldCheck(entity, curPos, nextPos, nextElevation, gameManager), gameManager);
            skidDistance++;

            // Check for collapse of any building the entity might be on
            Building roof = gameManager.game.getBoard().getBuildingAt(nextPos);
            if (roof != null) {
                if (gameManager.checkForCollapse(roof, gameManager.game.getPositionMap(), nextPos, true, gameManager.vPhaseReport)) {
                    break; // stop skidding if the building collapsed
                }
            }

            // Can the skidding entity enter the next hex from this?
            // N.B. can skid along roads.
            if ((entity.isLocationProhibited(start) || entity.isLocationProhibited(nextPos))
                    && !Compute.canMoveOnPavement(gameManager.game, curPos, nextPos, step)) {
                // Update report.
                r = new Report(2040);
                r.subject = entity.getId();
                r.indent();
                r.add(nextPos.getBoardNum(), true);
                gameManager.addReport(r);

                // If the prohibited terrain is water, entity is destroyed
                if ((nextHex.terrainLevel(Terrains.WATER) > 0)
                        && (entity instanceof Tank)
                        && (entity.getMovementMode() != EntityMovementMode.HOVER)
                        && (entity.getMovementMode() != EntityMovementMode.WIGE)) {
                    gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity(entity,
                            "skidded into a watery grave", false, true, gameManager), gameManager);
                }

                // otherwise, damage is weight/5 in 5pt clusters
                int damage = ((int) entity.getWeight() + 4) / 5;
                while (damage > 0) {
                    gameManager.reportManager.addReport(gameManager.damageEntity(entity, entity.rollHitLocation(
                                    ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT),
                            Math.min(5, damage)), gameManager);
                    damage -= 5;
                }
                // and unit is immobile
                if (entity instanceof Tank) {
                    ((Tank) entity).immobilize();
                }

                // Stay in the current hex and stop skidding.
                break;
            }

            if ((nextHex.terrainLevel(Terrains.WATER) > 0)
                    && (entity.getMovementMode() != EntityMovementMode.HOVER)
                    && (entity.getMovementMode() != EntityMovementMode.WIGE)) {
                // water ends the skid
                break;
            }

            // check for breaking magma crust
            // note that this must sequentially occur before the next 'entering liquid magma' check
            // otherwise, magma crust won't have a chance to break
            ServerHelper.checkAndApplyMagmaCrust(nextHex, nextElevation, entity, curPos, false, gameManager.vPhaseReport, gameManager);
            ServerHelper.checkEnteringMagma(nextHex, nextElevation, entity, gameManager);

            // is the next hex a swamp?
            PilotingRollData rollTarget = entity.checkBogDown(step, moveType, nextHex, curPos, nextPos,
                    step.getElevation(), Compute.canMoveOnPavement(gameManager.game, curPos, nextPos, step));

            if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                // Taharqa: According to TacOps, you automatically stick if you
                // are skidding, (pg. 63)
                // if (0 < doSkillCheckWhileMoving(entity, curPos, nextPos,
                // rollTarget, false)) {
                entity.setStuck(true);
                r = new Report(2081);
                r.subject = entity.getId();
                r.add(entity.getDisplayName(), true);
                gameManager.addReport(r);
                // check for quicksand
                gameManager.reportManager.addReport(gameManager.checkQuickSand(nextPos), gameManager);
                // check for accidental stacking violation
                Entity violation = Compute.stackingViolation(gameManager.game, entity.getId(), curPos, entity.climbMode());
                if (violation != null) {
                    // target gets displaced, because of low elevation
                    Coords targetDest = Compute.getValidDisplacement(gameManager.game, entity.getId(), curPos,
                            direction);
                    gameManager.reportManager.addReport(gameManager.utilityManager.doEntityDisplacement(violation, curPos, targetDest,
                            new PilotingRollData(violation.getId(), 0, "domino effect"), gameManager), gameManager);
                    // Update the violating entity's position on the client.
                    gameManager.entityUpdate(violation.getId());
                }
                // stay here and stop skidding, see bug 1115608
                break;
            }

            // Update the position and keep skidding.
            curPos = nextPos;
            curHex = nextHex;
            elevation = nextElevation;
            r = new Report(2085);
            r.subject = entity.getId();
            r.indent();
            r.add(curPos.getBoardNum(), true);
            gameManager.addReport(r);

            if (flip && entity instanceof Tank) {
                gameManager.entityActionManager.doVehicleFlipDamage((Tank) entity, flipDamage, direction < 3, skidDistance - 1, gameManager);
            }

        } // Handle the next skid hex.

        // If the skidding entity violates stacking,
        // displace targets until it doesn't.
        curPos = entity.getPosition();
        Entity target = Compute.stackingViolation(gameManager.game, entity.getId(), curPos, entity.climbMode());
        while (target != null) {
            nextPos = Compute.getValidDisplacement(gameManager.game, target.getId(), target.getPosition(), direction);
            // ASSUMPTION
            // There should always be *somewhere* that
            // the target can go... last skid hex if
            // nothing else is available.
            if (null == nextPos) {
                // But I don't trust the assumption fully.
                // Report the error and try to continue.
                LogManager.getLogger().error("The skid of " + entity.getShortName()
                        + " should displace " + target.getShortName()
                        + " in hex " + curPos.getBoardNum()
                        + " but there is nowhere to go.");
                break;
            }
            // indent displacement
            r = new Report(1210, Report.PUBLIC);
            r.indent();
            r.newlines = 0;
            gameManager.addReport(r);
            gameManager.reportManager.addReport(gameManager.utilityManager.doEntityDisplacement(target, curPos, nextPos, null, gameManager), gameManager);
            gameManager.reportManager.addReport(gameManager.utilityManager.doEntityDisplacementMinefieldCheck(entity, curPos, nextPos, entity.getElevation(), gameManager), gameManager);
            target = Compute.stackingViolation(gameManager.game, entity.getId(), curPos, entity.climbMode());
        }

        // Mechs suffer damage for every hex skidded.
        // For QuadVees in vehicle mode, apply
        // damage only if flipping.
        boolean mechDamage = ((entity instanceof Mech)
                && !((entity.getMovementMode() == EntityMovementMode.WIGE) && (entity.getElevation() > 0)));
        if (entity instanceof QuadVee && entity.getConversionMode() == QuadVee.CONV_MODE_VEHICLE) {
            mechDamage = flip;
        }
        if (mechDamage) {
            // Calculate one half falling damage times skid length.
            int damage = skidDistance * (int) Math.ceil(Math.round(entity.getWeight() / 10.0) / 2.0);

            // report skid damage
            r = new Report(2090);
            r.subject = entity.getId();
            r.indent();
            r.addDesc(entity);
            r.add(damage);
            gameManager.addReport(r);

            // standard damage loop
            // All skid damage is to the front.
            while (damage > 0) {
                int cluster = Math.min(5, damage);
                HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                gameManager.reportManager.addReport(gameManager.damageEntity(entity, hit, cluster), gameManager);
                damage -= cluster;
            }
            gameManager.addNewLines();
        }

        if (flip && entity instanceof Tank) {
            gameManager.reportManager.addReport(gameManager.applyCriticalHit(entity, Entity.NONE, new CriticalSlot(0, Tank.CRIT_CREW_STUNNED),
                    true, 0, false), gameManager);
        } else if (flip && entity instanceof QuadVee && entity.getConversionMode() == QuadVee.CONV_MODE_VEHICLE) {
            // QuadVees don't suffer stunned crew criticals; require PSR to avoid damage instead.
            PilotingRollData prd = entity.getBasePilotingRoll();
            gameManager.reportManager.addReport(gameManager.checkPilotAvoidFallDamage(entity, 1, prd), gameManager);
        }

        // Clean up the entity if it has been destroyed.
        if (entity.isDoomed()) {
            entity.setDestroyed(true);
            gameManager.game.moveToGraveyard(entity.getId());
            gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(entity.getId(), gameManager));

            // The entity's movement is completed.
            return true;
        }

        // Let the player know the ordeal is over.
        r = new Report(2095);
        r.subject = entity.getId();
        r.indent();
        gameManager.addReport(r);

        return false;
    }

    /**
     * Roll on the failed vehicle maneuver table.
     *
     * @param entity    The vehicle that failed the maneuver.
     * @param curPos    The coordinates of the hex in which the maneuver was attempted.
     * @param turnDirection The difference between the intended final facing and the starting facing
     *                      (-1 for left turn, 1 for right turn, 0 for not turning).
     * @param prevStep  The <code>MoveStep</code> immediately preceding the one being processed.
     *                  Cannot be null; if the check is made for the first step of the path,
     *                  use the current step.
     * @param isBackwards
     * @param lastStepMoveType  The <code>EntityMovementType</code> of the last step in the path.
     * @param distance  The distance moved so far during the phase; used to calculate any potential skid.
     * @param modifier  The modifier to the maneuver failure roll.
     * @param marginOfFailure
     * @param gameManager
     * @return          true if the maneuver failure result ends the unit's turn.
     */
    protected boolean processFailedVehicleManeuver(Entity entity, Coords curPos, int turnDirection,
                                                   MoveStep prevStep, boolean isBackwards, EntityMovementType lastStepMoveType, int distance,
                                                   int modifier, int marginOfFailure, GameManager gameManager) {
        Hex curHex = gameManager.game.getBoard().getHex(curPos);
        if (entity.getMovementMode() == EntityMovementMode.WHEELED
                && !curHex.containsTerrain(Terrains.PAVEMENT)) {
            modifier += 2;
        }
        if (entity.getMovementMode() == EntityMovementMode.VTOL) {
            modifier += 2;
        } else if (entity.getMovementMode() == EntityMovementMode.HOVER
                || (entity.getMovementMode() == EntityMovementMode.WIGE && entity instanceof Tank)
                || entity.getMovementMode() == EntityMovementMode.HYDROFOIL) {
            modifier += 4;
        }
        if (entity.getWeightClass() < EntityWeightClass.WEIGHT_MEDIUM
                || entity.getWeightClass() == EntityWeightClass.WEIGHT_SMALL_SUPPORT) {
            modifier++;
        } else if (entity.getWeightClass() == EntityWeightClass.WEIGHT_HEAVY
                || entity.getWeightClass() == EntityWeightClass.WEIGHT_LARGE_SUPPORT) {
            modifier--;
        } else if (entity.getWeightClass() == EntityWeightClass.WEIGHT_ASSAULT
                || entity.getWeightClass() == EntityWeightClass.WEIGHT_SUPER_HEAVY) {
            modifier -= 2;
        }
        boolean turnEnds = false;
        boolean motiveDamage = false;
        int motiveDamageMod = 0;
        boolean skid = false;
        boolean flip = false;
        boolean isGroundVehicle = ((entity instanceof Tank)
                && ((entity.getMovementMode() == EntityMovementMode.TRACKED)
                || (entity.getMovementMode() == EntityMovementMode.WHEELED)));

        Roll diceRoll = Compute.rollD6(2);
        int rollValue = diceRoll.getIntValue() + modifier;
        Report r = new Report(2505);
        r.subject = entity.getId();
        r.newlines = 0;
        r.indent(2);
        gameManager.addReport(r);
        r = new Report(6310);
        r.subject = entity.getId();
        String rollCalc = rollValue + " [" + diceRoll.getIntValue() + " + " + modifier + "]";
        r.addDataWithTooltip(rollCalc, diceRoll.getReport());
        r.newlines = 0;
        gameManager.addReport(r);
        r = new Report(3340);
        r.add(modifier);
        r.subject = entity.getId();
        r.newlines = 0;
        gameManager.addReport(r);

        r = new Report(1210);
        r.subject = entity.getId();
        if (rollValue < 8) {
            r.messageId = 2506;
            // minor fishtail, fail to turn
            turnDirection = 0;
        } else if (rollValue < 10) {
            r.messageId = 2507;
            // moderate fishtail, turn an extra hexside and roll for motive damage at -1.
            if (turnDirection == 0) {
                turnDirection = Compute.d6() < 4? -1 : 1;
            } else {
                turnDirection *= 2;
            }
            motiveDamage = true;
            motiveDamageMod = -1;
        } else if (rollValue < 12) {
            r.messageId = 2508;
            // serious fishtail, turn an extra hexside and roll for motive damage. Turn ends.
            if (turnDirection == 0) {
                turnDirection = Compute.d6() < 4? -1 : 1;
            } else {
                turnDirection *= 2;
            }
            motiveDamage = true;
            turnEnds = true;
        } else {
            r.messageId = 2509;
            // Turn fails and vehicle skids
            // Wheeled and naval vehicles start to flip if the roll is high enough.
            if (rollValue > 13) {
                if (entity.getMovementMode() == EntityMovementMode.WHEELED) {
                    r.messageId = 2510;
                    flip = true;
                } else if (entity.getMovementMode() == EntityMovementMode.NAVAL
                        || entity.getMovementMode() == EntityMovementMode.HYDROFOIL) {
                    entity.setDoomed(true);
                    r.messageId = 2511;
                }
            }
            skid = true;
            turnEnds = true;
        }
        gameManager.addReport(r);
        entity.setFacing((entity.getFacing() + turnDirection + 6) % 6);
        entity.setSecondaryFacing(entity.getFacing());
        if (motiveDamage && isGroundVehicle) {
            gameManager.reportManager.addReport(gameManager.vehicleMotiveDamage((Tank) entity, motiveDamageMod), gameManager);
        }
        if (skid && !entity.isDoomed()) {
            if (!flip && isGroundVehicle) {
                gameManager.reportManager.addReport(gameManager.vehicleMotiveDamage((Tank) entity, 0), gameManager);
            }

            int skidDistance = (int) Math.round((double) (distance - 1) / 2);
            if (flip && entity.getMovementMode() == EntityMovementMode.WHEELED) {
                // Wheeled vehicles that start to flip reduce the skid distance by one hex.
                skidDistance--;
            } else if (entity.getMovementMode() == EntityMovementMode.HOVER
                    || entity.getMovementMode() == EntityMovementMode.VTOL
                    || entity.getMovementMode() == EntityMovementMode.WIGE) {
                skidDistance = Math.min(marginOfFailure, distance);
            }
            if (skidDistance > 0) {
                int skidDirection = prevStep.getFacing();
                if (isBackwards) {
                    skidDirection = (skidDirection + 3) % 6;
                }
                processSkid(entity, curPos, prevStep.getElevation(), skidDirection, skidDistance,
                        prevStep, lastStepMoveType, flip, gameManager);
            }
        }
        return turnEnds;
    }

    protected void doVehicleFlipDamage(Tank entity, int damage, boolean startRight, int flipCount, GameManager gameManager) {
        HitData hit;

        int index = flipCount % 4;
        // If there is no turret, we do side-side-bottom
        if (entity.hasNoTurret()) {
            index = flipCount % 3;
            if (index > 0) {
                index++;
            }
        }
        switch (index) {
            case 0:
                hit = new HitData(startRight ? Tank.LOC_RIGHT : Tank.LOC_LEFT);
                break;
            case 1:
                hit = new HitData(Tank.LOC_TURRET);
                break;
            case 2:
                hit = new HitData(startRight ? Tank.LOC_LEFT : Tank.LOC_RIGHT);
                break;
            default:
                hit = null; //Motive damage instead
        }
        if (hit != null) {
            hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
            gameManager.reportManager.addReport(gameManager.damageEntity(entity, hit, damage), gameManager);
            // If the vehicle has two turrets, they both take full damage.
            if ((hit.getLocation() == Tank.LOC_TURRET) && !(entity.hasNoDualTurret())) {
                hit = new HitData(Tank.LOC_TURRET_2);
                hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                gameManager.reportManager.addReport(gameManager.damageEntity(entity, hit, damage), gameManager);
            }
        } else {
            gameManager.reportManager.addReport(gameManager.vehicleMotiveDamage(entity, 1), gameManager);
        }
    }

    /**
     * processes a potential collision
     *
     * @param entity
     * @param target
     * @param src
     * @param gameManager
     * @return
     */
    protected boolean processCollision(Entity entity, Entity target, Coords src, GameManager gameManager) {
        Report r;

        r = new Report(9035);
        r.subject = entity.getId();
        r.add(entity.getDisplayName());
        r.add(target.getDisplayName());
        gameManager.addReport(r);
        boolean partial = (Compute.d6() == 6);
        // if aero chance to avoid
        if ((target.isAero())
                && (target.mpUsed < target.getRunMPwithoutMASC())
                && !((IAero) target).isOutControlTotal() && !target.isImmobile()) {
            // give them a control roll to avoid the collision
            // TODO : I should make this voluntary really
            IAero ta = (IAero) target;
            PilotingRollData psr = target.getBasePilotingRoll();
            psr.addModifier(0, "avoiding collision");
            Roll diceRoll = Compute.rollD6(2);
            r = new Report(9045);
            r.subject = target.getId();
            r.add(target.getDisplayName());
            r.add(psr);
            r.add(diceRoll);
            r.newlines = 0;
            r.indent(2);

            if (diceRoll.getIntValue() < psr.getValue()) {
                r.choose(false);
                gameManager.addReport(r);
            } else {
                // avoided collision
                r.choose(true);
                gameManager.addReport(r);
                // two possibilities:
                // 1) the target already moved, but had MP left - check for
                // control roll conditions
                // 2) the target had not yet moved, move them in straight line
                if (!target.isDone()) {
                    int vel = ta.getCurrentVelocity();
                    MovePath md = new MovePath(gameManager.game, target);
                    while (vel > 0) {
                        md.addStep(MovePath.MoveStepType.FORWARDS);
                        vel--;
                    }
                    gameManager.game.removeTurnFor(target);
                    gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));
                    processMovement(target, md, null, gameManager);
                    // for some reason it is not clearing out turn
                } else {
                    // what needs to get checked?
                    // this move puts them at over-thrust
                    target.moved = EntityMovementType.MOVE_OVER_THRUST;
                    // they may have exceeded SI, only add if they hadn't
                    // exceeded it before
                    if (target.mpUsed <= ta.getSI()) {
                        PilotingRollData rollTarget = ta.checkThrustSITotal(
                                target.getRunMPwithoutMASC(), target.moved);
                        if (rollTarget.getValue() != TargetRoll.CHECK_FALSE) {
                            gameManager.game.addControlRoll(new PilotingRollData(
                                    target.getId(), 0,
                                    "Thrust spent during turn exceeds SI"));
                        }
                    }
                    target.mpUsed = target.getRunMPwithoutMASC();
                }
                return false;
            }
        } else {
            // can't avoid collision - write report
            r = new Report(9040);
            r.subject = entity.getId();
            r.add(entity.getDisplayName());
            r.indent(2);
            gameManager.addReport(r);
        }

        // if we are still here, then collide
        ToHitData toHit = new ToHitData(TargetRoll.AUTOMATIC_SUCCESS, "Its a collision");
        toHit.setSideTable(target.sideTable(src));
        gameManager.combatManager.resolveRamDamage((IAero) entity, target, toHit, partial, false, gameManager);

        // Has the target been destroyed?
        if (target.isDoomed()) {
            // Has the target taken a turn?
            if (!target.isDone()) {
                // Dead entities don't take turns.
                gameManager.game.removeTurnFor(target);
                gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));
            } // End target-still-to-move
            // Clean out the entity.
            target.setDestroyed(true);
            gameManager.game.moveToGraveyard(target.getId());
            gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(target.getId(), gameManager));
        }
        // Update the target's position,
        // unless it is off the game map.
        if (!gameManager.game.isOutOfGame(target)) {
            gameManager.entityUpdate(target.getId());
        }

        return true;
    }

    protected boolean checkCrash(Entity entity, Coords pos, int altitude, GameManager gameManager) {
        // only Aeros can crash
        if (!entity.isAero()) {
            return false;
        }
        // no crashing in space
        if (gameManager.game.getBoard().inSpace()) {
            return false;
        }
        // if aero on the ground map, then only crash if elevation is zero
        else if (gameManager.game.getBoard().onGround()) {
            return altitude <= 0;
        }
        // we must be in atmosphere
        // if we're off the map, assume hex ceiling 0
        // Hexes with elevations < 0 are treated as 0 altitude
        int ceiling = 0;
        if (gameManager.game.getBoard().getHex(pos) != null) {
            ceiling = Math.max(0, gameManager.game.getBoard().getHex(pos).ceiling(true));
        }
        return ceiling >= altitude;
    }

    protected Vector<Report> processCrash(Entity entity, int vel, Coords c, GameManager gameManager) {
        Vector<Report> vReport = new Vector<>();
        Report r;
        if (c == null) {
            r = new Report(9701);
            r.subject = entity.getId();
            vReport.add(r);
            vReport.addAll(gameManager.entityActionManager.destroyEntity(entity, "crashed off the map", true, true, gameManager));
            return vReport;
        }

        if (gameManager.game.getBoard().inAtmosphere()) {
            r = new Report(9393, Report.PUBLIC);
            r.indent();
            r.addDesc(entity);
            vReport.add(r);
            entity.setDoomed(true);
        } else {
            ((IAero) entity).land();
        }

        // we might hit multiple hexes, if we're a DropShip, so we do some
        // checks for all of them
        List<Coords> coords = new ArrayList<>();
        coords.add(c);
        Hex h = gameManager.game.getBoard().getHex(c);
        int crateredElevation;
        boolean containsWater = false;
        if (h.containsTerrain(Terrains.WATER)) {
            crateredElevation = Math.min(2, h.depth() + 1);
            containsWater = true;
        } else {
            crateredElevation = h.getLevel() - 2;
        }
        if (entity instanceof Dropship) {
            for (int i = 0; i < 6; i++) {
                Coords adjCoords = c.translated(i);
                if (!gameManager.game.getBoard().contains(adjCoords)) {
                    continue;
                }
                Hex adjHex = gameManager.game.getBoard().getHex(adjCoords);
                coords.add(adjCoords);
                if (adjHex.containsTerrain(Terrains.WATER)) {
                    if (containsWater) {
                        int newDepth = Math.min(2, adjHex.depth() + 1);
                        if (newDepth > crateredElevation) {
                            crateredElevation = newDepth;
                        }
                    } else {
                        crateredElevation = Math.min(2, adjHex.depth() + 1);
                        containsWater = true;
                    }
                } else if (!containsWater && (adjHex.getLevel() < crateredElevation)) {
                    crateredElevation = adjHex.getLevel();
                }
            }
        }
        // Units with velocity zero are treated like that had velocity two
        if (vel < 1) {
            vel = 2;
        }

        // deal crash damage only once
        boolean damageDealt = false;
        for (Coords hitCoords : coords) {
            int orig_crash_damage = Compute.d6(2) * 10 * vel;
            int crash_damage = orig_crash_damage;
            int direction = entity.getFacing();
            // first check for buildings
            Building bldg = gameManager.game.getBoard().getBuildingAt(hitCoords);
            if ((null != bldg) && (bldg.getType() == Building.HARDENED)) {
                crash_damage *= 2;
            }
            if (null != bldg) {
                gameManager.collapseBuilding(bldg, gameManager.game.getPositionMap(), hitCoords, true, vReport);
            }
            if (!damageDealt) {
                r = new Report(9700, Report.PUBLIC);
                r.indent();
                r.addDesc(entity);
                r.add(crash_damage);
                vReport.add(r);
                while (crash_damage > 0) {
                    HitData hit;
                    if ((entity instanceof SmallCraft) && ((SmallCraft) entity).isSpheroid()) {
                        hit = entity.rollHitLocation(ToHitData.HIT_SPHEROID_CRASH, ToHitData.SIDE_REAR);
                    } else {
                        hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                    }

                    if (crash_damage > 10) {
                        vReport.addAll(gameManager.damageEntity(entity, hit, 10));
                    } else {
                        vReport.addAll(gameManager.damageEntity(entity, hit, crash_damage));
                    }
                    crash_damage -= 10;
                }
                damageDealt = true;
            }

            // ok, now lets cycle through the entities in this spot and
            // potentially
            // damage them
            for (Entity victim : gameManager.game.getEntitiesVector(hitCoords)) {
                if (victim.getId() == entity.getId()) {
                    continue;
                }
                if (((victim.getElevation() > 0) && victim
                        .isAirborneVTOLorWIGE()) || (victim.getAltitude() > 0)) {
                    continue;
                }
                // if the crasher is a DropShip and the victim is not a mech,
                // then it is automatically destroyed
                if ((entity instanceof Dropship) && !(victim instanceof Mech)) {
                    vReport.addAll(gameManager.entityActionManager.destroyEntity(victim, "hit by crashing DropShip", gameManager));
                } else {
                    crash_damage = orig_crash_damage / 2;
                    // roll dice to see if they got hit
                    int target = 2;
                    if (victim instanceof Infantry) {
                        target = 3;
                    }
                    Roll diceRoll = Compute.rollD6(1);
                    r = new Report(9705, Report.PUBLIC);
                    r.indent();
                    r.addDesc(victim);
                    r.add(target);
                    r.add(crash_damage);
                    r.add(diceRoll);

                    if (diceRoll.getIntValue() > target) {
                        r.choose(true);
                        vReport.add(r);
                        // apply half the crash damage in 5 point clusters
                        // (check
                        // hit tables)
                        while (crash_damage > 0) {
                            HitData hit = victim.rollHitLocation(
                                    ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                            if (victim instanceof Mech) {
                                hit = victim.rollHitLocation(
                                        ToHitData.HIT_PUNCH, ToHitData.SIDE_FRONT);
                            }
                            if (victim instanceof Protomech) {
                                hit = victim.rollHitLocation(
                                        ToHitData.HIT_SPECIAL_PROTO, ToHitData.SIDE_FRONT);
                            }
                            if (crash_damage > 5) {
                                vReport.addAll(gameManager.damageEntity(victim, hit, 5));
                            } else {
                                vReport.addAll(gameManager.damageEntity(victim, hit, crash_damage));
                            }
                            crash_damage -= 5;
                        }

                    } else {
                        r.choose(false);
                        vReport.add(r);
                    }
                }

                if (!victim.isDoomed() && !victim.isDestroyed()) {
                    // entity displacement
                    Coords dest = Compute.getValidDisplacement(gameManager.game, victim.getId(), hitCoords, direction);
                    if (null != dest) {
                        gameManager.utilityManager.doEntityDisplacement(
                                victim,
                                hitCoords,
                                dest,
                                new PilotingRollData(victim.getId(), 0, "crash"), gameManager);
                    } else if (!(victim instanceof Dropship)) {
                        // destroy entity - but not DropShips which are immovable
                        gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity(victim, "impossible displacement",
                                victim instanceof Mech, victim instanceof Mech, gameManager), gameManager);
                    }
                }

            }

            // reduce woods
            h = gameManager.game.getBoard().getHex(hitCoords);
            if (h.containsTerrain(Terrains.WOODS)) {
                if (entity instanceof Dropship) {
                    h.removeTerrain(Terrains.WOODS);
                    h.removeTerrain(Terrains.FOLIAGE_ELEV);
                    h.addTerrain(new Terrain(Terrains.ROUGH, 1));
                } else {
                    int level = h.terrainLevel(Terrains.WOODS) - 1;
                    int folEl = h.terrainLevel(Terrains.FOLIAGE_ELEV);
                    h.removeTerrain(Terrains.WOODS);
                    if (level > 0) {
                        h.addTerrain(new Terrain(Terrains.WOODS, level));
                        h.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, folEl == 1 ? 1 : 2));
                    } else {
                        h.addTerrain(new Terrain(Terrains.ROUGH, 1));
                        h.removeTerrain(Terrains.FOLIAGE_ELEV);
                    }
                }
            }
            // do the same for jungles
            if (h.containsTerrain(Terrains.JUNGLE)) {
                if (entity instanceof Dropship) {
                    h.removeTerrain(Terrains.JUNGLE);
                    h.removeTerrain(Terrains.FOLIAGE_ELEV);
                    h.addTerrain(new Terrain(Terrains.ROUGH, 1));
                } else {
                    int level = h.terrainLevel(Terrains.JUNGLE) - 1;
                    int folEl = h.terrainLevel(Terrains.FOLIAGE_ELEV);
                    h.removeTerrain(Terrains.JUNGLE);
                    if (level > 0) {
                        h.addTerrain(new Terrain(Terrains.JUNGLE, level));
                        h.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, folEl == 1 ? 1 : 2));
                    } else {
                        h.addTerrain(new Terrain(Terrains.ROUGH, 1));
                        h.removeTerrain(Terrains.FOLIAGE_ELEV);
                    }
                }
            }
            if (entity instanceof Dropship) {
                if (!containsWater) {
                    h.setLevel(crateredElevation);
                } else {
                    if (!h.containsTerrain(Terrains.WATER)) {
                        h.removeAllTerrains();
                    }
                    h.addTerrain(new Terrain(Terrains.WATER, crateredElevation, false, 0));
                }
            }
            gameManager.communicationManager.sendChangedHex(hitCoords, gameManager);
        }

        // check for a stacking violation - which should only happen in the
        // case of grounded dropships, because they are not movable
        if (null != Compute.stackingViolation(gameManager.game, entity.getId(), c, entity.climbMode())) {
            Coords dest = Compute.getValidDisplacement(gameManager.game, entity.getId(), c,
                    Compute.d6() - 1);
            if (null != dest) {
                gameManager.utilityManager.doEntityDisplacement(entity, c, dest, null, gameManager);
            } else {
                // ack! automatic death! Tanks
                // suffer an ammo/power plant hit.
                // TODO : a Mech suffers a Head Blown Off crit.
                gameManager.vPhaseReport.addAll(gameManager.entityActionManager.destroyEntity(entity,
                        "impossible displacement", entity instanceof Mech,
                        entity instanceof Mech, gameManager));
            }
        }

        // Check for watery death
        h = gameManager.game.getBoard().getHex(c);
        if (h.containsTerrain(Terrains.WATER) && !entity.isDestroyed()
                && !entity.isDoomed()) {
            int lethalDepth;
            if (entity instanceof Dropship) {
                lethalDepth = 2;
            } else {
                lethalDepth = 1;
            }

            if (h.depth() >= lethalDepth) {
                // Oh snap... we is dead
                vReport.addAll(gameManager.entityActionManager.destroyEntity(entity,
                        "crashing into deep water", true, true, gameManager));
            }
        }

        return vReport;
    }

    /**
     * makes a unit skid or sideslip on the board
     *
     * @param entity    the unit which should skid
     * @param start     the coordinates of the hex the unit was in prior to skidding
     * @param elevation the elevation of the unit
     * @param direction the direction of the skid
     * @param distance  the number of hexes skidded
     * @param step      the MoveStep which caused the skid
     * @param moveType
     * @param gameManager
     * @return true if the entity was removed from play
     */
    protected boolean processSkid(Entity entity, Coords start, int elevation,
                                  int direction, int distance, MoveStep step,
                                  EntityMovementType moveType, GameManager gameManager) {
        return processSkid(entity, start, elevation, direction, distance, step, moveType, false, gameManager);
    }

    /**
     * Process any flee movement actions, including flying off the map
     *
     * @param movePath   The move path which resulted in an entity leaving the map.
     * @param flewOff    whether this fleeing is a result of accidentally flying off the
     *                   map
     * @param returnable the number of rounds until the unit can return to the map (-1
     *                   if it can't return)
     * @param gameManager
     * @return Vector of turn reports.
     */
    protected Vector<Report> processLeaveMap(MovePath movePath, boolean flewOff, int returnable, GameManager gameManager) {
        Entity entity = movePath.getEntity();
        Vector<Report> vReport = new Vector<>();
        Report r;
        // Unit has fled the battlefield.
        r = new Report(2005, Report.PUBLIC);
        if (flewOff) {
            r = new Report(9370, Report.PUBLIC);
        }
        r.addDesc(entity);
        gameManager.addReport(r);
        OffBoardDirection fleeDirection;
        if (movePath.getFinalCoords().getY() <= 0) {
            fleeDirection = OffBoardDirection.NORTH;
        } else if (movePath.getFinalCoords().getY() >= (gameManager.getGame().getBoard().getHeight() - 1)) {
            fleeDirection = OffBoardDirection.SOUTH;
        } else if (movePath.getFinalCoords().getX() <= 0) {
            fleeDirection = OffBoardDirection.WEST;
        } else {
            fleeDirection = OffBoardDirection.EAST;
        }

        if (returnable > -1) {

            entity.setDeployed(false);
            entity.setDeployRound(1 + gameManager.game.getRoundCount() + returnable);
            entity.setPosition(null);
            entity.setDone(true);
            if (entity.isAero()) {
                // If we're flying off because we're OOC, when we come back we
                // should no longer be OOC
                // If we don't, this causes a major problem as aeros tend to
                // return, re-deploy then
                // fly off again instantly.
                ((IAero) entity).setOutControl(false);
            }
            switch (fleeDirection) {
                case WEST:
                    entity.setStartingPos(Board.START_W);
                    break;
                case NORTH:
                    entity.setStartingPos(Board.START_N);
                    break;
                case EAST:
                    entity.setStartingPos(Board.START_E);
                    break;
                case SOUTH:
                    entity.setStartingPos(Board.START_S);
                    break;
                default:
                    entity.setStartingPos(Board.START_EDGE);
            }
            gameManager.entityUpdate(entity.getId());
            return vReport;
        } else {
            ServerHelper.clearBloodStalkers(gameManager.game, entity.getId(), gameManager);
        }

        // Is the unit carrying passengers or trailers?
        final List<Entity> passengers = new ArrayList<>(entity.getLoadedUnits());
        if (!entity.getAllTowedUnits().isEmpty()) {
            for (int id : entity.getAllTowedUnits()) {
                Entity towed = gameManager.game.getEntity(id);
                passengers.add(towed);
            }
        }
        if (!passengers.isEmpty()) {
            for (Entity passenger : passengers) {
                // Unit has fled the battlefield.
                r = new Report(2010, Report.PUBLIC);
                r.indent();
                r.addDesc(passenger);
                gameManager.addReport(r);
                passenger.setRetreatedDirection(fleeDirection);
                gameManager.game.removeEntity(passenger.getId(),
                        IEntityRemovalConditions.REMOVE_IN_RETREAT);
                gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(passenger.getId(),
                        IEntityRemovalConditions.REMOVE_IN_RETREAT, gameManager));
            }
        }

        // Handle any picked up MechWarriors
        for (Integer mechWarriorId : entity.getPickedUpMechWarriors()) {
            Entity mw = gameManager.game.getEntity(mechWarriorId);

            if (mw == null) {
                continue;
            }

            // Is the MechWarrior an enemy?
            int condition = IEntityRemovalConditions.REMOVE_IN_RETREAT;
            r = new Report(2010);
            if (mw.isCaptured()) {
                r = new Report(2015);
                condition = IEntityRemovalConditions.REMOVE_CAPTURED;
            } else {
                mw.setRetreatedDirection(fleeDirection);
            }
            gameManager.game.removeEntity(mw.getId(), condition);
            gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(mw.getId(), condition, gameManager));
            r.addDesc(mw);
            r.indent();
            gameManager.addReport(r);
        }
        // Is the unit being swarmed?
        final int swarmerId = entity.getSwarmAttackerId();
        if (Entity.NONE != swarmerId) {
            final Entity swarmer = gameManager.game.getEntity(swarmerId);

            // Has the swarmer taken a turn?
            if (!swarmer.isDone()) {
                // Dead entities don't take turns.
                gameManager.game.removeTurnFor(swarmer);
                gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));

            } // End swarmer-still-to-move

            // Unit has fled the battlefield.
            swarmer.setSwarmTargetId(Entity.NONE);
            entity.setSwarmAttackerId(Entity.NONE);
            r = new Report(2015, Report.PUBLIC);
            r.indent();
            r.addDesc(swarmer);
            gameManager.addReport(r);
            gameManager.game.removeEntity(swarmerId, IEntityRemovalConditions.REMOVE_CAPTURED);
            gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(swarmerId, IEntityRemovalConditions.REMOVE_CAPTURED, gameManager));
        }
        entity.setRetreatedDirection(fleeDirection);
        gameManager.game.removeEntity(entity.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
        gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(entity.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT, gameManager));
        return vReport;
    }

    /**
     * Mark the unit as destroyed! Units transported in the destroyed unit will
     * get a chance to escape.
     *
     * @param entity - the <code>Entity</code> that has been destroyed.
     * @param reason - a <code>String</code> detailing why the entity was
     *               destroyed.
     * @param gameManager
     * @return a <code>Vector</code> of <code>Report</code> objects that can be
     * sent to the output log.
     */
    protected Vector<Report> destroyEntity(Entity entity, String reason, GameManager gameManager) {
        return gameManager.entityActionManager.destroyEntity(entity, reason, true, gameManager);
    }

    /**
     * Marks a unit as destroyed! Units transported inside the destroyed unit
     * will get a chance to escape unless the destruction was not survivable.
     *
     * @param entity     - the <code>Entity</code> that has been destroyed.
     * @param reason     - a <code>String</code> detailing why the entity was
     *                   destroyed.
     * @param survivable - a <code>boolean</code> that identifies the destruction as
     *                   unsurvivable for transported units.
     * @param gameManager
     * @return a <code>Vector</code> of <code>Report</code> objects that can be
     * sent to the output log.
     */
    public Vector<Report> destroyEntity(Entity entity, String reason, boolean survivable, GameManager gameManager) {
        // Generally, the entity can still be salvaged.
        return gameManager.entityActionManager.destroyEntity(entity, reason, survivable, true, gameManager);
    }

    /**
     * Marks a unit as destroyed! Units transported inside the destroyed unit
     * will get a chance to escape unless the destruction was not survivable.
     *
     * @param entity     - the <code>Entity</code> that has been destroyed.
     * @param reason     - a <code>String</code> detailing why the entity was
     *                   destroyed.
     * @param survivable - a <code>boolean</code> that identifies the destruction as
     *                   unsurvivable for transported units.
     * @param canSalvage - a <code>boolean</code> that indicates if the unit can be
     *                   salvaged (or cannibalized for spare parts). If
     *                   <code>true</code>, salvage operations are possible, if
     *                   <code>false</code>, the unit is too badly damaged.
     * @param gameManager
     * @return a <code>Vector</code> of <code>Report</code> objects that can be
     * sent to the output log.
     */
    public Vector<Report> destroyEntity(Entity entity, String reason, boolean survivable,
                                        boolean canSalvage, GameManager gameManager) {
        // can't destroy an entity if it's already been destroyed
        if (entity.isDestroyed()) {
            return new Vector<>();
        }

        Vector<Report> vDesc = new Vector<>();
        Report r;

        //We'll need this later...
        Aero ship = null;
        if (entity.isLargeCraft()) {
            ship = (Aero) entity;
        }

        // regardless of what was passed in, units loaded onto aeros not on the
        // ground are destroyed
        if (entity.isAirborne()) {
            survivable = false;
        } else if (entity.isAero()) {
            survivable = true;
        }

        // The unit can suffer an ammo explosion after it has been destroyed.
        int condition = IEntityRemovalConditions.REMOVE_SALVAGEABLE;
        if (!canSalvage) {
            entity.setSalvage(false);
            condition = IEntityRemovalConditions.REMOVE_DEVASTATED;
        }

        // Destroy the entity, unless it's already destroyed.
        if (!entity.isDoomed() && !entity.isDestroyed()) {
            r = new Report(6365);
            r.subject = entity.getId();
            r.addDesc(entity);
            r.add(reason);
            vDesc.addElement(r);

            entity.setDoomed(true);

            // Kill any picked up MechWarriors
            Enumeration<Integer> iter = entity.getPickedUpMechWarriors().elements();
            while (iter.hasMoreElements()) {
                int mechWarriorId = iter.nextElement();
                Entity mw = gameManager.game.getEntity(mechWarriorId);

                // in some situations, a "picked up" mechwarrior won't actually exist
                // probably this is brought about by picking up a mechwarrior in a previous MekHQ scenario
                // then having the same unit get blown up in a subsequent scenario
                // in that case, we simply move on
                if (mw == null) {
                    continue;
                }

                mw.setDestroyed(true);
                // We can safely remove these, as they can't be targeted
                gameManager.game.removeEntity(mw.getId(), condition);
                gameManager.entityUpdate(mw.getId());
                gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(mw.getId(), condition, gameManager));
                r = new Report(6370);
                r.subject = mw.getId();
                r.addDesc(mw);
                vDesc.addElement(r);
            }

            // make any remaining telemissiles operated by this entity
            // out of contact
            for (int missileId : entity.getTMTracker().getMissiles()) {
                Entity tm = gameManager.game.getEntity(missileId);
                if ((null != tm) && !tm.isDestroyed() && (tm instanceof TeleMissile)) {
                    ((TeleMissile) tm).setOutContact(true);
                    gameManager.entityUpdate(tm.getId());
                }
            }

            // Mechanized BA that could die on a 3+
            List<Entity> externalUnits = entity.getExternalUnits();

            // Handle escape of transported units.
            if (!entity.getLoadedUnits().isEmpty()) {
                Coords curPos = entity.getPosition();
                int curFacing = entity.getFacing();
                for (Entity other : entity.getLoadedUnits()) {
                    // If the unit has been destroyed (as from a cargo hit), skip it
                    if (other.isDestroyed()) {
                        continue;
                    }
                    // Can the other unit survive?
                    boolean survived = false;
                    if (entity instanceof Tank) {
                        if (entity.getMovementMode().isNaval()
                                || entity.getMovementMode().isHydrofoil()) {
                            if (other.getMovementMode().isUMUInfantry()) {
                                survived = Compute.d6() <= 3;
                            } else if (other.getMovementMode().isJumpInfantry()) {
                                survived = Compute.d6() == 1;
                            } else if (other.getMovementMode().isVTOL()) {
                                survived = Compute.d6() <= 2;
                            }
                        } else if (entity.getMovementMode().isSubmarine()) {
                            if (other.getMovementMode().isUMUInfantry()) {
                                survived = Compute.d6() == 1;
                            }
                        } else {
                            survived = Compute.d6() <= 4;
                        }
                    } else if (entity instanceof Mech) {
                        // mechanized BA can escape on a roll of 1 or 2
                        if (externalUnits.contains(other)) {
                            survived = Compute.d6() < 3;
                        }
                    }
                    if (!survivable || (externalUnits.contains(other) && !survived)
                            // Don't unload from ejecting spacecraft. The crews aren't in their units...
                            || (ship != null && ship.isEjecting())) {
                        // Nope.
                        other.setDestroyed(true);
                        // We need to unload the unit, since it's ID goes away
                        entity.unload(other);
                        // Safe to remove, as they aren't targeted
                        gameManager.game.moveToGraveyard(other.getId());
                        gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(other.getId(), condition, gameManager));
                        r = new Report(6370);
                        r.subject = other.getId();
                        r.addDesc(other);
                        vDesc.addElement(r);
                    }
                    // Can we unload the unit to the current hex?
                    // TODO : unloading into stacking violation is not
                    // explicitly prohibited in the BMRr.
                    else if ((null != Compute.stackingViolation(gameManager.game, other.getId(), curPos, entity.climbMode()))
                            || other.isLocationProhibited(curPos)) {
                        // Nope.
                        other.setDestroyed(true);
                        // We need to unload the unit, since it's ID goes away
                        entity.unload(other);
                        // Safe to remove, as they aren't targeted
                        gameManager.game.moveToGraveyard(other.getId());
                        gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(other.getId(), condition, gameManager));
                        r = new Report(6375);
                        r.subject = other.getId();
                        r.addDesc(other);
                        vDesc.addElement(r);
                    } else {
                        // The other unit survives.
                        gameManager.unloadUnit(entity, other, curPos, curFacing, entity.getElevation(),
                                true, false);
                    }
                }
            }

            // Handle transporting unit.
            if (Entity.NONE != entity.getTransportId()) {
                final Entity transport = gameManager.game.getEntity(entity.getTransportId());
                Coords curPos = transport.getPosition();
                int curFacing = transport.getFacing();
                if (!transport.isLargeCraft()) {
                    gameManager.unloadUnit(transport, entity, curPos, curFacing, transport.getElevation());
                }
                gameManager.entityUpdate(transport.getId());

                // if this is the last fighter in a fighter squadron then remove the squadron
                if ((transport instanceof FighterSquadron) && transport.getSubEntities().isEmpty()) {
                    transport.setDestroyed(true);

                    r = new Report(6365);
                    r.subject = transport.getId();
                    r.addDesc(transport);
                    r.add("fighter destruction");
                    vDesc.addElement(r);
                }
            }

            // Is this unit towing some trailers?
            // If so, disconnect them
            if (!entity.getAllTowedUnits().isEmpty()) {
                //Find the first trailer in the list and drop it
                //this will disconnect all that follow too
                Entity leadTrailer = gameManager.game.getEntity(entity.getAllTowedUnits().get(0));
                gameManager.disconnectUnit(entity, leadTrailer, entity.getPosition());
            }

            // Is this unit a trailer being towed? If so, disconnect it from its tractor
            if (entity.getTractor() != Entity.NONE) {
                Entity tractor = gameManager.game.getEntity(entity.getTractor());
                gameManager.disconnectUnit(tractor, entity, tractor.getPosition());
            }

            // Is this unit being swarmed?
            final int swarmerId = entity.getSwarmAttackerId();
            if (Entity.NONE != swarmerId) {
                final Entity swarmer = gameManager.game.getEntity(swarmerId);

                swarmer.setSwarmTargetId(Entity.NONE);
                // a unit that stopped swarming due to the swarmed unit dieing
                // should be able to move: setSwarmTargetId to Entity.None
                // changes done to true and unloaded to true, need to undo this
                swarmer.setUnloaded(false);
                swarmer.setDone(false);
                entity.setSwarmAttackerId(Entity.NONE);
                Report.addNewline(vDesc);
                r = new Report(6380);
                r.subject = swarmerId;
                r.addDesc(swarmer);
                vDesc.addElement(r);
                // Swarming infantry shouldn't take damage when their target dies
                // http://bg.battletech.com/forums/total-warfare/swarming-question
                gameManager.entityUpdate(swarmerId);
            }

            // Is this unit swarming somebody?
            final int swarmedId = entity.getSwarmTargetId();
            if (Entity.NONE != swarmedId) {
                final Entity swarmed = gameManager.game.getEntity(swarmedId);
                swarmed.setSwarmAttackerId(Entity.NONE);
                entity.setSwarmTargetId(Entity.NONE);
                r = new Report(6385);
                r.subject = swarmed.getId();
                r.addDesc(swarmed);
                vDesc.addElement(r);
                gameManager.entityUpdate(swarmedId);
            }

            // If in a grapple, release both mechs
            if (entity.getGrappled() != Entity.NONE) {
                int grappler = entity.getGrappled();
                entity.setGrappled(Entity.NONE, false);
                Entity e = gameManager.game.getEntity(grappler);
                if (e != null) {
                    e.setGrappled(Entity.NONE, false);
                }
                gameManager.entityUpdate(grappler);
            }

            ServerHelper.clearBloodStalkers(gameManager.game, entity.getId(), gameManager);
        }

        // if using battlefield wreckage rules, then the destruction of this unit might convert the
        // hex to rough
        Coords curPos = entity.getPosition();
        Hex entityHex = gameManager.game.getBoard().getHex(curPos);
        if (gameManager.game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_BATTLE_WRECK)
                && (entityHex != null) && gameManager.game.getBoard().onGround()
                && !((entity instanceof Infantry) || (entity instanceof Protomech))) {
            // large support vees will create ultra rough, otherwise rough
            if (entity instanceof LargeSupportTank) {
                if (entityHex.terrainLevel(Terrains.ROUGH) < 2) {
                    entityHex.addTerrain(new Terrain(Terrains.ROUGH, 2));
                    gameManager.communicationManager.sendChangedHex(curPos, gameManager);
                }
            } else if ((entity.getWeight() >= 40) && !entityHex.containsTerrain(Terrains.ROUGH)) {
                entityHex.addTerrain(new Terrain(Terrains.ROUGH, 1));
                gameManager.communicationManager.sendChangedHex(curPos, gameManager);
            }
        }

        // update our entity, so clients have correct data needed for MekWars stuff
        gameManager.entityUpdate(entity.getId());

        return vDesc;
    }

    /**
     * Explodes a piece of equipment on the unit.
     * @param en
     * @param loc
     * @param mounted
     * @param gameManager
     */
    public Vector<Report> explodeEquipment(Entity en, int loc, Mounted mounted, GameManager gameManager) {
        return gameManager.entityActionManager.explodeEquipment(en, loc, mounted, false, gameManager);
    }

    /**
     * Makes a piece of equipment on a mech explode! POW! This expects either
     * ammo, or an explosive weapon. Returns a vector of Report objects.
     * Possible to override 'is explosive' check
     * @param en
     * @param loc
     * @param mounted
     * @param overrideExplosiveCheck
     * @param gameManager
     */
    public Vector<Report> explodeEquipment(Entity en, int loc, Mounted mounted, boolean overrideExplosiveCheck, GameManager gameManager) {
        Vector<Report> vDesc = new Vector<>();
        // is this already destroyed?
        if (mounted.isDestroyed()) {
            LogManager.getLogger().error("Called on destroyed equipment(" + mounted.getName() + ")");
            return vDesc;
        }

        // Special case: LAM bomb bays explode the bomb stored there, which may involve going through a
        // launch weapon to the bomb ammo.
        if ((mounted.getType() instanceof MiscType) && mounted.getType().hasFlag(MiscType.F_BOMB_BAY)) {
            while (mounted.getLinked() != null) {
                mounted = mounted.getLinked();
            }
            // Fuel tank explodes on 2d6 roll of 10+
            if ((mounted.getType() instanceof MiscType) && mounted.getType().hasFlag(MiscType.F_FUEL)) {
                Report r = new Report(9120);
                r.subject = en.getId();
                int boomTarget = 10;
                // check for possible explosion
                int fuelRoll = Compute.d6(2);
                r.choose(fuelRoll >= boomTarget);
                if (fuelRoll >= boomTarget) {
                    r.choose(true);
                    vDesc.add(r);
                } else {
                    r.choose(false);
                    vDesc.add(r);
                    return vDesc;
                }
            }
        }

        if (!overrideExplosiveCheck && !mounted.getType().isExplosive(mounted, false)) {
            return vDesc;
        }

        // Inferno ammo causes heat buildup as well as the damage
        if ((mounted.getType() instanceof AmmoType)
                && ((((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_SRM)
                || (((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_SRM_IMP)
                || (((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_IATM)
                || (((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_MML))
                && (((AmmoType) mounted.getType()).getMunitionType().contains(AmmoType.Munitions.M_INFERNO)
                && (mounted.getHittableShotsLeft() > 0))) {
            en.heatBuildup += Math.min(mounted.getExplosionDamage(), 30);
        }

        // Inferno bombs in LAM bomb bays
        if ((mounted.getType() instanceof BombType)
                && (((BombType) mounted.getType()).getBombType() == BombType.B_INFERNO)) {
            en.heatBuildup += Math.min(mounted.getExplosionDamage(), 30);
        }

        // determine and deal damage
        int damage = mounted.getExplosionDamage();

        // Smoke ammo halves damage
        if ((mounted.getType() instanceof AmmoType)
                && ((((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_SRM)
                || (((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_SRM_IMP)
                || (((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_LRM)
                || (((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_LRM_IMP))
                && (((AmmoType) mounted.getType()).getMunitionType().contains(AmmoType.Munitions.M_SMOKE_WARHEAD)
                && (mounted.getHittableShotsLeft() > 0))) {
            damage = ((mounted.getExplosionDamage()) / 2);
        }
        // coolant explodes for 2 damage and reduces heat by 3
        if ((mounted.getType() instanceof AmmoType)
                && ((((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_VEHICLE_FLAMER)
                || (((AmmoType) mounted.getType()).getAmmoType() == AmmoType.T_HEAVY_FLAMER))
                && (((AmmoType) mounted.getType()).getMunitionType().contains(AmmoType.Munitions.M_COOLANT)
                && (mounted.getHittableShotsLeft() > 0))) {
            damage = 2;
            en.coolFromExternal += 3;
        }

        // divide damage by 10 for aeros, per TW rules on pg. 161
        if (en instanceof Aero) {
            int newDamage = (int) Math.floor(damage / 10.0);
            if ((newDamage == 0) && (damage > 0)) {
                damage = 1;
            } else {
                damage = newDamage;
            }
        }

        if (damage <= 0) {
            return vDesc;
        }

        Report r = new Report(6390);
        r.subject = en.getId();
        r.add(mounted.getName());
        r.add(damage);
        r.indent(3);
        vDesc.addElement(r);
        // Mounted is a weapon and has Hot-Loaded ammo in it and it exploded now
        // we need to roll for chain reaction
        if ((mounted.getType() instanceof WeaponType) && mounted.isHotLoaded()) {
            Roll diceRoll = Compute.rollD6(2);
            int ammoExploded = 0;
            r = new Report(6077);
            r.subject = en.getId();
            r.add(diceRoll);
            r.indent(2);
            vDesc.addElement(r);

            // roll of 2-5 means a chain reaction happened
            if (diceRoll.getIntValue() < 6) {
                for (Mounted ammo : en.getAmmo()) {
                    if ((ammo.getLocation() == loc) && (ammo.getExplosionDamage() > 0)
                            // Dead-Fire ammo bins are designed not to explode
                            // from the chain reaction
                            // Of Critted Launchers with DFM or HotLoaded ammo.
                            && !(((AmmoType) ammo.getType()).getMunitionType().contains(AmmoType.Munitions.M_DEAD_FIRE))) {
                        ammoExploded++;
                        vDesc.addAll(explodeEquipment(en, loc, ammo, gameManager));
                        break;
                    }
                }
                if (ammoExploded == 0) {
                    r = new Report(6078);
                    r.subject = en.getId();
                    r.indent(2);
                    vDesc.addElement(r);
                }
            } else {
                r = new Report(6079);
                r.subject = en.getId();
                r.indent(2);
                vDesc.addElement(r);
            }
        }

        HitData hit = new HitData(loc);
        // check to determine whether this is capital scale if we have a capital
        // scale entity
        if (mounted.getType() instanceof AmmoType) {
            if (((AmmoType) mounted.getType()).isCapital()) {
                hit.setCapital(true);
            }
        }

        // exploding RISC laser pulse module should cause no normal crits, just
        // automatically crit the first uncritted crit of the laser it's
        // attached to
        if ((mounted.getType() instanceof MiscType)  && mounted.getType().hasFlag(MiscType.F_RISC_LASER_PULSE_MODULE)) {
            hit.setEffect(HitData.EFFECT_NO_CRITICALS);
            Mounted laser = mounted.getLinkedBy();
            if (en instanceof Mech) {
                for (int slot = 0; slot < en.getNumberOfCriticals(laser.getLocation()); slot++) {
                    CriticalSlot cs = en.getCritical(laser.getLocation(), slot);
                    if ((cs.getType() == CriticalSlot.TYPE_EQUIPMENT) && cs.getMount().equals(laser)
                            && cs.isHittable()) {
                        cs.setHit(true);
                        cs.setRepairable(true);
                        break;
                    }
                }
            }
            laser.setHit(true);
        }

        mounted.setShotsLeft(0);

        int pilotDamage = 2;
        if (en instanceof Aero) {
            pilotDamage = 1;
        }
        if (gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_CASE_PILOT_DAMAGE)
                && (en.locationHasCase(hit.getLocation()) || en.hasCASEII(hit.getLocation()))) {
            pilotDamage = 1;
        }
        if (en.hasAbility(OptionsConstants.MISC_PAIN_RESISTANCE)
                || en.hasAbility(OptionsConstants.MISC_IRON_MAN)) {
            pilotDamage -= 1;
        }
        // tanks only take pilot damage when using BVDNI or VDNI
        if ((en instanceof Tank) && !(en.hasAbility(OptionsConstants.MD_VDNI)
                || en.hasAbility(OptionsConstants.MD_BVDNI))) {
            pilotDamage = 0;
        }
        if (!en.hasAbility(OptionsConstants.MD_PAIN_SHUNT)) {
            vDesc.addAll(gameManager.damageCrew(en, pilotDamage, en.getCrew().getCurrentPilotIndex()));
        }
        if (en.getCrew().isDoomed() || en.getCrew().isDead()) {
            vDesc.addAll(destroyEntity(en, "crew death", true, gameManager));
        } else {
            Report.addNewline(vDesc);
        }

        Vector<Report> newReports = gameManager.damageEntity(en, hit, damage, true);
        for (Report rep : newReports) {
            rep.indent(2);
        }
        vDesc.addAll(newReports);
        Report.addNewline(vDesc);

        return vDesc;
    }

    /**
     * Makes a piece of equipment on a mech explode! POW! This expects either
     * ammo, or an explosive weapon. Returns a vector of Report objects.
     * @param en
     * @param loc
     * @param slot
     * @param gameManager
     */
    protected Vector<Report> explodeEquipment(Entity en, int loc, int slot, GameManager gameManager) {
        CriticalSlot critSlot = en.getCritical(loc, slot);
        Vector<Report> reports = explodeEquipment(en, loc, critSlot.getMount(), gameManager);
        if (critSlot.getMount2() != null) {
            reports.addAll(explodeEquipment(en, loc, critSlot.getMount2(), gameManager));
        }
        return reports;
    }

    /**
     * Makes any roll required when an AirMech lands and resolve any damage or
     * skidding resulting from a failed roll. Updates final position and elevation.
     *  @param lam       the landing LAM
     * @param pos       the <code>Coords</code> of the landing hex
     * @param elevation the elevation from which the landing is attempted (usually 1, but may be higher
     *                          if the unit is forced to land due to insufficient movement
     * @param distance  the distance the unit moved in the turn prior to landing
     * @param gameManager
     */
    protected Vector<Report> landAirMech(LandAirMech lam, Coords pos, int elevation, int distance, GameManager gameManager) {
        Vector<Report> vDesc = new Vector<>();

        lam.setPosition(pos);
        Hex hex = gameManager.game.getBoard().getHex(pos);
        if (hex.containsTerrain(Terrains.BLDG_ELEV)) {
            lam.setElevation(hex.terrainLevel(Terrains.BLDG_ELEV));
        } else {
            lam.setElevation(0);
        }
        PilotingRollData psr = lam.checkAirMechLanding();
        if (psr.getValue() != TargetRoll.CHECK_FALSE
                && (0 > gameManager.utilityManager.doSkillCheckWhileMoving(lam, elevation, pos, pos, psr, false, gameManager))) {
            gameManager.entityActionManager.crashAirMech(lam, pos, elevation, distance, psr, vDesc, gameManager);
        }
        return vDesc;
    }

    protected boolean crashAirMech(Entity en, PilotingRollData psr, Vector<Report> vDesc, GameManager gameManager) {
        return gameManager.entityActionManager.crashAirMech(en, en.getPosition(), en.getElevation(), en.delta_distance, psr, vDesc, gameManager);
    }

    protected boolean crashAirMech(Entity en, Coords pos, int elevation, int distance,
                                   PilotingRollData psr, Vector<Report> vDesc, GameManager gameManager) {
        MoveStep step = new MoveStep(null, MovePath.MoveStepType.DOWN);
        step.setFromEntity(en, gameManager.game);
        return gameManager.entityActionManager.crashAirMech(en, pos, elevation, distance, psr, step, vDesc, gameManager);
    }

    protected boolean crashAirMech(Entity en, Coords pos, int elevation, int distance,
                                   PilotingRollData psr, MoveStep lastStep, Vector<Report> vDesc, GameManager gameManager) {
        vDesc.addAll(gameManager.utilityManager.doEntityFallsInto(en, elevation, pos, pos, psr, true, 0, gameManager));
        return en.isDoomed()
                || processSkid(en, pos, 0, 0, distance, lastStep, en.moved, false, gameManager);
    }

    /**
     * Makes the landing roll required for a glider ProtoMech and resolves any damage
     * resulting from a failed roll. Updates final position and elevation.
     *
     * @param en    the landing glider ProtoMech
     * @param gameManager
     */
    protected Vector<Report> landGliderPM(Protomech en, GameManager gameManager) {
        return gameManager.entityActionManager.landGliderPM(en, en.getPosition(), en.getElevation(), en.delta_distance, gameManager);
    }

    /**
     * Makes the landing roll required for a glider ProtoMech and resolves any damage
     * resulting from a failed roll. Updates final position and elevation.
     *  @param en    the landing glider ProtoMech
     * @param pos   the <code>Coords</code> of the landing hex
     * @param startElevation    the elevation from which the landing is attempted (usually 1, but may be higher
     *                          if the unit is forced to land due to insufficient movement
     * @param distance  the distance the unit moved in the turn prior to landing
     * @param gameManager
     */
    protected Vector<Report> landGliderPM(Protomech en, Coords pos, int startElevation,
                                          int distance, GameManager gameManager) {
        Vector<Report> vDesc = new Vector<>();

        en.setPosition(pos);
        Hex hex = gameManager.game.getBoard().getHex(pos);
        if (hex.containsTerrain(Terrains.BLDG_ELEV)) {
            en.setElevation(hex.terrainLevel(Terrains.BLDG_ELEV));
        } else {
            en.setElevation(0);
        }
        PilotingRollData psr = en.checkGliderLanding();
        if ((psr.getValue() != TargetRoll.CHECK_FALSE)
                && (0 > gameManager.utilityManager.doSkillCheckWhileMoving(en, startElevation, pos, pos, psr, false, gameManager))) {
            for (int i = 0; i < en.getNumberOfCriticals(Protomech.LOC_LEG); i++) {
                en.getCritical(Protomech.LOC_LEG, i).setHit(true);
            }
            HitData hit = new HitData(Protomech.LOC_LEG);
            vDesc.addAll(gameManager.damageEntity(en, hit, 2 * startElevation));
        }
        return vDesc;
    }

    /**
     * Resolves the forced landing of one airborne {@code VTOL} or {@code WiGE}
     * in its current hex. As this method is only for internal use and not part
     * of the exported public API, it simply relies on its client code to only
     * ever hand it a valid airborne vehicle and does not run any further checks
     * of its own.
     *
     * @param en The {@code VTOL} or {@code WiGE} in question.
     * @param gameManager
     * @return The resulting {@code Vector} of {@code Report}s.
     */
    protected Vector<Report> forceLandVTOLorWiGE(Tank en, GameManager gameManager) {
        Vector<Report> vDesc = new Vector<>();
        PilotingRollData psr = en.getBasePilotingRoll();
        Hex hex = gameManager.game.getBoard().getHex(en.getPosition());
        if (en instanceof VTOL) {
            psr.addModifier(4, "VTOL making forced landing");
        } else {
            psr.addModifier(0, "WiGE making forced landing");
        }
        int elevation = Math.max(hex.terrainLevel(Terrains.BLDG_ELEV),
                hex.terrainLevel(Terrains.BRIDGE_ELEV));
        elevation = Math.max(elevation, 0);
        elevation = Math.min(elevation, en.getElevation());
        if (en.getElevation() > elevation) {
            if (!hex.containsTerrain(Terrains.FUEL_TANK)
                    && !hex.containsTerrain(Terrains.JUNGLE)
                    && !hex.containsTerrain(Terrains.MAGMA)
                    && !hex.containsTerrain(Terrains.MUD)
                    && !hex.containsTerrain(Terrains.RUBBLE)
                    && !hex.containsTerrain(Terrains.WATER)
                    && !hex.containsTerrain(Terrains.WOODS)) {
                Report r = new Report(2180);
                r.subject = en.getId();
                r.addDesc(en);
                r.add(psr.getLastPlainDesc(), true);
                vDesc.add(r);

                // roll
                final Roll diceRoll = Compute.rollD6(2);
                r = new Report(2185);
                r.subject = en.getId();
                r.add(psr.getValueAsString());
                r.add(psr.getDesc());
                r.add(diceRoll);

                if (diceRoll.getIntValue() < psr.getValue()) {
                    r.choose(false);
                    vDesc.add(r);
                    vDesc.addAll(gameManager.entityActionManager.crashVTOLorWiGE(en, true, gameManager));
                } else {
                    r.choose(true);
                    vDesc.add(r);
                    en.setElevation(elevation);
                }
            } else {
                vDesc.addAll(gameManager.entityActionManager.crashVTOLorWiGE(en, true, gameManager));
            }
        }
        return vDesc;
    }

    /**
     * Crash a VTOL
     *
     * @param en the <code>VTOL</code> to be crashed
     * @param gameManager
     * @return the <code>Vector<Report></code> containing phase reports
     */
    protected Vector<Report> crashVTOLorWiGE(Tank en, GameManager gameManager) {
        return gameManager.entityActionManager.crashVTOLorWiGE(en, false, false, 0, en.getPosition(),
                en.getElevation(), 0, gameManager);
    }

    /**
     * Crash a VTOL or WiGE.
     *
     * @param en              The {@code VTOL} or {@code WiGE} to crash.
     * @param rerollRotorHits Whether any rotor hits from the crash should be rerolled,
     *                        typically after a "rotor destroyed" critical hit.
     * @param gameManager
     * @return The {@code Vector<Report>} of resulting reports.
     */
    protected Vector<Report> crashVTOLorWiGE(Tank en, boolean rerollRotorHits, GameManager gameManager) {
        return gameManager.entityActionManager.crashVTOLorWiGE(en, rerollRotorHits, false, 0, en.getPosition(),
                en.getElevation(), 0, gameManager);
    }

    /**
     * Crash a VTOL or WiGE.
     *
     * @param en              The {@code VTOL} or {@code WiGE} to crash.
     * @param rerollRotorHits Whether any rotor hits from the crash should be rerolled,
     *                        typically after a "rotor destroyed" critical hit.
     * @param sideSlipCrash   A <code>boolean</code> value indicating whether this is a
     *                        sideslip crash or not.
     * @param hexesMoved      The <code>int</code> number of hexes moved.
     * @param crashPos        The <code>Coords</code> of the crash
     * @param crashElevation  The <code>int</code> elevation of the VTOL
     * @param impactSide      The <code>int</code> describing the side on which the VTOL
     *                        falls
     * @param gameManager
     * @return a <code>Vector<Report></code> of Reports.
     */

    protected Vector<Report> crashVTOLorWiGE(Tank en, boolean rerollRotorHits,
                                             boolean sideSlipCrash, int hexesMoved, Coords crashPos,
                                             int crashElevation, int impactSide, GameManager gameManager) {
        Vector<Report> vDesc = new Vector<>();
        Report r;

        // we might be off the board after a DFA, so return then
        if (!gameManager.game.getBoard().contains(crashPos)) {
            return vDesc;
        }

        if (!sideSlipCrash) {
            // report lost movement and crashing
            r = new Report(6260);
            r.subject = en.getId();
            r.newlines = 0;
            r.addDesc(en);
            vDesc.addElement(r);
            int newElevation = 0;
            Hex fallHex = gameManager.game.getBoard().getHex(crashPos);

            // May land on roof of building or bridge
            if (fallHex.containsTerrain(Terrains.BLDG_ELEV)) {
                newElevation = fallHex.terrainLevel(Terrains.BLDG_ELEV);
            } else if (fallHex.containsTerrain(Terrains.BRIDGE_ELEV)) {
                newElevation = fallHex.terrainLevel(Terrains.BRIDGE_ELEV);
                if (newElevation > crashElevation) {
                    newElevation = 0; // vtol was under bridge already
                }
            }

            int fall = crashElevation - newElevation;
            if (fall == 0) {
                // already on ground, no harm done
                r = new Report(6265);
                r.subject = en.getId();
                vDesc.addElement(r);
                return vDesc;
            }
            // set elevation 1st to avoid multiple crashes
            en.setElevation(newElevation);

            // plummets to ground
            r = new Report(6270);
            r.subject = en.getId();
            r.add(fall);
            vDesc.addElement(r);

            // facing after fall
            String side;
            int table;
            int facing = Compute.d6() - 1;
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

            if (newElevation <= 0) {
                boolean waterFall = fallHex.containsTerrain(Terrains.WATER);
                if (waterFall && fallHex.containsTerrain(Terrains.ICE)) {
                    Roll diceRoll = Compute.rollD6(1);
                    r = new Report(2119);
                    r.subject = en.getId();
                    r.addDesc(en);
                    r.add(diceRoll);
                    r.subject = en.getId();
                    vDesc.add(r);
                    if (diceRoll.getIntValue() > 3) {
                        vDesc.addAll(gameManager.resolveIceBroken(crashPos));
                    } else {
                        waterFall = false; // saved by ice
                    }
                }
                if (waterFall) {
                    // falls into water and is destroyed
                    r = new Report(6275);
                    r.subject = en.getId();
                    vDesc.addElement(r);
                    vDesc.addAll(destroyEntity(en, "Fell into water", false, false, gameManager));
                    // not sure, is this salvageable?
                }
            }

            // calculate damage for hitting the surface
            int damage = (int) Math.round(en.getWeight() / 10.0) * (fall + 1);

            // adjust damage for gravity
            damage = Math.round(damage * gameManager.game.getPlanetaryConditions().getGravity());
            // report falling
            r = new Report(6280);
            r.subject = en.getId();
            r.indent();
            r.addDesc(en);
            r.add(side);
            r.add(damage);
            //r.newlines = 0;
            vDesc.addElement(r);

            en.setFacing((en.getFacing() + (facing)) % 6);

            boolean exploded = false;

            // standard damage loop
            while (damage > 0) {
                int cluster = Math.min(5, damage);
                HitData hit = en.rollHitLocation(ToHitData.HIT_NORMAL, table);
                if ((en instanceof VTOL) && (hit.getLocation() == VTOL.LOC_ROTOR) && rerollRotorHits) {
                    continue;
                }
                hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                int[] isBefore = {en.getInternal(Tank.LOC_FRONT), en.getInternal(Tank.LOC_RIGHT),
                        en.getInternal(Tank.LOC_LEFT), en.getInternal(Tank.LOC_REAR)};
                vDesc.addAll(gameManager.damageEntity(en, hit, cluster));
                int[] isAfter = {en.getInternal(Tank.LOC_FRONT), en.getInternal(Tank.LOC_RIGHT),
                        en.getInternal(Tank.LOC_LEFT), en.getInternal(Tank.LOC_REAR)};
                for (int x = 0; x <= 3; x++) {
                    if (isBefore[x] != isAfter[x]) {
                        exploded = true;
                        break;
                    }
                }
                damage -= cluster;
            }
            if (exploded) {
                r = new Report(6285);
                r.subject = en.getId();
                r.addDesc(en);
                vDesc.addElement(r);
                vDesc.addAll(gameManager.entityActionManager.explodeVTOLorWiGE(en, gameManager));
            }

            // check for location exposure
            vDesc.addAll(gameManager.utilityManager.doSetLocationsExposure(en, fallHex, false, newElevation, gameManager));

        } else {
            en.setElevation(0);// considered landed in the hex.
            // crashes into ground thanks to sideslip
            r = new Report(6290);
            r.subject = en.getId();
            r.addDesc(en);
            vDesc.addElement(r);
            int damage = (int) Math.round(en.getWeight() / 10.0) * (hexesMoved + 1);
            boolean exploded = false;

            // standard damage loop
            while (damage > 0) {
                int cluster = Math.min(5, damage);
                HitData hit = en.rollHitLocation(ToHitData.HIT_NORMAL, impactSide);
                hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                int[] isBefore = {en.getInternal(Tank.LOC_FRONT), en.getInternal(Tank.LOC_RIGHT),
                        en.getInternal(Tank.LOC_LEFT), en.getInternal(Tank.LOC_REAR)};
                vDesc.addAll(gameManager.damageEntity(en, hit, cluster));
                int[] isAfter = {en.getInternal(Tank.LOC_FRONT), en.getInternal(Tank.LOC_RIGHT),
                        en.getInternal(Tank.LOC_LEFT), en.getInternal(Tank.LOC_REAR)};
                for (int x = 0; x <= 3; x++) {
                    if (isBefore[x] != isAfter[x]) {
                        exploded = true;
                        break;
                    }
                }
                damage -= cluster;
            }
            if (exploded) {
                r = new Report(6295);
                r.subject = en.getId();
                r.addDesc(en);
                vDesc.addElement(r);
                vDesc.addAll(gameManager.entityActionManager.explodeVTOLorWiGE(en, gameManager));
            }

        }

        if (gameManager.game.containsMinefield(crashPos)) {
            // may set off any minefields in the hex
            gameManager.environmentalEffectManager.enterMinefield(en, crashPos, 0, true, vDesc, 7, gameManager);
            // it may also clear any minefields that it detonated
            gameManager.environmentalEffectManager.clearDetonatedMines(crashPos, 5, gameManager);
            gameManager.environmentalEffectManager.resetMines(gameManager);
        }

        return vDesc;

    }

    /**
     * Explode a VTOL
     *
     * @param en The <code>VTOL</code> to explode.
     * @param gameManager
     * @return a <code>Vector</code> of reports
     */
    protected Vector<Report> explodeVTOLorWiGE(Tank en, GameManager gameManager) {
        Vector<Report> vDesc = new Vector<>();
        Report r;

        if (en.hasEngine() && en.getEngine().isFusion()) {
            // fusion engine, no effect
            r = new Report(6300);
            r.subject = en.getId();
            vDesc.addElement(r);
        } else {
            Coords pos = en.getPosition();
            Hex hex = gameManager.game.getBoard().getHex(pos);
            if (hex.containsTerrain(Terrains.WOODS) || hex.containsTerrain(Terrains.JUNGLE)) {
                gameManager.ignite(pos, Terrains.FIRE_LVL_NORMAL, vDesc);
            } else {
                gameManager.ignite(pos, Terrains.FIRE_LVL_INFERNO, vDesc);
            }
            vDesc.addAll(destroyEntity(en, "crashed and burned", false, false, gameManager));
        }

        return vDesc;
    }
}
