package megamek.server.gameManager;

import megamek.common.*;
import megamek.common.actions.ArtilleryAttackAction;
import megamek.common.actions.DfaAttackAction;
import megamek.common.actions.EntityAction;
import megamek.common.actions.SearchlightAttackAction;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.ArmorType;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.weapons.AreaEffectHelper;
import megamek.common.weapons.AttackHandler;
import megamek.common.weapons.DamageType;
import megamek.common.weapons.WeaponHandler;
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
            gameManager.environmentalEffectManager.collapseBuilding(bldg, positionMap, centralPos, gameManager.vPhaseReport, gameManager);
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
                gameManager.environmentalEffectManager.collapseBuilding(bldg, positionMap, pos, gameManager.vPhaseReport, gameManager);
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

                alreadyHit = gameManager.environmentalEffectManager.artilleryDamageHex(pos, centralPos, damageDice, null, killer.getId(),
                        killer, null, false, 0, gameManager.vPhaseReport, false,
                        alreadyHit, true, gameManager);
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
        gameManager.entityActionManager.entityUpdate(unit.getId(), gameManager);

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
        gameManager.entityActionManager.entityUpdate(drop.getId(), gameManager);
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
                            gameManager.entityActionManager.entityUpdate(entity.getId(), gameManager);
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
                if (gameManager.environmentalEffectManager.doBlind(gameManager)) { // only report if DB, otherwise all players see
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
                    if (gameManager.environmentalEffectManager.doBlind(gameManager)) {
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
                    if (gameManager.environmentalEffectManager.doBlind(gameManager)) {
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
            if (gameManager.environmentalEffectManager.doBlind(gameManager)) { // Only do if DB, otherwise all players will see
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
                if (gameManager.environmentalEffectManager.doBlind(gameManager)) {
                    r = new Report(2217, Report.PLAYER);
                    r.player = mf.getPlayerId();
                    vMineReport.add(r);
                }
                continue;
            }

            // Report hit
            if (gameManager.environmentalEffectManager.doBlind(gameManager)) {
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
        gameManager.environmentalEffectManager.createSmoke(coords, smokeType, 3, gameManager);
        Hex hex = gameManager.game.getBoard().getHex(coords);
        hex.addTerrain(new Terrain(Terrains.SMOKE, smokeType));
        gameManager.communicationManager.sendChangedHex(coords, gameManager);
    }

    public void deliverSmokeGrenade(Coords coords, Vector<Report> vPhaseReport, GameManager gameManager) {
        Report r = new Report(5200, Report.PUBLIC);
        r.indent(2);
        r.add(coords.getBoardNum());
        vPhaseReport.add(r);
        gameManager.environmentalEffectManager.createSmoke(coords, SmokeCloud.SMOKE_LIGHT, 3, gameManager);
        Hex hex = gameManager.game.getBoard().getHex(coords);
        hex.addTerrain(new Terrain(Terrains.SMOKE, SmokeCloud.SMOKE_LIGHT));
        gameManager.communicationManager.sendChangedHex(coords, gameManager);
    }

    public void deliverSmokeMortar(Coords coords, Vector<Report> vPhaseReport, int duration, GameManager gameManager) {
        Report r = new Report(5185, Report.PUBLIC);
        r.indent(2);
        r.add(coords.getBoardNum());
        vPhaseReport.add(r);
        gameManager.environmentalEffectManager.createSmoke(coords, SmokeCloud.SMOKE_HEAVY, duration, gameManager);
        Hex hex = gameManager.game.getBoard().getHex(coords);
        hex.addTerrain(new Terrain(Terrains.SMOKE, SmokeCloud.SMOKE_HEAVY));
        gameManager.communicationManager.sendChangedHex(coords, gameManager);
    }

    public void deliverChaffGrenade(Coords coords, Vector<Report> vPhaseReport, GameManager gameManager) {
        Report r = new Report(5187, Report.PUBLIC);
        r.indent(2);
        r.add(coords.getBoardNum());
        vPhaseReport.add(r);
        gameManager.environmentalEffectManager.createSmoke(coords, SmokeCloud.SMOKE_CHAFF_LIGHT, 1, gameManager);
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
        gameManager.environmentalEffectManager.createSmoke(coords, SmokeCloud.SMOKE_HEAVY, 3, gameManager);
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
                gameManager.environmentalEffectManager.createSmoke(tempcoords, SmokeCloud.SMOKE_HEAVY, 3, gameManager);
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
        gameManager.environmentalEffectManager.createSmoke(coords, SmokeCloud.SMOKE_LI_HEAVY, 2, gameManager);
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
                gameManager.environmentalEffectManager.createSmoke(tempcoords, SmokeCloud.SMOKE_LI_HEAVY, 2, gameManager);
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
                vPhaseReport.addAll(gameManager.entityActionManager.meltIceAndSnow(coords, subjectId, gameManager));
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
                    vPhaseReport.addAll(gameManager.entityActionManager.meltIceAndSnow(tempcoords, subjectId, gameManager));
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
        gameManager.entityActionManager.entityUpdate(tele.getId(), gameManager);
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
                    Vector<Report> vBuildingReport = gameManager.environmentalEffectManager.damageBuilding(gameManager.game.getBoard().getBuildingAt(t.getPosition()),
                            2 * missiles, t.getPosition(), gameManager);
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
                Vector<Report> vBuildingReport = gameManager.environmentalEffectManager.damageBuilding(gameManager.game.getBoard().getBuildingAt(t.getPosition()),
                        2 * missiles, t.getPosition(), gameManager);
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
                vPhaseReport.addAll(gameManager.entityActionManager.meltIceAndSnow(c, entityId, gameManager));
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
        } else if (gameManager.environmentalEffectManager.checkIgnition(c, nTargetRoll, bInferno, entityId,
                vPhaseReport, gameManager)) {
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
                vPhaseReport.addAll(gameManager.entityActionManager.resolveIceBroken(c, gameManager));
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
                    gameManager.environmentalEffectManager.doMagmaDamage(en, false, gameManager);
                }
            } else {
                magma.setTerrainFactor(tf);
            }
        }
        gameManager.communicationManager.sendChangedHex(c, gameManager);

        // any attempt to clear an heavy industrial hex may cause an explosion
        gameManager.environmentalEffectManager.checkExplodeIndustrialZone(c, vPhaseReport, gameManager);

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

    /**
     * Checks for fire ignition based on a given target roll. If successful,
     * lights a fire also checks to see that fire is possible in the specified
     * hex.
     *  @param c        - the <code>Coords</code> to be lit.
     * @param roll     - the <code>TargetRoll</code> for the ignition roll
     * @param bInferno - <code>true</code> if the fire is an inferno fire. If this
     *                 value is <code>false</code> the hex will be lit only if it
     *                 contains Woods, jungle or a Building.
     * @param entityId - the entityId responsible for the ignite attempt. If the
     *                 value is Entity.NONE, then the roll attempt will not be
     * @param vPhaseReport
     * @param gameManager
     */
    public boolean checkIgnition(Coords c, TargetRoll roll, boolean bInferno, int entityId,
                                 Vector<Report> vPhaseReport, GameManager gameManager) {

        Hex hex = gameManager.game.getBoard().getHex(c);

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
            gameManager.ignite(c, Terrains.FIRE_LVL_NORMAL, vPhaseReport);
            return true;
        }
        return false;
    }

    /**
     * Returns true if the hex is set on fire with the specified roll. Of
     * course, also checks to see that fire is possible in the specified hex.
     * This version of the method will not report the attempt roll.
     *  @param c        - the <code>Coords</code> to be lit.
     * @param roll     - the <code>int</code> target number for the ignition roll
     * @param bInferno - <code>true</code> if the fire can be lit in any terrain. If
     *                 this value is <code>false</code> the hex will be lit only if
     * @param gameManager
     */
    public boolean checkIgnition(Coords c, TargetRoll roll, boolean bInferno, GameManager gameManager) {
        return checkIgnition(c, roll, bInferno, Entity.NONE, null, gameManager);
    }

    /**
     * Returns true if the hex is set on fire with the specified roll. Of
     * course, also checks to see that fire is possible in the specified hex.
     * This version of the method will not report the attempt roll.
     *  @param c    - the <code>Coords</code> to be lit.
     * @param roll - the <code>int</code> target number for the ignition roll
     * @param gameManager
     */
    public boolean checkIgnition(Coords c, TargetRoll roll, GameManager gameManager) {
        // default signature, assuming only woods can burn
        return checkIgnition(c, roll, false, Entity.NONE, null, gameManager);
    }

    boolean suppressBlindBV(GameManager gameManager) {
        return gameManager.game.getOptions().booleanOption(OptionsConstants.ADVANCED_SUPPRESS_DB_BV);
    }

    /**
     * @return whether this game is double blind or not and we should be blind in
     * the current phase
     * @param gameManager
     */
    boolean doBlind(GameManager gameManager) {
        return gameManager.game.getOptions().booleanOption(OptionsConstants.ADVANCED_DOUBLE_BLIND)
                && gameManager.game.getPhase().isDuringOrAfter(GamePhase.DEPLOYMENT);
    }

    /**
     * Makes one slot of inferno ammo, determined by certain rules, explode on a
     * mech.
     *
     * @param entity
     *            The <code>Entity</code> that should suffer an inferno ammo
     *            explosion.
     * @param gameManager
     */
    protected Vector<Report> explodeInfernoAmmoFromHeat(Entity entity, GameManager gameManager) {
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
            vDesc.addAll(gameManager.entityActionManager.explodeEquipment(entity, boomloc, boomslot, gameManager));
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
     * @param c
     * @param vDesc
     * @param gameManager
     */
    public void checkExplodeIndustrialZone(Coords c, Vector<Report> vDesc, GameManager gameManager) {
        Report r;
        Hex hex = gameManager.game.getBoard().getHex(c);
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
                for (Entity en : gameManager.game.getEntitiesVector(c)) {
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
                                vDesc.addAll(gameManager.damageEntity(en, new HitData(loc), damage));
                            }
                        }
                    } else {
                        vDesc.addAll(gameManager.damageEntity(en, hit, damage));
                    }
                    if (majorExp) {
                        // lets pretend that the infernos came from the entity
                        // itself (should give us side_front)
                        vDesc.addAll(deliverInfernoMissiles(en, en, Compute.d6(2), gameManager));
                    }
                }
            }
            Report.addNewline(vDesc);
            if (onFire && !hex.containsTerrain(Terrains.FIRE)) {
                gameManager.ignite(c, Terrains.FIRE_LVL_NORMAL, vDesc);
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
     *  @param entity
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
     * @param overallMoveType
     * @param entering
     *            - a <code>boolean</code> if the entity is entering or exiting
     * @param gameManager
     */
    protected void passBuildingWall(Entity entity, Building bldg, Coords lastPos, Coords curPos,
                                    int distance, String why, boolean backwards,
                                    EntityMovementType overallMoveType, boolean entering, GameManager gameManager) {
        Report r;

        if (entity instanceof Protomech) {
            Vector<Report> vBuildingReport = gameManager.environmentalEffectManager.damageBuilding(bldg, 1, curPos, gameManager);
            for (Report report : vBuildingReport) {
                report.subject = entity.getId();
            }
            gameManager.reportManager.addReport(vBuildingReport, gameManager);
        } else {
            // Need to roll based on building type.
            PilotingRollData psr = entity.rollMovementInBuilding(bldg, distance, why, overallMoveType);

            // Did the entity make the roll?
            if (0 < gameManager.utilityManager.doSkillCheckWhileMoving(entity, entity.getElevation(), lastPos, curPos, psr, false, gameManager)) {

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
                    gameManager.addReport(r);
                } else {
                    // TW, pg. 268: if unit moves forward, damage from front,
                    // if backwards, damage from rear.
                    int side = ToHitData.SIDE_FRONT;
                    if (backwards) {
                        side = ToHitData.SIDE_REAR;
                    }
                    HitData hit = entity.rollHitLocation(ToHitData.HIT_NORMAL, side);
                    hit.setGeneralDamageType(HitData.DAMAGE_PHYSICAL);
                    gameManager.reportManager.addReport(gameManager.damageEntity(entity, hit, damage), gameManager);
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
            gameManager.reportManager.addReport(gameManager.environmentalEffectManager.damageInfantryIn(bldg, toBldg, entering ? curPos : lastPos, gameManager), gameManager);
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
     * @param gameManager
     * @return a <code>boolean</code> value indicating if the building collapses
     */
    protected boolean checkBuildingCollapseWhileMoving(Building bldg, Entity entity, Coords curPos, GameManager gameManager) {
        Coords oldPos = entity.getPosition();
        // Count the moving entity in its current position, not
        // its pre-move position. Be sure to handle nulls.
        entity.setPosition(curPos);

        // Get the position map of all entities in the game.
        Hashtable<Coords, Vector<Entity>> positionMap = gameManager.game.getPositionMap();

        // Check for collapse of this building due to overloading, and return.
        boolean rv = gameManager.environmentalEffectManager.checkForCollapse(bldg, positionMap, curPos, true, gameManager.vPhaseReport, gameManager);

        // If the entity was not displaced and didn't fall, move it back where it was
        if (curPos.equals(entity.getPosition()) && !entity.isProne()) {
            entity.setPosition(oldPos);
        }
        return rv;
    }

    /**
     * Apply the correct amount of damage that passes on to any infantry unit in
     * the given building, based upon the amount of damage the building just
     * sustained. This amount is a percentage dictated by pg. 172 of TW.
     *  @param bldg   - the <code>Building</code> that sustained the damage.
     * @param damage - the <code>int</code> amount of damage.
     * @param hexCoords
     * @param infDamageClass
     * @param gameManager
     */
    public Vector<Report> damageInfantryIn(Building bldg, int damage, Coords hexCoords,
                                           int infDamageClass, GameManager gameManager) {
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
        for (Entity entity : gameManager.game.getEntitiesVector()) {
            final Coords coords = entity.getPosition();

            // If the entity is infantry in the affected hex?
            if ((entity instanceof Infantry) && bldg.isIn(coords) && coords.equals(hexCoords)) {
                // Is the entity is inside of the building
                // (instead of just on top of it)?
                if (Compute.isInBuilding(gameManager.game, entity, coords)) {

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
                            vDesc.addAll((gameManager.damageEntity(entity, hit, next)));
                            remaining -= next;
                        }
                    }

                    Report.addNewline(vDesc);
                } // End infantry-inside-building
            } // End entity-is-infantry-in-building-hex
        } // Handle the next entity

        return vDesc;
    } // End protected void damageInfantryIn( Building, int )

    public Vector<Report> damageInfantryIn(Building bldg, int damage, Coords hexCoords, GameManager gameManager) {
        return damageInfantryIn(bldg, damage, hexCoords, WeaponType.WEAPON_NA, gameManager);
    }

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
     * @param checkBecauseOfDamage
     * @param vPhaseReport
     * @param gameManager
     * @return <code>true</code> if the building collapsed.
     */
    public boolean checkForCollapse(Building bldg, Hashtable<Coords, Vector<Entity>> positionMap,
                                    Coords coords, boolean checkBecauseOfDamage,
                                    Vector<Report> vPhaseReport, GameManager gameManager) {

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
            final Hex curHex = gameManager.game.getBoard().getHex(coords);
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
                    gameManager.environmentalEffectManager.collapseBasement(bldg, basementMap, coords, vPhaseReport, gameManager);
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

            gameManager.environmentalEffectManager.collapseBuilding(bldg, positionMap, coords, false, vPhaseReport, gameManager);
        } else if (topFloorCollapse) {
            Report r = new Report(2376, Report.PUBLIC);
            r.add(bldg.getName());
            vPhaseReport.add(r);

            gameManager.environmentalEffectManager.collapseBuilding(bldg, positionMap, coords, false, true, vPhaseReport, gameManager);
        }

        // Return true if the building collapsed.
        return collapse || topFloorCollapse;

    } // End protected boolean checkForCollapse( Building, Hashtable )

    public void collapseBuilding(Building bldg, Hashtable<Coords, Vector<Entity>> positionMap,
                                 Coords coords, Vector<Report> vPhaseReport, GameManager gameManager) {
        gameManager.environmentalEffectManager.collapseBuilding(bldg, positionMap, coords, true, false, vPhaseReport, gameManager);
    }

    public void collapseBuilding(Building bldg, Hashtable<Coords, Vector<Entity>> positionMap,
                                 Coords coords, boolean collapseAll, Vector<Report> vPhaseReport, GameManager gameManager) {
        gameManager.environmentalEffectManager.collapseBuilding(bldg, positionMap, coords, collapseAll, false, vPhaseReport, gameManager);
    }

    /**
     * Collapse a building basement. Inflict the appropriate amount of damage on
     * all entities that fell to the basement. Update all clients.
     *  @param bldg
     *            - the <code>Building</code> that has collapsed.
     * @param positionMap
     *            - a <code>Hashtable</code> that maps the <code>Coords</code>
     *            positions or each unit in the game to a <code>Vector</code> of
     *            <code>Entity</code>s at that position. This value should not
     *            be <code>null</code>.
     * @param coords
     *            - The <code>Coords</code> of the building basement hex that
     * @param vPhaseReport
     * @param gameManager
     */
    public void collapseBasement(Building bldg, Hashtable<Coords, Vector<Entity>> positionMap,
                                 Coords coords, Vector<Report> vPhaseReport, GameManager gameManager) {
        if (!bldg.hasCFIn(coords)) {
            return;
        }
        int runningCFTotal = bldg.getCurrentCF(coords);

        // Get the Vector of Entities at these coordinates.
        final Vector<Entity> entities = positionMap.get(coords);

        if (bldg.getBasement(coords).isNone()) {
            return;
        } else {
            bldg.collapseBasement(coords, gameManager.game.getBoard(), vPhaseReport);
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
                        vPhaseReport.addAll(gameManager.doEntityFall(entity, coords, 0, Compute.d6(), psr,
                                true, false));
                        runningCFTotal -= cfDamage * 2;
                        break;
                    default:
                        LogManager.getLogger().info(entity.getDisplayName() + " is falling 1 floor into " + coords.toString());
                        // Damage is determined by the depth of the basement, so a fall of 0
                        // elevation is correct in this case
                        vPhaseReport.addAll(gameManager.doEntityFall(entity, coords, 0, Compute.d6(), psr,
                                true, false));
                        runningCFTotal -= cfDamage;
                        break;
                }

                // Update this entity.
                // ASSUMPTION: this is the correct thing to do.
                gameManager.entityActionManager.entityUpdate(entity.getId(), gameManager);
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
        gameManager.communicationManager.sendChangedHex(coords, gameManager);
        Vector<Building> buildings = new Vector<>();
        buildings.add(bldg);
        gameManager.communicationManager.sendChangedBuildings(buildings, gameManager);
    }

    /**
     * Collapse a building hex. Inflict the appropriate amount of damage on all
     * entities in the building. Update all clients.
     *  @param bldg
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
     * @param vPhaseReport
     * @param gameManager
     *
     */
    public void collapseBuilding(Building bldg, Hashtable<Coords, Vector<Entity>> positionMap,
                                 Coords coords, boolean collapseAll, boolean topFloor,
                                 Vector<Report> vPhaseReport, GameManager gameManager) {
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
            final Hex curHex = gameManager.game.getBoard().getHex(coords);
            final int bridgeEl = curHex.terrainLevel(Terrains.BRIDGE_ELEV);
            final int numFloors = Math.max(bridgeEl,
                    curHex.terrainLevel(Terrains.BLDG_ELEV));

            // Now collapse the building in this hex, so entities fall to
            // the ground
            if (topFloor && numFloors > 1) {
                curHex.removeTerrain(Terrains.BLDG_ELEV);
                curHex.addTerrain(new Terrain(Terrains.BLDG_ELEV, numFloors - 1));
                gameManager.communicationManager.sendChangedHex(coords, gameManager);
            } else {
                bldg.setCurrentCF(0, coords);
                bldg.setPhaseCF(0, coords);
                gameManager.communicationManager.send(gameManager.packetManager.createCollapseBuildingPacket(coords, gameManager));
                gameManager.game.getBoard().collapseBuilding(coords);
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
                    vPhaseReport.addAll(gameManager.entityActionManager.destroyEntity(entity, "building collapse", gameManager));
                    gameManager.addNewLines();
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
                    vPhaseReport.addAll(gameManager.entityActionManager.destroyEntity(entity,
                            "Crushed under building rubble", false, false, gameManager));
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
                    vPhaseReport.addAll(gameManager.damageEntity(entity, hit, next));
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
                    vPhaseReport.addAll(gameManager.utilityManager.doEntityFallsInto(entity, coords, psr,
                            true, gameManager));
                }
                // Update this entity.
                // ASSUMPTION: this is the correct thing to do.
                gameManager.entityActionManager.entityUpdate(entity.getId(), gameManager);
            }
        } else {
            // Update the building.
            bldg.setCurrentCF(0, coords);
            bldg.setPhaseCF(0, coords);
            gameManager.communicationManager.send(gameManager.packetManager.createCollapseBuildingPacket(coords, gameManager));
            gameManager.game.getBoard().collapseBuilding(coords);
        }
        // if more than half of the hexes are gone, collapse all
        if (bldg.getCollapsedHexCount() > (bldg.getOriginalHexCount() / 2)) {
            for (Enumeration<Coords> coordsEnum = bldg.getCoords(); coordsEnum.hasMoreElements();) {
                coords = coordsEnum.nextElement();
                collapseBuilding(bldg, gameManager.game.getPositionMap(), coords, false, vPhaseReport, gameManager);
            }
        }
    }

    /**
     * Apply this phase's damage to all buildings. Buildings may collapse due to
     * damage.
     * @param gameManager
     */
    void applyBuildingDamage(GameManager gameManager) {

        // Walk through the buildings in the game.
        // Build the collapse and update vectors as you go.
        // N.B. never, NEVER, collapse buildings while you are walking through
        // the Enumeration from megamek.common.Board#getBuildings.
        Map<Building, Vector<Coords>> collapse = new HashMap<>();
        Map<Building, Vector<Coords>> update = new HashMap<>();
        Enumeration<Building> buildings = gameManager.game.getBoard().getBuildings();
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
            Hashtable<Coords, Vector<Entity>> positionMap = gameManager.game
                    .getPositionMap();

            // Walk through the hexes that have collapsed.
            for (Building bldg : collapse.keySet()) {
                Vector<Coords> coordsVector = collapse.get(bldg);
                for (Coords coords : coordsVector) {
                    Report r = new Report(6460, Report.PUBLIC);
                    r.add(bldg.getName());
                    gameManager.addReport(r);
                    collapseBuilding(bldg, positionMap, coords, gameManager.vPhaseReport, gameManager);
                }
            }
        }

        // check for buildings which should collapse due to being overloaded now
        // CF is reduced
        if (!update.isEmpty()) {
            Hashtable<Coords, Vector<Entity>> positionMap = gameManager.game.getPositionMap();
            for (Building bldg : update.keySet()) {
                Vector<Coords> updateCoords = update.get(bldg);
                Vector<Coords> coordsToRemove = new Vector<>();
                for (Coords coords : updateCoords) {
                    if (checkForCollapse(bldg, positionMap, coords, false,
                            gameManager.vPhaseReport, gameManager)) {
                        coordsToRemove.add(coords);
                    }
                }
                updateCoords.removeAll(coordsToRemove);
                update.put(bldg, updateCoords);
            }
        }

        // If we have any buildings to update, send the message.
        if (!update.isEmpty()) {
            gameManager.communicationManager.sendChangedBuildings(new Vector<>(update.keySet()), gameManager);
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
     * @param gameManager
     * @return a <code>Report</code> to be shown to the players.
     */
    public Vector<Report> damageBuilding(Building bldg, int damage,
                                         Coords coords, GameManager gameManager) {
        final String defaultWhy = " absorbs ";
        return gameManager.environmentalEffectManager.damageBuilding(bldg, damage, defaultWhy, coords, gameManager);
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
     * @param gameManager
     * @return a <code>Report</code> to be shown to the players.
     */
    public Vector<Report> damageBuilding(Building bldg, int damage, String why, Coords coords, GameManager gameManager) {
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
                        gameManager.doExplosion(((FuelTank) bldg).getMagnitude(), 10,
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
                    Vector<GunEmplacement> guns = gameManager.game.getGunEmplacements(coords);
                    if (!guns.isEmpty()) {
                        vPhaseReport.addAll(gameManager.environmentalEffectManager.criticalGunEmplacement(guns, bldg, coords, gameManager));
                    }
                }
            }
        }
        Report.indentAll(vPhaseReport, 2);
        return vPhaseReport;
    }

    protected Vector<Report> criticalGunEmplacement(Vector<GunEmplacement> guns, Building bldg,
                                                    Coords coords, GameManager gameManager) {
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
                    vDesc.addAll(gameManager.entityActionManager.destroyEntity(gun, "ammo explosion", false, false, gameManager));
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
                    gameManager.doExplosion(((FuelTank) bldg).getMagnitude(), 10, false,
                            bldg.getCoords().nextElement(), true, vRep, null,
                            -1);
                    Report.indentAll(vRep, 2);
                    vDesc.addAll(vRep);
                    return gameManager.vPhaseReport;
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
     *  @param entityID the <code>int</code> id of the entity
     * @param weaponID the <code>int</code> id of the weapon
     * @param gameManager
     */
    protected void clearArtillerySpotters(int entityID, int weaponID, GameManager gameManager) {
        for (Enumeration<AttackHandler> i = gameManager.game.getAttacks(); i.hasMoreElements(); ) {
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
     * resolve the landing of an assault drop
     *
     * @param entity the <code>Entity</code> for which to resolve it
     * @param gameManager
     */
    public void doAssaultDrop(Entity entity, GameManager gameManager) {
        //resolve according to SO p.22

        Report r = new Report(2380);

        // whatever else happens, this entity is on the ground now
        entity.setAltitude(0);

        PilotingRollData psr;
        // LAMs that convert to fighter mode on the landing turn are processed as crashes
        if ((entity instanceof LandAirMech)
                && (entity.getConversionMode() == LandAirMech.CONV_MODE_FIGHTER)) {
            gameManager.reportManager.addReport(gameManager.entityActionManager.processCrash(entity, 0, entity.getPosition(), gameManager), gameManager);
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
        gameManager.addNewLines();
        r.subject = entity.getId();
        r.add(entity.getDisplayName(), true);
        r.add(psr);
        r.add(diceRoll);
        r.newlines = 1;
        r.choose(diceRoll.getIntValue() >= psr.getValue());
        gameManager.addReport(r);

        // if we are on an atmospheric map or the entity is off the map for some reason
        if (gameManager.game.getBoard().inAtmosphere() || entity.getPosition() == null) {
            // then just remove the entity
            // TODO : for this and when the unit scatters off the board, we should really still
            // TODO : apply damage before we remove, but this causes all kinds of problems for
            // TODO : doEntityFallsInto and related methods which expect a coord on the board
            // TODO : - need to make those more robust
            r = new Report(2388);
            gameManager.addReport(r);
            r.subject = entity.getId();
            r.add(entity.getDisplayName(), true);
            gameManager.game.removeEntity(entity.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
            return;
        }

        if (diceRoll.getIntValue() < psr.getValue()) {
            int fallHeight = psr.getValue() - diceRoll.getIntValue();

            // if you fail by more than 7, you automatically fail
            if (fallHeight > 7) {
                gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity(entity, "failed assault drop", false, false, gameManager), gameManager);
                gameManager.entityActionManager.entityUpdate(entity.getId(), gameManager);
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
            gameManager.addReport(r);
            if (!gameManager.game.getBoard().contains(c)) {
                r = new Report(2386);
                r.subject = entity.getId();
                gameManager.addReport(r);
                gameManager.game.removeEntity(entity.getId(), IEntityRemovalConditions.REMOVE_IN_RETREAT);
                return;
            } else {
                r = new Report(2387);
                r.subject = entity.getId();
                r.add(c.getBoardNum());
                gameManager.addReport(r);
            }
            entity.setPosition(c);

            // do fall damage from accidental fall
            //set elevation to fall height above ground or building roof
            Hex hex = gameManager.game.getBoard().getHex(entity.getPosition());
            int bldgElev = hex.containsTerrain(Terrains.BLDG_ELEV)
                    ? hex.terrainLevel(Terrains.BLDG_ELEV) : 0;
            entity.setElevation(fallHeight + bldgElev);
            if (entity.isConventionalInfantry()) {
                HitData hit = new HitData(Infantry.LOC_INFANTRY);
                gameManager.reportManager.addReport(gameManager.damageEntity(entity, hit, 1), gameManager);
                // LAMs that convert to fighter mode on the landing turn are processed as crashes regardless of roll
            } else {
                gameManager.reportManager.addReport(gameManager.utilityManager.doEntityFallsInto(entity, c, psr, true, gameManager), gameManager);
            }
        } else {
            // set entity to expected elevation
            Hex hex = gameManager.game.getBoard().getHex(entity.getPosition());
            int bldgElev = hex.containsTerrain(Terrains.BLDG_ELEV)
                    ? hex.terrainLevel(Terrains.BLDG_ELEV) : 0;
            entity.setElevation(bldgElev);

            Building bldg = gameManager.game.getBoard().getBuildingAt(entity.getPosition());
            if (bldg != null) {
                // whoops we step on the roof
                checkBuildingCollapseWhileMoving(bldg, entity, entity.getPosition(), gameManager);
            }

            // finally, check for any stacking violations
            Entity violated = Compute.stackingViolation(gameManager.game, entity, entity.getPosition(), null, entity.climbMode());
            if (violated != null) {
                // StratOps explicitly says that this is not treated as an accident
                // fall from above
                // so we just need to displace the violating unit
                // check to see if the violating unit is a DropShip and if so, then
                // displace the unit dropping instead
                if (violated instanceof Dropship) {
                    violated = entity;
                }
                Coords targetDest = Compute.getValidDisplacement(gameManager.game, violated.getId(),
                        violated.getPosition(), Compute.d6() - 1);
                if (null != targetDest) {
                    gameManager.utilityManager.doEntityDisplacement(violated, violated.getPosition(), targetDest, null, gameManager);
                    gameManager.entityActionManager.entityUpdate(violated.getId(), gameManager);
                } else {
                    // ack! automatic death! Tanks
                    // suffer an ammo/power plant hit.
                    // TODO : a Mech suffers a Head Blown Off crit.
                    gameManager.vPhaseReport.addAll(gameManager.entityActionManager.destroyEntity(entity, "impossible displacement",
                            entity instanceof Mech, entity instanceof Mech, gameManager));
                }
            }
        }
    }

    /**
     * resolve assault drops for all entities
     * @param gameManager
     */
    void doAllAssaultDrops(GameManager gameManager) {
        for (Entity e : gameManager.game.getEntitiesVector()) {
            if (e.isAssaultDropInProgress() && e.isDeployed()) {
                doAssaultDrop(e, gameManager);
                e.setLandedAssaultDrop();
            }
        }
    }

    /**
     * do damage from magma
     *  @param en       the affected <code>Entity</code>
     * @param eruption <code>boolean</code> indicating whether or not this is because
     * @param gameManager
     */
    public void doMagmaDamage(Entity en, boolean eruption, GameManager gameManager) {
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
        gameManager.addReport(r);
        if (isMech) {
            HitData h;
            for (int i = 0; i < en.locations(); i++) {
                if (eruption || en.locationIsLeg(i) || en.isProne()) {
                    h = new HitData(i);
                    gameManager.reportManager.addReport(gameManager.damageEntity(en, h, Compute.d6(2)), gameManager);
                }
            }
        } else {
            gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity(en, "fell into magma", false, false, gameManager), gameManager);
        }
        gameManager.addNewLines();
    }

    /**
     * Applies damage to any eligible unit hit by anti-TSM missiles or entering
     * a hex with green smoke.
     *
     * @param entity An entity subject to anti-TSM damage
     * @param gameManager
     * @return The damage reports
     */
    public Vector<Report> doGreenSmokeDamage(Entity entity, GameManager gameManager) {
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
            reports.addAll(gameManager.damageEntity(entity, new HitData(Infantry.LOC_INFANTRY), Compute.d6()));
        } else {
            for (int loc = 0; loc < entity.locations(); loc++) {
                if ((entity.getArmor(loc) <= 0 || (entity.hasRearArmor(loc) && (entity.getArmor(loc, true) < 0)))
                        && !entity.isLocationBlownOff(loc)) {
                    r = new Report(6433);
                    r.subject = entity.getId();
                    r.add(entity.getLocationName(loc));
                    r.indent(1);
                    reports.add(r);
                    reports.addAll(gameManager.damageEntity(entity, new HitData(loc), 6, false,
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
     * @param en
     * @param gameManager
     */
    public void doSinkEntity(Entity en, GameManager gameManager) {
        Report r;
        r = new Report(2445);
        r.addDesc(en);
        r.subject = en.getId();
        gameManager.addReport(r);
        en.setElevation(en.getElevation() - 1);
        // if this means the entity is below the ground, then bye-bye!
        if (Math.abs(en.getElevation()) > en.getHeight()) {
            gameManager.reportManager.addReport(gameManager.entityActionManager.destroyEntity(en, "quicksand", gameManager), gameManager);
        }
    }

    /**
     * deal area saturation damage to an individual hex
     *  @param coords         The hex being hit
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
     * @param gameManager
     */
    public Vector<Integer> artilleryDamageHex(Coords coords,
                                              Coords attackSource, int damage, AmmoType ammo, int subjectId,
                                              Entity killer, Entity exclude, boolean flak, int altitude,
                                              Vector<Report> vPhaseReport, boolean asfFlak,
                                              Vector<Integer> alreadyHit, boolean variableDamage, GameManager gameManager) {

        Hex hex = gameManager.game.getBoard().getHex(coords);
        if (hex == null) {
            return alreadyHit; // not on board.
        }

        Report r;

        // Non-flak artillery damages terrain
        if (!flak) {
            // Report that damage applied to terrain, if there's TF to damage
            Hex h = gameManager.game.getBoard().getHex(coords);
            if ((h != null) && h.hasTerrainFactor()) {
                r = new Report(3384);
                r.indent(2);
                r.subject = subjectId;
                r.add(coords.getBoardNum());
                r.add(damage * 2);
                vPhaseReport.addElement(r);
            }
            // Update hex and report any changes
            Vector<Report> newReports = tryClearHex(coords, damage * 2, subjectId, gameManager);
            for (Report nr : newReports) {
                nr.indent(3);
            }
            vPhaseReport.addAll(newReports);
        }

        boolean isFuelAirBomb =
                ammo != null &&
                        (BombType.getBombTypeFromInternalName(ammo.getInternalName()) == BombType.B_FAE_SMALL ||
                                BombType.getBombTypeFromInternalName(ammo.getInternalName()) == BombType.B_FAE_LARGE);

        Building bldg = gameManager.game.getBoard().getBuildingAt(coords);
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
                Vector<Report> buildingReport = damageBuilding(bldg, actualDamage, coords, gameManager);
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
        for (Entity entity : gameManager.game.getEntitiesVector(coords)) {
            // Check: is entity excluded?
            if ((entity == exclude) || alreadyHit.contains(entity.getId())) {
                continue;
            } else {
                alreadyHit.add(entity.getId());
            }

            AreaEffectHelper.artilleryDamageEntity(entity, damage, bldg, bldgAbsorbs,
                    variableDamage, asfFlak, flak, altitude,
                    attackSource, ammo, coords, isFuelAirBomb,
                    killer, hex, subjectId, vPhaseReport, gameManager);
        }

        return alreadyHit;
    }

    /**
     * deal area saturation damage to the map, used for artillery
     *  @param centre       The hex on which damage is centred
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
     * @param gameManager
     */
    public void artilleryDamageArea(Coords centre, Coords attackSource,
                                    AmmoType ammo, int subjectId, Entity killer, boolean flak,
                                    int altitude, boolean mineClear, Vector<Report> vPhaseReport,
                                    boolean asfFlak, int attackingBA, GameManager gameManager) {
        AreaEffectHelper.DamageFalloff damageFalloff = AreaEffectHelper.calculateDamageFallOff(ammo, attackingBA, mineClear);

        int damage = damageFalloff.damage;
        int falloff = damageFalloff.falloff;
        if (damageFalloff.clusterMunitionsFlag) {
            attackSource = centre;
        }

        gameManager.environmentalEffectManager.artilleryDamageArea(centre, attackSource, ammo, subjectId, killer,
                damage, falloff, flak, altitude, vPhaseReport, asfFlak, gameManager);
    }

    /**
     * Deals area-saturation damage to an area of the board. Used for artillery,
     * bombs, or anything else with linear decrease in damage
     *  @param centre
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
     * @param gameManager
     */
    public void artilleryDamageArea(Coords centre, Coords attackSource, AmmoType ammo, int subjectId,
                                    Entity killer, int damage, int falloff, boolean flak, int altitude,
                                    Vector<Report> vPhaseReport, boolean asfFlak, GameManager gameManager) {
        Vector<Integer> alreadyHit = new Vector<>();
        for (int ring = 0; damage > 0; ring++, damage -= falloff) {
            List<Coords> hexes = centre.allAtDistance(ring);
            for (Coords c : hexes) {
                alreadyHit = artilleryDamageHex(c, attackSource, damage, ammo,
                        subjectId, killer, null, flak, altitude, vPhaseReport,
                        asfFlak, alreadyHit, false, gameManager);
            }
            attackSource = centre; // all splash comes from ground zero
        }
    }

    public void deliverBombDamage(Coords centre, int type, int subjectId, Entity killer,
                                  Vector<Report> vPhaseReport, GameManager gameManager) {
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
                alreadyHit, false, gameManager);
        if (range > 0) {
            List<Coords> hexes = centre.allAtDistance(range);
            for (Coords c : hexes) {
                alreadyHit = artilleryDamageHex(c, centre, damage, ammo,
                        subjectId, killer, null, false, 0, vPhaseReport, false,
                        alreadyHit, false, gameManager);
            }
        }
    }

    /**
     * deliver inferno bomb
     *  @param coords    the <code>Coords</code> where to deliver
     * @param ae        the attacking <code>entity</code>
     * @param subjectId the <code>int</code> id of the target
     * @param vPhaseReport
     * @param gameManager
     */
    public void deliverBombInferno(Coords coords, Entity ae, int subjectId,
                                   Vector<Report> vPhaseReport, GameManager gameManager) {
        Hex h = gameManager.game.getBoard().getHex(coords);
        Report r;
        // Unless there is a fire in the hex already, start one.
        if (h.terrainLevel(Terrains.FIRE) < Terrains.FIRE_LVL_INFERNO_BOMB) {
            gameManager.ignite(coords, Terrains.FIRE_LVL_INFERNO_BOMB, vPhaseReport);
        }
        // possibly melt ice and snow
        if (h.containsTerrain(Terrains.ICE) || h.containsTerrain(Terrains.SNOW)) {
            vPhaseReport.addAll(gameManager.entityActionManager.meltIceAndSnow(coords, subjectId, gameManager));
        }
        for (Entity entity : gameManager.game.getEntitiesVector(coords)) {
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
            Vector<Report> vDamageReport = deliverInfernoMissiles(ae, entity, 5, gameManager);
            Report.indentAll(vDamageReport, 2);
            vPhaseReport.addAll(vDamageReport);
        }
    }

    /**
     * Resolve any Infantry units which are fortifying hexes
     * @param gameManager
     */
    void resolveFortify(GameManager gameManager) {
        Report r;
        for (Entity ent : gameManager.game.getEntitiesVector()) {
            if (ent instanceof Infantry) {
                Infantry inf = (Infantry) ent;
                int dig = inf.getDugIn();
                if (dig == Infantry.DUG_IN_WORKING) {
                    r = new Report(5300);
                    r.addDesc(inf);
                    r.subject = inf.getId();
                    gameManager.addReport(r);
                } else if (dig == Infantry.DUG_IN_FORTIFYING3) {
                    Coords c = inf.getPosition();
                    r = new Report(5305);
                    r.addDesc(inf);
                    r.add(c.getBoardNum());
                    r.subject = inf.getId();
                    gameManager.addReport(r);
                    // fortification complete - add to map
                    Hex hex = gameManager.game.getBoard().getHex(c);
                    hex.addTerrain(new Terrain(Terrains.FORTIFIED, 1));
                    gameManager.communicationManager.sendChangedHex(c, gameManager);
                    // Clear the dig in for any units in same hex, since they
                    // get it for free by fort
                    for (Entity ent2 : gameManager.game.getEntitiesVector(c)) {
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
                    gameManager.addReport(r);
                    // Fort complete, now add it to the map
                    Hex hex = gameManager.game.getBoard().getHex(c);
                    hex.addTerrain(new Terrain(Terrains.FORTIFIED, 1));
                    gameManager.communicationManager.sendChangedHex(c, gameManager);
                    tnk.setDugIn(Tank.DUG_IN_NONE);
                    // Clear the dig in for any units in same hex, since they
                    // get it for free by fort
                    for (Entity ent2 : gameManager.game.getEntitiesVector(c)) {
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
     * create a <code>SmokeCloud</code> object and add it to the server list
     *  @param coords   the location to create the smoke
     * @param level    1=Light 2=Heavy Smoke 3:light LI smoke 4: Heavy LI smoke
     * @param duration How long the smoke will last.
     * @param gameManager
     */
    public void createSmoke(Coords coords, int level, int duration, GameManager gameManager) {
        SmokeCloud cloud = new SmokeCloud(coords, level, duration, gameManager.game.getRoundCount());
        gameManager.game.addSmokeCloud(cloud);
        gameManager.communicationManager.sendSmokeCloudAdded(cloud, gameManager);
    }

    /**
     * create a <code>SmokeCloud</code> object and add it to the server list
     *  @param coords   the location to create the smoke
     * @param level    1=Light 2=Heavy Smoke 3:light LI smoke 4: Heavy LI smoke
     * @param duration duration How long the smoke will last.
     * @param gameManager
     */
    public void createSmoke(ArrayList<Coords> coords, int level, int duration, GameManager gameManager) {
        SmokeCloud cloud = new SmokeCloud(coords, level, duration, gameManager.game.getRoundCount());
        gameManager.game.addSmokeCloud(cloud);
        gameManager.communicationManager.sendSmokeCloudAdded(cloud, gameManager);
    }

    /**
     * Check to see if blowing sand caused damage to airborne VTOL/WIGEs
     * @param gameManager
     */
    protected Vector<Report> resolveBlowingSandDamage(GameManager gameManager) {
        Vector<Report> vFullReport = new Vector<>();
        vFullReport.add(new Report(5002, Report.PUBLIC));
        int damage_bonus = Math.max(0, gameManager.game.getPlanetaryConditions().getWindStrength()
                - PlanetaryConditions.WI_MOD_GALE);
        // cycle through each team and damage 1d6 airborne VTOL/WiGE
        for (Team team : gameManager.game.getTeams()) {
            Vector<Integer> airborne = gameManager.environmentalEffectManager.getAirborneVTOL(team, gameManager);
            if (!airborne.isEmpty()) {
                // how many units are affected
                int unitsAffected = Math.min(Compute.d6(), airborne.size());
                while ((unitsAffected > 0) && !airborne.isEmpty()) {
                    int loc = Compute.randomInt(airborne.size());
                    Entity en = gameManager.game.getEntity(airborne.get(loc));
                    int damage = Math.max(1, Compute.d6() / 2) + damage_bonus;
                    while (damage > 0) {
                        HitData hit = en.rollHitLocation(ToHitData.HIT_NORMAL, ToHitData.SIDE_RANDOM);
                        vFullReport.addAll(gameManager.damageEntity(en, hit, 1));
                        damage--;
                    }
                    unitsAffected--;
                    airborne.remove(loc);
                }
            }
        }
        Report.addNewline(gameManager.vPhaseReport);
        return vFullReport;
    }

    /**
     * cycle through entities on team and collect all the airborne VTOL/WIGE
     *
     * @return a vector of relevant entity ids
     * @param team
     * @param gameManager
     */
    public Vector<Integer> getAirborneVTOL(Team team, GameManager gameManager) {
        Vector<Integer> units = new Vector<>();
        for (Entity entity : gameManager.game.getEntitiesVector()) {
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
     *  @param entity the <code>Entity</code> that should lay a mine
     * @param mineId an <code>int</code> pointing to the mine
     * @param coords
     * @param gameManager
     */
    protected void layMine(Entity entity, int mineId, Coords coords, GameManager gameManager) {
        Mounted mine = entity.getEquipment(mineId);
        Report r;
        if (!mine.isMissing()) {
            int reportId = 0;
            switch (mine.getMineType()) {
                case Mounted.MINE_CONVENTIONAL:
                    deliverThunderMinefield(coords, entity.getOwnerId(), 10,
                            entity.getId(), gameManager);
                    reportId = 3500;
                    break;
                case Mounted.MINE_VIBRABOMB:
                    deliverThunderVibraMinefield(coords, entity.getOwnerId(), 10,
                            mine.getVibraSetting(), entity.getId(), gameManager);
                    reportId = 3505;
                    break;
                case Mounted.MINE_ACTIVE:
                    deliverThunderActiveMinefield(coords, entity.getOwnerId(), 10,
                            entity.getId(), gameManager);
                    reportId = 3510;
                    break;
                case Mounted.MINE_INFERNO:
                    deliverThunderInfernoMinefield(coords, entity.getOwnerId(), 10,
                            entity.getId(), gameManager);
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
            gameManager.addReport(r);
            entity.setLayingMines(true);
        }
    }
}
