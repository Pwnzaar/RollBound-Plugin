package com.rollbound.account;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.runelite.client.util.Filepath;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local-only account guard for RollBound.
 *
 * Enabled RuneScape account hashes are stored in the plugin's local data directory.
 * Nothing from this class is written to Firebase, and only the account currently
 * logged into this RuneLite client can be enabled or disabled.
 */
@Singleton
public final class AccountGuardManager
{
    private static final String FILE_NAME = "account-guard.properties";

    private final Client client;
    private final Properties enabledAccounts = new Properties();
    private final Object fileIoLock = new Object();
    private Filepath storageFile;
    private ExecutorService ioExecutor;

    @Inject
    public AccountGuardManager(Client client)
    {
        this.client = client;
    }

    public synchronized void setDataDirectory(Filepath dataDirectory)
    {
        enabledAccounts.clear();
        storageFile = null;

        if (dataDirectory == null)
        {
            return;
        }

        try
        {
            dataDirectory.createDirectories();
            storageFile = dataDirectory.joinSegment(FILE_NAME);
            load();
        }
        catch (IOException ignored)
        {
            storageFile = null;
        }
    }

    public long getCurrentAccountHash()
    {
        if (client == null || client.getGameState() != GameState.LOGGED_IN)
        {
            return -1L;
        }
        return client.getAccountHash();
    }

    public String getCurrentDisplayName()
    {
        if (client == null || client.getGameState() != GameState.LOGGED_IN)
        {
            return null;
        }

        Player player = client.getLocalPlayer();
        if (player == null)
        {
            return null;
        }

        String name = player.getName();
        return name == null || name.trim().isEmpty() ? null : name.trim();
    }

    public synchronized boolean isCurrentAccountEnabled()
    {
        long hash = getCurrentAccountHash();
        return hash != -1L && enabledAccounts.containsKey(Long.toString(hash));
    }

    public synchronized boolean enableCurrentAccount()
    {
        long hash = getCurrentAccountHash();
        if (hash == -1L)
        {
            return false;
        }

        enabledAccounts.setProperty(Long.toString(hash), displayNameForStorage());
        save();
        return true;
    }

    public synchronized boolean disableCurrentAccount()
    {
        long hash = getCurrentAccountHash();
        if (hash == -1L)
        {
            return false;
        }

        Object removed = enabledAccounts.remove(Long.toString(hash));
        if (removed != null)
        {
            save();
            return true;
        }
        return false;
    }

    /** Keep the local display label current without changing authorization. */
    public synchronized void refreshCurrentDisplayName()
    {
        long hash = getCurrentAccountHash();
        if (hash == -1L)
        {
            return;
        }

        String key = Long.toString(hash);
        if (!enabledAccounts.containsKey(key))
        {
            return;
        }

        String name = displayNameForStorage();
        String previous = enabledAccounts.getProperty(key);
        if (!name.equals(previous))
        {
            enabledAccounts.setProperty(key, name);
            saveAsync();
        }
    }

    private String displayNameForStorage()
    {
        String name = getCurrentDisplayName();
        return name == null ? "Enabled account" : name;
    }

    private void load() throws IOException
    {
        if (storageFile == null || !storageFile.exists())
        {
            return;
        }

        try (BufferedReader reader = storageFile.openBufferedReader())
        {
            enabledAccounts.load(reader);
        }
    }

    private void save()
    {
        Filepath file = storageFile;
        if (file == null)
        {
            return;
        }

        Properties snapshot = new Properties();
        snapshot.putAll(enabledAccounts);
        saveProperties(file, snapshot);
    }

    private synchronized ExecutorService ensureExecutor()
    {
        if (ioExecutor == null || ioExecutor.isShutdown() || ioExecutor.isTerminated())
        {
            ioExecutor = Executors.newSingleThreadExecutor(
                    new ThreadFactoryBuilder().setNameFormat("rollbound-account-guard-%d").setDaemon(true).build());
        }
        return ioExecutor;
    }

    private void saveAsync()
    {
        Filepath file = storageFile;
        if (file == null)
        {
            return;
        }

        Properties snapshot = new Properties();
        snapshot.putAll(enabledAccounts);
        ensureExecutor().execute(() -> saveProperties(file, snapshot));
    }

    private void saveProperties(Filepath file, Properties values)
    {
        synchronized (fileIoLock)
        {
            try (BufferedWriter writer = file.openBufferedWriter(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE))
            {
                values.store(writer, "RollBound local account guard");
            }
            catch (IOException ignored)
            {
                // Fail closed for future sessions if the local guard cannot be persisted.
            }
        }
    }

    public synchronized void shutdown()
    {
        if (ioExecutor != null)
        {
            ioExecutor.shutdownNow();
            ioExecutor = null;
        }
    }

}
