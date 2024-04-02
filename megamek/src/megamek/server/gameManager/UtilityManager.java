package megamek.server.gameManager;

import megamek.common.*;
import megamek.common.actions.ArtilleryAttackAction;
import megamek.common.actions.TeleMissileAttackAction;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.net.enums.PacketCommand;
import megamek.common.options.OptionsConstants;
import megamek.common.weapons.*;
import megamek.server.Server;
import megamek.server.ServerHelper;
import org.apache.logging.log4j.LogManager;

import java.util.*;

public class UtilityManager {
    /**
     * checks whether a newly set mine should be revealed to players based on
     * LOS. If so, then it reveals the mine
     * @param mf
     * @param layer
     * @param gameManager
     */
    protected void checkForRevealMinefield(Minefield mf, Entity layer, GameManager gameManager) {
        // loop through each team and determine if they can see the mine, then
        // loop through players on team
        // and reveal the mine
        for (Team team : gameManager.game.getTeams()) {
            boolean canSee = false;

            // the players own team can always see the mine
            if (team.equals(gameManager.game.getTeamForPlayer(gameManager.game.getPlayer(mf.getPlayerId())))) {
                canSee = true;
            } else {
                // need to loop through all entities on this team and find the
                // one with the best shot of seeing
                // the mine placement
                int target = Integer.MAX_VALUE;
                Iterator<Entity> entities = gameManager.game.getEntities();
                while (entities.hasNext()) {
                    Entity en = entities.next();
                    // are we on the right team?
                    if (!team.equals(gameManager.game.getTeamForPlayer(en.getOwner()))) {
                        continue;
                    }
                    if (LosEffects.calculateLOS(gameManager.game, en,
                            new HexTarget(mf.getCoords(), Targetable.TYPE_HEX_CLEAR)).canSee()) {
                        target = 0;
                        break;
                    }
                    LosEffects los = LosEffects.calculateLOS(gameManager.game, en, layer);
                    if (los.canSee()) {
                        // TODO : need to add mods
                        ToHitData current = new ToHitData(4, "base");
                        current.append(Compute.getAttackerMovementModifier(gameManager.game, en.getId()));
                        current.append(Compute.getTargetMovementModifier(gameManager.game, layer.getId()));
                        current.append(los.losModifiers(gameManager.game));
                        if (current.getValue() < target) {
                            target = current.getValue();
                        }
                    }
                }

                if (Compute.d6(2) >= target) {
                    canSee = true;
                }
            }
            if (canSee) {
                gameManager.environmentalEffectManager.revealMinefield(team, mf, gameManager);
            }
        }
    }

    /**
     * Explodes a vibrabomb.
     *
     * @param mf The <code>Minefield</code> to explode
     * @param vBoomReport
     * @param entityToExclude
     * @param gameManager
     */
    protected void explodeVibrabomb(Minefield mf, Vector<Report> vBoomReport, Integer entityToExclude, GameManager gameManager) {
        Iterator<Entity> targets = gameManager.game.getEntities(mf.getCoords());
        Report r;

        while (targets.hasNext()) {
            Entity entity = targets.next();

            // Airborne entities wont get hit by the mines...
            if (entity.isAirborne()) {
                continue;
            }

            // check for the OptionsConstants.ADVGRNDMOV_NO_PREMOVE_VIBRA option
            // If it's set, and the target has not yet moved,
            // it doesn't get damaged.
            if (!entity.isDone()
                    && gameManager.game.getOptions().booleanOption(OptionsConstants.ADVGRNDMOV_NO_PREMOVE_VIBRA)) {
                r = new Report(2157);
                r.subject = entity.getId();
                r.add(entity.getShortName(), true);
                vBoomReport.add(r);
                continue;
            }

            // the "currently moving entity" may not be in the same hex, so it needs to be excluded
            if ((entityToExclude != null) && (entity.getId() == entityToExclude)) {
                // report not hitting vibrabomb
                r = new Report(2157);
                r.subject = entity.getId();
                r.add(entity.getShortName(), true);
                vBoomReport.add(r);
                continue;
            } else {
                // report hitting vibrabomb
                r = new Report(2160);
                r.subject = entity.getId();
                r.add(entity.getShortName(), true);
                vBoomReport.add(r);
            }

            int damage = mf.getDensity();
            while (damage > 0) {
                int cur_damage = Math.min(5, damage);
                damage = damage - cur_damage;
                HitData hit = entity.rollHitLocation(Minefield.TO_HIT_TABLE, Minefield.TO_HIT_SIDE);
                vBoomReport.addAll(gameManager.damageEntity(entity, hit, cur_damage));
            }
            Report.addNewline(vBoomReport);

            if (entity instanceof Tank) {
                vBoomReport.addAll(gameManager.vehicleMotiveDamage((Tank) entity,
                        entity.getMotiveSideMod(ToHitData.SIDE_LEFT)));
            }
            vBoomReport.addAll(gameManager.resolvePilotingRolls(entity, true, entity.getPosition(),
                    entity.getPosition()));
            // we need to apply Damage now, in case the entity lost a leg,
            // otherwise it won't get a leg missing mod if it hasn't yet
            // moved and lost a leg, see bug 1071434 for an example
            gameManager.game.resetPSRs(entity);
            entity.applyDamage();
            Report.addNewline(vBoomReport);
            gameManager.entityActionManager.entityUpdate(entity.getId(), gameManager);
        }

        // check the direct reduction of mine
        mf.checkReduction(0, true);
        mf.setDetonated(true);
    }

    /**
     * drowns any units swarming the entity
     *  @param entity The <code>Entity</code> that is being swarmed
     * @param pos    The <code>Coords</code> the entity is at
     * @param gameManager
     */
    protected void drownSwarmer(Entity entity, Coords pos, GameManager gameManager) {
        // Any swarming infantry will be destroyed.
        final int swarmerId = entity.getSwarmAttackerId();
        if (Entity.NONE != swarmerId) {
            final Entity swarmer = gameManager.game.getEntity(swarmerId);
            // Only *platoons* drown while swarming.
            if (!(swarmer instanceof BattleArmor)) {
                swarmer.setSwarmTargetId(Entity.NONE);
                entity.setSwarmAttackerId(Entity.NONE);
                swarmer.setPosition(pos);
                Report r = new Report(2165);
                r.subject = entity.getId();
                r.indent();
                r.add(entity.getShortName(), true);
                gameManager.addReport(r);
                gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity(swarmer, "a watery grave", false, gameManager), gameManager);
                gameManager.entityActionManager.entityUpdate(swarmerId, gameManager);
            }
        }
    }

    /**
     * Checks to see if we may have just washed off infernos. Call after a step
     * which may have done this.
     *  @param entity The <code>Entity</code> that is being checked
     * @param coords The <code>Coords</code> the entity is at
     * @param gameManager
     */
    void checkForWashedInfernos(Entity entity, Coords coords, GameManager gameManager) {
        Hex hex = gameManager.game.getBoard().getHex(coords);
        int waterLevel = hex.terrainLevel(Terrains.WATER);
        // Mech on fire with infernos can wash them off.
        if (!(entity instanceof Mech) || !entity.infernos.isStillBurning()) {
            return;
        }
        // Check if entering depth 2 water or prone in depth 1.
        if ((waterLevel > 0) && (entity.relHeight() < 0)) {
            gameManager.utilityManager.washInferno(entity, coords, gameManager);
        }
    }

    /**
     * Washes off an inferno from a mech and adds it to the (water) hex.
     *  @param entity The <code>Entity</code> that is taking a bath
     * @param coords The <code>Coords</code> the entity is at
     * @param gameManager
     */
    void washInferno(Entity entity, Coords coords, GameManager gameManager) {
        gameManager.game.getBoard().addInfernoTo(coords, InfernoTracker.STANDARD_ROUND, 1);
        entity.infernos.clear();

        // Start a fire in the hex?
        Hex hex = gameManager.game.getBoard().getHex(coords);
        Report r = new Report(2170);
        r.subject = entity.getId();
        r.addDesc(entity);
        if (!hex.containsTerrain(Terrains.FIRE)) {
            r.messageId = 2175;
            gameManager.ignite(coords, Terrains.FIRE_LVL_INFERNO, null);
        }
        gameManager.addReport(r);
    }

    /**
     * Add heat from the movement phase
     * @param gameManager
     */
    public void addMovementHeat(GameManager gameManager) {
        for (Iterator<Entity> i = gameManager.game.getEntities(); i.hasNext(); ) {
            Entity entity = i.next();

            if (entity.hasDamagedRHS()) {
                entity.heatBuildup += 1;
            }

            if ((entity.getMovementMode() == EntityMovementMode.BIPED_SWIM)
                    || (entity.getMovementMode() == EntityMovementMode.QUAD_SWIM)) {
                // UMU heat
                entity.heatBuildup += 1;
                continue;
            }

            // build up heat from movement
            if (entity.isEvading() && !entity.isAero()) {
                entity.heatBuildup += entity.getRunHeat() + 2;
            } else if (entity.moved == EntityMovementType.MOVE_NONE) {
                entity.heatBuildup += entity.getStandingHeat();
            } else if ((entity.moved == EntityMovementType.MOVE_WALK)
                    || (entity.moved == EntityMovementType.MOVE_VTOL_WALK)
                    || (entity.moved == EntityMovementType.MOVE_CAREFUL_STAND)) {
                entity.heatBuildup += entity.getWalkHeat();
            } else if ((entity.moved == EntityMovementType.MOVE_RUN)
                    || (entity.moved == EntityMovementType.MOVE_VTOL_RUN)
                    || (entity.moved == EntityMovementType.MOVE_SKID)) {
                entity.heatBuildup += entity.getRunHeat();
            } else if (entity.moved == EntityMovementType.MOVE_JUMP) {
                entity.heatBuildup += entity.getJumpHeat(entity.delta_distance);
            } else if (entity.moved == EntityMovementType.MOVE_SPRINT
                    || entity.moved == EntityMovementType.MOVE_VTOL_SPRINT) {
                entity.heatBuildup += entity.getSprintHeat();
            }
        }
    }

    /**
     * Set the LocationsExposure of an entity
     *  @param entity
     *            The <code>Entity</code> who's exposure is being set
     * @param hex
     *            The <code>Hex</code> the entity is in
     * @param isJump
     *            a <code>boolean</code> value whether the entity is jumping
     * @param elevation
     * @param gameManager
     */
    public Vector<Report> doSetLocationsExposure(Entity entity, Hex hex,
                                                 boolean isJump, int elevation, GameManager gameManager) {
        Vector<Report> vPhaseReport = new Vector<>();
        if (hex == null) {
            return vPhaseReport;
        }
        if ((hex.terrainLevel(Terrains.WATER) > 0) && !isJump
                && (elevation < 0)) {
            int partialWaterLevel = 1;
            if ((entity instanceof Mech) && entity.isSuperHeavy()) {
                partialWaterLevel = 2;
            }
            if ((entity instanceof Mech) && !entity.isProne()
                    && (hex.terrainLevel(Terrains.WATER) <= partialWaterLevel)) {
                for (int loop = 0; loop < entity.locations(); loop++) {
                    if (gameManager.game.getPlanetaryConditions().isVacuum()
                            || ((entity.getEntityType() & Entity.ETYPE_AERO) == 0 && entity.isSpaceborne())) {
                        entity.setLocationStatus(loop, ILocationExposureStatus.VACUUM);
                    } else {
                        entity.setLocationStatus(loop, ILocationExposureStatus.NORMAL);
                    }
                }
                entity.setLocationStatus(Mech.LOC_RLEG, ILocationExposureStatus.WET);
                entity.setLocationStatus(Mech.LOC_LLEG, ILocationExposureStatus.WET);
                vPhaseReport.addAll(gameManager.breachCheck(entity, Mech.LOC_RLEG, hex));
                vPhaseReport.addAll(gameManager.breachCheck(entity, Mech.LOC_LLEG, hex));
                if (entity instanceof QuadMech) {
                    entity.setLocationStatus(Mech.LOC_RARM, ILocationExposureStatus.WET);
                    entity.setLocationStatus(Mech.LOC_LARM, ILocationExposureStatus.WET);
                    vPhaseReport.addAll(gameManager.breachCheck(entity, Mech.LOC_RARM, hex));
                    vPhaseReport.addAll(gameManager.breachCheck(entity, Mech.LOC_LARM, hex));
                }
                if (entity instanceof TripodMech) {
                    entity.setLocationStatus(Mech.LOC_CLEG, ILocationExposureStatus.WET);
                    vPhaseReport.addAll(gameManager.breachCheck(entity, Mech.LOC_CLEG, hex));
                }
            } else {
                int status = ILocationExposureStatus.WET;
                if (entity.relHeight() >= 0) {
                    status = gameManager.game.getPlanetaryConditions().isVacuum() ?
                            ILocationExposureStatus.VACUUM : ILocationExposureStatus.NORMAL;
                }
                for (int loop = 0; loop < entity.locations(); loop++) {
                    entity.setLocationStatus(loop, status);
                    if (status == ILocationExposureStatus.WET) {
                        vPhaseReport.addAll(gameManager.breachCheck(entity, loop, hex));
                    }
                }
            }
        } else {
            for (int loop = 0; loop < entity.locations(); loop++) {
                if (gameManager.game.getPlanetaryConditions().isVacuum()
                        || ((entity.getEntityType() & Entity.ETYPE_AERO) == 0 && entity.isSpaceborne())) {
                    entity.setLocationStatus(loop, ILocationExposureStatus.VACUUM);
                } else {
                    entity.setLocationStatus(loop, ILocationExposureStatus.NORMAL);
                }
            }
        }
        return vPhaseReport;
    }

    /**
     * Do a roll to avoid pilot damage from g-forces
     *  @param entity       The <code>Entity</code> that should make the PSR
     * @param targetNumber The <code>int</code> to be used for this PSR.
     * @param gameManager
     */
    protected void resistGForce(Entity entity, int targetNumber, GameManager gameManager) {
        // okay, print the info
        Report r = new Report(9330);
        r.subject = entity.getId();
        r.addDesc(entity);
        gameManager.addReport(r);

        // roll
        final Roll diceRoll = Compute.rollD6(2);
        r = new Report(9335);
        r.subject = entity.getId();
        r.add(Integer.toString(targetNumber));
        r.add(diceRoll);

        if (diceRoll.getIntValue() < targetNumber) {
            r.choose(false);
            gameManager.addReport(r);
            gameManager.reportManager.addReport(gameManager.damageCrew(entity, 1), gameManager);
        } else {
            r.choose(true);
            gameManager.addReport(r);
        }
    }

    /**
     * Do a piloting skill check in space to avoid structural damage
     *
     * @param entity The <code>Entity</code> that should make the PSR
     * @param roll   The <code>PilotingRollData</code> to be used for this PSR.
     * @param gameManager
     * @return true if check succeeds, false otherwise.
     */
    protected boolean doSkillCheckInSpace(Entity entity, PilotingRollData roll, GameManager gameManager) {
        if (roll.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            return true;
        }

        // okay, print the info
        Report r = new Report(9320);
        r.subject = entity.getId();
        r.addDesc(entity);
        r.add(roll.getLastPlainDesc(), true);
        gameManager.addReport(r);

        // roll
        final Roll diceRoll = Compute.rollD6(2);
        r = new Report(9325);
        r.subject = entity.getId();
        r.add(roll.getValueAsString());
        r.add(roll.getDesc());
        r.add(diceRoll);
        boolean suc;

        if (diceRoll.getIntValue() < roll.getValue()) {
            r.choose(false);
            gameManager.addReport(r);
            suc = false;
        } else {
            r.choose(true);
            gameManager.addReport(r);
            suc = true;
        }

        return suc;
    }

    /**
     * Do a piloting skill check to take off vertically
     *
     * @param entity The <code>Entity</code> that should make the PSR
     * @param roll   The <code>PilotingRollData</code> to be used for this PSR.
     * @param gameManager
     * @return true if check succeeds, false otherwise.
     */
    protected boolean doVerticalTakeOffCheck(Entity entity, PilotingRollData roll, GameManager gameManager) {

        if (!entity.isAero()) {
            return false;
        }

        IAero a = (IAero) entity;

        if (roll.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            return true;
        }

        // okay, print the info
        Report r = new Report(9320);
        r.subject = entity.getId();
        r.addDesc(entity);
        r.add(roll.getLastPlainDesc(), true);
        gameManager.addReport(r);

        // roll
        final Roll diceRoll = Compute.rollD6(2);
        r = new Report(9321);
        r.subject = entity.getId();
        r.add(roll.getValueAsString());
        r.add(roll.getDesc());
        r.add(diceRoll);
        r.newlines = 0;
        gameManager.addReport(r);
        boolean suc = false;

        if (diceRoll.getIntValue() < roll.getValue()) {
            int mof = roll.getValue() - diceRoll.getIntValue();
            if (mof < 3) {
                r = new Report(9322);
                r.subject = entity.getId();
                gameManager.addReport(r);
                suc = true;
            } else if (mof < 5) {
                PilotingRollData newRoll = entity.getBasePilotingRoll();
                if (Compute.d6(2) >= newRoll.getValue()) {
                    r = new Report(9322);
                    r.subject = entity.getId();
                    gameManager.addReport(r);
                    suc = true;
                } else {
                    r = new Report(9323);
                    r.subject = entity.getId();
                    gameManager.addReport(r);
                    int damage = 20;
                    while (damage > 0) {
                        gameManager.reportManager.addReport(gameManager.damageEntity(entity, entity.rollHitLocation(ToHitData.HIT_NORMAL,
                                ToHitData.SIDE_REAR), Math.min(5, damage)), gameManager);
                        damage -= 5;
                    }
                }
            } else if (mof < 6) {
                r = new Report(9323);
                r.subject = entity.getId();
                gameManager.addReport(r);
                int damage = 50;
                while (damage > 0) {
                    gameManager.reportManager.addReport(gameManager.damageEntity(entity, entity.rollHitLocation(ToHitData.HIT_NORMAL,
                            ToHitData.SIDE_REAR), Math.min(5, damage)), gameManager);
                    damage -= 5;
                }
            } else {
                r = new Report(9323);
                r.subject = entity.getId();
                gameManager.addReport(r);
                int damage = 100;
                while (damage > 0) {
                    gameManager.reportManager.addReport(gameManager.damageEntity(entity, entity.rollHitLocation(ToHitData.HIT_NORMAL,
                            ToHitData.SIDE_REAR), Math.min(5, damage)), gameManager);
                    damage -= 5;
                }
            }
            a.setGearHit(true);
            r = new Report(9125);
            r.subject = entity.getId();
            gameManager.addReport(r);
        } else {
            r = new Report(9322);
            r.subject = entity.getId();
            gameManager.addReport(r);
            suc = true;
        }

        return suc;
    }

    /**
     * Do a piloting skill check in space to do a successful maneuver Failure
     * means moving forward half velocity
     *
     * @param entity The <code>Entity</code> that should make the PSR
     * @param roll   The <code>PilotingRollData</code> to be used for this PSR.
     * @param gameManager
     * @return true if check succeeds, false otherwise.
     */
    protected boolean doSkillCheckManeuver(Entity entity, PilotingRollData roll, GameManager gameManager) {
        if (roll.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            return true;
        }

        // okay, print the info
        Report r = new Report(9600);
        r.subject = entity.getId();
        r.addDesc(entity);
        r.add(roll.getLastPlainDesc(), true);
        gameManager.addReport(r);

        // roll
        final Roll diceRoll = Compute.rollD6(2);
        r = new Report(9601);
        r.subject = entity.getId();
        r.add(roll.getValueAsString());
        r.add(roll.getDesc());
        r.add(diceRoll);
        boolean suc;

        if (diceRoll.getIntValue() < roll.getValue()) {
            r.choose(false);
            gameManager.addReport(r);
            suc = false;
        } else {
            r.choose(true);
            gameManager.addReport(r);
            suc = true;
        }

        return suc;
    }

    /**
     * Do a piloting skill check while standing still (during the movement
     * phase).
     *
     * @param entity The <code>Entity</code> that should make the PSR
     * @param roll   The <code>PilotingRollData</code> to be used for this PSR.
     * @param gameManager
     * @return true if check succeeds, false otherwise.
     */
    protected boolean doSkillCheckInPlace(Entity entity, PilotingRollData roll, GameManager gameManager) {
        if (roll.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            return true;
        }

        if (entity.isProne()) {
            return true;
        }

        // okay, print the info
        Report r = new Report(2180);
        r.subject = entity.getId();
        r.addDesc(entity);
        r.add(roll.getLastPlainDesc(), true);
        gameManager.addReport(r);

        // roll
        final Roll diceRoll = entity.getCrew().rollPilotingSkill();
        r = new Report(2185);
        r.subject = entity.getId();
        r.add(roll.getValueAsString());
        r.add(roll.getDesc());
        r.add(diceRoll);
        boolean suc;

        if (diceRoll.getIntValue() < roll.getValue()) {
            r.choose(false);
            gameManager.addReport(r);
            if ((entity instanceof Mech)
                    && gameManager.game.getOptions().booleanOption(
                    OptionsConstants.ADVGRNDMOV_TACOPS_FALLING_EXPANDED)
                    && (entity.getCrew().getPiloting() < 6)
                    && !entity.isHullDown() && entity.canGoHullDown()) {
                if ((entity.getCrew().getPiloting() > 1) && ((roll.getValue() - diceRoll.getIntValue()) < 2)) {
                    entity.setHullDown(true);
                } else if ((entity.getCrew().getPiloting() <= 1)
                        && ((roll.getValue() - diceRoll.getIntValue()) < 3)) {
                    entity.setHullDown(true);
                }
            }
            if (!entity.isHullDown()
                    || (entity.isHullDown() && !entity.canGoHullDown())) {
                gameManager.reportManager.addReport(gameManager.doEntityFall(entity, roll), gameManager);
            } else {
                ServerHelper.sinkToBottom(entity);

                r = new Report(2317);
                r.subject = entity.getId();
                r.add(entity.getDisplayName());
                gameManager.addReport(r);
            }

            suc = false;
            // failed a PSR, possibly check for engine stalling
            entity.doCheckEngineStallRoll(gameManager.vPhaseReport);
        } else {
            r.choose(true);
            gameManager.addReport(r);
            suc = true;
        }

        return suc;
    }

    /**
     * Do a Piloting Skill check to dislodge swarming infantry.
     *
     * @param entity The <code>Entity</code> that is doing the dislodging.
     * @param roll   The <code>PilotingRollData</code> for this PSR.
     * @param curPos The <code>Coords</code> the entity is at.
     * @param gameManager
     * @return <code>true</code> if the dislodging is successful.
     */
    protected boolean doDislodgeSwarmerSkillCheck(Entity entity, PilotingRollData roll, Coords curPos, GameManager gameManager) {
        // okay, print the info
        Report r = new Report(2180);
        r.subject = entity.getId();
        r.addDesc(entity);
        r.add(roll.getLastPlainDesc(), true);
        gameManager.addReport(r);

        // roll
        final Roll diceRoll = Compute.rollD6(2);
        r = new Report(2190);
        r.subject = entity.getId();
        r.add(roll.getValueAsString());
        r.add(roll.getDesc());
        r.add(diceRoll);

        if (diceRoll.getIntValue() < roll.getValue()) {
            r.choose(false);
            gameManager.addReport(r);
            // failed a PSR, possibly check for engine stalling
            entity.doCheckEngineStallRoll(gameManager.vPhaseReport);
            return false;
        }
        // Dislodged swarmers don't get turns.
        int swarmerId = entity.getSwarmAttackerId();
        final Entity swarmer = gameManager.game.getEntity(swarmerId);
        if (!swarmer.isDone()) {
            gameManager.game.removeTurnFor(swarmer);
            swarmer.setDone(true);
            gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));
        }

        // Update the report and cause a fall.
        r.choose(true);
        gameManager.addReport(r);
        entity.setPosition(curPos);
        gameManager.reportManager.addReport(gameManager.utilityManager.doEntityFallsInto(entity, curPos, roll, false, gameManager), gameManager);
        return true;
    }

    /**
     * Do a piloting skill check while moving.
     *
     * @param entity          - the <code>Entity</code> that must roll.
     * @param entityElevation The elevation of the supplied Entity above the surface of the
     *                        src hex. This is necessary as the state of the Entity may
     *                        represent the elevation of the entity about the surface of the
     *                        destination hex.
     * @param src             - the <code>Coords</code> the entity is moving from.
     * @param dest            - the <code>Coords</code> the entity is moving to. This value
     *                        can be the same as src for in-place checks.
     * @param roll            - the <code>PilotingRollData</code> that is causing this
     *                        check.
     * @param isFallRoll      - a <code>boolean</code> flag that indicates that failure will
     *                        result in a fall or not. Falls will be processed.
     * @param gameManager
     * @return Margin of Failure if the pilot fails the skill check, 0 if they
     * pass.
     */
    protected int doSkillCheckWhileMoving(Entity entity, int entityElevation,
                                          Coords src, Coords dest, PilotingRollData roll, boolean isFallRoll, GameManager gameManager) {
        boolean fallsInPlace;

        // Start the info for this roll.
        Report r = new Report(1210);
        r.subject = entity.getId();
        r.addDesc(entity);

        // Will the entity fall in the source or destination hex?
        if (src.equals(dest)) {
            fallsInPlace = true;
            r.messageId = 2195;
            r.add(src.getBoardNum(), true);
        } else {
            fallsInPlace = false;
            r.messageId = 2200;
            r.add(src.getBoardNum(), true);
            r.add(dest.getBoardNum(), true);
        }

        // Finish the info.
        r.add(roll.getLastPlainDesc(), true);
        gameManager.addReport(r);

        // roll
        final Roll diceRoll = entity.getCrew().rollPilotingSkill();
        r = new Report(2185);
        r.subject = entity.getId();
        r.add(roll.getValueAsString());
        r.add(roll.getDesc());
        r.add(diceRoll);

        if (diceRoll.getIntValue() < roll.getValue()) {
            // Does failing the PSR result in a fall.
            if (isFallRoll && entity.canFall()) {
                r.choose(false);
                gameManager.addReport(r);
                gameManager.reportManager.addReport(gameManager.utilityManager.doEntityFallsInto(entity, entityElevation,
                        fallsInPlace ? dest : src, fallsInPlace ? src : dest,
                        roll, true, gameManager), gameManager);
            } else {
                r.messageId = 2190;
                r.choose(false);
                gameManager.addReport(r);
                entity.setPosition(fallsInPlace ? src : dest);
            }
            // failed a PSR, possibly check for engine stalling
            entity.doCheckEngineStallRoll(gameManager.vPhaseReport);
            return roll.getValue() - diceRoll.getIntValue();
        }
        r.choose(true);
        r.newlines = 2;
        gameManager.addReport(r);
        return 0;
    }

    /**
     * Process a fall when the source and destination hexes are the same.
     * Depending on the elevations of the hexes, the Entity could land in the
     * source or destination hexes. Check for any conflicts and resolve them.
     * Deal damage to faller. Note: the elevation of the entity is used to
     * determine fall distance, so it is important to ensure the Entity's
     * elevation is correct.
     *  @param entity    The <code>Entity</code> that is falling.
     * @param src       The <code>Coords</code> of the source hex.
     * @param roll      The <code>PilotingRollData</code> to be used for PSRs induced
     *                  by the falling.
     * @param causeAffa The <code>boolean</code> value whether this fall should be able
     * @param gameManager
     */
    protected Vector<Report> doEntityFallsInto(Entity entity, Coords src,
                                               PilotingRollData roll, boolean causeAffa, GameManager gameManager) {
        return gameManager.utilityManager.doEntityFallsInto(entity, entity.getElevation(), src, src, roll, causeAffa, gameManager);
    }

    /**
     * Process a fall when moving from the source hex to the destination hex.
     * Depending on the elevations of the hexes, the Entity could land in the
     * source or destination hexes. Check for any conflicts and resolve them.
     * Deal damage to faller. Note: the elevation of the entity is used to
     * determine fall distance, so it is important to ensure the Entity's
     * elevation is correct.
     *  @param entity             The <code>Entity</code> that is falling.
     * @param entitySrcElevation The elevation of the supplied Entity above the surface of the
     *                           src hex. This is necessary as the state of the Entity may
     *                           represent the elevation of the entity about the surface of the
     *                           destination hex.
     * @param src                The <code>Coords</code> of the source hex.
     * @param dest               The <code>Coords</code> of the destination hex.
     * @param roll               The <code>PilotingRollData</code> to be used for PSRs induced
     *                           by the falling.
     * @param causeAffa          The <code>boolean</code> value whether this fall should be able
     * @param gameManager
     */
    protected Vector<Report> doEntityFallsInto(Entity entity, int entitySrcElevation, Coords src,
                                               Coords dest, PilotingRollData roll, boolean causeAffa, GameManager gameManager) {
        return gameManager.utilityManager.doEntityFallsInto(entity, entitySrcElevation, src, dest, roll, causeAffa, 0, gameManager);
    }

    /**
     * Process a fall when moving from the source hex to the destination hex.
     * Depending on the elevations of the hexes, the Entity could land in the
     * source or destination hexes. Check for any conflicts and resolve them.
     * Deal damage to faller.
     *  @param entity             The <code>Entity</code> that is falling.
     * @param entitySrcElevation The elevation of the supplied Entity above the surface of the
     *                           src hex. This is necessary as the state of the Entity may
     *                           represent the elevation of the entity about the surface of the
     *                           destination hex.
     * @param origSrc            The <code>Coords</code> of the original source hex.
     * @param origDest           The <code>Coords</code> of the original destination hex.
     * @param roll               The <code>PilotingRollData</code> to be used for PSRs induced
     *                           by the falling.
     * @param causeAffa          The <code>boolean</code> value whether this fall should be able
     *                           to cause an accidental fall from above
     * @param fallReduction      An integer value to reduce the fall distance by
     * @param gameManager
     */
    protected Vector<Report> doEntityFallsInto(Entity entity, int entitySrcElevation, Coords origSrc,
                                               Coords origDest, PilotingRollData roll,
                                               boolean causeAffa, int fallReduction, GameManager gameManager) {
        Vector<Report> vPhaseReport = new Vector<>();
        Hex srcHex = gameManager.game.getBoard().getHex(origSrc);
        Hex destHex = gameManager.game.getBoard().getHex(origDest);
        Coords src, dest;
        // We need to fall into the lower of the two hexes, TW pg 68
        if (srcHex.getLevel() < destHex.getLevel()) {
            Hex swapHex = destHex;
            destHex = srcHex;
            srcHex = swapHex;
            src = origDest;
            dest = origSrc;
        } else {
            src = origSrc;
            dest = origDest;
        }
        final int srcHeightAboveFloor = entitySrcElevation + srcHex.depth(false);
        int fallElevation = Math.abs((srcHex.floor() + srcHeightAboveFloor)
                - (destHex.containsTerrain(Terrains.ICE) ? destHex.getLevel() : destHex.floor()))
                - fallReduction;
        if (destHex.containsTerrain(Terrains.BLDG_ELEV)) {
            fallElevation -= destHex.terrainLevel(Terrains.BLDG_ELEV);
        }
        if (destHex.containsTerrain(Terrains.BLDG_BASEMENT_TYPE)) {
            if (entity.getElevation() == 0) { // floor 0 falling into basement
                fallElevation = destHex.depth(true);
            }
        }

        int direction;
        if (src.equals(dest)) {
            direction = Compute.d6() - 1;
        } else {
            direction = src.direction(dest);
        }
        Report r;
        // check entity in target hex
        Entity affaTarget = gameManager.game.getAffaTarget(dest, entity);
        // falling mech falls
        r = new Report(2205);
        r.subject = entity.getId();
        r.addDesc(entity);
        r.add(fallElevation);
        r.add(dest.getBoardNum(), true);
        r.newlines = 0;

        // if hex was empty, deal damage and we're done
        if (affaTarget == null) {
            r.newlines = 1;
            vPhaseReport.add(r);
            // If we rolled for the direction, we want to use that for the fall
            if (src.equals(dest)) {
                vPhaseReport.addAll(gameManager.doEntityFall(entity, dest, fallElevation,
                        direction, roll, false, srcHex.hasCliffTopTowards(destHex)));
            } else {
                // Otherwise, we'll roll for the direction after the fall
                vPhaseReport.addAll(gameManager.doEntityFall(entity, dest, fallElevation, roll));
            }

            return vPhaseReport;
        }
        vPhaseReport.add(r);

        // hmmm... somebody there... problems.
        if ((fallElevation >= 2) && causeAffa) {
            // accidental fall from above: havoc!
            r = new Report(2210);
            r.subject = entity.getId();
            r.addDesc(affaTarget);
            vPhaseReport.add(r);

            // determine to-hit number
            ToHitData toHit = new ToHitData(7, "base");
            if ((affaTarget instanceof Tank) || (affaTarget instanceof Dropship)) {
                toHit = new ToHitData(TargetRoll.AUTOMATIC_SUCCESS, "Target is a Tank");
            } else {
                toHit.append(Compute.getTargetMovementModifier(gameManager.game, affaTarget.getId()));
                toHit.append(Compute.getTargetTerrainModifier(gameManager.game, affaTarget));
            }

            if (toHit.getValue() != TargetRoll.AUTOMATIC_FAIL) {
                // collision roll
                final Roll diceRoll = Compute.rollD6(2);

                if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
                    r = new Report(2212);
                    r.add(toHit.getValue());
                } else {
                    r = new Report(2215);
                    r.subject = entity.getId();
                    r.add(toHit.getValue());
                    r.add(diceRoll);
                    r.newlines = 0;
                }

                r.indent();
                vPhaseReport.add(r);

                if (diceRoll.getIntValue() >= toHit.getValue()) {
                    // deal damage to target
                    int damage = Compute.getAffaDamageFor(entity);
                    r = new Report(2220);
                    r.subject = affaTarget.getId();
                    r.addDesc(affaTarget);
                    r.add(damage);
                    vPhaseReport.add(r);
                    while (damage > 0) {
                        int cluster = Math.min(5, damage);
                        HitData hit = affaTarget.rollHitLocation(ToHitData.HIT_PUNCH, ToHitData.SIDE_FRONT);
                        hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                        vPhaseReport.addAll(gameManager.damageEntity(affaTarget, hit, cluster));
                        damage -= cluster;
                    }

                    // attacker falls as normal, on his back
                    // only given a modifier, so flesh out into a full piloting
                    // roll
                    PilotingRollData pilotRoll = entity.getBasePilotingRoll();
                    pilotRoll.append(roll);
                    vPhaseReport.addAll(gameManager.doEntityFall(entity, dest,
                            fallElevation, 3, pilotRoll, false, false));
                    vPhaseReport.addAll(gameManager.utilityManager.doEntityDisplacementMinefieldCheck(
                            entity, src, dest, entity.getElevation(), gameManager));

                    // defender pushed away, or destroyed, if there is a
                    // stacking violation
                    Entity violation = Compute.stackingViolation(gameManager.game, entity.getId(), dest, entity.climbMode());
                    if (violation != null) {
                        PilotingRollData prd = new PilotingRollData(violation.getId(), 2,
                                "fallen on");
                        if (violation instanceof Dropship) {
                            violation = entity;
                            prd = null;
                        }
                        Coords targetDest = Compute.getValidDisplacement(gameManager.game, violation.getId(),
                                dest, direction);
                        if (targetDest != null) {
                            vPhaseReport.addAll(gameManager.utilityManager.doEntityDisplacement(violation, dest, targetDest, prd, gameManager));
                            // Update the violating entity's position on the
                            // client.
                            gameManager.entityActionManager.entityUpdate(violation.getId(), gameManager);
                        } else {
                            // ack! automatic death! Tanks
                            // suffer an ammo/power plant hit.
                            // TODO : a Mech suffers a Head Blown Off crit.
                            vPhaseReport.addAll(gameManager.entityActionManager.destroyEntity(violation, "impossible displacement",
                                    violation instanceof Mech, violation instanceof Mech, gameManager));
                        }
                    }
                    return vPhaseReport;
                }
            } else {
                // automatic miss
                r = new Report(2213);
                r.add(toHit.getDesc());
                vPhaseReport.add(r);
            }
            // ok, we missed, let's fall into a valid other hex and not cause an
            // AFFA while doing so
            Coords targetDest = Compute.getValidDisplacement(gameManager.game, entity.getId(), dest, direction);
            if (targetDest != null) {
                vPhaseReport.addAll(doEntityFallsInto(entity, entitySrcElevation, src, targetDest,
                        new PilotingRollData(entity.getId(),
                                TargetRoll.IMPOSSIBLE,
                                "pushed off a cliff"),
                        false, gameManager));
                // Update the entity's position on the client.
                gameManager.entityActionManager.entityUpdate(entity.getId(), gameManager);
            } else {
                // ack! automatic death! Tanks
                // suffer an ammo/power plant hit.
                // TODO : a Mech suffers a Head Blown Off crit.
                vPhaseReport.addAll(gameManager.entityActionManager.destroyEntity(entity,
                        "impossible displacement", entity instanceof Mech, entity instanceof Mech, gameManager));
            }
        } else {
            // damage as normal
            vPhaseReport.addAll(gameManager.doEntityFall(entity, dest, fallElevation, roll));
            Entity violation = Compute.stackingViolation(gameManager.game, entity.getId(), dest, entity.climbMode());
            if (violation != null) {
                PilotingRollData prd = new PilotingRollData(violation.getId(), 0, "domino effect");
                if (violation instanceof Dropship) {
                    violation = entity;
                    prd = null;
                }
                // target gets displaced, because of low elevation
                Coords targetDest = Compute.getValidDisplacement(gameManager.game, violation.getId(), dest, direction);
                vPhaseReport.addAll(gameManager.utilityManager.doEntityDisplacement(violation, dest, targetDest, prd, gameManager));
                // Update the violating entity's position on the client.
                if (!gameManager.game.getOutOfGameEntitiesVector().contains(violation)) {
                    gameManager.entityActionManager.entityUpdate(violation.getId(), gameManager);
                }
            }
        }
        return vPhaseReport;
    }

    /**
     * Displace a unit in the direction specified. The unit moves in that
     * direction, and the piloting skill roll is used to determine if it falls.
     * The roll may be unnecessary as certain situations indicate an automatic
     * fall. Rolls are added to the piloting roll list.
     * @param entity
     * @param src
     * @param dest
     * @param roll
     * @param gameManager
     */
    protected Vector<Report> doEntityDisplacement(Entity entity, Coords src,
                                                  Coords dest, PilotingRollData roll, GameManager gameManager) {
        Vector<Report> vPhaseReport = new Vector<>();
        Report r;
        if (!gameManager.game.getBoard().contains(dest)) {
            // set position anyway, for pushes moving through, stuff like that
            entity.setPosition(dest);
            if (!entity.isDoomed()) {
                // Make sure there aren't any specific entity turns for entity
                int turnsRemoved = gameManager.game.removeSpecificEntityTurnsFor(entity);
                // May need to remove a turn for this Entity
                if (gameManager.game.getPhase().isMovement() && !entity.isDone() && (turnsRemoved == 0)) {
                    gameManager.game.removeTurnFor(entity);
                    gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));
                } else if (turnsRemoved > 0) {
                    gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));
                }
                gameManager.game.removeEntity(entity.getId(), IEntityRemovalConditions.REMOVE_PUSHED);
                gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(entity.getId(), IEntityRemovalConditions.REMOVE_PUSHED, gameManager));
                // entity forced from the field
                r = new Report(2230);
                r.subject = entity.getId();
                r.addDesc(entity);
                vPhaseReport.add(r);
                // TODO : remove passengers and swarmers.
            }
            return vPhaseReport;
        }
        final Hex srcHex = gameManager.game.getBoard().getHex(src);
        final Hex destHex = gameManager.game.getBoard().getHex(dest);
        final int direction = src.direction(dest);

        // Handle null hexes.
        if ((srcHex == null) || (destHex == null)) {
            LogManager.getLogger().error("Can not displace " + entity.getShortName()
                    + " from " + src + " to " + dest + ".");
            return vPhaseReport;
        }
        int bldgElev = destHex.containsTerrain(Terrains.BLDG_ELEV)
                ? destHex.terrainLevel(Terrains.BLDG_ELEV) : 0;
        int fallElevation = srcHex.getLevel() + entity.getElevation()
                - (destHex.getLevel() + bldgElev);
        if (fallElevation > 1) {
            if (roll == null) {
                roll = entity.getBasePilotingRoll();
            }
            if (!(entity.isAirborneVTOLorWIGE())) {
                vPhaseReport.addAll(doEntityFallsInto(entity, entity.getElevation(), src, dest,
                        roll, true, gameManager));
            } else {
                entity.setPosition(dest);
            }
            return vPhaseReport;
        }
        // unstick the entity if it was stuck in swamp
        boolean wasStuck = entity.isStuck();
        entity.setStuck(false);
        int oldElev = entity.getElevation();
        // move the entity into the new location gently
        entity.setPosition(dest);
        entity.setElevation(entity.calcElevation(srcHex, destHex));
        Building bldg = gameManager.game.getBoard().getBuildingAt(dest);
        if (bldg != null) {
            if (destHex.terrainLevel(Terrains.BLDG_ELEV) > oldElev) {
                // whoops, into the building we go
                gameManager.environmentalEffectManager.passBuildingWall(entity, gameManager.game.getBoard().getBuildingAt(dest), src, dest, 1,
                        "displaced into",
                        Math.abs(entity.getFacing() - src.direction(dest)) == 3,
                        entity.moved, true, gameManager);
            }
            gameManager.environmentalEffectManager.checkBuildingCollapseWhileMoving(bldg, entity, dest, gameManager);
        }

        ServerHelper.checkAndApplyMagmaCrust(destHex, entity.getElevation(), entity, dest, false, vPhaseReport, gameManager);
        ServerHelper.checkEnteringMagma(destHex, entity.getElevation(), entity, gameManager);

        Entity violation = Compute.stackingViolation(gameManager.game, entity.getId(), dest, entity.climbMode());
        if (violation == null) {
            // move and roll normally
            r = new Report(2235);
            r.indent();
            r.subject = entity.getId();
            r.addDesc(entity);
            r.add(dest.getBoardNum(), true);
        } else {
            // domino effect: move & displace target
            r = new Report(2240);
            r.indent();
            r.subject = entity.getId();
            r.addDesc(entity);
            r.add(dest.getBoardNum(), true);
            r.addDesc(violation);
        }
        vPhaseReport.add(r);
        // trigger any special things for moving to the new hex
        vPhaseReport.addAll(gameManager.utilityManager.doEntityDisplacementMinefieldCheck(entity, src, dest, entity.getElevation(), gameManager));
        vPhaseReport.addAll(doSetLocationsExposure(entity, destHex, false, entity.getElevation(), gameManager));
        if (destHex.containsTerrain(Terrains.BLDG_ELEV)
                && (entity.getElevation() == 0)) {
            bldg = gameManager.game.getBoard().getBuildingAt(dest);
            if (bldg.rollBasement(dest, gameManager.game.getBoard(), vPhaseReport)) {
                gameManager.communicationManager.sendChangedHex(dest, gameManager);
                Vector<Building> buildings = new Vector<>();
                buildings.add(bldg);
                gameManager.communicationManager.sendChangedBuildings(buildings, gameManager);
            }
        }

        // mechs that were stuck will automatically fall in their new hex
        if (wasStuck && entity.canFall()) {
            if (roll == null) {
                roll = entity.getBasePilotingRoll();
            }
            vPhaseReport.addAll(gameManager.doEntityFall(entity, dest, 0, roll));
        }
        // check bog-down conditions
        vPhaseReport.addAll(gameManager.utilityManager.doEntityDisplacementBogDownCheck(entity, dest, entity.getElevation(), gameManager));

        if (roll != null) {
            if (entity.canFall()) {
                gameManager.game.addPSR(roll);
            } else if ((entity instanceof LandAirMech) && entity.isAirborneVTOLorWIGE()) {
                gameManager.game.addControlRoll(roll);
            }
        }

        int waterDepth = destHex.terrainLevel(Terrains.WATER);

        if (destHex.containsTerrain(Terrains.ICE) && destHex.containsTerrain(Terrains.WATER)) {
            if (!(entity instanceof Infantry)) {
                Roll diceRoll = Compute.rollD6(1);
                r = new Report(2118);
                r.addDesc(entity);
                r.add(diceRoll);
                r.subject = entity.getId();
                vPhaseReport.add(r);

                if (diceRoll.getIntValue() == 6) {
                    vPhaseReport.addAll(gameManager.entityActionManager.resolveIceBroken(dest, gameManager));
                }
            }
        }
        // Falling into water instantly destroys most non-mechs
        else if ((waterDepth > 0)
                && !(entity instanceof Mech)
                && !(entity instanceof Protomech)
                && !((entity.getRunMP() > 0) && (entity.getMovementMode() == EntityMovementMode.HOVER))
                && (entity.getMovementMode() != EntityMovementMode.HYDROFOIL)
                && (entity.getMovementMode() != EntityMovementMode.NAVAL)
                && (entity.getMovementMode() != EntityMovementMode.SUBMARINE)
                && (entity.getMovementMode() != EntityMovementMode.WIGE)
                && (entity.getMovementMode() != EntityMovementMode.INF_UMU)) {
            vPhaseReport.addAll(gameManager.entityActionManager.destroyEntity(entity, "a watery grave", false, gameManager));
        } else if ((waterDepth > 0)
                && !(entity.getMovementMode() == EntityMovementMode.HOVER)) {
            PilotingRollData waterRoll = entity.checkWaterMove(waterDepth, entity.moved);
            if (waterRoll.getValue() != TargetRoll.CHECK_FALSE) {
                doSkillCheckInPlace(entity, waterRoll, gameManager);
            }
        }
        // Update the entity's position on the client.
        gameManager.entityActionManager.entityUpdate(entity.getId(), gameManager);

        if (violation != null) {
            // Can the violating unit move out of the way?
            // if the direction comes from a side, Entity didn't jump, and it
            // has MP left to use, it can try to move.
            MovePath stepForward = new MovePath(gameManager.game, violation);
            MovePath stepBackwards = new MovePath(gameManager.game, violation);
            stepForward.addStep(MovePath.MoveStepType.FORWARDS);
            stepBackwards.addStep(MovePath.MoveStepType.BACKWARDS);
            stepForward.compile(gameManager.getGame(), violation, false);
            stepBackwards.compile(gameManager.getGame(), violation, false);
            if ((direction != violation.getFacing())
                    && (direction != ((violation.getFacing() + 3) % 6))
                    && !entity.getIsJumpingNow()
                    && (stepForward.isMoveLegal() || stepBackwards.isMoveLegal())) {
                // First, we need to make a PSR to see if we can step out
                Roll diceRoll = Compute.rollD6(2);
                if (roll == null) {
                    roll = entity.getBasePilotingRoll();
                }

                r = new Report(2351);
                r.indent(2);
                r.subject = violation.getId();
                r.addDesc(violation);
                r.add(roll);
                r.add(diceRoll);
                vPhaseReport.add(r);

                if (diceRoll.getIntValue() < roll.getValue()) {
                    r.choose(false);
                    Vector<Report> newReports = doEntityDisplacement(violation,
                            dest, dest.translated(direction),
                            new PilotingRollData(violation.getId(),
                                    TargetRoll.AUTOMATIC_FAIL,
                                    "failed to step out of a domino effect"), gameManager);
                    for (Report newReport : newReports) {
                        newReport.indent(3);
                    }
                    vPhaseReport.addAll(newReports);
                } else {
                    r.choose(true);
                    gameManager.communicationManager.sendDominoEffectCFR(violation, gameManager);
                    synchronized (gameManager.cfrPacketQueue) {
                        try {
                            gameManager.cfrPacketQueue.wait();
                        } catch (Exception ignored) {

                        }

                        if (!gameManager.cfrPacketQueue.isEmpty()) {
                            Server.ReceivedPacket rp = gameManager.cfrPacketQueue.poll();
                            final PacketCommand cfrType = (PacketCommand) rp.getPacket().getObject(0);
                            // Make sure we got the right type of response
                            if (!cfrType.isCFRDominoEffect()) {
                                LogManager.getLogger().error("Excepted a CFR_DOMINO_EFFECT CFR packet, received: " + cfrType);
                                throw new IllegalStateException();
                            }
                            MovePath mp = (MovePath) rp.getPacket().getData()[1];
                            // Move based on the feedback
                            if (mp != null) {
                                mp.setGame(gameManager.getGame());
                                mp.setEntity(violation);
                                // Report
                                r = new Report(2352);
                                r.indent(3);
                                r.subject = violation.getId();
                                r.addDesc(violation);
                                r.choose(mp.getLastStep().getType() != MovePath.MoveStepType.FORWARDS);
                                r.add(mp.getLastStep().getPosition().getBoardNum());
                                vPhaseReport.add(r);
                                // Move unit
                                violation.setPosition(mp.getFinalCoords());
                                violation.mpUsed += mp.getMpUsed();
                                violation.moved = mp.getLastStepMovementType();
                            } else { // User decided to do nothing
                                r = new Report(2358);
                                r.indent(3);
                                r.subject = violation.getId();
                                r.addDesc(violation);
                                vPhaseReport.add(r);
                                vPhaseReport.addAll(doEntityDisplacement(violation, dest,
                                        dest.translated(direction), null, gameManager));
                            }
                        } else { // If no responses, treat as no action
                            vPhaseReport.addAll(doEntityDisplacement(violation,
                                    dest, dest.translated(direction),
                                    new PilotingRollData(violation.getId(), 0,
                                            "domino effect"), gameManager));
                        }
                    }
                }
            } else { // Nope
                r = new Report(2359);
                r.indent(2);
                r.subject = violation.getId();
                r.addDesc(violation);
                vPhaseReport.add(r);
                vPhaseReport.addAll(doEntityDisplacement(violation, dest, dest.translated(direction),
                        new PilotingRollData(violation.getId(), 0, "domino effect"), gameManager));

            }
            // Update the violating entity's position on the client,
            // if it didn't get displaced off the board.
            if (!gameManager.game.isOutOfGame(violation)) {
                gameManager.entityActionManager.entityUpdate(violation.getId(), gameManager);
            }
        }
        return vPhaseReport;
    }

    protected Vector<Report> doEntityDisplacementMinefieldCheck(Entity entity, Coords src,
                                                                Coords dest, int elev, GameManager gameManager) {
        Vector<Report> vPhaseReport = new Vector<>();
        boolean boom = gameManager.checkVibrabombs(entity, dest, true, vPhaseReport);
        if (gameManager.game.containsMinefield(dest)) {
            boom = gameManager.environmentalEffectManager.enterMinefield(entity, dest, elev, true, vPhaseReport, gameManager) || boom;
        }

        if (boom) {
            gameManager.environmentalEffectManager.resetMines(gameManager);
        }

        return vPhaseReport;
    }

    protected Vector<Report> doEntityDisplacementBogDownCheck(Entity entity, Coords c, int elev, GameManager gameManager) {
        Vector<Report> vReport = new Vector<>();
        Report r;
        Hex destHex = gameManager.game.getBoard().getHex(c);
        int bgMod = destHex.getBogDownModifier(entity.getMovementMode(),
                entity instanceof LargeSupportTank);
        if ((bgMod != TargetRoll.AUTOMATIC_SUCCESS)
                && !entity.getMovementMode().isHoverOrWiGE()
                && (elev == 0)) {
            PilotingRollData roll = entity.getBasePilotingRoll();
            roll.append(new PilotingRollData(entity.getId(), bgMod, "avoid bogging down"));
            int stuckroll = Compute.d6(2);
            // A DFA-ing mech is "displaced" into the target hex. Since it
            // must be jumping, it will automatically be bogged down
            if (stuckroll < roll.getValue() || entity.isMakingDfa()) {
                entity.setStuck(true);
                r = new Report(2081);
                r.subject = entity.getId();
                r.add(entity.getDisplayName(), true);
                vReport.add(r);
                // check for quicksand
                vReport.addAll(gameManager.checkQuickSand(c));
            }
        }
        return vReport;
    }

    /**
     * Determine which telemissile attack actions could be affected by AMS, and assign AMS to those
     * attacks.
     * @param taa
     * @param gameManager
     */
    public void assignTeleMissileAMS(final TeleMissileAttackAction taa, GameManager gameManager) {
        final Entity target = (taa.getTargetType() == Targetable.TYPE_ENTITY)
                ? (Entity) taa.getTarget(gameManager.game) : null;

        // If a telemissile is still on the board and its original target is not, just return.
        if (target == null) {
            LogManager.getLogger().info("Telemissile has no target. AMS not assigned.");
            return;
        }

        target.assignTMAMS(taa);
    }

    /**
     * Determine which missile attack actions could be affected by AMS, and
     * assign AMS (and APDS) to those attacks.
     * @param gameManager
     */
    public void assignAMS(GameManager gameManager) {
        // Get all of the coords that would be protected by APDS
        Hashtable<Coords, List<Mounted>> apdsCoords = gameManager.utilityManager.getAPDSProtectedCoords(gameManager);
        // Map target to a list of missile attacks directed at it
        Hashtable<Entity, Vector<WeaponHandler>> htAttacks = new Hashtable<>();
        // Keep track of each APDS, and which attacks it could affect
        Hashtable<Mounted, Vector<WeaponHandler>> apdsTargets = new Hashtable<>();

        for (AttackHandler ah : gameManager.game.getAttacksVector()) {
            WeaponHandler wh = (WeaponHandler) ah;
            WeaponAttackAction waa = wh.waa;

            // for artillery attacks, the attacking entity
            // might no longer be in the game.
            //TODO : Yeah, I know there's an exploit here, but better able to shoot some ArrowIVs than none, right?
            if (gameManager.game.getEntity(waa.getEntityId()) == null) {
                LogManager.getLogger().info("Can't Assign AMS: Artillery firer is null!");
                continue;
            }

            Mounted weapon = gameManager.game.getEntity(waa.getEntityId()).getEquipment(waa.getWeaponId());

            // Only entities can have AMS. Arrow IV doesn't target an entity until later, so we have to ignore them
            if (!(waa instanceof ArtilleryAttackAction) && (Targetable.TYPE_ENTITY != waa.getTargetType())) {
                continue;
            }

            // AMS is only used against attacks that hit (TW p129)
            if (wh.roll.getIntValue() < wh.toHit.getValue()) {
                continue;
            }

            // Can only use AMS versus missiles. Artillery Bays might be firing Arrow IV homing missiles,
            // but lack the flag
            boolean isHomingMissile = false;
            if (wh instanceof ArtilleryWeaponIndirectHomingHandler
                    || wh instanceof ArtilleryBayWeaponIndirectHomingHandler) {
                Mounted ammoUsed = gameManager.game.getEntity(waa.getEntityId()).getEquipment(waa.getAmmoId());
                AmmoType atype = ammoUsed == null ? null : (AmmoType) ammoUsed.getType();
                if (atype != null
                        && (atype.getAmmoType() == AmmoType.T_ARROW_IV || atype.getAmmoType() == BombType.B_HOMING)) {
                    isHomingMissile = true;
                }
            }
            if (!weapon.getType().hasFlag(WeaponType.F_MISSILE) && !isHomingMissile) {
                continue;
            }

            // For Bearings-only Capital Missiles, don't assign during the offboard phase
            if (wh instanceof CapitalMissileBearingsOnlyHandler) {
                ArtilleryAttackAction aaa = (ArtilleryAttackAction) waa;
                if ((aaa.getTurnsTilHit() > 0) || !gameManager.getGame().getPhase().isFiring()) {
                    continue;
                }
            }

            // For Arrow IV homing artillery
            Entity target;
            if (waa instanceof ArtilleryAttackAction) {
                target = (waa.getTargetType() == Targetable.TYPE_ENTITY) ? (Entity) waa
                        .getTarget(gameManager.game) : null;

                // In case our target really is null.
                if (target == null) {
                    continue;
                }
            } else {
                target = gameManager.game.getEntity(waa.getTargetId());
            }
            Vector<WeaponHandler> v = htAttacks.computeIfAbsent(target, k -> new Vector<>());
            v.addElement(wh);
            // Keep track of what weapon attacks could be affected by APDS
            if (apdsCoords.containsKey(target.getPosition())) {
                for (Mounted apds : apdsCoords.get(target.getPosition())) {
                    // APDS only affects attacks against friendly units
                    if (target.isEnemyOf(apds.getEntity())) {
                        continue;
                    }
                    Vector<WeaponHandler> handlerList = apdsTargets.computeIfAbsent(apds, k -> new Vector<>());
                    handlerList.add(wh);
                }
            }
        }

        // Let each target assign its AMS
        for (Entity e : htAttacks.keySet()) {
            Vector<WeaponHandler> vAttacks = htAttacks.get(e);
            // Allow MM to automatically assign AMS targets
            if (gameManager.game.getOptions().booleanOption(OptionsConstants.BASE_AUTO_AMS)) {
                e.assignAMS(vAttacks);
            } else { // Allow user to manually assign targets
                gameManager.utilityManager.manuallyAssignAMSTarget(e, vAttacks, gameManager);
            }
        }

        // Let each APDS assign itself to an attack
        Set<WeaponAttackAction> targetedAttacks = new HashSet<>();
        for (Mounted apds : apdsTargets.keySet()) {
            List<WeaponHandler> potentialTargets = apdsTargets.get(apds);
            // Ensure we only target each attack once
            List<WeaponHandler> targetsToRemove = new ArrayList<>();
            for (WeaponHandler wh : potentialTargets) {
                if (targetedAttacks.contains(wh.getWaa())) {
                    targetsToRemove.add(wh);
                }
            }
            potentialTargets.removeAll(targetsToRemove);
            WeaponAttackAction targetedWAA;
            // Assign APDS to an attack
            if (gameManager.game.getOptions().booleanOption(OptionsConstants.BASE_AUTO_AMS)) {
                targetedWAA = apds.assignAPDS(potentialTargets);
            } else { // Allow user to manually assign targets
                targetedWAA = gameManager.utilityManager.manuallyAssignAPDSTarget(apds, potentialTargets, gameManager);
            }
            if (targetedWAA != null) {
                targetedAttacks.add(targetedWAA);
            }
        }

    }

    /**
     * Convenience method for determining which missile attack will be targeted
     * with AMS on the supplied Entity
     *  @param apds
     *            The Entity with AMS
     * @param vAttacks
     * @param gameManager
     */
    protected WeaponAttackAction manuallyAssignAPDSTarget(Mounted apds,
                                                          List<WeaponHandler> vAttacks, GameManager gameManager) {
        Entity e = apds.getEntity();
        if (e == null) {
            return null;
        }

        // Create a list of valid assignments for this APDS
        List<WeaponAttackAction> vAttacksInArc = new ArrayList<>(vAttacks.size());
        for (WeaponHandler wr : vAttacks) {
            boolean isInArc = Compute.isInArc(e.getGame(), e.getId(),
                    e.getEquipmentNum(apds),
                    gameManager.game.getEntity(wr.waa.getEntityId()));
            boolean isInRange = e.getPosition().distance(
                    wr.getWaa().getTarget(gameManager.game).getPosition()) <= 3;
            if (isInArc && isInRange) {
                vAttacksInArc.add(wr.waa);
            }
        }

        // If there are no valid attacks left, don't bother
        if (vAttacksInArc.size() < 1) {
            return null;
        }

        WeaponAttackAction targetedWAA = null;

        if (apds.curMode().equals("Automatic")) {
            targetedWAA = Compute.getHighestExpectedDamage(gameManager.game,
                    vAttacksInArc, true);
        } else {
            // Send a client feedback request
            List<Integer> apdsDists = new ArrayList<>();
            for (WeaponAttackAction waa : vAttacksInArc) {
                apdsDists.add(waa.getTarget(gameManager.game).getPosition()
                        .distance(e.getPosition()));
            }
            gameManager.communicationManager.sendAPDSAssignCFR(e, apdsDists, vAttacksInArc, gameManager);
            synchronized (gameManager.cfrPacketQueue) {
                try {
                    gameManager.cfrPacketQueue.wait();
                } catch (InterruptedException ignored) {
                    // Do nothing
                }

                if (!gameManager.cfrPacketQueue.isEmpty()) {
                    Server.ReceivedPacket rp = gameManager.cfrPacketQueue.poll();
                    final PacketCommand cfrType = (PacketCommand) rp.getPacket().getObject(0);
                    // Make sure we got the right type of response
                    if (!cfrType.isCFRAPDSAssign()) {
                        LogManager.getLogger().error("Expected a CFR_APDS_ASSIGN CFR packet, received: " + cfrType);
                        throw new IllegalStateException();
                    }
                    Integer waaIndex = (Integer) rp.getPacket().getData()[1];
                    if (waaIndex != null) {
                        targetedWAA = vAttacksInArc.get(waaIndex);
                    }
                }
            }
        }

        if (targetedWAA != null) {
            targetedWAA.addCounterEquipment(apds);
            return targetedWAA;
        } else {
            return null;
        }
    }

    /**
     * Convenience method for determining which missile attack will be targeted
     * with AMS on the supplied Entity
     *  @param e
     *            The Entity with AMS
     * @param vAttacks
     * @param gameManager
     */
    protected void manuallyAssignAMSTarget(Entity e,
                                           Vector<WeaponHandler> vAttacks, GameManager gameManager) {
        //Fix for bug #1051 - don't send the targeting nag for a shutdown unit
        if (e.isShutDown()) {
            return;
        }
        // Current AMS targets: each attack can only be targeted once
        HashSet<WeaponAttackAction> amsTargets = new HashSet<>();
        // Pick assignment for each active AMS
        for (Mounted ams : e.getActiveAMS()) {
            // Skip APDS
            if (ams.isAPDS()) {
                continue;
            }
            // Create a list of valid assignments for this AMS
            List<WeaponAttackAction> vAttacksInArc = new ArrayList<>(vAttacks.size());
            for (WeaponHandler wr : vAttacks) {
                if (!amsTargets.contains(wr.waa)
                        && Compute.isInArc(gameManager.game, e.getId(),
                        e.getEquipmentNum(ams),
                        gameManager.game.getEntity(wr.waa.getEntityId()))) {
                    vAttacksInArc.add(wr.waa);
                }
            }

            // If there are no valid attacks left, don't bother
            if (vAttacksInArc.size() < 1) {
                continue;
            }

            WeaponAttackAction targetedWAA = null;

            if (ams.curMode().equals("Automatic")) {
                targetedWAA = Compute.getHighestExpectedDamage(gameManager.game, vAttacksInArc, true);
            } else {
                // Send a client feedback request
                gameManager.communicationManager.sendAMSAssignCFR(e, ams, vAttacksInArc, gameManager);
                synchronized (gameManager.cfrPacketQueue) {
                    try {
                        gameManager.cfrPacketQueue.wait();
                    } catch (Exception ignored) {

                    }

                    if (!gameManager.cfrPacketQueue.isEmpty()) {
                        Server.ReceivedPacket rp = gameManager.cfrPacketQueue.poll();
                        final PacketCommand cfrType = (PacketCommand) rp.getPacket().getObject(0);
                        // Make sure we got the right type of response
                        if (!cfrType.isCFRAMSAssign()) {
                            LogManager.getLogger().error("Expected a CFR_AMS_ASSIGN CFR packet, received: " + cfrType);
                            throw new IllegalStateException();
                        }
                        Integer waaIndex = (Integer) rp.getPacket().getData()[1];
                        if (waaIndex != null) {
                            targetedWAA = vAttacksInArc.get(waaIndex);
                        }
                    }
                }
            }

            if (targetedWAA != null) {
                targetedWAA.addCounterEquipment(ams);
                amsTargets.add(targetedWAA);
            }
        }
    }

    /**
     * Convenience method for computing a mapping of which Coords are
     * "protected" by an APDS. Protection implies that the coords is within the
     * range/arc of an active APDS.
     *
     * @return
     * @param gameManager
     */
    protected Hashtable<Coords, List<Mounted>> getAPDSProtectedCoords(GameManager gameManager) {
        // Get all of the coords that would be protected by APDS
        Hashtable<Coords, List<Mounted>> apdsCoords = new Hashtable<>();
        for (Entity e : gameManager.game.getEntitiesVector()) {
            // Ignore Entities without positions
            if (e.getPosition() == null) {
                continue;
            }
            Coords origPos = e.getPosition();
            for (Mounted ams : e.getActiveAMS()) {
                // Ignore non-APDS AMS
                if (!ams.isAPDS()) {
                    continue;
                }
                // Add the current hex as a defended location
                List<Mounted> apdsList = apdsCoords.computeIfAbsent(origPos, k -> new ArrayList<>());
                apdsList.add(ams);
                // Add each coords that is within arc/range as protected
                int maxDist = 3;
                if (e instanceof BattleArmor) {
                    int numTroopers = ((BattleArmor) e)
                            .getNumberActiverTroopers();
                    switch (numTroopers) {
                        case 1:
                            maxDist = 1;
                            break;
                        case 2:
                        case 3:
                            maxDist = 2;
                            break;
                        // Anything above is the same as the default
                    }
                }
                for (int dist = 1; dist <= maxDist; dist++) {
                    List<Coords> coords = e.getPosition().allAtDistance(dist);
                    for (Coords pos : coords) {
                        // Check that we're in the right arc
                        if (Compute.isInArc(gameManager.game, e.getId(), e.getEquipmentNum(ams),
                                new HexTarget(pos, HexTarget.TYPE_HEX_CLEAR))) {
                            apdsList = apdsCoords.computeIfAbsent(pos, k -> new ArrayList<>());
                            apdsList.add(ams);
                        }
                    }
                }

            }
        }
        return apdsCoords;
    }

    /**
     * Called at the start and end of movement. Determines if an entity
     * has been detected and/or had a firing solution calculated
     * @param gameManager
     */
    void detectSpacecraft(GameManager gameManager) {
        // Don't bother if we're not in space or if the game option isn't on
        if (!gameManager.game.getBoard().inSpace()
                || !gameManager.game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_STRATOPS_ADVANCED_SENSORS)) {
            return;
        }

        //Now, run the detection rolls
        for (Entity detector : gameManager.game.getEntitiesVector()) {
            //Don't process for invalid units
            //in the case of squadrons and transports, we want the 'host'
            //unit, not the component entities
            if (detector.getPosition() == null
                    || detector.isDestroyed()
                    || detector.isDoomed()
                    || detector.isOffBoard()
                    || detector.isPartOfFighterSquadron()
                    || detector.getTransportId() != Entity.NONE) {
                continue;
            }
            for (Entity target : gameManager.game.getEntitiesVector()) {
                //Once a target is detected, we don't need to detect it again
                if (detector.hasSensorContactFor(target.getId())) {
                    continue;
                }
                //Don't process for invalid units
                //in the case of squadrons and transports, we want the 'host'
                //unit, not the component entities
                if (target.getPosition() == null
                        || target.isDestroyed()
                        || target.isDoomed()
                        || target.isOffBoard()
                        || target.isPartOfFighterSquadron()
                        || target.getTransportId() != Entity.NONE) {
                    continue;
                }
                // Only process for enemy units
                if (!detector.isEnemyOf(target)) {
                    continue;
                }
                //If we successfully detect the enemy, add it to the appropriate detector's sensor contacts list
                if (Compute.calcSensorContact(gameManager.game, detector, target)) {
                    gameManager.game.getEntity(detector.getId()).addSensorContact(target.getId());
                    //If detector is part of a C3 network, share the contact
                    if (detector.hasNavalC3()) {
                        for (Entity c3NetMate : gameManager.game.getC3NetworkMembers(detector)) {
                            gameManager.game.getEntity(c3NetMate.getId()).addSensorContact(target.getId());
                        }
                    }
                }
            }
        }
        //Now, run the firing solution calculations
        for (Entity detector : gameManager.game.getEntitiesVector()) {
            //Don't process for invalid units
            //in the case of squadrons and transports, we want the 'host'
            //unit, not the component entities
            if (detector.getPosition() == null
                    || detector.isDestroyed()
                    || detector.isDoomed()
                    || detector.isOffBoard()
                    || detector.isPartOfFighterSquadron()
                    || detector.getTransportId() != Entity.NONE) {
                continue;
            }
            for (int targetId : detector.getSensorContacts()) {
                Entity target = gameManager.game.getEntity(targetId);
                //if we already have a firing solution, no need to process a new one
                if (detector.hasFiringSolutionFor(targetId)) {
                    continue;
                }
                //Don't process for invalid units
                //in the case of squadrons and transports, we want the 'host'
                //unit, not the component entities
                if (target == null
                        || target.getPosition() == null
                        || target.isDestroyed()
                        || target.isDoomed()
                        || target.isOffBoard()
                        || target.isPartOfFighterSquadron()
                        || target.getTransportId() != Entity.NONE) {
                    continue;
                }
                // Only process for enemy units
                if (!detector.isEnemyOf(target)) {
                    continue;
                }
                //If we successfully lock up the enemy, add it to the appropriate detector's firing solutions list
                if (Compute.calcFiringSolution(gameManager.game, detector, target)) {
                    gameManager.game.getEntity(detector.getId()).addFiringSolution(targetId);
                }
            }
        }
    }

    /**
     * Called at the end of movement. Determines if an entity
     * has moved beyond sensor range
     * @param gameManager
     */
    void updateSpacecraftDetection(GameManager gameManager) {
        // Don't bother if we're not in space or if the game option isn't on
        if (!gameManager.game.getBoard().inSpace()
                || !gameManager.game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_STRATOPS_ADVANCED_SENSORS)) {
            return;
        }
        //Run through our list of units and remove any entities from the plotting board that have moved out of range
        for (Entity detector : gameManager.game.getEntitiesVector()) {
            Compute.updateFiringSolutions(gameManager.game, detector);
            Compute.updateSensorContacts(gameManager.game, detector);
        }
    }

    /**
     * Checks to see if any units can detected hidden units.
     * @param gameManager
     */
    void detectHiddenUnits(GameManager gameManager) {
        // If hidden units aren't on, nothing to do
        if (!gameManager.game.getOptions().booleanOption(OptionsConstants.ADVANCED_HIDDEN_UNITS)) {
            return;
        }

        // See if any unit with a probe, detects any hidden units
        for (Entity detector : gameManager.game.getEntitiesVector()) {
            ServerHelper.detectHiddenUnits(gameManager.game, detector, detector.getPosition(), gameManager.vPhaseReport, gameManager);
        }
    }

    /**
     * Called to what players can see what units. This is used to determine who
     * can see what in double blind reports.
     * @param gameManager
     */
    void resolveWhatPlayersCanSeeWhatUnits(GameManager gameManager) {
        List<ECMInfo> allECMInfo = null;
        if (gameManager.game.getOptions().booleanOption(OptionsConstants.ADVANCED_TACOPS_SENSORS)) {
            allECMInfo = ComputeECM.computeAllEntitiesECMInfo(gameManager.game
                    .getEntitiesVector());
        }
        Map<EntityTargetPair, LosEffects> losCache = new HashMap<>();
        for (Entity entity : gameManager.game.getEntitiesVector()) {
            // We are hidden once again!
            entity.clearSeenBy();
            entity.clearDetectedBy();
            // Handle visual spotting
            for (Player p : gameManager.whoCanSee(entity, false, losCache)) {
                entity.addBeenSeenBy(p);
            }
            // Handle detection by sensors
            for (Player p : gameManager.entityActionManager.whoCanDetect(entity, allECMInfo, losCache, gameManager)) {
                entity.addBeenDetectedBy(p);
            }
        }
    }
}
