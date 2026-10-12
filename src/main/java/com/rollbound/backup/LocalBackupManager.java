package com.rollbound.backup;

import com.google.gson.Gson;
import com.rollbound.managers.ObtainedItemsManager;
import com.rollbound.managers.RollHistoryManager;
import com.rollbound.managers.RolledByManager;
import com.rollbound.managers.RolledItemsManager;
import net.runelite.client.util.Filepath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates a complete local RollBound backup after every completed roll.
 *
 * All file access is rooted in RuneLite's plugin-data directory via {@link Filepath}.
 * The plugin descriptor migrates the legacy .runelite/Rollbound directory on first use,
 * so existing Backups folders are preserved.
 */
@Singleton
public final class LocalBackupManager
{
    private static final Logger log = LoggerFactory.getLogger(LocalBackupManager.class);

    private static final int MAX_BACKUPS = 30;
    private static final String BACKUP_FOLDER_PATTERN = "'RB Backup ('dd-MM-yyyy') ('HH-mm-ss')'";

    private static final String ROLLED_FILE = "rollbound_rolled.json";
    private static final String OBTAINED_FILE = "rollbound_obtained.json";
    private static final String ROLLED_BY_FILE = "rollbound_rolled_by.json";
    private static final String HISTORY_FILE = "rollbound_roll_history.json";

    private final RolledItemsManager rolledItemsManager;
    private final ObtainedItemsManager obtainedItemsManager;
    private final RolledByManager rolledByManager;
    private final RollHistoryManager rollHistoryManager;
    private final Gson gson;

    private volatile Filepath pluginDataDirectory;

    /** Event ids already backed up during this RuneLite session. */
    private final Set<String> backedEventIds = new HashSet<>();
    private boolean baselineEstablished;

    @Inject
    public LocalBackupManager(
            RolledItemsManager rolledItemsManager,
            ObtainedItemsManager obtainedItemsManager,
            RolledByManager rolledByManager,
            RollHistoryManager rollHistoryManager,
            Gson gson)
    {
        this.rolledItemsManager = rolledItemsManager;
        this.obtainedItemsManager = obtainedItemsManager;
        this.rolledByManager = rolledByManager;
        this.rollHistoryManager = rollHistoryManager;
        this.gson = gson.newBuilder().setPrettyPrinting().create();
    }

    /** Called by RollBoundPlugin after RuneLite provides the plugin-scoped data directory. */
    public synchronized void setDataDirectory(Filepath pluginDataDirectory)
    {
        this.pluginDataDirectory = pluginDataDirectory == null ? null : pluginDataDirectory.rooted();
    }

    /**
     * Reset de-duplication when Firebase is reconnected/switched. The first authoritative
     * snapshot after this call becomes the baseline and does not generate historical backups.
     */
    public synchronized void resetSession()
    {
        backedEventIds.clear();
        baselineEstablished = false;
    }

    public synchronized void onAuthoritativeSnapshotApplied(Collection<RollHistoryManager.RollEvent> events)
    {
        List<RollHistoryManager.RollEvent> snapshot = sanitiseEvents(events);

        if (!baselineEstablished)
        {
            for (RollHistoryManager.RollEvent event : snapshot)
            {
                backedEventIds.add(event.getEventId());
            }
            baselineEstablished = true;
            return;
        }

        for (RollHistoryManager.RollEvent event : snapshot)
        {
            String eventId = event.getEventId();
            if (eventId != null && !eventId.trim().isEmpty() && !backedEventIds.contains(eventId))
            {
                backupCompletedRoll(eventId);
            }
        }
    }

    private synchronized boolean backupCompletedRoll(String eventId)
    {
        String id = eventId == null ? "" : eventId.trim();
        if (id.isEmpty() || backedEventIds.contains(id))
        {
            return false;
        }

        Filepath folder = null;
        try
        {
            Filepath backupRoot = backupRoot();
            backupRoot.createDirectories();

            folder = uniqueBackupFolder(backupRoot, new Date());
            folder.createDirectory();

            LinkedHashSet<Integer> rolled = new LinkedHashSet<>(rolledItemsManager.getRolledItems());
            LinkedHashSet<Integer> obtained = new LinkedHashSet<>(obtainedItemsManager.getObtainedItems());
            Map<Integer, RolledByManager.RolledByEntry> rolledBy = rolledByManager.getSnapshot();
            List<RollHistoryManager.RollEvent> history = rollHistoryManager.getSnapshot();

            writeJsonNew(folder.joinSegment(ROLLED_FILE), rolled);
            writeJsonNew(folder.joinSegment(OBTAINED_FILE), obtained);
            writeJsonNew(folder.joinSegment(ROLLED_BY_FILE), rolledBy);
            writeJsonNew(folder.joinSegment(HISTORY_FILE), history);

            verifyCompleteBackup(folder);

            backedEventIds.add(id);
            pruneOldBackups(backupRoot);
            log.debug("RollBound local backup created: {}", folder);
            return true;
        }
        catch (Exception error)
        {
            if (folder != null)
            {
                try
                {
                    folder.deleteRecursively();
                }
                catch (Exception cleanupError)
                {
                    log.warn("Could not remove incomplete RollBound backup folder {}", folder, cleanupError);
                }
            }

            String destination;
            try
            {
                destination = folder == null ? backupRoot().toString() : folder.toString();
            }
            catch (Exception ignored)
            {
                destination = "plugin data directory";
            }
            log.error("Could not create RollBound local backup for roll {} at {}", id, destination, error);
            return false;
        }
    }

    private Filepath backupRoot() throws IOException
    {
        Filepath root = pluginDataDirectory;
        if (root == null)
        {
            throw new IOException("RollBound plugin data directory has not been initialised");
        }
        return root.joinSegment("Backups");
    }

    private Filepath uniqueBackupFolder(Filepath root, Date now) throws IOException
    {
        String base = new SimpleDateFormat(BACKUP_FOLDER_PATTERN).format(now);
        Filepath candidate = root.joinSegment(base);
        if (!candidate.exists())
        {
            return candidate;
        }

        for (int suffix = 2; suffix < 1000; suffix++)
        {
            candidate = root.joinSegment(base + " (" + suffix + ")");
            if (!candidate.exists())
            {
                return candidate;
            }
        }
        throw new IOException("Could not allocate a unique RollBound backup folder");
    }

    private void writeJsonNew(Filepath target, Object value) throws IOException
    {
        try (BufferedWriter writer = target.openBufferedWriter(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE))
        {
            gson.toJson(value, writer);
        }
    }

    private void verifyCompleteBackup(Filepath folder) throws IOException
    {
        String[] names = {ROLLED_FILE, OBTAINED_FILE, ROLLED_BY_FILE, HISTORY_FILE};
        for (String name : names)
        {
            Filepath file = folder.joinSegment(name);
            if (!file.isFile() || file.size() <= 0L)
            {
                throw new IOException("Incomplete RollBound backup: " + file);
            }
        }
    }

    private void pruneOldBackups(Filepath root) throws IOException
    {
        List<Filepath> folders = new ArrayList<>();
        try (java.util.stream.Stream<Filepath> stream = root.walk(1))
        {
            stream.filter(path -> !path.equals(root))
                    .filter(Filepath::isDirectory)
                    .filter(path -> path.getFileName().startsWith("RB Backup ("))
                    .forEach(folders::add);
        }

        folders.sort(Comparator
                .comparingLong(LocalBackupManager::lastModifiedSafe)
                .reversed()
                .thenComparing(Filepath::getFileName, Comparator.reverseOrder()));

        for (int i = MAX_BACKUPS; i < folders.size(); i++)
        {
            folders.get(i).deleteRecursively();
        }
    }

    private static long lastModifiedSafe(Filepath path)
    {
        try
        {
            return path.getLastModifiedTime().toMillis();
        }
        catch (IOException ignored)
        {
            return 0L;
        }
    }

    private static List<RollHistoryManager.RollEvent> sanitiseEvents(Collection<RollHistoryManager.RollEvent> events)
    {
        List<RollHistoryManager.RollEvent> result = new ArrayList<>();
        if (events == null)
        {
            return result;
        }
        for (RollHistoryManager.RollEvent event : events)
        {
            if (event != null && event.getEventId() != null && !event.getEventId().trim().isEmpty())
            {
                result.add(event);
            }
        }
        result.sort(Comparator.comparingLong(RollHistoryManager.RollEvent::getAt));
        return result;
    }
}
