package megamek.server.gameManager;

import megamek.common.Entity;
import megamek.common.Player;
import megamek.common.Report;
import megamek.common.Team;
import megamek.server.ServerReportsHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BVCountHelper {
    int bv;
    int bvInitial;
    int bvFled;
    int unitsCount;
    int unitsInitialCount;
    int unitsLightDamageCount;
    int unitsModerateDamageCount;
    int unitsHeavyDamageCount;
    int unitsCrippledCount;
    int unitsDestroyedCount;
    int unitsCrewEjectedCount;
    int unitsCrewTrappedCount;
    int unitsCrewKilledCount;
    int unitsFledCount;
    int ejectedCrewActiveCount;
    int ejectedCrewPickedUpByTeamCount;
    int ejectedCrewPickedUpByEnemyTeamCount;
    int ejectedCrewKilledCount;
    int ejectedCrewFledCount;

    public BVCountHelper() {
        this.bv = 0;
        this.bvInitial = 0;
        this.bvFled = 0;
        this.unitsCount = 0;
        this.unitsInitialCount = 0;
        this.unitsLightDamageCount = 0;
        this.unitsModerateDamageCount = 0;
        this.unitsHeavyDamageCount = 0;
        this.unitsCrippledCount = 0;
        this.unitsDestroyedCount = 0;
        this.unitsCrewEjectedCount = 0;
        this.unitsCrewTrappedCount = 0;
        this.unitsCrewKilledCount = 0;
        this.unitsFledCount = 0;
        this.ejectedCrewActiveCount = 0;
        this.ejectedCrewPickedUpByTeamCount = 0;
        this.ejectedCrewPickedUpByEnemyTeamCount = 0;
        this.ejectedCrewKilledCount = 0;
        this.ejectedCrewFledCount = 0;
    }

    public void bvReports(boolean checkBlind, GameManager gameManager) {
        List<Report> playerReport = new ArrayList<>();
        List<Report> teamReport = new ArrayList<>();
        HashMap<Integer, BVCountHelper> teamsInfo = new HashMap<>();

        for (Team team : gameManager.game.getTeams()) {
            teamsInfo.put(team.getId(), new BVCountHelper());
        }

        // blank line
        gameManager.addReport(new Report(1210, Report.PUBLIC));

        // Show player BVs
        for (Player player : gameManager.game.getPlayersList()) {
            // Observers without initial entities get ignored
            if (player.isObserver() && (player.getInitialEntityCount() == 0)) {
                continue;
            }

            BVCountHelper bvcPlayer = new BVCountHelper();
            bvcPlayer.bv = player.getBV();
            bvcPlayer.bvInitial = player.getInitialBV();
            bvcPlayer.bvFled = ServerReportsHelper.getFledBV(player, gameManager.game);
            bvcPlayer.unitsCount = ServerReportsHelper.getUnitCount(player, gameManager.game);
            bvcPlayer.unitsInitialCount = player.getInitialEntityCount();
            bvcPlayer.unitsLightDamageCount = ServerReportsHelper.getUnitDamageCount(player, Entity.DMG_LIGHT, gameManager.game);
            bvcPlayer.unitsModerateDamageCount = ServerReportsHelper.getUnitDamageCount(player, Entity.DMG_MODERATE, gameManager.game);
            bvcPlayer.unitsHeavyDamageCount = ServerReportsHelper.getUnitDamageCount(player, Entity.DMG_HEAVY, gameManager.game);
            bvcPlayer.unitsCrippledCount = ServerReportsHelper.getUnitDamageCount(player, Entity.DMG_CRIPPLED, gameManager.game);
            bvcPlayer.unitsDestroyedCount =  ServerReportsHelper.getUnitDestroyedCount(player, gameManager.game);
            bvcPlayer.unitsCrewEjectedCount = ServerReportsHelper.getUnitCrewEjectedCount(player, gameManager.game);
            bvcPlayer.unitsCrewTrappedCount = ServerReportsHelper.getUnitCrewTrappedCount(player, gameManager.game);
            bvcPlayer.unitsCrewKilledCount = ServerReportsHelper.getUnitCrewKilledCount(player, gameManager.game);
            bvcPlayer.unitsFledCount = ServerReportsHelper.getFledUnitsCount(player, gameManager.game);
            bvcPlayer.ejectedCrewActiveCount = ServerReportsHelper.getEjectedCrewCount(player, gameManager.game);
            bvcPlayer.ejectedCrewPickedUpByTeamCount =  ServerReportsHelper.getEjectedCrewPickedUpByTeamCount(player, gameManager.game);
            bvcPlayer.ejectedCrewPickedUpByEnemyTeamCount = ServerReportsHelper.getEjectedCrewPickedUpByEnemyTeamCount(player, gameManager.game);
            bvcPlayer.ejectedCrewKilledCount = ServerReportsHelper.getEjectedCrewKilledCount(player, gameManager.game);
            bvcPlayer.ejectedCrewFledCount = ServerReportsHelper.getFledEjectedCrew(player, gameManager.game);

            playerReport.addAll(bvcPlayer.bvReport(player.getColorForPlayer(), player.getId(), checkBlind, gameManager));

            int playerTeam = player.getTeam();

            if ((playerTeam != Player.TEAM_UNASSIGNED) && (playerTeam != Player.TEAM_NONE)) {
                BVCountHelper bvcTeam = teamsInfo.get(playerTeam);
                bvcTeam.bv += bvcPlayer.bv;
                bvcTeam.bvInitial += bvcPlayer.bvInitial;
                bvcTeam.bvFled += bvcPlayer.bvFled;
                bvcTeam.unitsCount += bvcPlayer.unitsCount;
                bvcTeam.unitsInitialCount += bvcPlayer.unitsInitialCount;
                bvcTeam.unitsLightDamageCount += bvcPlayer.unitsLightDamageCount;
                bvcTeam.unitsModerateDamageCount += bvcPlayer.unitsModerateDamageCount;
                bvcTeam.unitsHeavyDamageCount += bvcPlayer.unitsHeavyDamageCount;
                bvcTeam.unitsCrippledCount += bvcPlayer.unitsCrippledCount;
                bvcTeam.unitsDestroyedCount += bvcPlayer.unitsDestroyedCount;
                bvcTeam.unitsCrewEjectedCount += bvcPlayer.unitsCrewEjectedCount;
                bvcTeam.unitsCrewTrappedCount += bvcPlayer.unitsCrewTrappedCount;
                bvcTeam.unitsCrewKilledCount += bvcPlayer.unitsCrewKilledCount;
                bvcTeam.unitsFledCount += bvcPlayer.unitsFledCount;
                bvcTeam.ejectedCrewActiveCount += bvcPlayer.ejectedCrewActiveCount;
                bvcTeam.ejectedCrewPickedUpByTeamCount += bvcPlayer.ejectedCrewPickedUpByTeamCount;
                bvcTeam.ejectedCrewPickedUpByEnemyTeamCount += bvcPlayer.ejectedCrewPickedUpByEnemyTeamCount;
                bvcTeam.ejectedCrewKilledCount += bvcPlayer.ejectedCrewKilledCount;
                bvcTeam.ejectedCrewFledCount += bvcPlayer.ejectedCrewFledCount;
            }
        }

        // Show teams BVs
        if (!(checkBlind && gameManager.environmentalEffectManager.doBlind(gameManager) && gameManager.environmentalEffectManager.suppressBlindBV(gameManager))) {
            for (Map.Entry<Integer, BVCountHelper> e : teamsInfo.entrySet()) {
                BVCountHelper bvc = e.getValue();
                teamReport.addAll(bvc.bvReport(Player.TEAM_NAMES[e.getKey()], Player.PLAYER_NONE, false, gameManager));
            }
        }

        gameManager.vPhaseReport.addAll(teamReport);
        gameManager.vPhaseReport.addAll(playerReport);
    }

    List<Report> bvReport(String name, int playerID, boolean checkBlind, GameManager gameManager) {
        List<Report> result = new ArrayList<>();

        Report r = new Report(7016, Report.PUBLIC);
        r.add(name);
        result.add(r);

        r = new Report(7017, Report.PUBLIC);
        if (checkBlind && gameManager.environmentalEffectManager.doBlind(gameManager) && gameManager.environmentalEffectManager.suppressBlindBV(gameManager)) {
            r.type = Report.PLAYER;
            r.player = playerID;
        }
        r.add(bv);
        r.add(bvInitial);
        r.add(Double.toString(Math.round(((double) bv / bvInitial) * 10000.0) / 100.0));
        r.add(bvFled);
        r.indent(2);
        result.add(r);

        r = new Report(7018, Report.PUBLIC);
        if (checkBlind && gameManager.environmentalEffectManager.doBlind(gameManager) && gameManager.environmentalEffectManager.suppressBlindBV(gameManager)) {
            r.type = Report.PLAYER;
            r.player = playerID;
        }
        r.add(unitsCount);
        r.add(unitsInitialCount);
        r.add(Double.toString(Math.round(((double) unitsCount / unitsInitialCount) * 10000.0) / 100.0));
        r.indent(2);
        result.add(r);

        if (unitsLightDamageCount + unitsModerateDamageCount + unitsHeavyDamageCount +
                unitsCrippledCount + unitsDestroyedCount + unitsFledCount + unitsCrewEjectedCount +
                unitsCrewKilledCount > 0) {
            r = new Report(7019, Report.PUBLIC);
            if (checkBlind && gameManager.environmentalEffectManager.doBlind(gameManager) && gameManager.environmentalEffectManager.suppressBlindBV(gameManager)) {
                r.type = Report.PLAYER;
                r.player = playerID;
            }
            r.add(unitsLightDamageCount > 0 ? r.warning(unitsLightDamageCount + "") : unitsLightDamageCount + "");
            r.add(unitsModerateDamageCount > 0 ? r.warning(unitsModerateDamageCount + "") : unitsModerateDamageCount + "");
            r.add(unitsHeavyDamageCount > 0 ? r.warning(unitsHeavyDamageCount + "") : unitsHeavyDamageCount + "");
            r.add(unitsCrippledCount > 0 ? r.warning(unitsCrippledCount + "") : unitsCrippledCount + "");
            r.add(unitsDestroyedCount > 0 ? r.warning(unitsDestroyedCount + "") : unitsDestroyedCount + "");
            r.add(unitsFledCount > 0 ? r.warning(unitsFledCount + "") : unitsFledCount + "");
            r.add(unitsCrewEjectedCount > 0 ? r.warning(unitsCrewEjectedCount + "") : unitsCrewEjectedCount + "");
            r.add(unitsCrewTrappedCount > 0 ? r.warning(unitsCrewTrappedCount + "") : unitsCrewTrappedCount + "");
            r.add(unitsCrewKilledCount > 0 ? r.warning(unitsCrewKilledCount + "") : unitsCrewKilledCount + "");
            r.indent(2);
            result.add(r);
        }

        if (unitsCrewEjectedCount > 0) {
            r = new Report(7020, Report.PUBLIC);
            if (checkBlind && gameManager.environmentalEffectManager.doBlind(gameManager) && gameManager.environmentalEffectManager.suppressBlindBV(gameManager)) {
                r.type = Report.PLAYER;
                r.player = playerID;
            }
            r.add(ejectedCrewActiveCount > 0 ? r.warning(ejectedCrewActiveCount + "") : ejectedCrewActiveCount + "");
            r.add(ejectedCrewPickedUpByTeamCount > 0 ? r.warning(ejectedCrewPickedUpByTeamCount + "") : ejectedCrewPickedUpByTeamCount + "");
            r.add(ejectedCrewPickedUpByEnemyTeamCount > 0 ? r.warning(ejectedCrewPickedUpByEnemyTeamCount + "") : ejectedCrewPickedUpByEnemyTeamCount + "");
            r.add(ejectedCrewKilledCount > 0 ? r.warning(ejectedCrewKilledCount + "") : ejectedCrewKilledCount + "");
            r.add(ejectedCrewFledCount > 0 ? r.warning(ejectedCrewFledCount + "") : ejectedCrewFledCount + "");
            r.indent(2);
            result.add(r);
        }

        // blank line
        result.add(new Report(1210, Report.PUBLIC));

        return result;
    }
}
