package megamek.server.gameManager;

import megamek.common.*;
import megamek.common.actions.DfaAttackAction;
import megamek.common.actions.EntityAction;
import megamek.common.actions.SearchlightAttackAction;
import megamek.common.equipment.ArmorType;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.weapons.DamageType;
import megamek.server.SmokeCloud;
import org.apache.logging.log4j.LogManager;

import java.util.*;

public class EnvironmentalEffectManager {
    protected void applyDropShipLandingDamage(Coords centralPos, Entity killer, GameManager gameManager) {
        // first cycle through hexes to figure out final elevation
        Hex centralHex = gameManager.game.getBoard().getHex(centralPos);
        if (null == centralHex) {
            // shouldn't happen
            return;
        }
        int finalElev = centralHex.getLevel();
        if (!centralHex.containsTerrain(Terrains.PAVEMENT)
                && !centralHex.containsTerrain(Terrains.ROAD)) {
            finalElev--;
        }
        Vector<Coords> positions = new Vector<>();
        positions.add(centralPos);
        for (int i = 0; i < 6; i++) {
            Coords pos = centralPos.translated(i);
            Hex hex = gameManager.game.getBoard().getHex(pos);
            if (null == hex) {
                continue;
            }
            if (hex.getLevel() < finalElev) {
                finalElev = hex.getLevel();
            }
            positions.add(pos);
        }
        // ok now cycle through hexes and make all changes
        for (Coords pos : positions) {
            Hex hex = gameManager.game.getBoard().getHex(pos);
            hex.setLevel(finalElev);
            // get rid of woods and replace with rough
            if (hex.containsTerrain(Terrains.WOODS) || hex.containsTerrain(Terrains.JUNGLE)) {
                hex.removeTerrain(Terrains.WOODS);
                hex.removeTerrain(Terrains.JUNGLE);
                hex.removeTerrain(Terrains.FOLIAGE_ELEV);
                hex.addTerrain(new Terrain(Terrains.ROUGH, 1));
            }
            gameManager.communicationManager.sendChangedHex(pos, gameManager);
        }

        gameManager.environmentalEffectManager.applyDropShipProximityDamage(centralPos, killer, gameManager);
    }

    protected void applyDropShipProximityDamage(Coords centralPos, Entity killer, GameManager gameManager) {
        gameManager.environmentalEffectManager.applyDropShipProximityDamage(centralPos, false, 0, killer, gameManager);
    }

    /**
     * apply damage to units and buildings within a certain radius of a landing
     * or lifting off DropShip
     *
     * @param centralPos - the Coords for the central position of the DropShip
     * @param rearArc
     * @param facing
     * @param killer
     * @param gameManager
     */
    protected void applyDropShipProximityDamage(Coords centralPos, boolean rearArc, int facing,
                                                Entity killer, GameManager gameManager) {
        Vector<Integer> alreadyHit = new Vector<>();

        // anything in the central hex or adjacent hexes is destroyed
        Hashtable<Coords, Vector<Entity>> positionMap = gameManager.game.getPositionMap();
        for (Entity en : gameManager.game.getEntitiesVector(centralPos)) {
            if (!en.isAirborne()) {
                gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity(en, "DropShip proximity damage", false, false, gameManager), gameManager);
                alreadyHit.add(en.getId());
            }
        }
        Building bldg = gameManager.game.getBoard().getBuildingAt(centralPos);
        if (null != bldg) {
            gameManager.collapseBuilding(bldg, positionMap, centralPos, gameManager.vPhaseReport);
        }
        for (int i = 0; i < 6; i++) {
            Coords pos = centralPos.translated(i);
            for (Entity en : gameManager.game.getEntitiesVector(pos)) {
                if (!en.isAirborne()) {
                    gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity(en, "DropShip proximity damage", false, false, gameManager), gameManager);
                }
                alreadyHit.add(en.getId());
            }
            bldg = gameManager.game.getBoard().getBuildingAt(pos);
            if (null != bldg) {
                gameManager.collapseBuilding(bldg, positionMap, pos, gameManager.vPhaseReport);
            }
        }

        // Report r;
        // ok now I need to look at the damage rings - start at 2 and go to 7
        for (int i = 2; i < 8; i++) {
            int damageDice = (8 - i) * 2;
            List<Coords> ring = centralPos.allAtDistance(i);
            for (Coords pos : ring) {
                if (rearArc && !Compute.isInArc(centralPos, facing, pos, Compute.ARC_AFT)) {
                    continue;
                }

                alreadyHit = gameManager.artilleryDamageHex(pos, centralPos, damageDice, null, killer.getId(),
                        killer, null, false, 0, gameManager.vPhaseReport, false,
                        alreadyHit, true);
            }
        }
        gameManager.entityActionManager.destroyDoomedEntities(alreadyHit, gameManager);
    }

    /**
     * Any aerospace unit that lands in a rough or rubble hex takes landing hear damage.
     * @param aero         The landing unit
     * @param vertical     Whether the landing is vertical
     * @param touchdownPos The coordinates of the hex of touchdown
     * @param finalPos     The coordinates of the hex in which the unit comes to a stop
     * @param facing       The facing of the landing unit
     * @param gameManager
     */
    protected void checkLandingTerrainEffects(IAero aero, boolean vertical, Coords touchdownPos, Coords finalPos, int facing, GameManager gameManager) {
        // Landing in a rough for rubble hex damages landing gear.
        Set<Coords> landingPositions = aero.getLandingCoords(vertical, touchdownPos, facing);
        if (landingPositions.stream().map(c -> gameManager.game.getBoard().getHex(c)).filter(Objects::nonNull)
                .anyMatch(h -> h.containsTerrain(Terrains.ROUGH) || h.containsTerrain(Terrains.RUBBLE))) {
            aero.setGearHit(true);
            Report r = new Report(9125);
            r.subject = ((Entity) aero).getId();
            gameManager.addReport(r);
        }
        // Landing in water can destroy or immobilize the unit.
        Hex hex = gameManager.game.getBoard().getHex(finalPos);
        if ((aero instanceof Aero) && hex.containsTerrain(Terrains.WATER) && !hex.containsTerrain(Terrains.ICE)
                && (hex.terrainLevel(Terrains.WATER) > 0)
                && !((Entity) aero).hasWorkingMisc(MiscType.F_FLOTATION_HULL)) {
            if ((hex.terrainLevel(Terrains.WATER) > 1) || !(aero instanceof Dropship)) {
                Report r = new Report(9702);
                r.subject(((Entity) aero).getId());
                r.addDesc((Entity) aero);
                gameManager.addReport(r);
                gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity((Entity) aero, "landing in deep water", gameManager), gameManager);
            }
        }
    }

    protected boolean launchUnit(Entity unloader, Targetable unloaded,
                                 Coords pos, int facing, int velocity, int altitude, int[] moveVec,
                                 int bonus, GameManager gameManager) {

        Entity unit;
        if (unloaded instanceof Entity && unloader instanceof Aero) {
            unit = (Entity) unloaded;
        } else {
            return false;
        }

        // must be an ASF, Small Craft, or DropShip
        if (!unit.isAero() || unit instanceof Jumpship) {
            return false;
        }
        IAero a = (IAero) unit;

        Report r;

        // Unload the unit.
        if (!unloader.unload(unit)) {
            return false;
        }

        // The unloaded unit is no longer being carried.
        unit.setTransportId(Entity.NONE);

        // pg. 86 of TW: launched fighters can move in fire in the turn they are
        // unloaded
        unit.setUnloaded(false);

        // Place the unloaded unit onto the screen.
        unit.setPosition(pos);

        // Units unloaded onto the screen are deployed.
        if (pos != null) {
            unit.setDeployed(true);
        }

        // Point the unloaded unit in the given direction.
        unit.setFacing(facing);
        unit.setSecondaryFacing(facing);

        // the velocity of the unloaded unit is the same as the loader
        a.setCurrentVelocity(velocity);
        a.setNextVelocity(velocity);

        // if using advanced movement then set vectors
        unit.setVectors(moveVec);

        unit.setAltitude(altitude);

        // it seems that the done button is still being set and I can't figure
        // out where
        unit.setDone(false);

        // if the bonus was greater than zero then too many fighters were
        // launched and they
        // must all make control rolls
        if (bonus > 0) {
            PilotingRollData psr = unit.getBasePilotingRoll();
            psr.addModifier(bonus, "safe launch rate exceeded");
            Roll diceRoll = Compute.rollD6(2);
            r = new Report(9375);
            r.subject = unit.getId();
            r.add(unit.getDisplayName());
            r.add(psr);
            r.add(diceRoll);
            r.indent(1);

            if (diceRoll.getIntValue() < psr.getValue()) {
                r.choose(false);
                gameManager.addReport(r);
                // damage the unit
                int damage = 10 * (psr.getValue() - diceRoll.getIntValue());
                HitData hit = unit.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                Vector<Report> rep = gameManager.damageEntity(unit, hit, damage);
                Report.indentAll(rep, 1);
                rep.lastElement().newlines++;
                gameManager.reportManager.addReport(rep, gameManager);
                // did we destroy the unit?
                if (unit.isDoomed()) {
                    // Clean out the entity.
                    unit.setDestroyed(true);
                    gameManager.game.moveToGraveyard(unit.getId());
                    gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(unit.getId(), gameManager));
                }
            } else {
                // avoided damage
                r.choose(true);
                r.newlines++;
                gameManager.addReport(r);
            }
        } else {
            r = new Report(9374);
            r.subject = unit.getId();
            r.add(unit.getDisplayName());
            r.indent(1);
            r.newlines++;
            gameManager.addReport(r);
        }

        // launching from an OOC vessel causes damage
        // same thing if faster than 2 velocity in atmosphere
        if ((((Aero) unloader).isOutControlTotal() && !unit.isDoomed())
                || ((((Aero) unloader).getCurrentVelocity() > 2) && !gameManager.game
                .getBoard().inSpace())) {
            Roll diceRoll = Compute.rollD6(2);
            int damage = diceRoll.getIntValue() * 10;
            String rollCalc = damage + "[" + diceRoll.getIntValue() + " * 10]";
            r = new Report(9385);
            r.subject = unit.getId();
            r.add(unit.getDisplayName());
            r.addDataWithTooltip(rollCalc, diceRoll.getReport());
            gameManager.addReport(r);
            HitData hit = unit.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
            gameManager.reportManager.addReport(gameManager.damageEntity(unit, hit, damage), gameManager);
            // did we destroy the unit?
            if (unit.isDoomed()) {
                // Clean out the entity.
                unit.setDestroyed(true);
                gameManager.game.moveToGraveyard(unit.getId());
                gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(unit.getId(), gameManager));
            }
        }

        // Update the unloaded unit.
        gameManager.entityUpdate(unit.getId());

        // Set the turn mask. We need to be specific otherwise we run the risk
        // of having a unit of another class consume the turn and leave the
        // unloaded unit without a turn
        int turnMask;
        List<GameTurn> turnVector = gameManager.game.getTurnVector();
        if (unit instanceof Dropship) {
            turnMask = GameTurn.CLASS_DROPSHIP;
        } else if (unit instanceof SmallCraft) {
            turnMask = GameTurn.CLASS_SMALL_CRAFT;
        } else {
            turnMask = GameTurn.CLASS_AERO;
        }
        // Add one, otherwise we consider the turn we're currently processing
        int turnInsertIdx = gameManager.game.getTurnIndex() + 1;
        // We have to figure out where to insert this turn, to maintain proper
        // space turn order (JumpShips, Small Craft, DropShips, Aeros)
        for (; turnInsertIdx < turnVector.size(); turnInsertIdx++) {
            GameTurn turn = turnVector.get(turnInsertIdx);
            if (turn.isValidEntity(unit, gameManager.game)) {
                break;
            }
        }

        // ok add another turn for the unloaded entity so that it can move
        GameTurn newTurn = new GameTurn.EntityClassTurn(unit.getOwner().getId(), turnMask);
        gameManager.game.insertTurnAfter(newTurn, turnInsertIdx);
        // brief everybody on the turn update
        gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));

        return true;
    }

    public void dropUnit(Entity drop, Entity entity, Coords curPos, int altitude, GameManager gameManager) {
        // Unload the unit.
        entity.unload(drop);
        // The unloaded unit is no longer being carried.
        drop.setTransportId(Entity.NONE);

        // OK according to Welshman's pending ruling, when on the ground map
        // units should be deployed in the ring two hexes away from the DropShip
        // optimally, we should let people choose here, but that would be
        // complicated
        // so for now I am just going to distribute them. I will give each unit
        // the first
        // emptiest hex that has no water or magma in it.
        // I will start the circle based on the facing of the dropper
        // Spheroid - facing
        // Aerodyne - opposite of facing
        // http://www.classicbattletech.com/forums/index.php?topic=65600.msg1568089#new
        if (gameManager.game.getBoard().onGround() && (null != curPos)) {
            boolean selected = false;
            int count;
            int max = 0;
            int facing = entity.getFacing();
            if (entity.getMovementMode() == EntityMovementMode.AERODYNE) {
                // no real rule for this but it seems to make sense that units
                // would drop behind an
                // aerodyne rather than in front of it
                facing = (facing + 3) % 6;
            }
            boolean checkDanger = true;
            while (!selected) {
                // we can get caught in an infinite loop if all available hexes
                // are dangerous, so check for this
                boolean allDanger = true;
                for (int i = 0; i < 6; i++) {
                    int dir = (facing + i) % 6;
                    Coords newPos = curPos.translated(dir, 2);
                    count = 0;
                    if (gameManager.game.getBoard().contains(newPos)) {
                        Hex newHex = gameManager.game.getBoard().getHex(newPos);
                        Building bldg = gameManager.game.getBoard().getBuildingAt(newPos);
                        boolean danger = newHex.containsTerrain(Terrains.WATER)
                                || newHex.containsTerrain(Terrains.MAGMA)
                                || (null != bldg);
                        for (Entity unit : gameManager.game.getEntitiesVector(newPos)) {
                            if ((unit.getAltitude() == altitude)
                                    && !unit.isAero()) {
                                count++;
                            }
                        }
                        if ((count <= max) && (!danger || !checkDanger)) {
                            selected = true;
                            curPos = newPos;
                            break;
                        }
                        if (!danger) {
                            allDanger = false;
                        }
                    }
                    newPos = newPos.translated((dir + 2) % 6);
                    count = 0;
                    if (gameManager.game.getBoard().contains(newPos)) {
                        Hex newHex = gameManager.game.getBoard().getHex(newPos);
                        Building bldg = gameManager.game.getBoard().getBuildingAt(newPos);
                        boolean danger = newHex.containsTerrain(Terrains.WATER)
                                || newHex.containsTerrain(Terrains.MAGMA)
                                || (null != bldg);
                        for (Entity unit : gameManager.game.getEntitiesVector(newPos)) {
                            if ((unit.getAltitude() == altitude) && !unit.isAero()) {
                                count++;
                            }
                        }
                        if ((count <= max) && (!danger || !checkDanger)) {
                            selected = true;
                            curPos = newPos;
                            break;
                        }
                        if (!danger) {
                            allDanger = false;
                        }
                    }
                }
                if (allDanger && checkDanger) {
                    checkDanger = false;
                } else {
                    max++;
                }
            }
        }

        // Place the unloaded unit onto the screen.
        drop.setPosition(curPos);

        // Units unloaded onto the screen are deployed.
        if (curPos != null) {
            drop.setDeployed(true);
        }

        // Point the unloaded unit in the given direction.
        drop.setFacing(entity.getFacing());
        drop.setSecondaryFacing(entity.getFacing());

        drop.setAltitude(altitude);
        gameManager.entityUpdate(drop.getId());
    }

    /**
     * Record that the given building has been affected by the current entity's
     * movement. At the end of the entity's movement, notify the clients about
     * the updates.
     *  @param bldg     - the <code>Building</code> that has been affected.
     * @param collapse - a <code>boolean</code> value that specifies that the
     * @param gameManager
     */
    protected void addAffectedBldg(Building bldg, boolean collapse, GameManager gameManager) {
        // If the building collapsed, then the clients have already
        // been notified, so remove it from the notification list.
        if (collapse) {
            gameManager.affectedBldgs.remove(bldg);
        } else { // Otherwise, make sure that this building is tracked.
            gameManager.affectedBldgs.put(bldg, Boolean.FALSE);
        }
    }

    /**
     * Walk through the building hexes that were affected by the recent entity's
     * movement. Notify the clients about the updates to all affected entities
     * and non-collapsed buildings. The affected hexes is then cleared for the
     * next entity's movement.
     * @param gameManager
     */
    protected void applyAffectedBldgs(GameManager gameManager) {
        // Build a list of Building updates.
        Vector<Building> bldgUpdates = new Vector<>();

        // Only send a single turn update.
        boolean bTurnsChanged = false;

        // Walk the set of buildings.
        Enumeration<Building> bldgs = gameManager.affectedBldgs.keys();
        while (bldgs.hasMoreElements()) {
            final Building bldg = bldgs.nextElement();

            // Walk through the building's coordinates.
            Enumeration<Coords> bldgCoords = bldg.getCoords();
            while (bldgCoords.hasMoreElements()) {
                final Coords coords = bldgCoords.nextElement();
                // Walk through the entities at these coordinates.
                for (Entity entity : gameManager.game.getEntitiesVector(coords)) {
                    // Is the entity infantry?
                    if (entity instanceof Infantry) {
                        // Is the infantry dead?
                        if (entity.isDoomed() || entity.isDestroyed()) {
                            // Has the entity taken a turn?
                            if (!entity.isDone()) {
                                // Dead entities don't take turns.
                                gameManager.game.removeTurnFor(entity);
                                bTurnsChanged = true;
                            } // End entity-still-to-move

                            // Clean out the dead entity.
                            entity.setDestroyed(true);
                            gameManager.game.moveToGraveyard(entity.getId());
                            gameManager.communicationManager.send(gameManager.packetManager.createRemoveEntityPacket(entity.getId(), gameManager));
                        } else { // Infantry that aren't dead are damaged.
                            gameManager.entityUpdate(entity.getId());
                        }
                    } // End entity-is-infantry
                } // Check the next entity.
            } // Handle the next hex in this building.
            // Add this building to the report.
            bldgUpdates.addElement(bldg);
        } // Handle the next affected building.

        // Did we update the turns?
        if (bTurnsChanged) {
            gameManager.communicationManager.send(gameManager.packetManager.createTurnVectorPacket(gameManager));
        }

        // Are there any building updates?
        if (!bldgUpdates.isEmpty()) {
            // Send the building updates to the clients.
            gameManager.communicationManager.sendChangedBuildings(bldgUpdates, gameManager);

            // Clear the list of affected buildings.
            gameManager.affectedBldgs.clear();
        }

        // And we're done.
    } // End protected void applyAffectedBldgs()

    /**
     * Check for any detonations when an entity enters a minefield, except a
     * vibrabomb.
     *
     * @param entity
     *            - the <code>entity</code> who entered the minefield
     * @param c
     *            - the <code>Coords</code> of the minefield
     * @param curElev
     *            - an <code>int</code> for the elevation of the entity entering
     *            the minefield (used for underwater sea mines)
     * @param isOnGround
     *            - <code>true</code> if the entity is not in the middle of a
     *            jump
     * @param vMineReport
     *            - the {@link Report} <code>Vector</code> that reports will be added to
     * @param gameManager
     * @return - <code>true</code> if the entity set off any mines
     */
    protected boolean enterMinefield(Entity entity, Coords c, int curElev, boolean isOnGround,
                                     Vector<Report> vMineReport, GameManager gameManager) {
        return gameManager.environmentalEffectManager.enterMinefield(entity, c, curElev, isOnGround, vMineReport, -1, gameManager);
    }

    /**
     * Check for any detonations when an entity enters a minefield, except a
     * vibrabomb.
     *
     * @param entity
     *            - the <code>entity</code> who entered the minefield
     * @param c
     *            - the <code>Coords</code> of the minefield
     * @param curElev
     *            - an <code>int</code> for the elevation of the entity entering
     *            the minefield (used for underwater sea mines)
     * @param isOnGround
     *            - <code>true</code> if the entity is not in the middle of a
     *            jump
     * @param vMineReport
     *            - the {@link Report} <code>Vector</code> that reports will be added to
     * @param target
     *            - the <code>int</code> target number for detonation. If this
     *            will be determined by density, it should be -1
     * @param gameManager
     * @return - <code>true</code> if the entity set off any mines
     */
    protected boolean enterMinefield(Entity entity, Coords c, int curElev, boolean isOnGround,
                                     Vector<Report> vMineReport, int target, GameManager gameManager) {
        Report r;
        boolean trippedMine = false;
        // flying units cannot trip a mine
        if (curElev > 0) {
            return false;
        }

        // Check for Mine sweepers
        Mounted minesweeper = null;
        for (Mounted m : entity.getMisc()) {
            if (m.getType().hasFlag(MiscType.F_MINESWEEPER) && m.isReady() && (m.getArmorValue() > 0)) {
                minesweeper = m;
                break; // Can only have one minesweeper
            }
        }

        Vector<Minefield> fieldsToRemove = new Vector<>();
        // loop through mines in this hex
        for (Minefield mf : gameManager.game.getMinefields(c)) {
            // vibrabombs are handled differently
            if (mf.getType() == Minefield.TYPE_VIBRABOMB) {
                continue;
            }

            // if we are in the water, then the sea mine will only blow up if at
            // the right depth
            if (gameManager.game.getBoard().getHex(mf.getCoords()).containsTerrain(Terrains.WATER)) {
                if ((Math.abs(curElev) != mf.getDepth())
                        && (Math.abs(curElev + entity.getHeight()) != mf.getDepth())) {
                    continue;
                }
            }

            // Check for mine-sweeping. Vibramines handled elsewhere
            if ((minesweeper != null)
                    && ((mf.getType() == Minefield.TYPE_CONVENTIONAL)
                    || (mf.getType() == Minefield.TYPE_ACTIVE)
                    || (mf.getType() == Minefield.TYPE_INFERNO))) {
                // Check to see if the minesweeper clears
                Roll diceRoll = Compute.rollD6(2);

                // Report minefield roll
                if (gameManager.doBlind()) { // only report if DB, otherwise all players see
                    r = new Report(2152, Report.PLAYER);
                    r.player = mf.getPlayerId();
                    r.add(Minefield.getDisplayableName(mf.getType()));
                    r.add(mf.getCoords().getBoardNum());
                    r.add(diceRoll);
                    r.newlines = 0;
                    vMineReport.add(r);
                }

                if (diceRoll.getIntValue() >= 6) {
                    // Report hit
                    if (gameManager.doBlind()) {
                        r = new Report(5543, Report.PLAYER);
                        r.player = mf.getPlayerId();
                        vMineReport.add(r);
                    }

                    // Clear the minefield
                    r = new Report(2158);
                    r.subject = entity.getId();
                    r.add(entity.getShortName(), true);
                    r.add(Minefield.getDisplayableName(mf.getType()), true);
                    r.add(mf.getCoords().getBoardNum(), true);
                    r.indent();
                    vMineReport.add(r);
                    fieldsToRemove.add(mf);

                    // Handle armor value damage
                    int remainingAV = minesweeper.getArmorValue() - 6;
                    minesweeper.setArmorValue(Math.max(remainingAV, 0));

                    r = new Report(2161);
                    r.indent(2);
                    r.subject = entity.getId();
                    r.add(entity.getShortName(), true);
                    r.add(6);
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
                        Vector<Report> damageReports = gameManager.damageEntity(entity, hit, damage);
                        for (Report r1 : damageReports) {
                            r1.indent(1);
                        }
                        vMineReport.addAll(damageReports);
                    }
                    Report.addNewline(vMineReport);
                    // If the minefield is cleared, we're done processing it
                    continue;
                } else {
                    // Report miss
                    if (gameManager.doBlind()) {
                        r = new Report(5542, Report.PLAYER);
                        r.player = mf.getPlayerId();
                        vMineReport.add(r);
                    }
                }
            }

            // check whether we have an active mine
            if ((mf.getType() == Minefield.TYPE_ACTIVE) && isOnGround) {
                continue;
            } else if ((mf.getType() != Minefield.TYPE_ACTIVE) && !isOnGround) {
                continue;
            }

            // set the target number
            if (target == -1) {
                target = mf.getTrigger();
                if (mf.getType() == Minefield.TYPE_ACTIVE) {
                    target = 9;
                }
                if (entity instanceof Infantry) {
                    target += 1;
                }
                if (entity.hasAbility(OptionsConstants.MISC_EAGLE_EYES)) {
                    target += 2;
                }
                if ((entity.getMovementMode() == EntityMovementMode.HOVER)
                        || (entity.getMovementMode() == EntityMovementMode.WIGE)) {
                    target = Minefield.HOVER_WIGE_DETONATION_TARGET;
                }
            }

            Roll diceRoll = Compute.rollD6(2);

            // Report minefield roll
            if (gameManager.doBlind()) { // Only do if DB, otherwise all players will see
                r = new Report(2151, Report.PLAYER);
                r.player = mf.getPlayerId();
                r.add(Minefield.getDisplayableName(mf.getType()));
                r.add(mf.getCoords().getBoardNum());
                r.add(target);
                r.add(diceRoll);
                r.newlines = 0;
                vMineReport.add(r);
            }

            if (diceRoll.getIntValue() < target) {
                // Report miss
                if (gameManager.doBlind()) {
                    r = new Report(2217, Report.PLAYER);
                    r.player = mf.getPlayerId();
                    vMineReport.add(r);
                }
                continue;
            }

            // Report hit
            if (gameManager.doBlind()) {
                r = new Report(2270, Report.PLAYER);
                r.player = mf.getPlayerId();
                vMineReport.add(r);
            }

            // apply damage
            trippedMine = true;
            // explodedMines.add(mf);
            mf.setDetonated(true);
            if (mf.getType() == Minefield.TYPE_INFERNO) {
                // report hitting an inferno mine
                r = new Report(2155);
                r.subject = entity.getId();
                r.add(entity.getShortName(), true);
                r.add(mf.getCoords().getBoardNum(), true);
                r.indent();
                vMineReport.add(r);
                vMineReport.addAll(gameManager.environmentalEffectManager.deliverInfernoMissiles(entity, entity, mf.getDensity() / 2, gameManager));
            } else {
                r = new Report(2150);
                r.subject = entity.getId();
                r.add(entity.getShortName(), true);
                r.add(mf.getCoords().getBoardNum(), true);
                r.indent();
                vMineReport.add(r);
                int damage = mf.getDensity();
                while (damage > 0) {
                    int cur_damage = Math.min(5, damage);
                    damage = damage - cur_damage;
                    HitData hit;
                    if (minesweeper == null) {
                        hit = entity.rollHitLocation(Minefield.TO_HIT_TABLE,
                                Minefield.TO_HIT_SIDE);
                    } else { // Minesweepers cause mines to hit minesweeper loc
                        hit = new HitData(minesweeper.getLocation());
                    }
                    vMineReport.addAll(gameManager.damageEntity(entity, hit, cur_damage));
                }

                if (entity instanceof Tank) {
                    // Tanks check for motive system damage from minefields as
                    // from a side hit even though the damage proper hits the
                    // front above; exact side doesn't matter, though.
                    vMineReport.addAll(gameManager.vehicleMotiveDamage((Tank) entity,
                            entity.getMotiveSideMod(ToHitData.SIDE_LEFT)));
                }
                Report.addNewline(vMineReport);
            }

            // check the direct reduction
            mf.checkReduction(0, true);
            gameManager.revealMinefield(mf);
        }

        for (Minefield mf : fieldsToRemove) {
            gameManager.environmentalEffectManager.removeMinefield(mf, gameManager);
        }

        return trippedMine;
    }

    /**
     * cycle through all mines on the board, check to see whether they should do
     * collateral damage to other mines due to detonation, resets detonation to
     * false, and removes any mines whose density has been reduced to zero.
     * @param gameManager
     */
    protected void resetMines(GameManager gameManager) {
        Enumeration<Coords> mineLoc = gameManager.game.getMinedCoords();
        while (mineLoc.hasMoreElements()) {
            Coords c = mineLoc.nextElement();
            Enumeration<Minefield> minefields = gameManager.game.getMinefields(c).elements();
            while (minefields.hasMoreElements()) {
                Minefield minefield = minefields.nextElement();
                if (minefield.hasDetonated()) {
                    minefield.setDetonated(false);
                    Enumeration<Minefield> otherMines = gameManager.game.getMinefields(c).elements();
                    while (otherMines.hasMoreElements()) {
                        Minefield otherMine = otherMines.nextElement();
                        if (otherMine.equals(minefield)) {
                            continue;
                        }
                        int bonus = 0;
                        if (otherMine.getDensity() > minefield.getDensity()) {
                            bonus = 1;
                        }
                        if (otherMine.getDensity() < minefield.getDensity()) {
                            bonus = -1;
                        }
                        otherMine.checkReduction(bonus, false);
                    }
                }
            }
            // cycle through a second time to see if any mines at these coords
            // need to be removed
            List<Minefield> mfRemoved = new ArrayList<>();
            Enumeration<Minefield> mines = gameManager.game.getMinefields(c).elements();
            while (mines.hasMoreElements()) {
                Minefield mine = mines.nextElement();
                if (mine.getDensity() < 5) {
                    mfRemoved.add(mine);
                }
            }
            // we have to do it this way to avoid a concurrent error problem
            for (Minefield mf : mfRemoved) {
                gameManager.environmentalEffectManager.removeMinefield(mf, gameManager);
            }
            // update the mines at these coords
            gameManager.communicationManager.sendChangedMines(c, gameManager);
        }
    }

    /**
     * attempt to clear a minefield
     *
     * @param mf     - a <code>Minefield</code> to clear
     * @param en     - <code>entity</code> doing the clearing
     * @param target - <code>int</code> needed to roll for a successful clearance
     * @param vClearReport
     * @param gameManager
     * @return <code>true</code> if clearance successful
     */
    public boolean clearMinefield(Minefield mf, Entity en, int target,
                                  Vector<Report> vClearReport, GameManager gameManager) {
        return gameManager.environmentalEffectManager.clearMinefield(mf, en, target, -1, vClearReport, 2, gameManager);
    }

    public boolean clearMinefield(Minefield mf, Entity en, int target,
                                  int botch, Vector<Report> vClearReport, GameManager gameManager) {
        return gameManager.environmentalEffectManager.clearMinefield(mf, en, target, botch, vClearReport, 1, gameManager);
    }

    /**
     * attempt to clear a minefield We don't actually remove the minefield here,
     * because if this is called up from within a loop, that will cause problems
     *
     * @param mf
     *            - a <code>Minefield</code> to clear
     * @param en
     *            - <code>entity</code> doing the clearing
     * @param target
     *            - <code>int</code> needed to roll for a successful clearance
     * @param botch
     *            - <code>int</code> that indicates an accidental detonation
     * @param vClearReport
     *            - The report collection to report to
     * @param indent
     *            - The number of indents for the report
     * @param gameManager
     * @return <code>true</code> if clearance successful
     */
    public boolean clearMinefield(Minefield mf, Entity en, int target,
                                  int botch, Vector<Report> vClearReport, int indent, GameManager gameManager) {
        Report r;
        Roll diceRoll = Compute.rollD6(2);

        if (diceRoll.getIntValue() >= target) {
            r = new Report(2250);
            r.subject = en.getId();
            r.add(Minefield.getDisplayableName(mf.getType()));
            r.add(target);
            r.add(diceRoll);
            r.indent(indent);
            vClearReport.add(r);
            return true;
        } else if (diceRoll.getIntValue() <= botch) {
            // TODO : detonate the minefield
            r = new Report(2255);
            r.subject = en.getId();
            r.indent(indent);
            r.add(Minefield.getDisplayableName(mf.getType()));
            r.add(target);
            r.add(diceRoll);
            vClearReport.add(r);
            // The detonation damages any units that were also attempting to
            // clear mines in the same hex
            for (Entity victim : gameManager.game.getEntitiesVector(mf.getCoords())) {
                Report rVictim;
                if (victim.isClearingMinefield()) {
                    rVictim = new Report(2265);
                    rVictim.subject = victim.getId();
                    rVictim.add(victim.getShortName(), true);
                    rVictim.indent(indent + 1);
                    vClearReport.add(rVictim);
                    int damage = mf.getDensity();
                    while (damage > 0) {
                        int cur_damage = Math.min(5, damage);
                        damage = damage - cur_damage;
                        HitData hit = victim.rollHitLocation(
                                Minefield.TO_HIT_TABLE, Minefield.TO_HIT_SIDE);
                        vClearReport.addAll(gameManager.damageEntity(victim, hit, cur_damage));
                    }
                }
            }
            // reduction works differently here
            if (mf.getType() == Minefield.TYPE_CONVENTIONAL) {
                mf.setDensity(Math.max(5, mf.getDensity() - 5));
            } else {
                // congratulations, you cleared the mine by blowing yourself up
                return true;
            }
        } else {
            // failure
            r = new Report(2260);
            r.subject = en.getId();
            r.indent(indent);
            r.add(Minefield.getDisplayableName(mf.getType()));
            r.add(target);
            r.add(diceRoll);
            vClearReport.add(r);
        }
        return false;
    }

    /**
     * Clear any detonated mines at these coords
     * @param c
     * @param target
     * @param gameManager
     */
    protected void clearDetonatedMines(Coords c, int target, GameManager gameManager) {
        Enumeration<Minefield> minefields = gameManager.game.getMinefields(c).elements();
        List<Minefield> mfRemoved = new ArrayList<>();
        while (minefields.hasMoreElements()) {
            Minefield minefield = minefields.nextElement();
            if (minefield.hasDetonated() && (Compute.d6(2) >= target)) {
                mfRemoved.add(minefield);
            }
        }
        // we have to do it this way to avoid a concurrent error problem
        for (Minefield mf : mfRemoved) {
            gameManager.environmentalEffectManager.removeMinefield(mf, gameManager);
        }
    }

    /**
     * Removes the minefield from the game.
     *
     * @param mf The <code>Minefield</code> to remove
     * @param gameManager
     */
    public void removeMinefield(Minefield mf, GameManager gameManager) {
        if (gameManager.game.containsVibrabomb(mf)) {
            gameManager.game.removeVibrabomb(mf);
        }
        gameManager.game.removeMinefield(mf);

        Enumeration<Player> players = gameManager.game.getPlayers();
        while (players.hasMoreElements()) {
            Player player = players.nextElement();
            gameManager.environmentalEffectManager.removeMinefield(player, mf, gameManager);
        }
    }

    /**
     * Removes the minefield from a player.
     *  @param player The <code>Player</code> whose minefield should be removed
     * @param mf     The <code>Minefield</code> to be removed
     * @param gameManager
     */
    protected void removeMinefield(Player player, Minefield mf, GameManager gameManager) {
        if (player.containsMinefield(mf)) {
            player.removeMinefield(mf);
            gameManager.communicationManager.send(player.getId(), new Packet(PacketCommand.REMOVE_MINEFIELD, mf));
        }
    }

    /**
     * Reveals a minefield for all players on a team.
     *  @param team The <code>team</code> whose minefield should be revealed
     * @param mf   The <code>Minefield</code> to be revealed
     * @param gameManager
     */
    public void revealMinefield(Team team, Minefield mf, GameManager gameManager) {
        for (Player player : team.players()) {
            if (!player.containsMinefield(mf)) {
                player.addMinefield(mf);
                gameManager.communicationManager.send(player.getId(), new Packet(PacketCommand.REVEAL_MINEFIELD, mf));
            }
        }
    }

    /**
     * Reveals a minefield for a specific player
     * If on a team, does it for the whole team. Otherwise, just the player.
     * @param player
     * @param mf
     * @param gameManager
     */
    public void revealMinefield(Player player, Minefield mf, GameManager gameManager) {
        Team team = gameManager.game.getTeamForPlayer(player);

        if (team != null) {
            revealMinefield(team, mf, gameManager);
        } else {
            if (!player.containsMinefield(mf)) {
                player.addMinefield(mf);
                gameManager.communicationManager.send(player.getId(), new Packet(PacketCommand.REVEAL_MINEFIELD, mf));
            }
        }
    }

    /**
     * Delivers a thunder-aug shot to the targeted hex area. Thunder-Augs are 7
     * hexes, though, so...
     *
     * @param coords
     * @param playerId
     * @param damage
     *            The per-hex density of the incoming minefield; that is, the
     *            final value with any modifiers (such as halving and rounding
     * @param entityId
     * @param gameManager
     */
    public void deliverThunderAugMinefield(Coords coords, int playerId, int damage, int entityId, GameManager gameManager) {
        Coords mfCoord;
        for (int dir = 0; dir < 7; dir++) {
            // May need to reset here for each new hex.
            int hexDamage = damage;
            if (dir == 6) {// The targeted hex.
                mfCoord = coords;
            } else {// The hex in the dir direction from the targeted hex.
                mfCoord = coords.translated(dir);
            }

            // Only if this is on the board...
            if (gameManager.game.getBoard().contains(mfCoord)) {
                Minefield minefield = null;
                Enumeration<Minefield> minefields = gameManager.game.getMinefields(mfCoord).elements();
                // Check if there already are Thunder minefields in the hex.
                while (minefields.hasMoreElements()) {
                    Minefield mf = minefields.nextElement();
                    if (mf.getType() == Minefield.TYPE_CONVENTIONAL) {
                        minefield = mf;
                        break;
                    }
                }

                // Did we find a Thunder minefield in the hex?
                // N.B. damage Thunder minefields equals the number of
                // missiles, divided by two, rounded up.
                if (minefield == null) {
                    // Nope. Create a new Thunder minefield
                    minefield = Minefield.createMinefield(mfCoord, playerId,
                            Minefield.TYPE_CONVENTIONAL, hexDamage);
                    gameManager.game.addMinefield(minefield);
                    gameManager.utilityManager.checkForRevealMinefield(minefield, gameManager.game.getEntity(entityId), gameManager);
                } else if (minefield.getDensity() < Minefield.MAX_DAMAGE) {
                    // Yup. Replace the old one.
                    removeMinefield(minefield, gameManager);
                    hexDamage += minefield.getDensity();

                    // Damage from Thunder minefields are capped.
                    if (hexDamage > Minefield.MAX_DAMAGE) {
                        hexDamage = Minefield.MAX_DAMAGE;
                    }
                    minefield.setDensity(hexDamage);
                    gameManager.game.addMinefield(minefield);
                    gameManager.utilityManager.checkForRevealMinefield(minefield, gameManager.game.getEntity(entityId), gameManager);
                }
            }
        }
    }

    /**
     * Adds a Thunder minefield to the hex.
     *  @param coords   the minefield's coordinates
     * @param playerId the deploying player's id
     * @param damage   the amount of damage the minefield does
     * @param entityId an entity that might spot the minefield
     * @param gameManager
     */
    public void deliverThunderMinefield(Coords coords, int playerId, int damage, int entityId, GameManager gameManager) {
        Minefield minefield = null;
        Enumeration<Minefield> minefields = gameManager.game.getMinefields(coords).elements();
        // Check if there already are Thunder minefields in the hex.
        while (minefields.hasMoreElements()) {
            Minefield mf = minefields.nextElement();
            if (mf.getType() == Minefield.TYPE_CONVENTIONAL) {
                minefield = mf;
                break;
            }
        }

        // Create a new Thunder minefield
        if (minefield == null) {
            minefield = Minefield.createMinefield(coords, playerId, Minefield.TYPE_CONVENTIONAL, damage);
            gameManager.game.addMinefield(minefield);
            gameManager.utilityManager.checkForRevealMinefield(minefield, gameManager.game.getEntity(entityId), gameManager);
        } else if (minefield.getDensity() < Minefield.MAX_DAMAGE) {
            // Add to the old one
            removeMinefield(minefield, gameManager);
            int oldDamage = minefield.getDensity();
            damage += oldDamage;
            damage = Math.min(damage, Minefield.MAX_DAMAGE);
            minefield.setDensity(damage);
            gameManager.game.addMinefield(minefield);
            gameManager.utilityManager.checkForRevealMinefield(minefield, gameManager.game.getEntity(entityId), gameManager);
        }
    }

    /**
     * Adds a Thunder Inferno minefield to the hex.
     *  @param coords   the minefield's coordinates
     * @param playerId the deploying player's id
     * @param damage   the amount of damage the minefield does
     * @param entityId an entity that might spot the minefield
     * @param gameManager
     */
    public void deliverThunderInfernoMinefield(Coords coords, int playerId, int damage, int entityId, GameManager gameManager) {
        Minefield minefield = null;
        Enumeration<Minefield> minefields = gameManager.game.getMinefields(coords).elements();
        // Check if there already are Thunder minefields in the hex.
        while (minefields.hasMoreElements()) {
            Minefield mf = minefields.nextElement();
            if (mf.getType() == Minefield.TYPE_INFERNO) {
                minefield = mf;
                break;
            }
        }

        // Create a new Thunder Inferno minefield
        if (minefield == null) {
            minefield = Minefield.createMinefield(coords, playerId, Minefield.TYPE_INFERNO, damage);
            gameManager.game.addMinefield(minefield);
            gameManager.utilityManager.checkForRevealMinefield(minefield, gameManager.game.getEntity(entityId), gameManager);
        } else if (minefield.getDensity() < Minefield.MAX_DAMAGE) {
            // Add to the old one
            removeMinefield(minefield, gameManager);
            int oldDamage = minefield.getDensity();
            damage += oldDamage;
            damage = Math.min(damage, Minefield.MAX_DAMAGE);
            minefield.setDensity(damage);
            gameManager.game.addMinefield(minefield);
            gameManager.utilityManager.checkForRevealMinefield(minefield, gameManager.game.getEntity(entityId), gameManager);
        }
    }

    /**
     * Delivers an artillery FASCAM shot to the targeted hex area.
     * @param coords
     * @param playerId
     * @param damage
     * @param entityId
     * @param gameManager
     */
    public void deliverFASCAMMinefield(Coords coords, int playerId, int damage, int entityId, GameManager gameManager) {
        // Only if this is on the board...
        if (gameManager.game.getBoard().contains(coords)) {
            Minefield minefield = null;
            Enumeration<Minefield> minefields = gameManager.game.getMinefields(coords).elements();
            // Check if there already are Thunder minefields in the hex.
            while (minefields.hasMoreElements()) {
                Minefield mf = minefields.nextElement();
                if (mf.getType() == Minefield.TYPE_CONVENTIONAL) {
                    minefield = mf;
                    break;
                }
            }
            // Did we find a Thunder minefield in the hex?
            if (minefield == null) {
                minefield = Minefield.createMinefield(coords, playerId,
                        Minefield.TYPE_CONVENTIONAL, damage);
                gameManager.game.addMinefield(minefield);
                gameManager.utilityManager.checkForRevealMinefield(minefield, gameManager.game.getEntity(entityId), gameManager);
            } else if (minefield.getDensity() < Minefield.MAX_DAMAGE) {
                // Add to the old one.
                removeMinefield(minefield, gameManager);
                int oldDamage = minefield.getDensity();
                damage += oldDamage;
                damage = Math.min(damage, Minefield.MAX_DAMAGE);
                minefield.setDensity(damage);
                gameManager.game.addMinefield(minefield);
                gameManager.utilityManager.checkForRevealMinefield(minefield, gameManager.game.getEntity(entityId), gameManager);
            }
        }
    }

    /**
     * Adds a Thunder-Active minefield to the hex.
     * @param coords   the minefield's coordinates
     * @param playerId the deploying player's id
     * @param damage   the amount of damage the minefield does
     * @param entityId an entity that might spot the minefield
     * @param gameManager
     */
    public void deliverThunderActiveMinefield(Coords coords, int playerId, int damage, int entityId, GameManager gameManager) {
        Minefield minefield = null;
        Enumeration<Minefield> minefields = gameManager.game.getMinefields(coords).elements();
        // Check if there already are Thunder minefields in the hex.
        while (minefields.hasMoreElements()) {
            Minefield mf = minefields.nextElement();
            if (mf.getType() == Minefield.TYPE_ACTIVE) {
                minefield = mf;
                break;
            }
        }

        // Create a new Thunder-Active minefield
        if (minefield == null) {
            minefield = Minefield.createMinefield(coords, playerId, Minefield.TYPE_ACTIVE, damage);
            gameManager.game.addMinefield(minefield);
            gameManager.utilityManager.checkForRevealMinefield(minefield, gameManager.game.getEntity(entityId), gameManager);
        } else if (minefield.getDensity() < Minefield.MAX_DAMAGE) {
            // Add to the old one
            removeMinefield(minefield, gameManager);
            int oldDamage = minefield.getDensity();
            damage += oldDamage;
            damage = Math.min(damage, Minefield.MAX_DAMAGE);
            minefield.setDensity(damage);
            gameManager.game.addMinefield(minefield);
            gameManager.utilityManager.checkForRevealMinefield(minefield, gameManager.game.getEntity(entityId), gameManager);
        }
    }

    /**
     * Adds a Thunder-Vibrabomb minefield to the hex.
     * @param coords
     * @param playerId
     * @param damage
     * @param sensitivity
     * @param entityId
     * @param gameManager
     */
    public void deliverThunderVibraMinefield(Coords coords, int playerId,
                                             int damage, int sensitivity, int entityId, GameManager gameManager) {
        Minefield minefield = null;
        Enumeration<Minefield> minefields = gameManager.game.getMinefields(coords).elements();
        // Check if there already are Thunder minefields in the hex.
        while (minefields.hasMoreElements()) {
            Minefield mf = minefields.nextElement();
            if (mf.getType() == Minefield.TYPE_VIBRABOMB) {
                minefield = mf;
                break;
            }
        }

        // Create a new Thunder-Vibra minefield
        if (minefield == null) {
            minefield = Minefield.createMinefield(coords, playerId,
                    Minefield.TYPE_VIBRABOMB, damage, sensitivity);
            gameManager.game.addMinefield(minefield);
            gameManager.game.addVibrabomb(minefield);
            gameManager.utilityManager.checkForRevealMinefield(minefield, gameManager.game.getEntity(entityId), gameManager);
        } else if (minefield.getDensity() < Minefield.MAX_DAMAGE) {
            // Add to the old one
            removeMinefield(minefield, gameManager);
            int oldDamage = minefield.getDensity();
            damage += oldDamage;
            damage = Math.min(damage, Minefield.MAX_DAMAGE);
            minefield.setDensity(damage);
            gameManager.game.addMinefield(minefield);
            gameManager.game.addVibrabomb(minefield);
            gameManager.utilityManager.checkForRevealMinefield(minefield, gameManager.game.getEntity(entityId), gameManager);
        }
    }

    /**
     * Creates an artillery flare of the given radius above the target
     * @param coords
     * @param radius
     * @param gameManager
     */
    public void deliverArtilleryFlare(Coords coords, int radius, GameManager gameManager) {
        Flare flare = new Flare(coords, 5, radius, Flare.F_DRIFTING);
        gameManager.game.addFlare(flare);
    }

    public void deliverMortarFlare(Coords coords, int duration, GameManager gameManager) {
        Flare flare = new Flare(coords, duration, 1, Flare.F_IGNITED);
        gameManager.game.addFlare(flare);
    }

    /**
     * deliver missile smoke
     *
     * @param coords the <code>Coords</code> where to deliver
     * @param smokeType
     * @param vPhaseReport
     * @param gameManager
     */
    public void deliverMissileSmoke(Coords coords, int smokeType, Vector<Report> vPhaseReport, GameManager gameManager) {
        Report r;
        if (smokeType == SmokeCloud.SMOKE_GREEN) {
            r = new Report(5184, Report.PUBLIC);
        } else {
            r = new Report(5183, Report.PUBLIC);
            //Report either light or heavy smoke, as appropriate
            r.choose(smokeType == SmokeCloud.SMOKE_LIGHT);
            r.indent(2);
        }
        r.add(coords.getBoardNum());
        vPhaseReport.add(r);
        gameManager.createSmoke(coords, smokeType, 3);
        Hex hex = gameManager.game.getBoard().getHex(coords);
        hex.addTerrain(new Terrain(Terrains.SMOKE, smokeType));
        gameManager.communicationManager.sendChangedHex(coords, gameManager);
    }

    public void deliverSmokeGrenade(Coords coords, Vector<Report> vPhaseReport, GameManager gameManager) {
        Report r = new Report(5200, Report.PUBLIC);
        r.indent(2);
        r.add(coords.getBoardNum());
        vPhaseReport.add(r);
        gameManager.createSmoke(coords, SmokeCloud.SMOKE_LIGHT, 3);
        Hex hex = gameManager.game.getBoard().getHex(coords);
        hex.addTerrain(new Terrain(Terrains.SMOKE, SmokeCloud.SMOKE_LIGHT));
        gameManager.communicationManager.sendChangedHex(coords, gameManager);
    }

    public void deliverSmokeMortar(Coords coords, Vector<Report> vPhaseReport, int duration, GameManager gameManager) {
        Report r = new Report(5185, Report.PUBLIC);
        r.indent(2);
        r.add(coords.getBoardNum());
        vPhaseReport.add(r);
        gameManager.createSmoke(coords, SmokeCloud.SMOKE_HEAVY, duration);
        Hex hex = gameManager.game.getBoard().getHex(coords);
        hex.addTerrain(new Terrain(Terrains.SMOKE, SmokeCloud.SMOKE_HEAVY));
        gameManager.communicationManager.sendChangedHex(coords, gameManager);
    }

    public void deliverChaffGrenade(Coords coords, Vector<Report> vPhaseReport, GameManager gameManager) {
        Report r = new Report(5187, Report.PUBLIC);
        r.indent(2);
        r.add(coords.getBoardNum());
        vPhaseReport.add(r);
        gameManager.createSmoke(coords, SmokeCloud.SMOKE_CHAFF_LIGHT, 1);
        Hex hex = gameManager.game.getBoard().getHex(coords);
        hex.addTerrain(new Terrain(Terrains.SMOKE, SmokeCloud.SMOKE_CHAFF_LIGHT));
        gameManager.communicationManager.sendChangedHex(coords, gameManager);
    }

    /**
     * deliver artillery smoke
     *
     * @param coords the <code>Coords</code> where to deliver
     * @param vPhaseReport
     * @param gameManager
     */
    public void deliverArtillerySmoke(Coords coords, Vector<Report> vPhaseReport, GameManager gameManager) {
        Report r = new Report(5185, Report.PUBLIC);
        r.indent(2);
        r.add(coords.getBoardNum());
        vPhaseReport.add(r);
        gameManager.createSmoke(coords, SmokeCloud.SMOKE_HEAVY, 3);
        Hex hex = gameManager.game.getBoard().getHex(coords);
        if (hex != null) {
            hex.addTerrain(new Terrain(Terrains.SMOKE, SmokeCloud.SMOKE_HEAVY));
            gameManager.communicationManager.sendChangedHex(coords, gameManager);
            for (int dir = 0; dir <= 5; dir++) {
                Coords tempcoords = coords.translated(dir);
                if (!gameManager.game.getBoard().contains(tempcoords)) {
                    continue;
                }
                if (coords.equals(tempcoords)) {
                    continue;
                }
                r = new Report(5185, Report.PUBLIC);
                r.indent(2);
                r.add(tempcoords.getBoardNum());
                vPhaseReport.add(r);
                gameManager.createSmoke(tempcoords, SmokeCloud.SMOKE_HEAVY, 3);
                hex = gameManager.game.getBoard().getHex(tempcoords);
                hex.addTerrain(new Terrain(Terrains.SMOKE, SmokeCloud.SMOKE_HEAVY));
                gameManager.communicationManager.sendChangedHex(tempcoords, gameManager);
            }
        }
    }

    /**
     * deliver LASER inhibiting smoke
     *
     * @param coords the <code>Coords</code> where to deliver
     * @param vPhaseReport
     * @param gameManager
     */
    public void deliverLIsmoke(Coords coords, Vector<Report> vPhaseReport, GameManager gameManager) {
        Report r = new Report(5186, Report.PUBLIC);
        r.indent(2);
        r.add(coords.getBoardNum());
        vPhaseReport.add(r);
        gameManager.createSmoke(coords, SmokeCloud.SMOKE_LI_HEAVY, 2);
        Hex hex = gameManager.game.getBoard().getHex(coords);
        if (null != hex) {
            hex.addTerrain(new Terrain(Terrains.SMOKE, SmokeCloud.SMOKE_LI_HEAVY));
            gameManager.communicationManager.sendChangedHex(coords, gameManager);
            for (int dir = 0; dir <= 5; dir++) {
                Coords tempcoords = coords.translated(dir);
                if (!gameManager.game.getBoard().contains(tempcoords)) {
                    continue;
                }
                if (coords.equals(tempcoords)) {
                    continue;
                }
                r = new Report(5186, Report.PUBLIC);
                r.indent(2);
                r.add(tempcoords.getBoardNum());
                vPhaseReport.add(r);
                gameManager.createSmoke(tempcoords, SmokeCloud.SMOKE_LI_HEAVY, 2);
                hex = gameManager.game.getBoard().getHex(tempcoords);
                hex.addTerrain(new Terrain(Terrains.SMOKE, SmokeCloud.SMOKE_LI_HEAVY));
                gameManager.communicationManager.sendChangedHex(tempcoords, gameManager);
            }
        }
    }

    /**
     * deliver artillery inferno
     *  @param coords    the <code>Coords</code> where to deliver
     * @param ae        the attacking <code>entity</code>
     * @param subjectId the <code>int</code> id of the target
     * @param vPhaseReport
     * @param gameManager
     */
    public void deliverArtilleryInferno(Coords coords, Entity ae,
                                        int subjectId, Vector<Report> vPhaseReport, GameManager gameManager) {
        Hex h = gameManager.game.getBoard().getHex(coords);
        Report r;
        if (null != h) {
            // Unless there is a fire in the hex already, start one.
            if (h.terrainLevel(Terrains.FIRE) < Terrains.FIRE_LVL_INFERNO_IV) {
                gameManager.ignite(coords, Terrains.FIRE_LVL_INFERNO_IV, vPhaseReport);
            }
            // possibly melt ice and snow
            if (h.containsTerrain(Terrains.ICE) || h.containsTerrain(Terrains.SNOW)) {
                vPhaseReport.addAll(gameManager.meltIceAndSnow(coords, subjectId));
            }
        }
        for (Entity entity : gameManager.game.getEntitiesVector(coords)) {
            // TacOps, p. 356 - treat as if hit by 5 inferno missiles
            r = new Report(6695);
            r.indent(3);
            r.add(entity.getDisplayName());
            r.subject = entity.getId();
            r.newlines = 0;
            vPhaseReport.add(r);
            if (entity instanceof Tank) {
                Report.addNewline(vPhaseReport);
            }
            Vector<Report> vDamageReport = gameManager.environmentalEffectManager.deliverInfernoMissiles(ae, entity, 5, true, gameManager);
            Report.indentAll(vDamageReport, 2);
            vPhaseReport.addAll(vDamageReport);
        }
        for (int dir = 0; dir <= 5; dir++) {
            Coords tempcoords = coords.translated(dir);
            if (!gameManager.game.getBoard().contains(tempcoords)) {
                continue;
            }
            if (coords.equals(tempcoords)) {
                continue;
            }
            h = gameManager.game.getBoard().getHex(tempcoords);
            if (null != h) {
                // Unless there is a fire in the hex already, start one.
                if (h.terrainLevel(Terrains.FIRE) < Terrains.FIRE_LVL_INFERNO_IV) {
                    gameManager.ignite(tempcoords, Terrains.FIRE_LVL_INFERNO_IV, vPhaseReport);
                }
                // possibly melt ice and snow
                if (h.containsTerrain(Terrains.ICE) || h.containsTerrain(Terrains.SNOW)) {
                    vPhaseReport.addAll(gameManager.meltIceAndSnow(tempcoords, subjectId));
                }
            }
            for (Entity entity : gameManager.game.getEntitiesVector(tempcoords)) {
                r = new Report(6695);
                r.indent(3);
                r.add(entity.getDisplayName());
                r.newlines = 0;
                r.subject = entity.getId();
                vPhaseReport.add(r);
                if (entity instanceof Tank) {
                    Report.addNewline(vPhaseReport);
                }
                Vector<Report> vDamageReport = gameManager.environmentalEffectManager.deliverInfernoMissiles(ae,
                        entity, 5, true, gameManager);
                Report.indentAll(vDamageReport, 2);
                vPhaseReport.addAll(vDamageReport);
            }
        }
    }

    public void deliverScreen(Coords coords, Vector<Report> vPhaseReport, GameManager gameManager) {
        Hex h = gameManager.game.getBoard().getHex(coords);
        Report r;
        Report.addNewline(vPhaseReport);
        r = new Report(9070, Report.PUBLIC);
        r.indent(2);
        r.add(coords.getBoardNum());
        vPhaseReport.add(r);
        // use level to count the number of screens (since level does not matter
        // in space)
        int nscreens = h.terrainLevel(Terrains.SCREEN);
        if (nscreens > 0) {
            h.removeTerrain(Terrains.SCREEN);
            h.addTerrain(new Terrain(Terrains.SCREEN, nscreens + 1));
        } else {
            h.addTerrain(new Terrain(Terrains.SCREEN, 1));
        }
        gameManager.communicationManager.sendChangedHex(coords, gameManager);
    }

    /**
     * deploys a new telemissile entity onto the map
     * @param ae
     * @param wtype
     * @param atype
     * @param wId
     * @param capMisMod
     * @param damage
     * @param armor
     * @param vPhaseReport
     * @param gameManager
     */
    public void deployTeleMissile(Entity ae, WeaponType wtype, AmmoType atype, int wId,
                                  int capMisMod, int damage, int armor, Vector<Report> vPhaseReport, GameManager gameManager) {
        Report r = new Report(9080);
        r.subject = ae.getId();
        r.addDesc(ae);
        r.indent(2);
        r.newlines = 0;
        r.add(wtype.getName());
        vPhaseReport.add(r);
        TeleMissile tele = new TeleMissile(ae, damage, armor,
                atype.getTonnage(ae), atype.getAmmoType(), capMisMod);
        tele.setDeployed(true);
        tele.setId(gameManager.game.getNextEntityId());
        if (ae instanceof Aero) {
            Aero a = (Aero) ae;
            tele.setCurrentVelocity(a.getCurrentVelocity());
            tele.setNextVelocity(a.getNextVelocity());
            tele.setVectors(a.getVectors());
            tele.setFacing(a.getFacing());
        }
        // set velocity and heading the same as parent entity
        gameManager.game.addEntity(tele);
        gameManager.communicationManager.send(gameManager.packetManager.createAddEntityPacket(tele.getId(), gameManager));
        // make him not get a move this turn
        tele.setDone(true);
        // place on board
        tele.setPosition(ae.getPosition());
        // Update the entity
        gameManager.entityUpdate(tele.getId());
        // check to see if the launching of this missile removes control of any
        // prior missiles
        if (ae.getTMTracker().containsLauncher(wId)) {
            Entity priorMissile = gameManager.game.getEntity(ae.getTMTracker().getMissile(wId));
            if (priorMissile instanceof TeleMissile) {
                ((TeleMissile) priorMissile).setOutContact(true);
                // remove this from the tracker for good measure
                ae.getTMTracker().removeMissile(wId);
            }
        }
        // track this missile on the entity
        ae.getTMTracker().addMissile(wId, tele.getId());
    }

    /**
     * deliver inferno missiles
     *  @param ae       the <code>Entity</code> that fired the missiles
     * @param t        the <code>Targetable</code> that is the target
     * @param missiles the <code>int</code> amount of missiles
     * @param gameManager
     */
    public Vector<Report> deliverInfernoMissiles(Entity ae, Targetable t, int missiles, GameManager gameManager) {
        return gameManager.environmentalEffectManager.deliverInfernoMissiles(ae, t, missiles, CalledShot.CALLED_NONE, gameManager);
    }

    /**
     * deliver inferno missiles
     *  @param ae       the <code>Entity</code> that fired the missiles
     * @param t        the <code>Targetable</code> that is the target
     * @param missiles the <code>int</code> amount of missiles
     * @param areaEffect a <code>boolean</code> indicating whether the attack is from an
     *                   area effect weapon such as Arrow IV inferno, and partial cover should
     * @param gameManager
     */
    public Vector<Report> deliverInfernoMissiles(Entity ae, Targetable t, int missiles,
                                                 boolean areaEffect, GameManager gameManager) {
        return gameManager.environmentalEffectManager.deliverInfernoMissiles(ae, t, missiles, CalledShot.CALLED_NONE, areaEffect, gameManager);
    }

    /**
     * deliver inferno missiles
     *  @param ae       the <code>Entity</code> that fired the missiles
     * @param t        the <code>Targetable</code> that is the target
     * @param missiles the <code>int</code> amount of missiles
     * @param called   an <code>int</code> indicated the aiming mode used to fire the
     * @param gameManager
     */
    public Vector<Report> deliverInfernoMissiles(Entity ae, Targetable t, int missiles,
                                                 int called, GameManager gameManager) {
        return gameManager.environmentalEffectManager.deliverInfernoMissiles(ae, t, missiles, called, false, gameManager);
    }

    /**
     * deliver inferno missiles
     *  @param ae         the <code>Entity</code> that fired the missiles
     * @param t          the <code>Targetable</code> that is the target
     * @param missiles   the <code>int</code> amount of missiles
     * @param called     an <code>int</code> indicated the aiming mode used to fire the
     *                   inferno missiles (for called shots)
     * @param areaEffect a <code>boolean</code> indicating whether the attack is from an
     *                   area effect weapon such as Arrow IV inferno, and partial cover should
     * @param gameManager
     */
    public Vector<Report> deliverInfernoMissiles(Entity ae, Targetable t, int missiles, int called,
                                                 boolean areaEffect, GameManager gameManager) {
        Hex hex = gameManager.game.getBoard().getHex(t.getPosition());
        Report r;
        Vector<Report> vPhaseReport = new Vector<>();
        int attId = Entity.NONE;
        if (null != ae) {
            attId = ae.getId();
        }
        switch (t.getTargetType()) {
            case Targetable.TYPE_HEX_ARTILLERY:
                // used for BA inferno explosion
                for (Entity e : gameManager.game.getEntitiesVector(t.getPosition())) {
                    if (e.getElevation() > hex.terrainLevel(Terrains.BLDG_ELEV)) {
                        r = new Report(6685);
                        r.subject = e.getId();
                        r.addDesc(e);
                        vPhaseReport.add(r);
                        vPhaseReport.addAll(deliverInfernoMissiles(ae, e, missiles, called, gameManager));
                    } else {
                        Roll diceRoll = Compute.rollD6(1);
                        r = new Report(3570);
                        r.subject = e.getId();
                        r.addDesc(e);
                        r.add(diceRoll);
                        vPhaseReport.add(r);

                        if (diceRoll.getIntValue() >= 5) {
                            vPhaseReport.addAll(deliverInfernoMissiles(ae, e, missiles, called, gameManager));
                        }
                    }
                }
                if (gameManager.game.getBoard().getBuildingAt(t.getPosition()) != null) {
                    Vector<Report> vBuildingReport = gameManager.damageBuilding(gameManager.game.getBoard().getBuildingAt(t.getPosition()),
                            2 * missiles, t.getPosition());
                    for (Report report : vBuildingReport) {
                        report.subject = attId;
                    }
                    vPhaseReport.addAll(vBuildingReport);
                }
                // fall through
            case Targetable.TYPE_HEX_CLEAR:
            case Targetable.TYPE_HEX_IGNITE:
                // Report that damage applied to terrain, if there's TF to damage
                Hex h = gameManager.game.getBoard().getHex(t.getPosition());
                if ((h != null) && h.hasTerrainFactor()) {
                    r = new Report(3384);
                    r.indent(2);
                    r.subject = attId;
                    r.add(t.getPosition().getBoardNum());
                    r.add(missiles * 4);
                    vPhaseReport.addElement(r);
                }
                vPhaseReport.addAll(gameManager.environmentalEffectManager.tryClearHex(t.getPosition(), missiles * 4, attId, gameManager));
                gameManager.environmentalEffectManager.tryIgniteHex(t.getPosition(), attId, false, true,
                        new TargetRoll(0, "inferno"), -1, vPhaseReport, gameManager);
                break;
            case Targetable.TYPE_BLDG_IGNITE:
            case Targetable.TYPE_BUILDING:
                Vector<Report> vBuildingReport = gameManager.damageBuilding(gameManager.game.getBoard().getBuildingAt(t.getPosition()),
                        2 * missiles, t.getPosition());
                for (Report report : vBuildingReport) {
                    report.subject = attId;
                }
                vPhaseReport.addAll(vBuildingReport);

                // For each missile, check to see if it hits a unit in this hex
                for (Entity e : gameManager.game.getEntitiesVector(t.getPosition())) {
                    if (e.getElevation() > hex.terrainLevel(Terrains.BLDG_ELEV)) {
                        continue;
                    }
                    for (int m = 0; m < missiles; m++) {
                        Roll diceRoll = Compute.rollD6(1);
                        r = new Report(3570);
                        r.subject = e.getId();
                        r.indent(3);
                        r.addDesc(e);
                        r.add(diceRoll);
                        vPhaseReport.add(r);

                        if (diceRoll.getIntValue() >= 5) {
                            Vector<Report> dmgReports = deliverInfernoMissiles(ae, e, 1, called, gameManager);
                            for (Report rep : dmgReports) {
                                rep.indent(4);
                            }
                            vPhaseReport.addAll(dmgReports);
                        }
                    }
                }

                break;
            case Targetable.TYPE_ENTITY:
                Entity te = (Entity) t;
                if ((te instanceof Mech) && (!areaEffect)) {
                    // Bug #1585497: Check for partial cover
                    int m = missiles;
                    LosEffects le = LosEffects.calculateLOS(gameManager.game, ae, t);
                    int cover = le.getTargetCover();
                    Vector<Report> coverDamageReports = new Vector<>();
                    int heatDamage = 0;
                    boolean heatReduced = false;
                    String reductionCause = "";
                    for (int i = 0; i < m; i++) {
                        int side = Compute.targetSideTable(ae, t, called);
                        HitData hit = te.rollHitLocation(ToHitData.HIT_NORMAL, side);
                        if (te.removePartialCoverHits(hit.getLocation(), cover, side)) {
                            missiles--;
                            // Determine if damageable cover is hit
                            int damageableCoverType;
                            Entity coverDropship;
                            Coords coverLoc;

                            // Determine if there is primary and secondary
                            // cover,
                            // and then determine which one gets hit
                            if (((cover == LosEffects.COVER_75RIGHT) || (cover == LosEffects.COVER_75LEFT))
                                    // 75% cover has a primary and secondary
                                    || ((cover == LosEffects.COVER_HORIZONTAL)
                                    && (le.getDamagableCoverTypeSecondary() != LosEffects.DAMAGABLE_COVER_NONE))) {
                                // Horizontal cover provided by two 25%'s,
                                // so primary and secondary
                                int hitLoc = hit.getLocation();
                                // Primary stores the left side, from the
                                // perspective of the attacker
                                if ((hitLoc == Mech.LOC_RLEG) || (hitLoc == Mech.LOC_RT)
                                        || (hitLoc == Mech.LOC_RARM)) {
                                    // Left side is primary
                                    damageableCoverType = le.getDamagableCoverTypePrimary();
                                    coverDropship = le.getCoverDropshipPrimary();
                                    coverLoc = le.getCoverLocPrimary();
                                } else {
                                    // If not left side, then right side,
                                    // which is secondary
                                    damageableCoverType = le.getDamagableCoverTypeSecondary();
                                    coverDropship = le.getCoverDropshipSecondary();
                                    coverLoc = le.getCoverLocSecondary();
                                }
                            } else { // Only primary cover exists
                                damageableCoverType = le.getDamagableCoverTypePrimary();
                                coverDropship = le.getCoverDropshipPrimary();
                                coverLoc = le.getCoverLocPrimary();
                            }

                            // Check if we need to damage the cover that
                            // absorbed
                            // the hit.
                            Vector<Report> coverDamageReport = new Vector<>();
                            if (damageableCoverType == LosEffects.DAMAGABLE_COVER_DROPSHIP) {
                                r = new Report(3465);
                                r.addDesc(coverDropship);
                                r.indent(1);
                                coverDamageReport = deliverInfernoMissiles(ae,
                                        coverDropship, 1, CalledShot.CALLED_NONE, gameManager);
                                coverDamageReport.insertElementAt(r, 0);
                                for (Report report : coverDamageReport) {
                                    report.indent(1);
                                }
                            } else if (damageableCoverType == LosEffects.DAMAGABLE_COVER_BUILDING) {
                                BuildingTarget bldgTrgt = new BuildingTarget(coverLoc,
                                        gameManager.game.getBoard(), false);
                                coverDamageReport = deliverInfernoMissiles(ae, bldgTrgt, 1,
                                        CalledShot.CALLED_NONE, gameManager);
                            }
                            for (Report report : coverDamageReport) {
                                report.indent(1);
                            }
                            coverDamageReports.addAll(coverDamageReport);
                        } else { // No partial cover, missile hits
                            if ((te.getArmor(hit) > 0)
                                    && (te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_HEAT_DISSIPATING)) {
                                heatDamage += 1;
                                heatReduced = true;
                                reductionCause = ArmorType.forEntity(te, hit.getLocation()).getName();
                            } else {
                                heatDamage += 2;
                            }
                        }
                    }
                    if (heatReduced) {
                        r = new Report(3406);
                        r.add(heatDamage);
                        r.subject = te.getId();
                        r.indent(2);
                        r.choose(true);
                        r.add(missiles * 2);
                        r.add(reductionCause);
                    } else {
                        r = new Report(3400);
                        r.add(heatDamage);
                        r.subject = te.getId();
                        r.indent(2);
                        r.choose(true);
                    }
                    vPhaseReport.add(r);
                    Report.addNewline(vPhaseReport);
                    te.heatFromExternal += heatDamage;

                    if (missiles != m) {
                        r = new Report(3403);
                        r.add(m - missiles);
                        r.indent(2);
                        r.subject = te.getId();
                        vPhaseReport.add(r);
                    }
                    vPhaseReport.addAll(coverDamageReports);
                    Report.addNewline(vPhaseReport);
                } else if (te.tracksHeat()) {
                    // ASFs and small craft
                    r = new Report(3400);
                    r.add(2 * missiles);
                    r.subject = te.getId();
                    r.indent(2);
                    r.choose(true);
                    vPhaseReport.add(r);
                    te.heatFromExternal += 2 * missiles;
                    Report.addNewline(vPhaseReport);
                } else if (te instanceof GunEmplacement) {
                    int direction = Compute.targetSideTable(ae, te, called);
                    while (missiles-- > 0) {
                        HitData hit = te.rollHitLocation(ToHitData.HIT_NORMAL, direction);
                        vPhaseReport.addAll(gameManager.damageEntity(te, hit, 2));
                    }
                } else if ((te instanceof Tank) || te.isSupportVehicle()) {
                    int direction = Compute.targetSideTable(ae, te, called);
                    while (missiles-- > 0) {
                        HitData hit = te.rollHitLocation(ToHitData.HIT_NORMAL, direction);
                        int critRollMod = 0;
                        if (!te.isSupportVehicle() || (te.hasArmoredChassis()
                                && (te.getBARRating(hit.getLocation()) > 9))) {
                            critRollMod -= 2;
                        }
                        if ((te.getArmorType(hit.getLocation()) == EquipmentType.T_ARMOR_HARDENED)
                                && (te.getArmor(hit.getLocation()) > 0)) {
                            critRollMod -= 2;
                        }
                        vPhaseReport.addAll(gameManager.criticalEntity(te, hit.getLocation(), hit.isRear(),
                                critRollMod, 0, DamageType.INFERNO));
                    }
                } else if (te instanceof ConvFighter) {
                    // CFs take a point SI damage for every three missiles that hit.
                    // Use the heatFromExternal field to carry the remainder in case of multiple inferno hits.
                    te.heatFromExternal += missiles;
                    if (te.heatFromExternal >= 3) {
                        int siDamage = te.heatFromExternal / 3;
                        te.heatFromExternal %= 3;
                        final ConvFighter ftr = (ConvFighter) te;
                        int remaining = Math.max(0,  ftr.getSI() - siDamage);
                        r = new Report(9146);
                        r.subject = te.getId();
                        r.indent(2);
                        r.add(siDamage);
                        r.add(remaining);
                        vPhaseReport.add(r);
                        ftr.setSI(remaining);
                        te.damageThisPhase += siDamage;
                        if (remaining <= 0) {
                            // Lets auto-eject if we can!
                            if (ftr.isAutoEject()
                                    && (!gameManager.game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                    || (gameManager.game.getOptions().booleanOption(OptionsConstants.RPG_CONDITIONAL_EJECTION)
                                    && ftr.isCondEjectSIDest()))) {
                                vPhaseReport.addAll(gameManager.ejectEntity(te, true, false));
                            }
                            vPhaseReport.addAll(gameManager.entityActionManager.destroyEntity(te,"Structural Integrity Collapse", gameManager));
                            ftr.setSI(0);
                            if (null != ae) {
                                gameManager.creditKill(te, ae);
                            }
                        }
                    }
                } else if (te.isLargeCraft()) {
                    // Large craft ignore infernos
                    r = new Report(1242);
                    r.subject = te.getId();
                    r.indent(2);
                    vPhaseReport.add(r);
                } else if (te instanceof Protomech) {
                    te.heatFromExternal += missiles;
                    while (te.heatFromExternal >= 3) {
                        te.heatFromExternal -= 3;
                        HitData hit = te.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                        if (hit.getLocation() == Protomech.LOC_NMISS) {
                            Protomech proto = (Protomech) te;
                            r = new Report(6035);
                            r.subject = te.getId();
                            r.indent(2);
                            if (proto.isGlider()) {
                                r.messageId = 6036;
                                proto.setWingHits(proto.getWingHits() + 1);
                            }
                            vPhaseReport.add(r);
                        } else {
                            r = new Report(6690);
                            r.subject = te.getId();
                            r.indent(2);
                            r.add(te.getLocationName(hit));
                            vPhaseReport.add(r);
                            te.destroyLocation(hit.getLocation());
                            // Handle ProtoMech pilot damage
                            // due to location destruction
                            int hits = Protomech.POSSIBLE_PILOT_DAMAGE[hit.getLocation()]
                                    - ((Protomech) te).getPilotDamageTaken(hit.getLocation());
                            if (hits > 0) {
                                vPhaseReport.addAll(gameManager.damageCrew(te, hits));
                                ((Protomech) te).setPilotDamageTaken(hit.getLocation(),
                                        Protomech.POSSIBLE_PILOT_DAMAGE[hit.getLocation()]);
                            }
                            if (te.getTransferLocation(hit).getLocation() == Entity.LOC_DESTROYED) {
                                vPhaseReport.addAll(gameManager.entityActionManager.destroyEntity(te,
                                        "flaming inferno death", false, true, gameManager));
                                Report.addNewline(vPhaseReport);
                            }
                        }
                    }
                } else if (te instanceof BattleArmor) {
                    if (((BattleArmor) te).isFireResistant()) {
                        r = new Report(3395);
                        r.indent(2);
                        r.subject = te.getId();
                        r.addDesc(te);
                        vPhaseReport.add(r);
                        return vPhaseReport;
                    }
                    te.heatFromExternal += missiles;
                    while (te.heatFromExternal >= 3) {
                        te.heatFromExternal -= 3;
                        HitData hit = te.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_FRONT);
                        hit.setEffect(HitData.EFFECT_CRITICAL);
                        vPhaseReport.addAll(gameManager.damageEntity(te, hit, 1));
                        Report.addNewline(vPhaseReport);
                    }
                } else if (te instanceof Infantry) {
                    HitData hit = new HitData(Infantry.LOC_INFANTRY);
                    if (te.getInternal(hit) > (3 * missiles)) {
                        // internal structure absorbs all damage
                        te.setInternal(te.getInternal(hit) - (3 * missiles), hit);
                        r = new Report(6065);
                        r.addDesc(te);
                        r.add(3 * missiles);
                        r.indent(2);
                        r.add(te.getLocationAbbr(hit));
                        r.newlines = 0;
                        r.subject = te.getId();
                        vPhaseReport.add(r);
                        Report.addNewline(vPhaseReport);
                        r = new Report(6095);
                        r.add(te.getInternal(hit));
                        r.subject = te.getId();
                        r.indent(3);
                        vPhaseReport.add(r);
                    } else {
                        vPhaseReport.addAll(gameManager.entityActionManager.destroyEntity(te, "damage", false, gameManager));
                        gameManager.creditKill(te, ae);
                        Report.addNewline(vPhaseReport);
                    }
                }
        }
        return vPhaseReport;
    }

    /**
     * Try to ignite the hex, taking into account existing fires and the
     * effects of Inferno rounds.
     *  @param c
     *            - the <code>Coords</code> of the hex being lit.
     * @param entityId
     *            - the <code>int</code> id of the entity involved.
     * @param bHotGun
     *            - <code>true</code> if the weapon is plasma/flamer/incendiary
     *            LRM/etc
     * @param bInferno
     *            - <code>true</code> if the weapon igniting the hex is an
     *            Inferno round. If some other weapon or ammo is causing the
     *            roll, this should be <code>false</code>.
     * @param nTargetRoll
     *            - the <code>TargetRoll</code> for the ignition roll.
     * @param bReportAttempt
     *            - <code>true</code> if the attempt roll should be added to the
     *            report.
     * @param accidentTarget
     *            - <code>int</code> the target number below which a roll has to
     *            be made in order to try igniting a hex accidentally. -1 for
     * @param vPhaseReport
     * @param gameManager
     */
    public boolean tryIgniteHex(Coords c, int entityId, boolean bHotGun,
                                boolean bInferno, TargetRoll nTargetRoll, boolean bReportAttempt,
                                int accidentTarget, Vector<Report> vPhaseReport, GameManager gameManager) {

        Hex hex = gameManager.game.getBoard().getHex(c);
        Report r;

        // Ignore bad coordinates.
        if (hex == null) {
            return false;
        }

        // Ignore if fire is not enabled as a game option
        if (!gameManager.game.getOptions().booleanOption(OptionsConstants.ADVCOMBAT_TACOPS_START_FIRE)) {
            return false;
        }

        // is the hex ignitable (how are infernos handled?)
        if (!hex.isIgnitable()) {
            return false;
        }

        // first for accidental ignitions, make the necessary roll
        if (accidentTarget > -1) {
            // if this hex is in snow, then accidental ignitions are not
            // possible
            if (hex.containsTerrain(Terrains.SNOW)) {
                return false;
            }
            nTargetRoll.addModifier(2, "accidental");
            Roll diceRoll = Compute.rollD6(2);
            r = new Report(3066);
            r.subject = entityId;
            r.add(accidentTarget);
            r.add(diceRoll);
            r.indent(2);

            if (diceRoll.getIntValue() > accidentTarget) {
                r.choose(false);
                vPhaseReport.add(r);
                return false;
            }
            r.choose(true);
            vPhaseReport.add(r);
        }

        int terrainMod = hex.getIgnitionModifier();
        if (terrainMod != 0) {
            nTargetRoll.addModifier(terrainMod, "terrain");
        }

        // building modifiers
        Building bldg = gameManager.game.getBoard().getBuildingAt(c);
        if (null != bldg) {
            nTargetRoll.addModifier(bldg.getType() - 3, "building");
        }

        // add in any modifiers for planetary conditions
        int weatherMod = gameManager.game.getPlanetaryConditions().getIgniteModifiers();
        if (weatherMod != 0) {
            nTargetRoll.addModifier(weatherMod, "conditions");
        }

        // if there is snow on the ground and this a hotgun or inferno, it may
        // melt the snow instead
        if ((hex.containsTerrain(Terrains.SNOW) || hex
                .containsTerrain(Terrains.ICE)
                || hex.containsTerrain(Terrains.BLACK_ICE)) && (bHotGun || bInferno)) {
            boolean melted = false;
            int meltCheck = Compute.d6(2);
            if ((hex.terrainLevel(Terrains.SNOW) > 1) && (meltCheck == 12)) {
                melted = true;
            } else if ((hex.containsTerrain(Terrains.ICE)
            || hex.containsTerrain(Terrains.BLACK_ICE)) && (meltCheck > 9)) {
                melted = true;
            } else if (hex.containsTerrain(Terrains.SNOW) && (meltCheck > 7)) {
                melted = true;
            }
            if (bInferno) {
                melted = true;
            }
            if (melted) {
                vPhaseReport.addAll(gameManager.meltIceAndSnow(c, entityId));
                return false;
            }

        }

        // inferno always ignites
        // ERRATA not if targeting clear hexes for ignition is disabled.
        if (bInferno && !gameManager.game.getOptions().booleanOption(OptionsConstants.ADVANCED_NO_IGNITE_CLEAR)) {
            nTargetRoll = new TargetRoll(0, "inferno");
        }

        // no lighting fires in tornadoes
        if (gameManager.game.getPlanetaryConditions().getWindStrength() > PlanetaryConditions.WI_STORM) {
            nTargetRoll = new TargetRoll(TargetRoll.AUTOMATIC_FAIL, "tornado");
        }

        // The hex may already be on fire.
        if (hex.containsTerrain(Terrains.FIRE)) {
            if (bReportAttempt) {
                r = new Report(3065);
                r.indent(2);
                r.subject = entityId;
                vPhaseReport.add(r);
            }
        } else if (gameManager.checkIgnition(c, nTargetRoll, bInferno, entityId,
                vPhaseReport)) {
            return true;
        }
        return false;
    }

    public Vector<Report> tryClearHex(Coords c, int nDamage, int entityId, GameManager gameManager) {
        Vector<Report> vPhaseReport = new Vector<>();
        Hex h = gameManager.game.getBoard().getHex(c);
        if (h == null) {
            return vPhaseReport;
        }
        Terrain woods = h.getTerrain(Terrains.WOODS);
        Terrain jungle = h.getTerrain(Terrains.JUNGLE);
        Terrain ice = h.getTerrain(Terrains.ICE);
        Terrain magma = h.getTerrain(Terrains.MAGMA);
        Report r;
        int reportType = Report.HIDDEN;
        if (entityId == Entity.NONE) {
            reportType = Report.PUBLIC;
        }
        if (woods != null) {
            int tf = woods.getTerrainFactor() - nDamage;
            int level = woods.getLevel();
            int folEl = h.terrainLevel(Terrains.FOLIAGE_ELEV);
            if (tf <= 0) {
                h.removeTerrain(Terrains.WOODS);
                h.removeTerrain(Terrains.FOLIAGE_ELEV);
                h.addTerrain(new Terrain(Terrains.ROUGH, 1));
                // light converted to rough
                r = new Report(3090, reportType);
                r.subject = entityId;
                vPhaseReport.add(r);
            } else if ((tf <= 50) && (level > 1)) {
                h.removeTerrain(Terrains.WOODS);
                h.addTerrain(new Terrain(Terrains.WOODS, 1));
                if (folEl != 1) {
                    h.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
                }
                woods = h.getTerrain(Terrains.WOODS);
                // heavy converted to light
                r = new Report(3085, reportType);
                r.subject = entityId;
                vPhaseReport.add(r);
            } else if ((tf <= 90) && (level > 2)) {
                h.removeTerrain(Terrains.WOODS);
                h.addTerrain(new Terrain(Terrains.WOODS, 2));
                if (folEl != 1) {
                    h.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
                }
                woods = h.getTerrain(Terrains.WOODS);
                // ultra heavy converted to heavy
                r = new Report(3082, reportType);
                r.subject = entityId;
                vPhaseReport.add(r);
            }
            woods.setTerrainFactor(tf);
        }
        if (jungle != null) {
            int tf = jungle.getTerrainFactor() - nDamage;
            int level = jungle.getLevel();
            int folEl = h.terrainLevel(Terrains.FOLIAGE_ELEV);
            if (tf < 0) {
                h.removeTerrain(Terrains.JUNGLE);
                h.removeTerrain(Terrains.FOLIAGE_ELEV);
                h.addTerrain(new Terrain(Terrains.ROUGH, 1));
                // light converted to rough
                r = new Report(3091, reportType);
                r.subject = entityId;
                vPhaseReport.add(r);
            } else if ((tf <= 50) && (level > 1)) {
                h.removeTerrain(Terrains.JUNGLE);
                h.addTerrain(new Terrain(Terrains.JUNGLE, 1));
                if (folEl != 1) {
                    h.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
                }
                jungle = h.getTerrain(Terrains.JUNGLE);
                // heavy converted to light
                r = new Report(3086, reportType);
                r.subject = entityId;
                vPhaseReport.add(r);
            } else if ((tf <= 90) && (level > 2)) {
                h.removeTerrain(Terrains.JUNGLE);
                h.addTerrain(new Terrain(Terrains.JUNGLE, 2));
                if (folEl != 1) {
                    h.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
                }
                jungle = h.getTerrain(Terrains.JUNGLE);
                // ultra heavy converted to heavy
                r = new Report(3083, reportType);
                r.subject = entityId;
                vPhaseReport.add(r);
            }
            jungle.setTerrainFactor(tf);
        }
        if (ice != null) {
            int tf = ice.getTerrainFactor() - nDamage;
            if (tf <= 0) {
                // ice melted
                r = new Report(3092, reportType);
                r.subject = entityId;
                vPhaseReport.add(r);
                vPhaseReport.addAll(gameManager.resolveIceBroken(c));
            } else {
                ice.setTerrainFactor(tf);
            }
        }
        if ((magma != null) && (magma.getLevel() == 1)) {
            int tf = magma.getTerrainFactor() - nDamage;
            if (tf <= 0) {
                // magma crust destroyed
                r = new Report(3093, reportType);
                r.subject = entityId;
                vPhaseReport.add(r);
                h.removeTerrain(Terrains.MAGMA);
                h.addTerrain(new Terrain(Terrains.MAGMA, 2));
                for (Entity en : gameManager.game.getEntitiesVector(c)) {
                    gameManager.doMagmaDamage(en, false);
                }
            } else {
                magma.setTerrainFactor(tf);
            }
        }
        gameManager.communicationManager.sendChangedHex(c, gameManager);

        // any attempt to clear an heavy industrial hex may cause an explosion
        gameManager.checkExplodeIndustrialZone(c, vPhaseReport);

        return vPhaseReport;
    }

    /**
     * Cleans up the attack declarations for the physical phase by removing all
     * attacks past the first for any one mech. Also clears out attacks by dead
     * or disabled mechs.
     * @param gameManager
     */
    protected void cleanupPhysicalAttacks(GameManager gameManager) {
        for (Iterator<Entity> i = gameManager.game.getEntities(); i.hasNext(); ) {
            Entity entity = i.next();
            gameManager.environmentalEffectManager.removeDuplicateAttacks(entity.getId(), gameManager);
        }
        gameManager.environmentalEffectManager.removeDeadAttacks(gameManager);
    }

    /**
     * Removes any actions in the attack queue beyond the first by the specified
     * entity, unless that entity has melee master in which case it allows two
     * attacks.
     * @param entityId
     * @param gameManager
     */
    protected void removeDuplicateAttacks(int entityId, GameManager gameManager) {
        int allowed = 1;
        Entity en = gameManager.game.getEntity(entityId);
        if (null != en) {
            allowed = en.getAllowedPhysicalAttacks();
        }
        Vector<EntityAction> toKeep = new Vector<>();

        for (Enumeration<EntityAction> i = gameManager.game.getActions(); i.hasMoreElements(); ) {
            EntityAction action = i.nextElement();
            if (action.getEntityId() != entityId) {
                toKeep.addElement(action);
            } else if (allowed > 0) {
                toKeep.addElement(action);
                if (!(action instanceof SearchlightAttackAction)) {
                    allowed--;
                }
            } else {
                LogManager.getLogger().error("Removing duplicate phys attack for id#" + entityId
                        + "\n\t\taction was " + action);
            }
        }

        // reset actions and re-add valid elements
        gameManager.game.resetActions();
        for (EntityAction entityAction : toKeep) {
            gameManager.game.addAction(entityAction);
        }
    }

    /**
     * Try to ignite the hex, taking into account existing fires and the
     * effects of Inferno rounds. This version of the method will not report the
     * attempt roll.
     *  @param c
     *            - the <code>Coords</code> of the hex being lit.
     * @param entityId
     *            - the <code>int</code> id of the entity involved.
     * @param bHotGun
     * @param bInferno
     *            - <code>true</code> if the weapon igniting the hex is an
     *            Inferno round. If some other weapon or ammo is causing the
     *            roll, this should be <code>false</code>.
     * @param nTargetRoll
     * @param accidentTarget
     * @param vPhaseReport
     * @param gameManager
     */
    public boolean tryIgniteHex(Coords c, int entityId, boolean bHotGun, boolean bInferno,
                                TargetRoll nTargetRoll, int accidentTarget,
                                Vector<Report> vPhaseReport, GameManager gameManager) {
        return tryIgniteHex(c, entityId, bHotGun, bInferno, nTargetRoll, false,
                accidentTarget, vPhaseReport, gameManager);
    }

    /**
     * Removes all attacks by any dead entities. It does this by going through
     * all the attacks and only keeping ones from active entities. DFAs are kept
     * even if the pilot is unconscious, so that he can fail.
     * @param gameManager
     */
    protected void removeDeadAttacks(GameManager gameManager) {
        Vector<EntityAction> toKeep = new Vector<>(gameManager.game.actionsSize());

        for (Enumeration<EntityAction> i = gameManager.game.getActions(); i.hasMoreElements(); ) {
            EntityAction action = i.nextElement();
            Entity entity = gameManager.game.getEntity(action.getEntityId());
            if ((entity != null) && !entity.isDestroyed()
                    && (entity.isActive() || (action instanceof DfaAttackAction))) {
                toKeep.addElement(action);
            }
        }

        // reset actions and re-add valid elements
        gameManager.game.resetActions();
        for (EntityAction entityAction : toKeep) {
            gameManager.game.addAction(entityAction);
        }
    }
}
