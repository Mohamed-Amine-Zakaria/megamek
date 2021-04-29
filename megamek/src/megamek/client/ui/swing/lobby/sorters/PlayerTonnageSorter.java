/*
 * Copyright (c) 2021 - The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MegaMek. If not, see <http://www.gnu.org/licenses/>.
 */
package megamek.client.ui.swing.lobby.sorters;

import megamek.client.ui.swing.ClientGUI;
import megamek.client.ui.swing.lobby.MekTableModel;
import megamek.common.Entity;
import megamek.common.IPlayer;

/** A Lobby Mek Table sorter that sorts by 1) player 2) tonnage. */
public class PlayerTonnageSorter implements MekTableSorter {

    private ClientGUI clientGui;
    private Sorting direction;

    /** A Lobby Mek Table sorter that sorts by 1) player 2) tonnage. */
    public PlayerTonnageSorter(ClientGUI cg, Sorting dir) {
        clientGui = cg;
        direction = dir;
    }

    @Override
    public int compare(final Entity a, final Entity b) {
        final IPlayer p_a = clientGui.getClient().getGame().getPlayer(a.getOwnerId());
        final IPlayer p_b = clientGui.getClient().getGame().getPlayer(b.getOwnerId());
        final IPlayer localPlayer = clientGui.getClient().getLocalPlayer();
        final int t_a = p_a.getTeam();
        final int t_b = p_b.getTeam();
        if (p_a.equals(localPlayer) && !p_b.equals(localPlayer)) {
            return -1;
        } else if (!p_a.equals(localPlayer) && p_b.equals(localPlayer)) {
            return 1;
        } else if ((t_a == localPlayer.getTeam()) && (t_b != localPlayer.getTeam())) {
            return -1;
        } else if ((t_b == localPlayer.getTeam()) && (t_a != localPlayer.getTeam())) {
            return 1;
        } else if (t_a != t_b) {
            return t_a - t_b;
        } else if (!p_a.equals(p_b)) {
            return p_a.getName().compareTo(p_b.getName());
        } else {
            double aWeight = a.getWeight();
            double bWeight = b.getWeight();
            if (bWeight > aWeight) {
                return smaller(direction);
            } else if (bWeight < aWeight) {
                return bigger(direction);
            } else {
                return 0;
            }
        }
    }

    @Override
    public String getDisplayName() {
        return "Player, Tonnage";
    }

    @Override
    public int getColumnIndex() {
        return MekTableModel.COL_UNIT;
    }

    @Override
    public Sorting getSortingDirection() {
        return direction;
    }
}
