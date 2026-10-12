package com.rollbound;

import com.rollbound.managers.ObtainedItemsManager;
import com.rollbound.managers.RollAnimationManager;
import com.rollbound.managers.RolledItemsManager;
import com.rollbound.managers.RolledByManager;
import com.rollbound.managers.RollHistoryManager;
import com.rollbound.firebase.FirebaseSyncService;
import com.rollbound.account.AccountGuardManager;
import com.rollbound.drops.DropCache;
import com.rollbound.drops.NpcDropData;
import com.rollbound.ui.RollBoundDropsPanel;
import com.rollbound.ui.NpcSearchService;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Panel for RollBound.
 * Shows a searchable list of items from rolled/obtained state. A dropdown selects which list:
 *  - Obtained
 *  - Rolled
 *  - Rolled, not Obtained
 *  - Usable (in both sets)
 * Items are shown newest-first based on underlying manager insertion order; for Usable,
 * ordering follows rolled recency.
 */
public class RollBoundPanel extends PluginPanel
{
    private final ObtainedItemsManager obtainedItemsManager;
    private final RolledItemsManager rolledItemsManager;
    private final RolledByManager rolledByManager;
    private final RollHistoryManager rollHistoryManager;
    private final ItemManager itemManager;
    private final HashSet<Integer> allTradeableItems;
    private final ClientThread clientThread;
    private final RollAnimationManager rollAnimationManager;

    private final RollBoundConfig config;
    private final FirebaseSyncService firebaseSyncService;
    private final AccountGuardManager accountGuardManager;
    private final String version;
    private final RollBoundDropsPanel dropsPanel;
    private final JTabbedPane mainTabs = new JTabbedPane();

    /** Called when the user clicks Manual Roll - triggers a synced group roll in the plugin. */
    private final Runnable manualGroupRoll;
    private final Runnable accountGuardChanged;

    private final Map<Integer, ImageIcon> itemIconCache = new ConcurrentHashMap<>();
    private final Map<Integer, String> itemNameCache = new ConcurrentHashMap<>();
    private final Set<Integer> nameFetchInFlight = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Integer> iconFetchInFlight = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final JLabel modeLabel = new JLabel("Unlocked Items");
    private final JLabel syncStatusLabel = new JLabel("Not synced");
    private final JLabel accountGuardStatusLabel = new JLabel("Account Guard: waiting for login");
    private final JButton accountGuardButton = new JButton("Enable this account");
    private final JTextField searchField = new JTextField();
    private final DefaultListModel<Integer> listModel = new DefaultListModel<>();
    private final JList<Integer> itemList = new JList<>(listModel);
    private final JLabel countLabel = new JLabel("0/0");
    private final JButton rollButton = new JButton("Manual Roll");

    // Header/footer polish
    private final JButton resetUiButton = new JButton("Reset UI");
    private final JLabel versionLabel = new JLabel();


    private enum ListMode
    {
        ROLLED("Group Unlocked"),
        OBTAINED("Group Obtained"),
        ROLLED_NOT_OBTAINED("Unlocked, not Obtained"),
        USABLE("Usable");

        private final String label;

        ListMode(String label)
        {
            this.label = label;
        }

        public String label()
        {
            return label;
        }

        @Override
        public String toString()
        {
            return label;
        }
    }

    private static final Map<ListMode, String> MODE_TOOLTIPS = Map.of(
            ListMode.ROLLED, "Items that have been unlocked by the group.",
            ListMode.OBTAINED, "Trigger items obtained by the group (drops that caused a group roll).",
            ListMode.ROLLED_NOT_OBTAINED, "Items unlocked by the group, but not yet obtained.",
            ListMode.USABLE, "Items that are both obtained and unlocked."
    );

    private volatile ListMode listMode = ListMode.ROLLED;
    private volatile String searchText = "";

    private final JComboBox<ListMode> modeDropdown = new JComboBox<>(ListMode.values());

    public RollBoundPanel(
            ObtainedItemsManager obtainedItemsManager,
            RolledItemsManager rolledItemsManager,
            RolledByManager rolledByManager,
            RollHistoryManager rollHistoryManager,
            ItemManager itemManager,
            HashSet<Integer> allTradeableItems,
            ClientThread clientThread,
            RollAnimationManager rollAnimationManager,
            RollBoundConfig config,
            FirebaseSyncService firebaseSyncService,
            DropCache dropCache,
            NpcSearchService npcSearchService,
            AccountGuardManager accountGuardManager,
            String version,
            Runnable manualGroupRoll,
            Runnable accountGuardChanged
    )
    {
        this.obtainedItemsManager = obtainedItemsManager;
        this.rolledItemsManager = rolledItemsManager;
        this.rolledByManager = rolledByManager;
        this.rollHistoryManager = rollHistoryManager;
        this.itemManager = itemManager;
        this.allTradeableItems = allTradeableItems;
        this.clientThread = clientThread;
        this.rollAnimationManager = rollAnimationManager;

        this.config = config;
        this.firebaseSyncService = firebaseSyncService;
        this.accountGuardManager = accountGuardManager;
        this.dropsPanel = new RollBoundDropsPanel(dropCache, npcSearchService, obtainedItemsManager, itemManager, config);
        this.version = (version == null || version.isBlank()) ? "dev" : version;
        this.manualGroupRoll = manualGroupRoll;
        this.accountGuardChanged = accountGuardChanged;
        init();
    }

    private void init()
    {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(12, 12, 12, 12));
        setBackground(new Color(37, 37, 37));

        JPanel items = new JPanel(new BorderLayout());
        items.setOpaque(false);
        items.add(buildTop(), BorderLayout.NORTH);
        items.add(buildCenter(), BorderLayout.CENTER);
        items.add(buildBottom(), BorderLayout.SOUTH);

        mainTabs.setFocusable(false);
        mainTabs.addTab("Items", items);
        mainTabs.addTab("Drops", dropsPanel);
        add(mainTabs, BorderLayout.CENTER);

        // Footer: version label
        versionLabel.setText("v" + version);
        versionLabel.setForeground(new Color(150, 150, 150));
        versionLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(new EmptyBorder(6, 2, 0, 2));
        footer.add(versionLabel, BorderLayout.WEST);
        add(footer, BorderLayout.SOUTH);

        itemList.setFixedCellHeight(36);
        itemList.setVisibleRowCount(-1);

        setMode(ListMode.ROLLED);
        updatePanel();
    }


    /** Display an NPC drop table in RollBound's own sidebar and switch to the Drops tab. */
    public void showDrops(NpcDropData data)
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(() -> showDrops(data));
            return;
        }
        dropsPanel.showDrops(data);
        mainTabs.setSelectedComponent(dropsPanel);
    }

    public void refreshDropsView()
    {
        dropsPanel.refresh();
    }

    private JPanel buildTop()
    {
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        top.add(buildHeaderRow());
        top.add(Box.createVerticalStrut(10));
        top.add(buildDropdownRow());
        top.add(Box.createVerticalStrut(8));
        top.add(buildModeLabelRow());
        top.add(Box.createVerticalStrut(10));
        top.add(buildSearchBar());

        return top;
    }

    private JPanel buildHeaderRow()
    {
        JPanel headerPanel = new JPanel();
        headerPanel.setOpaque(false);
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));

        // Banner header
        try
        {
            BufferedImage banner = ImageUtil.loadImageResource(getClass(), "/rollbound-banner.png");
            if (banner != null)
            {
                int targetW = PluginPanel.PANEL_WIDTH;
                int targetH = Math.max(1, (int) Math.round((banner.getHeight() * (targetW / (double) banner.getWidth()))));
                java.awt.Image scaled = banner.getScaledInstance(targetW, targetH, java.awt.Image.SCALE_SMOOTH);
                JLabel bannerLabel = new JLabel(new ImageIcon(scaled));
                bannerLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                headerPanel.add(bannerLabel);

                // subtle accent divider
                JSeparator sep = new JSeparator();
                sep.setForeground(new Color(85, 65, 110));
                sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
                headerPanel.add(Box.createVerticalStrut(4));
                headerPanel.add(sep);

                headerPanel.add(Box.createVerticalStrut(3));
                syncStatusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                syncStatusLabel.setForeground(new Color(170, 160, 190));
                syncStatusLabel.setFont(syncStatusLabel.getFont().deriveFont(Font.PLAIN, 11f));
                headerPanel.add(syncStatusLabel);

                headerPanel.add(Box.createVerticalStrut(3));
                accountGuardStatusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                accountGuardStatusLabel.setForeground(new Color(190, 190, 190));
                accountGuardStatusLabel.setFont(accountGuardStatusLabel.getFont().deriveFont(Font.PLAIN, 11f));
                headerPanel.add(accountGuardStatusLabel);
            }
        }
        catch (Exception ignored) {}

        // Quick action to recover from any stale roll UI.
        styleHeaderActionButton(resetUiButton);
        resetUiButton.setToolTipText("Clear any stuck roll/choice overlay and pending local roll animations");
        resetUiButton.addActionListener(e -> {
            if (rollAnimationManager != null && rollAnimationManager.isAwaitingLocalChoice())
            {
                JOptionPane.showMessageDialog(
                        this,
                        "You are currently choosing the group's roll. Pick an item before resetting the UI.",
                        "RollBound",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (rollAnimationManager != null)
            {
                rollAnimationManager.resetUiState();
            }
            updatePanel();
        });

        styleHeaderActionButton(accountGuardButton);
        accountGuardButton.addActionListener(e -> toggleCurrentAccountGuard());

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        actionRow.setOpaque(false);
        actionRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        actionRow.add(accountGuardButton);
        actionRow.add(resetUiButton);

        headerPanel.add(Box.createVerticalStrut(8));
        headerPanel.add(actionRow);

        return headerPanel;
    }

    private void styleHeaderActionButton(JButton button)
    {
        button.setFocusable(false);
        button.setFont(new Font("SansSerif", Font.BOLD, 11));
        button.setBorder(new EmptyBorder(3, 8, 3, 8));
        button.setBackground(new Color(48, 48, 48));
        button.setForeground(new Color(230, 230, 230));
        button.setOpaque(true);
    }

    private void updateAccountGuardStatus()
    {
        if (accountGuardManager == null)
        {
            accountGuardStatusLabel.setText("Account Guard: unavailable");
            accountGuardButton.setEnabled(false);
            return;
        }

        long hash = accountGuardManager.getCurrentAccountHash();
        if (hash == -1L)
        {
            accountGuardStatusLabel.setText("Account Guard: waiting for login");
            accountGuardButton.setText("Enable this account");
            accountGuardButton.setEnabled(false);
            return;
        }

        String name = accountGuardManager.getCurrentDisplayName();
        String who = name == null ? "this account" : name;
        boolean enabled = accountGuardManager.isCurrentAccountEnabled();
        if (enabled)
        {
            accountGuardStatusLabel.setText("RollBound enabled: " + who + " ✓");
            accountGuardButton.setText("Disable this account");
        }
        else
        {
            accountGuardStatusLabel.setText("RollBound paused: " + who + " not enabled");
            accountGuardButton.setText("Enable this account");
        }
        accountGuardButton.setEnabled(true);
    }

    private void toggleCurrentAccountGuard()
    {
        if (accountGuardManager == null || accountGuardManager.getCurrentAccountHash() == -1L)
        {
            return;
        }

        String name = accountGuardManager.getCurrentDisplayName();
        String who = name == null ? "this account" : name;
        boolean enabled = accountGuardManager.isCurrentAccountEnabled();

        String message = enabled
                ? "Disable RollBound for " + who + " on this computer?\n\nRollBound will be paused for this account."
                : "Enable RollBound for " + who + " on this computer?\n\nThis account will be allowed to trigger rolls and update group progression.";
        int result = JOptionPane.showConfirmDialog(
                this,
                message,
                "RollBound Account Guard",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (result != JOptionPane.YES_OPTION)
        {
            return;
        }

        if (enabled)
        {
            accountGuardManager.disableCurrentAccount();
        }
        else
        {
            accountGuardManager.enableCurrentAccount();
        }

        if (accountGuardChanged != null)
        {
            accountGuardChanged.run();
        }
        updatePanel();
    }

    private void updateFirebaseStatus()
    {
        if (firebaseSyncService == null)
        {
            syncStatusLabel.setText("Firebase: unavailable");
            return;
        }

        syncStatusLabel.setToolTipText(null);
        switch (firebaseSyncService.getStatus())
        {
            case CONNECTED:
                syncStatusLabel.setText("Firebase: Connected ✓");
                break;
            case CONNECTING:
                syncStatusLabel.setText("Firebase: Connecting…");
                break;
            case ERROR:
                syncStatusLabel.setText("Firebase: Error");
                String error = firebaseSyncService.getLastError();
                syncStatusLabel.setToolTipText(error == null ? "Firebase sync error" : error);
                break;
            case NOT_CONFIGURED:
            default:
                syncStatusLabel.setText("Firebase: Not configured");
                break;
        }

    }

    private JPanel buildDropdownRow()
    {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);

        modeDropdown.setFocusable(false);
        modeDropdown.setBackground(new Color(60, 63, 65));
        modeDropdown.setForeground(Color.WHITE);
        modeDropdown.setFont(new Font("SansSerif", Font.BOLD, 12));
        modeDropdown.setBorder(
                new CompoundBorder(new LineBorder(new Color(80, 80, 80)),
                        new EmptyBorder(2, 6, 2, 6))
        );

        modeDropdown.setRenderer(new DefaultListCellRenderer()
        {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus)
            {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);

                lbl.setBorder(new EmptyBorder(4, 6, 4, 6));

                if (value instanceof ListMode)
                {
                    ListMode mode = (ListMode) value;

                    lbl.setText(index == -1 ? "Filter: " + mode.label() : mode.label());

                    String tip = MODE_TOOLTIPS.get(mode);
                    lbl.setToolTipText(tip);
                    modeDropdown.setToolTipText(tip);
                }
                else
                {
                    lbl.setToolTipText(null);
                }

                lbl.setBackground(isSelected
                        ? new Color(75, 78, 80)
                        : new Color(60, 63, 65));
                lbl.setForeground(Color.WHITE);

                return lbl;
            }
        });

        modeDropdown.addActionListener(e ->
        {
            Object sel = modeDropdown.getSelectedItem();
            if (sel instanceof ListMode)
            {
                setMode((ListMode) sel);
                updatePanel();
            }
        });

        row.add(modeDropdown, BorderLayout.CENTER);
        return row;
    }

    private JPanel buildModeLabelRow()
    {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);

        modeLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        modeLabel.setForeground(new Color(210, 210, 210));

        row.add(modeLabel, BorderLayout.WEST);
        return row;
    }

    private JPanel buildSearchBar()
    {
        JPanel container = new JPanel(new BorderLayout());
        container.setOpaque(false);

        JPanel searchBox = new JPanel(new BorderLayout());
        searchBox.setBackground(new Color(30, 30, 30));
        searchBox.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JLabel icon = new JLabel("\uD83D\uDD0D");
        icon.setForeground(new Color(200, 200, 200));
        icon.setBorder(new EmptyBorder(0, 0, 0, 6));
        searchBox.add(icon, BorderLayout.WEST);

        searchField.setBackground(new Color(45, 45, 45));
        searchField.setForeground(Color.WHITE);
        searchField.setBorder(null);
        searchField.setCaretColor(Color.WHITE);

        searchField.addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyReleased(KeyEvent e)
            {
                searchText = searchField.getText().toLowerCase();
                updatePanel();
            }
        });

        searchBox.add(searchField, BorderLayout.CENTER);

        container.add(searchBox, BorderLayout.CENTER);
        return container;
    }

    private JPanel buildCenter()
    {
        itemList.setCellRenderer(new ItemCellRenderer());
        itemList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane scroll = new JScrollPane(
                itemList,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        );
        scroll.setBorder(null);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setBorder(new CompoundBorder(
                new LineBorder(new Color(80, 80, 80)),
                new EmptyBorder(6, 6, 6, 6)));

        wrap.add(scroll, BorderLayout.CENTER);
        return wrap;
    }

    private JPanel buildBottom()
    {
        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));

        bottom.add(Box.createVerticalStrut(10));

        JPanel countPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        countPanel.setOpaque(false);
        countLabel.setFont(new Font("Arial", Font.BOLD, 11));
        countLabel.setForeground(new Color(220, 220, 220));
        countPanel.add(countLabel);
        bottom.add(countPanel);

        bottom.add(Box.createVerticalStrut(10));

        JPanel rollPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        rollPanel.setOpaque(false);
        rollButton.setToolTipText("Manual roll (select a random locked item)");
        rollButton.addActionListener(this::performManualRoll);
        rollButton.setPreferredSize(new Dimension(120, 30));
        rollPanel.add(rollButton);
        bottom.add(rollPanel);

        bottom.add(Box.createVerticalStrut(4));
        return bottom;
    }

    // Roll Feed / Stats tabs removed to keep the UI focused.

    private void setMode(ListMode mode)
    {
        listMode = mode;

        String tip = MODE_TOOLTIPS.get(mode);
        modeDropdown.setToolTipText(tip);

        switch (mode)
        {
            case ROLLED:
                modeLabel.setText("Group Unlocked");
                break;
            case OBTAINED:
                modeLabel.setText("Group Obtained (Trigger Items)");
                break;
            case ROLLED_NOT_OBTAINED:
                modeLabel.setText("Unlocked, not Obtained");
                break;
            case USABLE:
                modeLabel.setText("Items Usable");
                break;
        }
    }

        public void updatePanel()
    {
        updateFirebaseStatus();
        updateAccountGuardStatus();
        rollButton.setEnabled(accountGuardManager != null
                && accountGuardManager.isCurrentAccountEnabled()
                && firebaseSyncService != null
                && firebaseSyncService.getStatus() == FirebaseSyncService.Status.CONNECTED
                && rollAnimationManager.hasTradeablesReady()
                && !rollAnimationManager.isRolling());

        final ListMode modeSnap = listMode;
        final String searchSnap = searchText;

        clientThread.invokeLater(() ->
        {
            Set<Integer> obtained = obtainedItemsManager.getObtainedItems();
            Set<Integer> rolled = rolledItemsManager.getRolledItems();
            List<Integer> base = new ArrayList<>();

            switch (modeSnap)
            {
                case ROLLED:
                    base.addAll(rolled);
                    break;

                case OBTAINED:
                    base.addAll(obtained);
                    break;

                case ROLLED_NOT_OBTAINED:
                    // Show what you can buy/unlock but haven't obtained yet
                    base.addAll(rolled);
                    base.removeIf(obtained::contains);
                    break;

                case USABLE:
                    base.addAll(rolled);
                    base.removeIf(id -> !obtained.contains(id));
                    break;
            }

            Collections.reverse(base);

            if (searchSnap != null && !searchSnap.isEmpty())
            {
                base.removeIf(id ->
                {
                    ItemComposition comp = itemManager.getItemComposition(id);
                    String name = comp.getName();
                    return name == null || !name.toLowerCase().contains(searchSnap);
                });
            }

            final int total;
            synchronized (allTradeableItems)
            {
                total = allTradeableItems.size();
            }

            SwingUtilities.invokeLater(() ->
            {
                listModel.clear();
                for (Integer id : base)
                {
                    listModel.addElement(id);
                }
                countLabel.setText(base.size() + "/" + total);

                // extra stability during mode swaps
                itemList.revalidate();
                itemList.repaint();
            });
        });
    }

    
    private class ItemCellRenderer extends JPanel implements ListCellRenderer<Integer>
    {
        private final JLabel icon = new JLabel();
        private final JLabel name = new JLabel();

        ItemCellRenderer()
        {
            setLayout(new BorderLayout(8, 0));
            setOpaque(true);
            icon.setPreferredSize(new Dimension(32, 32));

            add(icon, BorderLayout.WEST);
            add(name, BorderLayout.CENTER);
            name.setFont(new Font("SansSerif", Font.PLAIN, 11));
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends Integer> list, Integer value, int index,
                boolean isSelected, boolean cellHasFocus)
        {
            ImageIcon ico = itemIconCache.get(value);
            if (ico != null)
            {
                icon.setIcon(ico);
            }
            else
            {
                icon.setIcon(null);
                requestItemIcon(value, list, index);
            }

            String cached = itemNameCache.get(value);
            if (cached != null)
            {
                name.setText(cached);
            }
            else
            {
                name.setText("Loading...");
                requestItemName(value, list, index);
            }


            // Tooltip: who rolled this item (and when), if available.
            if (rolledByManager != null)
            {
                RolledByManager.RolledByEntry entry = rolledByManager.getEntry(value);
                if (entry != null && entry.getBy() != null && !entry.getBy().trim().isEmpty() && entry.getAt() > 0)
                {
                    java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd MMM yyyy HH:mm");
                    String when = fmt.format(new java.util.Date(entry.getAt()));
                    setToolTipText("Unlocked by: " + entry.getBy() + " (" + when + ")");
                }
                else if (entry != null && entry.getBy() != null && !entry.getBy().trim().isEmpty())
                {
                    setToolTipText("Unlocked by: " + entry.getBy());
                }
                else
                {
                    setToolTipText(null);
                }
            }
            else
            {
                setToolTipText(null);
            }

            // Slight green tint in Unlocks when an unlocked item is also obtained.
            Color base = new Color(60, 63, 65);
            boolean tint = false;
            try
            {
                tint = !isSelected
                        && listMode == ListMode.ROLLED
                        && config != null
                        && config.highlightObtainedInUnlocks()
                        && obtainedItemsManager != null
                        && obtainedItemsManager.isObtained(value);
            }
            catch (Exception ignored) {}

            if (isSelected)
            {
                setBackground(list.getSelectionBackground());
            }
            else if (tint)
            {
                setBackground(new Color(45, 85, 55));
            }
            else
            {
                setBackground(base);
            }
            name.setForeground(new Color(220, 220, 220));
            return this;
        }
    }

    private void requestItemName(int itemId, JList<?> list, int index)
    {
        if (!nameFetchInFlight.add(itemId))
        {
            return;
        }

        clientThread.invokeLater(() ->
        {
            String resolved = "Unknown";
            try
            {
                ItemComposition comp = itemManager.getItemComposition(itemId);
                if (comp.getName() != null)
                {
                    resolved = comp.getName();
                }
            }
            catch (Exception ignored)
            {
            }

            final String finalName = resolved;
            SwingUtilities.invokeLater(() ->
            {
                itemNameCache.put(itemId, finalName);
                nameFetchInFlight.remove(itemId);

                Rectangle r = list.getCellBounds(index, index);
                if (r != null) list.repaint(r);
                else list.repaint();
            });
        });
    }

    private void requestItemIcon(int itemId, JList<?> list, int index)
    {
        if (!iconFetchInFlight.add(itemId))
        {
            return;
        }

        clientThread.invokeLater(() ->
        {
            ImageIcon resolved = null;
            try
            {
                BufferedImage img = itemManager.getImage(itemId, 1, false);
                if (img != null)
                {
                    resolved = new ImageIcon(img);
                }
            }
            catch (Exception ignored)
            {
            }

            final ImageIcon finalIcon = resolved;
            SwingUtilities.invokeLater(() ->
            {
                if (finalIcon != null)
                {
                    itemIconCache.put(itemId, finalIcon);
                }
                iconFetchInFlight.remove(itemId);

                Rectangle r = list.getCellBounds(index, index);
                if (r != null) list.repaint(r);
                else list.repaint();
            });
        });
    }

    private void performManualRoll(java.awt.event.ActionEvent e)
    {
        if (rollAnimationManager.isRolling()) return;
        if (!rollAnimationManager.hasTradeablesReady()) return;

        // Manual rolls are synchronised through Firebase so every connected group member sees them.
        if (manualGroupRoll != null)
        {
            manualGroupRoll.run();
        }
    }

    @Override
    public Dimension getPreferredSize()
    {
        Dimension d = super.getPreferredSize();
        int viewportH = getViewportHeight();
        if (viewportH > 0 && d.height < viewportH)
        {
            return new Dimension(d.width, viewportH);
        }
        return d;
    }

    private int getViewportHeight()
    {
        Container p = getParent();
        while (p != null && !(p instanceof JViewport))
        {
            p = p.getParent();
        }
        if (p != null)
        {
            return Math.max(p.getHeight(), 0);
        }
        return 0;
    }

    // Roll Feed / Stats functionality removed.

}
