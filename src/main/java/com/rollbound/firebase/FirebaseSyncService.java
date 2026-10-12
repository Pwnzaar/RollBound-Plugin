package com.rollbound.firebase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rollbound.RollBoundConfig;
import com.rollbound.backup.LocalBackupManager;
import net.runelite.client.config.ConfigManager;
import com.rollbound.managers.ObtainedItemsManager;
import com.rollbound.managers.RollHistoryManager;
import com.rollbound.managers.RolledByManager;
import com.rollbound.managers.RolledItemsManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Firebase is RollBound's single shared source of truth.
 *
 * Persistent state:
 *   /obtainedItems/{itemId} = recorded epoch millis
 *   /rolledItems/{itemId} = unlock epoch millis
 *   /rolledBy/{itemId} = { by, at }
 *   /rollHistory/{eventId} = RollEvent
 *
 * Short-lived live animation state:
 *   /liveRolls/{eventId} = FirebaseRollEvent
 *
 * The REST API performs idempotent writes while an SSE stream tells every connected
 * RuneLite client to refresh immediately when another player changes the database.
 */
@Singleton
public final class FirebaseSyncService
{
    private static final Logger log = LoggerFactory.getLogger(FirebaseSyncService.class);
    public enum Status
    {
        NOT_CONFIGURED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    public interface RollListener
    {
        void onRollStarted(FirebaseRollEvent event);
        void onRollPicked(FirebaseRollEvent event);
    }

    private static final int SCHEMA_VERSION = 1;
    private static final long LIVE_EVENT_MAX_AGE_MS = TimeUnit.MINUTES.toMillis(2);
    private static final long LIVE_EVENT_DELETE_DELAY_SECONDS = 90L;
    private static final long REFRESH_DEBOUNCE_MS = 100L;
    private static final int MAX_SEEN_LIVE_EVENTS = 1000;

    private final FirebaseDatabaseClient databaseClient;
    private final Gson gson;
    private final RollBoundConfig config;
    private final ConfigManager configManager;
    private final ObtainedItemsManager obtainedItemsManager;
    private final RolledItemsManager rolledItemsManager;
    private final RolledByManager rolledByManager;
    private final RollHistoryManager rollHistoryManager;
    private final LocalBackupManager localBackupManager;

    private final Set<String> observedStarts = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> observedPicks = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, Boolean> pendingRuleWrites = new ConcurrentHashMap<>();
    private final AtomicBoolean refreshQueued = new AtomicBoolean(false);
    private final Set<ScheduledFuture<?>> scheduledTasks = ConcurrentHashMap.newKeySet();
    private final FirebaseRealtimeState realtimeState;

    private volatile Status status = Status.NOT_CONFIGURED;
    private volatile String lastError;
    private volatile String activeDatabaseUrl;
    private volatile boolean running;
    private volatile long generation;

    private volatile ScheduledExecutorService executor;
    private volatile FirebaseSseStream stream;
    private volatile RollListener rollListener;
    private volatile Runnable statusListener;
    private volatile FirebaseGameRules gameRules;

    @Inject
    public FirebaseSyncService(
            FirebaseDatabaseClient databaseClient,
            Gson gson,
            RollBoundConfig config,
            ConfigManager configManager,
            ObtainedItemsManager obtainedItemsManager,
            RolledItemsManager rolledItemsManager,
            RolledByManager rolledByManager,
            RollHistoryManager rollHistoryManager,
            LocalBackupManager localBackupManager)
    {
        this.databaseClient = databaseClient;
        this.gson = gson;
        this.config = config;
        this.configManager = configManager;
        this.obtainedItemsManager = obtainedItemsManager;
        this.rolledItemsManager = rolledItemsManager;
        this.rolledByManager = rolledByManager;
        this.rollHistoryManager = rollHistoryManager;
        this.localBackupManager = localBackupManager;
        this.realtimeState = new FirebaseRealtimeState(gson);
    }

    public synchronized void setRollListener(RollListener rollListener)
    {
        this.rollListener = rollListener;
    }

    public synchronized void setStatusListener(Runnable statusListener)
    {
        this.statusListener = statusListener;
    }

    public Status getStatus()
    {
        return status;
    }

    public String getLastError()
    {
        return lastError;
    }

    public String getActiveDatabaseUrl()
    {
        return activeDatabaseUrl;
    }

    public boolean isConfigured()
    {
        return databaseClient.normaliseDatabaseUrl(config.firebaseDatabaseUrl()) != null;
    }

    public synchronized void start()
    {
        // Reset backup de-duplication. The first authoritative snapshot becomes the baseline.
        localBackupManager.resetSession();
        // Never show or act on state from a previously configured database while reconnecting.
        stopInternal(true);

        String databaseUrl = databaseClient.normaliseDatabaseUrl(config.firebaseDatabaseUrl());
        activeDatabaseUrl = databaseUrl;
        generation++;
        long currentGeneration = generation;

        if (databaseUrl == null)
        {
            running = false;
            clearSharedState();
            setStatus(Status.NOT_CONFIGURED, null);
            return;
        }

        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "RollBound-Firebase-Sync");
            thread.setDaemon(true);
            return thread;
        });

        setStatus(Status.CONNECTING, null);

        // The root SSE subscription sends an initial put containing the current database state,
        // followed by put/patch deltas. Apply those payloads directly instead of performing a
        // second GET of /.json for every event. Reconnecting the stream naturally delivers a
        // fresh initial root put, so no periodic full-state polling is required.
        stream = databaseClient.stream(
                databaseUrl,
                (eventType, payload) -> handleStreamEvent(currentGeneration, eventType, payload),
                () -> {
                    if (running && generation == currentGeneration && status != Status.CONNECTED)
                    {
                        setStatus(Status.CONNECTING, null);
                    }
                },
                () -> {
                    if (running && generation == currentGeneration && status == Status.CONNECTED)
                    {
                        setStatus(Status.CONNECTING, null);
                    }
                });
        stream.start();
    }

    public synchronized void restart()
    {
        start();
    }

    public synchronized void stop()
    {
        stopInternal(false);
        setStatus(Status.NOT_CONFIGURED, null);
    }

    private synchronized void stopInternal(boolean clearState)
    {
        running = false;
        generation++;
        refreshQueued.set(false);

        if (stream != null)
        {
            stream.stop();
            stream = null;
        }
        cancelScheduledTasks();
        if (executor != null)
        {
            executor.shutdownNow();
            executor = null;
        }

        observedStarts.clear();
        observedPicks.clear();
        pendingRuleWrites.clear();
        gameRules = null;
        realtimeState.reset();
        activeDatabaseUrl = null;

        if (clearState)
        {
            clearSharedState();
        }
    }

    /**
     * Re-apply the latest in-memory realtime snapshot. This is intentionally local-only: the SSE
     * stream is authoritative and automatically sends a full root snapshot on (re)connect.
     */
    public void requestRefresh()
    {
        ScheduledExecutorService current = executor;
        long currentGeneration = generation;
        if (!running || current == null || current.isShutdown() || !realtimeState.isInitialised())
        {
            return;
        }
        if (!refreshQueued.compareAndSet(false, true))
        {
            return;
        }

        schedule(current, () -> {
            refreshQueued.set(false);
            if (!running || currentGeneration != generation)
            {
                return;
            }
            try
            {
                applySnapshot(realtimeState.snapshot());
            }
            catch (Exception error)
            {
                log.error("Failed to re-apply cached Firebase RollBound state", error);
                setStatus(Status.ERROR, "Invalid database data");
            }
        }, REFRESH_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }


    /**
     * Publish one gameplay-affecting RuneLite config value as a shared Firebase rule.
     * Remote rule application updates the cached snapshot before ConfigManager, so echoed
     * ConfigChanged events are ignored instead of causing write loops.
     */
    public void publishGameRule(String key)
    {
        if (!FirebaseGameRules.isSharedKey(key))
        {
            return;
        }

        boolean value = FirebaseGameRules.configValue(config, key);
        FirebaseGameRules currentRules = gameRules;
        if (currentRules != null && currentRules.value(key) == value)
        {
            return;
        }

        pendingRuleWrites.put(key, value);

        JsonObject updates = new JsonObject();
        updates.addProperty("gameRules/schemaVersion", FirebaseGameRules.SCHEMA_VERSION);
        updates.addProperty("gameRules/" + key, value);
        updates.addProperty("gameRules/updatedAt", System.currentTimeMillis());
        submitPersistentPatch("shared game rule " + key, updates, generation, 0);
    }

    /** Persist an obtained trigger item immediately. Safe to retry because the write is idempotent. */
    public void publishObtained(int itemId)
    {
        if (itemId <= 0)
        {
            return;
        }
        JsonObject updates = new JsonObject();
        updates.addProperty("schemaVersion", SCHEMA_VERSION);
        updates.addProperty("obtainedItems/" + itemId, System.currentTimeMillis());
        submitPersistentPatch("obtained item " + itemId, updates, generation, 0);
    }

    /** Publish one live roll. Kept for compatibility; ordered batches use publishRollStarts. */
    public void publishRollStart(FirebaseRollEvent event)
    {
        if (event == null)
        {
            return;
        }
        publishRollStarts(Collections.singletonList(event));
    }

    /**
     * Publish every start event from one simultaneous roll batch in a single Firebase PATCH.
     *
     * Teammates therefore observe the complete batch and reconstruct the same sequence instead of
     * relying on the order in which independent network requests happen to arrive.
     */
    public void publishRollStarts(Collection<FirebaseRollEvent> events)
    {
        if (events == null || events.isEmpty() || !running || activeDatabaseUrl == null)
        {
            return;
        }

        JsonObject updates = new JsonObject();
        updates.addProperty("schemaVersion", SCHEMA_VERSION);
        int validEvents = 0;
        for (FirebaseRollEvent event : events)
        {
            if (event == null || isBlank(event.getEventId()))
            {
                continue;
            }
            if (event.getObtainedItemId() > 0)
            {
                updates.addProperty("obtainedItems/" + event.getObtainedItemId(), event.getCreatedAt());
            }
            updates.add("liveRolls/" + event.getEventId(), gson.toJsonTree(event));
            validEvents++;
        }
        if (validEvents == 0)
        {
            return;
        }

        final int publishedCount = validEvents;
        databaseClient.patch(activeDatabaseUrl, "/", updates).whenComplete((ignored, error) -> {
            if (error != null && running)
            {
                log.warn("Could not publish RollBound roll batch ({} events)", publishedCount, error);
            }
        });
    }

    /** Persist several obtained items together when no rolls remain to be generated. */
    public void publishObtainedBatch(Collection<Integer> itemIds, long recordedAt)
    {
        if (itemIds == null || itemIds.isEmpty() || !running || activeDatabaseUrl == null)
        {
            return;
        }

        long at = recordedAt > 0 ? recordedAt : System.currentTimeMillis();
        JsonObject updates = new JsonObject();
        updates.addProperty("schemaVersion", SCHEMA_VERSION);
        int count = 0;
        for (Integer itemId : itemIds)
        {
            if (itemId == null || itemId <= 0)
            {
                continue;
            }
            updates.addProperty("obtainedItems/" + itemId, at);
            count++;
        }
        if (count == 0)
        {
            return;
        }
        submitPersistentPatch("obtained item batch", updates, generation, 0);
    }

    /**
     * Atomically publish the final result and all shared state affected by it.
     * A multi-location Firebase PATCH prevents one player from overwriting another player's progress.
     */
    public void publishRollPick(FirebaseRollEvent startEvent, int chosenItemId, long pickedAt)
    {
        if (startEvent == null || isBlank(startEvent.getEventId()) || chosenItemId <= 0)
        {
            return;
        }

        long at = pickedAt > 0 ? pickedAt : System.currentTimeMillis();
        String roller = normalisePlayerName(startEvent.getRollerName());

        FirebaseRollEvent completed = new FirebaseRollEvent(
                startEvent.getEventId(),
                startEvent.getCreatedAt(),
                roller,
                startEvent.getObtainedItemId(),
                startEvent.isManual(),
                startEvent.getOptions(),
                startEvent.getBatchId(),
                startEvent.getSequence(),
                startEvent.getBatchSize()).withPick(chosenItemId, at);
        JsonObject liveJson = gson.toJsonTree(completed).getAsJsonObject();

        RollHistoryManager.RollEvent historyEvent = new RollHistoryManager.RollEvent(
                startEvent.getEventId(), at, roller,
                startEvent.getObtainedItemId(), chosenItemId, startEvent.isManual());
        RolledByManager.RolledByEntry rolledByEntry = new RolledByManager.RolledByEntry(roller, at);

        JsonObject updates = new JsonObject();
        updates.addProperty("schemaVersion", SCHEMA_VERSION);
        if (startEvent.getObtainedItemId() > 0)
        {
            updates.addProperty("obtainedItems/" + startEvent.getObtainedItemId(), startEvent.getCreatedAt());
        }
        updates.addProperty("rolledItems/" + chosenItemId, at);
        updates.add("rolledBy/" + chosenItemId, gson.toJsonTree(rolledByEntry));
        updates.add("rollHistory/" + startEvent.getEventId(), gson.toJsonTree(historyEvent));
        updates.add("liveRolls/" + startEvent.getEventId(), liveJson);

        long writeGeneration = generation;
        submitPersistentPatch("roll result " + startEvent.getEventId(), updates, writeGeneration, 0);

        ScheduledExecutorService current = executor;
        if (current != null && !current.isShutdown())
        {
            schedule(current, () -> deleteLiveRoll(startEvent.getEventId(), writeGeneration),
                    LIVE_EVENT_DELETE_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void handleStreamEvent(long expectedGeneration, String eventType, String payload)
    {
        ScheduledExecutorService current = executor;
        if (!running || expectedGeneration != generation || current == null || current.isShutdown())
        {
            return;
        }

        // Serialise state-cache mutation and manager updates on the Firebase sync executor.
        current.execute(() -> {
            if (!running || expectedGeneration != generation)
            {
                return;
            }
            try
            {
                FirebaseRealtimeState.ApplyResult result = realtimeState.apply(eventType, payload);
                if (result.isRollBoundStateChanged())
                {
                    applySnapshot(result.getSnapshot());
                }
                setStatus(Status.CONNECTED, null);
            }
            catch (Exception parseError)
            {
                log.error("Failed to apply Firebase realtime event", parseError);
                setStatus(Status.ERROR, "Invalid realtime database data");
            }
        });
    }

    private void applySnapshot(JsonElement rootElement)
    {
        JsonObject root = rootElement != null && rootElement.isJsonObject()
                ? rootElement.getAsJsonObject()
                : new JsonObject();

        synchroniseGameRules(root.get("gameRules"));

        obtainedItemsManager.replaceAll(readItemSet(root.get("obtainedItems")));
        rolledItemsManager.replaceAll(readItemSet(root.get("rolledItems")));
        rolledByManager.replaceAll(readRolledBy(root.get("rolledBy")));
        rollHistoryManager.replaceAll(readRollHistory(root.get("rollHistory")));

        // First snapshot establishes the local backup baseline. Later unseen history events
        // create one complete backup per roll after all four managers are in sync.
        localBackupManager.onAuthoritativeSnapshotApplied(rollHistoryManager.getSnapshot());

        processLiveRolls(root.get("liveRolls"));
        // Fallback: if a client saw the live start but missed the short-lived live pick update,
        // the persistent roll-history record still resolves the on-screen choice.
        processResolvedHistory(root.get("rollHistory"));
        pruneRemoteHistory(root.get("rollHistory"));
    }


    private void synchroniseGameRules(JsonElement element)
    {
        FirebaseGameRules remote = FirebaseGameRules.fromJson(element);
        if (remote == null)
        {
            // First RollBound client on a new database defines the group's initial rules.
            FirebaseGameRules initial = FirebaseGameRules.fromConfig(config);
            gameRules = initial;
            JsonObject updates = new JsonObject();
            updates.add("gameRules", initial.toJson());
            submitPersistentPatch("initial shared game rules", updates, generation, 0);
            return;
        }

        gameRules = remote;

        JsonObject legacyRuleReset = FirebaseGameRules.legacyRulesOffPatch(element);
        if (legacyRuleReset.size() > 0)
        {
            legacyRuleReset.addProperty("gameRules/schemaVersion", FirebaseGameRules.SCHEMA_VERSION);
            legacyRuleReset.addProperty("gameRules/updatedAt", System.currentTimeMillis());
            submitPersistentPatch("retired shared game rules", legacyRuleReset, generation, 0);
        }

        applyRemoteRuleUnlessPending(FirebaseGameRules.REQUIRE_ROLLED_UNLOCKED_FOR_GE,
                remote.isRequireRolledUnlockedForGe());
    }

    private void applyRemoteRuleUnlessPending(String key, boolean remoteValue)
    {
        Boolean pendingValue = pendingRuleWrites.get(key);
        if (pendingValue != null)
        {
            if (pendingValue == remoteValue)
            {
                pendingRuleWrites.remove(key, pendingValue);
            }
            else
            {
                // A GET that started before our write completed can contain the old value.
                // Keep the user's local choice until Firebase reflects the pending write.
                return;
            }
        }
        applyRemoteRule(key, remoteValue);
    }

    private void applyRemoteRule(String key, boolean value)
    {
        try
        {
            if (FirebaseGameRules.configValue(config, key) != value)
            {
                // Updating ConfigManager also updates RuneLite's settings UI and emits ConfigChanged,
                // so the normal local refresh path is reused on every client.
                configManager.setConfiguration("rollbound", key, value);
            }
        }
        catch (Exception error)
        {
            log.warn("Could not apply shared RollBound rule {}", key, error);
        }
    }

    private Set<Integer> readItemSet(JsonElement element)
    {
        class TimedItem
        {
            final int id;
            final long at;
            TimedItem(int id, long at) { this.id = id; this.at = at; }
        }

        List<TimedItem> items = new ArrayList<>();
        if (element == null || !element.isJsonObject())
        {
            return new LinkedHashSet<>();
        }

        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet())
        {
            try
            {
                int id = Integer.parseInt(entry.getKey());
                if (id <= 0 || entry.getValue() == null || entry.getValue().isJsonNull())
                {
                    continue;
                }

                JsonElement value = entry.getValue();
                long timestamp = 0L;
                boolean present = true;
                if (value.isJsonPrimitive())
                {
                    if (value.getAsJsonPrimitive().isBoolean())
                    {
                        present = value.getAsBoolean();
                    }
                    else if (value.getAsJsonPrimitive().isNumber())
                    {
                        timestamp = value.getAsLong();
                    }
                }
                if (present)
                {
                    items.add(new TimedItem(id, timestamp));
                }
            }
            catch (Exception ignored) {}
        }

        items.sort(Comparator.comparingLong((TimedItem item) -> item.at).thenComparingInt(item -> item.id));
        Set<Integer> ids = new LinkedHashSet<>();
        for (TimedItem item : items)
        {
            ids.add(item.id);
        }
        return ids;
    }

    private Map<Integer, RolledByManager.RolledByEntry> readRolledBy(JsonElement element)
    {
        Map<Integer, RolledByManager.RolledByEntry> entries = new HashMap<>();
        if (element == null || !element.isJsonObject())
        {
            return entries;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet())
        {
            try
            {
                int id = Integer.parseInt(entry.getKey());
                RolledByManager.RolledByEntry value = gson.fromJson(entry.getValue(), RolledByManager.RolledByEntry.class);
                if (id > 0 && value != null)
                {
                    entries.put(id, value);
                }
            }
            catch (Exception ignored) {}
        }
        return entries;
    }

    private Collection<RollHistoryManager.RollEvent> readRollHistory(JsonElement element)
    {
        List<RollHistoryManager.RollEvent> events = new ArrayList<>();
        if (element == null || !element.isJsonObject())
        {
            return events;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet())
        {
            try
            {
                RollHistoryManager.RollEvent event = gson.fromJson(entry.getValue(), RollHistoryManager.RollEvent.class);
                if (event != null && event.getEventId() != null && event.getRolledItemId() > 0)
                {
                    events.add(event);
                }
            }
            catch (Exception ignored) {}
        }
        events.sort(Comparator.comparingLong(RollHistoryManager.RollEvent::getAt));
        if (events.size() > RollHistoryManager.MAX_EVENTS)
        {
            return new ArrayList<>(events.subList(events.size() - RollHistoryManager.MAX_EVENTS, events.size()));
        }
        return events;
    }

    private void processLiveRolls(JsonElement element)
    {
        if (element == null || !element.isJsonObject())
        {
            return;
        }

        List<FirebaseRollEvent> events = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet())
        {
            try
            {
                FirebaseRollEvent event = gson.fromJson(entry.getValue(), FirebaseRollEvent.class);
                if (event != null && !isBlank(event.getEventId()))
                {
                    events.add(event);
                }
            }
            catch (Exception ignored) {}
        }
        events.sort(FirebaseRollEvent::compareForPlayback);

        long now = System.currentTimeMillis();
        RollListener listener = rollListener;
        for (FirebaseRollEvent event : events)
        {
            boolean recent = event.getCreatedAt() > 0 && now - event.getCreatedAt() <= LIVE_EVENT_MAX_AGE_MS;
            String id = event.getEventId();

            if (event.getChosenItemId() <= 0)
            {
                if (recent && remember(observedStarts, id) && listener != null)
                {
                    listener.onRollStarted(event);
                }
                else if (!recent)
                {
                    deleteLiveRoll(id, generation);
                }
                continue;
            }

            // A client can miss the short-lived start update and first observe the completed event.
            // Reconstruct the animation from the completed payload, then deliver the pick. The overlay
            // queues early picks until this event reaches the front of its local animation queue.
            if (recent && listener != null && !observedStarts.contains(id) && remember(observedStarts, id))
            {
                listener.onRollStarted(event);
            }
            if (recent && remember(observedPicks, id) && listener != null)
            {
                listener.onRollPicked(event);
            }
            else if (!recent)
            {
                remember(observedPicks, id);
            }

            long pickedAt = event.getPickedAt();
            if (pickedAt > 0 && now - pickedAt > LIVE_EVENT_MAX_AGE_MS)
            {
                deleteLiveRoll(id, generation);
            }
        }
    }

    private void processResolvedHistory(JsonElement element)
    {
        if (element == null || !element.isJsonObject())
        {
            return;
        }

        RollListener listener = rollListener;
        if (listener == null)
        {
            return;
        }

        long now = System.currentTimeMillis();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet())
        {
            String id = entry.getKey();
            if (!observedStarts.contains(id) || observedPicks.contains(id))
            {
                continue;
            }

            try
            {
                RollHistoryManager.RollEvent history = gson.fromJson(entry.getValue(), RollHistoryManager.RollEvent.class);
                if (history == null || history.getRolledItemId() <= 0)
                {
                    continue;
                }
                // Only use recent history as a live-UI recovery path. Old history must never replay.
                if (history.getAt() <= 0 || now - history.getAt() > LIVE_EVENT_MAX_AGE_MS)
                {
                    continue;
                }
                if (!remember(observedPicks, id))
                {
                    continue;
                }

                FirebaseRollEvent resolved = new FirebaseRollEvent(
                        id,
                        history.getAt(),
                        history.getBy(),
                        history.getObtainedItemId(),
                        history.isManual(),
                        Collections.emptyList())
                        .withPick(history.getRolledItemId(), history.getAt());
                listener.onRollPicked(resolved);
            }
            catch (Exception ignored) {}
        }
    }

    private void pruneRemoteHistory(JsonElement element)
    {
        if (element == null || !element.isJsonObject())
        {
            return;
        }

        class HistoryKey
        {
            final String id;
            final long at;

            HistoryKey(String id, long at)
            {
                this.id = id;
                this.at = at;
            }
        }

        List<HistoryKey> history = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet())
        {
            try
            {
                RollHistoryManager.RollEvent event = gson.fromJson(entry.getValue(), RollHistoryManager.RollEvent.class);
                if (event != null)
                {
                    history.add(new HistoryKey(entry.getKey(), event.getAt()));
                }
            }
            catch (Exception ignored) {}
        }

        if (history.size() <= RollHistoryManager.MAX_EVENTS)
        {
            return;
        }

        history.sort(Comparator.comparingLong((HistoryKey key) -> key.at).thenComparing(key -> key.id));
        int removeCount = history.size() - RollHistoryManager.MAX_EVENTS;
        JsonObject deletions = new JsonObject();
        for (int i = 0; i < removeCount; i++)
        {
            deletions.add("rollHistory/" + history.get(i).id, com.google.gson.JsonNull.INSTANCE);
        }
        submitPersistentPatch("roll history pruning", deletions, generation, 0);
    }

    private boolean remember(Set<String> set, String value)
    {
        boolean added = set.add(value);
        if (set.size() > MAX_SEEN_LIVE_EVENTS)
        {
            // Exact ordering is unimportant; these are only short-lived de-duplication sets.
            int remove = set.size() - MAX_SEEN_LIVE_EVENTS;
            for (String existing : new ArrayList<>(set))
            {
                if (remove-- <= 0)
                {
                    break;
                }
                set.remove(existing);
            }
        }
        return added;
    }

    private void submitPersistentPatch(String description, JsonObject updates, long expectedGeneration, int attempt)
    {
        String url = activeDatabaseUrl;
        ScheduledExecutorService current = executor;
        if (!running || url == null || expectedGeneration != generation || current == null || current.isShutdown())
        {
            return;
        }

        databaseClient.patch(url, "/", updates).whenComplete((ignored, error) -> {
            if (!running || expectedGeneration != generation)
            {
                return;
            }
            if (error == null)
            {
                setStatus(Status.CONNECTED, null);
                // The SSE echo carries the authoritative delta back into the in-memory mirror.
                return;
            }

            String message = conciseMessage(error);
            setStatus(Status.ERROR, message);
            int nextAttempt = attempt + 1;
            long delaySeconds = Math.min(30L, 1L << Math.min(nextAttempt, 5));
            log.warn("Firebase write failed for {}; retrying in {}s", description, delaySeconds, error);

            ScheduledExecutorService retryExecutor = executor;
            if (retryExecutor != null && !retryExecutor.isShutdown())
            {
                schedule(retryExecutor,
                        () -> submitPersistentPatch(description, updates, expectedGeneration, nextAttempt),
                        delaySeconds,
                        TimeUnit.SECONDS);
            }
        });
    }


    private void schedule(ScheduledExecutorService current, Runnable task, long delay, TimeUnit unit)
    {
        ScheduledFuture<?> future = current.schedule(task, delay, unit);
        scheduledTasks.add(future);
        scheduledTasks.removeIf(done -> done.isDone() || done.isCancelled());
    }

    private void cancelScheduledTasks()
    {
        for (ScheduledFuture<?> future : scheduledTasks)
        {
            future.cancel(false);
        }
        scheduledTasks.clear();
    }

    private void deleteLiveRoll(String eventId, long expectedGeneration)
    {
        String url = activeDatabaseUrl;
        if (!running || url == null || expectedGeneration != generation || isBlank(eventId))
        {
            return;
        }
        databaseClient.delete(url, "/liveRolls/" + eventId).whenComplete((ignored, error) -> {
            if (error != null && running && expectedGeneration == generation)
            {
                log.debug("Could not clean up live roll {}", eventId, error);
            }
        });
    }

    private void clearSharedState()
    {
        obtainedItemsManager.clear();
        rolledItemsManager.clear();
        rolledByManager.clear();
        rollHistoryManager.clear();
    }

    private void setStatus(Status next, String error)
    {
        boolean changed = status != next || (lastError == null ? error != null : !lastError.equals(error));
        status = next;
        lastError = error;
        if (changed)
        {
            Runnable listener = statusListener;
            if (listener != null)
            {
                listener.run();
            }
        }
    }

    private static String normalisePlayerName(String name)
    {
        return name == null || name.trim().isEmpty() ? "Unknown" : name.trim();
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }

    private static String conciseMessage(Throwable error)
    {
        Throwable current = error;
        while (current.getCause() != null)
        {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.trim().isEmpty())
        {
            return current.getClass().getSimpleName();
        }
        return message.length() > 140 ? message.substring(0, 140) + "…" : message;
    }
}
