package com.rollbound.drops;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import com.rollbound.account.AccountManager;
import com.rollbound.tracker.TrackerTelemetryService;
import net.runelite.client.util.Filepath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Persistent drop-table cache backed by JSON files in RuneLite's plugin-data directory.
 * All file access goes through {@link Filepath}; stale cache data is disposable and rebuilt
 * from the Old School RuneScape Wiki when needed.
 */
@Singleton
public class DropCache
{
    private static final Logger log = LoggerFactory.getLogger(DropCache.class);
    private static final Duration MAX_AGE = Duration.ofDays(7);

    private final Gson gson;
    private final AccountManager accountManager;
    private final DropFetcher dropFetcher;
    private final TrackerTelemetryService trackerTelemetryService;

    private final Map<Filepath, Object> writeLocks = new ConcurrentHashMap<>();
    private final Map<Filepath, NpcDropData> cache = new ConcurrentHashMap<>();
    private final Map<String, Filepath> nameIndex = new ConcurrentHashMap<>();
    private volatile boolean indexLoaded;
    private volatile Filepath pluginDataDirectory;

    // Dedicated IO executor so file operations never run on the RuneLite client thread.
    private ExecutorService ioExecutor;

    @Inject
    public DropCache(Gson gson, AccountManager accountManager, DropFetcher dropFetcher,
                     TrackerTelemetryService trackerTelemetryService)
    {
        this.gson = gson;
        this.accountManager = accountManager;
        this.dropFetcher = dropFetcher;
        this.trackerTelemetryService = trackerTelemetryService;
    }

    /** Called by RollBoundPlugin after RuneLite provides the plugin-scoped data directory. */
    public synchronized void setDataDirectory(Filepath pluginDataDirectory)
    {
        this.pluginDataDirectory = pluginDataDirectory == null ? null : pluginDataDirectory.rooted();
        resetIndex();
    }

    /** Preload on-disk index and prune stale cache entries off the RuneLite UI/client threads. */
    public void startUp()
    {
        ExecutorService executor = ensureExecutor();
        String player = accountManager.getPlayerName();
        if (player == null || player.isEmpty())
        {
            return;
        }

        executor.execute(() ->
        {
            loadIndex();
            pruneOldCaches();
        });
    }

    /** Reset the in-memory cache when the RuneScape account changes. */
    public synchronized void reloadForAccount()
    {
        resetIndex();
        startUp();
    }

    private void resetIndex()
    {
        cache.clear();
        nameIndex.clear();
        writeLocks.clear();
        indexLoaded = false;
    }

    /**
     * Load from disk if possible; otherwise fetch from the wiki, write JSON, and return the data.
     * Results without drop-table sections are discarded and never cached.
     */
    public CompletableFuture<NpcDropData> get(int npcId, String name, int level)
    {
        final String safeName = safeFilePart(name);
        ExecutorService executor = ensureExecutor();

        return CompletableFuture.supplyAsync(() ->
        {
            loadIndex();

            final Filepath file;
            try
            {
                file = npcId == 0
                        ? findExistingCacheFile(safeName, level)
                        : getCacheFile(npcId, name, level);
            }
            catch (IOException ex)
            {
                throw new CompletionException(ex);
            }

            if (file != null)
            {
                NpcDropData cached = cache.get(file);
                if (cached != null && file.exists() && isFresh(file))
                {
                    return cached;
                }

                try
                {
                    file.deleteIfExists();
                }
                catch (IOException ignored) { }
                removeIndex(file);
            }
            return null;
        }, executor).thenComposeAsync(cached ->
        {
            if (cached != null)
            {
                trackerTelemetryService.publishNpcDropData(cached);
                return CompletableFuture.completedFuture(cached);
            }

            return dropFetcher.fetch(npcId, name, level)
                    .thenApplyAsync(data ->
                    {
                        if (data == null || data.getDropTableSections() == null || data.getDropTableSections().isEmpty())
                        {
                            return null;
                        }

                        try
                        {
                            Filepath out = getCacheFile(data.getNpcId(), data.getName(), data.getLevel());
                            out.getParent().createDirectories();
                            String json = gson.toJson(data);

                            Object lock = writeLocks.computeIfAbsent(out, ignored -> new Object());
                            synchronized (lock)
                            {
                                Filepath tmp = out.getParent().createTempFile("rb-drop-", ".tmp");
                                try
                                {
                                    tmp.write(json,
                                            StandardOpenOption.CREATE,
                                            StandardOpenOption.TRUNCATE_EXISTING,
                                            StandardOpenOption.WRITE);
                                    try
                                    {
                                        tmp.moveTo(out,
                                                StandardCopyOption.REPLACE_EXISTING,
                                                StandardCopyOption.ATOMIC_MOVE);
                                    }
                                    catch (AtomicMoveNotSupportedException unsupported)
                                    {
                                        tmp.moveTo(out, StandardCopyOption.REPLACE_EXISTING);
                                    }
                                }
                                finally
                                {
                                    writeLocks.remove(out);
                                    try { tmp.deleteIfExists(); } catch (IOException ignored) { }
                                }
                            }

                            cache.put(out, data);
                            nameIndex.put(buildNameKey(data.getName(), data.getLevel()), out);

                            if (npcId == 0 && data.getNpcId() != 0)
                            {
                                Filepath old = findExistingCacheFile(safeName, data.getLevel());
                                if (old != null && !old.equals(out))
                                {
                                    old.deleteIfExists();
                                    removeIndex(old);
                                }
                            }
                        }
                        catch (Exception e)
                        {
                            log.error("Failed to write cache file for {}", name, e);
                        }

                        trackerTelemetryService.publishNpcDropData(data);
                        return data;
                    }, executor);
        }, executor).exceptionally(ex ->
        {
            log.error("Error loading drop data for NPC {}", npcId, ex);
            return null;
        });
    }

    /** Fetch all exact Wiki NPC variants for a name and optional combat level. */
    public CompletableFuture<List<NpcDropData>> searchNpcVariants(String name, int level)
    {
        return dropFetcher.fetchVariants(name, level)
                .thenApply(results ->
                {
                    if (results != null)
                    {
                        for (NpcDropData data : results)
                        {
                            trackerTelemetryService.publishNpcDropData(data);
                        }
                    }
                    return results == null ? new ArrayList<NpcDropData>() : results;
                })
                .exceptionally(ex ->
                {
                    log.error("Error fetching NPC variants for {} (lvl {})", name, level, ex);
                    return new ArrayList<NpcDropData>();
                });
    }

    public CompletableFuture<Collection<NpcDropData>> getAllNpcDataAsync()
    {
        ExecutorService executor = ensureExecutor();
        return CompletableFuture.supplyAsync(() ->
        {
            loadIndex();
            return new ArrayList<>(cache.values());
        }, executor);
    }

    public CompletableFuture<List<String>> searchNpcNames(String query)
    {
        ExecutorService executor = ensureExecutor();
        return CompletableFuture.supplyAsync(() ->
        {
            String lc = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
            loadIndex();

            Set<String> names = cache.values().stream()
                    .map(NpcDropData::getName)
                    .filter(Objects::nonNull)
                    .filter(name -> name.toLowerCase(Locale.ROOT).contains(lc))
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            try
            {
                names.addAll(dropFetcher.searchNpcNames(query));
            }
            catch (Exception ex)
            {
                log.debug("Wiki search failed for {}", query, ex);
            }

            return new ArrayList<>(names);
        }, executor);
    }

    private boolean isFresh(Filepath file)
    {
        try
        {
            Instant cutoff = Instant.now().minus(MAX_AGE);
            return file.getLastModifiedTime().toInstant().isAfter(cutoff);
        }
        catch (IOException e)
        {
            return false;
        }
    }

    private Filepath findExistingCacheFile(String safeName, int level) throws IOException
    {
        String key = safeName + "_" + level;
        Filepath file = nameIndex.get(key);
        if (file != null && file.exists())
        {
            if (isFresh(file))
            {
                return file;
            }
            file.deleteIfExists();
            removeIndex(file);
        }
        return null;
    }

    private Filepath getCacheDir() throws IOException
    {
        Filepath root = pluginDataDirectory;
        if (root == null)
        {
            throw new IOException("RollBound plugin data directory has not been initialised");
        }

        String player = accountManager.getPlayerName();
        if (player == null || player.trim().isEmpty())
        {
            throw new IOException("Player name is not available");
        }

        Filepath dir = root.joinSegment("drops-v2").joinSegment(safeSegment(player));
        dir.createDirectories();
        return dir;
    }

    private Filepath getCacheFile(int npcId, String name, int level) throws IOException
    {
        Filepath dir = getCacheDir();
        String fileName = npcId + "_" + safeFilePart(name) + "_" + level + ".json";
        return dir.joinSegment(fileName);
    }

    private void pruneOldCaches()
    {
        final Filepath dir;
        try
        {
            dir = getCacheDir();
        }
        catch (IOException ignored)
        {
            return;
        }

        Instant cutoff = Instant.now().minus(MAX_AGE);
        try (Stream<Filepath> files = dir.walk(1))
        {
            files.filter(path -> !path.equals(dir))
                    .filter(Filepath::isFile)
                    .forEach(path ->
                    {
                        try
                        {
                            if (path.getLastModifiedTime().toInstant().isBefore(cutoff))
                            {
                                path.deleteIfExists();
                                removeIndex(path);
                            }
                        }
                        catch (IOException ex)
                        {
                            log.debug("Failed to delete old drop cache {}", path, ex);
                        }
                    });
        }
        catch (IOException ex)
        {
            log.debug("Error pruning drop cache directory {}", dir, ex);
        }
    }

    public CompletableFuture<Void> clearAllCachesAsync()
    {
        return CompletableFuture.runAsync(this::clearAllCaches, ensureExecutor());
    }

    private void clearAllCaches()
    {
        final Filepath dir;
        try
        {
            dir = getCacheDir();
        }
        catch (IOException ignored)
        {
            resetIndex();
            indexLoaded = true;
            return;
        }

        try (Stream<Filepath> files = dir.walk(1))
        {
            files.filter(path -> !path.equals(dir))
                    .filter(Filepath::isFile)
                    .forEach(path ->
                    {
                        try
                        {
                            path.deleteIfExists();
                        }
                        catch (IOException ex)
                        {
                            log.debug("Failed to delete drop cache {}", path, ex);
                        }
                    });
        }
        catch (IOException ex)
        {
            log.debug("Error clearing drop cache directory {}", dir, ex);
        }

        resetIndex();
        indexLoaded = true;
    }

    private void removeIndex(Filepath path)
    {
        NpcDropData data = cache.remove(path);
        if (data != null)
        {
            nameIndex.remove(buildNameKey(data.getName(), data.getLevel()));
        }
    }

    private void loadIndex()
    {
        if (indexLoaded)
        {
            return;
        }

        synchronized (this)
        {
            if (indexLoaded)
            {
                return;
            }

            try
            {
                Filepath dir = getCacheDir();
                try (Stream<Filepath> files = dir.walk(1))
                {
                    for (Filepath path : files
                            .filter(file -> !file.equals(dir))
                            .filter(Filepath::isFile)
                            .collect(Collectors.toList()))
                    {
                        if (!isFresh(path))
                        {
                            path.deleteIfExists();
                            continue;
                        }

                        try (BufferedReader reader = path.openBufferedReader())
                        {
                            NpcDropData data = gson.fromJson(reader, NpcDropData.class);
                            if (data != null && data.getDropTableSections() != null && !data.getDropTableSections().isEmpty())
                            {
                                cache.put(path, data);
                                nameIndex.put(buildNameKey(data.getName(), data.getLevel()), path);
                            }
                            else
                            {
                                path.deleteIfExists();
                            }
                        }
                        catch (Exception e)
                        {
                            log.warn("Skipping bad cache file {}", path, e);
                            path.deleteIfExists();
                        }
                    }
                }
            }
            catch (IOException e)
            {
                log.debug("Error loading cache index", e);
            }

            indexLoaded = true;
        }
    }

    public synchronized void shutdown()
    {
        ExecutorService executor = ioExecutor;
        if (executor != null)
        {
            executor.shutdownNow();
            ioExecutor = null;
        }
        resetIndex();
    }

    private synchronized ExecutorService ensureExecutor()
    {
        if (ioExecutor == null || ioExecutor.isShutdown() || ioExecutor.isTerminated())
        {
            ioExecutor = Executors.newFixedThreadPool(
                    2,
                    new ThreadFactoryBuilder().setNameFormat("rollbound-dropcache-%d").setDaemon(true).build());
        }
        return ioExecutor;
    }

    private static String buildNameKey(String name, int level)
    {
        return safeFilePart(name) + "_" + level;
    }

    private static String safeFilePart(String value)
    {
        String safe = value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "_");
        safe = safe.replaceAll("_+", "_");
        return safe.isEmpty() ? "unknown" : safe;
    }

    private static String safeSegment(String value)
    {
        String safe = value == null ? "" : value.replaceAll("[^A-Za-z0-9 _-]", "_").trim();
        return safe.isEmpty() ? "unknown" : safe;
    }
}
