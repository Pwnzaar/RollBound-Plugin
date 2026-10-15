package com.rollbound.ui;

import com.rollbound.RollBoundConfig;
import com.rollbound.drops.DropCache;
import com.rollbound.drops.DropItem;
import com.rollbound.drops.DropTableSection;
import com.rollbound.drops.NpcDropData;
import com.rollbound.managers.ObtainedItemsManager;
import net.runelite.client.game.ItemManager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * RollBound sidebar view for NPC drops.
 *
 * Drop items are displayed as RuneScape item icons. Items the shared RollBound
 * group has obtained are shown at full opacity; unobtained items are dimmed.
 */
public final class RollBoundDropsPanel extends JPanel
{
    private static final int GRID_COLUMNS = 4;
    private static final int ICON_SIZE = 32;
    private static final float UNOBTAINED_ALPHA = 0.28f;
    private static final Color OBTAINED_BORDER = new Color(74, 170, 92);
    private static final Color OBTAINED_BACKGROUND = new Color(38, 66, 43);
    private static final Color UNOBTAINED_BORDER = new Color(70, 70, 70);
    private static final Color UNOBTAINED_BACKGROUND = new Color(32, 32, 32);

    private final DropCache dropCache;
    private final NpcSearchService npcSearchService;
    private final ObtainedItemsManager obtainedItemsManager;
    private final ItemManager itemManager;
    private final RollBoundConfig config;

    private final JTextField searchField = new JTextField();
    private final JLabel statusLabel = new JLabel("Search for an NPC or right-click an NPC and choose Show Drops.");
    private final JProgressBar progressBar = new JProgressBar();
    private final JPanel content = new JPanel();
    private volatile NpcDropData currentData;

    public RollBoundDropsPanel(
            DropCache dropCache,
            NpcSearchService npcSearchService,
            ObtainedItemsManager obtainedItemsManager,
            ItemManager itemManager,
            RollBoundConfig config)
    {
        this.dropCache = dropCache;
        this.npcSearchService = npcSearchService;
        this.obtainedItemsManager = obtainedItemsManager;
        this.itemManager = itemManager;
        this.config = config;
        init();
    }

    private void init()
    {
        setLayout(new BorderLayout(0, 8));
        setOpaque(false);

        JPanel search = new JPanel(new BorderLayout(6, 0));
        search.setOpaque(false);
        searchField.setToolTipText("Search NPC names from the Old School RuneScape Wiki");
        searchField.addActionListener(e -> search());
        JButton button = new JButton("Search");
        button.addActionListener(e -> search());
        search.add(searchField, BorderLayout.CENTER);
        search.add(button, BorderLayout.EAST);

        statusLabel.setForeground(new Color(220, 220, 220));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 13f));
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        statusLabel.setAlignmentX(CENTER_ALIGNMENT);
        statusLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));

        progressBar.setStringPainted(true);
        progressBar.setBorderPainted(false);
        progressBar.setVisible(false);
        progressBar.setAlignmentX(CENTER_ALIGNMENT);
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        progressBar.setPreferredSize(new Dimension(180, 22));
        progressBar.setFont(progressBar.getFont().deriveFont(Font.BOLD, 13f));
        progressBar.setForeground(OBTAINED_BORDER);
        progressBar.setBackground(new Color(45, 45, 45));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(search);
        top.add(Box.createVerticalStrut(7));
        top.add(statusLabel);
        top.add(Box.createVerticalStrut(5));
        top.add(progressBar);
        add(top, BorderLayout.NORTH);

        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(content,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
    }

    private void search()
    {
        String query = searchField.getText() == null ? "" : searchField.getText().trim();
        if (query.isEmpty())
        {
            return;
        }

        currentData = null;
        progressBar.setVisible(false);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
        statusLabel.setForeground(new Color(180, 180, 180));
        statusLabel.setText("Searching…");
        content.removeAll();
        content.revalidate();
        content.repaint();

        npcSearchService.search(query).whenComplete((results, error) -> SwingUtilities.invokeLater(() ->
        {
            if (error != null)
            {
                statusLabel.setText("Could not search NPCs.");
                return;
            }
            if (results == null || results.isEmpty())
            {
                statusLabel.setText("No matching NPCs with drop tables found.");
                return;
            }

            statusLabel.setText("Select an NPC:");
            int shown = 0;
            for (NpcDropData npc : results)
            {
                String label = npc.getName() == null ? "NPC" : npc.getName();
                if (npc.getLevel() > 0)
                {
                    label += " (Lvl " + npc.getLevel() + ")";
                }

                JButton result = new JButton(label);
                result.setHorizontalAlignment(JButton.LEFT);
                result.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
                result.addActionListener(e -> showDrops(npc));
                content.add(result);
                content.add(Box.createVerticalStrut(3));
                if (++shown >= 20)
                {
                    break;
                }
            }
            content.revalidate();
            content.repaint();
        }));
    }

    public void showDrops(NpcDropData data)
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(() -> showDrops(data));
            return;
        }

        currentData = data;
        content.removeAll();
        if (data == null)
        {
            progressBar.setVisible(false);
            statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
            statusLabel.setForeground(new Color(180, 180, 180));
            statusLabel.setText("No drop data loaded.");
            content.revalidate();
            content.repaint();
            return;
        }

        String title = data.getName() == null ? "NPC" : data.getName();
        if (data.getLevel() > 0)
        {
            title += " (Lvl " + data.getLevel() + ")";
        }
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 14f));
        statusLabel.setForeground(new Color(220, 220, 220));
        statusLabel.setText(title);

        List<VisibleSection> visibleSections = buildVisibleSections(data.getDropTableSections());
        updateProgress(visibleSections);

        for (VisibleSection section : visibleSections)
        {
            addSection(section);
        }

        if (content.getComponentCount() == 0)
        {
            progressBar.setVisible(false);
            JLabel empty = new JLabel("No drop-table entries available.");
            empty.setForeground(new Color(180, 180, 180));
            content.add(empty);
        }
        content.revalidate();
        content.repaint();
    }

    public void refresh()
    {
        NpcDropData data = currentData;
        if (data != null)
        {
            showDrops(data);
        }
    }

    private boolean showSection(String header)
    {
        String value = header == null ? "" : header.toLowerCase(Locale.ROOT);
        if (!config.showRareDropTable() && value.contains("rare drop table"))
        {
            return false;
        }
        return config.showGemDropTable() || !value.contains("gem drop table");
    }

    private List<VisibleSection> buildVisibleSections(List<DropTableSection> sections)
    {
        List<VisibleSection> visible = new ArrayList<>();
        if (sections == null)
        {
            return visible;
        }

        for (DropTableSection section : sections)
        {
            if (section == null || !showSection(section.getHeader()))
            {
                continue;
            }

            List<DropItem> items = sortedValidItems(section);
            if (!items.isEmpty())
            {
                visible.add(new VisibleSection(
                        section.getHeader() == null ? "Drops" : section.getHeader(),
                        items));
            }
        }
        return visible;
    }

    private List<DropItem> sortedValidItems(DropTableSection section)
    {
        Map<Integer, DropItem> unique = new LinkedHashMap<>();
        if (section.getItems() != null)
        {
            for (DropItem item : section.getItems())
            {
                if (item != null && item.getItemId() > 0)
                {
                    unique.putIfAbsent(item.getItemId(), item);
                }
            }
        }

        List<DropItem> items = new ArrayList<>(unique.values());
        if (config.sortDropsByRarity())
        {
            items.sort(Comparator.comparingDouble(DropItem::getRarityValue)
                    .thenComparing(item -> item.getName() == null ? "" : item.getName()));
        }
        else
        {
            items.sort(Comparator.comparingInt(DropItem::getItemId));
        }
        return items;
    }

    private void updateProgress(List<VisibleSection> sections)
    {
        Set<Integer> uniqueIds = new LinkedHashSet<>();
        for (VisibleSection section : sections)
        {
            for (DropItem item : section.items)
            {
                uniqueIds.add(item.getItemId());
            }
        }

        int obtained = 0;
        for (int itemId : uniqueIds)
        {
            if (obtainedItemsManager.isObtained(itemId))
            {
                obtained++;
            }
        }

        int total = uniqueIds.size();
        progressBar.setMinimum(0);
        progressBar.setMaximum(Math.max(1, total));
        progressBar.setValue(obtained);
        progressBar.setString(obtained + "/" + total + " obtained");
        progressBar.setVisible(total > 0);
    }

    private void addSection(VisibleSection section)
    {
        JLabel heading = new JLabel(section.header);
        heading.setForeground(new Color(205, 205, 205));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 11f));
        heading.setBorder(BorderFactory.createEmptyBorder(9, 2, 4, 2));
        heading.setAlignmentX(LEFT_ALIGNMENT);
        content.add(heading);

        JPanel grid = new JPanel(new GridLayout(0, GRID_COLUMNS, 4, 4));
        grid.setOpaque(false);
        grid.setAlignmentX(LEFT_ALIGNMENT);
        grid.setBorder(BorderFactory.createEmptyBorder(0, 2, 2, 2));
        int rows = (section.items.size() + GRID_COLUMNS - 1) / GRID_COLUMNS;
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(ICON_SIZE + 8, rows * (ICON_SIZE + 8))));

        for (DropItem item : section.items)
        {
            grid.add(buildDropIcon(item));
        }

        content.add(grid);
    }

    private JPanel buildDropIcon(DropItem item)
    {
        boolean obtained = obtainedItemsManager.isObtained(item.getItemId());

        JPanel cell = new JPanel(new BorderLayout());
        cell.setOpaque(true);
        cell.setBackground(obtained ? OBTAINED_BACKGROUND : UNOBTAINED_BACKGROUND);
        cell.setBorder(BorderFactory.createLineBorder(
                obtained ? OBTAINED_BORDER : UNOBTAINED_BORDER,
                obtained ? 2 : 1));
        cell.setPreferredSize(new Dimension(44, 42));
        cell.setMinimumSize(new Dimension(44, 42));

        OpacityLabel icon = new OpacityLabel(obtained ? 1f : UNOBTAINED_ALPHA);
        icon.setHorizontalAlignment(SwingConstants.CENTER);
        icon.setVerticalAlignment(SwingConstants.CENTER);
        icon.setPreferredSize(new Dimension(ICON_SIZE, ICON_SIZE));
        icon.setToolTipText(buildTooltip(item, obtained));

        try
        {
            itemManager.getImage(item.getItemId()).addTo(icon);
        }
        catch (Exception ignored)
        {
            icon.setText("?");
            icon.setForeground(new Color(180, 180, 180));
        }

        cell.setToolTipText(icon.getToolTipText());
        cell.add(icon, BorderLayout.CENTER);
        return cell;
    }

    private String buildTooltip(DropItem item, boolean obtained)
    {
        String name = item.getName() == null || item.getName().isBlank()
                ? "Unknown item" : item.getName();
        String rarity = item.getOneOverRarity();
        if (rarity == null || rarity.isBlank())
        {
            rarity = "Unknown";
        }
        return "<html><b>" + escapeHtml(name) + "</b><br>Rate: " + escapeHtml(rarity)
                + "<br>" + (obtained ? "Obtained" : "Not obtained") + "</html>";
    }

    private static String escapeHtml(String text)
    {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static final class VisibleSection
    {
        private final String header;
        private final List<DropItem> items;

        private VisibleSection(String header, List<DropItem> items)
        {
            this.header = header;
            this.items = items;
        }
    }

    /** JLabel that applies alpha to the item icon while preserving normal Swing tooltips. */
    private static final class OpacityLabel extends JLabel
    {
        private final float alpha;

        private OpacityLabel(float alpha)
        {
            this.alpha = Math.max(0f, Math.min(1f, alpha));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics)
        {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try
            {
                g2.setComposite(AlphaComposite.SrcOver.derive(alpha));
                super.paintComponent(g2);
            }
            finally
            {
                g2.dispose();
            }
        }
    }
}
