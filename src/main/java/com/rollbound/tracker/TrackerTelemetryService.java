package com.rollbound.tracker;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rollbound.RollBoundConfig;
import com.rollbound.drops.DropItem;
import com.rollbound.drops.DropTableSection;
import com.rollbound.drops.NpcDropData;
import com.rollbound.firebase.FirebaseDatabaseClient;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Realtime player progression + tracker source publisher.
 *
 * Snapshots are hash-deduplicated and debounced. The Firebase key is a group-specific
 * SHA-256 pseudonym derived from the account hash, so the raw account hash is never uploaded.
 */
@Singleton
public final class TrackerTelemetryService
{
    private static final Logger log = LoggerFactory.getLogger(TrackerTelemetryService.class);
    private static final long DEBOUNCE_MS = 750L;
    private static final long KEEPALIVE_SECONDS = 60L;
    private static final String HUNTER_RUMOURS_KEY = "trackerHunterRumoursCompleted";

    private final Client client;
    private final ClientThread clientThread;
    private final RollBoundConfig config;
    private final ConfigManager configManager;
    private final FirebaseDatabaseClient databaseClient;
    private final PlayerProgressCollector collector;
    private final Gson gson;

    private final AtomicBoolean queued = new AtomicBoolean(false);
    private final Set<ScheduledFuture<?>> scheduledTasks = ConcurrentHashMap.newKeySet();
    private volatile ScheduledExecutorService executor;
    private volatile boolean running;
    private volatile String lastFingerprint;
    private volatile String activePlayerKey;
    private volatile String activeDatabaseUrl;

    @Inject
    public TrackerTelemetryService(Client client, ClientThread clientThread, RollBoundConfig config,
                                   ConfigManager configManager, FirebaseDatabaseClient databaseClient,
                                   PlayerProgressCollector collector, Gson gson)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.configManager = configManager;
        this.databaseClient = databaseClient;
        this.collector = collector;
        this.gson = gson;
    }

    public synchronized void start()
    {
        stop();
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RollBound-Tracker-Telemetry");
            t.setDaemon(true);
            return t;
        });
        scheduledTasks.add(executor.scheduleWithFixedDelay(
                () -> requestSync(true), 5, KEEPALIVE_SECONDS, TimeUnit.SECONDS));
        requestSync(true);
    }

    public synchronized void stop()
    {
        running = false;
        queued.set(false);
        publishOfflineBestEffort();
        cancelScheduledTasks();
        if (executor != null)
        {
            executor.shutdownNow();
            executor = null;
        }
        lastFingerprint = null;
        activePlayerKey = null;
        activeDatabaseUrl = null;
    }

    public void requestSync()
    {
        requestSync(false);
    }

    public void requestSync(boolean force)
    {
        ScheduledExecutorService current = executor;
        if (!running || current == null || current.isShutdown()) return;
        if (!force && !queued.compareAndSet(false, true)) return;
        if (force) queued.set(true);
        ScheduledFuture<?> future = current.schedule(() -> {
            queued.set(false);
            collectAndPublish(force);
        }, force ? 50L : DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        scheduledTasks.add(future);
        scheduledTasks.removeIf(done -> done.isDone() || done.isCancelled());
    }

    private void collectAndPublish(boolean force)
    {
        if (!running) return;
        clientThread.invokeLater(() -> {
            try
            {
                String databaseUrl = databaseClient.normaliseDatabaseUrl(config.firebaseDatabaseUrl());
                if (databaseUrl == null || client.getGameState() != GameState.LOGGED_IN || client.getAccountHash() == -1)
                {
                    return;
                }
                PlayerProgressSnapshot snapshot = collector.collect(getHunterRumoursCompleted());
                if (snapshot == null) return;
                String playerKey = playerKey(databaseUrl, client.getAccountHash());
                JsonObject tree = snapshot.toFirebaseJson();
                JsonObject fingerprintTree = tree.deepCopy();
                fingerprintTree.remove("updatedAt");
                String fingerprint = gson.toJson(fingerprintTree);
                if (!force && playerKey.equals(activePlayerKey) && databaseUrl.equals(activeDatabaseUrl)
                        && fingerprint.equals(lastFingerprint))
                {
                    return;
                }
                activePlayerKey = playerKey;
                activeDatabaseUrl = databaseUrl;
                lastFingerprint = fingerprint;
                databaseClient.patch(databaseUrl, "players/" + playerKey, tree)
                        .exceptionally(error -> {
                            log.warn("Could not publish RollBound tracker progress: {}", error.getMessage());
                            return null;
                        });
            }
            catch (Exception error)
            {
                log.debug("Could not collect RollBound tracker progress", error);
            }
        });
    }

    public void setHunterRumoursCompleted(int count)
    {
        try
        {
            configManager.setRSProfileConfiguration("rollbound", HUNTER_RUMOURS_KEY, Math.max(0, count));
        }
        catch (Exception ignored) {}
        requestSync();
    }

    private int getHunterRumoursCompleted()
    {
        try
        {
            Integer value = configManager.getRSProfileConfiguration("rollbound", HUNTER_RUMOURS_KEY, Integer.class);
            return value == null ? 0 : Math.max(0, value);
        }
        catch (Exception ignored)
        {
            return 0;
        }
    }

    public void publishNpcDropData(NpcDropData data)
    {
        if (!running || data == null || data.getName() == null || data.getDropTableSections() == null) return;
        String databaseUrl = databaseClient.normaliseDatabaseUrl(config.firebaseDatabaseUrl());
        if (databaseUrl == null) return;

        JsonObject npc = new JsonObject();
        npc.addProperty("npcId", data.getNpcId());
        npc.addProperty("name", data.getName());
        npc.addProperty("level", data.getLevel());
        npc.addProperty("updatedAt", System.currentTimeMillis());
        JsonObject drops = new JsonObject();
        for (DropTableSection section : data.getDropTableSections())
        {
            if (section == null || section.getItems() == null) continue;
            for (DropItem item : section.getItems())
            {
                if (item == null || item.getItemId() <= 0) continue;
                JsonObject drop = new JsonObject();
                drop.addProperty("itemId", item.getItemId());
                drop.addProperty("name", item.getName());
                drop.addProperty("rarity", item.getRarity());
                drop.addProperty("section", section.getHeader());
                drops.add(Integer.toString(item.getItemId()), drop);
            }
        }
        npc.add("drops", drops);
        int npcKey = data.getNpcId() > 0 ? data.getNpcId() : Math.abs(data.getName().toLowerCase().hashCode());
        databaseClient.patch(databaseUrl, "trackerSources/npcs/" + npcKey, npc)
                .exceptionally(error -> {
                    log.debug("Could not publish RollBound NPC source data", error);
                    return null;
                });
    }

    public void publishOfflineBestEffort()
    {
        try
        {
            String databaseUrl = activeDatabaseUrl;
            String key = activePlayerKey;
            if (databaseUrl == null || key == null) return;
            JsonObject offline = new JsonObject();
            offline.addProperty("online", false);
            offline.addProperty("updatedAt", System.currentTimeMillis());
            databaseClient.patch(databaseUrl, "players/" + key, offline);
        }
        catch (Exception ignored) {}
    }

    private void cancelScheduledTasks()
    {
        for (ScheduledFuture<?> future : scheduledTasks)
        {
            future.cancel(false);
        }
        scheduledTasks.clear();
    }

    static String playerKey(String databaseUrl, long accountHash)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((databaseUrl + ":" + accountHash).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("p_");
            for (int i = 0; i < 12; i++)
            {
                out.append(String.format("%02x", bytes[i] & 0xff));
            }
            return out.toString();
        }
        catch (Exception ignored)
        {
            return "p_" + Long.toUnsignedString(accountHash);
        }
    }
}
