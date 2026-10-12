package com.rollbound.managers;

import javax.inject.Singleton;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Tracks the first player/time associated with each group unlock. */
@Singleton
public class RolledByManager
{
    public static final class RolledByEntry
    {
        private String by;
        private long at;

        public RolledByEntry() {}

        public RolledByEntry(String by, long at)
        {
            this.by = by;
            this.at = at;
        }

        public String getBy() { return by; }
        public long getAt() { return at; }
    }

    private final Map<Integer, RolledByEntry> rolledBy = Collections.synchronizedMap(new HashMap<>());

    private volatile Runnable onChange;

    public void setOnChange(Runnable onChange)
    {
        this.onChange = onChange;
    }

    public RolledByEntry getEntry(int itemId)
    {
        return rolledBy.get(itemId);
    }

    public Map<Integer, RolledByEntry> getSnapshot()
    {
        synchronized (rolledBy)
        {
            return new HashMap<>(rolledBy);
        }
    }

    /** Keep the earliest attribution for an item. */
    public boolean recordRoll(int itemId, String by, long atMillis)
    {
        if (itemId <= 0)
        {
            return false;
        }

        String who = by == null || by.trim().isEmpty() ? "Unknown" : by.trim();
        long at = atMillis > 0 ? atMillis : System.currentTimeMillis();
        boolean changed = false;

        synchronized (rolledBy)
        {
            RolledByEntry existing = rolledBy.get(itemId);
            if (existing == null || existing.getAt() <= 0 || at < existing.getAt())
            {
                rolledBy.put(itemId, new RolledByEntry(who, at));
                changed = true;
            }
        }

        if (changed)
        {
            notifyChange();
        }
        return changed;
    }

    public void replaceAll(Map<Integer, RolledByEntry> entries)
    {
        Map<Integer, RolledByEntry> next = new HashMap<>();
        if (entries != null)
        {
            for (Map.Entry<Integer, RolledByEntry> entry : entries.entrySet())
            {
                Integer id = entry.getKey();
                RolledByEntry value = entry.getValue();
                if (id != null && id > 0 && value != null)
                {
                    next.put(id, value);
                }
            }
        }

        boolean changed;
        synchronized (rolledBy)
        {
            changed = !sameEntries(rolledBy, next);
            if (changed)
            {
                rolledBy.clear();
                rolledBy.putAll(next);
            }
        }

        if (changed)
        {
            notifyChange();
        }
    }

    public void clear()
    {
        replaceAll(Collections.emptyMap());
    }

    private static boolean sameEntries(Map<Integer, RolledByEntry> a, Map<Integer, RolledByEntry> b)
    {
        if (a.size() != b.size())
        {
            return false;
        }
        for (Map.Entry<Integer, RolledByEntry> entry : a.entrySet())
        {
            RolledByEntry other = b.get(entry.getKey());
            RolledByEntry value = entry.getValue();
            if (other == null || value == null)
            {
                return false;
            }
            if (value.getAt() != other.getAt())
            {
                return false;
            }
            String left = value.getBy();
            String right = other.getBy();
            if (left == null ? right != null : !left.equals(right))
            {
                return false;
            }
        }
        return true;
    }

    private void notifyChange()
    {
        Runnable callback = onChange;
        if (callback != null)
        {
            callback.run();
        }
    }
}
