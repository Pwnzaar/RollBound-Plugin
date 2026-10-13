package com.rollbound;

import com.rollbound.account.AccountChanged;
import com.rollbound.account.AccountManager;
import com.rollbound.account.AccountGuardManager;
import com.rollbound.backup.LocalBackupManager;
import com.rollbound.drops.DropFetcher;
import com.rollbound.drops.DropCache;
import com.rollbound.filters.EnsouledHeadMapping;
import com.rollbound.filters.ItemsFilter;
import com.rollbound.menus.ActionHandler;
import com.rollbound.firebase.FirebaseRollEvent;
import com.rollbound.firebase.FirebaseSyncService;
import com.rollbound.tracker.TrackerTelemetryService;
import com.rollbound.ui.ItemDimmerController;
import com.rollbound.ui.NpcSearchService;
import com.rollbound.ui.DropsMenuListener;
import com.google.inject.Provides;
import com.rollbound.managers.RollAnimationManager;
import com.rollbound.managers.RolledItemsManager;
import com.rollbound.managers.ObtainedItemsManager;
import com.rollbound.managers.RolledByManager;
import com.rollbound.managers.RollHistoryManager;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseListener;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import net.runelite.client.util.Filepath;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.util.*;

@PluginDescriptor(
        name = "RollBound",
        description = "Shared roll-unlock progression created for Group Ironmen, with live group rolls and optional web tracking.",
        tags = {"rollbound", "roll", "group", "gim", "groupironman", "shared", "progression", "lock", "unlock", "multiplayer"},
        internalName = "rollbound",
        legacyDataDirectory = "Rollbound"
)
public class RollBoundPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ClientToolbar clientToolbar;
    @Inject private OverlayManager overlayManager;
    @Inject private ItemManager itemManager;
    @Inject private RollBoundOverlay rollBoundOverlay;
    @Inject private RollBoundConfig config;
    @Inject private AccountManager accountManager;
    @Inject private AccountGuardManager accountGuardManager;
    @Inject private ObtainedItemsManager obtainedItemsManager;
    @Inject private RolledItemsManager rolledItemsManager;
    @Inject
    private RolledByManager rolledByManager;

    @Inject
    private RollHistoryManager rollHistoryManager;
    @Inject private RollAnimationManager rollAnimationManager;
    @Inject private EventBus eventBus;
    @Inject private DropFetcher dropFetcher;
    @Inject private DropCache dropCache;
    @Inject private NpcSearchService npcSearchService;
    @Inject private ItemDimmerController itemDimmerController;
    @Inject private DropsMenuListener dropsMenuListener;
    @Inject private MouseManager mouseManager;
    @Inject private FirebaseSyncService firebaseSyncService;
    @Inject private TrackerTelemetryService trackerTelemetryService;
    @Inject private LocalBackupManager localBackupManager;

    private RollBoundPanel rollBoundPanel;
    private NavigationButton navButton;
    private Filepath pluginDataDirectory;

    // Firebase can reconnect while RuneLite is between game states. Retain recent live events
    // locally until LOGGED_IN rather than losing them after Firebase de-duplicates the event.
    private final LinkedHashMap<String, FirebaseRollEvent> deferredRollStarts = new LinkedHashMap<>();
    private final LinkedHashMap<String, FirebaseRollEvent> deferredRollPicks = new LinkedHashMap<>();
    private static final long DEFERRED_ROLL_MAX_AGE_MS = 2 * 60 * 1000L;

    // Mouse click blocker/selector for RollBound roll overlay
    private final MouseListener overlayMouseListener = new MouseListener()
    {
        @Override
        public java.awt.event.MouseEvent mouseClicked(java.awt.event.MouseEvent e)
        {
            return e;
        }

        @Override
        public java.awt.event.MouseEvent mousePressed(java.awt.event.MouseEvent e)
        {
            if (featuresActive && rollBoundOverlay != null)
            {
                boolean consumed = rollBoundOverlay.handleClick(e.getPoint());
                if (consumed)
                {
                    // Only consume if a selection tile was clicked.
                    e.consume();
                }
            }
            return e;
        }

        @Override
        public java.awt.event.MouseEvent mouseReleased(java.awt.event.MouseEvent e)
        {
            return e;
        }

        @Override
        public java.awt.event.MouseEvent mouseEntered(java.awt.event.MouseEvent e)
        {
            return e;
        }

        @Override
        public java.awt.event.MouseEvent mouseExited(java.awt.event.MouseEvent e)
        {
            return e;
        }

        @Override
        public java.awt.event.MouseEvent mouseDragged(java.awt.event.MouseEvent e)
        {
            return e;
        }

        @Override
        public java.awt.event.MouseEvent mouseMoved(java.awt.event.MouseEvent e)
        {
            if (featuresActive && rollBoundOverlay != null)
            {
                // Track hover for selection tiles without blocking game interaction.
                rollBoundOverlay.handleMouseMove(e.getPoint());
            }
            return e;
        }
        // Not all RuneLite versions include mouseWheelMoved on MouseListener; keep behaviour without @Override.
        public java.awt.event.MouseWheelEvent mouseWheelMoved(java.awt.event.MouseWheelEvent e)
        {
            return e;
        }
    };
    
private final HashSet<Integer> allTradeableItems = new LinkedHashSet<>();
    private static final int GE_SEARCH_BUILD_SCRIPT = 751;
    private volatile boolean tradeableItemsInitialized = false;
    private boolean featuresActive = false;

    // De-duplicate live Firebase roll events echoed back to this client.
    private final LinkedHashSet<String> processedEventIds = new LinkedHashSet<>();
    private final LinkedHashSet<String> processedPickEventIds = new LinkedHashSet<>();
    private static final int MAX_PROCESSED_EVENTS = 500;
    private final Random groupRandom = new Random();

    public ItemManager getItemManager()
    {
        return itemManager;
    }

    @Provides
    RollBoundConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(RollBoundConfig.class);
    }

    @Override
    protected void startUp() throws Exception
    {
        pluginDataDirectory = getPluginDirectory();
        localBackupManager.setDataDirectory(pluginDataDirectory);
        accountGuardManager.setDataDirectory(pluginDataDirectory);
        dropCache.setDataDirectory(pluginDataDirectory);
        eventBus.register(this);
        if (isNormalWorld()) enableFeatures();
    }

    @Override
    protected void shutDown() throws Exception
    {
        if (featuresActive) disableFeatures();
        accountGuardManager.shutdown();
        eventBus.unregister(this);
    }

    private void enableFeatures()
    {
        if (featuresActive) return;
        featuresActive = true;

        getInjector().getInstance(ActionHandler.class).startUp();
        accountManager.init();
        dropFetcher.startUp();
        dropCache.startUp();
        eventBus.register(accountManager);
        overlayManager.add(rollBoundOverlay);

        // RollBound on-screen selection: block clicks through the overlay and allow selection.
        try
        {
            if (mouseManager != null)
            {
                mouseManager.registerMouseListener(overlayMouseListener);
            }
        }
        catch (Exception ignored) {}

        Runnable refreshPanel = () -> {
            if (rollBoundPanel != null)
            {
                SwingUtilities.invokeLater(() -> {
                    rollBoundPanel.updatePanel();
                    rollBoundPanel.refreshDropsView();
                });
            }
        };
        obtainedItemsManager.setOnChange(refreshPanel);
        rolledItemsManager.setOnChange(refreshPanel);
        rolledByManager.setOnChange(refreshPanel);
        rollHistoryManager.setOnChange(refreshPanel);

        itemDimmerController.setEnabled(config.dimLockedItemsEnabled() && isCurrentAccountEnabledForRollBound());
        itemDimmerController.setDimOpacity(config.dimLockedItemsOpacity());
        eventBus.register(itemDimmerController);
        rollAnimationManager.startUp();

        rollBoundPanel = new RollBoundPanel(
                obtainedItemsManager,
                rolledItemsManager,
                rolledByManager,
                rollHistoryManager,
                itemManager,
                allTradeableItems,
                clientThread,
                rollAnimationManager,
                config,
                firebaseSyncService,
                dropCache,
                npcSearchService,
                accountGuardManager,
                getClass().getPackage().getImplementationVersion(),
                // Manual rolls must be group-synchronised too.
                () -> triggerGroupRoll(-1, true),
                this::onAccountGuardChanged
        );
        rollAnimationManager.setRollBoundPanel(rollBoundPanel);

        SwingUtilities.invokeLater(rollBoundPanel::updatePanel);

        firebaseSyncService.setStatusListener(() -> {
            if (rollBoundPanel != null)
            {
                SwingUtilities.invokeLater(rollBoundPanel::updatePanel);
            }
        });
        firebaseSyncService.setRollListener(new FirebaseSyncService.RollListener()
        {
            @Override
            public void onRollStarted(FirebaseRollEvent event)
            {
                clientThread.invokeLater(() -> handleFirebaseRollStarted(event));
            }

            @Override
            public void onRollPicked(FirebaseRollEvent event)
            {
                clientThread.invokeLater(() -> handleFirebaseRollPicked(event));
            }
        });
        firebaseSyncService.start();
        if (config.enableTrackerTelemetry() && isCurrentAccountEnabledForRollBound())
        {
            trackerTelemetryService.start();
            publishCachedNpcData();
        }

        BufferedImage icon = ImageUtil.loadImageResource(
                getClass(), "/icon.png"
        );
        navButton = NavigationButton.builder()
                .tooltip("RollBound")
                .icon(icon)
                .priority(5)
                .panel(rollBoundPanel)
                .build();
        clientToolbar.addNavigation(navButton);

        dropsMenuListener.setOnDropsLoaded(data -> SwingUtilities.invokeLater(() -> {
            if (rollBoundPanel != null)
            {
                rollBoundPanel.showDrops(data);
                if (navButton != null)
                {
                    clientToolbar.openPanel(navButton);
                }
            }
        }));
        eventBus.register(dropsMenuListener);
        tradeableItemsInitialized = false;
        rollAnimationManager.setAllTradeableItems(Collections.emptySet());
    }

    private void disableFeatures()
    {
        if (!featuresActive) return;
        featuresActive = false;

        trackerTelemetryService.stop();
        firebaseSyncService.stop();

        dropsMenuListener.setOnDropsLoaded(null);
        eventBus.unregister(dropsMenuListener);
        itemDimmerController.setEnabled(false);
        eventBus.unregister(itemDimmerController);
        eventBus.unregister(accountManager);
        getInjector().getInstance(ActionHandler.class).shutDown();

        if (clientToolbar != null && navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }
        if (overlayManager != null)
        {
            overlayManager.remove(rollBoundOverlay);
        }

        try
        {
            if (mouseManager != null)
            {
                mouseManager.unregisterMouseListener(overlayMouseListener);
            }
        }
        catch (Exception ignored) {}
        if (rollAnimationManager != null)
        {
            rollAnimationManager.shutdown();
        }
        obtainedItemsManager.setOnChange(null);
        rolledItemsManager.setOnChange(null);
        rolledByManager.setOnChange(null);
        rollHistoryManager.setOnChange(null);
        firebaseSyncService.setStatusListener(null);
        firebaseSyncService.setRollListener(null);
        dropFetcher.shutdown();
        dropCache.shutdown();
        synchronized (processedEventIds)
        {
            processedEventIds.clear();
        }
        synchronized (processedPickEventIds)
        {
            processedPickEventIds.clear();
        }
        synchronized (deferredRollStarts)
        {
            deferredRollStarts.clear();
            deferredRollPicks.clear();
        }

        // reset panel/tradeable state
        rollBoundPanel = null;
        allTradeableItems.clear();
        tradeableItemsInitialized = false;
        rollAnimationManager.setAllTradeableItems(Collections.emptySet());
        accountManager.reset();
    }

    
    private java.util.List<Integer> eligibleRemoteOptions(java.util.List<Integer> optionIds)
    {
        if (optionIds == null || optionIds.isEmpty())
        {
            return java.util.Collections.emptyList();
        }

        java.util.List<Integer> eligible = new java.util.ArrayList<>();
        for (Integer itemId : optionIds)
        {
            if (itemId != null && allTradeableItems.contains(itemId))
            {
                eligible.add(itemId);
            }
        }
        return eligible;
    }

    private void handleFirebaseRollStarted(FirebaseRollEvent event)
    {
        if (!isCurrentAccountEnabledForRollBound() || event == null || event.getEventId() == null) return;
        String eventId = event.getEventId();
        if (client == null || client.getGameState() != GameState.LOGGED_IN)
        {
            deferLiveRoll(deferredRollStarts, event);
            return;
        }

        synchronized (processedEventIds)
        {
            if (processedEventIds.contains(eventId)) return;
            rememberProcessed(processedEventIds, eventId);
        }

        java.util.List<Integer> eligibleOptions = eligibleRemoteOptions(event.getOptions());
        if (eligibleOptions.isEmpty())
        {
            return;
        }

        try
        {
            announceChoiceRollToChat(event.getRollerName(), eligibleOptions, event.getObtainedItemId());
        }
        catch (Exception ignored) {}

        rollAnimationManager.enqueueSyncedChoiceRoll(
                eventId,
                event.getObtainedItemId(),
                eligibleOptions,
                event.isManual(),
                event.getRollerName(),
                event.getCreatedAt(),
                event.getBatchId(),
                event.getSequence(),
                false,
                chosenItemId -> { /* Remote clients never choose the authoritative result. */ }
        );
    }

    private void handleFirebaseRollPicked(FirebaseRollEvent event)
    {
        if (!isCurrentAccountEnabledForRollBound() || event == null || event.getEventId() == null || event.getChosenItemId() <= 0) return;
        String eventId = event.getEventId();
        if (!allTradeableItems.contains(event.getChosenItemId()))
        {
            return;
        }
        if (client == null || client.getGameState() != GameState.LOGGED_IN)
        {
            deferLiveRoll(deferredRollPicks, event);
            return;
        }

        synchronized (processedPickEventIds)
        {
            if (processedPickEventIds.contains(eventId)) return;
            rememberProcessed(processedPickEventIds, eventId);
        }

        try
        {
            announceChoicePickToChat(event.getRollerName(), event.getChosenItemId());
        }
        catch (Exception ignored) {}

        if (rollBoundPanel != null)
        {
            SwingUtilities.invokeLater(rollBoundPanel::updatePanel);
        }
        try
        {
            rollBoundOverlay.applyRemoteChoiceSelection(eventId, event.getChosenItemId());
        }
        catch (Exception ignored) {}
    }

    private void deferLiveRoll(LinkedHashMap<String, FirebaseRollEvent> target, FirebaseRollEvent event)
    {
        long createdAt = event.getCreatedAt();
        long now = System.currentTimeMillis();
        if (createdAt <= 0 || now - createdAt > DEFERRED_ROLL_MAX_AGE_MS)
        {
            return;
        }
        synchronized (deferredRollStarts)
        {
            target.put(event.getEventId(), event);
            while (target.size() > MAX_PROCESSED_EVENTS)
            {
                Iterator<String> iterator = target.keySet().iterator();
                if (iterator.hasNext())
                {
                    iterator.next();
                    iterator.remove();
                }
                else
                {
                    break;
                }
            }
        }
    }

    private void replayDeferredRolls()
    {
        if (!isCurrentAccountEnabledForRollBound() || client == null || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        final List<FirebaseRollEvent> starts;
        final List<FirebaseRollEvent> picks;
        synchronized (deferredRollStarts)
        {
            starts = new ArrayList<>(deferredRollStarts.values());
            picks = new ArrayList<>(deferredRollPicks.values());
            deferredRollStarts.clear();
            deferredRollPicks.clear();
        }
        starts.sort(FirebaseRollEvent::compareForPlayback);
        picks.sort(Comparator.comparingLong(FirebaseRollEvent::getPickedAt)
                .thenComparing(event -> event.getEventId() == null ? "" : event.getEventId()));
        starts.forEach(this::handleFirebaseRollStarted);
        picks.forEach(this::handleFirebaseRollPicked);
    }

    private void clearDeferredRolls()
    {
        synchronized (deferredRollStarts)
        {
            deferredRollStarts.clear();
            deferredRollPicks.clear();
        }
    }

    private void rememberProcessed(LinkedHashSet<String> set, String eventId)
    {
        set.add(eventId);
        while (set.size() > MAX_PROCESSED_EVENTS)
        {
            Iterator<String> it = set.iterator();
            if (it.hasNext())
            {
                it.next();
                it.remove();
            }
            else
            {
                break;
            }
        }
    }

    private void announceChoiceRollToChat(String rollerName, java.util.List<Integer> optionIds, int obtainedItemId)
    {
        final String who = (rollerName == null || rollerName.trim().isEmpty()) ? "Someone" : Text.escapeJagex(rollerName.trim());
        final java.util.List<Integer> opts = (optionIds == null) ? java.util.Collections.emptyList() : optionIds;

        clientThread.invoke(() ->
        {
            StringBuilder sb = new StringBuilder();

            // Colours: username blue, items green
            sb.append("<col=0000ff>").append(who).append("</col>").append(" rolled ");

            for (int i = 0; i < opts.size(); i++)
            {
                int id = opts.get(i);
                String nm = "";
                try { nm = itemManager.getItemComposition(id).getName(); } catch (Exception ignored) {}
                if (nm == null) nm = "";
                sb.append("<col=146314>").append(nm).append("</col>");
                if (i < opts.size() - 1)
                {
                    sb.append(" - ");
                }
            }

            if (obtainedItemId > 0)
            {
                String ob = "";
                try { ob = itemManager.getItemComposition(obtainedItemId).getName(); } catch (Exception ignored) {}
                if (ob == null) ob = "";
                sb.append(" by obtaining ").append("<col=00ff00>").append(ob).append("</col>");
            }

            client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", sb.toString(), null);
        });
    }

    private void announceChoicePickToChat(String rollerName, int chosenItemId)
    {
        final String who = (rollerName == null || rollerName.trim().isEmpty()) ? "Someone" : Text.escapeJagex(rollerName.trim());
        clientThread.invoke(() ->
        {
            String nm = "";
            try { nm = itemManager.getItemComposition(chosenItemId).getName(); } catch (Exception ignored) {}
            if (nm == null) nm = "";
            // Colours: username blue, chosen item red
            String msg = "<col=0000ff>" + who + "</col> has chosen <col=ff0000>" + nm + "</col>";
            client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", msg, null);
        });
    }


@Subscribe
    public void onWorldChanged(WorldChanged event)
    {
        if (isNormalWorld()) enableFeatures();
        else disableFeatures();
    }

    /** Refreshes the tradeable item pool using RollBound's fixed roll rules. */
    public void refreshTradeableItems()
    {
        clientThread.invokeLater(() ->
        {
            tradeableItemsInitialized = false;

            allTradeableItems.clear();
            for (int i = 0; i < 40000; i++)
            {
                ItemComposition comp = itemManager.getItemComposition(i);
                if (comp != null && comp.isGeTradeable() && !isNotTracked(i)
                        && !ItemsFilter.isBlocked(i))
                {
                    allTradeableItems.add(i);
                }
            }
            rollAnimationManager.setAllTradeableItems(allTradeableItems);

            // Only now mark initialized (prevents early rolls on login/inventory scan).
            tradeableItemsInitialized = true;

            if (rollBoundPanel != null)
            {
                SwingUtilities.invokeLater(rollBoundPanel::updatePanel);
            }
        });
    }

    @Subscribe
    public void onConfigChanged(net.runelite.client.events.ConfigChanged event)
    {
        if (!featuresActive) return;
        // Config group for this standalone plugin.
        if (!event.getGroup().equals("rollbound")) return;
        switch (event.getKey())
        {
            case "firebaseDatabaseUrl":
                firebaseSyncService.restart();
                if (config.enableTrackerTelemetry() && isCurrentAccountEnabledForRollBound())
                {
                    trackerTelemetryService.start();
                    publishCachedNpcData();
                }
                if (rollBoundPanel != null)
                {
                    SwingUtilities.invokeLater(rollBoundPanel::updatePanel);
                }
                break;
            case "requireRolledUnlockedForGe":
                if (isCurrentAccountEnabledForRollBound())
                {
                    firebaseSyncService.publishGameRule(event.getKey());
                }
                break;
            case "enableTrackerTelemetry":
                if (config.enableTrackerTelemetry() && isCurrentAccountEnabledForRollBound())
                {
                    trackerTelemetryService.start();
                    publishCachedNpcData();
                }
                else trackerTelemetryService.stop();
                break;
            case "showRareDropTable":
            case "showGemDropTable":
                dropCache.clearAllCachesAsync().whenComplete((ignored, error) -> refreshDropsViewerIfOpen());
                break;
            case "sortDropsByRarity":
                refreshDropsViewerIfOpen();
                break;
            case "dimLockedItemsEnabled":
            case "dimLockedItemsOpacity":
                itemDimmerController.setEnabled(config.dimLockedItemsEnabled() && isCurrentAccountEnabledForRollBound());
                itemDimmerController.setDimOpacity(config.dimLockedItemsOpacity());
                break;
            case "highlightObtainedInUnlocks":
                if (rollBoundPanel != null)
                {
                    SwingUtilities.invokeLater(rollBoundPanel::updatePanel);
                }
                break;
        }
    }

    @Subscribe
    private void onAccountChanged(AccountChanged event)
    {
        if (!featuresActive) return;
        // Never carry a pending choice/animation from one account session into another.
        rollAnimationManager.resetUiState();
        clearDeferredRolls();
        dropCache.reloadForAccount();
        accountGuardManager.refreshCurrentDisplayName();
        firebaseSyncService.requestRefresh();

        if (isCurrentAccountEnabledForRollBound())
        {
            itemDimmerController.setEnabled(config.dimLockedItemsEnabled());
            if (config.enableTrackerTelemetry())
            {
                trackerTelemetryService.start();
                publishCachedNpcData();
            }
        }
        else
        {
            itemDimmerController.setEnabled(false);
            trackerTelemetryService.stop();
        }

        refreshTradeableItems();
        if (rollBoundPanel != null)
        {
            SwingUtilities.invokeLater(rollBoundPanel::updatePanel);
        }
    }

    private void onAccountGuardChanged()
    {
        if (!featuresActive) return;

        rollAnimationManager.resetUiState();
        clearDeferredRolls();
        accountGuardManager.refreshCurrentDisplayName();

        if (isCurrentAccountEnabledForRollBound())
        {
            itemDimmerController.setEnabled(config.dimLockedItemsEnabled());
            firebaseSyncService.requestRefresh();
            if (config.enableTrackerTelemetry())
            {
                trackerTelemetryService.start();
                publishCachedNpcData();
            }
        }
        else
        {
            itemDimmerController.setEnabled(false);
            trackerTelemetryService.stop();
        }

        if (rollBoundPanel != null)
        {
            SwingUtilities.invokeLater(rollBoundPanel::updatePanel);
        }
    }

    public boolean isCurrentAccountEnabledForRollBound()
    {
        return featuresActive
                && accountManager.ready()
                && accountGuardManager.isCurrentAccountEnabled();
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        if (isCurrentAccountEnabledForRollBound() && config.enableTrackerTelemetry())
        {
            trackerTelemetryService.requestSync();
        }
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        if (isCurrentAccountEnabledForRollBound() && config.enableTrackerTelemetry())
        {
            trackerTelemetryService.requestSync();
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (!featuresActive) return;

        GameState next = event.getGameState();
        if (next == GameState.LOGIN_SCREEN
                || next == GameState.HOPPING
                || next == GameState.CONNECTION_LOST)
        {
            // Clear transient RollBound roll state immediately. Firebase progress itself is untouched.
            rollAnimationManager.resetUiState();
            if (config.enableTrackerTelemetry())
            {
                trackerTelemetryService.publishOfflineBestEffort();
            }
            return;
        }

        if (next == GameState.LOGGED_IN)
        {
            accountGuardManager.refreshCurrentDisplayName();
            boolean accountEnabled = isCurrentAccountEnabledForRollBound();
            itemDimmerController.setEnabled(config.dimLockedItemsEnabled() && accountEnabled);

            // Re-apply the cached Firebase snapshot and then deliver any live events that arrived
            // while RuneLite was reconnecting/hopping. No extra Firebase GET is performed.
            firebaseSyncService.requestRefresh();
            replayDeferredRolls();
            if (accountEnabled && config.enableTrackerTelemetry())
            {
                trackerTelemetryService.start();
                publishCachedNpcData();
            }
            else if (!accountEnabled)
            {
                trackerTelemetryService.stop();
            }

            if (rollBoundPanel != null)
            {
                SwingUtilities.invokeLater(rollBoundPanel::updatePanel);
            }
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (!isCurrentAccountEnabledForRollBound() || !config.enableTrackerTelemetry()) return;
        String plain = net.runelite.client.util.Text.removeTags(event.getMessage());
        if (plain == null) return;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("You have completed ([0-9,]+) rumours? for the Hunter Guild\\.", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(plain);
        if (matcher.find())
        {
            try
            {
                trackerTelemetryService.setHunterRumoursCompleted(Integer.parseInt(matcher.group(1).replace(",", "")));
            }
            catch (NumberFormatException ignored) {}
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (!featuresActive) return;
        if (!tradeableItemsInitialized && client.getGameState() == GameState.LOGGED_IN)
        {
            refreshTradeableItems();
        }

        if (tradeableItemsInitialized && isCurrentAccountEnabledForRollBound())
        {
            rollAnimationManager.process();
        }
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event)
    {
        if (!isCurrentAccountEnabledForRollBound()) return;
        if (event.getScriptId() == GE_SEARCH_BUILD_SCRIPT) { killSearchResults(); }
    }

    private void killSearchResults()
    {
        Widget geSearchResults = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
        if (geSearchResults == null)
        {
            return;
        }
        Widget[] children = geSearchResults.getDynamicChildren();
        if (children == null || children.length < 2 || children.length % 3 != 0)
        {
            return;
        }
        Set<Integer> obtained = obtainedItemsManager.getObtainedItems();
        Set<Integer> rolled = rolledItemsManager.getRolledItems();
        boolean requireRolled = config.requireRolledUnlockedForGe();
        for (int i = 0; i < children.length; i += 3)
        {
            int offerItemId = children[i + 2].getItemId();
            boolean isObtained = obtained.contains(offerItemId);
            boolean isRolled = rolled.contains(offerItemId);
            boolean hide = requireRolled ? !(isObtained && isRolled) : !isRolled;
            if (hide)
            {
                children[i].setHidden(true);
                children[i + 1].setOpacity(70);
                children[i + 2].setOpacity(70);
            }
        }
    }

    private boolean canProcessItemEvents()
    {
        return isCurrentAccountEnabledForRollBound()
                && firebaseSyncService.getStatus() == FirebaseSyncService.Status.CONNECTED
                && tradeableItemsInitialized
                && rollAnimationManager.hasTradeablesReady();
    }

    /**
     * Trigger one group roll. Single/manual rolls are represented as one-member batches so they
     * share the same deterministic playback path as simultaneous inventory rolls.
     */
    private void triggerGroupRoll(int obtainedItemId, boolean manual)
    {
        triggerGroupRollBatch(java.util.Collections.singletonList(obtainedItemId), manual);
    }

    /**
     * Creates an authoritative ordered batch for items obtained in the same inventory update.
     * Every event shares batchId + createdAt and receives a stable sequence number.
     */
    private void triggerGroupRollBatch(java.util.List<Integer> obtainedItemIds, boolean manual)
    {
        if (!isCurrentAccountEnabledForRollBound()
                || firebaseSyncService.getStatus() != FirebaseSyncService.Status.CONNECTED
                || !tradeableItemsInitialized)
        {
            return;
        }

        final java.util.List<Integer> triggers = new java.util.ArrayList<>();
        if (manual)
        {
            triggers.add(-1);
        }
        else if (obtainedItemIds != null)
        {
            final java.util.Set<Integer> seen = new java.util.LinkedHashSet<>();
            for (Integer itemId : obtainedItemIds)
            {
                if (itemId != null && itemId > 0 && seen.add(itemId))
                {
                    triggers.add(itemId);
                }
            }
        }
        if (triggers.isEmpty())
        {
            return;
        }

        // If everything is already unlocked, the obtain still needs to reach the shared database.
        boolean anyLocked = false;
        for (int id : allTradeableItems)
        {
            if (!rolledItemsManager.isRolled(id))
            {
                anyLocked = true;
                break;
            }
        }

        final long createdAt = System.currentTimeMillis();
        if (!anyLocked)
        {
            if (!manual)
            {
                firebaseSyncService.publishObtainedBatch(triggers, createdAt);
            }
            return;
        }

        String rollerName = "Someone";
        try
        {
            if (client != null && client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
            {
                rollerName = client.getLocalPlayer().getName();
            }
        }
        catch (Exception ignored) {}

        final String rollerNameFinal = rollerName;
        final String batchId = java.util.UUID.randomUUID().toString();
        final int batchSize = triggers.size();
        final java.util.List<FirebaseRollEvent> events = new java.util.ArrayList<>(batchSize);

        for (int sequence = 0; sequence < batchSize; sequence++)
        {
            final int obtainedItemId = triggers.get(sequence);

            final java.util.List<Integer> locked = new java.util.ArrayList<>();
            for (int id : allTradeableItems)
            {
                if (!rolledItemsManager.isRolled(id))
                {
                    locked.add(id);
                }
            }
            if (locked.isEmpty())
            {
                break;
            }

            int unlockedCount = 0;
            try
            {
                unlockedCount = rolledItemsManager.getRolledItems().size();
            }
            catch (Exception ignored) { /* default 0 */ }

            int choiceTarget = 2;
            if (unlockedCount >= 2000) choiceTarget = 5;
            else if (unlockedCount >= 1250) choiceTarget = 4;
            else if (unlockedCount >= 500) choiceTarget = 3;

            java.util.Collections.shuffle(locked, groupRandom);
            final int count = Math.min(Math.min(5, choiceTarget), locked.size());
            final java.util.List<Integer> optionIds =
                    new java.util.ArrayList<>(locked.subList(0, count));

            final String eventId = java.util.UUID.randomUUID().toString();
            synchronized (processedEventIds)
            {
                rememberProcessed(processedEventIds, eventId);
            }

            events.add(new FirebaseRollEvent(
                    eventId,
                    createdAt,
                    rollerNameFinal,
                    obtainedItemId,
                    manual,
                    optionIds,
                    batchId,
                    sequence,
                    batchSize));
        }

        if (events.isEmpty())
        {
            if (!manual)
            {
                firebaseSyncService.publishObtainedBatch(triggers, createdAt);
            }
            return;
        }

        // Publish the entire batch in one PATCH, then use exactly the same ordering metadata locally.
        firebaseSyncService.publishRollStarts(events);

        for (FirebaseRollEvent firebaseEvent : events)
        {
            final String eventIdFinal = firebaseEvent.getEventId();
            final int obtainedItemIdFinal = firebaseEvent.getObtainedItemId();
            final boolean manualFinal = firebaseEvent.isManual();

            rollAnimationManager.enqueueSyncedChoiceRoll(
                    eventIdFinal,
                    obtainedItemIdFinal,
                    firebaseEvent.getOptions(),
                    manualFinal,
                    rollerNameFinal,
                    firebaseEvent.getCreatedAt(),
                    firebaseEvent.getBatchId(),
                    firebaseEvent.getSequence(),
                    true,
                    chosenItemId -> {
                        final long pickedAt = System.currentTimeMillis();

                        synchronized (processedPickEventIds)
                        {
                            rememberProcessed(processedPickEventIds, eventIdFinal);
                        }
                        firebaseSyncService.publishRollPick(firebaseEvent, chosenItemId, pickedAt);

                        try { rolledItemsManager.markRolled(chosenItemId); } catch (Exception ignored) {}
                        try { rolledByManager.recordRoll(chosenItemId, rollerNameFinal, pickedAt); } catch (Exception ignored) {}
                        try { rollHistoryManager.recordEvent(eventIdFinal, rollerNameFinal, obtainedItemIdFinal, chosenItemId, pickedAt, manualFinal); } catch (Exception ignored) {}
                    });
        }
    }

    @Subscribe
    public void onItemSpawned(ItemSpawned event)
    {
        if (!canProcessItemEvents()) return;

        TileItem tileItem = event.getItem();
        int itemId = EnsouledHeadMapping.toTradeableId(tileItem.getId());
        int canonicalItemId = itemManager.canonicalize(itemId);
        if (!isGeTradeable(canonicalItemId) || isNotTracked(canonicalItemId)
                || ItemsFilter.isBlocked(canonicalItemId))
        {
            return;
        }
        if (tileItem.getOwnership() != TileItem.OWNERSHIP_SELF)
        {
            return;
        }
        if (!obtainedItemsManager.isObtained(canonicalItemId))
        {
            obtainedItemsManager.markObtained(canonicalItemId);
            triggerGroupRoll(canonicalItemId, false);
            refreshDropsViewerIfOpen();
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (!canProcessItemEvents()) return;

        if (event.getContainerId() == InventoryID.INV)
        {
            Set<Integer> processed = new HashSet<>();
            java.util.List<Integer> simultaneousTriggers = new java.util.ArrayList<>();
            for (net.runelite.api.Item item : event.getItemContainer().getItems())
            {
                int rawItemId = item.getId();
                int mapped = EnsouledHeadMapping.toTradeableId(rawItemId);
                int canonicalId = itemManager.canonicalize(mapped);
                if (!isGeTradeable(canonicalId) || isNotTracked(canonicalId)
                        || ItemsFilter.isBlocked(canonicalId))
                {
                    continue;
                }

                if (!processed.contains(canonicalId) && !obtainedItemsManager.isObtained(canonicalId))
                {
                    obtainedItemsManager.markObtained(canonicalId);
                    simultaneousTriggers.add(canonicalId);
                    processed.add(canonicalId);
                }
            }

            if (!simultaneousTriggers.isEmpty())
            {
                triggerGroupRollBatch(simultaneousTriggers, false);
                refreshDropsViewerIfOpen();
            }
        }
    }

    public boolean isNormalWorld()
    {
        EnumSet<WorldType> worldTypes = client.getWorldType();
        return !(WorldType.isPvpWorld(worldTypes)
                || worldTypes.contains(WorldType.BOUNTY)
                || worldTypes.contains(WorldType.HIGH_RISK)
                || worldTypes.contains(WorldType.LAST_MAN_STANDING)
                || worldTypes.contains(WorldType.DEADMAN)
                || worldTypes.contains(WorldType.SEASONAL)
                || worldTypes.contains(WorldType.BETA_WORLD)
                || worldTypes.contains(WorldType.PVP_ARENA)
                || worldTypes.contains(WorldType.QUEST_SPEEDRUNNING)
                || worldTypes.contains(WorldType.TOURNAMENT_WORLD));
    }

    private void publishCachedNpcData()
    {
        if (!config.enableTrackerTelemetry() || !isCurrentAccountEnabledForRollBound())
        {
            return;
        }

        dropCache.getAllNpcDataAsync()
                .thenAccept(allNpcData -> allNpcData.forEach(trackerTelemetryService::publishNpcDropData));
    }

    private void refreshDropsViewerIfOpen()
    {
        if (rollBoundPanel != null)
        {
            SwingUtilities.invokeLater(rollBoundPanel::refreshDropsView);
        }
    }

    public boolean isGeTradeable(int itemId)
    {
        ItemComposition comp = itemManager.getItemComposition(itemId);
        return comp != null && comp.isGeTradeable();
    }

    public boolean isNotTracked(int itemId)
    {
        return itemId == ItemID.COINS
                || itemId == ItemID.BOUGHT_OSRS_BOND
                || itemId == ItemID.OSRS_BOND
                || itemId == ItemID.MACRO_DIGGER_COFFIN_OBJECT_1
                || itemId == ItemID.MACRO_DIGGER_COFFIN_OBJECT_2
                || itemId == ItemID.MACRO_DIGGER_COFFIN_OBJECT_3
                || itemId == ItemID.MACRO_DIGGER_COFFIN_OBJECT_4
                || itemId == ItemID.MACRO_DIGGER_COFFIN_OBJECT_5;
    }

    public boolean isInPlay(int itemId)
    {
        return allTradeableItems.contains(itemId);
    }
}
