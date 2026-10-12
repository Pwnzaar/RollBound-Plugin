package com.rollbound.managers;

import com.rollbound.RollBoundOverlay;
import com.rollbound.RollBoundPanel;
import com.rollbound.RollBoundConfig;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.ColorUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.util.*;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages roll animations and result announcements.
 *
 * Coordinates local and Firebase-synchronised roll animations.
 */
@Singleton
public class RollAnimationManager
{
    @Inject private ItemManager itemManager;
    @Inject private Client client;
    @Inject private ClientThread clientThread;

    @Inject private RolledItemsManager rolledManager;

    @Inject private RolledByManager rolledByManager;
    @Inject private RollHistoryManager rollHistoryManager;

    @Inject private RollBoundOverlay overlay;
    @Inject private RollBoundConfig config;

    private RollBoundPanel rollBoundPanel;

    public void setRollBoundPanel(RollBoundPanel rollBoundPanel)
    {
        this.rollBoundPanel = rollBoundPanel;
    }

    private Set<Integer> allTradeableItems = Collections.emptySet();

    private static final class RollRequest
    {
        final String eventId; // shared event id for history de-dupe
        final int obtainedItemId;
        final Integer forcedRolledItemId; // null for local RNG
        final java.util.List<Integer> forcedChoiceOptions; // null unless this is a RollBound selection roll
        final boolean choiceIsRoller;
        final java.util.function.IntConsumer onChoiceSelected;
        final boolean manual;
        final String rollerName;
        final long createdAt;
        final String batchId;
        final int sequence;

        RollRequest(String eventId,
                    int obtainedItemId,
                    Integer forcedRolledItemId,
                    java.util.List<Integer> forcedChoiceOptions,
                    boolean choiceIsRoller,
                    java.util.function.IntConsumer onChoiceSelected,
                    boolean manual,
                    String rollerName,
                    long createdAt,
                    String batchId,
                    int sequence)
        {
            this.eventId = eventId;
            this.obtainedItemId = obtainedItemId;
            this.forcedRolledItemId = forcedRolledItemId;
            this.forcedChoiceOptions = forcedChoiceOptions;
            this.choiceIsRoller = choiceIsRoller;
            this.onChoiceSelected = onChoiceSelected;
            this.manual = manual;
            this.rollerName = rollerName;
            this.createdAt = createdAt;
            this.batchId = batchId;
            this.sequence = Math.max(0, sequence);
        }

        String orderingGroupId()
        {
            if (batchId != null && !batchId.trim().isEmpty()) return batchId;
            return eventId == null ? "" : eventId;
        }
    }

    private static final Comparator<RollRequest> ROLL_REQUEST_ORDER =
            Comparator.comparingLong((RollRequest request) -> request.createdAt)
                    .thenComparing(RollRequest::orderingGroupId)
                    .thenComparingInt(request -> request.sequence)
                    .thenComparing(request -> request.eventId == null ? "" : request.eventId);

    private final Queue<RollRequest> rollQueue = new PriorityBlockingQueue<>(11, ROLL_REQUEST_ORDER);
    private final Set<ScheduledFuture<?>> scheduledTasks = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService executor = newExecutor();

    private volatile boolean isRolling = false;
    /** Incremented whenever the local roll UI is reset so interrupted workers cannot revive stale state. */
    private volatile long uiGeneration = 0L;

    // tradeables gating
    private volatile boolean tradeablesReady = false;

    private static final int SNAP_WINDOW_MS = 350;
    /** Small grace period after a RollBound selection so the exit animation can begin before the next roll starts. */
    private static final long CHOICE_EXIT_GRACE_MS = 150L;
    private final Random random = new Random();


    private String getLocalPlayerName()
    {
        try
        {
            if (client != null && client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
            {
                return client.getLocalPlayer().getName();
            }
        }
        catch (Exception ignored) {}
        return "Someone";
    }


    private volatile boolean manualRoll = false;

    /** Called by plugin after building tradeables. */
    public void setAllTradeableItems(Set<Integer> allTradeableItems)
    {
        this.allTradeableItems = (allTradeableItems == null)
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(allTradeableItems));
        this.tradeablesReady = !this.allTradeableItems.isEmpty();
    }

    public boolean hasTradeablesReady()
    {
        return tradeablesReady && allTradeableItems != null && !allTradeableItems.isEmpty();
    }

    /**
     * Enqueue an obtained item that should trigger a roll.
     *
     * @param obtainedItemId item that caused the roll
     */
    public void enqueueRoll(int obtainedItemId)
    {
        rollQueue.offer(new RollRequest(null, obtainedItemId, null, null, false, null, manualRoll, getLocalPlayerName(), System.currentTimeMillis(), null, 0));
    }

    /** Enqueue a group-synchronised roll with a forced final rolled item id. */
    public void enqueueSyncedRoll(String eventId, int obtainedItemId, int rolledItemId, boolean manual, String rollerName, long createdAt)
    {
        rollQueue.offer(new RollRequest(eventId, obtainedItemId, rolledItemId, null, false, null, manual, rollerName, createdAt, null, 0));
    }

    /** Enqueue a group-synchronised RollBound selection roll (up to 5 duplicated strips). */
    public void enqueueSyncedChoiceRoll(String eventId,
                                        int obtainedItemId,
                                        java.util.List<Integer> optionIds,
                                        boolean manual,
                                        String rollerName,
                                        long createdAt,
                                        boolean isRoller,
                                        java.util.function.IntConsumer onSelected)
    {
        enqueueSyncedChoiceRoll(eventId, obtainedItemId, optionIds, manual, rollerName, createdAt,
                null, 0, isRoller, onSelected);
    }

    /** Enqueue an explicitly ordered RollBound batch member. */
    public void enqueueSyncedChoiceRoll(String eventId,
                                        int obtainedItemId,
                                        java.util.List<Integer> optionIds,
                                        boolean manual,
                                        String rollerName,
                                        long createdAt,
                                        String batchId,
                                        int sequence,
                                        boolean isRoller,
                                        java.util.function.IntConsumer onSelected)
    {
        java.util.List<Integer> safe = (optionIds == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(optionIds);
        rollQueue.offer(new RollRequest(eventId, obtainedItemId, null, safe, isRoller, onSelected,
                manual, rollerName, createdAt, batchId, sequence));
    }

    /**
     * Process pending rolls if idle.
     *
     * Animation timing is implemented with scheduled continuations rather than blocking a
     * worker thread. This keeps RollBound compliant with Plugin Hub guidance and makes reset/
     * shutdown cancellation deterministic.
     */
    public void process()
    {
        if (!hasTradeablesReady())
        {
            return; // queue stays intact until tradeables are built
        }

        if (!isRolling && !rollQueue.isEmpty())
        {
            RollRequest req = rollQueue.poll();
            if (req == null)
            {
                return;
            }
            isRolling = true;
            final long runGeneration = uiGeneration;
            ScheduledExecutorService current = executor;
            if (current == null || current.isShutdown())
            {
                finishRoll(runGeneration);
                return;
            }
            current.execute(() -> beginRoll(req, runGeneration));
        }
    }

    private void beginRoll(RollRequest req, long runGeneration)
    {
        try
        {
            if (!isCurrent(runGeneration) || !hasTradeablesReady())
            {
                finishRoll(runGeneration);
                return;
            }

            final int obtainedItemId = req.obtainedItemId;
            final Integer forcedRolled = req.forcedRolledItemId;
            final java.util.List<Integer> forcedChoice = req.forcedChoiceOptions;
            final String rollerName = (req.rollerName != null && !req.rollerName.trim().isEmpty())
                    ? req.rollerName.trim() : "Someone";
            final long createdAt = req.createdAt > 0 ? req.createdAt : System.currentTimeMillis();

            if (forcedChoice != null)
            {
                final java.util.List<Integer> eligibleChoice = new java.util.ArrayList<>();
                for (Integer itemId : forcedChoice)
                {
                    if (itemId != null && allTradeableItems.contains(itemId))
                    {
                        eligibleChoice.add(itemId);
                    }
                }
                if (eligibleChoice.isEmpty())
                {
                    finishRoll(runGeneration);
                    return;
                }

                final int rollDuration = 3000;
                overlay.startChoiceAnimation(req.eventId, eligibleChoice, rollDuration,
                        this::getRandomLockedItem, req.choiceIsRoller, req.onChoiceSelected, rollerName);
                schedule(runGeneration,
                        () -> afterChoiceAnimation(req, runGeneration, System.currentTimeMillis()),
                        rollDuration + SNAP_WINDOW_MS);
                return;
            }

            final int rollDuration = 3000;
            if (forcedRolled != null && !allTradeableItems.contains(forcedRolled))
            {
                finishRoll(runGeneration);
                return;
            }
            final int winnerId = forcedRolled != null ? forcedRolled : getRandomLockedItem();
            overlay.startRollAnimation(winnerId, rollDuration, this::getRandomLockedItem);
            schedule(runGeneration,
                    () -> completeStandardRoll(req, winnerId, rollerName, createdAt, runGeneration,
                            Math.max(0, overlay.getHighlightDurationMs() - SNAP_WINDOW_MS)),
                    rollDuration + SNAP_WINDOW_MS);
        }
        catch (Exception error)
        {
            finishRoll(runGeneration);
        }
    }

    private void afterChoiceAnimation(RollRequest req, long runGeneration, long waitStartedAt)
    {
        if (!isCurrent(runGeneration))
        {
            return;
        }

        try
        {
            if (!req.choiceIsRoller)
            {
                overlay.applyPendingRemoteChoiceSelection(req.eventId);
            }
            waitForChoice(req, runGeneration, waitStartedAt);
        }
        catch (Exception error)
        {
            finishRoll(runGeneration);
        }
    }

    private void waitForChoice(RollRequest req, long runGeneration, long waitStartedAt)
    {
        if (!isCurrent(runGeneration))
        {
            return;
        }

        try
        {
            if (!req.choiceIsRoller)
            {
                overlay.applyPendingRemoteChoiceSelection(req.eventId);
            }

            if (!overlay.isChoicePending(req.eventId))
            {
                schedule(runGeneration, () -> finishRoll(runGeneration), CHOICE_EXIT_GRACE_MS);
                return;
            }

            final long maxWaitMs = req.choiceIsRoller
                    ? 10 * 60 * 1000L
                    : 2 * 60 * 1000L;
            if (System.currentTimeMillis() - waitStartedAt > maxWaitMs)
            {
                overlay.resetUiState();
                schedule(runGeneration, () -> finishRoll(runGeneration), CHOICE_EXIT_GRACE_MS);
                return;
            }

            schedule(runGeneration, () -> waitForChoice(req, runGeneration, waitStartedAt), 50L);
        }
        catch (Exception error)
        {
            finishRoll(runGeneration);
        }
    }

    private void completeStandardRoll(RollRequest req,
                                      int rolledItemId,
                                      String rollerName,
                                      long createdAt,
                                      long runGeneration,
                                      long remainingHighlight)
    {
        if (!isCurrent(runGeneration))
        {
            return;
        }

        try
        {
            if (req.forcedRolledItemId == null)
            {
                rolledManager.markRolled(rolledItemId);
            }

            if (rolledByManager != null)
            {
                rolledByManager.recordRoll(rolledItemId, rollerName, createdAt);
            }

            if (rollHistoryManager != null)
            {
                rollHistoryManager.recordEvent(req.eventId, rollerName, req.obtainedItemId,
                        rolledItemId, createdAt, req.manual);
            }

            announceRollToChat(rollerName, rolledItemId, req.obtainedItemId, req.manual);

            if (remainingHighlight > 0)
            {
                schedule(runGeneration, () -> finishRoll(runGeneration), remainingHighlight);
            }
            else
            {
                finishRoll(runGeneration);
            }
        }
        catch (Exception error)
        {
            finishRoll(runGeneration);
        }
    }

    private void schedule(long runGeneration, Runnable task, long delayMs)
    {
        ScheduledExecutorService current = executor;
        if (current == null || current.isShutdown() || !isCurrent(runGeneration))
        {
            return;
        }
        ScheduledFuture<?> future = current.schedule(() ->
        {
            if (isCurrent(runGeneration))
            {
                task.run();
            }
        }, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
        scheduledTasks.add(future);
        scheduledTasks.removeIf(done -> done.isDone() || done.isCancelled());
    }

    private boolean isCurrent(long runGeneration)
    {
        return runGeneration == uiGeneration;
    }

    private void finishRoll(long runGeneration)
    {
        if (runGeneration == uiGeneration)
        {
            manualRoll = false;
            isRolling = false;
        }
    }

    /**
     * Cancels the active/queued visual rolls without touching shared Firebase progress.
     * Used by the panel reset button and on logout/account transitions.
     */
    public synchronized void resetUiState()
    {
        uiGeneration++;
        rollQueue.clear();
        manualRoll = false;
        isRolling = false;
        if (overlay != null)
        {
            overlay.resetUiState();
        }

        cancelScheduledTasks();
        ScheduledExecutorService previous = executor;
        if (previous != null)
        {
            previous.shutdownNow();
        }
        executor = newExecutor();
    }

    public boolean isAwaitingLocalChoice()
    {
        return overlay != null && overlay.isAwaitingLocalChoice();
    }

    public boolean isRolling()
    {
        return isRolling;
    }

    /**
     * Pick a random locked item to display during the roll.
     */
    private int getRandomLockedItem()
    {
        if (!hasTradeablesReady())
        {
            return overlay.getFinalItem();
        }

        List<Integer> locked = new ArrayList<>();
        for (int id : allTradeableItems)
        {
            if (!rolledManager.isRolled(id))
            {
                locked.add(id);
            }
        }

        if (locked.isEmpty())
        {
            return overlay.getFinalItem();
        }

        return locked.get(random.nextInt(locked.size()));
    }

    
    private void announceRollToChat(String rollerName, int rolledItemId, int obtainedItemId, boolean wasManual)
    {
        final String who = (rollerName != null && !rollerName.trim().isEmpty()) ? rollerName.trim() : "Someone";

        clientThread.invoke(() ->
        {
            String rolledTag = ColorUtil.wrapWithColorTag(
                    getItemName(rolledItemId),
                    config.unlockedItemColor()
            );

            String message = who + " unlocked " + rolledTag;

            // For automatic rolls, include the trigger item in the message.
            if (!wasManual && obtainedItemId > 0)
            {
                String obtainedTag = ColorUtil.wrapWithColorTag(
                        getItemName(obtainedItemId),
                        config.rolledItemColor()
                );
                message += " by obtaining " + obtainedTag;
            }

            if (wasManual)
            {
                message += " (manual roll)";
            }

            client.addChatMessage(
                    ChatMessageType.GAMEMESSAGE,
                    "",
                    message,
                    null
            );

            if (rollBoundPanel != null)
            {
                SwingUtilities.invokeLater(rollBoundPanel::updatePanel);
            }
        });
    }

private String getItemName(int itemId)
    {
        ItemComposition comp = itemManager.getItemComposition(itemId);
        return comp.getName();
    }

    public synchronized void startUp()
    {
        if (executor == null || executor.isShutdown() || executor.isTerminated())
        {
            executor = newExecutor();
        }
    }

    private static ScheduledExecutorService newExecutor()
    {
        return Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread thread = new Thread(r, "RollBound-Roll-Animation");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void shutdown()
    {
        uiGeneration++;
        rollQueue.clear();
        manualRoll = false;
        isRolling = false;
        if (overlay != null)
        {
            overlay.resetUiState();
        }
        cancelScheduledTasks();
        if (executor != null)
        {
            executor.shutdownNow();
        }
    }

    private void cancelScheduledTasks()
    {
        for (ScheduledFuture<?> future : scheduledTasks)
        {
            future.cancel(false);
        }
        scheduledTasks.clear();
    }
}
