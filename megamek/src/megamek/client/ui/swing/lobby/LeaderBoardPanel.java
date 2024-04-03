package megamek.client.ui.swing.lobby;

import megamek.MMConstants;
import megamek.client.ui.Messages;
import megamek.client.ui.swing.ClientGUI;
import megamek.client.ui.swing.TableColumnManager;
import megamek.common.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static megamek.client.ui.swing.util.UIUtil.*;
import static megamek.client.ui.swing.util.UIUtil.scaleForGUI;

/**
 * A JPanel that holds a table giving an overview of the current relative strength
 * of the teams of the game. The table does not listen to game changes and requires
 * being notified through {@link #refreshData()}. It accesses data through the stored
 * ClientGUI.
 */

public class LeaderBoardPanel extends JPanel {
    private static final long serialVersionUID = -4754010220963493049L;
    private enum TOMCOLS { PLAYER, GAMESPLAYED, GAMESWON , SCORE, RANK  }
    private final LeaderboardModel leaderboardModel = new LeaderboardModel();
    private final JTable leaderboardTable = new JTable(leaderboardModel);
    private final TableColumnManager teamOverviewManager = new TableColumnManager(leaderboardTable, false);
    private final JScrollPane scr = new JScrollPane(leaderboardTable);
    private final ClientGUI clientGui;
    private boolean isDetached;
    private int shownColumn;

    /** Constructs the team overview panel; the given ClientGUI is used to access the game data. */
    public LeaderBoardPanel(ClientGUI cg) {
        clientGui = cg;
        setLayout(new GridLayout(1, 1));
        leaderboardTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        leaderboardTable.getSelectionModel().addListSelectionListener(e -> repaint());
        leaderboardTable.getTableHeader().setReorderingAllowed(false);
        var colModel = leaderboardTable.getColumnModel();
        var centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);

        scr.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scr);
        refreshData();
    }

    /** Refreshes the headers, setting the header names and gui scaling them. */
    public void refreshTableHeader() {
        JTableHeader header = leaderboardTable.getTableHeader();
        for (int i = 0; i < leaderboardTable.getColumnCount(); i++) {
            TableColumn column = leaderboardTable.getColumnModel().getColumn(i);
        }
        header.repaint();
    }

    /** Updates the table with data from the game. */
    public void refreshData() {
        int selectedRow = leaderboardTable.getSelectedRow();
        int selectedTeam = -1;
    }

    /** The table model for the Team overview panel */
    private class LeaderboardModel extends AbstractTableModel {
        private static final long serialVersionUID = 2747614890129092912L;
        private ArrayList<Team> teams = new ArrayList<>();

        @Override
        public int getRowCount() {
            return teams.size();
        }

        public void clearData() {
            /** Loop in order to add anything needed to be cleared */
        }

        @Override
        public int getColumnCount() {
            return TOMCOLS.values().length;
        }

        /** Updates the stored data from the provided game. */
        public void updateTable(Game game) {
            clearData();

            /** Loop in order to add data */

            leaderboardTable.clearSelection();
            fireTableDataChanged();
            updateRowHeights();
        }

        /** Finds and sets the required row height (max height of all cells plus margin). */
        private void updateRowHeights()
        {
            /** Loop in order to add the correct number of rows */
        }

        @Override
        public String getColumnName(int column) {
            column += (isDetached && column > 1) ? 2 : 0;
            String text = Messages.getString("ChatLounge.teamOverview.COL" + TOMCOLS.values()[column]);
            float textSizeDelta = isDetached ? 0f : 0.3f;
            return "<HTML><NOBR>" + guiScaledFontHTML(textSizeDelta) + text;
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return getValueAt(0, c).getClass();
        }

        @Override
        public Object getValueAt(int row, int col) {
            float textSizeDelta = isDetached ? -0.1f : 0.2f;
            StringBuilder result = new StringBuilder("<HTML><NOBR>");
            TOMCOLS column = TOMCOLS.values()[col];

            /** Get Values for each column   **/
            switch (column) {
                case PLAYER:
                    /** result.append("Player 1"); **/
                    break;
                case GAMESPLAYED:
                    /** result.append("10"); **/
                    break;
                case GAMESWON:
                    /** result.append("5"); **/
                    break;
                case SCORE:
                    /** result.append("1000"); **/
                    break;
                case RANK:
                    /** result.append("1"); **/
                    break;
            }

            return result.toString();
        }

    }

    private class MemberListRenderer extends JPanel implements TableCellRenderer {
        private static final long serialVersionUID = 6379065972840999336L;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {

            if (!(value instanceof List<?>)) {
                return null;
            }
            removeAll();
            add(Box.createVerticalGlue());
            List<?> playerList = (List<?>) value;
            int baseSize = FONT_SCALE1 - (isDetached ? 2 : 0);
            int size = scaleForGUI(2 * baseSize);
            Font font = new Font(MMConstants.FONT_DIALOG, Font.PLAIN, scaleForGUI(baseSize));
            for (Object obj : playerList) {
                if (!(obj instanceof Player)) {
                    continue;
                }
                Player player = (Player) obj;
                JLabel lblPlayer = new JLabel(player.getName());
                lblPlayer.setBorder(new EmptyBorder(3, 3, 3, 3));
                lblPlayer.setFont(font);
                lblPlayer.setIconTextGap(scaleForGUI(5));
                Image camo = player.getCamouflage().getImage();
                lblPlayer.setIcon(new ImageIcon(camo.getScaledInstance(-1, size, Image.SCALE_SMOOTH)));
                add(lblPlayer);
            }
            add(Box.createVerticalGlue());

            if (isSelected) {
                setForeground(table.getSelectionForeground());
                setBackground(table.getSelectionBackground());
            } else {
                setForeground(table.getForeground());
                setBackground(table.getBackground());
            }
            return this;
        }
    }
}
