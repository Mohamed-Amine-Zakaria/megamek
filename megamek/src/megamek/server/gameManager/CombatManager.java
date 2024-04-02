package megamek.server.gameManager;

import megamek.common.*;
import megamek.common.actions.*;
import megamek.common.options.OptionsConstants;
import megamek.common.weapons.AttackHandler;
import megamek.common.weapons.DamageType;
import megamek.common.weapons.Weapon;
import org.apache.logging.log4j.LogManager;

import java.util.Enumeration;
import java.util.Vector;

public class CombatManager {
    /**
     * Resolve a Physical Attack
     *  @param pr  The <code>PhysicalResult</code> of the physical attack
     * @param cen The <code>int</code> Entity Id of the entity whose physical
     * @param gameManager
     */
    protected void resolvePhysicalAttack(PhysicalResult pr, int cen, GameManager gameManager) {
        AbstractAttackAction aaa = pr.aaa;
        if (aaa instanceof PunchAttackAction) {
            PunchAttackAction paa = (PunchAttackAction) aaa;
            if (paa.getArm() == PunchAttackAction.BOTH) {
                paa.setArm(PunchAttackAction.LEFT);
                pr.aaa = paa;
                gameManager.combatManager.resolvePunchAttack(pr, cen, gameManager);
                cen = paa.getEntityId();
                paa.setArm(PunchAttackAction.RIGHT);
                pr.aaa = paa;
                gameManager.combatManager.resolvePunchAttack(pr, cen, gameManager);
            } else {
                gameManager.combatManager.resolvePunchAttack(pr, cen, gameManager);
                cen = paa.getEntityId();
            }
        } else if (aaa instanceof KickAttackAction) {
            gameManager.combatManager.resolveKickAttack(pr, cen, gameManager);
            cen = aaa.getEntityId();
        } else if (aaa instanceof BrushOffAttackAction) {
            BrushOffAttackAction baa = (BrushOffAttackAction) aaa;
            if (baa.getArm() == BrushOffAttackAction.BOTH) {
                baa.setArm(BrushOffAttackAction.LEFT);
                pr.aaa = baa;
                gameManager.combatManager.resolveBrushOffAttack(pr, cen, gameManager);
                cen = baa.getEntityId();
                baa.setArm(BrushOffAttackAction.RIGHT);
                pr.aaa = baa;
                gameManager.combatManager.resolveBrushOffAttack(pr, cen, gameManager);
            } else {
                gameManager.combatManager.resolveBrushOffAttack(pr, cen, gameManager);
                cen = baa.getEntityId();
            }
        } else if (aaa instanceof ThrashAttackAction) {
            gameManager.combatManager.resolveThrashAttack(pr, cen, gameManager);
            cen = aaa.getEntityId();
        } else if (aaa instanceof ProtomechPhysicalAttackAction) {
            gameManager.combatManager.resolveProtoAttack(pr, cen, gameManager);
            cen = aaa.getEntityId();
        } else if (aaa instanceof ClubAttackAction) {
            gameManager.combatManager.resolveClubAttack(pr, cen, gameManager);
            cen = aaa.getEntityId();
        } else if (aaa instanceof PushAttackAction) {
            gameManager.combatManager.resolvePushAttack(pr, cen, gameManager);
            cen = aaa.getEntityId();
        } else if (aaa instanceof ChargeAttackAction) {
            gameManager.combatManager.resolveChargeAttack(pr, cen, gameManager);
            cen = aaa.getEntityId();
        } else if (aaa instanceof AirmechRamAttackAction) {
            gameManager.combatManager.resolveAirmechRamAttack(pr, cen, gameManager);
            cen = aaa.getEntityId();
        } else if (aaa instanceof DfaAttackAction) {
            gameManager.combatManager.resolveDfaAttack(pr, cen, gameManager);
            cen = aaa.getEntityId();
        } else if (aaa instanceof LayExplosivesAttackAction) {
            gameManager.combatManager.resolveLayExplosivesAttack(pr, gameManager);
            cen = aaa.getEntityId();
        } else if (aaa instanceof TripAttackAction) {
            gameManager.combatManager.resolveTripAttack(pr, cen, gameManager);
            cen = aaa.getEntityId();
        } else if (aaa instanceof JumpJetAttackAction) {
            gameManager.combatManager.resolveJumpJetAttack(pr, cen, gameManager);
            cen = aaa.getEntityId();
        } else if (aaa instanceof GrappleAttackAction) {
            gameManager.combatManager.resolveGrappleAttack(pr, cen, gameManager);
            cen = aaa.getEntityId();
        } else if (aaa instanceof BreakGrappleAttackAction) {
            gameManager.combatManager.resolveBreakGrappleAttack(pr, cen, gameManager);
            cen = aaa.getEntityId();
        } else if (aaa instanceof RamAttackAction) {
            gameManager.combatManager.resolveRamAttack(pr, cen, gameManager);
            cen = aaa.getEntityId();
        } else if (aaa instanceof TeleMissileAttackAction) {
            gameManager.combatManager.resolveTeleMissileAttack(pr, cen, gameManager);
            cen = aaa.getEntityId();
        } else if (aaa instanceof BAVibroClawAttackAction) {
            gameManager.combatManager.resolveBAVibroClawAttack(pr, cen, gameManager);
            cen = aaa.getEntityId();
        } else {
            LogManager.getLogger().error("Unknown attack action declared.");
        }
        // Not all targets are Entities.
        Targetable target = gameManager.game.getTarget(aaa.getTargetType(), aaa.getTargetId());

        if ((target != null) && (target instanceof Entity)) {
            Entity targetEntity = (Entity) target;
            targetEntity.setStruck(true);
            targetEntity.addAttackedByThisTurn(target.getId());
            gameManager.creditKill(targetEntity, gameManager.game.getEntity(cen));
        }
    }

    /**
     * Called during the fire phase to resolve all (and only) weapon attacks
     * @param gameManager
     */
    void resolveOnlyWeaponAttacks(GameManager gameManager) {
        // loop through received attack actions, getting attack handlers
        for (Enumeration<EntityAction> i = gameManager.game.getActions(); i.hasMoreElements(); ) {
            EntityAction ea = i.nextElement();
            if (ea instanceof WeaponAttackAction) {
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
        // and clear the attacks Vector
        gameManager.game.resetActions();
    }

    /*
     * Called during the weapons firing phase to initiate self destruction.
     */
    void resolveSelfDestructions(GameManager gameManager) {
        Vector<Report> vDesc = new Vector<>();
        Report r;
        for (Entity e : gameManager.game.getEntitiesVector()) {
            if (e.getSelfDestructInitiated() && e.hasEngine()) {
                r = new Report(6166, Report.PUBLIC);
                int target = e.getCrew().getPiloting();
                Roll diceRoll = e.getCrew().rollPilotingSkill();
                r.subject = e.getId();
                r.addDesc(e);
                r.indent();
                r.add(target);
                r.add(diceRoll);
                r.choose(diceRoll.getIntValue() >= target);
                vDesc.add(r);

                // Blow it up...
                if (diceRoll.getIntValue() >= target) {
                    int engineRating = e.getEngine().getRating();
                    r = new Report(5400, Report.PUBLIC);
                    r.subject = e.getId();
                    r.indent(2);
                    vDesc.add(r);

                    if (e instanceof Mech) {
                        Mech mech = (Mech) e;
                        if (mech.isAutoEject()
                                && (!gameManager.game.getOptions().booleanOption(
                                OptionsConstants.RPG_CONDITIONAL_EJECTION) || (gameManager.game
                                .getOptions().booleanOption(
                                        OptionsConstants.RPG_CONDITIONAL_EJECTION) && mech
                                .isCondEjectEngine()))) {
                            vDesc.addAll(gameManager.ejectEntity(e, true));
                        }
                    }
                    e.setSelfDestructedThisTurn(true);
                    gameManager.doFusionEngineExplosion(engineRating, e.getPosition(),
                            vDesc, null);
                    Report.addNewline(vDesc);
                    r = new Report(5410, Report.PUBLIC);
                    r.subject = e.getId();
                    r.indent(2);
                    Report.addNewline(vDesc);
                    vDesc.add(r);
                }
                e.setSelfDestructInitiated(false);
            }
        }
        gameManager.reportManager.addReport(vDesc, gameManager);
    }

    protected void resolveClearMinefield(Entity ent, Minefield mf, GameManager gameManager) {

        if ((null == mf) || (null == ent) || ent.isDoomed()
                || ent.isDestroyed()) {
            return;
        }

        Coords pos = mf.getCoords();
        int clear = Minefield.CLEAR_NUMBER_INFANTRY;
        int boom = Minefield.CLEAR_NUMBER_INFANTRY_ACCIDENT;

        Report r = new Report(2245);
        // Does the entity has a minesweeper?
        if ((ent instanceof BattleArmor)) {
            BattleArmor ba = (BattleArmor) ent;
            String mcmName = BattleArmor.MANIPULATOR_TYPE_STRINGS
                    [BattleArmor.MANIPULATOR_BASIC_MINE_CLEARANCE];
            if (ba.getLeftManipulatorName().equals(mcmName)) {
                clear = Minefield.CLEAR_NUMBER_BA_SWEEPER;
                boom = Minefield.CLEAR_NUMBER_BA_SWEEPER_ACCIDENT;
                r = new Report(2246);
            }
        } else if (ent instanceof Infantry) { // Check Minesweeping Engineers
            Infantry inf = (Infantry) ent;
            if (inf.hasSpecialization(Infantry.MINE_ENGINEERS)) {
                clear = Minefield.CLEAR_NUMBER_INF_ENG;
                boom = Minefield.CLEAR_NUMBER_INF_ENG_ACCIDENT;
                r = new Report(2247);
            }
        }
        // mine clearing roll
        r.subject = ent.getId();
        r.add(ent.getShortName(), true);
        r.add(Minefield.getDisplayableName(mf.getType()));
        r.add(pos.getBoardNum(), true);
        gameManager.addReport(r);

        if (gameManager.environmentalEffectManager.clearMinefield(mf, ent, clear, boom, gameManager.vPhaseReport, gameManager)) {
            gameManager.environmentalEffectManager.removeMinefield(mf, gameManager);
        }
        // some mines might have blown up
        gameManager.environmentalEffectManager.resetMines(gameManager);

        gameManager.addNewLines();
    }

    /**
     * Trigger the indicated AP Pod of the entity.
     *  @param entity the <code>Entity</code> triggering the AP Pod.
     * @param podId  the <code>int</code> ID of the AP Pod.
     * @param gameManager
     */
    protected void triggerAPPod(Entity entity, int podId, GameManager gameManager) {
        // Get the mount for this pod.
        Mounted mount = entity.getEquipment(podId);

        // Confirm that this is, indeed, an AP Pod.
        if (null == mount) {
            LogManager.getLogger().error("Expecting to find an AP Pod at " + podId + " on the unit, " + entity.getDisplayName()
                    + " but found NO equipment at all!!!");
            return;
        }
        EquipmentType equip = mount.getType();
        if (!(equip instanceof MiscType) || !equip.hasFlag(MiscType.F_AP_POD)) {
            LogManager.getLogger().error("Expecting to find an AP Pod at " + podId + " on the unit, "+ entity.getDisplayName()
                    + " but found " + equip.getName() + " instead!!!");
            return;
        }

        // Now confirm that the entity can trigger the pod.
        // Ignore the "used this round" flag.
        boolean oldFired = mount.isUsedThisRound();
        mount.setUsedThisRound(false);
        boolean canFire = mount.canFire();
        mount.setUsedThisRound(oldFired);
        if (!canFire) {
            LogManager.getLogger().error("Can not trigger the AP Pod at " + podId + " on the unit, "
                    + entity.getDisplayName() + "!!!");
            return;
        }

        Report r;

        // Mark the pod as fired and log the action.
        mount.setFired(true);
        r = new Report(3010);
        r.newlines = 0;
        r.subject = entity.getId();
        r.addDesc(entity);
        gameManager.addReport(r);

        // Walk through ALL entities in the triggering entity's hex.
        for (Entity target : gameManager.game.getEntitiesVector(entity.getPosition())) {
            // Is this an unarmored infantry platoon?
            if (target.isConventionalInfantry()) {
                // Roll d6-1 for damage.
                final int damage = Math.max(1, Compute.d6() - 1);

                // Damage the platoon.
                gameManager.reportManager.addReport(gameManager.damageEntity(target, new HitData(Infantry.LOC_INFANTRY), damage), gameManager);

                // Damage from AP Pods is applied immediately.
                target.applyDamage();
            } // End target-is-unarmored

            // Nope, the target is immune.
            // Don't make a log entry for the triggering entity.
            else if (!entity.equals(target)) {
                r = new Report(3020);
                r.indent(2);
                r.subject = target.getId();
                r.addDesc(target);
                gameManager.addReport(r);
            }

        } // Check the next entity in the triggering entity's hex.
    }

    /**
     * Trigger the indicated B Pod of the entity.
     *  @param entity the <code>Entity</code> triggering the B Pod.
     * @param podId  the <code>int</code> ID of the B Pod.
     * @param target
     * @param gameManager
     */
    protected void triggerBPod(Entity entity, int podId, Entity target, GameManager gameManager) {
        // Get the mount for this pod.
        Mounted mount = entity.getEquipment(podId);

        // Confirm that this is, indeed, an Anti-BA Pod.
        if (null == mount) {
            LogManager.getLogger().error("Expecting to find an B Pod at " + podId + " on the unit, "
                    + entity.getDisplayName() + " but found NO equipment at all!!!");
            return;
        }
        EquipmentType equip = mount.getType();
        if (!(equip instanceof WeaponType) || !equip.hasFlag(WeaponType.F_B_POD)) {
            LogManager.getLogger().error("Expecting to find an B Pod at " + podId + " on the unit, "
                    + entity.getDisplayName() + " but found " + equip.getName() + " instead!!!");
            return;
        }

        // Now confirm that the entity can trigger the pod.
        // Ignore the "used this round" flag.
        boolean oldFired = mount.isUsedThisRound();
        mount.setUsedThisRound(false);
        boolean canFire = mount.canFire();
        mount.setUsedThisRound(oldFired);
        if (!canFire) {
            LogManager.getLogger().error("Can not trigger the B Pod at " + podId + " on the unit, "
                    + entity.getDisplayName() + "!!!");
            return;
        }

        Report r;

        // Mark the pod as fired and log the action.
        mount.setFired(true);
        r = new Report(3011);
        r.newlines = 0;
        r.subject = entity.getId();
        r.addDesc(entity);
        gameManager.addReport(r);

        // Is this an unarmored infantry platoon?
        if (target.isConventionalInfantry()) {
            // Roll d6 for damage.
            final int damage = Compute.d6();

            // Damage the platoon.
            gameManager.reportManager.addReport(gameManager.damageEntity(target, new HitData(Infantry.LOC_INFANTRY), damage), gameManager);

            // Damage from AP Pods is applied immediately.
            target.applyDamage();
        } else if (target instanceof BattleArmor) {
            // 20 damage in 5 point clusters
            final int damage = 5;

            // Damage the squad.
            gameManager.reportManager.addReport(gameManager.damageEntity(target, target.rollHitLocation(0, 0), damage), gameManager);
            gameManager.reportManager.addReport(gameManager.damageEntity(target, target.rollHitLocation(0, 0), damage), gameManager);
            gameManager.reportManager.addReport(gameManager.damageEntity(target, target.rollHitLocation(0, 0), damage), gameManager);
            gameManager.reportManager.addReport(gameManager.damageEntity(target, target.rollHitLocation(0, 0), damage), gameManager);

            // Damage from B Pods is applied immediately.
            target.applyDamage();
        } else if (!entity.equals(target)) {
            // Nope, the target is immune.
            // Don't make a log entry for the triggering entity.
            r = new Report(3020);
            r.indent(2);
            r.subject = target.getId();
            r.addDesc(target);
            gameManager.addReport(r);
        }
    }

    /**
     * Resolve an Unjam Action object
     * @param entity
     * @param gameManager
     */
    protected void resolveUnjam(Entity entity, GameManager gameManager) {
        Report r;
        final int TN = entity.getCrew().getGunnery() + 3;
        if (gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_UNJAM_UAC)) {
            r = new Report(3026);
        } else {
            r = new Report(3025);
        }
        r.subject = entity.getId();
        r.addDesc(entity);
        gameManager.addReport(r);
        for (Mounted mounted : entity.getTotalWeaponList()) {
            if (mounted.isJammed() && !mounted.isDestroyed()) {
                WeaponType wtype = (WeaponType) mounted.getType();
                if (wtype.getAmmoType() == AmmoType.T_AC_ROTARY) {
                    Roll diceRoll = Compute.rollD6(2);
                    r = new Report(3030);
                    r.indent();
                    r.subject = entity.getId();
                    r.add(wtype.getName());
                    r.add(TN);
                    r.add(diceRoll);

                    if (diceRoll.getIntValue() >= TN) {
                        r.choose(true);
                        mounted.setJammed(false);
                    } else {
                        r.choose(false);
                    }
                    gameManager.addReport(r);
                }
                // Unofficial option to unjam UACs, ACs, and LACs like Rotary
                // Autocannons
                if (((wtype.getAmmoType() == AmmoType.T_AC_ULTRA)
                        || (wtype.getAmmoType() == AmmoType.T_AC_ULTRA_THB)
                        || (wtype.getAmmoType() == AmmoType.T_AC)
                        || (wtype.getAmmoType() == AmmoType.T_AC_IMP)
                        || (wtype.getAmmoType() == AmmoType.T_PAC)
                        || (wtype.getAmmoType() == AmmoType.T_LAC))
                        && gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_UNJAM_UAC)) {
                    Roll diceRoll = Compute.rollD6(2);
                    r = new Report(3030);
                    r.indent();
                    r.subject = entity.getId();
                    r.add(wtype.getName());
                    r.add(TN);
                    r.add(diceRoll);

                    if (diceRoll.getIntValue() >= TN) {
                        r.choose(true);
                        mounted.setJammed(false);
                    } else {
                        r.choose(false);
                    }
                    gameManager.addReport(r);
                }
            }
        }
    }

    protected void resolveFindClub(Entity entity, GameManager gameManager) {
        EquipmentType clubType = null;

        entity.setFindingClub(true);

        // Get the entity's current hex.
        Coords coords = entity.getPosition();
        Hex curHex = gameManager.game.getBoard().getHex(coords);

        Report r;

        // Is there a blown off arm in the hex?
        if (curHex.terrainLevel(Terrains.ARMS) > 0) {
            clubType = EquipmentType.get(EquipmentTypeLookup.LIMB_CLUB);
            curHex.addTerrain(new Terrain(Terrains.ARMS, curHex.terrainLevel(Terrains.ARMS) - 1));
            gameManager.communicationManager.sendChangedHex(entity.getPosition(), gameManager);
            r = new Report(3035);
            r.subject = entity.getId();
            r.addDesc(entity);
            gameManager.addReport(r);
        }
        // Is there a blown off leg in the hex?
        else if (curHex.terrainLevel(Terrains.LEGS) > 0) {
            clubType = EquipmentType.get(EquipmentTypeLookup.LIMB_CLUB);
            curHex.addTerrain(new Terrain(Terrains.LEGS, curHex.terrainLevel(Terrains.LEGS) - 1));
            gameManager.communicationManager.sendChangedHex(entity.getPosition(), gameManager);
            r = new Report(3040);
            r.subject = entity.getId();
            r.addDesc(entity);
            gameManager.addReport(r);
        }

        // Is there the rubble of a medium, heavy,
        // or hardened building in the hex?
        else if (Building.LIGHT < curHex.terrainLevel(Terrains.RUBBLE)) {

            // Finding a club is not guaranteed. The chances are
            // based on the type of building that produced the
            // rubble.
            boolean found = false;
            int roll = Compute.d6(2);
            switch (curHex.terrainLevel(Terrains.RUBBLE)) {
                case Building.MEDIUM:
                    if (roll >= 7) {
                        found = true;
                    }
                    break;
                case Building.HEAVY:
                    if (roll >= 6) {
                        found = true;
                    }
                    break;
                case Building.HARDENED:
                    if (roll >= 5) {
                        found = true;
                    }
                    break;
                case Building.WALL:
                    if (roll >= 13) {
                        found = true;
                    }
                    break;
                default:
                    // we must be in ultra
                    if (roll >= 4) {
                        found = true;
                    }
            }

            // Let the player know if they found a club.
            if (found) {
                clubType = EquipmentType.get(EquipmentTypeLookup.GIRDER_CLUB);
                r = new Report(3045);
                r.subject = entity.getId();
                r.addDesc(entity);
                gameManager.addReport(r);
            } else {
                // Sorry, no club for you.
                r = new Report(3050);
                r.subject = entity.getId();
                r.addDesc(entity);
                gameManager.addReport(r);
            }
        }

        // Are there woods in the hex?
        else if (curHex.containsTerrain(Terrains.WOODS)
                || curHex.containsTerrain(Terrains.JUNGLE)) {
            clubType = EquipmentType.get(EquipmentTypeLookup.TREE_CLUB);
            r = new Report(3055);
            r.subject = entity.getId();
            r.addDesc(entity);
            gameManager.addReport(r);
        }

        // add the club
        try {
            if (clubType != null) {
                entity.addEquipment(clubType, Entity.LOC_NONE);
            }
        } catch (LocationFullException ex) {
            // unlikely...
            r = new Report(3060);
            r.subject = entity.getId();
            r.addDesc(entity);
            gameManager.addReport(r);
        }
    }

    /**
     * Handle a punch attack
     * @param pr
     * @param lastEntityId
     * @param gameManager
     */
    protected void resolvePunchAttack(PhysicalResult pr, int lastEntityId, GameManager gameManager) {
        final PunchAttackAction paa = (PunchAttackAction) pr.aaa;
        final Entity ae = gameManager.game.getEntity(paa.getEntityId());
        final Targetable target = gameManager.game.getTarget(paa.getTargetType(), paa.getTargetId());
        Entity te = null;
        if (target.getTargetType() == Targetable.TYPE_ENTITY) {
            te = (Entity) target;
        }
        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute.isThroughFrontHex(gameManager.game, ae.getPosition(), te);
        }
        final String armName = (paa.getArm() == PunchAttackAction.LEFT) ? "Left Arm" : "Right Arm";
        final int armLoc = (paa.getArm() == PunchAttackAction.LEFT) ? Mech.LOC_LARM : Mech.LOC_RARM;

        // get damage, ToHitData and roll from the PhysicalResult
        int damage = paa.getArm() == PunchAttackAction.LEFT ? pr.damage : pr.damageRight;
        // LAMs in airmech mode do half damage if airborne.
        if (ae.isAirborneVTOLorWIGE()) {
            damage = (int) Math.ceil(damage * 0.5);
        }
        final ToHitData toHit = paa.getArm() == PunchAttackAction.LEFT ? pr.toHit : pr.toHitRight;
        int rollValue = paa.getArm() == PunchAttackAction.LEFT ? pr.roll.getIntValue() : pr.rollRight.getIntValue();
        final boolean targetInBuilding = Compute.isInBuilding(gameManager.game, te);
        final boolean glancing = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS)
                && (rollValue == toHit.getValue());

        Report r;

        // Set Margin of Success/Failure.
        toHit.setMoS(rollValue - Math.max(2, toHit.getValue()));
        final boolean directBlow = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW)
                && ((toHit.getMoS() / 3) >= 1);

        // Which building takes the damage?
        Building bldg = gameManager.game.getBoard().getBuildingAt(target.getPosition());

        if (lastEntityId != paa.getEntityId()) {
            // report who is making the attacks
            r = new Report(4005);
            r.subject = ae.getId();
            r.addDesc(ae);
            gameManager.addReport(r);
        }

        r = new Report(4010);
        r.subject = ae.getId();
        r.indent();
        r.add(armName);
        r.add(target.getDisplayName());
        r.newlines = 0;
        gameManager.addReport(r);

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            r = new Report(4015);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            gameManager.addReport(r);
            if ((ae instanceof LandAirMech) && ae.isAirborneVTOLorWIGE()) {
                gameManager.game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed punch attack"));
            }
            return;
        } else if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            r = new Report(4020);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            r.newlines = 0;
            gameManager.addReport(r);
            rollValue = Integer.MAX_VALUE;
        } else {
            r = new Report(4025);
            r.subject = ae.getId();
            r.add(toHit);
            r.add(paa.getArm() == PunchAttackAction.LEFT ? pr.roll : pr.rollRight);
            r.newlines = 0;
            gameManager.addReport(r);
            if (glancing) {
                r = new Report(3186);
                r.subject = ae.getId();
                r.newlines = 0;
                gameManager.addReport(r);
            }

            if (directBlow) {
                r = new Report(3189);
                r.subject = ae.getId();
                r.newlines = 0;
                gameManager.addReport(r);
            }
        }

        // do we hit?
        if (rollValue < toHit.getValue()) {
            // nope
            r = new Report(4035);
            r.subject = ae.getId();
            gameManager.addReport(r);

            if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                gameManager.game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed punch attack"));
            }
            // If the target is in a building, the building absorbs the damage.
            if (targetInBuilding && (bldg != null)) {
                // Only report if damage was done to the building.
                if (damage > 0) {
                    Vector<Report> buildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, damage, target.getPosition(), gameManager);
                    for (Report report : buildingReport) {
                        report.subject = ae.getId();
                    }
                    gameManager.reportManager.addReport(buildingReport, gameManager);
                }
            }

            if (paa.isZweihandering()) {
                gameManager.applyZweihanderSelfDamage(ae, true, Mech.LOC_RARM, Mech.LOC_LARM);
            }

            return;
        }

        // Targeting a building.
        if ((target.getTargetType() == Targetable.TYPE_BUILDING)
                || (target.getTargetType() == Targetable.TYPE_FUEL_TANK)) {
            // The building takes the full brunt of the attack.
            r = new Report(4040);
            r.subject = ae.getId();
            gameManager.addReport(r);
            Vector<Report> buildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, damage, target.getPosition(), gameManager);
            for (Report report : buildingReport) {
                report.subject = ae.getId();
            }
            gameManager.reportManager.addReport(buildingReport, gameManager);

            // Damage any infantry in the hex.
            gameManager.reportManager.addReport(gameManager.environmentalEffectManager.damageInfantryIn(bldg, damage, target.getPosition(), gameManager), gameManager);

            if (paa.isZweihandering()) {
                gameManager.applyZweihanderSelfDamage(ae, false,  Mech.LOC_RARM, Mech.LOC_LARM);
            }

            // And we're done!
            return;
        }

        HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
        hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
        r = new Report(4045);
        r.subject = ae.getId();
        r.add(toHit.getTableDesc());
        r.add(te.getLocationAbbr(hit));
        gameManager.addReport(r);

        // The building shields all units from a certain amount of damage.
        // The amount is based upon the building's CF at the phase's start.
        if (targetInBuilding && (bldg != null)) {
            int bldgAbsorbs = bldg.getAbsorbtion(target.getPosition());
            int toBldg = Math.min(bldgAbsorbs, damage);
            damage -= toBldg;
            gameManager.addNewLines();
            Vector<Report> buildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, toBldg, target.getPosition(), gameManager);
            for (Report report : buildingReport) {
                report.subject = ae.getId();
            }
            gameManager.reportManager.addReport(buildingReport, gameManager);

            // some buildings scale remaining damage that is not absorbed
            // TODO : this isn't quite right for castles brian
            damage = (int) Math.floor(bldg.getDamageToScale() * damage);
        }

        // A building may absorb the entire shot.
        if (damage == 0) {
            r = new Report(4050);
            r.subject = ae.getId();
            r.add(te.getShortName());
            r.add(te.getOwner().getName());
            r.indent();
            gameManager.addReport(r);
        } else {
            if (glancing) {
                // Round up glancing blows against conventional infantry
                damage = (int) (te.isConventionalInfantry() ? Math.ceil(damage / 2.0) : Math.floor(damage / 2.0));
            }

            if (directBlow) {
                damage += toHit.getMoS() / 3;
                hit.makeDirectBlow(toHit.getMoS() / 3);
            }

            damage = gameManager.combatManager.checkForSpikes(te, hit.getLocation(), damage, ae,
                    (paa.getArm() == PunchAttackAction.LEFT) ?  Mech.LOC_LARM : Mech.LOC_RARM, gameManager);
            DamageType damageType = DamageType.NONE;
            gameManager.reportManager.addReport(gameManager.damageEntity(te, hit, damage, false, damageType, false,
                    false, throughFront), gameManager);
            if (target instanceof VTOL) {
                // destroy rotor
                gameManager.reportManager.addReport(gameManager.applyCriticalHit(te, VTOL.LOC_ROTOR,
                        new CriticalSlot(CriticalSlot.TYPE_SYSTEM, VTOL.CRIT_ROTOR_DESTROYED),
                        false, 0, false), gameManager);
            }
            // check for extending retractable blades
            if (paa.isBladeExtended(paa.getArm())) {
                gameManager.addNewLines();
                r = new Report(4455);
                r.indent(2);
                r.subject = ae.getId();
                r.newlines = 0;
                gameManager.addReport(r);
                // conventional infantry don't take crits and battle armor need
                // to be handled differently
                if (!(target instanceof Infantry)) {
                    gameManager.addNewLines();
                    gameManager.reportManager.addReport(gameManager.criticalEntity(te, hit.getLocation(), hit.isRear(), 0,
                            true, false, damage), gameManager);
                }

                if ((target instanceof BattleArmor) && (hit.getLocation() < te.locations())
                        && (te.getInternal(hit.getLocation()) > 0)) {
                    // TODO : we should really apply BA criticals through the critical
                    // TODO : hits methods. Right now they are applied in damageEntity
                    HitData baHit = new HitData(hit.getLocation(), false, HitData.EFFECT_CRITICAL);
                    gameManager.reportManager.addReport(gameManager.damageEntity(te, baHit, 0), gameManager);
                }
                // extend the blade
                // since retracting/extending is a freebie in the movement
                // phase, lets assume that the
                // blade retracts to its original mode
                // ae.extendBlade(paa.getArm());
                // check for breaking a nail
                if (Compute.d6(2) > 9) {
                    gameManager.addNewLines();
                    r = new Report(4456);
                    r.indent(2);
                    r.subject = ae.getId();
                    r.newlines = 0;
                    gameManager.addReport(r);
                    ae.destroyRetractableBlade(armLoc);
                }
            }
        }

        gameManager.addNewLines();

        if (paa.isZweihandering()) {
            gameManager.applyZweihanderSelfDamage(ae, false,  Mech.LOC_RARM, Mech.LOC_LARM);
        }

        gameManager.addNewLines();

        // if the target is an industrial mech, it needs to check for crits at the end of turn
        if ((target instanceof Mech) && ((Mech) target).isIndustrial()) {
            ((Mech) target).setCheckForCrit(true);
        }
    }

    /**
     * Handle a kick attack
     * @param pr
     * @param lastEntityId
     * @param gameManager
     */
    protected void resolveKickAttack(PhysicalResult pr, int lastEntityId, GameManager gameManager) {
        KickAttackAction kaa = (KickAttackAction) pr.aaa;
        final Entity ae = gameManager.game.getEntity(kaa.getEntityId());
        final Targetable target = gameManager.game.getTarget(kaa.getTargetType(), kaa.getTargetId());
        Entity te = null;
        if (target.getTargetType() == Targetable.TYPE_ENTITY) {
            te = (Entity) target;
        }
        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute.isThroughFrontHex(gameManager.game, ae.getPosition(), te);
        }
        String legName = (kaa.getLeg() == KickAttackAction.LEFT)
                || (kaa.getLeg() == KickAttackAction.LEFTMULE) ? "Left " : "Right ";
        if ((kaa.getLeg() == KickAttackAction.LEFTMULE)
                || (kaa.getLeg() == KickAttackAction.RIGHTMULE)) {
            legName = legName.concat("rear ");
        } else if (ae instanceof QuadMech) {
            legName = legName.concat("front ");
        }
        legName = legName.concat("leg");
        Report r;

        // get damage, ToHitData and roll from the PhysicalResult
        int damage = pr.damage;
        // LAMs in airmech mode do half damage if airborne.
        if (ae.isAirborneVTOLorWIGE()) {
            damage = (int) Math.ceil(damage * 0.5);
        }
        final ToHitData toHit = pr.toHit;
        int rollValue = pr.roll.getIntValue();
        final boolean targetInBuilding = Compute.isInBuilding(gameManager.game, te);
        final boolean glancing = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS)
                && (rollValue == toHit.getValue());

        // Set Margin of Success/Failure.
        toHit.setMoS(rollValue - Math.max(2, toHit.getValue()));
        final boolean directBlow = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW)
                && ((toHit.getMoS() / 3) >= 1);

        // Which building takes the damage?
        Building bldg = gameManager.game.getBoard().getBuildingAt(target.getPosition());

        if (lastEntityId != ae.getId()) {
            // who is making the attacks
            r = new Report(4005);
            r.subject = ae.getId();
            r.addDesc(ae);
            gameManager.addReport(r);
        }

        r = new Report(4055);
        r.subject = ae.getId();
        r.indent();
        r.add(legName);
        r.add(target.getDisplayName());
        r.newlines = 0;
        gameManager.addReport(r);

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            r = new Report(4060);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            gameManager.addReport(r);
            if ((ae instanceof LandAirMech) && ae.isAirborneVTOLorWIGE()) {
                gameManager.game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed a kick"));
            } else {
                gameManager.game.addPSR(new PilotingRollData(ae.getId(), 0, "missed a kick"));
            }
            return;
        } else if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            r = new Report(4065);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            r.newlines = 0;
            gameManager.addReport(r);
            rollValue = Integer.MAX_VALUE;
        } else {
            r = new Report(4025);
            r.subject = ae.getId();
            r.add(toHit);
            r.add(pr.roll);
            r.newlines = 0;
            gameManager.addReport(r);
            if (glancing) {
                r = new Report(3186);
                r.subject = ae.getId();
                r.newlines = 0;
                gameManager.addReport(r);
            }

            if (directBlow) {
                r = new Report(3189);
                r.subject = ae.getId();
                r.newlines = 0;
                gameManager.addReport(r);
            }
        }

        // do we hit?
        if (rollValue < toHit.getValue()) {
            // miss
            r = new Report(4035);
            r.subject = ae.getId();
            gameManager.addReport(r);
            if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                gameManager.game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed a kick"));
            } else {
                gameManager.game.addPSR(new PilotingRollData(ae.getId(), 0, "missed a kick"));
            }

            // If the target is in a building, the building absorbs the damage.
            if (targetInBuilding && (bldg != null)) {
                // Only report if damage was done to the building.
                if (damage > 0) {
                    Vector<Report> buildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, damage, target.getPosition(), gameManager);
                    for (Report report : buildingReport) {
                        report.subject = ae.getId();
                    }
                    gameManager.reportManager.addReport(buildingReport, gameManager);
                }
            }
            return;
        }

        // Targeting a building.
        if ((target.getTargetType() == Targetable.TYPE_BUILDING)
                || (target.getTargetType() == Targetable.TYPE_FUEL_TANK)) {
            // The building takes the full brunt of the attack.
            r = new Report(4040);
            r.subject = ae.getId();
            gameManager.addReport(r);
            Vector<Report> buildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, damage, target.getPosition(), gameManager);
            for (Report report : buildingReport) {
                report.subject = ae.getId();
            }
            gameManager.reportManager.addReport(buildingReport, gameManager);

            // Damage any infantry in the hex.
            gameManager.reportManager.addReport(gameManager.environmentalEffectManager.damageInfantryIn(bldg, damage, target.getPosition(), gameManager), gameManager);

            // And we're done!
            return;
        }

        HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
        hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
        r = new Report(4045);
        r.subject = ae.getId();
        r.add(toHit.getTableDesc());
        r.add(te.getLocationAbbr(hit));
        gameManager.addReport(r);

        // The building shields all units from a certain amount of damage.
        // The amount is based upon the building's CF at the phase's start.
        if (targetInBuilding && (bldg != null)) {
            int bldgAbsorbs = bldg.getAbsorbtion(target.getPosition());
            int toBldg = Math.min(bldgAbsorbs, damage);
            damage -= toBldg;
            gameManager.addNewLines();
            Vector<Report> buildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, damage, target.getPosition(), gameManager);
            for (Report report : buildingReport) {
                report.subject = ae.getId();
            }
            gameManager.reportManager.addReport(buildingReport, gameManager);

            // some buildings scale remaining damage that is not absorbed
            // TODO : this isn't quite right for castles brian
            damage = (int) Math.floor(bldg.getDamageToScale() * damage);
        }

        // A building may absorb the entire shot.
        if (damage == 0) {
            r = new Report(4050);
            r.subject = ae.getId();
            r.add(te.getShortName());
            r.add(te.getOwner().getName());
            r.newlines = 0;
            gameManager.addReport(r);
        } else {
            if (glancing) {
                // Round up glancing blows against conventional infantry
                damage = (int) (te.isConventionalInfantry() ? Math.ceil(damage / 2.0) : Math.floor(damage / 2.0));
            }

            if (directBlow) {
                damage += toHit.getMoS() / 3;
                hit.makeDirectBlow(toHit.getMoS() / 3);
            }

            int leg;
            switch (kaa.getLeg()) {
                case KickAttackAction.LEFT:
                    leg = (ae instanceof QuadMech) ? Mech.LOC_LARM : Mech.LOC_LLEG;
                    break;
                case KickAttackAction.RIGHT:
                    leg = (ae instanceof QuadMech) ? Mech.LOC_RARM : Mech.LOC_RLEG;
                    break;
                case KickAttackAction.LEFTMULE:
                    leg = Mech.LOC_LLEG;
                    break;
                case KickAttackAction.RIGHTMULE:
                default:
                    leg = Mech.LOC_RLEG;
                    break;
            }
            damage = gameManager.combatManager.checkForSpikes(te, hit.getLocation(), damage, ae, leg, gameManager);
            DamageType damageType = DamageType.NONE;
            gameManager.reportManager.addReport(gameManager.damageEntity(te, hit, damage, false, damageType, false,
                    false, throughFront), gameManager);
            if (target instanceof VTOL) {
                // destroy rotor
                gameManager.reportManager.addReport(gameManager.applyCriticalHit(te, VTOL.LOC_ROTOR,
                        new CriticalSlot(CriticalSlot.TYPE_SYSTEM, VTOL.CRIT_ROTOR_DESTROYED),
                        false, 0, false), gameManager);
            }

            if (te.hasQuirk(OptionsConstants.QUIRK_NEG_WEAK_LEGS)) {
                gameManager.addNewLines();
                gameManager.reportManager.addReport(gameManager.criticalEntity(te, hit.getLocation(), hit.isRear(), 0, 0), gameManager);
            }
        }

        if (te.canFall()) {
            PilotingRollData kickPRD = gameManager.getKickPushPSR(te, ae, te, "was kicked");
            gameManager.game.addPSR(kickPRD);
        }

        // if the target is an industrial mech, it needs to check for crits at the end of turn
        if ((te instanceof Mech) && ((Mech) te).isIndustrial()) {
            ((Mech) te).setCheckForCrit(true);
        }

        gameManager.addNewLines();
    }

    /**
     * Handle a kick attack
     * @param pr
     * @param lastEntityId
     * @param gameManager
     */
    protected void resolveJumpJetAttack(PhysicalResult pr, int lastEntityId, GameManager gameManager) {
        JumpJetAttackAction kaa = (JumpJetAttackAction) pr.aaa;
        final Entity ae = gameManager.game.getEntity(kaa.getEntityId());
        final Targetable target = gameManager.game.getTarget(kaa.getTargetType(), kaa.getTargetId());
        Entity te = null;
        if (target.getTargetType() == Targetable.TYPE_ENTITY) {
            te = (Entity) target;
        }
        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute.isThroughFrontHex(gameManager.game, ae.getPosition(), te);
        }
        String legName;
        switch (kaa.getLeg()) {
            case JumpJetAttackAction.LEFT:
                legName = "Left leg";
                break;
            case JumpJetAttackAction.RIGHT:
                legName = "Right leg";
                break;
            default:
                legName = "Both legs";
                break;
        }

        Report r;

        // get damage, ToHitData and roll from the PhysicalResult
        int damage = pr.damage;
        final ToHitData toHit = pr.toHit;
        int roll = pr.roll.getIntValue();
        final boolean targetInBuilding = Compute.isInBuilding(gameManager.game, te);
        final boolean glancing = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS)
                && (roll == toHit.getValue());

        // Set Margin of Success/Failure.
        toHit.setMoS(roll - Math.max(2, toHit.getValue()));
        final boolean directBlow = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW)
                && ((toHit.getMoS() / 3) >= 1);

        // Which building takes the damage?
        Building bldg = gameManager.game.getBoard().getBuildingAt(target.getPosition());

        if (lastEntityId != ae.getId()) {
            // who is making the attacks
            r = new Report(4005);
            r.subject = ae.getId();
            r.addDesc(ae);
            gameManager.addReport(r);
        }

        r = new Report(4290);
        r.subject = ae.getId();
        r.indent();
        r.add(legName);
        r.add(target.getDisplayName());
        r.newlines = 0;
        gameManager.addReport(r);

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            r = new Report(4075);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            gameManager.addReport(r);
            return;
        } else if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            r = new Report(4080);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            r.newlines = 0;
            gameManager.addReport(r);
            roll = Integer.MAX_VALUE;
        } else {
            r = new Report(4025);
            r.subject = ae.getId();
            r.add(toHit);
            r.add(roll);
            r.newlines = 0;
            gameManager.addReport(r);
            if (glancing) {
                r = new Report(3186);
                r.subject = ae.getId();
                r.newlines = 0;
                gameManager.addReport(r);
            }

            if (directBlow) {
                r = new Report(3189);
                r.subject = ae.getId();
                r.newlines = 0;
                gameManager.addReport(r);
            }

        }

        // do we hit?
        if (roll < toHit.getValue()) {
            // miss
            r = new Report(4035);
            r.subject = ae.getId();
            gameManager.addReport(r);

            // If the target is in a building, the building absorbs the damage.
            if (targetInBuilding && (bldg != null)) {
                damage += pr.damageRight;
                // Only report if damage was done to the building.
                if (damage > 0) {
                    Vector<Report> buildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, damage, target.getPosition(), gameManager);
                    for (Report report : buildingReport) {
                        report.subject = ae.getId();
                    }
                    gameManager.reportManager.addReport(buildingReport, gameManager);
                }
            }
            return;
        }

        // Targeting a building.
        if ((target.getTargetType() == Targetable.TYPE_BUILDING)
                || (target.getTargetType() == Targetable.TYPE_FUEL_TANK)) {
            damage += pr.damageRight;
            // The building takes the full brunt of the attack.
            r = new Report(4040);
            r.subject = ae.getId();
            gameManager.addReport(r);
            Vector<Report> buildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, damage, target.getPosition(), gameManager);
            for (Report report : buildingReport) {
                report.subject = ae.getId();
            }
            gameManager.reportManager.addReport(buildingReport, gameManager);

            // Damage any infantry in the hex.
            gameManager.reportManager.addReport(gameManager.environmentalEffectManager.damageInfantryIn(bldg, damage, target.getPosition(), gameManager), gameManager);

            // And we're done!
            return;
        }

        r = new Report(4040);
        r.subject = ae.getId();
        r.newlines = 0;
        gameManager.addReport(r);

        for (int leg = 0; leg < 2; leg++) {
            if (leg == 1) {
                damage = pr.damageRight;
                if (damage == 0) {
                    break;
                }
            }
            HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
            hit.setGeneralDamageType(HitData.DAMAGE_ENERGY);

            // The building shields all units from a certain amount of damage.
            // The amount is based upon the building's CF at the phase's start.
            if (targetInBuilding && (bldg != null)) {
                int bldgAbsorbs = bldg.getAbsorbtion(target.getPosition());
                int toBldg = Math.min(bldgAbsorbs, damage);
                damage -= toBldg;
                gameManager.addNewLines();
                Vector<Report> buildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, damage, target.getPosition(), gameManager);
                for (Report report : buildingReport) {
                    report.subject = ae.getId();
                }
                gameManager.reportManager.addReport(buildingReport, gameManager);

                // some buildings scale remaining damage that is not absorbed
                // TODO : this isn't quite right for castles brian
                damage = (int) Math.floor(bldg.getDamageToScale() * damage);
            }

            // A building may absorb the entire shot.
            if (damage == 0) {
                r = new Report(4050);
                r.subject = ae.getId();
                r.add(te.getShortName());
                r.add(te.getOwner().getName());
                r.newlines = 0;
                gameManager.addReport(r);
            } else {
                if (glancing) {
                    // Round up glancing blows against conventional infantry
                    damage = (int) (te.isConventionalInfantry() ? Math.ceil(damage / 2.0) : Math.floor(damage / 2.0));
                }
                if (directBlow) {
                    damage += toHit.getMoS() / 3;
                    hit.makeDirectBlow(toHit.getMoS() / 3);
                }
                gameManager.reportManager.addReport(gameManager.damageEntity(te, hit, damage, false, DamageType.NONE,
                        false, false, throughFront), gameManager);
            }
        }

        gameManager.addNewLines();

        // if the target is an industrial mech, it needs to check for crits at the end of turn
        if ((target instanceof Mech) && ((Mech) target).isIndustrial()) {
            ((Mech) target).setCheckForCrit(true);
        }
    }

    /**
     * Handle a ProtoMech physical attack
     * @param pr
     * @param lastEntityId
     * @param gameManager
     */
    protected void resolveProtoAttack(PhysicalResult pr, int lastEntityId, GameManager gameManager) {
        final ProtomechPhysicalAttackAction ppaa = (ProtomechPhysicalAttackAction) pr.aaa;
        final Entity ae = gameManager.game.getEntity(ppaa.getEntityId());
        // get damage, ToHitData and roll from the PhysicalResult
        int damage = pr.damage;
        final ToHitData toHit = pr.toHit;
        int rollValue = pr.roll.getIntValue();
        final Targetable target = gameManager.game.getTarget(ppaa.getTargetType(), ppaa.getTargetId());
        Entity te = null;
        if (target.getTargetType() == Targetable.TYPE_ENTITY) {
            te = (Entity) target;
        }
        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute.isThroughFrontHex(gameManager.game, ae.getPosition(), te);
        }
        final boolean targetInBuilding = Compute.isInBuilding(gameManager.game, te);
        final boolean glancing = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS)
                && (rollValue == toHit.getValue());
        // Set Margin of Success/Failure.
        toHit.setMoS(rollValue - Math.max(2, toHit.getValue()));
        final boolean directBlow = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW)
                && ((toHit.getMoS() / 3) >= 1);

        Report r;

        // Which building takes the damage?
        Building bldg = gameManager.game.getBoard().getBuildingAt(target.getPosition());

        if (lastEntityId != ae.getId()) {
            // who is making the attacks
            r = new Report(4005);
            r.subject = ae.getId();
            r.addDesc(ae);
            gameManager.addReport(r);
        }

        r = new Report(4070);
        r.subject = ae.getId();
        r.indent();
        r.add(target.getDisplayName());
        r.newlines = 0;
        gameManager.addReport(r);

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            r = new Report(4075);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            gameManager.addReport(r);
            return;
        } else if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            r = new Report(4080);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            r.newlines = 0;
            gameManager.addReport(r);
            rollValue = Integer.MAX_VALUE;
        } else {
            // report the roll
            r = new Report(4025);
            r.subject = ae.getId();
            r.add(toHit);
            r.add(pr.roll);
            r.newlines = 0;
            gameManager.addReport(r);
            if (glancing) {
                r = new Report(3186);
                r.subject = ae.getId();
                r.newlines = 0;
                gameManager.addReport(r);
            }

            if (directBlow) {
                r = new Report(3189);
                r.subject = ae.getId();
                r.newlines = 0;
                gameManager.addReport(r);
            }
        }

        // do we hit?
        if (rollValue < toHit.getValue()) {
            // miss
            r = new Report(4035);
            r.subject = ae.getId();
            gameManager.addReport(r);

            // If the target is in a building, the building absorbs the damage.
            if (targetInBuilding && (bldg != null)) {
                // Only report if damage was done to the building.
                if (damage > 0) {
                    Vector<Report> buildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, damage, target.getPosition(), gameManager);
                    for (Report report : buildingReport) {
                        report.subject = ae.getId();
                    }
                    gameManager.reportManager.addReport(buildingReport, gameManager);
                }
            }
            return;
        }

        // Targeting a building.
        if ((target.getTargetType() == Targetable.TYPE_BUILDING)
                || (target.getTargetType() == Targetable.TYPE_FUEL_TANK)) {
            // The building takes the full brunt of the attack.
            r = new Report(4040);
            r.subject = ae.getId();
            gameManager.addReport(r);
            Vector<Report> buildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, damage, target.getPosition(), gameManager);
            for (Report report : buildingReport) {
                report.subject = ae.getId();
            }
            gameManager.reportManager.addReport(buildingReport, gameManager);

            // Damage any infantry in the hex.
            gameManager.reportManager.addReport(gameManager.environmentalEffectManager.damageInfantryIn(bldg, damage, target.getPosition(), gameManager), gameManager);

            // And we're done!
            return;
        }

        HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
        hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);

        r = new Report(4045);
        r.subject = ae.getId();
        r.add(toHit.getTableDesc());
        r.add(te.getLocationAbbr(hit));
        gameManager.addReport(r);

        // The building shields all units from a certain amount of damage.
        // The amount is based upon the building's CF at the phase's start.
        if (targetInBuilding && (bldg != null)) {
            int bldgAbsorbs = bldg.getAbsorbtion(target.getPosition());
            int toBldg = Math.min(bldgAbsorbs, damage);
            damage -= toBldg;
            gameManager.addNewLines();
            Vector<Report> buildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, damage, target.getPosition(), gameManager);
            for (Report report : buildingReport) {
                report.subject = ae.getId();
            }
            gameManager.reportManager.addReport(buildingReport, gameManager);

            // some buildings scale remaining damage that is not absorbed
            // TODO : this isn't quite right for castles brian
            damage = (int) Math.floor(bldg.getDamageToScale() * damage);
        }

        // A building may absorb the entire shot.
        if (damage == 0) {
            r = new Report(4050);
            r.subject = ae.getId();
            r.add(te.getShortName());
            r.add(te.getOwner().getName());
            r.newlines = 0;
            gameManager.addReport(r);
        } else {
            if (glancing) {
                // Round up glancing blows against conventional infantry
                damage = (int) (te.isConventionalInfantry() ? Math.ceil(damage / 2.0) : Math.floor(damage / 2.0));
            }

            if (directBlow) {
                damage += toHit.getMoS() / 3;
                hit.makeDirectBlow(toHit.getMoS() / 3);
            }
            gameManager.reportManager.addReport(gameManager.damageEntity(te, hit, damage, false, DamageType.NONE,
                    false, false, throughFront), gameManager);
            if (((Protomech) ae).isEDPCharged()) {
                r = new Report(3701);
                Roll diceRoll2 = Compute.rollD6(2);
                int rollValue2 = diceRoll2.getIntValue() - 2;
                String rollCalc2 = rollValue2 + " [" + diceRoll2.getIntValue() + " - 2]";
                r.addDataWithTooltip(rollCalc2, diceRoll2.getReport());
                r.newlines = 0;
                gameManager.vPhaseReport.add(r);

                if (te instanceof BattleArmor) {
                    r = new Report(3706);
                    r.addDesc(te);
                    // shut down for rest of scenario, so we actually kill it
                    // TODO : fix for salvage purposes
                    HitData targetTrooper = te.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                    r.add(te.getLocationAbbr(targetTrooper));
                    gameManager.vPhaseReport.add(r);
                    gameManager.vPhaseReport.addAll(gameManager.criticalEntity(ae, targetTrooper.getLocation(),
                            targetTrooper.isRear(), 0, false, false, 0));
                } else if (te instanceof Mech) {
                    if (((Mech) te).isIndustrial()) {
                        if (rollValue2 >= 8) {
                            r = new Report(3705);
                            r.addDesc(te);
                            r.add(4);
                            te.taserShutdown(4, false);
                        } else {
                            // suffer +2 to piloting and gunnery for 4 rounds
                            r = new Report(3710);
                            r.addDesc(te);
                            r.add(2);
                            r.add(4);
                            te.setTaserInterference(2, 4, true);
                        }
                    } else {
                        if (rollValue2 >= 11) {
                            r = new Report(3705);
                            r.addDesc(te);
                            r.add(3);
                            gameManager.vPhaseReport.add(r);
                            te.taserShutdown(3, false);
                        } else {
                            r = new Report(3710);
                            r.addDesc(te);
                            r.add(2);
                            r.add(3);
                            gameManager.vPhaseReport.add(r);
                            te.setTaserInterference(2, 3, true);
                        }
                    }
                } else if ((te instanceof Protomech) || (te instanceof Tank)
                        || (te instanceof Aero)) {
                    if (rollValue2 >= 8) {
                        r = new Report(3705);
                        r.addDesc(te);
                        r.add(4);
                        gameManager.vPhaseReport.add(r);
                        te.taserShutdown(4, false);
                    } else {
                        r = new Report(3710);
                        r.addDesc(te);
                        r.add(2);
                        r.add(4);
                        gameManager.vPhaseReport.add(r);
                        te.setTaserInterference(2, 4, false);
                    }
                }

            }
        }

        gameManager.addNewLines();

        // if the target is an industrial mech, it needs to check for crits at the end of turn
        if ((target instanceof Mech) && ((Mech) target).isIndustrial()) {
            ((Mech) target).setCheckForCrit(true);
        }
    }

    /**
     * Handle a brush off attack
     * @param pr
     * @param lastEntityId
     * @param gameManager
     */
    protected void resolveBrushOffAttack(PhysicalResult pr, int lastEntityId, GameManager gameManager) {
        final BrushOffAttackAction baa = (BrushOffAttackAction) pr.aaa;
        final Entity ae = gameManager.game.getEntity(baa.getEntityId());
        // PLEASE NOTE: buildings are *never* the target
        // of a "brush off", but iNarc pods **are**.
        Targetable target = gameManager.game.getTarget(baa.getTargetType(), baa.getTargetId());
        Entity te = null;
        final String armName = baa.getArm() == BrushOffAttackAction.LEFT ? "Left Arm" : "Right Arm";
        Report r;

        if (target.getTargetType() == Targetable.TYPE_ENTITY) {
            te = gameManager.game.getEntity(baa.getTargetId());
        }

        // get damage, ToHitData and roll from the PhysicalResult
        // ASSUMPTION: buildings can't absorb *this* damage.
        int damage = baa.getArm() == BrushOffAttackAction.LEFT ? pr.damage : pr.damageRight;
        final ToHitData toHit = baa.getArm() == BrushOffAttackAction.LEFT ? pr.toHit : pr.toHitRight;
        int rollValue = baa.getArm() == BrushOffAttackAction.LEFT ? pr.roll.getIntValue() : pr.rollRight.getIntValue();

        if (lastEntityId != baa.getEntityId()) {
            // who is making the attacks
            r = new Report(4005);
            r.subject = ae.getId();
            r.addDesc(ae);
            gameManager.addReport(r);
        }

        r = new Report(4085);
        r.subject = ae.getId();
        r.indent();
        r.add(target.getDisplayName());
        r.add(armName);
        r.newlines = 0;
        gameManager.addReport(r);

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            r = new Report(4090);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            gameManager.addReport(r);
            return;
        }

        // report the roll
        r = new Report(4025);
        r.subject = ae.getId();
        r.add(toHit);
        r.add(baa.getArm() == BrushOffAttackAction.LEFT ? pr.roll : pr.rollRight);
        r.newlines = 0;
        gameManager.addReport(r);

        // do we hit?
        if (rollValue < toHit.getValue()) {
            // miss
            r = new Report(4035);
            r.subject = ae.getId();
            gameManager.addReport(r);

            // Missed Brush Off attacks cause punch damage to the attacker.
            toHit.setHitTable(ToHitData.HIT_PUNCH);
            toHit.setSideTable(ToHitData.SIDE_FRONT);
            HitData hit = ae.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
            hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
            r = new Report(4095);
            r.subject = ae.getId();
            r.addDesc(ae);
            r.add(ae.getLocationAbbr(hit));
            r.newlines = 0;
            gameManager.addReport(r);
            gameManager.reportManager.addReport(gameManager.damageEntity(ae, hit, damage), gameManager);
            gameManager.addNewLines();
            // if this is an industrial mech, it needs to check for crits
            // at the end of turn
            if ((ae instanceof Mech) && ((Mech) ae).isIndustrial()) {
                ((Mech) ae).setCheckForCrit(true);
            }
            return;
        }

        // Different target types get different handling.
        switch (target.getTargetType()) {
            case Targetable.TYPE_ENTITY:
                // Handle Entity targets.
                HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
                hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                r = new Report(4045);
                r.subject = ae.getId();
                r.add(toHit.getTableDesc());
                r.add(te.getLocationAbbr(hit));
                gameManager.addReport(r);
                gameManager.reportManager.addReport(gameManager.damageEntity(te, hit, damage), gameManager);
                gameManager.addNewLines();

                // Dislodge the swarming infantry.
                ae.setSwarmAttackerId(Entity.NONE);
                te.setSwarmTargetId(Entity.NONE);
                r = new Report(4100);
                r.subject = ae.getId();
                r.add(te.getDisplayName());
                gameManager.addReport(r);
                break;
            case Targetable.TYPE_INARC_POD:
                // Handle iNarc pod targets.
                // TODO : check the return code and handle false appropriately.
                ae.removeINarcPod((INarcPod) target);
                // // TODO : confirm that we don't need to update the attacker.
                // //killme
                // entityUpdate( ae.getId() ); // killme
                r = new Report(4105);
                r.subject = ae.getId();
                r.add(target.getDisplayName());
                gameManager.addReport(r);
                break;
            // TODO : add a default: case and handle it appropriately.
        }
    }

    /**
     * Handle a thrash attack
     * @param pr
     * @param lastEntityId
     * @param gameManager
     */
    protected void resolveThrashAttack(PhysicalResult pr, int lastEntityId, GameManager gameManager) {
        final ThrashAttackAction taa = (ThrashAttackAction) pr.aaa;
        final Entity ae = gameManager.game.getEntity(taa.getEntityId());

        // get damage, ToHitData and roll from the PhysicalResult
        int hits = pr.damage;
        final ToHitData toHit = pr.toHit;
        int rollValue = pr.roll.getIntValue();
        final boolean glancing = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS)
                && (rollValue == toHit.getValue());

        // Set Margin of Success/Failure.
        toHit.setMoS(rollValue - Math.max(2, toHit.getValue()));
        final boolean directBlow = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW)
                && ((toHit.getMoS() / 3) >= 1);

        // PLEASE NOTE: buildings are *never* the target of a "thrash".
        final Entity te = gameManager.game.getEntity(taa.getTargetId());
        Report r;

        if (lastEntityId != taa.getEntityId()) {
            // who is making the attacks
            r = new Report(4005);
            r.subject = ae.getId();
            r.addDesc(ae);
            gameManager.addReport(r);
        }

        r = new Report(4110);
        r.subject = ae.getId();
        r.indent();
        r.addDesc(te);
        r.newlines = 0;
        gameManager.addReport(r);

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            r = new Report(4115);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            gameManager.addReport(r);
            return;
        }

        // Thrash attack may hit automatically
        if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            r = new Report(4120);
        } else {
            // report the roll
            r = new Report(4025);
            r.subject = ae.getId();
            r.add(toHit);
            r.add(pr.roll);
            r.newlines = 0;
            gameManager.addReport(r);

            // do we hit?
            if (rollValue < toHit.getValue()) {
                // miss
                r = new Report(4035);
                r.subject = ae.getId();
                gameManager.addReport(r);
                return;
            }
            r = new Report(4125);
        }
        r.subject = ae.getId();
        r.newlines = 0;
        gameManager.addReport(r);

        // Standard damage loop in 5 point clusters.
        if (glancing) {
            hits = (int) Math.floor(hits / 2.0);
        }

        if (directBlow) {
            hits += toHit.getMoS() / 3;
        }

        r = new Report(4130);
        r.subject = ae.getId();
        r.add(hits);
        r.newlines = 0;
        gameManager.addReport(r);
        if (glancing) {
            r = new Report(3186);
            r.subject = ae.getId();
            r.newlines = 0;
            gameManager.addReport(r);
        }

        if (directBlow) {
            r = new Report(3189);
            r.subject = ae.getId();
            r.newlines = 0;
            gameManager.addReport(r);
        }

        while (hits > 0) {
            int damage = Math.min(5, hits);
            hits -= damage;
            HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
            hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
            r = new Report(4135);
            r.subject = ae.getId();
            r.add(te.getLocationAbbr(hit));
            r.newlines = 0;
            gameManager.addReport(r);
            gameManager.reportManager.addReport(gameManager.damageEntity(te, hit, damage), gameManager);
        }

        gameManager.addNewLines();

        // Thrash attacks cause PSRs. Failed PSRs cause falling damage.
        // This fall damage applies even though the Thrashing Mek is prone.
        PilotingRollData rollData = ae.getBasePilotingRoll();
        ae.addPilotingModifierForTerrain(rollData);
        rollData.addModifier(0, "thrashing at infantry");
        r = new Report(4140);
        r.subject = ae.getId();
        r.addDesc(ae);
        gameManager.addReport(r);
        final Roll diceRoll2 = Compute.rollD6(2);
        r = new Report(2190);
        r.subject = ae.getId();
        r.add(rollData.getValueAsString());
        r.add(rollData.getDesc());
        r.add(diceRoll2);
        if (diceRoll2.getIntValue() < rollData.getValue()) {
            r.choose(false);
            gameManager.addReport(r);
            gameManager.reportManager.addReport(gameManager.doEntityFall(ae, rollData), gameManager);
        } else {
            r.choose(true);
            gameManager.addReport(r);
        }
    }

    /**
     * Handle a thrash attack
     * @param pr
     * @param lastEntityId
     * @param gameManager
     */
    protected void resolveBAVibroClawAttack(PhysicalResult pr, int lastEntityId, GameManager gameManager) {
        final BAVibroClawAttackAction bvaa = (BAVibroClawAttackAction) pr.aaa;
        final Entity ae = gameManager.game.getEntity(bvaa.getEntityId());

        // get damage, ToHitData and roll from the PhysicalResult
        int hits = pr.damage;
        final ToHitData toHit = pr.toHit;
        int rollValue = pr.roll.getIntValue();
        final boolean glancing = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS)
                && (rollValue == toHit.getValue());

        // Set Margin of Success/Failure.
        toHit.setMoS(rollValue - Math.max(2, toHit.getValue()));
        final boolean directBlow = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW)
                && ((toHit.getMoS() / 3) >= 1);

        // PLEASE NOTE: buildings are *never* the target of a BA vibroclaw attack.
        final Entity te = gameManager.game.getEntity(bvaa.getTargetId());
        Report r;

        if (lastEntityId != bvaa.getEntityId()) {
            // who is making the attacks
            r = new Report(4005);
            r.subject = ae.getId();
            r.addDesc(ae);
            gameManager.addReport(r);
        }

        r = new Report(4146);
        r.subject = ae.getId();
        r.indent();
        r.addDesc(te);
        r.newlines = 0;
        gameManager.addReport(r);

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            r = new Report(4147);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            gameManager.addReport(r);
            return;
        }

        // we may hit automatically
        if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            r = new Report(4120);
            r.subject = ae.getId();
            r.newlines = 0;
            gameManager.addReport(r);
        } else {
            // report the roll
            r = new Report(4025);
            r.subject = ae.getId();
            r.add(toHit);
            r.add(pr.roll);
            r.newlines = 0;
            gameManager.addReport(r);

            // do we hit?
            if (rollValue < toHit.getValue()) {
                // miss
                r = new Report(4035);
                r.subject = ae.getId();
                gameManager.addReport(r);
                return;
            }
        }

        // Standard damage loop
        if (glancing) {
            hits = (int) Math.floor(hits / 2.0);
        }

        if (directBlow) {
            hits += toHit.getMoS() / 3;
        }

        if (te.isConventionalInfantry()) {
            r = new Report(4149);
            r.subject = ae.getId();
            r.add(hits);
        } else {
            r = new Report(4148);
            r.subject = ae.getId();
            r.add(hits);
            r.add(ae.getVibroClaws());
        }
        r.newlines = 0;
        gameManager.addReport(r);
        if (glancing) {
            r = new Report(3186);
            r.subject = ae.getId();
            r.newlines = 0;
            gameManager.addReport(r);
        }

        if (directBlow) {
            r = new Report(3189);
            r.subject = ae.getId();
            r.newlines = 0;
            gameManager.addReport(r);
        }

        while (hits > 0) {
            // BA get hit separately by each attacking BA trooper
            int damage = Math.min(ae.getVibroClaws(), hits);
            // conventional infantry get hit in one lump
            if (te.isConventionalInfantry()) {
                damage = hits;
            }
            hits -= damage;
            HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
            hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
            r = new Report(4135);
            r.subject = ae.getId();
            r.add(te.getLocationAbbr(hit));
            r.newlines = 0;
            gameManager.addReport(r);
            gameManager.reportManager.addReport(gameManager.damageEntity(te, hit, damage), gameManager);
        }
        gameManager.addNewLines();
    }

    /**
     * Handle a club attack
     * @param pr
     * @param lastEntityId
     * @param gameManager
     */
    protected void resolveClubAttack(PhysicalResult pr, int lastEntityId, GameManager gameManager) {
        final ClubAttackAction caa = (ClubAttackAction) pr.aaa;
        final Entity ae = gameManager.game.getEntity(caa.getEntityId());
        // get damage, ToHitData and roll from the PhysicalResult
        int damage = pr.damage;
        // LAMs in airmech mode do half damage if airborne.
        if (ae.isAirborneVTOLorWIGE()) {
            damage = (int) Math.ceil(damage * 0.5);
        }
        final ToHitData toHit = pr.toHit;
        // TargetRoll.AUTOMATIC_SUCCESS from Flail/Wrecking Ball auto misses on a 2 and hits themself.
        int rollValue = toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS ? Integer.MAX_VALUE : pr.roll.getIntValue();
        final Targetable target = gameManager.game.getTarget(caa.getTargetType(), caa.getTargetId());
        Entity te = null;
        if (target.getTargetType() == Targetable.TYPE_ENTITY) {
            te = (Entity) target;
        }
        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute
                    .isThroughFrontHex(gameManager.game, ae.getPosition(), te);
        }
        final boolean targetInBuilding = Compute.isInBuilding(gameManager.game, te);
        final boolean glancing = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS)
                && (rollValue == toHit.getValue());

        // Set Margin of Success/Failure.
        // Make sure the MoS is zero for *automatic* hits in case direct blows
        // are in force.
        toHit.setMoS((rollValue == Integer.MAX_VALUE) ? 0 : rollValue - Math.max(2, toHit.getValue()));
        final boolean directBlow = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW)
                && ((toHit.getMoS() / 3) >= 1);

        Report r;

        // Which building takes the damage?
        Building bldg = gameManager.game.getBoard().getBuildingAt(target.getPosition());

        // restore club attack
        caa.getClub().restore();

        // Shield bash causes 1 point of damage to the shield
        if (((MiscType) caa.getClub().getType()).isShield()) {
            ((Mech) ae).shieldAbsorptionDamage(1, caa.getClub().getLocation(), false);
        }

        if (lastEntityId != caa.getEntityId()) {
            // who is making the attacks
            r = new Report(4005);
            r.subject = ae.getId();
            r.addDesc(ae);
            gameManager.addReport(r);
        }

        r = new Report(4145);
        r.subject = ae.getId();
        r.indent();
        r.add(caa.getClub().getName());
        r.add(target.getDisplayName());
        r.newlines = 0;
        gameManager.addReport(r);

        // Flail/Wrecking Ball auto misses on a 2 and hits themself.
        if ((caa.getClub().getType().hasSubType(MiscType.S_FLAIL)
                || caa.getClub().getType().hasSubType(MiscType.S_WRECKING_BALL)) && (rollValue == 2)) {
            // miss
            r = new Report(4025);
            r.subject = ae.getId();
            r.add(toHit);
            r.add(pr.roll);
            r.newlines = 0;
            gameManager.addReport(r);// miss
            r = new Report(4035);
            r.subject = ae.getId();
            gameManager.addReport(r);
            // setup autohit
            ToHitData newToHit = new ToHitData(TargetRoll.AUTOMATIC_SUCCESS, "hit with own flail/wrecking ball");
            pr.damage = ClubAttackAction.getDamageFor(ae, caa.getClub(), false, caa.isZweihandering());
            pr.damage = (pr.damage / 2) + (pr.damage % 2);
            newToHit.setHitTable(ToHitData.HIT_NORMAL);
            newToHit.setSideTable(ToHitData.SIDE_FRONT);
            pr.toHit = newToHit;
            pr.aaa.setTargetId(ae.getId());
            pr.aaa.setTargetType(Targetable.TYPE_ENTITY);
            resolveClubAttack(pr, ae.getId(), gameManager);
            if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                gameManager.game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed a flail/wrecking ball attack"));
            } else {
                gameManager.game.addPSR(new PilotingRollData(ae.getId(), 0, "missed a flail/wrecking ball attack"));
            }

            if (caa.isZweihandering()) {
                gameManager.applyZweihanderSelfDamage(ae, true, caa.getClub().getLocation());
            }
            return;
        }

        // Need to compute 2d6 damage. and add +3 heat build up.
        if (caa.getClub().getType().hasSubType(MiscType.S_BUZZSAW)) {
            damage = Compute.d6(2);
            ae.heatBuildup += 3;

            // Buzzsaw's blade will shatter on a roll of 2.
            if (rollValue == 2) {
                Mounted club = caa.getClub();

                for (Mounted eq : ae.getWeaponList()) {
                    if ((eq.getLocation() == club.getLocation())
                            && (eq.getType() instanceof MiscType)
                            && eq.getType().hasFlag(MiscType.F_CLUB)
                            && eq.getType().hasSubType(MiscType.S_BUZZSAW)) {
                        eq.setHit(true);
                        break;
                    }
                }
                r = new Report(4037);
                r.subject = ae.getId();
                gameManager.addReport(r);
                if (caa.isZweihandering()) {
                    gameManager.applyZweihanderSelfDamage(ae, true, caa.getClub().getLocation());
                }
                return;
            }
        }

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            r = new Report(4075);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            gameManager.addReport(r);
            if (caa.getClub().getType().hasSubType(MiscType.S_MACE)) {
                if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                    gameManager.game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed a mace attack"));
                } else {
                    gameManager.game.addPSR(new PilotingRollData(ae.getId(), 0, "missed a mace attack"));
                }
            }

            if (caa.isZweihandering()) {
                if (caa.getClub().getType().hasSubType(MiscType.S_CLUB)) {
                    gameManager.applyZweihanderSelfDamage(ae, true, Mech.LOC_RARM, Mech.LOC_LARM);
                } else {
                    gameManager.applyZweihanderSelfDamage(ae, true, caa.getClub().getLocation());
                }
            }
            return;
        } else if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            r = new Report(4080);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            r.newlines = 0;
            gameManager.addReport(r);
            rollValue = Integer.MAX_VALUE;
        } else {
            // report the roll
            r = new Report(4025);
            r.subject = ae.getId();
            r.add(toHit);
            r.add(pr.roll);
            r.newlines = 0;
            gameManager.addReport(r);
            if (glancing) {
                r = new Report(3186);
                r.subject = ae.getId();
                r.newlines = 0;
                gameManager.addReport(r);
            }

            if (directBlow) {
                r = new Report(3189);
                r.subject = ae.getId();
                r.newlines = 0;
                gameManager.addReport(r);
            }
        }

        // do we hit?
        if (rollValue < toHit.getValue()) {
            // miss
            r = new Report(4035);
            r.subject = ae.getId();
            gameManager.addReport(r);

            if (caa.getClub().getType().hasSubType(MiscType.S_MACE)) {
                if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                    gameManager.game.addControlRoll(new PilotingRollData(ae.getId(), 2, "missed a mace attack"));
                } else {
                    gameManager.game.addPSR(new PilotingRollData(ae.getId(), 2, "missed a mace attack"));
                }
            }

            // If the target is in a building, the building absorbs the damage.
            if (targetInBuilding && (bldg != null)) {
                // Only report if damage was done to the building.
                if (damage > 0) {
                    Vector<Report> buildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, damage, target.getPosition(), gameManager);
                    for (Report report : buildingReport) {
                        report.subject = ae.getId();
                    }
                    gameManager.reportManager.addReport(buildingReport, gameManager);
                }
            }

            if (caa.isZweihandering()) {
                if (caa.getClub().getType().hasSubType(MiscType.S_CLUB)) {
                    gameManager.applyZweihanderSelfDamage(ae, true, Mech.LOC_RARM, Mech.LOC_LARM);
                } else {
                    gameManager.applyZweihanderSelfDamage(ae, true, caa.getClub().getLocation());
                }
            }
            return;
        }

        // Targeting a building.
        if ((target.getTargetType() == Targetable.TYPE_BUILDING)
                || (target.getTargetType() == Targetable.TYPE_FUEL_TANK)) {
            // The building takes the full brunt of the attack.
            r = new Report(4040);
            r.subject = ae.getId();
            gameManager.addReport(r);
            Vector<Report> buildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, damage, target.getPosition(), gameManager);
            for (Report report : buildingReport) {
                report.subject = ae.getId();
            }
            gameManager.reportManager.addReport(buildingReport, gameManager);

            // Damage any infantry in the hex.
            gameManager.reportManager.addReport(gameManager.environmentalEffectManager.damageInfantryIn(bldg, damage, target.getPosition(), gameManager), gameManager);

            if (caa.isZweihandering()) {
                if (caa.getClub().getType().hasSubType(MiscType.S_CLUB)) {
                    gameManager.applyZweihanderSelfDamage(ae, false, Mech.LOC_RARM, Mech.LOC_LARM);

                    // the club breaks
                    r = new Report(4150);
                    r.subject = ae.getId();
                    r.add(caa.getClub().getName());
                    gameManager.addReport(r);
                    ae.removeMisc(caa.getClub().getName());
                } else {
                    gameManager.applyZweihanderSelfDamage(ae, false, caa.getClub().getLocation());
                }
            }

            // And we're done!
            return;
        }

        HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
        hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
        r = new Report(4045);
        r.subject = ae.getId();
        r.add(toHit.getTableDesc());
        r.add(te.getLocationAbbr(hit));
        gameManager.addReport(r);

        // The building shields all units from a certain amount of damage.
        // The amount is based upon the building's CF at the phase's start.
        if (targetInBuilding && (bldg != null)) {
            int bldgAbsorbs = bldg.getAbsorbtion(target.getPosition());
            int toBldg = Math.min(bldgAbsorbs, damage);
            damage -= toBldg;
            gameManager.addNewLines();
            Vector<Report> buildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, damage, target.getPosition(), gameManager);
            for (Report report : buildingReport) {
                report.subject = ae.getId();
            }
            gameManager.reportManager.addReport(buildingReport, gameManager);

            // some buildings scale remaining damage that is not absorbed
            // TODO : this isn't quite right for castles brian
            damage = (int) Math.floor(bldg.getDamageToScale() * damage);
        }

        // A building may absorb the entire shot.
        if (damage == 0) {
            r = new Report(4050);
            r.subject = ae.getId();
            r.add(te.getShortName());
            r.add(te.getOwner().getName());
            r.newlines = 0;
            gameManager.addReport(r);
        } else {
            if (glancing) {
                // Round up glancing blows against conventional infantry
                damage = (int) (te.isConventionalInfantry() ? Math.ceil(damage / 2.0) : Math.floor(damage / 2.0));
            }

            if (directBlow) {
                damage += toHit.getMoS() / 3;
                hit.makeDirectBlow(toHit.getMoS() / 3);
            }

            damage = gameManager.combatManager.checkForSpikes(te, hit.getLocation(), damage, ae, Entity.LOC_NONE, gameManager);

            DamageType damageType = DamageType.NONE;
            gameManager.reportManager.addReport(gameManager.damageEntity(te, hit, damage, false, damageType, false,
                    false, throughFront), gameManager);
            if (target instanceof VTOL) {
                // destroy rotor
                gameManager.reportManager.addReport(gameManager.applyCriticalHit(te, VTOL.LOC_ROTOR,
                        new CriticalSlot(CriticalSlot.TYPE_SYSTEM, VTOL.CRIT_ROTOR_DESTROYED),
                        false, 0, false), gameManager);
            }
        }

        // On a roll of 10+ a lance hitting a mech/Vehicle can cause 1 point of
        // internal damage
        if (caa.getClub().getType().hasSubType(MiscType.S_LANCE)
                && (te.getArmor(hit) > 0)
                && (te.getArmorType(hit.getLocation()) != EquipmentType.T_ARMOR_HARDENED)
                && (te.getArmorType(hit.getLocation()) != EquipmentType.T_ARMOR_FERRO_LAMELLOR)) {
            Roll diceRoll2 = Compute.rollD6(2);
            // Pierce checking report
            r = new Report(4021);
            r.indent(2);
            r.subject = ae.getId();
            r.add(te.getLocationAbbr(hit));
            r.add(diceRoll2);
            gameManager.addReport(r);

            if (diceRoll2.getIntValue() >= 10) {
                hit.makeGlancingBlow();
                gameManager.reportManager.addReport(gameManager.damageEntity(te, hit, 1, false, DamageType.NONE,
                        true, false, throughFront), gameManager);
            }
        }

        // TODO : Verify this is correct according to latest rules
        if (caa.getClub().getType().hasSubType(MiscType.S_WRECKING_BALL)
                && (ae instanceof SupportTank) && (te instanceof Mech)) {
            // forces a PSR like a charge
            if (te instanceof LandAirMech && te.isAirborneVTOLorWIGE()) {
                gameManager.game.addControlRoll(new PilotingRollData(te.getId(), 2, "was hit by wrecking ball"));
            } else {
                gameManager.game.addPSR(new PilotingRollData(te.getId(), 2, "was hit by wrecking ball"));
            }
        }

        // Chain whips can entangle 'Mech and ProtoMech limbs. This
        // implementation assumes that in order to do so the limb must still
        // have some structure left, so if the whip hits and destroys a
        // location in the same attack no special effects take place.
        if (caa.getClub().getType().hasSubType(MiscType.S_CHAIN_WHIP)
                && ((te instanceof Mech) || (te instanceof Protomech))) {
            gameManager.addNewLines();

            int loc = hit.getLocation();

            boolean mightTrip = (te instanceof Mech)
                    && te.locationIsLeg(loc)
                    && !te.isLocationBad(loc)
                    && !te.isLocationDoomed(loc)
                    && !te.hasActiveShield(loc)
                    && !te.hasPassiveShield(loc);

            boolean mightGrapple = ((te instanceof Mech)
                    && ((loc == Mech.LOC_LARM) || (loc == Mech.LOC_RARM))
                    && !te.isLocationBad(loc)
                    && !te.isLocationDoomed(loc)
                    && !te.hasActiveShield(loc)
                    && !te.hasPassiveShield(loc)
                    && !te.hasNoDefenseShield(loc))
                    || ((te instanceof Protomech)
                    && ((loc == Protomech.LOC_LARM) || (loc == Protomech.LOC_RARM)
                    || (loc == Protomech.LOC_LEG))
                    // Only check location status after confirming we did
                    // hit a limb -- Protos have no actual near-miss
                    // "location" and will throw an exception if it's
                    // referenced here.
                    && !te.isLocationBad(loc)
                    && !te.isLocationDoomed(loc));

            if (mightTrip) {
                Roll diceRoll3 = Compute.rollD6(2);
                int toHitValue = toHit.getValue();
                String toHitDesc = toHit.getDesc();
                if ((ae instanceof Mech) && ((Mech) ae).hasActiveTSM(false)) {
                    toHitValue -= 2;
                    toHitDesc += " -2 (TSM Active Bonus)";
                }

                r = new Report(4450);
                r.subject = ae.getId();
                r.add(ae.getShortName());
                r.add(te.getShortName());
                r.addDataWithTooltip(toHitValue, toHitDesc);
                r.add(diceRoll3);
                r.indent(2);
                r.newlines = 0;
                gameManager.addReport(r);

                if (diceRoll3.getIntValue() >= toHitValue) {
                    r = new Report(2270);
                    r.subject = ae.getId();
                    r.newlines = 0;
                    gameManager.addReport(r);

                    gameManager.game.addPSR(new PilotingRollData(te.getId(), 3, "Snared by chain whip"));
                } else {
                    r = new Report(2357);
                    r.subject = ae.getId();
                    r.newlines = 0;
                    gameManager.addReport(r);
                }
            } else if (mightGrapple) {
                GrappleAttackAction gaa = new GrappleAttackAction(ae.getId(), te.getId());
                int grappleSide;
                if (caa.getClub().getLocation() == Mech.LOC_RARM) {
                    grappleSide = Entity.GRAPPLE_RIGHT;
                } else {
                    grappleSide = Entity.GRAPPLE_LEFT;
                }
                ToHitData grappleHit = GrappleAttackAction.toHit(gameManager.game, ae.getId(), target,
                        grappleSide, true);
                PhysicalResult grappleResult = new PhysicalResult();
                grappleResult.aaa = gaa;
                grappleResult.toHit = grappleHit;
                grappleResult.roll = Compute.rollD6(2);
                gameManager.combatManager.resolveGrappleAttack(grappleResult, lastEntityId, grappleSide,
                        (hit.getLocation() == Mech.LOC_RARM) ? Entity.GRAPPLE_RIGHT : Entity.GRAPPLE_LEFT, gameManager);
            }
        }

        gameManager.addNewLines();

        if (caa.isZweihandering()) {
            if (caa.getClub().getType().hasSubType(MiscType.S_CLUB)) {
                gameManager.applyZweihanderSelfDamage(ae, false, Mech.LOC_RARM, Mech.LOC_LARM);
            } else {
                gameManager.applyZweihanderSelfDamage(ae, false, caa.getClub().getLocation());
            }
        }

        // If the attacker is Zweihandering with an improvised club, it will break on the attack.
        // Otherwise, only a tree club will break on the attack
        if ((caa.isZweihandering() && caa.getClub().getType().hasSubType(MiscType.S_CLUB))
                || caa.getClub().getType().hasSubType(MiscType.S_TREE_CLUB)) {
            // the club breaks
            r = new Report(4150);
            r.subject = ae.getId();
            r.add(caa.getClub().getName());
            gameManager.addReport(r);
            ae.removeMisc(caa.getClub().getName());
        }

        gameManager.addNewLines();

        // if the target is an industrial mech, it needs to check for crits at the end of turn
        if ((target instanceof Mech) && ((Mech) target).isIndustrial()) {
            ((Mech) target).setCheckForCrit(true);
        }
    }

    /**
     * Handle a push attack
     * @param pr
     * @param lastEntityId
     * @param gameManager
     */
    protected void resolvePushAttack(PhysicalResult pr, int lastEntityId, GameManager gameManager) {
        final PushAttackAction paa = (PushAttackAction) pr.aaa;
        final Entity ae = gameManager.game.getEntity(paa.getEntityId());
        // PLEASE NOTE: buildings are *never* the target of a "push".
        final Entity te = gameManager.game.getEntity(paa.getTargetId());
        // get roll and ToHitData from the PhysicalResult
        int rollValue = pr.roll.getIntValue();
        final ToHitData toHit = pr.toHit;
        Report r;

        // was this push resolved earlier?
        if (pr.pushBackResolved) {
            return;
        }
        // don't try this one again
        pr.pushBackResolved = true;

        if (lastEntityId != paa.getEntityId()) {
            // who is making the attack
            r = new Report(4005);
            r.subject = ae.getId();
            r.addDesc(ae);
            gameManager.addReport(r);
        }

        r = new Report(4155);
        r.subject = ae.getId();
        r.indent();
        r.addDesc(te);
        r.newlines = 0;
        gameManager.addReport(r);

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            r = new Report(4160);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            gameManager.addReport(r);
            return;
        }

        // report the roll
        r = new Report(4025);
        r.subject = ae.getId();
        r.add(toHit);
        r.add(pr.roll);
        r.newlines = 0;
        gameManager.addReport(r);

        // check if our target has a push against us, too, and get it
        PhysicalResult targetPushResult = null;
        for (PhysicalResult tpr : gameManager.physicalResults) {
            if ((tpr.aaa.getEntityId() == te.getId()) && (tpr.aaa instanceof PushAttackAction)
                    && (tpr.aaa.getTargetId() == ae.getId())) {
                targetPushResult = tpr;
            }
        }

        // if our target has a push against us, and we are hitting, we need to resolve both now
        if ((targetPushResult != null) && !targetPushResult.pushBackResolved
                && (rollValue >= toHit.getValue())) {
            targetPushResult.pushBackResolved = true;
            // do they hit?
            if (targetPushResult.roll.getIntValue() >= targetPushResult.toHit.getValue()) {
                r = new Report(4165);
                r.subject = ae.getId();
                r.addDesc(te);
                r.addDesc(te);
                r.addDesc(ae);
                r.add(targetPushResult.toHit);
                r.add(targetPushResult.roll);
                r.addDesc(ae);
                gameManager.addReport(r);
                if (ae.canFall()) {
                    PilotingRollData pushPRD = gameManager.getKickPushPSR(ae, ae, te, "was pushed");
                    gameManager.game.addPSR(pushPRD);
                } else if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                    gameManager.game.addControlRoll(gameManager.getKickPushPSR(ae, ae, te, "was pushed"));
                }

                if (te.canFall()) {
                    PilotingRollData targetPushPRD = gameManager.getKickPushPSR(te, ae, te, "was pushed");
                    gameManager.game.addPSR(targetPushPRD);
                } else if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                    gameManager.game.addControlRoll(gameManager.getKickPushPSR(te, ae, te, "was pushed"));
                }
                return;
            }
            // report the miss
            r = new Report(4166);
            r.subject = ae.getId();
            r.addDesc(te);
            r.addDesc(ae);
            r.add(targetPushResult.toHit);
            r.add(targetPushResult.roll);
            gameManager.addReport(r);
        }

        // do we hit?
        if (rollValue < toHit.getValue()) {
            // miss
            r = new Report(4035);
            r.subject = ae.getId();
            gameManager.addReport(r);
            return;
        }

        // we hit...
        int direction = ae.getFacing();

        Coords src = te.getPosition();
        Coords dest = src.translated(direction);

        PilotingRollData pushPRD = gameManager.getKickPushPSR(te, ae, te, "was pushed");

        if (Compute.isValidDisplacement(gameManager.game, te.getId(), te.getPosition(), direction)) {
            r = new Report(4170);
            r.subject = ae.getId();
            r.newlines = 0;
            gameManager.addReport(r);
            if (gameManager.game.getBoard().contains(dest)) {
                r = new Report(4175);
                r.subject = ae.getId();
                r.add(dest.getBoardNum(), true);
            } else {
                // uh-oh, pushed off board
                r = new Report(4180);
                r.subject = ae.getId();
            }
            gameManager.addReport(r);

            gameManager.reportManager.addReport(gameManager.utilityManager.doEntityDisplacement(te, src, dest, pushPRD, gameManager), gameManager);

            // if push actually moved the target, attacker follows through
            if (!te.getPosition().equals(src)) {
                ae.setPosition(src);
            }
        } else {
            // target immovable
            r = new Report(4185);
            r.subject = ae.getId();
            gameManager.addReport(r);
            if (te.canFall()) {
                gameManager.game.addPSR(pushPRD);
            }
        }

        // if the target is an industrial mech, it needs to check for crits at the end of turn
        if ((te instanceof Mech) && ((Mech) te).isIndustrial()) {
            ((Mech) te).setCheckForCrit(true);
        }

        gameManager.combatManager.checkForSpikes(te, ae.rollHitLocation(ToHitData.HIT_PUNCH, Compute.targetSideTable(ae, te)).getLocation(),
                0, ae, Mech.LOC_LARM, Mech.LOC_RARM, gameManager);

        gameManager.addNewLines();
    }

    /**
     * Handle a trip attack
     * @param pr
     * @param lastEntityId
     * @param gameManager
     */
    protected void resolveTripAttack(PhysicalResult pr, int lastEntityId, GameManager gameManager) {
        final TripAttackAction paa = (TripAttackAction) pr.aaa;
        final Entity ae = gameManager.game.getEntity(paa.getEntityId());
        // PLEASE NOTE: buildings are *never* the target of a "trip".
        final Entity te = gameManager.game.getEntity(paa.getTargetId());
        // get roll and ToHitData from the PhysicalResult
        int rollValue = pr.roll.getIntValue();
        final ToHitData toHit = pr.toHit;
        Report r;

        if (lastEntityId != paa.getEntityId()) {
            // who is making the attack
            r = new Report(4005);
            r.subject = ae.getId();
            r.addDesc(ae);
            gameManager.addReport(r);
        }

        r = new Report(4280);
        r.subject = ae.getId();
        r.indent();
        r.addDesc(te);
        r.newlines = 0;
        gameManager.addReport(r);

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            r = new Report(4285);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            gameManager.addReport(r);
            return;
        }

        // report the roll
        r = new Report(4025);
        r.subject = ae.getId();
        r.add(toHit);
        r.add(pr.roll);
        r.newlines = 0;
        gameManager.addReport(r);

        // do we hit?
        if (rollValue < toHit.getValue()) {
            // miss
            r = new Report(4035);
            r.subject = ae.getId();
            gameManager.addReport(r);
            return;
        }

        // we hit...
        if (te.canFall()) {
            PilotingRollData pushPRD = gameManager.getKickPushPSR(te, ae, te, "was tripped");
            gameManager.game.addPSR(pushPRD);
        }

        r = new Report(4040);
        r.subject = ae.getId();
        gameManager.addReport(r);
        gameManager.addNewLines();
        // if the target is an industrial mech, it needs to check for crits at the end of turn
        if ((te instanceof Mech) && ((Mech) te).isIndustrial()) {
            ((Mech) te).setCheckForCrit(true);
        }
    }

    /**
     * Handle a grapple attack
     * @param pr
     * @param lastEntityId
     * @param gameManager
     */
    protected void resolveGrappleAttack(PhysicalResult pr, int lastEntityId, GameManager gameManager) {
        gameManager.combatManager.resolveGrappleAttack(pr, lastEntityId, Entity.GRAPPLE_BOTH, Entity.GRAPPLE_BOTH, gameManager);
    }

    /**
     * Resolves a grapple attack.
     *  @param pr            the result of a physical attack - this one specifically being a grapple
     * @param lastEntityId  the entity making the attack
     * @param aeGrappleSide
     *            The side that the attacker is grappling with. For normal
     *            grapples this will be both, for chain whip grapples this will
     *            be the arm with the chain whip in it.
     * @param teGrappleSide
     *            The that the target is grappling with. For normal grapples
     *            this will be both, for chain whip grapples this will be the
     * @param gameManager
     */
    protected void resolveGrappleAttack(PhysicalResult pr, int lastEntityId, int aeGrappleSide,
                                        int teGrappleSide, GameManager gameManager) {
        final GrappleAttackAction paa = (GrappleAttackAction) pr.aaa;
        final Entity ae = gameManager.game.getEntity(paa.getEntityId());
        // PLEASE NOTE: buildings are *never* the target of a "push".
        final Entity te = gameManager.game.getEntity(paa.getTargetId());
        // get roll and ToHitData from the PhysicalResult
        int rollValue = pr.roll.getIntValue();
        final ToHitData toHit = pr.toHit;
        Report r;

        // same method as push, for counterattacks
        if (pr.pushBackResolved) {
            return;
        }

        if ((te.getGrappled() != Entity.NONE) || (ae.getGrappled() != Entity.NONE)) {
            toHit.addModifier(TargetRoll.IMPOSSIBLE, "Already Grappled");
        }

        if (lastEntityId != paa.getEntityId()) {
            // who is making the attack
            r = new Report(4005);
            r.subject = ae.getId();
            r.addDesc(ae);
            gameManager.addReport(r);
        }

        r = new Report(4295);
        r.subject = ae.getId();
        r.indent();
        r.addDesc(te);
        r.newlines = 0;
        gameManager.addReport(r);

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            r = new Report(4300);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            gameManager.addReport(r);
            return;
        }

        // report the roll
        r = new Report(4025);
        r.subject = ae.getId();
        r.add(toHit);
        r.add(pr.roll);
        r.newlines = 0;
        gameManager.addReport(r);

        // do we hit?
        if (rollValue < toHit.getValue()) {
            // miss
            r = new Report(4035);
            r.subject = ae.getId();
            gameManager.addReport(r);
            return;
        }

        // we hit...
        ae.setGrappled(te.getId(), true);
        te.setGrappled(ae.getId(), false);
        ae.setGrappledThisRound(true);
        te.setGrappledThisRound(true);
        // For normal grapples, AE moves into targets hex.
        if (aeGrappleSide == Entity.GRAPPLE_BOTH) {
            Coords pos = te.getPosition();
            ae.setPosition(pos);
            ae.setElevation(te.getElevation());
            te.setFacing((ae.getFacing() + 3) % 6);
            gameManager.reportManager.addReport(gameManager.utilityManager.doSetLocationsExposure(ae, gameManager.game.getBoard().getHex(pos), false, ae.getElevation(), gameManager), gameManager);
        }

        ae.setGrappleSide(aeGrappleSide);
        te.setGrappleSide(teGrappleSide);

        r = new Report(4040);
        r.subject = ae.getId();
        gameManager.addReport(r);
        gameManager.addNewLines();

        // if the target is an industrial mech, it needs to check for crits at the end of turn
        if ((te instanceof Mech) && ((Mech) te).isIndustrial()) {
            ((Mech) te).setCheckForCrit(true);
        }
    }

    /**
     * Handle a break grapple attack
     * @param pr
     * @param lastEntityId
     * @param gameManager
     */
    protected void resolveBreakGrappleAttack(PhysicalResult pr, int lastEntityId, GameManager gameManager) {
        final BreakGrappleAttackAction paa = (BreakGrappleAttackAction) pr.aaa;
        final Entity ae = gameManager.game.getEntity(paa.getEntityId());
        // PLEASE NOTE: buildings are *never* the target of a "push".
        final Entity te = gameManager.game.getEntity(paa.getTargetId());
        // get roll and ToHitData from the PhysicalResult
        int rollValue = pr.roll.getIntValue();
        final ToHitData toHit = pr.toHit;
        Report r;

        if (lastEntityId != paa.getEntityId()) {
            // who is making the attack
            r = new Report(4005);
            r.subject = ae.getId();
            r.addDesc(ae);
            gameManager.addReport(r);
        }

        r = new Report(4305);
        r.subject = ae.getId();
        r.indent();
        r.addDesc(te);
        r.newlines = 0;
        gameManager.addReport(r);

        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            r = new Report(4310);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            gameManager.addReport(r);
            if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                gameManager.game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed a physical attack"));
            }
            return;
        }

        if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            r = new Report(4320);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
        } else {
            // report the roll
            r = new Report(4025);
            r.subject = ae.getId();
            r.add(toHit);
            r.add(pr.roll);
            r.newlines = 0;
            gameManager.addReport(r);

            // do we hit?
            if (rollValue < toHit.getValue()) {
                // miss
                r = new Report(4035);
                r.subject = ae.getId();
                gameManager.addReport(r);
                if (ae instanceof LandAirMech && ae.isAirborneVTOLorWIGE()) {
                    gameManager.game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed a physical attack"));
                }
                return;
            }

            // hit
            r = new Report(4040);
            r.subject = ae.getId();
        }
        gameManager.addReport(r);

        // is there a counterattack?
        PhysicalResult targetGrappleResult = null;
        for (PhysicalResult tpr : gameManager.physicalResults) {
            if ((tpr.aaa.getEntityId() == te.getId())
                    && (tpr.aaa instanceof GrappleAttackAction)
                    && (tpr.aaa.getTargetId() == ae.getId())) {
                targetGrappleResult = tpr;
                break;
            }
        }

        if (targetGrappleResult != null) {
            targetGrappleResult.pushBackResolved = true;
            // counterattack
            r = new Report(4315);
            r.subject = te.getId();
            r.newlines = 0;
            r.addDesc(te);
            gameManager.addReport(r);

            // report the roll
            r = new Report(4025);
            r.subject = te.getId();
            r.add(targetGrappleResult.toHit);
            r.add(targetGrappleResult.roll);
            r.newlines = 0;
            gameManager.addReport(r);

            // do we hit?
            if (rollValue < toHit.getValue()) {
                // miss
                r = new Report(4035);
                r.subject = ae.getId();
                gameManager.addReport(r);
            } else {
                // hit
                r = new Report(4040);
                r.subject = ae.getId();
                gameManager.addReport(r);

                // exchange attacker and defender
                ae.setGrappled(te.getId(), false);
                te.setGrappled(ae.getId(), true);

                return;
            }
        }

        // score the adjacent hexes
        Coords[] hexes = new Coords[6];
        int[] scores = new int[6];

        Hex curHex = gameManager.game.getBoard().getHex(ae.getPosition());
        for (int i = 0; i < 6; i++) {
            hexes[i] = ae.getPosition().translated(i);
            scores[i] = 0;
            Hex hex = gameManager.game.getBoard().getHex(hexes[i]);
            if (hex.containsTerrain(Terrains.MAGMA)) {
                scores[i] += 10;
            }

            if (hex.containsTerrain(Terrains.WATER)) {
                scores[i] += hex.terrainLevel(Terrains.WATER);
            }

            if ((curHex.getLevel() - hex.getLevel()) >= 2) {
                scores[i] += 2 * (curHex.getLevel() - hex.getLevel());
            }
        }

        int bestScore = 99999;
        int best = 0;
        int worstScore = -99999;
        int worst = 0;

        for (int i = 0; i < 6; i++) {
            if (bestScore > scores[i]) {
                best = i;
                bestScore = scores[i];
            }
            if (worstScore < scores[i]) {
                worst = i;
                worstScore = scores[i];
            }
        }

        // attacker doesn't fall, unless off a cliff
        if (ae.isGrappleAttacker()) {
            // move self to least dangerous hex
            PilotingRollData psr = ae.getBasePilotingRoll();
            psr.addModifier(TargetRoll.AUTOMATIC_SUCCESS, "break grapple");
            gameManager.reportManager.addReport(gameManager.utilityManager.doEntityDisplacement(ae, ae.getPosition(), hexes[best], psr, gameManager), gameManager);
            ae.setFacing(hexes[best].direction(te.getPosition()));
        } else {
            // move enemy to most dangerous hex
            PilotingRollData psr = te.getBasePilotingRoll();
            psr.addModifier(TargetRoll.AUTOMATIC_SUCCESS, "break grapple");
            gameManager.reportManager.addReport(gameManager.utilityManager.doEntityDisplacement(te, te.getPosition(), hexes[worst], psr, gameManager), gameManager);
            te.setFacing(hexes[worst].direction(ae.getPosition()));
        }

        // grapple is broken
        ae.setGrappled(Entity.NONE, false);
        te.setGrappled(Entity.NONE, false);

        gameManager.addNewLines();
    }

    /**
     * Handle a charge attack
     * @param pr
     * @param lastEntityId
     * @param gameManager
     */
    protected void resolveChargeAttack(PhysicalResult pr, int lastEntityId, GameManager gameManager) {
        final ChargeAttackAction caa = (ChargeAttackAction) pr.aaa;
        final Entity ae = gameManager.game.getEntity(caa.getEntityId());
        final Targetable target = gameManager.game.getTarget(caa.getTargetType(), caa.getTargetId());
        // get damage, ToHitData and roll from the PhysicalResult
        int damage = pr.damage;
        final ToHitData toHit = pr.toHit;
        int rollValue = pr.roll.getIntValue();

        Entity te = null;
        if ((target != null) && (target.getTargetType() == Targetable.TYPE_ENTITY)) {
            te = (Entity) target;
        }
        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute.isThroughFrontHex(gameManager.game, ae.getPosition(), te);
        }
        final boolean glancing = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS)
                && (rollValue == toHit.getValue());

        // Set Margin of Success/Failure.
        toHit.setMoS(rollValue - Math.max(2, toHit.getValue()));
        final boolean directBlow = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW)
                && ((toHit.getMoS() / 3) >= 1);

        Report r;

        // Which building takes the damage?
        Building bldg = gameManager.game.getBoard().getBuildingAt(caa.getTargetPos());

        // is the attacker dead? because that sure messes up the calculations
        if (ae == null) {
            return;
        }

        final int direction = ae.getFacing();

        // entity isn't charging any more
        ae.setDisplacementAttack(null);

        if (lastEntityId != caa.getEntityId()) {
            // who is making the attack
            r = new Report(4005);
            r.subject = ae.getId();
            r.addDesc(ae);
            gameManager.addReport(r);
        }

        // should we even bother?
        if ((target == null) || ((target.getTargetType() == Targetable.TYPE_ENTITY)
                && (te.isDestroyed() || te.isDoomed() || te.getCrew().isDead()))) {
            r = new Report(4190);
            r.subject = ae.getId();
            r.indent();
            gameManager.addReport(r);
            // doEntityDisplacement(ae, ae.getPosition(), caa.getTargetPos(),
            // null);
            // Randall said that if a charge fails because of target
            // destruction,
            // the attacker stays in the hex he was in at the end of the
            // movement phase
            // See Bug 912094
            return;
        }

        // attacker fell down?
        if (ae.isProne()) {
            r = new Report(4195);
            r.subject = ae.getId();
            r.indent();
            gameManager.addReport(r);
            return;
        }

        // attacker immobile?
        if (ae.isImmobile()) {
            r = new Report(4200);
            r.subject = ae.getId();
            r.indent();
            gameManager.addReport(r);
            return;
        }

        // target fell down, only for attacking Mechs, though
        if ((te != null) && (te.isProne()) && (ae instanceof Mech)) {
            r = new Report(4205);
            r.subject = ae.getId();
            r.indent();
            gameManager.addReport(r);
            return;
        }

        r = new Report(4210);
        r.subject = ae.getId();
        r.indent();
        r.add(target.getDisplayName());
        r.newlines = 0;
        gameManager.addReport(r);

        // target still in the same position?
        if (!target.getPosition().equals(caa.getTargetPos())) {
            r = new Report(4215);
            r.subject = ae.getId();
            gameManager.addReport(r);
            gameManager.reportManager.addReport(gameManager.utilityManager.doEntityDisplacement(ae, ae.getPosition(), caa.getTargetPos(), null, gameManager), gameManager);
            return;
        }

        // if the attacker's prone, fudge the roll
        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            rollValue = -12;
            r = new Report(4220);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            gameManager.addReport(r);
        } else if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            rollValue = Integer.MAX_VALUE;
            r = new Report(4225);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            gameManager.addReport(r);
        } else {
            // report the roll
            r = new Report(4025);
            r.subject = ae.getId();
            r.add(toHit);
            r.add(pr.roll);
            gameManager.addReport(r);
            if (glancing) {
                r = new Report(3186);
                r.subject = ae.getId();
                gameManager.addReport(r);
            }

            if (directBlow) {
                r = new Report(3189);
                r.subject = ae.getId();
                gameManager.addReport(r);
            }
        }

        // do we hit?
        if (rollValue < toHit.getValue()) {
            Coords src = ae.getPosition();
            Coords dest = Compute.getMissedChargeDisplacement(gameManager.game, ae.getId(), src, direction);

            // TODO : handle movement into/out of/through a building. Do it here?

            // miss
            r = new Report(4035);
            r.subject = ae.getId();
            gameManager.addReport(r);
            // move attacker to side hex
            gameManager.reportManager.addReport(gameManager.utilityManager.doEntityDisplacement(ae, src, dest, null, gameManager), gameManager);
        } else if ((target.getTargetType() == Targetable.TYPE_BUILDING)
                || (target.getTargetType() == Targetable.TYPE_FUEL_TANK)) { // Targeting
            // a building.
            // The building takes the full brunt of the attack.
            r = new Report(4040);
            r.subject = ae.getId();
            gameManager.addReport(r);
            Vector<Report> buildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, damage, target.getPosition(), gameManager);
            for (Report report : buildingReport) {
                report.subject = ae.getId();
            }
            gameManager.reportManager.addReport(buildingReport, gameManager);

            // Damage any infantry in the hex.
            gameManager.reportManager.addReport(gameManager.environmentalEffectManager.damageInfantryIn(bldg, damage, target.getPosition(), gameManager), gameManager);

            // Apply damage to the attacker.
            int toAttacker = ChargeAttackAction.getDamageTakenBy(ae, bldg, target.getPosition());
            HitData hit = ae.rollHitLocation(ToHitData.HIT_NORMAL, ae.sideTable(target.getPosition()));
            hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
            gameManager.reportManager.addReport(gameManager.damageEntity(ae, hit, toAttacker, false, DamageType.NONE,
                    false, false, throughFront), gameManager);
            gameManager.addNewLines();
            gameManager.entityActionManager.entityUpdate(ae.getId(), gameManager);

            // TODO : Does the attacker enter the building?
            // TODO : What if the building collapses?
        } else {
            // Resolve the damage.
            gameManager.combatManager.resolveChargeDamage(ae, te, toHit, direction, glancing, throughFront, false, gameManager);
        }
    }

    /**
     * Handle an Airmech ram attack
     * @param pr
     * @param lastEntityId
     * @param gameManager
     */
    protected void resolveAirmechRamAttack(PhysicalResult pr, int lastEntityId, GameManager gameManager) {
        final AirmechRamAttackAction caa = (AirmechRamAttackAction) pr.aaa;
        final Entity ae = gameManager.game.getEntity(caa.getEntityId());
        final Targetable target = gameManager.game.getTarget(caa.getTargetType(), caa.getTargetId());
        // get damage, ToHitData and roll from the PhysicalResult
        int damage = pr.damage;
        final ToHitData toHit = pr.toHit;
        int rollValue = pr.roll.getIntValue();

        Entity te = null;
        if ((target != null) && (target.getTargetType() == Targetable.TYPE_ENTITY)) {
            te = (Entity) target;
        }
        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute.isThroughFrontHex(gameManager.game, ae.getPosition(), te);
        }
        final boolean glancing = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS)
                && (rollValue == toHit.getValue());

        // Set Margin of Success/Failure.
        toHit.setMoS(rollValue - Math.max(2, toHit.getValue()));
        final boolean directBlow = gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW)
                && ((toHit.getMoS() / 3) >= 1);

        Report r;

        // Which building takes the damage?
        Building bldg = gameManager.game.getBoard().getBuildingAt(caa.getTargetPos());

        // is the attacker dead? because that sure messes up the calculations
        if (ae == null) {
            return;
        }

        final int direction = ae.getFacing();

        // entity isn't charging any more
        ae.setDisplacementAttack(null);

        if (lastEntityId != caa.getEntityId()) {
            // who is making the attack
            r = new Report(4005);
            r.subject = ae.getId();
            r.addDesc(ae);
            gameManager.addReport(r);
        }

        // should we even bother?
        if ((target == null) || ((target.getTargetType() == Targetable.TYPE_ENTITY)
                && (te.isDestroyed() || te.isDoomed() || te.getCrew().isDead()))) {
            r = new Report(4192);
            r.subject = ae.getId();
            r.indent();
            gameManager.addReport(r);
            gameManager.game.addControlRoll(new PilotingRollData(
                    ae.getId(), 0, "missed a ramming attack"));
            return;
        }

        // attacker landed?
        if (!ae.isAirborneVTOLorWIGE()) {
            r = new Report(4197);
            r.subject = ae.getId();
            r.indent();
            gameManager.addReport(r);
            return;
        }

        // attacker immobile?
        if (ae.isImmobile()) {
            r = new Report(4202);
            r.subject = ae.getId();
            r.indent();
            gameManager.addReport(r);
            return;
        }

        r = new Report(4212);
        r.subject = ae.getId();
        r.indent();
        r.add(target.getDisplayName());
        r.newlines = 0;
        gameManager.addReport(r);

        // if the attacker's prone, fudge the roll
        if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            rollValue = -12;
            r = new Report(4222);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            gameManager.addReport(r);
        } else if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            rollValue = Integer.MAX_VALUE;
            r = new Report(4227);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            gameManager.addReport(r);
        } else {
            // report the roll
            r = new Report(4025);
            r.subject = ae.getId();
            r.add(toHit);
            r.add(pr.roll);
            gameManager.addReport(r);
            if (glancing) {
                r = new Report(3186);
                r.subject = ae.getId();
                gameManager.addReport(r);
            }

            if (directBlow) {
                r = new Report(3189);
                r.subject = ae.getId();
                gameManager.addReport(r);
            }
        }

        // do we hit?
        if (rollValue < toHit.getValue()) {
            // miss
            r = new Report(4035);
            r.subject = ae.getId();
            gameManager.addReport(r);
            // attacker must make a control roll
            gameManager.game.addControlRoll(new PilotingRollData(ae.getId(), 0, "missed ramming attack"));
        } else if ((target.getTargetType() == Targetable.TYPE_BUILDING)
                || (target.getTargetType() == Targetable.TYPE_FUEL_TANK)) { // Targeting a building.
            // The building takes the full brunt of the attack.
            r = new Report(4040);
            r.subject = ae.getId();
            gameManager.addReport(r);
            Vector<Report> buildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, damage, target.getPosition(), gameManager);
            for (Report report : buildingReport) {
                report.subject = ae.getId();
            }
            gameManager.reportManager.addReport(buildingReport, gameManager);

            // Damage any infantry in the hex.
            gameManager.reportManager.addReport(gameManager.environmentalEffectManager.damageInfantryIn(bldg, damage, target.getPosition(), gameManager), gameManager);

            // Apply damage to the attacker.
            int toAttacker = AirmechRamAttackAction.getDamageTakenBy(ae, target, ae.delta_distance);
            HitData hit = new HitData(Mech.LOC_CT);
            hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
            gameManager.reportManager.addReport(gameManager.damageEntity(ae, hit, toAttacker, false, DamageType.NONE,
                    false, false, throughFront), gameManager);
            gameManager.addNewLines();
            gameManager.entityActionManager.entityUpdate(ae.getId(), gameManager);

            // TODO : Does the attacker enter the building?
            // TODO : What if the building collapses?
        } else {
            // Resolve the damage.
            gameManager.combatManager.resolveChargeDamage(ae, te, toHit, direction, glancing, throughFront, true, gameManager);
        }
    }

    /**
     * Handle a telemissile attack
     * @param pr
     * @param lastEntityId
     * @param gameManager
     */
    protected void resolveTeleMissileAttack(PhysicalResult pr, int lastEntityId, GameManager gameManager) {
        final TeleMissileAttackAction taa = (TeleMissileAttackAction) pr.aaa;
        final Entity ae = gameManager.game.getEntity(taa.getEntityId());
        if (!(ae instanceof TeleMissile)) {
            return;
        }
        TeleMissile tm = (TeleMissile) ae;
        final Targetable target = gameManager.game.getTarget(taa.getTargetType(), taa.getTargetId());
        final ToHitData toHit = pr.toHit;
        int rollValue = pr.roll.getIntValue();
        int amsDamage = taa.CounterAVInt;
        Entity te = null;
        if ((target != null) && (target.getTargetType() == Targetable.TYPE_ENTITY)) {
            te = (Entity) target;
        }

        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute.isThroughFrontHex(gameManager.game, ae.getPosition(), te);
        }

        Report r;

        if (lastEntityId != taa.getEntityId()) {
            // who is making the attack
            r = new Report(4005);
            r.subject = ae.getId();
            r.addDesc(ae);
            gameManager.addReport(r);
        }

        // should we even bother?
        if ((target == null)
                || ((target.getTargetType() == Targetable.TYPE_ENTITY) && (te.isDestroyed()
                || te.isDoomed() || te.getCrew().isDead()))) {
            r = new Report(4191);
            r.subject = ae.getId();
            r.indent();
            gameManager.addReport(r);
            return;
        }

        r = new Report(9031);
        r.subject = ae.getId();
        r.indent();
        r.add(target.getDisplayName());
        r.newlines = 1;
        gameManager.addReport(r);

        // If point defenses engaged the missile, handle that damage
        if (amsDamage > 0) {
            //Report the attack
            r = new Report(3362);
            r.newlines = 1;
            r.subject = te.getId();
            gameManager.vPhaseReport.add(r);

            // If the target's point defenses overheated, report that
            if (taa.getPDOverheated()) {
                r = new Report(3361);
                r.newlines = 1;
                r.subject = te.getId();
                gameManager.vPhaseReport.add(r);
            }

            // Damage the missile
            HitData hit = tm.rollHitLocation(ToHitData.HIT_NORMAL,
                    tm.sideTable(te.getPosition(), true));
            gameManager.reportManager.addReport(gameManager.damageEntity(ae, hit, amsDamage, false,
                    DamageType.NONE, false, false, false), gameManager);

            // If point defense fire destroys the missile, don't process a hit
            if (ae.isDoomed()) {
                return;
            }
        }

        // add some stuff to the to hit value
        // need to add damage done modifier
        int damageTaken = (ae.getOArmor(TeleMissile.LOC_BODY) - ae.getArmor(TeleMissile.LOC_BODY));
        if (damageTaken > 10) {
            toHit.addModifier((int) (Math.floor(damageTaken / 10.0)), "damage taken");
        }

        // add modifiers for the originating unit missing CIC, FCS, or sensors
        Entity ride = gameManager.game.getEntity(tm.getOriginalRideId());
        if (ride instanceof Aero) {
            Aero aride = (Aero) ride;
            int cic = aride.getCICHits();
            if (cic > 0) {
                toHit.addModifier(cic * 2, "CIC damage");
            }

            // sensor hits
            int sensors = aride.getSensorHits();
            if ((sensors > 0) && (sensors < 3)) {
                toHit.addModifier(sensors, "sensor damage");
            }

            if (sensors > 2) {
                toHit.addModifier(+5, "sensors destroyed");
            }

            // FCS hits
            int fcs = aride.getFCSHits();
            if (fcs > 0) {
                toHit.addModifier(fcs * 2, "fcs damage");
            }
        }

        if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            rollValue = Integer.MAX_VALUE;
            r = new Report(4226);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
        } else {
            // report the roll
            r = new Report(9033);
            r.subject = ae.getId();
            r.add(toHit);
            r.add(toHit.getDesc());
            r.add(pr.roll);
            r.newlines = 0;
        }
        gameManager.addReport(r);

        // do we hit?
        if (rollValue < toHit.getValue()) {
            // miss
            r = new Report(4035);
            r.subject = ae.getId();
            gameManager.addReport(r);
        } else {
            // Resolve the damage.
            HitData hit = te.rollHitLocation(ToHitData.HIT_NORMAL,
                    te.sideTable(ae.getPosition(), true));
            hit.setCapital(true);
            hit.setCapMisCritMod(tm.getCritMod());
            gameManager.reportManager.addReport(gameManager.damageEntity(te, hit,
                    TeleMissileAttackAction.getDamageFor(ae), false,
                    DamageType.NONE, false, false, throughFront), gameManager);
            gameManager.entityActionManager.destroyEntity(ae, "successful attack", gameManager);
        }

    }

    /**
     * Handle a ramming attack
     * @param pr
     * @param lastEntityId
     * @param gameManager
     */
    protected void resolveRamAttack(PhysicalResult pr, int lastEntityId, GameManager gameManager) {
        final RamAttackAction raa = (RamAttackAction) pr.aaa;
        final Entity ae = gameManager.game.getEntity(raa.getEntityId());
        final Targetable target = gameManager.game.getTarget(raa.getTargetType(), raa.getTargetId());
        final ToHitData toHit = pr.toHit;
        int rollValue = pr.roll.getIntValue();
        Entity te = null;
        if ((target != null) && (target.getTargetType() == Targetable.TYPE_ENTITY)) {
            te = (Entity) target;
        }

        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute.isThroughFrontHex(gameManager.game, ae.getPosition(), te);
        }

        Report r;

        boolean glancing = Compute.d6(1) == 6;

        // entity isn't ramming any more
        ae.setRamming(false);

        if (lastEntityId != raa.getEntityId()) {
            // who is making the attack
            r = new Report(4005);
            r.subject = ae.getId();
            r.addDesc(ae);
            gameManager.addReport(r);
        }

        // should we even bother?
        if ((target == null)
                || ((target.getTargetType() == Targetable.TYPE_ENTITY) && (te.isDestroyed()
                || te.isDoomed() || te.getCrew().isDead()))) {
            r = new Report(4190);
            r.subject = ae.getId();
            r.indent();
            gameManager.addReport(r);
            return;
        }

        // steel yourself for attack
        Roll diceRoll2 = Compute.rollD6(2);
        r = new Report(9020);
        r.subject = ae.getId();
        r.add(diceRoll2);

        if (diceRoll2.getIntValue() >= 11) {
            r.choose(true);
            gameManager.addReport(r);
        } else {
            r.choose(false);
            gameManager.addReport(r);
            return;
        }

        // attacker immobile?
        if (ae.isImmobile()) {
            r = new Report(4200);
            r.subject = ae.getId();
            r.indent();
            gameManager.addReport(r);
            return;
        }

        r = new Report(9030);
        r.subject = ae.getId();
        r.indent();
        r.add(target.getDisplayName());
        r.newlines = 0;
        gameManager.addReport(r);

        if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            rollValue = Integer.MAX_VALUE;
            r = new Report(4225);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
        } else {
            // report the roll
            r = new Report(4025);
            r.subject = ae.getId();
            r.add(toHit);
            r.add(pr.roll);
            r.newlines = 0;
        }
        gameManager.addReport(r);

        // do we hit?
        if (rollValue < toHit.getValue()) {
            // miss
            r = new Report(4035);
            r.subject = ae.getId();
            gameManager.addReport(r);
        } else {
            // Resolve the damage.
            gameManager.combatManager.resolveRamDamage((IAero) ae, te, toHit, glancing, throughFront, gameManager);
        }
    }

    /**
     * Handle a ramming attack's damage
     * @param aero
     * @param te
     * @param toHit
     * @param glancing
     * @param throughFront
     * @param gameManager
     */
    protected void resolveRamDamage(IAero aero, Entity te, ToHitData toHit, boolean glancing,
                                    boolean throughFront, GameManager gameManager) {
        Entity ae = (Entity) aero;

        int damage = RamAttackAction.getDamageFor(aero, te);
        int damageTaken = RamAttackAction.getDamageTakenBy(aero, te);
        if (glancing) {
            // Round up glancing blows against conventional infantry
            damage = (int) (te.isConventionalInfantry() ? Math.ceil(damage / 2.0) : Math.floor(damage / 2.0));
            damageTaken = (int) Math.floor(damageTaken / 2.0);
        }

        // are they capital scale?
        if (te.isCapitalScale()
                && !gameManager.game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_AERO_SANITY)) {
            damage = (int) Math.floor(damage / 10.0);
        }

        if (ae.isCapitalScale()
                && !gameManager.game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_AERO_SANITY)) {
            damageTaken = (int) Math.floor(damageTaken / 10.0);
        }

        Report r;

        if (glancing) {
            r = new Report(9015);
            r.subject = ae.getId();
            r.indent(1);
            gameManager.addReport(r);
        }

        // damage to attacker
        r = new Report(4240);
        r.subject = ae.getId();
        r.add(damageTaken);
        r.indent();
        gameManager.addReport(r);

        HitData hit = ae.rollHitLocation(ToHitData.HIT_NORMAL, ae.sideTable(te.getPosition(), true));
        // if the damage is greater than the initial armor then destroy the
        // entity
        if ((2 * ae.getOArmor(hit)) < damageTaken) {
            gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity(ae, "by massive ramming damage", false, gameManager), gameManager);
        } else {
            gameManager.reportManager.addReport(gameManager.damageEntity(ae, hit, damageTaken, false,
                    DamageType.NONE, false, false, throughFront), gameManager);
        }

        r = new Report(4230);
        r.subject = ae.getId();
        r.add(damage);
        r.add(toHit.getTableDesc());
        r.indent();
        gameManager.addReport(r);

        hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
        if ((2 * te.getOArmor(hit)) < damage) {
            gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity(te, "by massive ramming damage", false, gameManager), gameManager);
        } else {
            gameManager.reportManager.addReport(gameManager.damageEntity(te, hit, damage, false, DamageType.NONE,
                    false, false, throughFront), gameManager);
        }
    }

    protected void resolveChargeDamage(Entity ae, Entity te, ToHitData toHit, int direction,
                                       boolean glancing, boolean throughFront, boolean airmechRam, GameManager gameManager) {
        // we hit...

        PilotingRollData chargePSR = null;
        // If we're upright, we may fall down.
        if (!ae.isProne() && !airmechRam) {
            chargePSR = new PilotingRollData(ae.getId(), 2, "charging");
        }

        // Damage To Target
        int damage;

        // Damage to Attacker
        int damageTaken;

        if (airmechRam) {
            damage = AirmechRamAttackAction.getDamageFor(ae);
            damageTaken = AirmechRamAttackAction.getDamageTakenBy(ae, te);
        } else {
            damage = ChargeAttackAction.getDamageFor(ae, te, gameManager.game.getOptions()
                    .booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_CHARGE_DAMAGE), toHit.getMoS());
            damageTaken = ChargeAttackAction.getDamageTakenBy(ae, te, gameManager.game
                    .getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_CHARGE_DAMAGE));
        }
        if (ae.hasWorkingMisc(MiscType.F_RAM_PLATE)) {
            damage = (int) Math.ceil(damage * 1.5);
            damageTaken = (int) Math.floor(damageTaken * 0.5);
        }
        if (glancing) {
            // Glancing Blow rule doesn't state whether damage to attacker on charge
            // or DFA is halved as well, assume yes. TODO : Check with PM
            damage = (int) (te.isConventionalInfantry() ? Math.ceil(damage / 2.0) : Math.floor(damage / 2.0));
            damageTaken = (int) Math.floor(damageTaken / 2.0);
        }
        boolean bDirect = false;
        int directBlowCritMod = toHit.getMoS() / 3;
        if (gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW)
                && ((toHit.getMoS() / 3) >= 1)) {
            damage += toHit.getMoS() / 3;
            bDirect = false;
        }

        // Is the target inside a building?
        final boolean targetInBuilding = Compute.isInBuilding(gameManager.game, te);

        // Which building takes the damage?
        Building bldg = gameManager.game.getBoard().getBuildingAt(te.getPosition());

        // The building shields all units from a certain amount of damage.
        // The amount is based upon the building's CF at the phase's start.
        int bldgAbsorbs = 0;
        if (targetInBuilding && (bldg != null)) {
            bldgAbsorbs = bldg.getAbsorbtion(te.getPosition());
        }

        Report r;

        // damage to attacker
        r = new Report(4240);
        r.subject = ae.getId();
        r.add(damageTaken);
        r.indent();
        gameManager.addReport(r);

        // Charging vehicles check for possible motive system hits.
        if (ae instanceof Tank) {
            r = new Report(4241);
            r.indent();
            gameManager.addReport(r);
            int side = Compute.targetSideTable(te, ae);
            int mod = ae.getMotiveSideMod(side);
            gameManager.reportManager.addReport(gameManager.vehicleMotiveDamage((Tank) ae, mod), gameManager);
        }

        while (damageTaken > 0) {
            int cluster;
            HitData hit;
            // An airmech ramming attack does all damage to attacker's CT
            if (airmechRam) {
                cluster = damageTaken;
                hit = new HitData(Mech.LOC_CT);
            } else {
                cluster = Math.min(5, damageTaken);
                hit = ae.rollHitLocation(toHit.getHitTable(), ae.sideTable(te.getPosition()));
            }
            damageTaken -= cluster;
            hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
            cluster = gameManager.combatManager.checkForSpikes(ae, hit.getLocation(), cluster, te, Mech.LOC_CT, gameManager);
            gameManager.reportManager.addReport(gameManager.damageEntity(ae, hit, cluster, false, DamageType.NONE,
                    false, false, throughFront), gameManager);
        }

        // Damage to target
        if (ae instanceof Mech) {
            int spikeDamage = 0;
            for (int loc = 0; loc < ae.locations(); loc++) {
                if (((Mech) ae).locationIsTorso(loc) && ae.hasWorkingMisc(MiscType.F_SPIKES, -1, loc)) {
                    spikeDamage += 2;
                }
            }

            if (spikeDamage > 0) {
                r = new Report(4335);
                r.indent(2);
                r.subject = ae.getId();
                r.add(spikeDamage);
                gameManager.addReport(r);
            }
            damage += spikeDamage;
        }
        r = new Report(4230);
        r.subject = ae.getId();
        r.add(damage);
        r.add(toHit.getTableDesc());
        r.indent();
        gameManager.addReport(r);

        // Vehicles that have *been* charged check for motive system damage,
        // too...
        // ...though VTOLs don't use that table and should lose their rotor
        // instead,
        // which would be handled as part of the damage already.
        if ((te instanceof Tank) && !(te instanceof VTOL)) {
            r = new Report(4242);
            r.indent();
            gameManager.addReport(r);

            int side = Compute.targetSideTable(ae, te);
            int mod = te.getMotiveSideMod(side);
            gameManager.reportManager.addReport(gameManager.vehicleMotiveDamage((Tank) te, mod), gameManager);
        }

        // track any additional damage to the attacker due to the target having spikes
        while (damage > 0) {
            int cluster = Math.min(5, damage);
            // Airmech ramming attacks do all damage to a single location
            if (airmechRam) {
                cluster = damage;
            }
            damage -= cluster;
            if (bldgAbsorbs > 0) {
                int toBldg = Math.min(bldgAbsorbs, cluster);
                cluster -= toBldg;
                gameManager.addNewLines();
                Vector<Report> buildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, damage, te.getPosition(), gameManager);
                for (Report report : buildingReport) {
                    report.subject = ae.getId();
                }
                gameManager.reportManager.addReport(buildingReport, gameManager);

                // some buildings scale remaining damage that is not absorbed
                // TODO : this isn't quite right for castles brian
                damage = (int) Math.floor(bldg.getDamageToScale() * damage);
            }

            // A building may absorb the entire shot.
            if (cluster == 0) {
                r = new Report(4235);
                r.subject = ae.getId();
                r.addDesc(te);
                r.indent();
                gameManager.addReport(r);
            } else {
                HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
                hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                if (bDirect) {
                    hit.makeDirectBlow(directBlowCritMod);
                }
                cluster = gameManager.combatManager.checkForSpikes(te, hit.getLocation(), cluster, ae, Mech.LOC_CT, gameManager);
                gameManager.reportManager.addReport(gameManager.damageEntity(te, hit, cluster, false,
                        DamageType.NONE, false, false, throughFront), gameManager);
            }
        }

        if (airmechRam) {
            if (!ae.isDoomed()) {
                PilotingRollData controlRoll = ae.getBasePilotingRoll();
                Vector<Report> reports = new Vector<>();
                r = new Report(9320);
                r.subject = ae.getId();
                r.addDesc(ae);
                r.add("successful ramming attack");
                reports.add(r);
                Roll diceRoll = Compute.rollD6(2);
                // different reports depending on out-of-control status
                r = new Report(9606);
                r.subject = ae.getId();
                r.add(controlRoll.getValueAsString());
                r.add(controlRoll.getDesc());
                r.add(diceRoll);
                r.newlines = 1;

                if (diceRoll.getIntValue() < controlRoll.getValue()) {
                    r.choose(false);
                    reports.add(r);
                    gameManager.entityActionManager.crashAirMech(ae, controlRoll, reports, gameManager);
                } else {
                    r.choose(true);
                    reports.addElement(r);
                    if (ae instanceof LandAirMech) {
                        reports.addAll(gameManager.entityActionManager.landAirMech((LandAirMech) ae, ae.getPosition(), 1, ae.delta_distance, gameManager));
                    }
                }
                gameManager.reportManager.addReport(reports, gameManager);
            }
        } else {
            // move attacker and target, if possible
            Coords src = te.getPosition();
            Coords dest = src.translated(direction);

            if (Compute.isValidDisplacement(gameManager.game, te.getId(), te.getPosition(), direction)) {
                gameManager.addNewLines();
                gameManager.reportManager.addReport(gameManager.utilityManager.doEntityDisplacement(te, src, dest, new PilotingRollData(
                        te.getId(), 2, "was charged"), gameManager), gameManager);
                gameManager.reportManager.addReport(gameManager.utilityManager.doEntityDisplacement(ae, ae.getPosition(), src, chargePSR, gameManager), gameManager);
            }

            gameManager.addNewLines();
        }

        // if the target is an industrial mech, it needs to check for crits at the end of turn
        if ((te instanceof Mech) && ((Mech) te).isIndustrial()) {
            ((Mech) te).setCheckForCrit(true);
        }
    }

    /**
     * Handle a charge's damage
     * @param ae
     * @param te
     * @param toHit
     * @param direction
     * @param gameManager
     */
    protected void resolveChargeDamage(Entity ae, Entity te, ToHitData toHit, int direction, GameManager gameManager) {
        resolveChargeDamage(ae, te, toHit, direction, false, true, false, gameManager);
    }

    /**
     * Checks whether the location has spikes, and if so handles the damage to the
     * attack and returns the reduced damage. Locations without spikes return the
     * original damage amount.
     *
     * @param target            The target of a physical attack
     * @param targetLocation    The location that was hit
     * @param damage            The amount of damage dealt to the target
     * @param attacker          The attacker
     * @param attackerLocation  The location on the attacker that is damaged if the
     *                          target has spikes. Entity.LOC_NONE if the attacker
     *                          can't be damaged by spikes in this attack.
     * @param gameManager
     * @return          The damage after applying any reduction due to spikes
     */
    protected int checkForSpikes(Entity target, int targetLocation, int damage,
                                 Entity attacker, int attackerLocation, GameManager gameManager) {
        return gameManager.combatManager.checkForSpikes(target, targetLocation, damage, attacker, attackerLocation, Entity.LOC_NONE, gameManager);
    }

    /**
     * Checks whether the location has spikes, and if so handles the damage to the
     * attack and returns the reduced damage. Locations without spikes return the
     * original damage amount.
     *
     * @param target            The target of a physical attack
     * @param targetLocation    The location that was hit
     * @param damage            The amount of damage dealt to the target
     * @param attacker          The attacker
     * @param attackerLocation  The location on the attacker that is damaged if the
     *                          target has spikes. Entity.LOC_NONE if the attacker
     *                          can't be damaged by spikes in this attack.
     * @param attackerLocation2 If not Entity.LOC_NONE, the damage to the attacker
     *                          will be split between two locations.
     * @param gameManager
     * @return          The damage after applying any reduction due to spikes
     */
    protected int checkForSpikes(Entity target, int targetLocation, int damage,
                                 Entity attacker, int attackerLocation, int attackerLocation2, GameManager gameManager) {
        if (target.hasWorkingMisc(MiscType.F_SPIKES, -1, targetLocation)) {
            Report r;
            if (damage == 0) {
                // Only show damage to attacker (push attack)
                r = new Report(4333);
            } else if (attackerLocation != Entity.LOC_NONE) {
                // Show damage reduction and damage to attacker
                r = new Report(4330);
            } else {
                // Only show damage reduction (club/physical weapon attack)
                r = new Report(4331);
            }
            r.indent(2);
            r.subject = target.getId();
            gameManager.addReport(r);
            // An attack that deals zero damage can still damage the attacker in the case of a push
            if (attackerLocation != Entity.LOC_NONE) {
                // Spikes also protect from retaliatory spike damage
                if (attacker.hasWorkingMisc(MiscType.F_SPIKES, -1, attackerLocation)) {
                    r = new Report(4332);
                    r.indent(2);
                    r.subject = attacker.getId();
                    gameManager.addReport(r);
                } else if (attackerLocation2 == Entity.LOC_NONE) {
                    gameManager.reportManager.addReport(gameManager.damageEntity(attacker, new HitData(attackerLocation), 2, false,
                            DamageType.NONE, false, false, false), gameManager);
                } else {
                    gameManager.reportManager.addReport(gameManager.damageEntity(attacker, new HitData(attackerLocation), 1, false,
                            DamageType.NONE, false, false, false), gameManager);
                    gameManager.reportManager.addReport(gameManager.damageEntity(attacker, new HitData(attackerLocation2), 1, false,
                            DamageType.NONE, false, false, false), gameManager);
                }
            }
            return Math.max(1, damage - 4);
        }
        return damage;
    }

    protected void resolveLayExplosivesAttack(PhysicalResult pr, GameManager gameManager) {
        final LayExplosivesAttackAction laa = (LayExplosivesAttackAction) pr.aaa;
        final Entity ae = gameManager.game.getEntity(laa.getEntityId());
        if (ae instanceof Infantry) {
            Infantry inf = (Infantry) ae;
            if (inf.turnsLayingExplosives < 0) {
                inf.turnsLayingExplosives = 0;
                Report r = new Report(4270);
                r.subject = inf.getId();
                r.addDesc(inf);
                gameManager.addReport(r);
            } else {
                Building building = gameManager.game.getBoard().getBuildingAt(ae.getPosition());
                if (building != null) {
                    building.addDemolitionCharge(ae.getOwner().getId(), pr.damage, ae.getPosition());
                    Report r = new Report(4275);
                    r.subject = inf.getId();
                    r.addDesc(inf);
                    r.add(pr.damage);
                    gameManager.addReport(r);
                    // Update clients with this info
                    Vector<Building> updatedBuildings = new Vector<>();
                    updatedBuildings.add(building);
                    gameManager.communicationManager.sendChangedBuildings(updatedBuildings, gameManager);
                }
                inf.turnsLayingExplosives = -1;
            }
        }
    }

    /**
     * Handle a death from above attack
     * @param pr
     * @param lastEntityId
     * @param gameManager
     */
    protected void resolveDfaAttack(PhysicalResult pr, int lastEntityId, GameManager gameManager) {
        final DfaAttackAction daa = (DfaAttackAction) pr.aaa;
        final Entity ae = gameManager.game.getEntity(daa.getEntityId());

        // is the attacker dead? because that sure messes up the calculations
        if (ae == null) {
            return;
        }

        final Hex aeHex = gameManager.game.getBoard().getHex(ae.getPosition());
        final Hex teHex = gameManager.game.getBoard().getHex(daa.getTargetPos());
        final Targetable target = gameManager.game.getTarget(daa.getTargetType(), daa.getTargetId());
        // get damage, ToHitData and roll from the PhysicalResult
        int damage = pr.damage;
        final ToHitData toHit = pr.toHit;
        int rollValue = pr.roll.getIntValue();
        Entity te = null;
        if ((target != null)
                && (target.getTargetType() == Targetable.TYPE_ENTITY)) {
            // Lets re-write around that horrible hack that was here before.
            // So instead of asking if a specific location is wet and praying
            // that it won't cause an NPE...
            // We'll check 1) if the hex has water, and 2) if it's deep enough
            // to cover the unit in question at its current elevation.
            // It's especially important to make sure it's done this way,
            // because some units (Sylph, submarines) can be at ANY elevation
            // underwater, and VTOLs can be well above the surface.
            te = (Entity) target;
            Hex hex = gameManager.game.getBoard().getHex(te.getPosition());
            if (hex.containsTerrain(Terrains.WATER)) {
                if (te.relHeight() < 0) {
                    damage = (int) Math.ceil(damage * 0.5f);
                }
            }
        }
        boolean throughFront = true;
        if (te != null) {
            throughFront = Compute.isThroughFrontHex(gameManager.game, ae.getPosition(), te);
        }
        final boolean glancing = gameManager.game.getOptions().booleanOption(
                OptionsConstants.ADVCOMBAT_TACOPS_GLANCING_BLOWS) && (rollValue == toHit.getValue());
        // Set Margin of Success/Failure.
        toHit.setMoS(rollValue - Math.max(2, toHit.getValue()));
        final boolean directBlow = gameManager.game.getOptions().booleanOption(
                OptionsConstants.ADVCOMBAT_TACOPS_DIRECT_BLOW) && ((toHit.getMoS() / 3) >= 1);

        Report r;

        final int direction = ae.getFacing();

        if (lastEntityId != daa.getEntityId()) {
            // who is making the attack
            r = new Report(4005);
            r.subject = ae.getId();
            r.addDesc(ae);
            gameManager.addReport(r);
        }

        // should we even bother?
        if ((target == null) || ((target.getTargetType() == Targetable.TYPE_ENTITY)
                && (te.isDestroyed() || te.isDoomed() || te.getCrew().isDead()))) {
            r = new Report(4245);
            r.subject = ae.getId();
            r.indent();
            gameManager.addReport(r);
            // entity isn't DFAing any more
            ae.setDisplacementAttack(null);
            if (ae.isProne()) {
                // attacker prone during weapons phase
                gameManager.reportManager.addReport(gameManager.doEntityFall(ae, daa.getTargetPos(), 2, 3,
                        ae.getBasePilotingRoll(), false, false), gameManager);

            } else {
                // same effect as successful DFA
                ae.setElevation(ae.calcElevation(aeHex, teHex, 0, false, false));
                gameManager.reportManager.addReport(gameManager.utilityManager.doEntityDisplacement(ae, ae.getPosition(),
                        daa.getTargetPos(), new PilotingRollData(ae.getId(), 4,
                                "executed death from above"), gameManager), gameManager);
            }
            return;
        }

        r = new Report(4246);
        r.subject = ae.getId();
        r.indent();
        r.add(target.getDisplayName());
        r.newlines = 0;
        gameManager.addReport(r);

        // target still in the same position?
        if (!target.getPosition().equals(daa.getTargetPos())) {
            r = new Report(4215);
            r.subject = ae.getId();
            gameManager.addReport(r);
            // entity isn't DFAing any more
            ae.setDisplacementAttack(null);
            gameManager.reportManager.addReport(gameManager.utilityManager.doEntityFallsInto(ae, ae.getElevation(), ae.getPosition(), daa.getTargetPos(),
                    ae.getBasePilotingRoll(), true, gameManager), gameManager);
            return;
        }

        // hack: if the attacker's prone, or incapacitated, fudge the roll
        if (ae.isProne() || !ae.isActive()) {
            rollValue = -12;
            r = new Report(4250);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            r.newlines--;
            gameManager.addReport(r);
        } else if (toHit.getValue() == TargetRoll.IMPOSSIBLE) {
            rollValue = -12;
            r = new Report(4255);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            gameManager.addReport(r);
        } else if (toHit.getValue() == TargetRoll.AUTOMATIC_SUCCESS) {
            r = new Report(4260);
            r.subject = ae.getId();
            r.add(toHit.getDesc());
            gameManager.addReport(r);
            rollValue = Integer.MAX_VALUE;
        } else {
            // report the roll
            r = new Report(4025);
            r.subject = ae.getId();
            r.add(toHit);
            r.add(pr.roll);
            r.newlines = 0;
            gameManager.addReport(r);
            if (glancing) {
                r = new Report(3186);
                r.subject = ae.getId();
                r.newlines = 0;
                gameManager.addReport(r);
            }

            if (directBlow) {
                r = new Report(3189);
                r.subject = ae.getId();
                r.newlines = 0;
                gameManager.addReport(r);
            }

        }

        // do we hit?
        if (rollValue < toHit.getValue()) {
            Coords dest = te.getPosition();
            Coords targetDest = Compute.getPreferredDisplacement(gameManager.game, te.getId(), dest, direction);
            // miss
            r = new Report(4035);
            r.subject = ae.getId();
            gameManager.addReport(r);
            if (targetDest != null) {
                // move target to preferred hex
                gameManager.reportManager.addReport(gameManager.utilityManager.doEntityDisplacement(te, dest, targetDest, null, gameManager), gameManager);
                // attacker falls into destination hex
                r = new Report(4265);
                r.subject = ae.getId();
                r.addDesc(ae);
                r.add(dest.getBoardNum(), true);
                r.indent();
                gameManager.addReport(r);
                // entity isn't DFAing any more
                ae.setDisplacementAttack(null);
                gameManager.reportManager.addReport(gameManager.doEntityFall(ae, dest, 2, 3, ae.getBasePilotingRoll(),
                        false, false), 1, gameManager);
                Entity violation = Compute.stackingViolation(gameManager.game, ae.getId(), dest, ae.climbMode());
                if (violation != null) {
                    // target gets displaced
                    targetDest = Compute.getValidDisplacement(gameManager.game,
                            violation.getId(), dest, direction);
                    gameManager.vPhaseReport.addAll(gameManager.utilityManager.doEntityDisplacement(violation, dest,
                            targetDest, new PilotingRollData(violation.getId(),
                                    0, "domino effect"), gameManager));
                    // Update the violating entity's position on the client.
                    if (!gameManager.game.getOutOfGameEntitiesVector().contains(violation)) {
                        gameManager.entityActionManager.entityUpdate(violation.getId(), gameManager);
                    }
                }
            } else {
                // attacker destroyed
                // Tanks suffer an ammo/power plant hit.
                // TODO : a Mech suffers a Head Blown Off crit.
                gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity(ae, "impossible displacement",
                        ae instanceof Mech, ae instanceof Mech, gameManager), gameManager);
            }
            return;
        }

        // we hit...

        r = new Report(4040);
        r.subject = ae.getId();
        gameManager.addReport(r);

        Coords dest = target.getPosition();

        // Can't DFA a target inside of a building.
        int damageTaken = DfaAttackAction.getDamageTakenBy(ae);

        // Targeting a building.
        if ((target.getTargetType() == Targetable.TYPE_BUILDING)
                || (target.getTargetType() == Targetable.TYPE_FUEL_TANK)) {
            // Which building takes the damage?
            Building bldg = gameManager.game.getBoard().getBuildingAt(daa.getTargetPos());

            // The building takes the full brunt of the attack.
            Vector<Report> buildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, damage, target.getPosition(), gameManager);
            for (Report report : buildingReport) {
                report.subject = ae.getId();
            }
            gameManager.reportManager.addReport(buildingReport, gameManager);

            // Damage any infantry in the hex.
            gameManager.reportManager.addReport(gameManager.environmentalEffectManager.damageInfantryIn(bldg, damage, target.getPosition(), gameManager), gameManager);
        } else { // Target isn't building.
            if (glancing && (te != null)) {
                damage = (int) (te.isConventionalInfantry() ? Math.ceil(damage / 2.0) : Math.floor(damage / 2.0));
            }

            if (directBlow) {
                damage += toHit.getMoS() / 3;
            }
            // damage target
            r = new Report(4230);
            r.subject = ae.getId();
            r.add(damage);
            r.add(toHit.getTableDesc());
            r.indent(2);
            gameManager.addReport(r);

            while (damage > 0) {
                int cluster = Math.min(5, damage);
                HitData hit = te.rollHitLocation(toHit.getHitTable(), toHit.getSideTable());
                hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                if (directBlow) {
                    hit.makeDirectBlow(toHit.getMoS() / 3);
                }
                damage -= cluster;
                cluster = checkForSpikes(te, hit.getLocation(), cluster, ae, Mech.LOC_LLEG, Mech.LOC_RLEG, gameManager);
                gameManager.reportManager.addReport(gameManager.damageEntity(te, hit, cluster, false,
                        DamageType.NONE, false, false, throughFront), gameManager);
            }

            if (target instanceof VTOL) {
                // destroy rotor
                gameManager.reportManager.addReport(gameManager.applyCriticalHit(te, VTOL.LOC_ROTOR,
                        new CriticalSlot(CriticalSlot.TYPE_SYSTEM,
                                VTOL.CRIT_ROTOR_DESTROYED), false, 0, false), gameManager);
            }
            // Target entities are pushed away or destroyed.
            Coords targetDest = Compute.getValidDisplacement(gameManager.game, te.getId(), dest, direction);
            if (targetDest != null) {
                gameManager.reportManager.addReport(gameManager.utilityManager.doEntityDisplacement(te, dest, targetDest,
                        new PilotingRollData(te.getId(), 2, "hit by death from above"), gameManager), gameManager);
            } else {
                // ack! automatic death! Tanks
                // suffer an ammo/power plant hit.
                // TODO : a Mech suffers a Head Blown Off crit.
                gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity(te, "impossible displacement",
                        te instanceof Mech, te instanceof Mech, gameManager), gameManager);
            }

        }

        if (glancing) {
            // Glancing Blow rule doesn't state whether damage to attacker on charge
            // or DFA is halved as well, assume yes. TODO : Check with PM
            damageTaken = (int) Math.floor(damageTaken / 2.0);
        }

        if (ae.hasQuirk(OptionsConstants.QUIRK_POS_REINFORCED_LEGS)) {
            damageTaken = (int) Math.floor(damageTaken / 2.0);
        }

        // damage attacker
        r = new Report(4240);
        r.subject = ae.getId();
        r.add(damageTaken);
        r.indent(2);
        gameManager.addReport(r);
        while (damageTaken > 0) {
            int cluster = Math.min(5, damageTaken);
            HitData hit = ae.rollHitLocation(ToHitData.HIT_KICK, ToHitData.SIDE_FRONT);
            hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
            gameManager.reportManager.addReport(gameManager.damageEntity(ae, hit, cluster), gameManager);
            damageTaken -= cluster;
        }

        if (ae.hasQuirk(OptionsConstants.QUIRK_NEG_WEAK_LEGS)) {
            gameManager.addNewLines();
            gameManager.reportManager.addReport(gameManager.criticalEntity(ae, Mech.LOC_LLEG, false, 0, 0), gameManager);
            gameManager.addNewLines();
            gameManager.reportManager.addReport(gameManager.criticalEntity(ae, Mech.LOC_RLEG, false, 0, 0), gameManager);
            if (ae instanceof QuadMech) {
                gameManager.addNewLines();
                gameManager.reportManager.addReport(gameManager.criticalEntity(ae, Mech.LOC_LARM, false, 0, 0), gameManager);
                gameManager.addNewLines();
                gameManager.reportManager.addReport(gameManager.criticalEntity(ae, Mech.LOC_RARM, false, 0, 0), gameManager);
            }
        }

        gameManager.addNewLines();

        // That's it for target buildings.
        if ((target.getTargetType() == Targetable.TYPE_BUILDING)
                || (target.getTargetType() == Targetable.TYPE_FUEL_TANK)) {
            return;
        }
        ae.setElevation(ae.calcElevation(aeHex, teHex, 0, false, false));
        // HACK: to avoid automatic falls, displace from dest to dest
        gameManager.reportManager.addReport(gameManager.utilityManager.doEntityDisplacement(ae, dest, dest, new PilotingRollData(
                ae.getId(), 4, "executed death from above"), gameManager), gameManager);

        // entity isn't DFAing any more
        ae.setDisplacementAttack(null);

        // if the target is an industrial mech, it needs to check for crits at the end of turn
        if ((target instanceof Mech) && ((Mech) target).isIndustrial()) {
            ((Mech) target).setCheckForCrit(true);
        }
    }
}
