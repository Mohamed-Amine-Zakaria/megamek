package megamek.server.gameManager;

import megamek.client.ui.swing.tooltip.UnitToolTip;
import megamek.common.*;
import megamek.common.options.OptionsConstants;
import megamek.server.UnitStatusFormatter;
import org.apache.logging.log4j.LogManager;

import java.util.*;
import java.util.stream.Collectors;

public class ReportManager {
    /**
     * Writes the victory report
     * @param gameManager
     */
    protected void prepareVictoryReport(GameManager gameManager) {
        // remove carcasses to the graveyard
        Vector<Entity> toRemove = new Vector<>();
        for (Entity e : gameManager.game.getEntitiesVector()) {
            if (e.isCarcass() && !e.isDestroyed()) {
                toRemove.add(e);
            }
        }
        for (Entity e : toRemove) {
            gameManager.entityActionManager.destroyEntity(e, "crew death", false, true, gameManager);
            gameManager.game.removeEntity(e.getId(), IEntityRemovalConditions.REMOVE_SALVAGEABLE);
            e.setDestroyed(true);
        }

        gameManager.addReport(new Report(7000, Report.PUBLIC));

        // Declare the victor
        Report r = new Report(1210);
        r.type = Report.PUBLIC;
        if (gameManager.game.getVictoryTeam() == Player.TEAM_NONE) {
            Player player = gameManager.game.getPlayer(gameManager.game.getVictoryPlayerId());
            if (null == player) {
                r.messageId = 7005;
            } else {
                r.messageId = 7010;
                r.add(player.getColorForPlayer());
            }
        } else {
            // Team victory
            r.messageId = 7015;
            r.add(gameManager.game.getVictoryTeam());
        }
        gameManager.addReport(r);

        gameManager.bvCount.bvReports(false, gameManager);

        // List the survivors
        Iterator<Entity> survivors = gameManager.game.getEntities();
        if (survivors.hasNext()) {
            gameManager.addReport(new Report(7023, Report.PUBLIC));
            while (survivors.hasNext()) {
                Entity entity = survivors.next();

                if (!entity.isDeployed()) {
                    continue;
                }

                gameManager.reportManager.addReport(entity.victoryReport(), gameManager);
            }
        }
        // List units that never deployed
        Iterator<Entity> undeployed = gameManager.game.getEntities();
        if (undeployed.hasNext()) {
            boolean wroteHeader = false;

            while (undeployed.hasNext()) {
                Entity entity = undeployed.next();

                if (entity.isDeployed()) {
                    continue;
                }

                if (!wroteHeader) {
                    gameManager.addReport(new Report(7075, Report.PUBLIC));
                    wroteHeader = true;
                }

                gameManager.reportManager.addReport(entity.victoryReport(), gameManager);
            }
        }
        // List units that retreated
        Enumeration<Entity> retreat = gameManager.game.getRetreatedEntities();
        if (retreat.hasMoreElements()) {
            gameManager.addReport(new Report(7080, Report.PUBLIC));
            while (retreat.hasMoreElements()) {
                Entity entity = retreat.nextElement();
                gameManager.reportManager.addReport(entity.victoryReport(), gameManager);
            }
        }
        // List destroyed units
        Enumeration<Entity> graveyard = gameManager.game.getGraveyardEntities();
        if (graveyard.hasMoreElements()) {
            gameManager.addReport(new Report(7085, Report.PUBLIC));
            while (graveyard.hasMoreElements()) {
                Entity entity = graveyard.nextElement();
                gameManager.reportManager.addReport(entity.victoryReport(), gameManager);
            }
        }
        // List devastated units (not salvageable)
        Enumeration<Entity> devastated = gameManager.game.getDevastatedEntities();
        if (devastated.hasMoreElements()) {
            gameManager.addReport(new Report(7090, Report.PUBLIC));

            while (devastated.hasMoreElements()) {
                Entity entity = devastated.nextElement();
                gameManager.reportManager.addReport(entity.victoryReport(), gameManager);
            }
        }
        // Let player know about entitystatus.txt file
        gameManager.addReport(new Report(7095, Report.PUBLIC));
    }

    /**
     * Generates a detailed report for campaign use
     * @param gameManager
     */
    protected String getDetailedVictoryReport(GameManager gameManager) {
        StringBuilder sb = new StringBuilder();

        Vector<Entity> vAllUnits = new Vector<>();
        for (Iterator<Entity> i = gameManager.game.getEntities(); i.hasNext(); ) {
            vAllUnits.addElement(i.next());
        }

        for (Enumeration<Entity> i = gameManager.game.getRetreatedEntities(); i.hasMoreElements(); ) {
            vAllUnits.addElement(i.nextElement());
        }

        for (Enumeration<Entity> i = gameManager.game.getGraveyardEntities(); i.hasMoreElements(); ) {
            vAllUnits.addElement(i.nextElement());
        }

        for (Enumeration<Player> i = gameManager.game.getPlayers(); i.hasMoreElements(); ) {
            // Record the player.
            Player p = i.nextElement();
            sb.append("++++++++++ ").append(p.getName()).append(" ++++++++++\n");

            // Record the player's alive, retreated, or salvageable units.
            for (int x = 0; x < vAllUnits.size(); x++) {
                Entity e = vAllUnits.elementAt(x);
                if (e.getOwner() == p) {
                    sb.append(UnitStatusFormatter.format(e));
                }
            }

            // Record the player's devastated units.
            Enumeration<Entity> devastated = gameManager.game.getDevastatedEntities();
            if (devastated.hasMoreElements()) {
                sb.append("=============================================================\n");
                sb.append("The following utterly destroyed units are not available for salvage:\n");
                while (devastated.hasMoreElements()) {
                    Entity e = devastated.nextElement();
                    if (e.getOwner() == p) {
                        sb.append(e.getShortName());
                        for (int pos = 0; pos < e.getCrew().getSlotCount(); pos++) {
                            sb.append(", ").append(e.getCrew().getNameAndRole(pos)).append(" (")
                                    .append(e.getCrew().getGunnery()).append('/')
                                    .append(e.getCrew().getPiloting()).append(")\n");
                        }
                    }
                }
                sb.append("=============================================================\n");
            }
        }

        return sb.toString();
    }

    protected void entityStatusReport(GameManager gameManager) {
        if (gameManager.game.getOptions().booleanOption(OptionsConstants.BASE_SUPPRESS_UNIT_TOOLTIP_IN_REPORT_LOG)) {
            return;
        }

        List<Report> reports = new ArrayList<>();
        List<Entity> entities = gameManager.game.getEntitiesVector().stream()
                .filter(e -> (e.isDeployed() && e.getPosition() != null))
                .collect(Collectors.toList());
        Comparator<Entity> comp = Comparator.comparing((Entity e) -> e.getOwner().getTeam());
        comp = comp.thenComparing((Entity e) -> e.getOwner().getName());
        comp = comp.thenComparing((Entity e) -> e.getDisplayName());
        entities.sort(comp);

        // turn off preformatted text for unit tool tip
        Report r = new Report(1230, Report.PUBLIC);
        r.add("</pre>");
        reports.add(r);

        r = new Report(7600);
        reports.add(r);

        for (Entity e : entities) {
            r = new Report(1231);
            r.subject = e.getId();
            r.addDesc(e);
            String etr = "";
            try {
                etr = UnitToolTip.getEntityTipReport(e).toString();
            } catch (Exception ex) {
                LogManager.getLogger().error("", ex);
            }
            r.add(etr);
            reports.add(r);

            r = new Report(1230, Report.PUBLIC);
            r.add("<BR>");
            reports.add(r);
        }

        // turn preformatted text back on, so that text after will display properly
        r = new Report(1230, Report.PUBLIC);
        r.add("<pre>");
        reports.add(r);

        gameManager.vPhaseReport.addAll(reports);
    }

    /**
     * New Round has started clear everyone's report queue
     * @param gameManager
     */
    void clearReports(GameManager gameManager) {
        gameManager.vPhaseReport.removeAllElements();
    }

    public Vector<Report> getvPhaseReport(GameManager gameManager) {
        return gameManager.vPhaseReport;
    }

    /**
     * Add a whole lotta Reports to the players report queues as well as the
     * Master report queue vPhaseReport.
     * @param reports
     * @param gameManager
     */
    protected void addReport(Vector<Report> reports, GameManager gameManager) {
        gameManager.vPhaseReport.addAll(reports);
    }

    /**
     * Add a whole lotta Reports to the players report queues as well as the
     * Master report queue vPhaseReport, indenting each report by the passed
     * value.
     * @param reports
     * @param indents
     * @param gameManager
     */
    protected void addReport(Vector<Report> reports, int indents, GameManager gameManager) {
        for (Report r : reports) {
            r.indent(indents);
            gameManager.vPhaseReport.add(r);
        }
    }

    /**
     * Write the initiative results to the report
     * @param abbreviatedReport
     * @param gameManager
     */
    protected void writeInitiativeReport(boolean abbreviatedReport, GameManager gameManager) {
        // write to report
        Report r;
        boolean deployment = false;
        if (!abbreviatedReport) {
            r = new Report(1210);
            r.type = Report.PUBLIC;
            if (gameManager.game.getLastPhase().isDeployment() || gameManager.game.isDeploymentComplete()
                    || !gameManager.game.shouldDeployThisRound()) {
                r.messageId = 1000;
                r.add(gameManager.game.getRoundCount());
            } else {
                deployment = true;
                if (gameManager.game.getRoundCount() == 0) {
                    r.messageId = 1005;
                } else {
                    r.messageId = 1010;
                    r.add(gameManager.game.getRoundCount());
                }
            }
            gameManager.addReport(r);
            // write separator
            gameManager.addReport(new Report(1200, Report.PUBLIC));
        } else {
            gameManager.addReport(new Report(1210, Report.PUBLIC));
        }

        if (gameManager.game.getOptions().booleanOption(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE)) {
            r = new Report(1040, Report.PUBLIC);
            gameManager.addReport(r);
            for (Enumeration<GameTurn> e = gameManager.game.getTurns(); e.hasMoreElements(); ) {
                GameTurn t = e.nextElement();
                if (t instanceof GameTurn.SpecificEntityTurn) {
                    Entity entity = gameManager.game.getEntity(((GameTurn.SpecificEntityTurn) t).getEntityNum());
                    if (entity.getDeployRound() <= gameManager.game.getRoundCount()) {
                        r = new Report(1045);
                        r.subject = entity.getId();
                        r.addDesc(entity);
                        r.add(entity.getInitiative().toString());
                        gameManager.addReport(r);
                    }
                } else {
                    Player player = gameManager.game.getPlayer(t.getPlayerNum());
                    if (null != player) {
                        r = new Report(1050, Report.PUBLIC);
                        r.add(player.getColorForPlayer());
                        gameManager.addReport(r);
                    }
                }
            }
        } else {
            for (Team team : gameManager.game.getTeams()) {
                // Teams with no active players can be ignored
                if (team.isObserverTeam()) {
                    continue;
                }

                // If there is only one non-observer player, list
                // them as the 'team', and use the team initiative
                if (team.getNonObserverSize() == 1) {
                    final Player player = team.nonObserverPlayers().get(0);
                    r = new Report(1015, Report.PUBLIC);
                    r.add(player.getColorForPlayer());
                    r.add(team.getInitiative().toString());
                    gameManager.addReport(r);
                } else {
                    // Multiple players. List the team, then break it down.
                    r = new Report(1015, Report.PUBLIC);
                    r.add(Player.TEAM_NAMES[team.getId()]);
                    r.add(team.getInitiative().toString());
                    gameManager.addReport(r);
                    for (Player player : team.nonObserverPlayers()) {
                        r = new Report(1015, Report.PUBLIC);
                        r.indent();
                        r.add(player.getName());
                        r.add(player.getInitiative().toString());
                        gameManager.addReport(r);
                    }
                }
            }

            if (!gameManager.doBlind()) {
                // The turn order is different in movement phase
                // if a player has any "even" moving units.
                r = new Report(1020, Report.PUBLIC);

                boolean hasEven = false;
                for (Enumeration<GameTurn> i = gameManager.game.getTurns(); i.hasMoreElements(); ) {
                    GameTurn turn = i.nextElement();
                    Player player = gameManager.game.getPlayer(turn.getPlayerNum());
                    if (null != player) {
                        r.add(player.getName());
                        if (player.getEvenTurns() > 0) {
                            hasEven = true;
                        }
                    }
                }
                r.newlines = 2;
                gameManager.addReport(r);
                if (hasEven) {
                    r = new Report(1021, Report.PUBLIC);
                    if ((gameManager.game.getOptions().booleanOption(OptionsConstants.INIT_INF_DEPLOY_EVEN)
                            || gameManager.game.getOptions().booleanOption(OptionsConstants.INIT_PROTOS_MOVE_EVEN))
                            && !gameManager.game.getLastPhase().isEndReport()) {
                        r.choose(true);
                    } else {
                        r.choose(false);
                    }
                    r.indent();
                    r.newlines = 2;
                    gameManager.addReport(r);
                }
            }
        }

        gameManager.addNewLines();

        if (!abbreviatedReport) {
            // remaining deployments
            Comparator<Entity> comp = Comparator.comparingInt(Entity::getDeployRound);
            comp = comp.thenComparingInt(Entity::getOwnerId);
            comp = comp.thenComparingInt(Entity::getStartingPos);
            List<Entity> ue = gameManager.game.getEntitiesVector().stream().filter(e -> e.getDeployRound() > gameManager.game.getRoundCount()).sorted(comp).collect(Collectors.toList());
            if (!ue.isEmpty()) {
                r = new Report(1060, Report.PUBLIC);
                gameManager.addReport(r);
                int round = -1;

                for (Entity entity : ue) {
                    if (round != entity.getDeployRound()) {
                        round = entity.getDeployRound();
                        r = new Report(1065, Report.PUBLIC);
                        r.add(round);
                        gameManager.addReport(r);
                    }

                    r = new Report(1066);
                    r.subject = entity.getId();
                    r.addDesc(entity);
                    String s = IStartingPositions.START_LOCATION_NAMES[entity.getStartingPos()];
                    r.add(s);
                    gameManager.addReport(r);
                }

                r = new Report(1210, Report.PUBLIC);
                r.newlines = 2;
                gameManager.addReport(r);
            }

            // we don't much care about wind direction and such in a hard vacuum
            if (!gameManager.game.getBoard().inSpace()) {
                // Wind direction and strength
                Report rWindDir = new Report(1025, Report.PUBLIC);
                rWindDir.add(gameManager.game.getPlanetaryConditions().getWindDirDisplayableName());
                rWindDir.newlines = 0;
                Report rWindStr = new Report(1030, Report.PUBLIC);
                rWindStr.add(gameManager.game.getPlanetaryConditions().getWindDisplayableName());
                rWindStr.newlines = 0;
                Report rWeather = new Report(1031, Report.PUBLIC);
                rWeather.add(gameManager.game.getPlanetaryConditions().getWeatherDisplayableName());
                rWeather.newlines = 0;
                Report rLight = new Report(1032, Report.PUBLIC);
                rLight.add(gameManager.game.getPlanetaryConditions().getLightDisplayableName());
                Report rVis = new Report(1033, Report.PUBLIC);
                rVis.add(gameManager.game.getPlanetaryConditions().getFogDisplayableName());
                gameManager.addReport(rWindDir);
                gameManager.addReport(rWindStr);
                gameManager.addReport(rWeather);
                gameManager.addReport(rLight);
                gameManager.addReport(rVis);
            }

            if (deployment) {
                gameManager.addNewLines();
            }
        }
    }

    void reportLargeCraftECCMRolls(GameManager gameManager) {
        // run through an enumeration of deployed game entities. If they are
        // large craft in space, then check the roll
        // and report it
        if (!gameManager.game.getBoard().inSpace()
                || !gameManager.game.getOptions().booleanOption(OptionsConstants.ADVAERORULES_STRATOPS_ECM)) {
            return;
        }
        Report r;
        for (Iterator<Entity> e = gameManager.game.getEntities(); e.hasNext(); ) {
            Entity ent = e.next();
            if (ent.isDeployed() && ent.isLargeCraft()) {
                r = new Report(3635);
                r.subject = ent.getId();
                r.addDesc(ent);
                int target = ((Aero) ent).getECCMTarget();
                int roll = ((Aero) ent).getECCMRoll();
                r.add(roll);
                r.add(target);
                int mod = ((Aero) ent).getECCMBonus();
                r.add(mod);
                gameManager.addReport(r);
            }
        }
    }
}
